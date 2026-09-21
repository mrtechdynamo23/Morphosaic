package com.pixelmosaic.admission;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionQueueTest {

    @Test
    void queuesBeyondConcurrencyAndRejectsBeyondQueue() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AdmissionQueue queue = new AdmissionQueue(2, 2, executor);
            List<TestJob> jobs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                jobs.add(new TestJob());
            }

            assertTrue(queue.submit(jobs.get(0)));
            assertTrue(queue.submit(jobs.get(1)));
            assertTrue(jobs.get(0).started.await(5, TimeUnit.SECONDS));
            assertTrue(jobs.get(1).started.await(5, TimeUnit.SECONDS));

            assertTrue(queue.submit(jobs.get(2)));
            assertTrue(queue.submit(jobs.get(3)));
            assertFalse(queue.submit(jobs.get(4)), "line is full");

            assertEquals(1, jobs.get(2).lastPosition());
            assertEquals(2, jobs.get(3).lastPosition());

            jobs.get(0).finish.countDown();
            assertTrue(jobs.get(2).started.await(5, TimeUnit.SECONDS));
            awaitPosition(jobs.get(3), 1);

            jobs.forEach(j -> j.finish.countDown());
            assertTrue(jobs.get(3).started.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelledJobNeverRunsAndLineMovesUp() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AdmissionQueue queue = new AdmissionQueue(1, 5, executor);
            TestJob running = new TestJob();
            TestJob leaver = new TestJob();
            TestJob stayer = new TestJob();

            queue.submit(running);
            assertTrue(running.started.await(5, TimeUnit.SECONDS));
            queue.submit(leaver);
            queue.submit(stayer);
            assertEquals(2, stayer.lastPosition());

            queue.cancel(leaver);
            assertEquals(1, stayer.lastPosition());

            running.finish.countDown();
            assertTrue(stayer.started.await(5, TimeUnit.SECONDS));
            assertEquals(1, leaver.started.getCount(), "cancelled job must not run");
            stayer.finish.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitPosition(TestJob job, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (job.lastPosition() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, job.lastPosition());
    }

    private static final class TestJob implements AdmissionQueue.Job {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final List<Integer> positions = new CopyOnWriteArrayList<>();

        @Override
        public void run() {
            started.countDown();
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onQueuePosition(int position) {
            positions.add(position);
        }

        int lastPosition() {
            return positions.isEmpty() ? -1 : positions.get(positions.size() - 1);
        }
    }
}
