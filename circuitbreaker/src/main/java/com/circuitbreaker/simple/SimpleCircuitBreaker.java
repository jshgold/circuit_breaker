package com.circuitbreaker.simple;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 학습용 최소 서킷브레이커 (docs/phase1-concept-and-states.md 11장).
 *
 * <p>Resilience4j의 핵심 동작만 단순화해서 구현했다.
 * <ul>
 *   <li>COUNT_BASED 슬라이딩 윈도우 (원형 배열 + Subtract-on-Evict)</li>
 *   <li>minimumNumberOfCalls 이전에는 Open 판단 안 함</li>
 *   <li>Open -> Half-Open 은 lazy 전이 (호출이 들어온 순간 확인)</li>
 *   <li>Half-Open 은 Closed 와 별개의 새 윈도우(크기 = 시험 호출 수)로 판정</li>
 *   <li>거절된 호출은 윈도우에 기록하지 않고 별도 카운터만 증가</li>
 * </ul>
 *
 * <p>의도적으로 생략한 것: slow call 집계, TIME_BASED 윈도우, 예외 필터링,
 * maxWaitDurationInHalfOpenState, 이벤트/메트릭 발행.
 *
 * <p>동시성: 상태 판단/기록은 {@code synchronized} 로 직렬화하고, 실제 호출(fn)은
 * 락 밖에서 실행한다. 락 안에서 호출하면 느린 대상이 모든 호출을 막아버리기 때문이다.
 */
public final class SimpleCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Config(
            int slidingWindowSize,
            int minimumNumberOfCalls,
            double failureRateThreshold,   // 퍼센트 (0~100)
            Duration waitDurationInOpenState,
            int permittedNumberOfCallsInHalfOpenState) {

        public Config {
            if (slidingWindowSize <= 0 || permittedNumberOfCallsInHalfOpenState <= 0) {
                throw new IllegalArgumentException("window/permitted calls must be positive");
            }
            if (minimumNumberOfCalls <= 0 || minimumNumberOfCalls > slidingWindowSize) {
                // 윈도우가 minimumNumberOfCalls 보다 작으면 영영 평가되지 않으므로 막는다.
                throw new IllegalArgumentException("minimumNumberOfCalls must be in 1..slidingWindowSize");
            }
        }
    }

    private static final long NOT_PERMITTED = -1L;

    private final Config config;
    private final LongSupplier nanoClock;
    private final Object lock = new Object();

    // --- 아래 필드는 모두 lock 으로 보호된다 ---
    private State state = State.CLOSED;
    private long generation = 0;          // 상태가 바뀔 때마다 증가. 늦게 도착한 결과를 걸러내는 용도
    private long openedAtNanos;
    private Window window;                // CLOSED: slidingWindowSize, HALF_OPEN: 시험 호출 수
    private int halfOpenAcquired;         // HALF_OPEN 에서 이미 내보낸 시험 호출 수
    private long notPermittedCalls;       // 윈도우와 별개의 카운터

    public SimpleCircuitBreaker(Config config) {
        this(config, System::nanoTime);
    }

    /** 테스트에서 시간을 제어하기 위해 시계를 주입할 수 있다. */
    public SimpleCircuitBreaker(Config config, LongSupplier nanoClock) {
        this.config = config;
        this.nanoClock = nanoClock;
        this.window = new Window(config.slidingWindowSize());
    }

    /** fn 을 보호해서 실행한다. 차단되었거나 fn 이 실패하면 fallback 을 반환한다. */
    public <T> T call(Supplier<T> fn, Supplier<T> fallback) {
        long permit = tryAcquirePermission();
        if (permit == NOT_PERMITTED) {
            return fallback.get();               // fail-fast: 대상을 호출하지 않는다
        }
        T result;
        try {
            result = fn.get();
        } catch (RuntimeException e) {
            onResult(permit, false);
            return fallback.get();
        }
        onResult(permit, true);
        return result;
    }

    public State getState() {
        synchronized (lock) {
            // lazy 전이이므로 대기 시간이 지났어도 호출이 오기 전까지는 OPEN 으로 보인다.
            return state;
        }
    }

    public long getNotPermittedCalls() {
        synchronized (lock) {
            return notPermittedCalls;
        }
    }

    // ------------------------------------------------------------------

    /** 허용되면 현재 generation(>=0)을, 거절되면 NOT_PERMITTED 를 반환한다. */
    private long tryAcquirePermission() {
        synchronized (lock) {
            if (state == State.OPEN) {
                if (nanoClock.getAsLong() - openedAtNanos >= config.waitDurationInOpenState().toNanos()) {
                    transitionTo(State.HALF_OPEN);   // lazy: 이 호출이 전이를 일으킨다
                } else {
                    notPermittedCalls++;
                    return NOT_PERMITTED;
                }
            }
            if (state == State.HALF_OPEN) {
                if (halfOpenAcquired >= config.permittedNumberOfCallsInHalfOpenState()) {
                    notPermittedCalls++;             // 시험 호출 정원 초과 -> Open 처럼 거절
                    return NOT_PERMITTED;
                }
                halfOpenAcquired++;
            }
            return generation;
        }
    }

    private void onResult(long permitGeneration, boolean success) {
        synchronized (lock) {
            if (permitGeneration != generation) {
                // 예: CLOSED 에서 시작한 느린 호출이 HALF_OPEN 중에 끝난 경우.
                // 시험 호출이 아니므로 새 윈도우를 오염시키면 안 된다.
                return;
            }
            window.record(!success);
            if (state == State.CLOSED) {
                if (window.calls() >= config.minimumNumberOfCalls()
                        && window.failureRate() >= config.failureRateThreshold()) {
                    transitionTo(State.OPEN);
                }
            } else if (state == State.HALF_OPEN) {
                // 시험 호출이 모두 끝난 뒤에 판정한다.
                if (window.calls() >= config.permittedNumberOfCallsInHalfOpenState()) {
                    transitionTo(window.failureRate() >= config.failureRateThreshold()
                            ? State.OPEN : State.CLOSED);
                }
            }
        }
    }

    /** lock 을 쥔 상태에서만 호출한다. */
    private void transitionTo(State next) {
        state = next;
        generation++;
        switch (next) {
            case OPEN -> openedAtNanos = nanoClock.getAsLong();
            case HALF_OPEN -> {
                window = new Window(config.permittedNumberOfCallsInHalfOpenState());
                halfOpenAcquired = 0;
            }
            case CLOSED -> window = new Window(config.slidingWindowSize());
        }
    }

    /**
     * COUNT_BASED 슬라이딩 윈도우. 크기 N 의 원형 배열에 최근 N 개 결과를 저장하고,
     * 가장 오래된 값이 밀려날 때 합계에서 빼는(Subtract-on-Evict) 방식으로 O(1) 에 갱신한다.
     */
    static final class Window {
        private final boolean[] failed;   // true = 실패
        private int head;                 // 다음에 쓸 위치
        private int filled;               // 현재 저장된 개수 (<= size)
        private int failures;             // 저장된 것 중 실패 수

        Window(int size) {
            this.failed = new boolean[size];
        }

        void record(boolean isFailure) {
            if (filled == failed.length) {
                if (failed[head]) {
                    failures--;           // 밀려나는 값을 합계에서 제거
                }
            } else {
                filled++;
            }
            failed[head] = isFailure;
            if (isFailure) {
                failures++;
            }
            head = (head + 1) % failed.length;
        }

        int calls() {
            return filled;
        }

        double failureRate() {
            return filled == 0 ? 0.0 : failures * 100.0 / filled;
        }
    }
}
