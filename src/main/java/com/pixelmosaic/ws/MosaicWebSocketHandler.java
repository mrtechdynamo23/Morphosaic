package com.pixelmosaic.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelmosaic.admission.AdmissionQueue;
import com.pixelmosaic.admission.RateLimiterService;
import com.pixelmosaic.pipeline.MosaicPipeline;
import com.pixelmosaic.pipeline.MosaicResult;
import com.pixelmosaic.stats.UsageStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * WebSocket protocol for one mosaic request:
 * <ol>
 *   <li>client sends a {@code begin_request} JSON control frame (declared sizes + format);</li>
 *   <li>server replies {@code accepted} with a request id;</li>
 *   <li>client sends the source image as one binary frame, then the target image;</li>
 *   <li>server replies {@code processing}, or {@code queued} with the client's position (repeated
 *       as it changes) followed by {@code processing}, or {@code rejected} if the line is full;</li>
 *   <li>server streams a 32-byte binary header, the particle payload in 256&nbsp;KB chunks, and a
 *       final {@code complete} JSON frame.</li>
 * </ol>
 *
 * Admission is two-layered: per-IP hourly rate limiting at connect time, and a bounded FIFO
 * queue at processing time. Only processing holds a slot; streaming the result does not.
 */
@Component
public class MosaicWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MosaicWebSocketHandler.class);

    /** Binary protocol header: magic bytes "MOS\1". */
    private static final int MAGIC = 0x4D4F5301;
    private static final int PROTOCOL_VERSION = 1;
    private static final int HEADER_BYTES = 32;

    private static final int SEND_TIME_LIMIT_MS = 30_000;

    private static final String ATTR_STATE = "state";
    private static final String ATTR_SOURCE = "sourceBytes";
    private static final String ATTR_TARGET = "targetBytes";
    private static final String ATTR_REQUEST_ID = "requestId";
    private static final String ATTR_OUT = "out";
    private static final String ATTR_JOB = "job";

    private static final Set<String> ALLOWED_FORMATS =
            Set.of("image/jpeg", "image/png", "image/webp");

    enum State {AWAITING_BEGIN, AWAITING_SOURCE, AWAITING_TARGET, PROCESSING}

    private final AdmissionQueue admissionQueue;
    private final RateLimiterService rateLimiter;
    private final MosaicPipeline pipeline;
    private final ExecutorService streamExecutor;
    private final ObjectMapper objectMapper;
    private final int chunkSize;
    private final long maxImageBytes;
    private final int trustedProxyHops;
    private final UsageStats usageStats;

    public MosaicWebSocketHandler(AdmissionQueue admissionQueue,
                                  RateLimiterService rateLimiter,
                                  MosaicPipeline pipeline,
                                  UsageStats usageStats,
                                  @Qualifier("streamExecutor") ExecutorService streamExecutor,
                                  ObjectMapper objectMapper,
                                  @Value("${pixelmosaic.chunk-size-bytes}") int chunkSize,
                                  @Value("${pixelmosaic.max-image-bytes}") long maxImageBytes,
                                  @Value("${pixelmosaic.trusted-proxy-hops}") int trustedProxyHops) {
        this.admissionQueue = admissionQueue;
        this.rateLimiter = rateLimiter;
        this.pipeline = pipeline;
        this.usageStats = usageStats;
        this.streamExecutor = streamExecutor;
        this.objectMapper = objectMapper;
        this.chunkSize = chunkSize;
        this.maxImageBytes = maxImageBytes;
        this.trustedProxyHops = Math.max(0, trustedProxyHops);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.getAttributes().put(ATTR_OUT, new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, 4 * chunkSize));

        String ip = clientIp(session);
        if (ip == null) {
            log.warn("Connection {}: no client IP resolved; skipping rate limit", session.getId());
        } else if (!rateLimiter.tryAcquire(ip)) {
            log.info("Connection {} from {} rejected: rate limit exceeded", session.getId(), ip);
            session.close(new CloseStatus(1008, "rate_limit_exceeded"));
            return;
        }
        session.getAttributes().put(ATTR_STATE, State.AWAITING_BEGIN);
        log.info("Connection {} from {}", session.getId(), ip);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (state(session) != State.AWAITING_BEGIN) {
            sendJson(session, Map.of("type", "error", "reason", "unexpected_message"));
            silentClose(session);
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (IOException e) {
            sendJson(session, Map.of("type", "error", "reason", "invalid_json"));
            silentClose(session);
            return;
        }

        String type = root.path("type").asText("");
        long sourceBytes = root.path("source_bytes").asLong(-1);
        long targetBytes = root.path("target_bytes").asLong(-1);
        String sourceFormat = root.path("source_format").asText("");
        String targetFormat = root.path("target_format").asText("");

        String rejection = validateBegin(type, sourceBytes, targetBytes, sourceFormat, targetFormat);
        if (rejection != null) {
            sendJson(session, Map.of("type", "error", "reason", rejection));
            silentClose(session);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        session.getAttributes().put(ATTR_REQUEST_ID, requestId);
        session.getAttributes().put(ATTR_STATE, State.AWAITING_SOURCE);
        sendJson(session, Map.of("type", "accepted", "request_id", requestId));
    }

    private String validateBegin(String type, long sourceBytes, long targetBytes,
                                 String sourceFormat, String targetFormat) {
        if (!"begin_request".equals(type)) {
            return "expected_begin_request";
        }
        if (sourceBytes <= 0 || sourceBytes > maxImageBytes) {
            return "source_too_large";
        }
        if (targetBytes <= 0 || targetBytes > maxImageBytes) {
            return "target_too_large";
        }
        if (!ALLOWED_FORMATS.contains(sourceFormat)) {
            return "unsupported_source_format";
        }
        if (!ALLOWED_FORMATS.contains(targetFormat)) {
            return "unsupported_target_format";
        }
        return null;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        State state = state(session);
        byte[] bytes = toByteArray(message.getPayload());

        switch (state) {
            case AWAITING_SOURCE -> {
                session.getAttributes().put(ATTR_SOURCE, bytes);
                session.getAttributes().put(ATTR_STATE, State.AWAITING_TARGET);
            }
            case AWAITING_TARGET -> {
                session.getAttributes().put(ATTR_TARGET, bytes);
                session.getAttributes().put(ATTR_STATE, State.PROCESSING);
                enqueue(session);
            }
            default -> session.close(new CloseStatus(1002, "unexpected_binary"));
        }
    }

    private void enqueue(WebSocketSession session) {
        MosaicJob job = new MosaicJob(session);
        session.getAttributes().put(ATTR_JOB, job);
        if (!admissionQueue.submit(job)) {
            dropImages(session);
            sendJson(session, Map.of("type", "rejected", "reason", "queue_full"));
            silentClose(session);
        }
    }

    private final class MosaicJob implements AdmissionQueue.Job {

        private final WebSocketSession session;
        private boolean started;
        private int lastPosition = Integer.MAX_VALUE;

        MosaicJob(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public synchronized void onQueuePosition(int position) {
            if (started || position >= lastPosition) {
                return;
            }
            lastPosition = position;
            sendJson(session, Map.of("type", "queued", "position", position));
        }

        @Override
        public void run() {
            synchronized (this) {
                started = true;
            }
            try {
                byte[] src = (byte[]) session.getAttributes().get(ATTR_SOURCE);
                byte[] tgt = (byte[]) session.getAttributes().get(ATTR_TARGET);
                if (!session.isOpen() || src == null || tgt == null) {
                    return;
                }
                sendJson(session, Map.of("type", "processing"));
                MosaicResult result = pipeline.process(src, tgt);
                usageStats.recordProcessed();
                streamExecutor.execute(() -> {
                    try {
                        streamPayload(session, result);
                    } catch (Exception e) {
                        log.info("Streaming to session {} aborted: {}", session.getId(), e.getMessage());
                        silentClose(session);
                    }
                });
            } catch (Exception e) {
                reportFailure(session, e);
            } finally {
                dropImages(session);
            }
        }
    }

    private void reportFailure(WebSocketSession session, Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof IllegalArgumentException || cause instanceof IOException) {
            log.warn("Rejected image for session {}: {}", session.getId(), cause.getMessage());
            sendJson(session, Map.of("type", "error", "reason", "invalid_image"));
        } else {
            log.error("Processing failed for session {}: {}", session.getId(), e.getMessage(), e);
            sendJson(session, Map.of("type", "error", "reason", "processing_failed"));
        }
        silentClose(session);
    }

    private void streamPayload(WebSocketSession session, MosaicResult result) throws IOException {
        WebSocketSession out = out(session);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC);
        header.putInt(PROTOCOL_VERSION);
        header.putInt(result.particleCount());
        header.putInt(result.srcWidth());
        header.putInt(result.srcHeight());
        header.putInt(result.tgtWidth());
        header.putInt(result.tgtHeight());
        header.putInt(0); // reserved
        header.flip();
        out.sendMessage(new BinaryMessage(header));

        ByteBuffer payload = result.payload();
        while (payload.hasRemaining()) {
            int size = Math.min(chunkSize, payload.remaining());
            byte[] chunk = new byte[size];
            payload.get(chunk);
            out.sendMessage(new BinaryMessage(chunk));
        }

        sendJson(session, Map.of("type", "complete", "particle_count", result.particleCount()));
        log.info("Streamed {} particles ({} MB) to session {}",
                result.particleCount(),
                String.format("%.2f", result.particleCount() * 12 / 1_048_576.0),
                session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getAttributes().get(ATTR_JOB) instanceof MosaicJob job) {
            admissionQueue.cancel(job);
        }
        dropImages(session);
        log.info("Session {} closed: {}", session.getId(), status);
    }

    private static State state(WebSocketSession session) {
        return (State) session.getAttributes().get(ATTR_STATE);
    }

    private static WebSocketSession out(WebSocketSession session) {
        Object out = session.getAttributes().get(ATTR_OUT);
        return out instanceof WebSocketSession decorated ? decorated : session;
    }

    private static void dropImages(WebSocketSession session) {
        session.getAttributes().remove(ATTR_SOURCE);
        session.getAttributes().remove(ATTR_TARGET);
    }

    private void sendJson(WebSocketSession session, Map<String, ?> data) {
        try {
            out(session).sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            // Client may have disconnected mid-exchange; nothing useful to do.
        }
    }

    private static void silentClose(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static byte[] toByteArray(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    private String clientIp(WebSocketSession session) {
        if (trustedProxyHops == 0) {
            InetSocketAddress remote = session.getRemoteAddress();
            return remote != null && remote.getAddress() != null
                    ? rateLimitKey(remote.getAddress())
                    : null;
        }
        List<String> headers = session.getHandshakeHeaders().get("X-Forwarded-For");
        if (headers == null) {
            return null;
        }
        List<String> hops = headers.stream()
                .flatMap(h -> Arrays.stream(h.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return hops.isEmpty() ? null : hops.get(Math.max(0, hops.size() - trustedProxyHops));
    }

    static String rateLimitKey(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            return String.format("%x:%x:%x:%x::/64",
                    ((b[0] & 0xFF) << 8) | (b[1] & 0xFF), ((b[2] & 0xFF) << 8) | (b[3] & 0xFF),
                    ((b[4] & 0xFF) << 8) | (b[5] & 0xFF), ((b[6] & 0xFF) << 8) | (b[7] & 0xFF));
        }
        return address.getHostAddress();
    }
}
