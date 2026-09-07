package com.company.orderapi.concurrency;

import java.util.concurrent.locks.ReentrantLock;

/**
 * PR #30 - why {@link ReentrantLock} beats a {@code synchronized} block.
 *
 * <p>A shared mutable sequence is the classic example: guarded with
 * {@code synchronized} the lock is opaque (you cannot see or influence its
 * behaviour). The {@link ReentrantLock} version is explicit and observable:
 * this one is FAIR (waiters are served first-come, first-served) and releases
 * reliably in a finally block. Fairness matters when many threads queue on a
 * hot counter and you want no thread to starve.
 *
 * <p>All access (reads included) goes through the same lock so the counter is
 * always seen in a consistent, linearisable order.
 */
public final class FairSequenceAllocator {

    private final ReentrantLock lock = new ReentrantLock(true);
    private long value;

    /** Allocates the next strictly-increasing sequence number. */
    public long next() {
        lock.lock();
        try {
            return ++value;
        } finally {
            lock.unlock();
        }
    }

    public long current() {
        lock.lock();
        try {
            return value;
        } finally {
            lock.unlock();
        }
    }
}
