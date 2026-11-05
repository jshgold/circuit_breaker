package com.circuitbreaker.sim;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 가짜 DB + 커넥션 풀. 풀 크기만큼만 동시에 쿼리할 수 있다(Semaphore).
 * 커넥션을 못 얻으면 acquireTimeoutMs까지 호출 스레드가 블로킹되고, 시간이 지나면 예외를 던진다
 * (HikariCP의 connectionTimeout 동작을 흉내).
 * DB 서버는 느려지기만 하고 죽지는 않는다. 부하에 따라 쿼리가 느려지는 효과도 없다(단순화).
 */
final class FakeDb {

    static final class DbTimeoutException extends RuntimeException {
        DbTimeoutException(String message) {
            super(message);
        }
    }

    private final Semaphore connections;
    private final long queryMs;
    private final long acquireTimeoutMs;

    FakeDb(int poolSize, long queryMs, long acquireTimeoutMs) {
        this.connections = new Semaphore(poolSize, true);   // fair: 먼저 기다린 순서대로
        this.queryMs = queryMs;
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    String query() {
        boolean acquired;
        try {
            acquired = connections.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (!acquired) {
            throw new DbTimeoutException("connection acquire timeout after " + acquireTimeoutMs + "ms");
        }
        try {
            SimUtil.sleepMs(queryMs);
            return "row";
        } finally {
            connections.release();
        }
    }

    /** 커넥션을 기다리는 스레드 수. */
    int waiting() {
        return connections.getQueueLength();
    }
}
