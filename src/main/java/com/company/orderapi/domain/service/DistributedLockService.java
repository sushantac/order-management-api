package com.company.orderapi.domain.service;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * PR #30 - DISTRIBUTED lock backed by Redis (Redisson).
 *
 * <p>A JVM-local lock only serialises threads in ONE process; when several API
 * instances share a database you need a lock all of them can see. Redisson
 * implements the standard Redis lock algorithm (SET with NX+PX, safe release
 * via a token, auto-expiry via the lease time so a crashed holder never
 * deadlocks the system).
 *
 * <p>{@code runExclusive} acquires the lock for {@code key}, runs the task and
 * ALWAYS releases the lock - including when the task throws.
 */
@Service
public class DistributedLockService {

    private final RedissonClient redisson;

    public DistributedLockService(@Lazy RedissonClient redisson) {
        this.redisson = redisson;
    }

    public <T> T runExclusive(String key, Duration waitTime, Duration leaseTime,
                              Supplier<T> task) {
        RLock lock = redisson.getLock("app-lock:" + key);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring lock " + key, e);
        }
        if (!acquired) {
            throw new IllegalStateException(
                    "Could not acquire distributed lock within " + waitTime + ": " + key);
        }
        try {
            return task.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
