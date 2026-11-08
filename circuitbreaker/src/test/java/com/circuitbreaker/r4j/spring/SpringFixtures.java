package com.circuitbreaker.r4j.spring;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 3_4 시험용 빈들. 어노테이션 방식(@CircuitBreaker)이 적용되는지 보려는 용도다.
 *
 * 주의: Spring이 만드는 CGLIB 프록시는 필드를 복사하지 않으므로 상태는 필드가 아니라 메서드로 읽는다.
 */
public final class SpringFixtures {

    private SpringFixtures() {
    }

    public static class ErrA extends RuntimeException {
        public ErrA() {
            super("A");
        }
    }

    public static class ErrB extends RuntimeException {
        public ErrB() {
            super("B");
        }
    }

    /** P2, P3, Q1~Q3: 인스턴스 svc (윈도우 4, 최소 4, 50%, 대기 300ms, 시험 호출 2). */
    public static class SvcService {
        private final AtomicInteger targetCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private volatile Throwable lastFallbackCause;
        private volatile boolean fail;

        public void reset() {
            targetCalls.set(0);
            fallbackCalls.set(0);
            lastFallbackCause = null;
            fail = false;
        }

        public void setFail(boolean fail) {
            this.fail = fail;
        }

        public int targetCalls() {
            return targetCalls.get();
        }

        public int fallbackCalls() {
            return fallbackCalls.get();
        }

        public Throwable lastFallbackCause() {
            return lastFallbackCause;
        }

        @CircuitBreaker(name = "svc", fallbackMethod = "fallback")
        public String call(String key) {
            targetCalls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("downstream failure");
            }
            return "real:" + key;
        }

        public String fallback(String key, Throwable t) {
            fallbackCalls.incrementAndGet();
            lastFallbackCause = t;
            return "fallback:" + key;
        }
    }

    /** P4: yaml에 정의하지 않은 인스턴스 "undefined". fallback 없음. */
    public static class UndefinedService {
        private final AtomicInteger targetCalls = new AtomicInteger();

        public void reset() {
            targetCalls.set(0);
        }

        public int targetCalls() {
            return targetCalls.get();
        }

        @CircuitBreaker(name = "undefined")
        public String call() {
            targetCalls.incrementAndGet();
            throw new IllegalStateException("downstream failure");
        }
    }

    /** P5: 예외 분류. fallback 없음 (예외는 호출자에게 그대로 전달). */
    public static class ExceptionService {
        @CircuitBreaker(name = "excRecord")
        public String viaRecord(RuntimeException toThrow) {
            throw toThrow;
        }

        @CircuitBreaker(name = "excIgnore")
        public String viaIgnore(RuntimeException toThrow) {
            throw toThrow;
        }

        @CircuitBreaker(name = "excBoth")
        public String viaBoth(RuntimeException toThrow) {
            throw toThrow;
        }
    }

    /** P6: 같은 클래스 안에서 호출(self-invocation). */
    public static class SelfInvocationService {
        private final AtomicInteger targetCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private volatile boolean fail;

        public void reset() {
            targetCalls.set(0);
            fallbackCalls.set(0);
            fail = false;
        }

        public void setFail(boolean fail) {
            this.fail = fail;
        }

        public int targetCalls() {
            return targetCalls.get();
        }

        public int fallbackCalls() {
            return fallbackCalls.get();
        }

        @CircuitBreaker(name = "selfinv", fallbackMethod = "fb")
        public String inner() {
            targetCalls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("downstream failure");
            }
            return "real";
        }

        public String fb(Throwable t) {
            fallbackCalls.incrementAndGet();
            return "fallback";
        }

        /** 프록시를 거치지 않고 this.inner()를 호출한다. */
        public String outer() {
            return inner();
        }
    }

    /** P8: fallback 해석 규칙. */
    public static class FallbackRuleService {
        private final AtomicInteger targetCalls = new AtomicInteger();
        private volatile boolean fail;

        public void reset() {
            targetCalls.set(0);
            fail = false;
        }

        public void setFail(boolean fail) {
            this.fail = fail;
        }

        public int targetCalls() {
            return targetCalls.get();
        }

        /** fallback이 CallNotPermittedException 만 받는다. */
        @CircuitBreaker(name = "svc2", fallbackMethod = "fbCnpeOnly")
        public String callCnpeOnly() {
            targetCalls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("downstream failure");
            }
            return "real";
        }

        public String fbCnpeOnly(CallNotPermittedException e) {
            return "cnpe-fallback";
        }

        /** private fallback. */
        @CircuitBreaker(name = "svc3", fallbackMethod = "privateFb")
        public String callPrivate() {
            targetCalls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("downstream failure");
            }
            return "real";
        }

        @SuppressWarnings("unused")
        private String privateFb(Throwable t) {
            return "private-fallback";
        }
    }

    /** Q4: 헬스 인디케이터 등록 인스턴스. */
    public static class HealthService {
        @CircuitBreaker(name = "health1", fallbackMethod = "fb")
        public String call() {
            throw new IllegalStateException("downstream failure");
        }

        public String fb(Throwable t) {
            return "fallback";
        }
    }

    @TestConfiguration
    public static class Beans {
        @Bean
        public SvcService svcService() {
            return new SvcService();
        }

        @Bean
        public UndefinedService undefinedService() {
            return new UndefinedService();
        }

        @Bean
        public ExceptionService exceptionService() {
            return new ExceptionService();
        }

        @Bean
        public SelfInvocationService selfInvocationService() {
            return new SelfInvocationService();
        }

        @Bean
        public FallbackRuleService fallbackRuleService() {
            return new FallbackRuleService();
        }

        @Bean
        public HealthService healthService() {
            return new HealthService();
        }
    }
}
