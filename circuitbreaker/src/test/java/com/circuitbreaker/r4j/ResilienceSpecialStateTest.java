package com.circuitbreaker.r4j;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 3_2 실습 F: 특수 상태 (docs/phase3_2-resilience4j-practice-plan.md 4장 F).
 * 실행: ./gradlew simulationTest --tests '*ResilienceSpecialStateTest*'
 *
 * FORCED_OPEN / DISABLED / METRICS_ONLY를 수동 전이 메서드로 만든 뒤, 호출 결과·메트릭·이벤트를 관찰한다.
 */
@Tag("simulation")
class ResilienceSpecialStateTest {

    private static CircuitBreakerConfig config() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofMillis(300))
                .build();
    }

    /** 이벤트 집계. 종류별 횟수와 상태 전이 기록. */
    private static final class Events {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger error = new AtomicInteger();
        final AtomicInteger notPermitted = new AtomicInteger();
        final AtomicInteger failureRateExceeded = new AtomicInteger();
        final List<String> transitions = new CopyOnWriteArrayList<>();

        @Override
        public String toString() {
            return String.format("이벤트[success=%d, error=%d, notPermitted=%d, failureRateExceeded=%d, 전이=%s]",
                    success.get(), error.get(), notPermitted.get(), failureRateExceeded.get(), transitions);
        }
    }

    private static CircuitBreaker newBreaker(String name, Events ev) {
        CircuitBreaker cb = CircuitBreaker.of(name, config());
        cb.getEventPublisher().onSuccess(e -> ev.success.incrementAndGet());
        cb.getEventPublisher().onError(e -> ev.error.incrementAndGet());
        cb.getEventPublisher().onCallNotPermitted(e -> ev.notPermitted.incrementAndGet());
        cb.getEventPublisher().onFailureRateExceeded(e -> ev.failureRateExceeded.incrementAndGet());
        cb.getEventPublisher().onStateTransition(e -> ev.transitions.add(
                e.getStateTransition().getFromState() + "->" + e.getStateTransition().getToState()));
        return cb;
    }

    /** "S" 성공, "F" 대상이 던진 실패, "REJECTED" 서킷이 막음. 대상이 호출되면 counter가 오른다. */
    private static String call(CircuitBreaker cb, boolean success, AtomicInteger targetInvocations) {
        try {
            cb.executeSupplier(() -> {
                targetInvocations.incrementAndGet();
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
        return String.format("state=%-12s failureRate=%6.1f buffered=%d failed=%d successful=%d notPermitted=%d",
                cb.getState(), m.getFailureRate(), m.getNumberOfBufferedCalls(),
                m.getNumberOfFailedCalls(), m.getNumberOfSuccessfulCalls(), m.getNumberOfNotPermittedCalls());
    }

    // ------------------------------------------------------------ F1

    @Test
    void F1_FORCED_OPEN은_항상_거절하고_거절_카운터는_센다() throws InterruptedException {
        StringBuilder log = new StringBuilder("\n[F1] FORCED_OPEN (윈도우 4, 최소 4, Open 대기 300ms)\n");
        Events ev = new Events();
        CircuitBreaker cb = newBreaker("f1", ev);
        AtomicInteger target = new AtomicInteger();

        cb.transitionToForcedOpenState();
        log.append("  강제 전이 직후        : ").append(snapshot(cb)).append('\n');

        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            if ("REJECTED".equals(call(cb, true, target))) rejected++;
        }
        log.append("  성공 호출 5번 후      : ").append(snapshot(cb)).append('\n');
        log.append("  거절=").append(rejected).append(", 대상 실제 호출=").append(target).append('\n');
        log.append("  ").append(ev).append('\n');

        // 주 관찰값은 추가 관찰(상태 전이)로 메트릭이 초기화되기 전에 확정해 둔다
        final int targetAfter5 = target.get();
        final long counterAfter5 = cb.getMetrics().getNumberOfNotPermittedCalls();
        final int notPermittedEventsAfter5 = ev.notPermitted.get();

        // [추가 관찰] 대기 시간(300ms)이 한참 지나도 자동으로 풀리지 않는가
        Thread.sleep(1_000);
        String afterWait = call(cb, true, target);
        final long counterAfterWait = cb.getMetrics().getNumberOfNotPermittedCalls();
        log.append("  [추가] 1초 대기 후 호출 결과=").append(afterWait).append(" | ").append(snapshot(cb)).append('\n');

        // [추가 관찰] 수동 전이로 빠져나오기
        cb.transitionToClosedState();
        String afterClose = call(cb, true, target);
        log.append("  [추가] transitionToClosedState() 후 호출 결과=").append(afterClose).append(" | ").append(snapshot(cb)).append('\n');
        log.append("  ").append(ev).append('\n');

        // [추가 관찰] reset()으로도 빠져나오는가
        CircuitBreaker cb2 = newBreaker("f1-reset", new Events());
        cb2.transitionToForcedOpenState();
        cb2.reset();
        log.append("  [추가] FORCED_OPEN에서 reset() 후: ").append(snapshot(cb2)).append('\n');
        System.out.println(log);

        final int rejectedCount = rejected;
        final CircuitBreaker.State stateAfterReset = cb2.getState();
        assertSoftly(softly -> {
            softly.assertThat(rejectedCount).as("항상 거절").isEqualTo(5);
            softly.assertThat(targetAfter5).as("강제 Open 구간에서 대상은 호출되지 않음").isZero();
            softly.assertThat(counterAfter5)
                    .as("[가설] 공식 문서는 '메트릭 없음'이지만 소스대로 거절 카운터는 증가").isEqualTo(5);
            // 계획에 없던 관찰: 카운터는 오르는데 onCallNotPermitted 이벤트는 발행되지 않는다 (D1의 일반 Open과 다름)
            softly.assertThat(notPermittedEventsAfter5)
                    .as("[관찰] FORCED_OPEN의 거절은 onCallNotPermitted 이벤트를 발행하지 않음").isZero();
            softly.assertThat(afterWait).as("[추가] 대기 시간이 지나도 자동 해제되지 않음").isEqualTo("REJECTED");
            softly.assertThat(counterAfterWait).as("[추가] 대기 후 거절 1건 더 센다").isEqualTo(6);
            softly.assertThat(afterClose).as("[추가] 수동 전이 후에는 통과").isEqualTo("S");
            softly.assertThat(stateAfterReset).as("[추가] reset()은 FORCED_OPEN에서 CLOSED로 되돌림").isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }

    // ------------------------------------------------------------ F2

    @Test
    void F2_DISABLED는_항상_통과시키고_아무것도_기록하지_않는다() {
        StringBuilder log = new StringBuilder("\n[F2] DISABLED (윈도우 4, 최소 4, 임계치 50%)\n");
        Events ev = new Events();
        CircuitBreaker cb = newBreaker("f2", ev);
        AtomicInteger target = new AtomicInteger();

        cb.transitionToDisabledState();
        int failed = 0;
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            String r = call(cb, false, target);
            if ("F".equals(r)) failed++;
            if ("REJECTED".equals(r)) rejected++;
        }
        log.append("  실패 호출 10번 후     : ").append(snapshot(cb)).append('\n');
        log.append("  대상 실제 호출=").append(target).append(", 호출자가 받은 실패=").append(failed)
                .append(", 거절=").append(rejected).append('\n');
        log.append("  ").append(ev).append('\n');
        System.out.println(log);

        final int failedCount = failed;
        final int rejectedCount = rejected;
        assertSoftly(softly -> {
            softly.assertThat(target.get()).as("10번 모두 대상까지 감").isEqualTo(10);
            softly.assertThat(failedCount).as("실패는 호출자에게 전달").isEqualTo(10);
            softly.assertThat(rejectedCount).as("거절 없음").isZero();
            softly.assertThat(cb.getState()).as("실패율 100%여도 열리지 않음").isEqualTo(CircuitBreaker.State.DISABLED);
            softly.assertThat(cb.getMetrics().getNumberOfBufferedCalls()).as("윈도우에 기록되지 않음").isZero();
            softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
        });
    }

    // ------------------------------------------------------------ F3

    @Test
    void F3_METRICS_ONLY는_열리지_않지만_메트릭은_기록한다() {
        StringBuilder log = new StringBuilder("\n[F3] METRICS_ONLY (윈도우 4, 최소 4, 임계치 50%)\n");
        Events ev = new Events();
        CircuitBreaker cb = newBreaker("f3", ev);
        AtomicInteger target = new AtomicInteger();

        cb.transitionToMetricsOnlyState();
        for (int i = 0; i < 6; i++) {
            call(cb, false, target);
            log.append(String.format("  실패 %d번째 후: %s%n", i + 1, snapshot(cb)));
        }
        log.append("  대상 실제 호출=").append(target).append('\n');
        log.append("  ").append(ev).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(target.get()).as("모두 통과").isEqualTo(6);
            softly.assertThat(cb.getState()).as("임계치를 넘어도 열리지 않음").isEqualTo(CircuitBreaker.State.METRICS_ONLY);
            softly.assertThat(cb.getMetrics().getNumberOfBufferedCalls()).as("메트릭은 기록됨 (윈도우 크기 4)").isEqualTo(4);
            softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
            softly.assertThat(cb.getMetrics().getFailureRate()).as("실패율 100%가 계산됨").isEqualTo(100.0f);
            softly.assertThat(cb.getMetrics().getNumberOfNotPermittedCalls()).isZero();
        });
    }
}
