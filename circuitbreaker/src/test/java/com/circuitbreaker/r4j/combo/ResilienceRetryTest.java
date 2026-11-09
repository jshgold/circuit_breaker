package com.circuitbreaker.r4j.combo;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 4_1 실습 R: Retry와 서킷브레이커의 조합 (docs/phase4_1-practice-plan.md 4장 R).
 * 실행: ./gradlew simulationTest --tests '*ResilienceRetryTest*'
 *
 * 조립 순서를 코드로 정확히 통제하려고 코어의 decorateSupplier를 직접 겹친다.
 *   Retry 바깥 + CB 안쪽 : Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, fn))
 *   CB 바깥 + Retry 안쪽 : CircuitBreaker.decorateSupplier(cb, Retry.decorateSupplier(retry, fn))
 */
@Tag("simulation")
class ResilienceRetryTest {

    static class ErrA extends RuntimeException {
        ErrA(String m) {
            super(m);
        }
    }

    static class ErrB extends RuntimeException {
        ErrB(String m) {
            super(m);
        }
    }

    // ------------------------------------------------------------ 도우미

    /** 항상 실패하는 대상. 호출 횟수를 센다. */
    private static final class AlwaysFail implements Supplier<String> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String get() {
            int n = calls.incrementAndGet();
            throw new IllegalStateException("fail #" + n);
        }
    }

    private static CircuitBreaker newCb(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofMinutes(5))     // 열리면 계속 열려 있게
                .build());
    }

    private static Retry newRetry(String name) {
        return Retry.of(name, RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(20)).build());
    }

    private static Retry newRetryIgnoring(String name, Class<? extends Throwable> ignored) {
        return Retry.of(name, RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(20))
                .ignoreExceptions(ignored).build());
    }

    private static Throwable thrownBy(Supplier<?> s) {
        try {
            s.get();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static String snap(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        return String.format("state=%-9s failureRate=%6.1f buffered=%d failed=%d notPermitted=%d",
                cb.getState(), m.getFailureRate(), m.getNumberOfBufferedCalls(),
                m.getNumberOfFailedCalls(), m.getNumberOfNotPermittedCalls());
    }

    private static Supplier<String> retryOutsideCb(Retry retry, CircuitBreaker cb, Supplier<String> fn) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, fn));
    }

    private static Supplier<String> cbOutsideRetry(Retry retry, CircuitBreaker cb, Supplier<String> fn) {
        return CircuitBreaker.decorateSupplier(cb, Retry.decorateSupplier(retry, fn));
    }

    // ------------------------------------------------------------ R1

    @Test
    void R1_Retry_기본_동작과_기본값() {
        StringBuilder log = new StringBuilder("\n[R1] Retry만 (maxAttempts 3, waitDuration 20ms)\n");

        // ① 2번 실패 후 성공
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> flaky = () -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                throw new IllegalStateException("fail #" + n);
            }
            return "ok";
        };
        long t0 = System.nanoTime();
        String result = Retry.decorateSupplier(newRetry("r1-flaky"), flaky).get();
        long elapsed1 = (System.nanoTime() - t0) / 1_000_000;
        log.append("  ① 2번 실패 후 성공: 결과=").append(result).append(", 시도=").append(calls)
                .append(", 소요=").append(elapsed1).append("ms (대기 20ms × 2회 = 최소 40ms)\n");

        // ② 항상 실패
        AlwaysFail always = new AlwaysFail();
        Throwable thrown = thrownBy(Retry.decorateSupplier(newRetry("r1-always"), always));
        log.append("  ② 항상 실패: 시도=").append(always.calls).append(", 호출자가 받은 예외=").append(thrown).append('\n');

        // 기본값
        RetryConfig defaults = RetryConfig.ofDefaults();
        log.append("  기본값: maxAttempts=").append(defaults.getMaxAttempts())
                .append(", waitDuration=").append(RetryConfig.DEFAULT_WAIT_DURATION).append("ms\n");
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(result).as("① 호출자는 실패를 모른 채 성공 결과만 받음").isEqualTo("ok");
            softly.assertThat(calls.get()).as("① 3번 시도").isEqualTo(3);
            softly.assertThat(elapsed1).as("① 대기 2회분 이상").isGreaterThanOrEqualTo(40);
            softly.assertThat(always.calls.get()).as("② 3번 시도").isEqualTo(3);
            softly.assertThat(thrown).as("② 마지막 시도의 예외가 호출자에게 전달").isInstanceOf(IllegalStateException.class)
                    .hasMessage("fail #3");
            softly.assertThat(defaults.getMaxAttempts()).isEqualTo(3);
            softly.assertThat(RetryConfig.DEFAULT_WAIT_DURATION).isEqualTo(500L);
        });
    }

    // ------------------------------------------------------------ R2

    @Test
    void R2_Retry_바깥_CB_안쪽에서는_시도마다_서킷에_기록된다() {
        StringBuilder log = new StringBuilder("\n[R2] Retry 바깥 + CB 안쪽, 논리 호출 1건(항상 실패)\n");
        AlwaysFail target = new AlwaysFail();
        CircuitBreaker cb = newCb("r2");
        Supplier<String> chain = retryOutsideCb(newRetry("r2"), cb, target);

        Throwable thrown = thrownBy(chain);
        log.append("  대상 호출=").append(target.calls).append(", 호출자가 받은 예외=").append(thrown).append('\n');
        log.append("  서킷: ").append(snap(cb)).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(target.calls.get()).isEqualTo(3);
            softly.assertThat(cb.getMetrics().getNumberOfBufferedCalls()).as("[가설] 시도 3번이 서킷에 3건으로 기록").isEqualTo(3);
            softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        });
    }

    // ------------------------------------------------------------ R3

    @Test
    void R3_서킷이_열린_뒤에도_Retry는_거절을_재시도한다() {
        StringBuilder log = new StringBuilder("\n[R3] Retry 바깥 + CB 안쪽, 기본 Retry 설정 (논리 호출 2건)\n");
        AlwaysFail target = new AlwaysFail();
        CircuitBreaker cb = newCb("r3");
        Supplier<String> chain = retryOutsideCb(newRetry("r3"), cb, target);

        thrownBy(chain);                                   // 논리 호출 1: 시도 3번 -> 서킷 버퍼 3
        int afterFirst = target.calls.get();
        log.append("  논리 호출 1건 후: 대상 호출=").append(afterFirst).append(" | ").append(snap(cb)).append('\n');

        long t0 = System.nanoTime();
        Throwable thrown = thrownBy(chain);                // 논리 호출 2: 시도 1에서 서킷이 열림
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        int secondCalls = target.calls.get() - afterFirst;
        log.append("  논리 호출 2건 후: 이 호출에서 대상 호출=").append(secondCalls).append(", 소요=").append(elapsed)
                .append("ms | ").append(snap(cb)).append('\n');
        log.append("  호출자가 받은 예외: ").append(thrown).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(afterFirst).isEqualTo(3);
            softly.assertThat(secondCalls).as("[가설] 두 번째 논리 호출의 대상 호출은 1회 (시도 1에서 서킷이 열림)").isEqualTo(1);
            softly.assertThat(cb.getMetrics().getNumberOfNotPermittedCalls())
                    .as("[가설] 시도 2, 3은 서킷이 거절했고 거절도 재시도됨 -> 거절 카운터 2").isEqualTo(2);
            softly.assertThat(thrown).as("[가설] 마지막 예외는 CallNotPermittedException").isInstanceOf(CallNotPermittedException.class);
            softly.assertThat(elapsed).as("거절을 재시도하며 대기도 함 (20ms × 2)").isGreaterThanOrEqualTo(40);
        });
    }

    // ------------------------------------------------------------ R4

    @Test
    void R4_Retry가_거절_예외를_재시도하지_않게_하면_즉시_중단한다() {
        StringBuilder log = new StringBuilder("\n[R4] Retry에 ignoreExceptions(CallNotPermittedException), 그 외 R3와 같음\n");
        AlwaysFail target = new AlwaysFail();
        CircuitBreaker cb = newCb("r4");
        Supplier<String> chain = retryOutsideCb(newRetryIgnoring("r4", CallNotPermittedException.class), cb, target);

        thrownBy(chain);
        int afterFirst = target.calls.get();
        long t0 = System.nanoTime();
        Throwable thrown = thrownBy(chain);
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        int secondCalls = target.calls.get() - afterFirst;
        log.append("  논리 호출 2건 후: 이 호출에서 대상 호출=").append(secondCalls).append(", 소요=").append(elapsed)
                .append("ms | ").append(snap(cb)).append('\n');
        log.append("  호출자가 받은 예외: ").append(thrown).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(secondCalls).as("시도 1은 대상 호출 (서킷이 열림)").isEqualTo(1);
            softly.assertThat(cb.getMetrics().getNumberOfNotPermittedCalls())
                    .as("[가설] 첫 거절에서 즉시 중단 -> 거절 카운터 1").isEqualTo(1);
            softly.assertThat(thrown).isInstanceOf(CallNotPermittedException.class);
        });
    }

    // ------------------------------------------------------------ R5

    @Test
    void R5_CB_바깥_Retry_안쪽에서는_서킷이_논리_호출_단위로_본다() {
        StringBuilder log = new StringBuilder("\n[R5] CB 바깥 + Retry 안쪽\n");
        AlwaysFail target = new AlwaysFail();
        CircuitBreaker cb = newCb("r5");
        Supplier<String> chain = cbOutsideRetry(newRetry("r5"), cb, target);

        thrownBy(chain);
        String after1 = snap(cb);
        int calls1 = target.calls.get();
        log.append("  논리 호출 1건 후: 대상 호출=").append(calls1).append(" | ").append(after1).append('\n');

        thrownBy(chain);
        thrownBy(chain);
        String after3 = snap(cb);
        int calls3 = target.calls.get();
        log.append("  논리 호출 3건 후: 대상 호출=").append(calls3).append(" | ").append(after3).append('\n');

        thrownBy(chain);
        int calls4 = target.calls.get();
        log.append("  논리 호출 4건 후: 대상 호출=").append(calls4).append(" | ").append(snap(cb)).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(calls1).isEqualTo(3);
            softly.assertThat(after1).as("[가설] 논리 호출 1건 = 서킷 버퍼 1건").contains("buffered=1").contains("state=CLOSED");
            softly.assertThat(after3).as("논리 호출 3건까지 CLOSED").contains("buffered=3").contains("state=CLOSED");
            softly.assertThat(cb.getState()).as("[가설] 논리 호출 4건이 실패해야 서킷이 열림").isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(calls4).as("서킷이 열릴 때까지 대상 호출 12회 (4 × 3시도)").isEqualTo(12);
        });
    }

    // ------------------------------------------------------------ R6

    private record Outcome(int targetCalls, int failures, long elapsedMs) {
    }

    /** 논리 호출 n건을 순차로 보낸다. */
    private static Outcome runSequential(Supplier<String> chain, AlwaysFail target, int n) {
        long t0 = System.nanoTime();
        int failures = 0;
        for (int i = 0; i < n; i++) {
            if (thrownBy(chain) != null) failures++;
        }
        return new Outcome(target.calls.get(), failures, (System.nanoTime() - t0) / 1_000_000);
    }

    @Test
    void R6_증폭_측정_구성별_대상_실제_호출_수() {
        StringBuilder log = new StringBuilder("\n[R6] 항상 실패하는 대상에 논리 호출 20건을 순차 전송 (Retry 3회/20ms, CB 윈도우 4)\n");

        AlwaysFail a = new AlwaysFail();
        Outcome oa = runSequential(a, a, 20);

        AlwaysFail b = new AlwaysFail();
        Outcome ob = runSequential(Retry.decorateSupplier(newRetry("r6-b"), b), b, 20);

        AlwaysFail c = new AlwaysFail();
        Outcome oc = runSequential(CircuitBreaker.decorateSupplier(newCb("r6-c"), c), c, 20);

        AlwaysFail d = new AlwaysFail();
        Outcome od = runSequential(retryOutsideCb(newRetry("r6-d"), newCb("r6-d"), d), d, 20);

        AlwaysFail e = new AlwaysFail();
        Outcome oe = runSequential(cbOutsideRetry(newRetry("r6-e"), newCb("r6-e"), e), e, 20);

        log.append(String.format("  (a) 보호 없음               : 대상 호출 %3d, 소요 %4dms%n", oa.targetCalls(), oa.elapsedMs()));
        log.append(String.format("  (b) Retry만                 : 대상 호출 %3d, 소요 %4dms%n", ob.targetCalls(), ob.elapsedMs()));
        log.append(String.format("  (c) CB만                    : 대상 호출 %3d, 소요 %4dms%n", oc.targetCalls(), oc.elapsedMs()));
        log.append(String.format("  (d) Retry 바깥 + CB 안쪽    : 대상 호출 %3d, 소요 %4dms%n", od.targetCalls(), od.elapsedMs()));
        log.append(String.format("  (e) CB 바깥 + Retry 안쪽    : 대상 호출 %3d, 소요 %4dms%n", oe.targetCalls(), oe.elapsedMs()));
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(oa.targetCalls()).as("[가설] (a) 20").isEqualTo(20);
            softly.assertThat(ob.targetCalls()).as("[가설] (b) 20 × 3").isEqualTo(60);
            softly.assertThat(oc.targetCalls()).as("[가설] (c) 윈도우를 채운 4건 후 서킷이 열림").isEqualTo(4);
            softly.assertThat(od.targetCalls()).as("[가설] (d) 첫 논리 호출 3 + 둘째 논리 호출 1에서 열림").isEqualTo(4);
            softly.assertThat(oe.targetCalls()).as("[가설] (e) 논리 호출 4건 × 3시도").isEqualTo(12);
            softly.assertThat(od.elapsedMs()).as("[가설] (d)가 (e)보다 오래 걸림: 거절을 재시도하며 대기").isGreaterThan(oe.elapsedMs());
        });
    }

    // ------------------------------------------------------------ R7

    @Test
    void R7_재시도_대상_예외를_제한한다() {
        StringBuilder log = new StringBuilder("\n[R7] Retry에 ignoreExceptions(ErrB)\n");
        Retry retry = newRetryIgnoring("r7", ErrB.class);

        AtomicInteger callsB = new AtomicInteger();
        Throwable tb = thrownBy(Retry.decorateSupplier(retry, () -> {
            callsB.incrementAndGet();
            throw new ErrB("b");
        }));
        AtomicInteger callsA = new AtomicInteger();
        Throwable ta = thrownBy(Retry.decorateSupplier(retry, () -> {
            callsA.incrementAndGet();
            throw new ErrA("a");
        }));
        log.append("  ErrB(재시도 제외): 시도=").append(callsB).append(", 전달=").append(tb.getClass().getSimpleName()).append('\n');
        log.append("  ErrA(재시도 대상): 시도=").append(callsA).append(", 전달=").append(ta.getClass().getSimpleName()).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(callsB.get()).as("[가설] ErrB는 1번 시도로 끝").isEqualTo(1);
            softly.assertThat(callsA.get()).as("[가설] ErrA는 3번 시도").isEqualTo(3);
            softly.assertThat(tb).isInstanceOf(ErrB.class);
            softly.assertThat(ta).isInstanceOf(ErrA.class);
        });
    }
}
