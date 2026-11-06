package com.circuitbreaker.r4j;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 3_2 실습 C: 예외 분류 (docs/phase3_2-resilience4j-practice-plan.md 4장 C).
 * 실행: ./gradlew simulationTest --tests '*ResilienceExceptionTest*'
 *
 * 서킷이 열리지 않도록 윈도우 100, 최소 100으로 두고, 예외가 성공/실패/무시 중 무엇으로 집계되는지만 본다.
 */
@Tag("simulation")
class ResilienceExceptionTest {

    static class ErrA extends RuntimeException {
        ErrA() {
            super("A");
        }
    }

    static class ErrB extends RuntimeException {
        ErrB() {
            super("B");
        }
    }

    static class ParentErr extends RuntimeException {
        ParentErr() {
            super("parent");
        }
    }

    static class ChildErr extends ParentErr {
    }

    static class CodedErr extends RuntimeException {
        final int code;

        CodedErr(int code) {
            super("code=" + code);
            this.code = code;
        }
    }

    /** 이벤트 카운터. */
    private static final class Events {
        final AtomicInteger error = new AtomicInteger();
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger ignored = new AtomicInteger();

        @Override
        public String toString() {
            return "이벤트[success=" + success + ", error=" + error + ", ignoredError=" + ignored + "]";
        }
    }

    private static CircuitBreakerConfig.Builder base() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .waitDurationInOpenState(Duration.ofMinutes(5));
    }

    private static CircuitBreaker newBreaker(String name, CircuitBreakerConfig config, Events events) {
        CircuitBreaker cb = CircuitBreaker.of(name, config);
        cb.getEventPublisher().onSuccess(e -> events.success.incrementAndGet());
        cb.getEventPublisher().onError(e -> events.error.incrementAndGet());
        cb.getEventPublisher().onIgnoredError(e -> events.ignored.incrementAndGet());
        return cb;
    }

    /** 예외를 던지고, 호출자에게 전달된 예외 클래스 이름을 돌려준다. 던진 예외가 없으면 "none". */
    private static String throwFrom(CircuitBreaker cb, RuntimeException toThrow) {
        try {
            cb.executeSupplier(() -> {
                throw toThrow;
            });
            return "none";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static String snapshot(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        return String.format("buffered=%d successful=%d failed=%d", m.getNumberOfBufferedCalls(),
                m.getNumberOfSuccessfulCalls(), m.getNumberOfFailedCalls());
    }

    // ------------------------------------------------------------ C1

    @Test
    void C1_recordExceptions_지정_시_목록_밖_예외는_성공으로_집계된다() {
        StringBuilder log = new StringBuilder("\n[C1] recordExceptions(ErrA) 만 지정\n");
        Events ev = new Events();
        CircuitBreaker cb = newBreaker("c1", base().recordExceptions(ErrA.class).build(), ev);

        String propagatedA = throwFrom(cb, new ErrA());
        log.append("  ErrA 던짐 -> 호출자가 받은 예외: ").append(propagatedA).append(" | ").append(snapshot(cb)).append(" | ").append(ev).append('\n');

        String propagatedB = throwFrom(cb, new ErrB());
        log.append("  ErrB 던짐 -> 호출자가 받은 예외: ").append(propagatedB).append(" | ").append(snapshot(cb)).append(" | ").append(ev).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(propagatedA).as("ErrA는 호출자에게 전달").isEqualTo("ErrA");
            softly.assertThat(propagatedB).as("ErrB도 호출자에게 그대로 전달").isEqualTo("ErrB");
            softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).as("실패는 ErrA 1건뿐").isEqualTo(1);
            softly.assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).as("ErrB는 성공으로 집계").isEqualTo(1);
        });
    }

    // ------------------------------------------------------------ C2

    @Test
    void C2_ignoreExceptions_그리고_record와_ignore_우선순위() {
        StringBuilder log = new StringBuilder("\n[C2] ignoreExceptions\n");

        // ① 무시 예외만 지정
        Events ev1 = new Events();
        CircuitBreaker cb1 = newBreaker("c2-1", base().ignoreExceptions(ErrA.class).build(), ev1);
        String propagated = throwFrom(cb1, new ErrA());
        log.append("  ① ignore(ErrA) 던짐 -> 전달: ").append(propagated).append(" | ").append(snapshot(cb1)).append(" | ").append(ev1).append('\n');

        // ② 같은 예외를 record와 ignore 양쪽에 지정 (우선순위 옵션을 건드리지 않은 기본 상태)
        Events ev2 = new Events();
        CircuitBreakerConfig cfg2 = base().recordExceptions(ErrA.class).ignoreExceptions(ErrA.class).build();
        CircuitBreaker cb2 = newBreaker("c2-2", cfg2, ev2);
        throwFrom(cb2, new ErrA());
        log.append("  ② record(ErrA)+ignore(ErrA), 우선순위 옵션 기본: ").append(snapshot(cb2)).append(" | ").append(ev2).append('\n');

        // ③ 같은 구성 + ignoreExceptionsPrecedence 켬
        Events ev3 = new Events();
        CircuitBreakerConfig cfg3 = base().recordExceptions(ErrA.class).ignoreExceptions(ErrA.class)
                .ignoreExceptionsPrecedenceEnabled(true).build();
        CircuitBreaker cb3 = newBreaker("c2-3", cfg3, ev3);
        throwFrom(cb3, new ErrA());
        log.append("  ③ 같은 구성 + ignoreExceptionsPrecedenceEnabled(true): ").append(snapshot(cb3)).append(" | ").append(ev3).append('\n');

        // ④ 같은 구성 + ignoreExceptionsPrecedence 끔(명시)
        Events ev4 = new Events();
        CircuitBreakerConfig cfg4 = base().recordExceptions(ErrA.class).ignoreExceptions(ErrA.class)
                .ignoreExceptionsPrecedenceEnabled(false).build();
        CircuitBreaker cb4 = newBreaker("c2-4", cfg4, ev4);
        throwFrom(cb4, new ErrA());
        log.append("  ④ 같은 구성 + ignoreExceptionsPrecedenceEnabled(false): ").append(snapshot(cb4)).append(" | ").append(ev4).append('\n');

        // ⑤ (계획에 없던 추가 관찰) 부모를 record, 자식을 ignore 하고 자식을 던짐: 옵션 기본 / 켬
        Events ev5 = new Events();
        CircuitBreaker cb5 = newBreaker("c2-5",
                base().recordExceptions(ParentErr.class).ignoreExceptions(ChildErr.class).build(), ev5);
        throwFrom(cb5, new ChildErr());
        log.append("  ⑤ [추가] record(Parent)+ignore(Child), Child 던짐, 옵션 기본: ").append(snapshot(cb5)).append(" | ").append(ev5).append('\n');

        Events ev6 = new Events();
        CircuitBreaker cb6 = newBreaker("c2-6",
                base().recordExceptions(ParentErr.class).ignoreExceptions(ChildErr.class)
                        .ignoreExceptionsPrecedenceEnabled(true).build(), ev6);
        throwFrom(cb6, new ChildErr());
        log.append("  ⑥ [추가] 같은 구성 + 옵션 켬                                : ").append(snapshot(cb6)).append(" | ").append(ev6).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(propagated).as("① 무시 예외도 호출자에게 전달").isEqualTo("ErrA");
            softly.assertThat(cb1.getMetrics().getNumberOfBufferedCalls()).as("① 집계에서 제외").isZero();
            softly.assertThat(ev1.ignored.get()).as("① 무시 이벤트 발생").isEqualTo(1);
            // 계획서 가설: 양쪽에 있으면 ignore가 우선 (옵션과 무관하게)
            softly.assertThat(cb2.getMetrics().getNumberOfBufferedCalls()).as("② [가설] 기본 상태에서도 ignore 우선 -> 집계 제외").isZero();
            softly.assertThat(cb3.getMetrics().getNumberOfBufferedCalls()).as("③ 옵션 켜면 ignore 우선 -> 집계 제외").isZero();
            softly.assertThat(cb4.getMetrics().getNumberOfBufferedCalls()).as("④ [가설] 옵션 꺼도 ignore 우선 -> 집계 제외").isZero();
        });
    }

    // ------------------------------------------------------------ C2b (계획에 없던 추가 관찰)

    private static String observe(String label, CircuitBreakerConfig cfg, RuntimeException toThrow,
                                  java.util.List<int[]> sink) {
        Events ev = new Events();
        CircuitBreaker cb = newBreaker("c2b-" + label.hashCode(), cfg, ev);
        throwFrom(cb, toThrow);
        sink.add(new int[]{cb.getMetrics().getNumberOfBufferedCalls(), ev.ignored.get()});
        return String.format("  %-62s : %s | %s%n", label, snapshot(cb), ev);
    }

    /** ignoreExceptionsPrecedenceEnabled 옵션이 어떤 구성에서 차이를 만드는지 관찰한다. 가설 없음(관찰용). */
    @Test
    void C2b_우선순위_옵션이_차이를_만드는_구성_찾기() {
        StringBuilder log = new StringBuilder("\n[C2b] [추가 관찰] ignoreExceptionsPrecedenceEnabled가 영향을 주는 구성\n");
        java.util.List<int[]> sink = new java.util.ArrayList<>();   // {집계된 호출 수, 무시 이벤트 수}

        // 상속 관계를 반대로: 자식을 record, 부모를 ignore, 자식을 던짐
        for (Boolean opt : new Boolean[]{null, true, false}) {
            CircuitBreakerConfig.Builder b = base().recordExceptions(ChildErr.class).ignoreExceptions(ParentErr.class);
            if (opt != null) b.ignoreExceptionsPrecedenceEnabled(opt);
            log.append(observe("record(Child)+ignore(Parent), Child 던짐, 옵션=" + (opt == null ? "기본" : opt), b.build(), new ChildErr(), sink));
        }

        // 조건식 방식: record 조건과 ignore 조건 둘 다 참
        for (Boolean opt : new Boolean[]{null, true, false}) {
            CircuitBreakerConfig.Builder b = base()
                    .recordException(t -> t instanceof ErrA)
                    .ignoreException(t -> t instanceof ErrA);
            if (opt != null) b.ignoreExceptionsPrecedenceEnabled(opt);
            log.append(observe("recordException(조건)+ignoreException(조건) 둘 다 참, 옵션=" + (opt == null ? "기본" : opt), b.build(), new ErrA(), sink));
        }

        // 클래스 목록과 조건식 혼합: record는 클래스, ignore는 조건식
        for (Boolean opt : new Boolean[]{null, true, false}) {
            CircuitBreakerConfig.Builder b = base()
                    .recordExceptions(ErrA.class)
                    .ignoreException(t -> t instanceof ErrA);
            if (opt != null) b.ignoreExceptionsPrecedenceEnabled(opt);
            log.append(observe("recordExceptions(클래스)+ignoreException(조건), 옵션=" + (opt == null ? "기본" : opt), b.build(), new ErrA(), sink));
        }
        System.out.println(log);

        // 관찰한 사실을 고정: 9개 구성 모두 ignore가 이긴다 (옵션과 무관). 가설(계획서 C2)과 일치하지만,
        // 옵션 Javadoc("기본값에서는 recordExceptions가 우선")과는 어긋난다.
        assertSoftly(softly -> {
            softly.assertThat(sink).as("9개 구성을 모두 관찰").hasSize(9);
            for (int i = 0; i < sink.size(); i++) {
                softly.assertThat(sink.get(i)[0]).as("구성 %d: 집계에서 제외", i + 1).isZero();
                softly.assertThat(sink.get(i)[1]).as("구성 %d: 무시 이벤트 1회", i + 1).isEqualTo(1);
            }
        });
    }

    // ------------------------------------------------------------ C3

    @Test
    void C3_recordException_조건식으로_집계한다() {
        StringBuilder log = new StringBuilder("\n[C3] recordException(예외 코드가 500 이상이면 실패)\n");
        Events ev = new Events();
        CircuitBreakerConfig cfg = base()
                .recordException(t -> t instanceof CodedErr c && c.code >= 500)
                .build();
        CircuitBreaker cb = newBreaker("c3", cfg, ev);

        String p500 = throwFrom(cb, new CodedErr(500));
        log.append("  code=500 던짐 -> 전달: ").append(p500).append(" | ").append(snapshot(cb)).append(" | ").append(ev).append('\n');
        String p400 = throwFrom(cb, new CodedErr(400));
        log.append("  code=400 던짐 -> 전달: ").append(p400).append(" | ").append(snapshot(cb)).append(" | ").append(ev).append('\n');
        String pOther = throwFrom(cb, new ErrB());
        log.append("  ErrB 던짐(조건식에 해당 안 됨) -> 전달: ").append(pOther).append(" | ").append(snapshot(cb)).append(" | ").append(ev).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).as("실패는 code=500 1건").isEqualTo(1);
            softly.assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).as("code=400, ErrB는 성공으로 집계").isEqualTo(2);
            softly.assertThat(cb.getMetrics().getNumberOfBufferedCalls()).isEqualTo(3);
        });
    }
}
