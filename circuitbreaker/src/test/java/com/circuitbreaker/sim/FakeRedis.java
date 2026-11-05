package com.circuitbreaker.sim;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 가짜 Redis. 켜고 끌 수 있다.
 * - 정상: 1ms 후 hit율에 따라 값(hit) 또는 null(miss)을 반환
 * - 장애: 타임아웃 시간만큼 호출 스레드를 붙잡은 뒤 예외 (즉시 에러가 아니라 "매달리다 실패"를 흉내)
 */
final class FakeRedis {

    static final class RedisDownException extends RuntimeException {
        RedisDownException(String message) {
            super(message);
        }
    }

    private final double hitRate;
    private final long downHangMs;
    private volatile boolean down;

    FakeRedis(double hitRate, long downHangMs) {
        this.hitRate = hitRate;
        this.downHangMs = downHangMs;
    }

    void setDown(boolean down) {
        this.down = down;
    }

    /** hit이면 값, miss이면 null. 장애면 downHangMs 후 RedisDownException. */
    String get() {
        if (down) {
            SimUtil.sleepMs(downHangMs);
            throw new RedisDownException("redis timeout");
        }
        SimUtil.sleepMs(1);
        return ThreadLocalRandom.current().nextDouble() < hitRate ? "cached" : null;
    }
}
