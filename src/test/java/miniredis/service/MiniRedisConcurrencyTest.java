package miniredis.service;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MiniRedisConcurrencyTest {
    private static final long TIMEOUT_SECONDS = 5;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredGetDoesNotDeleteAConcurrentSet(boolean setWithTtl) throws Exception {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(start);
        AtomicBoolean pauseNextClockRead = new AtomicBoolean();
        CountDownLatch checkingExpiration = new CountDownLatch(1);
        CountDownLatch finishExpirationCheck = new CountDownLatch(1);
        InstantSource time = () -> {
            if (pauseNextClockRead.getAndSet(false)) {
                checkingExpiration.countDown();
                await(finishExpirationCheck);
            }
            return now.get();
        };
        MiniRedisService service = new MiniRedisService(16, time.withZone(ZoneOffset.UTC));
        service.set("key", "expired", Duration.ofSeconds(1));
        now.set(start.plusSeconds(1));
        pauseNextClockRead.set(true);

        FutureTask<String> get = new FutureTask<>(() -> service.get("key"));
        FutureTask<Void> set = new FutureTask<>(() -> {
            if (setWithTtl) {
                service.set("key", "fresh", Duration.ofSeconds(10));
            } else {
                service.set("key", "fresh");
            }
            return null;
        });
        Thread.ofPlatform().daemon().start(get);
        try {
            // GET has read the old entry and is now checking its expiration.
            await(checkingExpiration);
            Thread writer = Thread.ofPlatform().daemon().start(set);
            awaitLockWaitOrCompletion(writer, set);
        } finally {
            finishExpirationCheck.countDown();
        }

        assertNull(get.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        set.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("fresh", service.get("key"));
    }

    @Test
    void concurrentDelReportsExactlyOneSuccessfulDeletion() throws Exception {
        MiniRedisService service = new MiniRedisService(16);
        service.set("key", "value");
        AtomicInteger deleted = new AtomicInteger();

        runConcurrently(8, worker -> {
            if (service.del("key")) {
                deleted.incrementAndGet();
            }
        });

        assertEquals(1, deleted.get());
        assertNull(service.get("key"));
    }

    @Test
    void concurrentCommandsPreserveDifferentKeysInTheSameBucket() throws Exception {
        MiniRedisService service = new MiniRedisService(1);
        int workerCount = 8;
        int keysPerWorker = 40;

        runConcurrently(workerCount, worker -> {
            for (int index = 0; index < keysPerWorker; index++) {
                String key = worker + "-" + index;
                service.set(key, key);
                assertEquals(key, service.get(key));
                if (index % 2 != 0) {
                    assertTrue(service.del(key));
                }
            }
        });

        for (int worker = 0; worker < workerCount; worker++) {
            for (int index = 0; index < keysPerWorker; index++) {
                String key = worker + "-" + index;
                assertEquals(index % 2 == 0 ? key : null, service.get(key), key);
            }
        }
    }

    @Test
    void failedSetReleasesTheLockForAnotherThread() throws Exception {
        MiniRedisService service = new MiniRedisService(16);

        assertThrows(IllegalArgumentException.class,
                () -> service.set("key", "invalid", Duration.ZERO));

        FutureTask<String> nextCommand = new FutureTask<>(() -> {
            service.set("key", "valid");
            return service.get("key");
        });
        Thread.ofPlatform().daemon().start(nextCommand);

        assertEquals("valid", nextCommand.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void reentrantOwnerKeepsTheLockUntilTheLastUnlock() throws Exception {
        CasLock lock = new CasLock();
        CountDownLatch releasedOnce = new CountDownLatch(1);
        CountDownLatch releaseLastHold = new CountDownLatch(1);
        FutureTask<Void> owner = new FutureTask<>(() -> {
            lock.lock();
            try {
                lock.lock();
                lock.unlock();
                releasedOnce.countDown();
                await(releaseLastHold);
            } finally {
                lock.unlock();
            }
            return null;
        });
        FutureTask<Boolean> contender = new FutureTask<>(() -> {
            Thread.currentThread().interrupt();
            lock.lock();
            try {
                return Thread.currentThread().isInterrupted();
            } finally {
                lock.unlock();
            }
        });

        Thread.ofPlatform().daemon().start(owner);
        try {
            await(releasedOnce);
            assertThrows(IllegalMonitorStateException.class, lock::unlock);
            Thread waiter = Thread.ofPlatform().daemon().start(contender);
            awaitLockWaitOrCompletion(waiter, contender);
            assertFalse(contender.isDone(), "The owner still holds the lock");
        } finally {
            releaseLastHold.countDown();
        }

        owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(contender.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Acquiring the lock preserves the thread's interrupt status");
    }

    private static void awaitLockWaitOrCompletion(Thread contender, Future<?> command) {
        long start = System.nanoTime();
        while (!command.isDone() && !(LockSupport.getBlocker(contender) instanceof CasLock)) {
            if (System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)) {
                fail("Command did not reach the lock or complete");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void runConcurrently(int workerCount, IntConsumer command) throws Exception {
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(workerCount)) {
            for (int worker = 0; worker < workerCount; worker++) {
                int workerIndex = worker;
                workers.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    command.accept(workerIndex);
                }));
            }
            try {
                await(ready);
            } finally {
                start.countDown();
            }
            for (Future<?> worker : workers) {
                worker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for another thread");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
