package com.pixelmosaic.admission;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class AdmissionQueue {

    public interface Job {
        void run();

        void onQueuePosition(int position);
    }

    private final int maxConcurrent;
    private final int maxQueued;
    private final Executor executor;
    private final Deque<Job> waiting = new ArrayDeque<>();
    private int running;

    public AdmissionQueue(int maxConcurrent, int maxQueued, Executor executor) {
        this.maxConcurrent = maxConcurrent;
        this.maxQueued = maxQueued;
        this.executor = executor;
    }

    public boolean submit(Job job) {
        boolean startNow;
        synchronized (this) {
            if (running < maxConcurrent) {
                running++;
                startNow = true;
            } else if (waiting.size() < maxQueued) {
                waiting.addLast(job);
                startNow = false;
            } else {
                return false;
            }
        }
        if (startNow) {
            start(job);
        } else {
            notifyPositions();
        }
        return true;
    }

    public void cancel(Job job) {
        boolean removed;
        synchronized (this) {
            removed = waiting.remove(job);
        }
        if (removed) {
            notifyPositions();
        }
    }

    private void start(Job job) {
        try {
            executor.execute(() -> {
                try {
                    job.run();
                } finally {
                    release();
                }
            });
        } catch (RejectedExecutionException e) {
            release();
            throw e;
        }
    }

    private void release() {
        Job next;
        synchronized (this) {
            next = waiting.pollFirst();
            if (next == null) {
                running--;
            }
        }
        if (next != null) {
            start(next);
            notifyPositions();
        }
    }

    private void notifyPositions() {
        List<Job> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(waiting);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            snapshot.get(i).onQueuePosition(i + 1);
        }
    }
}
