package com.aetheria.concurrent;

/**
 * A unit of background work ordered by how urgently the player needs its result.
 *
 * <p>Priority is the squared distance from the camera to the chunk the task belongs to, so the
 * terrain about to come into view is always built before the terrain behind the player. Ordering
 * the queue this way is what lets a player turn around without waiting for a backlog of tasks for
 * chunks they can no longer see.
 */
public final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {

    private final Runnable action;
    private final long priority;
    private final long sequence;

    /**
     * @param action   the work to run
     * @param priority lower runs first; use the squared chunk distance from the camera
     * @param sequence a monotonically increasing value, used to break ties in submission order so
     *                 that equally distant chunks are processed fairly
     */
    public PrioritizedTask(Runnable action, long priority, long sequence) {
        this.action = action;
        this.priority = priority;
        this.sequence = sequence;
    }

    public long priority() {
        return priority;
    }

    @Override
    public void run() {
        action.run();
    }

    @Override
    public int compareTo(PrioritizedTask other) {
        int byPriority = Long.compare(priority, other.priority);
        return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
    }
}
