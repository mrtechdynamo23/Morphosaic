package com.pixelmosaic.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelmosaic.config.ModelLoader;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end WebSocket round trip against a real embedded server. Requires the ONNX model on
 * the classpath; if it is absent the whole class is disabled (the context can't start without
 * the {@code OrtSession} bean). Synthetic PNGs keep the payload small and inference fast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pixelmosaic.admin-token=test-token")
@EnabledIf("modelAvailable")
class WebSocketIntegrationTest {

    private static final int MAGIC = 0x4D4F5301;

    @LocalServerPort
    private int port;

    static boolean modelAvailable() {
        return ModelLoader.isAvailable();
    }

    @Test
    void fullRoundTrip() throws Exception {
        byte[] source = pngImage(96, 96, true);
        byte[] target = pngImage(300, 300, false);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(20 * 1024 * 1024);
        StandardWebSocketClient client = new StandardWebSocketClient(container);

        String wsUrl = "ws://localhost:" + port + "/ws/mosaic";
        TestClientHandler handler = new TestClientHandler(source, target);
        WebSocketSession session = client.execute(handler, wsUrl).get(10, TimeUnit.SECONDS);
        try {
            assertTrue(handler.done.await(30, TimeUnit.SECONDS), "no terminal frame received");
            assertEquals("complete", handler.terminalType, "expected a completion, not an error");
            assertEquals(MAGIC, handler.headerMagic, "binary header magic mismatch");
            assertTrue(handler.particleCount > 0, "particle_count should be > 0");
            assertEquals((long) handler.particleCount * 12, handler.payloadBytes,
                    "payload bytes should equal particleCount * 12");
            assertEquals((handler.payloadBytes + 262_143) / 262_144, handler.payloadFrames,
                    "payload should arrive as one message per 256 KB chunk");
            assertTrue(handler.payloadFrames > 1, "test payload should span several chunks");
        } finally {
            session.close();
        }

        HttpClient http = HttpClient.newHttpClient();
        URI statsUri = URI.create("http://localhost:" + port + "/admin/stats");

        HttpResponse<String> anonymous = http.send(
                HttpRequest.newBuilder(statsUri).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, anonymous.statusCode(), "stats must be hidden without the token");

        HttpResponse<String> wrong = http.send(
                HttpRequest.newBuilder(statsUri).header("X-Admin-Token", "nope").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, wrong.statusCode(), "stats must be hidden with a wrong token");

        HttpResponse<String> authorized = http.send(
                HttpRequest.newBuilder(statsUri).header("X-Admin-Token", "test-token").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authorized.statusCode());
        JsonNode stats = new ObjectMapper().readTree(authorized.body());
        assertTrue(stats.path("processed").asLong() >= 1, "processed count should include this run");
    }

    private static byte[] pngImage(int w, int h, boolean circle) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(circle ? Color.RED : Color.BLUE);
        if (circle) {
            g.fillOval(w / 4, h / 4, w / 2, h / 2);
        } else {
            g.fillRect(w / 4, h / 4, w / 2, h / 2);
        }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /** Drives the protocol from the client side and records what comes back. */
    private static final class TestClientHandler extends AbstractWebSocketHandler {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final byte[] source;
        private final byte[] target;

        final CountDownLatch done = new CountDownLatch(1);
        volatile String terminalType;
        volatile int headerMagic;
        volatile int particleCount;
        volatile long payloadBytes;
        volatile int payloadFrames;
        private boolean headerSeen;

        TestClientHandler(byte[] source, byte[] target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            String begin = objectMapper.writeValueAsString(Map.of(
                    "type", "begin_request",
                    "source_bytes", source.length,
                    "target_bytes", target.length,
                    "source_format", "image/png",
                    "target_format", "image/png"));
            session.sendMessage(new TextMessage(begin));
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText();
            switch (type) {
                case "accepted" -> {
                    session.sendMessage(new BinaryMessage(source));
                    session.sendMessage(new BinaryMessage(target));
                }
                case "processing", "queued" -> {
                }
                case "complete" -> {
                    particleCount = node.path("particle_count").asInt();
                    terminalType = "complete";
                    done.countDown();
                }
                default -> {
                    terminalType = type;
                    done.countDown();
                }
            }
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            ByteBuffer payload = message.getPayload();
            if (!headerSeen) {
                headerSeen = true;
                headerMagic = payload.getInt();
            } else {
                payloadBytes += payload.remaining();
                payloadFrames++;
            }
        }
    }
}
