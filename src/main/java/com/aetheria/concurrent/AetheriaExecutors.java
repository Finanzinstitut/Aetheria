package com.aetheria.concurrent;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The background thread pools Aetheria runs its work on.
 *
 * <p>Nothing that can stall belongs on the render thread. Two separate pools are used because the
 * two kinds of work have opposite characteristics:
 *
 * <ul>
 *   <li>The <b>mesh pool</b> is CPU bound. It is sized from the available cores, leaving headroom
 *       for the game's own render and server threads so that meshing never starves them.
 *   <li>The <b>I/O pool</b> is latency bound and mostly blocked on the disk. It is small and
 *       deliberately separate, so a slow disk can never occupy a core that meshing needs.
 * </ul>
 *
 * <p>Both pools run at below-normal thread priority and use daemon threads, so a hung task can
 * never keep the game from exiting, and background work always yields to the frame loop under
 * contention.
 */
public final class AetheriaExecutors {

    private final ThreadPoolExecutor meshPool;
    private final ThreadPoolExecutor ioPool;
    private final AtomicLong sequence = new AtomicLong();

    /**
     * Creates the pools.
     *
     * @param meshThreads number of meshing threads, or zero to size from the available cores
     * @param ioThreads   number of I/O threads, or zero for a sensible default
     */
    public AetheriaExecutors(int meshThreads, int ioThreads) {
        int cores = Runtime.getRuntime().availableProcessors();
        int mesh = meshThreads > 0 ? meshThreads : Math.max(1, cores - 2);
        int io = ioThreads > 0 ? ioThreads : Math.max(1, Math.min(4, cores / 4));

        this.meshPool = newPool(mesh, "Aetheria LOD Builder");
        this.ioPool = newPool(io, "Aetheria LOD I/O");
    }

    /** Returns the number of meshing threads. */
    public int meshThreadCount() {
        return meshPool.getCorePoolSize();
    }

    /** Returns the number of I/O threads. */
    public int ioThreadCount() {
        return ioPool.getCorePoolSize();
    }

    /** Returns the number of meshing tasks waiting to run, for the debug overlay. */
    public int pendingMeshTasks() {
        return meshPool.getQueue().size();
    }

    /** Returns the number of I/O tasks waiting to run, for the debug overlay. */
    public int pendingIoTasks() {
        return ioPool.getQueue().size();
    }

    /**
     * Submits LOD meshing work.
     *
     * @param priority lower runs first; pass the squared chunk distance from the camera
     * @return {@code true} if the task was accepted, {@code false} if the pool is shutting down
     */
    public boolean submitMesh(Runnable task, long priority) {
        return submit(meshPool, task, priority);
    }

    /**
     * Submits cache read or write work.
     *
     * @param priority lower runs first; pass the squared chunk distance from the camera
     * @return {@code true} if the task was accepted, {@code false} if the pool is shutting down
     */
    public boolean submitIo(Runnable task, long priority) {
        return submit(ioPool, task, priority);
    }

    /** Discards every queued task that has not started yet, on world unload or a settings change. */
    public void drainQueues() {
        meshPool.getQueue().clear();
        ioPool.getQueue().clear();
    }

    /**
     * Shuts both pools down and waits briefly for in-flight work to finish.
     *
     * <p>Pending tasks are dropped: everything they would produce is a cache of derived data that
     * the next session rebuilds, so waiting for the queue to drain on exit would only delay the
     * player for no benefit.
     */
    public void shutdown() {
        meshPool.shutdownNow();
        ioPool.shutdownNow();
        awaitTermination(meshPool);
        awaitTermination(ioPool);
    }

    private boolean submit(ThreadPoolExecutor pool, Runnable task, long priority) {
        try {
            pool.execute(new PrioritizedTask(task, priority, sequence.getAndIncrement()));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private static void awaitTermination(ThreadPoolExecutor pool) {
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ThreadPoolExecutor newPool(int threads, String namePrefix) {
        // PriorityBlockingQueue holds Runnables; every task submitted here is a PrioritizedTask,
        // which is Comparable, so the raw-typed queue is safe in practice.
        PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(64,
                (a, b) -> ((PrioritizedTask) a).compareTo((PrioritizedTask) b));

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong counter = new AtomicLong();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, namePrefix + " #" + counter.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            }
        };

        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                queue, factory);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
