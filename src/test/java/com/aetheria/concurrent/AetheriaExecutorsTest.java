package com.aetheria.concurrent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies task ordering and shutdown behaviour of the background pools. */
class AetheriaExecutorsTest {

    @Test
    void poolsAreSizedAndReportTheirQueues() {
        AetheriaExecutors executors = new AetheriaExecutors(2, 1);
        try {
            assertEquals(2, executors.meshThreadCount());
            assertEquals(1, executors.ioThreadCount());
            assertEquals(0, executors.pendingMeshTasks());
        } finally {
            executors.shutdown();
        }
    }

    @Test
    void zeroThreadCountsAreSizedFromTheMachine() {
        AetheriaExecutors executors = new AetheriaExecutors(0, 0);
        try {
            assertTrue(executors.meshThreadCount() >= 1);
            assertTrue(executors.ioThreadCount() >= 1);
        } finally {
            executors.shutdown();
        }
    }

    @Test
    void nearerWorkRunsBeforeFartherWork() throws InterruptedException {
        // A single thread makes the ordering observable: the first task blocks the pool while the
        // rest of the queue is filled, so they can only be drained in priority order.
        AetheriaExecutors executors = new AetheriaExecutors(1, 1);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(4);
            List<Long> order = new CopyOnWriteArrayList<>();

            executors.submitMesh(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            }, 0L);

            for (long priority : new long[] {900L, 100L, 400L}) {
                executors.submitMesh(() -> {
                    order.add(priority);
                    done.countDown();
                }, priority);
            }

            gate.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "tasks did not finish in time");
            assertEquals(List.of(100L, 400L, 900L), order);
        } finally {
            executors.shutdown();
        }
    }

    @Test
    void submittingAfterShutdownIsRefusedRatherThanThrowing() {
        AetheriaExecutors executors = new AetheriaExecutors(1, 1);
        executors.shutdown();

        assertTrue(!executors.submitMesh(() -> { }, 0L));
        assertTrue(!executors.submitIo(() -> { }, 0L));
    }

    @Test
    void drainingClearsQueuedWork() throws InterruptedException {
        AetheriaExecutors executors = new AetheriaExecutors(1, 1);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            executors.submitMesh(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, 0L);
            for (int i = 0; i < 20; i++) {
                executors.submitMesh(() -> { }, 10L);
            }

            executors.drainQueues();
            assertEquals(0, executors.pendingMeshTasks());
            gate.countDown();
        } finally {
            executors.shutdown();
        }
    }
}
