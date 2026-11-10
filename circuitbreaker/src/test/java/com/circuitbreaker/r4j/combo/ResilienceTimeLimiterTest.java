package com.circuitbreaker.r4j.combo;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 4_1 실습 T: TimeLimiter(타임아웃)와 서킷브레이커의 조합 (docs/phase4_1-practice-plan.md 4장 T).
 * 실행: ./gradlew simulationTest --tests '*ResilienceTimeLimiterTest*'
 *
 * TimeLimiter는 Future를 대상으로 하므로 대상은 별도 실행기(풀)에서 sleep으로 매달리는 작업으로 만든다.
 * 조립 순서는 Spring 어노테이션 기본 순서와 같게: CircuitBreaker(바깥) -> TimeLimiter(안쪽) -> 대상.
 *   CircuitBreaker.decorateCallable(cb, TimeLimiter.decorateFutureSupplier(tl, () -> pool.submit(대상)))
 */
@Tag("simulation")
class ResilienceTimeLimiterTest {

    private static final long TARGET_MS = 2_000;     // 대상이 매달리는 시간
    private static final long TIMEOUT_MS = 200;      // TimeLimiter 상한

    /** 2초 매달리는 대상. 시작/인터럽트/정상 종료를 기록한다. */
    private static final class SlowTarget {
        final AtomicInteger started = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean completed = new AtomicBoolean();

        Callable<String> callable() {
            return () -> {
                started.incrementAndGet();
                try {
                    Thread.sleep(TARGET_MS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
                completed.set(true);
                return "ok";
            };
        }
    }

    private static CircuitBreakerConfig.Builder cbConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofMinutes(5));
    }

    private static TimeLimiter newTimeLimiter(String name, boolean cancel) {
        return TimeLimiter.of(name, TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(TIMEOUT_MS)).cancelRunningFuture(cancel).build());
    }

    private static Throwable thrownBy(Callable<?> c) {
        try {
            c.call();
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String snap(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        return String.format("state=%-9s failureRate=%6.1f buffered=%d failed=%d slowFailed=%d",
                cb.getState(), m.getFailureRate(), m.getNumberOfBufferedCalls(),
                m.getNumberOfFailedCalls(), m.getNumberOfSlowFailedCalls());
    }

    /** 서킷의 상태 전이를 (시작 시각 대비 ms, 전이)로 기록한다. */
    private static List<String> recordTransitions(CircuitBreaker cb, long originNanos) {
        List<String> transitions = new CopyOnWriteArrayList<>();
        cb.getEventPublisher().onStateTransition(e -> transitions.add(String.format("+%dms %s->%s",
                (System.nanoTime() - originNanos) / 1_000_000,
                e.getStateTransition().getFromState(), e.getStateTransition().getToState())));
        return transitions;
    }

    /** OPEN 으로 전이했으면 그 시각(ms)을, 아니면 -1 을 돌려준다. */
    private static long openedAtMs(List<String> transitions) {
        return transitions.stream().filter(t -> t.endsWith("CLOSED->OPEN"))
                .map(t -> Long.parseLong(t.substring(1, t.indexOf("ms")))).findFirst().orElse(-1L);
    }

    // ------------------------------------------------------------ T1

    @Test
    void T1_타임아웃_동작과_cancelRunningFuture() throws Exception {
        StringBuilder log = new StringBuilder("\n[T1] 대상 2초, timeout 200ms\n");
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            // cancelRunningFuture = true
            SlowTarget cancelTarget = new SlowTarget();
            TimeLimiter tlCancel = newTimeLimiter("t1-cancel", true);
            long t0 = System.nanoTime();
            Throwable thrownCancel = thrownBy(() -> tlCancel.executeFutureSupplier(() -> pool.submit(cancelTarget.callable())));
            long elapsedCancel = (System.nanoTime() - t0) / 1_000_000;
            sleep(300);
            log.append(String.format("  cancel=true : 호출자는 %dms 만에 %s%n", elapsedCancel, thrownCancel));
            log.append(String.format("                0.3초 뒤 대상 상태: 시작=%d, 인터럽트됨=%s, 정상 종료=%s%n",
                    cancelTarget.started.get(), cancelTarget.interrupted.get(), cancelTarget.completed.get()));

            // cancelRunningFuture = false
            SlowTarget keepTarget = new SlowTarget();
            TimeLimiter tlKeep = newTimeLimiter("t1-keep", false);
            long t1 = System.nanoTime();
            Throwable thrownKeep = thrownBy(() -> tlKeep.executeFutureSupplier(() -> pool.submit(keepTarget.callable())));
            long elapsedKeep = (System.nanoTime() - t1) / 1_000_000;
            sleep(300);
            boolean keepRunningAt05 = !keepTarget.completed.get() && !keepTarget.interrupted.get();
            log.append(String.format("  cancel=false: 호출자는 %dms 만에 %s%n", elapsedKeep, thrownKeep));
            log.append(String.format("                0.3초 뒤 대상 상태: 시작=%d, 인터럽트됨=%s, 정상 종료=%s (계속 실행 중=%s)%n",
                    keepTarget.started.get(), keepTarget.interrupted.get(), keepTarget.completed.get(), keepRunningAt05));
            long waitStart = System.nanoTime();
            while (!keepTarget.completed.get() && System.nanoTime() - waitStart < 3_000_000_000L) {
                sleep(50);
            }
            log.append(String.format("                이후 대상은 호출자가 포기한 뒤에도 계속 실행되어 정상 종료=%s (타임아웃 후 약 %dms)%n",
                    keepTarget.completed.get(), (System.nanoTime() - t1) / 1_000_000));
            System.out.println(log);

            assertSoftly(softly -> {
                softly.assertThat(thrownCancel).as("[가설] 타임아웃 예외").isInstanceOf(TimeoutException.class);
                softly.assertThat(elapsedCancel).as("[가설] 약 200ms에 포기").isBetween(150L, 600L);
                softly.assertThat(cancelTarget.interrupted.get()).as("[가설] cancel=true면 대상이 인터럽트됨").isTrue();
                softly.assertThat(cancelTarget.completed.get()).as("[가설] cancel=true면 정상 종료하지 않음").isFalse();
                softly.assertThat(thrownKeep).isInstanceOf(TimeoutException.class);
                softly.assertThat(keepRunningAt05).as("[가설] cancel=false면 호출자가 포기해도 대상은 계속 실행").isTrue();
                softly.assertThat(keepTarget.interrupted.get()).as("[가설] cancel=false면 인터럽트되지 않음").isFalse();
                softly.assertThat(keepTarget.completed.get()).as("[가설] cancel=false면 2초까지 실행해 정상 종료").isTrue();
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ T2

    @Test
    void T2_CB_바깥_TimeLimiter_안쪽에서는_타임아웃_시점에_실패가_기록된다() {
        StringBuilder log = new StringBuilder("\n[T2] CB 바깥 + TimeLimiter 안쪽, 대상 2초, timeout 200ms, 순차 4건\n");
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            SlowTarget target = new SlowTarget();
            CircuitBreaker cb = CircuitBreaker.of("t2", cbConfig().build());
            long origin = System.nanoTime();
            List<String> transitions = recordTransitions(cb, origin);
            List<Long> recordedMs = new CopyOnWriteArrayList<>();
            cb.getEventPublisher().onError(e -> recordedMs.add(e.getElapsedDuration().toMillis()));

            TimeLimiter tl = newTimeLimiter("t2", true);
            Callable<String> chain = CircuitBreaker.decorateCallable(cb,
                    TimeLimiter.decorateFutureSupplier(tl, () -> pool.submit(target.callable())));

            List<Throwable> thrown = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                thrown.add(thrownBy(chain));
                log.append(String.format("  호출 %d(+%dms): %s | %s%n", i, (System.nanoTime() - origin) / 1_000_000,
                        thrown.get(i - 1).getClass().getSimpleName(), snap(cb)));
            }
            sleep(100);
            long openedAt = openedAtMs(transitions);
            log.append("  서킷에 기록된 호출 소요(ms): ").append(recordedMs).append('\n');
            log.append("  전이 기록: ").append(transitions).append('\n');
            System.out.println(log);

            assertSoftly(softly -> {
                softly.assertThat(thrown).as("모두 타임아웃").allMatch(t -> t instanceof TimeoutException);
                softly.assertThat(cb.getMetrics().getNumberOfFailedCalls()).as("[가설] TimeoutException이 실패로 집계됨").isEqualTo(4);
                softly.assertThat(cb.getState()).as("[가설] 4건 타임아웃으로 서킷이 열림").isEqualTo(CircuitBreaker.State.OPEN);
                softly.assertThat(openedAt).as("[가설] 약 0.8초에 열림 (호출이 끝나는 2초를 기다리지 않음)").isBetween(600L, 1_500L);
                softly.assertThat(recordedMs).as("[가설] 서킷에는 타임아웃 시점의 소요(약 200ms)가 기록됨").allMatch(ms -> ms >= 150 && ms <= 600);
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ T3

    @Test
    void T3_TimeLimiter가_없으면_호출이_끝나야_서킷이_열린다() throws Exception {
        StringBuilder log = new StringBuilder("\n[T3] CB만 (TimeLimiter 없음), 대상 2초, 동시 4건\n");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            SlowTarget target = new SlowTarget();
            CircuitBreaker cb = CircuitBreaker.of("t3", cbConfig().build());
            long origin = System.nanoTime();
            List<String> transitions = recordTransitions(cb, origin);
            // 대상이 성공하면 서킷이 안 열리므로, 2초 매달린 뒤 실패하도록 만든다 (T2와 같은 "느린 장애")
            Callable<String> slowFail = () -> {
                target.started.incrementAndGet();
                Thread.sleep(TARGET_MS);
                throw new IllegalStateException("downstream failure after hang");
            };
            Callable<String> chain = CircuitBreaker.decorateCallable(cb, slowFail);

            List<java.util.concurrent.Future<Throwable>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> thrownBy(chain)));
            }
            sleep(1_000);
            String at1s = snap(cb);
            log.append("  1초 경과 (호출은 모두 대상 안에서 대기 중): ").append(at1s).append('\n');
            for (var f : futures) {
                f.get();
            }
            sleep(100);
            long openedAt = openedAtMs(transitions);
            log.append("  모두 끝난 뒤: ").append(snap(cb)).append('\n');
            log.append("  전이 기록: ").append(transitions).append('\n');
            System.out.println(log);

            assertSoftly(softly -> {
                softly.assertThat(at1s).as("[가설] 1초 시점에는 아무것도 기록되지 않아 CLOSED, 버퍼 0")
                        .contains("state=CLOSED").contains("buffered=0");
                softly.assertThat(openedAt).as("[가설] 호출이 끝나는 약 2초 이후에야 열림").isBetween(1_800L, 3_500L);
                softly.assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ T4

    @Test
    void T4_타임아웃은_느린_호출이면서_실패로_집계된다() {
        StringBuilder log = new StringBuilder("\n[T4] T2와 같은 구성 + slowCallDurationThreshold=100ms, 순차 3건 (최소 4건 미만이라 열리지 않음)\n");
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            SlowTarget target = new SlowTarget();
            CircuitBreaker cb = CircuitBreaker.of("t4", cbConfig()
                    .slowCallDurationThreshold(Duration.ofMillis(100)).build());
            TimeLimiter tl = newTimeLimiter("t4", true);
            Callable<String> chain = CircuitBreaker.decorateCallable(cb,
                    TimeLimiter.decorateFutureSupplier(tl, () -> pool.submit(target.callable())));
            for (int i = 0; i < 3; i++) {
                thrownBy(chain);
            }
            CircuitBreaker.Metrics m = cb.getMetrics();
            log.append(String.format("  실패=%d, 느린 호출=%d (느리면서 성공=%d, 느리면서 실패=%d)%n",
                    m.getNumberOfFailedCalls(), m.getNumberOfSlowCalls(),
                    m.getNumberOfSlowSuccessfulCalls(), m.getNumberOfSlowFailedCalls()));
            log.append("  ").append(snap(cb)).append('\n');
            System.out.println(log);

            assertSoftly(softly -> {
                softly.assertThat(m.getNumberOfFailedCalls()).isEqualTo(3);
                softly.assertThat(m.getNumberOfSlowFailedCalls()).as("[가설] 타임아웃 호출(기록 소요 약 200ms)이 느리면서 실패로 집계").isEqualTo(3);
                softly.assertThat(m.getNumberOfSlowSuccessfulCalls()).isZero();
            });
        } finally {
            pool.shutdownNow();
        }
    }
}
