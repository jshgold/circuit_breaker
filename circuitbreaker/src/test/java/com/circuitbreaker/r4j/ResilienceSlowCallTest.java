package com.circuitbreaker.r4j;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 3_2 실습 B: 느린 호출 (docs/phase3_2-resilience4j-practice-plan.md 4장 B).
 * 실행: ./gradlew simulationTest --tests '*ResilienceSlowCallTest*'
 *
 * "느림"의 기준은 100ms, 느린 호출은 150ms 걸리게 하고 빠른 호출은 지연 없이 처리한다.
 * 어떤 기준(실패율/느린 호출 비율)으로 판정이 일어났는지 이벤트(onFailureRateExceeded,
 * onSlowCallRateExceeded)로도 확인한다.
 */
@Tag("simulation")
class ResilienceSlowCallTest {

    private static final long SLOW_MS = 150;
    private static final Duration SLOW_THRESHOLD = Duration.ofMillis(100);

    /** 이벤트 발생 횟수. */
    private static final class Events {
        final AtomicInteger failureRateExceeded = new AtomicInteger();
        final AtomicInteger slowCallRateExceeded = new AtomicInteger();

        @Override
        public String toString() {
            return "failureRateExceeded이벤트=" + failureRateExceeded + ", slowCallRateExceeded이벤트=" + slowCallRateExceeded;
        }
    }

    private static CircuitBreaker newBreaker(String name, CircuitBreakerConfig config, Events events) {
        CircuitBreaker cb = CircuitBreaker.of(name, config);
        cb.getEventPublisher().onFailureRateExceeded(e -> events.failureRateExceeded.incrementAndGet());
        cb.getEventPublisher().onSlowCallRateExceeded(e -> events.slowCallRateExceeded.incrementAndGet());
        return cb;
    }

    /** slow=true면 150ms 걸림, success=false면 예외. 서킷이 막으면 "REJECTED". */
    private static String call(CircuitBreaker cb, boolean success, boolean slow) {
        try {
            cb.executeSupplier(() -> {
                if (slow) {
                    try {
                        Thread.sleep(SLOW_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                }
                if (!success) {
                    throw new IllegalStateException("downstream failure");
                }
                return "ok";
            });
            return "S";
        } catch (CallNotPermittedException e) {
            return "REJECTED";
        } catch (IllegalStateException e) {
            return "F";
        }
    }

    private static String snapshot(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        return String.format(
                "state=%-9s failureRate=%6.1f slowCallRate=%6.1f buffered=%2d failed=%2d slow=%2d (slowOk=%d slowFail=%d)",
                cb.getState(), m.getFailureRate(), m.getSlowCallRate(), m.getNumberOfBufferedCalls(),
                m.getNumberOfFailedCalls(), m.getNumberOfSlowCalls(),
                m.getNumberOfSlowSuccessfulCalls(), m.getNumberOfSlowFailedCalls());
    }

    private static CircuitBreakerConfig.Builder base(int window, int minCalls) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(window)
                .minimumNumberOfCalls(minCalls)
                .slowCallDurationThreshold(SLOW_THRESHOLD)
                .waitDurationInOpenState(Duration.ofMinutes(5));
    }

    // ------------------------------------------------------------ B1

    @Test
    void B1_성공이어도_느리면_열린다() {
        StringBuilder log = new StringBuilder("\n[B1] 윈도우 5, 최소 5, 실패율 임계치 50%(기본), 느린 호출 비율 임계치 50%, 느림 기준 100ms\n");
        Events events = new Events();
        CircuitBreaker cb = newBreaker("b1", base(5, 5).failureRateThreshold(50f).slowCallRateThreshold(50f).build(), events);

        String after4 = null;
        for (int i = 1; i <= 5; i++) {
            call(cb, true, true);                 // 150ms 걸리는 "성공" 호출
            log.append(String.format("  %d번째 (150ms 성공): %s%n", i, snapshot(cb)));
            if (i == 4) after4 = snapshot(cb);
        }
        log.append("  이벤트: ").append(events).append('\n');
        System.out.println(log);

        final String a4 = after4;
        assertSoftly(softly -> {
            softly.assertThat(a4).as("4번째까지는 관문으로 Closed").contains("state=CLOSED");
            softly.assertThat(cb.getState()).as("실패 0건인데 느려서 Open").isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).as("실패 0건").isZero();
            softly.assertThat(cb.getMetrics().getFailureRate()).as("실패율 0%").isEqualTo(0.0f);
            softly.assertThat(cb.getMetrics().getSlowCallRate()).as("느린 호출 비율 100%").isEqualTo(100.0f);
            softly.assertThat(events.slowCallRateExceeded.get()).as("느린 호출 비율 초과 이벤트").isPositive();
            softly.assertThat(events.failureRateExceeded.get()).as("실패율 초과 이벤트는 없음").isZero();
        });
    }

    // ------------------------------------------------------------ B2

    @Test
    void B2_기본값_slowCallRateThreshold_100은_전부_느려야_열린다() {
        StringBuilder log = new StringBuilder("\n[B2] 윈도우 10, 최소 10, 느린 호출 비율 임계치는 설정하지 않음(기본값), 느림 기준 100ms\n");
        CircuitBreakerConfig config = base(10, 10).build();
        log.append("  기본 slowCallRateThreshold = ").append(config.getSlowCallRateThreshold()).append('\n');

        // ① 9건 느림 + 1건 빠름
        Events e1 = new Events();
        CircuitBreaker cb1 = newBreaker("b2-1", config, e1);
        for (int i = 0; i < 9; i++) call(cb1, true, true);
        call(cb1, true, false);
        log.append("  ① 느림 9 + 빠름 1: ").append(snapshot(cb1)).append(" | ").append(e1).append('\n');

        // ② 10건 전부 느림
        Events e2 = new Events();
        CircuitBreaker cb2 = newBreaker("b2-2", config, e2);
        for (int i = 0; i < 9; i++) call(cb2, true, true);
        String after9 = snapshot(cb2);
        call(cb2, true, true);
        log.append("  ② 느림 9건 후      : ").append(after9).append('\n');
        log.append("     느림 10건 후     : ").append(snapshot(cb2)).append(" | ").append(e2).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(config.getSlowCallRateThreshold()).as("기본값은 100").isEqualTo(100.0f);
            softly.assertThat(cb1.getState()).as("① 90%는 Closed").isEqualTo(CircuitBreaker.State.CLOSED);
            softly.assertThat(cb1.getMetrics().getSlowCallRate()).isEqualTo(90.0f);
            softly.assertThat(after9).as("② 9건까지는 관문으로 Closed").contains("state=CLOSED");
            softly.assertThat(cb2.getState()).as("② 10건 전부 느리면 Open").isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(cb2.getMetrics().getSlowCallRate()).isEqualTo(100.0f);
        });
    }

    // ------------------------------------------------------------ B3

    @Test
    void B3_실패율과_느린_호출_비율은_OR로_판정된다() {
        StringBuilder log = new StringBuilder("\n[B3] 윈도우 10, 최소 10, 실패율 임계치 50%, 느린 호출 비율 임계치 50%, 느림 기준 100ms\n");
        CircuitBreakerConfig config = base(10, 10).failureRateThreshold(50f).slowCallRateThreshold(50f).build();

        // 실험: 실패(빠름) 1 + 느린 성공 6 + 빠른 성공 3 -> 실패율 10%, 느린 호출 비율 60%
        Events ev = new Events();
        CircuitBreaker cb = newBreaker("b3", config, ev);
        call(cb, false, false);
        for (int i = 0; i < 6; i++) call(cb, true, true);
        for (int i = 0; i < 3; i++) call(cb, true, false);
        log.append("  실험(실패율 10%, 느림 60%) : ").append(snapshot(cb)).append(" | ").append(ev).append('\n');

        // 대조군: 실패(빠름) 1 + 느린 성공 4 + 빠른 성공 5 -> 실패율 10%, 느린 호출 비율 40%
        Events evc = new Events();
        CircuitBreaker control = newBreaker("b3-ctl", config, evc);
        call(control, false, false);
        for (int i = 0; i < 4; i++) call(control, true, true);
        for (int i = 0; i < 5; i++) call(control, true, false);
        log.append("  대조군(실패율 10%, 느림 40%): ").append(snapshot(control)).append(" | ").append(evc).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(cb.getMetrics().getFailureRate()).as("실험: 실패율 10%").isEqualTo(10.0f);
            softly.assertThat(cb.getMetrics().getSlowCallRate()).as("실험: 느린 호출 비율 60%").isEqualTo(60.0f);
            softly.assertThat(cb.getState()).as("실험: 실패율은 미만이지만 느린 호출 비율 때문에 Open")
                    .isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(ev.slowCallRateExceeded.get()).as("실험: 느린 호출 비율 초과 이벤트").isPositive();
            softly.assertThat(ev.failureRateExceeded.get()).as("실험: 실패율 초과 이벤트는 없음").isZero();
            softly.assertThat(control.getMetrics().getSlowCallRate()).as("대조군: 느린 호출 비율 40%").isEqualTo(40.0f);
            softly.assertThat(control.getState()).as("대조군: 둘 다 미만이면 Closed").isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }
}
