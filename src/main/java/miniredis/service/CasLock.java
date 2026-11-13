package miniredis.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** 소유자가 비어 있을 때 CAS로 획득하는 재진입 락이다. 
 * Java의 synchronized 을 사용하는 대신 직접 락을 구현했다. */
final class CasLock {
    // 락 획득에 실패하면 대기
    private static final long RETRY_NANOS = TimeUnit.MICROSECONDS.toNanos(100);

    private final AtomicReference<Thread> owner = new AtomicReference<>();
    // 소유 스레드만 획득 횟수를 읽거나 바꾼다.
    private int holdCount; // 같은 스레드가 락을 몇 번 획득했는지 기록. 재진입 락(Reentrant Lock) 가능성 때문이다.

    void lock() {
        Thread current = Thread.currentThread();

        // 1. 재진입 확인
        // 이미 현재 스레드가 락을 소유했다면 holdCount를 증가시키고 다시 진입
        if (owner.get() == current) {
            holdCount++;
            return;
        }

        boolean interrupted = false;
        // 2. CAS로 락 획득
        while (!owner.compareAndSet(null, current)) {
            // 3. 다른 스레드가 락을 소유하고 있다면, 짧게 대기한 뒤 다시 시도한다.
            LockSupport.parkNanos(this, RETRY_NANOS);
            // 한 번이라도 인터럽트가 발생했다면 interrupted는 계속 true
            interrupted |= Thread.interrupted();
        }
        holdCount = 1; 
        if (interrupted) {
            current.interrupt();
        }
    }

    void unlock() {
        if (owner.get() != Thread.currentThread()) {
            throw new IllegalMonitorStateException("current thread does not own the lock");
        }

        holdCount--;
        if (holdCount == 0) {
            // 다음 소유자는 CAS로 락을 획득하면서 이전 명령의 변경을 볼 수 있다.
            owner.set(null);
        }
    }
}
