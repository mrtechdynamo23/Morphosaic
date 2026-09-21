package com.pixelmosaic.stats;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UsageStats {

    private final AtomicLong processed = new AtomicLong();
    private final Instant since = Instant.now();

    public void recordProcessed() {
        processed.incrementAndGet();
    }

    public long processed() {
        return processed.get();
    }

    public Instant since() {
        return since;
    }
}
