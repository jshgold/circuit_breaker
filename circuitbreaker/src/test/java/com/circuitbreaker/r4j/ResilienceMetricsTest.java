package com.circuitbreaker.r4j;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 3_2 실습 G: 메트릭과 이벤트 (docs/phase3_2-resilience4j-practice-plan.md 4장 G).
 * 실행: ./gradlew simulationTest --tests '*ResilienceMetricsTest*'
 *
 * Micrometer의 SimpleMeterRegistry에 서킷브레이커를 바인딩하고, 생성된 메트릭의 이름·태그·값을 전부 출력한다.
 * Prometheus 레지스트리는 의존성에 없으므로 Micrometer 이름(점 표기)까지만 본다.
 */
@org.junit.jupiter.api.Tag("simulation")
class ResilienceMetricsTest {

    private static CircuitBreakerConfig config() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .permittedNumberOfCallsInHalfOpenState(2)
                .waitDurationInOpenState(Duration.ofMillis(300))
                .build();
    }

    private static void ok(CircuitBreaker cb) {
        cb.executeSupplier(() -> "ok");
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

    private static String tags(Meter meter) {
        return meter.getId().getTags().stream()
                .map(t -> t.getKey() + "=" + t.getValue())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static String values(Meter meter) {
        return StreamSupport.stream(meter.measure().spliterator(), false)
                .map(m -> m.getStatistic().name().toLowerCase() + "=" + m.getValue())
                .collect(Collectors.joining(" "));
    }

    private static List<Meter> circuitBreakerMeters(SimpleMeterRegistry meters) {
        List<Meter> list = new ArrayList<>(meters.getMeters());
        list.removeIf(m -> !m.getId().getName().startsWith("resilience4j.circuitbreaker"));
        list.sort(Comparator.comparing((Meter m) -> m.getId().getName()).thenComparing(ResilienceMetricsTest::tags));
        return list;
    }

    private static String dump(SimpleMeterRegistry meters) {
        StringBuilder sb = new StringBuilder();
        for (Meter m : circuitBreakerMeters(meters)) {
            sb.append(String.format("    %-52s [%s] {%s} %s%n",
                    m.getId().getName(), m.getId().getType(), tags(m), values(m)));
        }
        return sb.toString();
    }

    private static Meter find(SimpleMeterRegistry meters, String name, String tagKey, String tagValue) {
        return circuitBreakerMeters(meters).stream()
                .filter(m -> m.getId().getName().equals(name))
                .filter(m -> tagKey == null || m.getId().getTags().stream()
                        .anyMatch((Tag t) -> t.getKey().equals(tagKey) && t.getValue().equals(tagValue)))
                .findFirst().orElse(null);
    }

    private static double value(Meter m) {
        return m == null ? Double.NaN : m.measure().iterator().next().getValue();
    }

    // ------------------------------------------------------------ G1

    @Test
    void G1_거절_호출_메트릭의_실제_이름() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        CircuitBreaker cb = registry.circuitBreaker("g1");

        ok(cb);
        ok(cb);
        fail(cb);
        fail(cb);          // 4번째 호출: 실패율 50% -> Open
        int rejected = 0;
        for (int i = 0; i < 3; i++) {
            try {
                ok(cb);
            } catch (CallNotPermittedException e) {
                rejected++;
            }
        }
        System.out.println("\n[G1] 성공2+실패2로 Open 후 거절 " + rejected + "번. 상태=" + cb.getState()
                + ", 거절 카운터(API)=" + cb.getMetrics().getNumberOfNotPermittedCalls()
                + "\n  생성된 resilience4j.circuitbreaker* 메트릭 전체:\n" + dump(meters));

        Meter notPermittedByName = find(meters, "resilience4j.circuitbreaker.not.permitted.calls", null, null);
        Meter callsNotPermitted = find(meters, "resilience4j.circuitbreaker.calls", "kind", "not_permitted");
        System.out.println("  가설 이름(not.permitted.calls) 존재: " + (notPermittedByName != null)
                + ", calls{kind=not_permitted} 존재: " + (callsNotPermitted != null));

        final int rejectedCount = rejected;
        assertSoftly(softly -> {
            softly.assertThat(rejectedCount).isEqualTo(3);
            softly.assertThat(notPermittedByName)
                    .as("[가설] resilience4j.circuitbreaker.not.permitted.calls 메트릭이 존재").isNotNull();
            softly.assertThat(value(notPermittedByName))
                    .as("[가설] 그 값은 거절 횟수 3").isEqualTo(3.0);
            softly.assertThat(callsNotPermitted)
                    .as("[관찰] calls 타이머에 kind=not_permitted는 없다 (거절은 별도 카운터)").isNull();
            softly.assertThat(notPermittedByName.getId().getType()).as("[관찰] 거절 메트릭은 COUNTER")
                    .isEqualTo(Meter.Type.COUNTER);
            // 공식 문서 표에는 있으나 2.4.0 실행에서는 보이지 않은 메트릭
            softly.assertThat(find(meters, "resilience4j.circuitbreaker.max.buffered.calls", null, null))
                    .as("[관찰] 문서 표의 max.buffered.calls는 노출되지 않음").isNull();
            // 문서 표에는 없으나 실제로 존재하는 메트릭
            softly.assertThat(find(meters, "resilience4j.circuitbreaker.slow.calls", "kind", "successful"))
                    .as("[관찰] 문서 표에 없는 slow.calls 게이지가 존재").isNotNull();
        });
    }

    // ------------------------------------------------------------ G2

    @Test
    void G2_METRICS_ONLY일_때_state_태그() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        CircuitBreaker cb = registry.circuitBreaker("g2");

        cb.transitionToMetricsOnlyState();
        StringBuilder sb = new StringBuilder("\n[G2] METRICS_ONLY 상태에서 resilience4j.circuitbreaker.state 게이지\n");
        List<String> stateTagValues = new ArrayList<>();
        for (Meter m : circuitBreakerMeters(meters)) {
            if (m.getId().getName().equals("resilience4j.circuitbreaker.state")) {
                String state = m.getId().getTag("state");
                stateTagValues.add(state);
                sb.append(String.format("    state=%-12s value=%s%n", state, value(m)));
            }
        }
        sb.append("  CircuitBreaker.getState() = ").append(cb.getState()).append('\n');
        System.out.println(sb);

        // 계획서 가설은 "state 태그 값에 metrics_only가 없다 (문서 표 기준)"이었으나 실행 결과 반대였다.
        // 가설은 계획 문서에 그대로 두고, 여기서는 관찰한 사실을 고정한다.
        Meter metricsOnlyGauge = find(meters, "resilience4j.circuitbreaker.state", "state", "metrics_only");
        assertSoftly(softly -> {
            softly.assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.METRICS_ONLY);
            softly.assertThat(stateTagValues)
                    .as("[관찰] state 태그 값은 6개이며 metrics_only를 포함한다 (가설과 다름)")
                    .containsExactlyInAnyOrder("closed", "open", "half_open", "forced_open", "disabled", "metrics_only");
            softly.assertThat(value(metricsOnlyGauge)).as("[관찰] 현재 상태의 게이지만 1").isEqualTo(1.0);
        });
    }

    // ------------------------------------------------------------ G3

    @Test
    void G3_상태_전이_이벤트가_순서대로_기록된다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("g3", config());
        List<String> transitions = new CopyOnWriteArrayList<>();
        cb.getEventPublisher().onStateTransition(e -> transitions.add(
                e.getStateTransition().getFromState() + "->" + e.getStateTransition().getToState()));

        ok(cb);
        ok(cb);
        fail(cb);
        fail(cb);                              // CLOSED -> OPEN
        Thread.sleep(500);                     // Open 대기 300ms 경과 (호출이 와야 전이)
        ok(cb);                                // OPEN -> HALF_OPEN (시험 호출 1)
        ok(cb);                                // 시험 호출 2 -> HALF_OPEN -> CLOSED
        System.out.println("\n[G3] 전이 기록: " + transitions + " | 최종 상태=" + cb.getState());

        assertSoftly(softly -> {
            softly.assertThat(transitions).as("[가설] 전이 이벤트가 이 순서로 기록")
                    .containsExactly("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->CLOSED");
            softly.assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }

    // ------------------------------------------------------------ G4 (계획에 없던 추가 관찰)

    @Test
    void G4_최소_호출_수_미만일_때_실패율_게이지의_값() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        CircuitBreaker cb = registry.circuitBreaker("g4");

        fail(cb);
        fail(cb);          // 최소 호출 수(4) 미만
        Meter failureRate = find(meters, "resilience4j.circuitbreaker.failure.rate", null, null);
        Meter slowRate = find(meters, "resilience4j.circuitbreaker.slow.call.rate", null, null);
        Meter buffered = find(meters, "resilience4j.circuitbreaker.buffered.calls", "kind", "failed");
        System.out.println("\n[G4] 실패 2건(최소 4건 미만) 후 게이지: failure.rate=" + value(failureRate)
                + ", slow.call.rate=" + value(slowRate) + ", buffered.calls{kind=failed}=" + value(buffered)
                + " | API getFailureRate()=" + cb.getMetrics().getFailureRate()
                + "\n전체:\n" + dump(meters));

        // 관찰용: 단정 없음 (계획서에 가설이 없던 추가 관찰이라 값을 출력만 한다)
    }
}
