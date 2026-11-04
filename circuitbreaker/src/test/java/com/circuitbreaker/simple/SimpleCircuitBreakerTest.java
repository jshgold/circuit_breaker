package com.circuitbreaker.simple;

import com.circuitbreaker.simple.SimpleCircuitBreaker.Config;
import com.circuitbreaker.simple.SimpleCircuitBreaker.State;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleCircuitBreakerTest {

    /** 테스트에서 시간을 직접 흘려보내기 위한 가짜 시계. */
    static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong();
        long now() { return nanos.get(); }
        void advance(Duration d) { nanos.addAndGet(d.toNanos()); }
    }

    private static final Config CONFIG = new Config(10, 10, 50.0, Duration.ofSeconds(30), 3);

    private static String ok() { return "ok"; }
    private static String boom() { throw new IllegalStateException("downstream failure"); }

    private static void callTimes(SimpleCircuitBreaker cb, int times, boolean fail) {
        for (int i = 0; i < times; i++) {
            cb.call(fail ? SimpleCircuitBreakerTest::boom : SimpleCircuitBreakerTest::ok, () -> "fallback");
        }
    }

    // ---------------------------------------------------------------- 상태 전이

    @Test
    void 최소_호출_수를_채우기_전에는_실패율이_100퍼센트여도_열리지_않는다() {
        var cb = new SimpleCircuitBreaker(CONFIG, new FakeClock()::now);

        callTimes(cb, 9, true);   // minimumNumberOfCalls = 10 이므로 아직 평가 안 함

        assertThat(cb.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void 최소_호출_수를_채우고_실패율이_임계치_이상이면_열린다() {
        var cb = new SimpleCircuitBreaker(CONFIG, new FakeClock()::now);

        callTimes(cb, 5, false);
        callTimes(cb, 5, true);   // 10번째 호출에서 실패율 50% -> OPEN

        assertThat(cb.getState()).isEqualTo(State.OPEN);

    }

    @Test
    void 실패율이_임계치_미만이면_닫힌_상태를_유지한다() {
        var cb = new SimpleCircuitBreaker(CONFIG, new FakeClock()::now);

        callTimes(cb, 6, false);
        callTimes(cb, 4, true);   // 40%

        assertThat(cb.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void 열린_상태에서는_대상을_호출하지_않고_fallback_을_반환한다() {
        var cb = new SimpleCircuitBreaker(CONFIG, new FakeClock()::now);
        callTimes(cb, 10, true);
        assertThat(cb.getState()).isEqualTo(State.OPEN);

        var invoked = new AtomicInteger();
        String result = cb.call(() -> { invoked.incrementAndGet(); return "real"; }, () -> "fallback");

        assertThat(result).isEqualTo("fallback");
        assertThat(invoked.get()).isZero();            // fail-fast: 대상은 건드리지 않았다
        assertThat(cb.getNotPermittedCalls()).isEqualTo(1);
    }

    @Test
    void 대기_시간이_지나도_호출이_오기_전까지는_OPEN_으로_보인다_lazy_전이() {
        var clock = new FakeClock();
        var cb = new SimpleCircuitBreaker(CONFIG, clock::now);
        callTimes(cb, 10, true);

        clock.advance(Duration.ofSeconds(31));

        assertThat(cb.getState()).isEqualTo(State.OPEN);   // 아직 아무도 호출하지 않았다
        cb.call(SimpleCircuitBreakerTest::ok, () -> "fallback");
        assertThat(cb.getState()).isEqualTo(State.HALF_OPEN);  // 호출이 전이를 일으켰다
    }

    @Test
    void 대기_시간_전에는_계속_차단된다() {
        var clock = new FakeClock();
        var cb = new SimpleCircuitBreaker(CONFIG, clock::now);
        callTimes(cb, 10, true);

        clock.advance(Duration.ofSeconds(29));
        String result = cb.call(SimpleCircuitBreakerTest::ok, () -> "fallback");

        assertThat(result).isEqualTo("fallback");
        assertThat(cb.getState()).isEqualTo(State.OPEN);
    }

    @Test
    void 반열림에서_시험_호출이_성공하면_닫힌다() {
        var clock = new FakeClock();
        var cb = new SimpleCircuitBreaker(CONFIG, clock::now);
        callTimes(cb, 10, true);
        clock.advance(Duration.ofSeconds(31));

        callTimes(cb, 3, false);   // permittedNumberOfCallsInHalfOpenState = 3

        assertThat(cb.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void 반열림에서_시험_호출이_실패하면_다시_열린다() {
        var clock = new FakeClock();
        var cb = new SimpleCircuitBreaker(CONFIG, clock::now);
        callTimes(cb, 10, true);
        clock.advance(Duration.ofSeconds(31));

        callTimes(cb, 3, true);

        assertThat(cb.getState()).isEqualTo(State.OPEN);
        // 다시 열렸으므로 대기 시간이 처음부터 다시 시작된다
        assertThat(cb.call(SimpleCircuitBreakerTest::ok, () -> "fallback")).isEqualTo("fallback");
    }

    @Test
    void 반열림은_닫힘_때의_기록을_쓰지_않는_새_윈도우다() {
        var clock = new FakeClock();
        var cb = new SimpleCircuitBreaker(CONFIG, clock::now);
        callTimes(cb, 10, true);                 // 닫힘 윈도우는 실패 100%
        clock.advance(Duration.ofSeconds(31));

        callTimes(cb, 3, false);                 // 시험 호출 3개 모두 성공

        // 과거 실패 기록이 섞였다면 닫히지 못했을 것이다
        assertThat(cb.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void 슬라이딩_윈도우는_오래된_결과를_밀어낸다() {
        var cb = new SimpleCircuitBreaker(new Config(4, 4, 50.0, Duration.ofSeconds(30), 2), new FakeClock()::now);

        callTimes(cb, 1, true);
        callTimes(cb, 3, false);       // [F, S, S, S] -> 25%
        assertThat(cb.getState()).isEqualTo(State.CLOSED);

        callTimes(cb, 1, true);        // [S, S, S, F]  (첫 F 가 밀려남) -> 25%
        callTimes(cb, 1, true);        // [S, S, F, F] -> 50% -> OPEN
        assertThat(cb.getState()).isEqualTo(State.OPEN);
    }

    // ---------------------------------------------------------------- 실험 2: 동시성

    @Test
    void 반열림에서_동시에_몰려도_시험_호출은_정확히_정원만큼만_나간다() throws Exception {
        var clock = new FakeClock();
        var cb = new SimpleCircuitBreaker(CONFIG, clock::now);
        callTimes(cb, 10, true);
        clock.advance(Duration.ofSeconds(31));

        int threads = 50;
        var executing = new AtomicInteger();          // 대상에 실제로 들어간 호출 수
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    started.countDown();
                    started.await();                  // 전 스레드가 동시에 출발
                    return cb.call(() -> {
                        executing.incrementAndGet();
                        awaitQuietly(release);        // 시험 호출은 끝나지 않고 붙잡아 둔다
                        return "real";
                    }, () -> "fallback");
                }));
            }
            // 거절된 스레드들이 먼저 끝날 때까지 기다린다
            Thread.sleep(500);

            assertThat(executing.get()).isEqualTo(CONFIG.permittedNumberOfCallsInHalfOpenState());
            release.countDown();
            long real = 0;
            for (var f : futures) {
                if (f.get(5, TimeUnit.SECONDS).equals("real")) real++;
            }
            assertThat(real).isEqualTo(3);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 실험 1: 스레드 점유

    /**
     * 느려지다 실패하는 대상(호출당 100ms 후 예외)에 100건을 보낸다.
     * 서킷브레이커가 없으면 100건 전부 대상에 들어가 100ms 씩 스레드를 점유하지만,
     * 있으면 처음 몇 건만 들어가고 나머지는 즉시 fallback 된다.
     */
    @Test
    void 서킷브레이커가_있으면_장애_대상으로_가는_호출과_점유_시간이_크게_줄어든다() throws Exception {
        int requests = 100;
        int threads = 10;
        long slowMillis = 100;

        var withoutCb = new AtomicInteger();
        long tWithout = runLoad(requests, threads, () -> {
            withoutCb.incrementAndGet();
            sleepQuietly(slowMillis);
            return boom();
        }, null);

        var withCb = new AtomicInteger();
        var cb = new SimpleCircuitBreaker(new Config(10, 10, 50.0, Duration.ofSeconds(60), 3));
        long tWith = runLoad(requests, threads, () -> {
            withCb.incrementAndGet();
            sleepQuietly(slowMillis);
            return boom();
        }, cb);

        System.out.printf("[실험1] CB 없음: 대상 호출 %d건, %dms / CB 있음: 대상 호출 %d건, %dms%n",
                withoutCb.get(), tWithout, withCb.get(), tWith);

        assertThat(withoutCb.get()).isEqualTo(requests);
        // 윈도우가 차는 동안 동시에 진행 중이던 호출까지 최대 (10 + 스레드 수) 건은 들어갈 수 있다
        assertThat(withCb.get()).isLessThanOrEqualTo(10 + threads);
        assertThat(tWith).isLessThan(tWithout / 2);
    }

    private static long runLoad(int requests, int threads, java.util.function.Supplier<String> target,
                                SimpleCircuitBreaker cb) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    if (cb == null) {
                        try { return target.get(); } catch (RuntimeException e) { return "fallback"; }
                    }
                    return cb.call(target, () -> "fallback");
                }));
            }
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    // ---------------------------------------------------------------- 실험 3: 저트래픽

    @Test
    void 저트래픽에서는_최소_호출_수_때문에_장애가_나도_닫힌_채로_남는다() {
        // 기본값과 같은 구조: window=100, min=100. 분당 소수의 호출만 오는 서비스를 흉내낸다.
        var cb = new SimpleCircuitBreaker(new Config(100, 100, 50.0, Duration.ofSeconds(60), 10), new FakeClock()::now);

        callTimes(cb, 99, true);   // 99번 연속 실패 (100%) 인데도

        assertThat(cb.getState()).isEqualTo(State.CLOSED);

        callTimes(cb, 1, true);    // 100번째에서야 평가된다
        assertThat(cb.getState()).isEqualTo(State.OPEN);
    }

    // ---------------------------------------------------------------- 유틸

    private static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
