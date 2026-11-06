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
 * Phase 3_2 실습 D: Open 상태 (docs/phase3_2-resilience4j-practice-plan.md 4장 D).
 * 실행: ./gradlew simulationTest --tests '*ResilienceOpenStateTest*'
 */
@Tag("simulation")
class ResilienceOpenStateTest {

    private static final Duration WAIT = Duration.ofMillis(300);

    private static CircuitBreakerConfig.Builder base(Duration waitInOpen) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .permittedNumberOfCallsInHalfOpenState(2)
                .waitDurationInOpenState(waitInOpen);   // 같은 빌더에서 두 번 설정하면 예외가 난다 (아래 D0 참고)
    }

    private static CircuitBreakerConfig.Builder base() {
        return base(WAIT);
    }

    private static void fail(CircuitBreaker cb) {
        try {
            cb.executeSupplier(() -> {
                throw new IllegalStateException("downstream failure");
            });
        } catch (IllegalStateException ignored) {
            // 집계만 필요
        }
    }

    private static void openIt(CircuitBreaker cb) {
        for (int i = 0; i < 4; i++) fail(cb);
    }

    private static String snapshot(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        return String.format("state=%-9s failureRate=%6.1f buffered=%d failed=%d successful=%d notPermitted=%d",
                cb.getState(), m.getFailureRate(), m.getNumberOfBufferedCalls(),
                m.getNumberOfFailedCalls(), m.getNumberOfSuccessfulCalls(), m.getNumberOfNotPermittedCalls());
    }

    /** 상태 전이를 (시각 ms, from->to) 문자열로 기록한다. 시각은 기준 시각으로부터의 경과. */
    private static List<String> recordTransitions(CircuitBreaker cb, long originNanos) {
        List<String> transitions = new CopyOnWriteArrayList<>();
        cb.getEventPublisher().onStateTransition(e -> transitions.add(String.format("+%dms %s->%s",
                (System.nanoTime() - originNanos) / 1_000_000,
                e.getStateTransition().getFromState(), e.getStateTransition().getToState())));
        return transitions;
    }

    // ------------------------------------------------------------ D0 (우연히 발견한 설정 제약)

    /** 테스트 작성 중 발견: 대기 시간을 같은 빌더에서 두 번 설정하면 build()가 예외를 던진다. 계획에 없던 관찰. */
    @Test
    void D0_대기_시간을_두_번_설정하면_build가_예외를_던진다() {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .waitDurationInOpenState(Duration.ofSeconds(2));
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(builder::build);
        System.out.println("\n[D0] waitDurationInOpenState를 두 번 설정하고 build() -> " + thrown);
        assertSoftly(softly -> {
            softly.assertThat(thrown).as("빌더에 같은 설정을 두 번 하면 예외").isInstanceOf(IllegalStateException.class);
            softly.assertThat(thrown).hasMessageContaining("waitIntervalFunction was configured multiple times");
        });
    }

    // ------------------------------------------------------------ D1

    @Test
    void D1_Open_상태에서는_대상을_호출하지_않고_거절한다() {
        StringBuilder log = new StringBuilder("\n[D1] 윈도우 4, 최소 4, 대기 시간 5분(Half-Open으로 안 넘어가게)\n");
        CircuitBreaker cb = CircuitBreaker.of("d1", base(Duration.ofMinutes(5)).build());
        AtomicInteger notPermittedEvents = new AtomicInteger();
        cb.getEventPublisher().onCallNotPermitted(e -> notPermittedEvents.incrementAndGet());

        openIt(cb);
        String before = snapshot(cb);
        log.append("  Open 직후              : ").append(before).append('\n');

        AtomicInteger targetInvocations = new AtomicInteger();
        int rejected = 0;
        Throwable lastRejection = null;
        for (int i = 0; i < 5; i++) {
            try {
                cb.executeSupplier(() -> {
                    targetInvocations.incrementAndGet();
                    return "real";
                });
            } catch (CallNotPermittedException e) {
                rejected++;
                lastRejection = e;
            }
        }
        String after = snapshot(cb);
        log.append("  5번 호출 후            : ").append(after).append('\n');
        log.append("  거절된 횟수=").append(rejected).append(", 대상 실제 호출 횟수=").append(targetInvocations)
                .append(", onCallNotPermitted 이벤트=").append(notPermittedEvents).append('\n');
        log.append("  거절 예외: ").append(lastRejection).append('\n');
        System.out.println(log);

        final Throwable rejection = lastRejection;
        final int rejectedCount = rejected;
        assertSoftly(softly -> {
            softly.assertThat(rejectedCount).as("5번 모두 CallNotPermittedException").isEqualTo(5);
            softly.assertThat(targetInvocations.get()).as("대상 함수는 한 번도 호출되지 않음").isZero();
            softly.assertThat(cb.getMetrics().getNumberOfNotPermittedCalls()).as("거절 카운터 5").isEqualTo(5);
            softly.assertThat(notPermittedEvents.get()).as("거절 이벤트 5").isEqualTo(5);
            softly.assertThat(cb.getMetrics().getNumberOfBufferedCalls()).as("윈도우(버퍼)는 그대로 4").isEqualTo(4);
            softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).as("실패 수도 그대로 4").isEqualTo(4);
            softly.assertThat(cb.getMetrics().getFailureRate()).as("실패율도 그대로 100").isEqualTo(100.0f);
            softly.assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(rejection).hasMessageContaining("d1");
        });
    }

    // ------------------------------------------------------------ D2

    @Test
    void D2a_기본_설정은_lazy_전이_호출이_와야_Half_Open이_된다() throws InterruptedException {
        StringBuilder log = new StringBuilder("\n[D2a] 자동 전이 꺼짐(기본), 대기 시간 300ms\n");
        long origin = System.nanoTime();
        CircuitBreaker cb = CircuitBreaker.of("d2a", base().build());
        List<String> transitions = recordTransitions(cb, origin);
        log.append("  자동 전이 설정값 = ").append(cb.getCircuitBreakerConfig().isAutomaticTransitionFromOpenToHalfOpenEnabled()).append('\n');

        openIt(cb);
        log.append("  Open 직후                          : ").append(snapshot(cb)).append('\n');

        Thread.sleep(1_000);   // 대기 시간(300ms)의 3배 이상, 그동안 호출 없음
        String idle = snapshot(cb);
        log.append("  1초 경과 (호출 없음)                : ").append(idle).append('\n');

        AtomicInteger targetInvocations = new AtomicInteger();
        String result = cb.executeSupplier(() -> {
            targetInvocations.incrementAndGet();
            return "real";
        });
        String afterCall = snapshot(cb);
        log.append("  이후 호출 1번 (대상 호출 ").append(targetInvocations).append("회, 결과=").append(result).append("): ").append(afterCall).append('\n');
        log.append("  전이 기록: ").append(transitions).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(idle).as("호출이 없으면 대기 시간이 한참 지나도 OPEN으로 보임").contains("state=OPEN");
            softly.assertThat(targetInvocations.get()).as("그 호출은 거절되지 않고 대상까지 감").isEqualTo(1);
            softly.assertThat(cb.getState()).as("호출 뒤 Half-Open").isEqualTo(CircuitBreaker.State.HALF_OPEN);
            softly.assertThat(transitions).as("전이는 호출 시점에 발생")
                    .anyMatch(t -> t.endsWith("OPEN->HALF_OPEN"));
        });
    }

    @Test
    void D2b_자동_전이를_켜면_호출_없이도_Half_Open이_된다() throws InterruptedException {
        StringBuilder log = new StringBuilder("\n[D2b] 자동 전이 켬, 대기 시간 300ms\n");
        long origin = System.nanoTime();
        CircuitBreaker cb = CircuitBreaker.of("d2b", base().enableAutomaticTransitionFromOpenToHalfOpen().build());
        List<String> transitions = recordTransitions(cb, origin);
        log.append("  자동 전이 설정값 = ").append(cb.getCircuitBreakerConfig().isAutomaticTransitionFromOpenToHalfOpenEnabled()).append('\n');

        openIt(cb);
        long openedAt = System.nanoTime();
        log.append("  Open 직후: ").append(snapshot(cb)).append('\n');

        // 호출 없이 Half-Open이 될 때까지 기다린다 (최대 3초)
        long elapsedMs = -1;
        for (int i = 0; i < 60; i++) {
            if (cb.getState() == CircuitBreaker.State.HALF_OPEN) {
                elapsedMs = (System.nanoTime() - openedAt) / 1_000_000;
                break;
            }
            Thread.sleep(50);
        }
        log.append("  호출 없이 Half-Open이 된 시점: Open 후 약 ").append(elapsedMs).append("ms (대기 시간 300ms)\n");
        log.append("  현재: ").append(snapshot(cb)).append('\n');
        log.append("  전이 기록: ").append(transitions).append('\n');
        System.out.println(log);

        final long elapsed = elapsedMs;
        assertSoftly(softly -> {
            softly.assertThat(elapsed).as("3초 안에 호출 없이 Half-Open으로 전이").isPositive();
            softly.assertThat(elapsed).as("대기 시간(300ms)보다 일찍 전이하지는 않음").isGreaterThanOrEqualTo(250);
            softly.assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            softly.assertThat(cb.getMetrics().getNumberOfNotPermittedCalls()).as("호출이 없었으니 거절도 없음").isZero();
        });
    }
}
