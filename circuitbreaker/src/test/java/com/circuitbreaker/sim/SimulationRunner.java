package com.circuitbreaker.sim;

import com.circuitbreaker.simple.SimpleCircuitBreaker;
import com.circuitbreaker.simple.SimpleCircuitBreaker.Config;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 연쇄 장애 시뮬레이션 (docs/phase2-practice-plan.md).
 *
 * 구조: 부하 생성기(open-loop) -> 서버(스레드 풀 + 제한된 대기열) -> Redis -> (miss/장애) -> DB
 * health 요청은 의존 대상이 없지만 상품 요청과 같은 스레드 풀을 공유한다.
 *
 * <h3>한 번의 실행 흐름 (run -> Run.execute)</h3>
 * <pre>
 *  1. Run 생성자      : 시나리오 설정에 맞춰 부품을 만든다 (가짜 Redis/DB, 서킷브레이커, 서버 모형).
 *  2. 샘플러 시작     : 0.1초마다 서버 상태(활성 스레드, 대기열, DB 대기)를 기록한다.
 *  3. generateLoad()  : 13초 동안 요청을 고정 속도로 서버에 넣는다.
 *                       상품 200건/초(5ms마다), health 10건/초(100ms마다). 3초 지점에 Redis를 고장낸다.
 *  4. submit()        : 요청 1건을 서버 모형(스레드 20 + 대기열 10)에 넣는다.
 *                       대기열까지 가득 차면 즉시 거절(REJECTED)로 기록한다.
 *  5. handleProduct() : 서버 스레드가 상품 요청을 처리한다.
 *                       서킷브레이커로 감싼 Redis -> (miss 또는 Redis 못 씀) -> DB.
 *  6. 정리            : 처리 중이던 요청이 끝나길 기다린 뒤 샘플러를 멈추고 SimulationResult를 만든다.
 *  7. SimulationResult.print() : 초별 표와 안정 구간(5~13초) 요약을 출력한다.
 * </pre>
 *
 * <h3>서킷브레이커 구현 선택 (CbImpl)</h3>
 * 시나리오는 그대로 두고 Redis를 감싸는 서킷브레이커만 바꿀 수 있다.
 * <ul>
 *   <li>SIMPLE      : Phase 1에서 직접 만든 SimpleCircuitBreaker (기존 테스트 S1~S5)</li>
 *   <li>RESILIENCE4J: Resilience4j 2.4.0 CircuitBreaker (테스트 R4J_S3~S5)</li>
 * </ul>
 * 두 구현은 같은 설정값(윈도우 20, 최소 20, 임계치 50%, Open 대기 5초, 시험 호출 3)을 쓴다.
 * 부하 생성, 서버 모형, DB, 집계는 완전히 같아서 결과를 직접 비교할 수 있다.
 * 서킷브레이커를 쓰지 않는 시나리오(S1, S2)는 구현을 바꿔도 달라지지 않는다.
 */
final class SimulationRunner {

    // --- 파라미터 (계획서 2.1) ---
    static final int SERVER_THREADS = 20;
    static final int SERVER_QUEUE = 10;
    static final int PRODUCT_RPS = 200;
    static final int HEALTH_RPS = 10;
    static final double CACHE_HIT_RATE = 0.95;
    static final long REDIS_DOWN_HANG_MS = 200;
    static final int DB_CONNECTIONS = 2;
    static final long DB_QUERY_MS = 20;

    static final int OUTAGE_AT_SEC = 3;
    static final int TOTAL_SEC = 13;
    /** 장애 직후 과도 구간을 빼고 안정 구간만 집계하기 위한 시작 초. */
    static final int STEADY_FROM_SEC = 5;
    /** health는 이 시간 안에 응답해야 성공으로 친다 (로드밸런서 헬스체크 타임아웃 흉내). 계획서에 없던 기준. */
    static final long HEALTH_OK_MS = 500;

    enum Kind { PRODUCT, HEALTH }

    enum Outcome { OK_CACHE, OK_DB, DEGRADED, ERROR, REJECTED, HEALTH_OK, HEALTH_SLOW }

    record Event(Kind kind, int second, Outcome outcome, long latencyMs) {
    }

    record Gauge(int second, int active, int queued, int dbWaiting) {
    }

    /** Redis 장애로 값을 못 얻었다는 표시. */
    private static final Object UNAVAILABLE = new Object();

    /** 서킷브레이커 구현. SIMPLE은 Phase 1에서 직접 만든 것, RESILIENCE4J는 라이브러리(2.4.0). */
    enum CbImpl { SIMPLE, RESILIENCE4J }

    SimulationResult run(Scenario scenario) throws InterruptedException {
        return run(scenario, CbImpl.SIMPLE);
    }

    /** 서킷브레이커 구현만 바꿔서 같은 시나리오를 실행한다. 서킷브레이커를 쓰지 않는 시나리오(S1, S2)에서는 구현이 영향을 주지 않는다. */
    SimulationResult run(Scenario scenario, CbImpl impl) throws InterruptedException {
        return new Run(scenario, impl).execute();
    }

    /**
     * 시나리오 1번의 실행 상태를 담는 객체. run()을 부를 때마다 새로 만들어서 시나리오끼리 상태가 섞이지 않는다.
     * (서킷브레이커, 가짜 DB, 서버 스레드 풀, 기록한 이벤트가 모두 이 객체 안에 있다.)
     */
    private static final class Run {

        private final Scenario s;
        private final FakeRedis redis = new FakeRedis(CACHE_HIT_RATE, REDIS_DOWN_HANG_MS);
        private final FakeDb db;
        private final CbImpl impl;
        /** SIMPLE 구현용. 시나리오가 서킷브레이커를 쓰고 impl이 SIMPLE일 때만 값이 있고, 아니면 null. */
        private final SimpleCircuitBreaker cb;
        /**
         * RESILIENCE4J 구현용. 시나리오가 서킷브레이커를 쓰고 impl이 RESILIENCE4J일 때만 값이 있고, 아니면 null.
         * cb와 r4jCb는 둘 중 최대 하나만 non-null이다 (둘 다 null이면 서킷브레이커 없음: S1, S2).
         * 상태가 바뀌면 라이브러리의 거절 카운터가 0으로 초기화되므로 거절 횟수는 핸들러가 직접 센다.
         */
        private final CircuitBreaker r4jCb;
        /** r4jCb가 막아서 거절한 호출의 누적 횟수 (라이브러리 카운터 대신 직접 셈). */
        private final AtomicLong r4jRejected = new AtomicLong();
        /** DB 접근 허용량 제한 (S4). 자리가 없으면 기다리지 않고 바로 DEGRADED. 시나리오가 쓰지 않으면 null. */
        private final Semaphore dbGuard;
        /** 서버 모형: 스레드 20개 + 대기열 10칸, 대기열까지 차면 요청을 거절한다. */
        private final ThreadPoolExecutor server;
        /** 요청 1건마다 결과 1줄을 쌓는다 (여러 스레드가 동시에 쓰므로 동시성 큐). */
        private final Queue<Event> events = new ConcurrentLinkedQueue<>();
        /** 0.1초마다 찍는 서버 상태 사진. */
        private final Queue<Gauge> gauges = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean stopSampler = new AtomicBoolean();
        /** 실행 시작 시각(나노초). 모든 "몇 초"는 이 시각 기준이다. */
        private final long start;

        Run(Scenario s, CbImpl impl) {
            this.s = s;
            this.impl = impl;
            this.db = new FakeDb(DB_CONNECTIONS, DB_QUERY_MS, s.dbAcquireTimeoutMs());
            // 두 구현 모두 같은 값: 윈도우 20, 최소 20, 임계치 50%, Open 대기 5초, 시험 호출 3
            this.cb = (s.circuitBreaker() && impl == CbImpl.SIMPLE)
                    ? new SimpleCircuitBreaker(new Config(20, 20, 50.0, Duration.ofSeconds(5), 3))
                    : null;
            this.r4jCb = (s.circuitBreaker() && impl == CbImpl.RESILIENCE4J)
                    ? CircuitBreaker.of("sim-redis", CircuitBreakerConfig.custom()
                            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                            .slidingWindowSize(20)
                            .minimumNumberOfCalls(20)
                            .failureRateThreshold(50f)
                            .waitDurationInOpenState(Duration.ofSeconds(5))
                            .permittedNumberOfCallsInHalfOpenState(3)
                            .build())
                    : null;
            this.dbGuard = s.dbGuard() ? new Semaphore(DB_CONNECTIONS) : null;
            this.server = new ThreadPoolExecutor(
                    SERVER_THREADS, SERVER_THREADS, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(SERVER_QUEUE),
                    runnable -> {
                        Thread t = new Thread(runnable, "server-worker");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            this.start = System.nanoTime();
        }

        /** 시나리오 한 번을 끝까지 실행하고 결과를 돌려준다. generateLoad()가 13초 동안 반환하지 않으므로 이 메서드도 그만큼 걸린다. */
        SimulationResult execute() throws InterruptedException {
            Thread sampler = new Thread(this::sampleLoop, "sampler");
            sampler.setDaemon(true);
            sampler.start();

            generateLoad();         // 13초 동안 요청 투입 (3초 지점에 Redis 장애 발생)

            // 투입은 끝났지만 아직 처리 중이거나 대기열에 있는 요청이 있다. 그 결과까지 기록되도록 끝날 때까지 기다린다.
            // (기다리지 않고 집계하면 장애 구간의 느린 요청 결과가 빠진다.)
            server.shutdown();
            boolean drained = server.awaitTermination(30, TimeUnit.SECONDS);
            if (!drained) {
                server.shutdownNow();
            }
            stopSampler.set(true);
            sampler.join(1000);

            // 출력 제목에 "[Resilience4j]"를 붙여 어느 구현의 결과인지 구분한다 (설정은 그대로 복사).
            Scenario shown = impl == CbImpl.RESILIENCE4J
                    ? new Scenario(s.name() + " [Resilience4j]", s.outage(), s.circuitBreaker(), s.dbGuard(), s.dbAcquireTimeoutMs())
                    : s;
            // 서킷브레이커 종료 시 상태와 거절 횟수. 구현마다 읽는 곳이 다르다 (SIMPLE은 자체 누적 카운터, R4J는 직접 센 값).
            String cbState = cb != null ? cb.getState().name() : r4jCb != null ? r4jCb.getState().name() : "-";
            long cbRejected = cb != null ? cb.getNotPermittedCalls() : r4jCb != null ? r4jRejected.get() : 0;
            return new SimulationResult(
                    shown,
                    new ArrayList<>(events),
                    new ArrayList<>(gauges),
                    cbState,
                    cbRejected,
                    drained);
        }

        /**
         * open-loop 부하 생성: 서버 응답과 무관하게 고정 속도로 요청을 투입한다.
         * 지연은 "실제 투입 시각"이 아니라 "투입됐어야 하는 시각"부터 재서, 생성기가 밀려도 지연이 가려지지 않게 한다.
         */
        private void generateLoad() {
            long productInterval = 1_000_000_000L / PRODUCT_RPS;
            long healthInterval = 1_000_000_000L / HEALTH_RPS;
            long nextProduct = start;
            long nextHealth = start;
            long end = start + TOTAL_SEC * 1_000_000_000L;
            long outageAt = start + OUTAGE_AT_SEC * 1_000_000_000L;
            boolean flipped = false;

            while (true) {
                long now = System.nanoTime();
                if (now >= end) {
                    break;
                }
                if (s.outage() && !flipped && now >= outageAt) {
                    redis.setDown(true);
                    flipped = true;
                }
                if (now >= nextProduct) {
                    submit(Kind.PRODUCT, nextProduct);
                    nextProduct += productInterval;
                    continue;
                }
                if (now >= nextHealth) {
                    submit(Kind.HEALTH, nextHealth);
                    nextHealth += healthInterval;
                    continue;
                }
                long wait = Math.min(nextProduct, nextHealth) - now;
                LockSupport.parkNanos(Math.min(wait, 1_000_000L));
            }
        }

        /**
         * 요청 1건을 서버 모형에 넣는다.
         * <pre>
         *  스레드가 비어 있다        -> 바로 처리
         *  스레드는 다 바쁘다        -> 대기열(10칸)에 줄을 선다
         *  대기열도 가득 차 있다      -> RejectedExecutionException -> REJECTED 기록 (처리 없이 끝)
         * </pre>
         * 처리가 끝나면 Event(종류, 도착한 초, 결과, 걸린 시간)를 기록한다. 걸린 시간은 대기열에서 기다린 시간을 포함한다.
         * health 요청은 하는 일이 없고, 도착부터 끝까지 500ms 안이면 HEALTH_OK, 넘으면 HEALTH_SLOW다.
         *
         * @param arrivalNs 이 요청이 "도착했어야 하는" 시각 (부하 생성기가 밀려도 지연이 가려지지 않게 이 시각부터 잰다)
         */
        private void submit(Kind kind, long arrivalNs) {
            int second = (int) Math.min(TOTAL_SEC - 1, (arrivalNs - start) / 1_000_000_000L);
            try {
                server.execute(() -> {
                    Outcome outcome;
                    try {
                        outcome = kind == Kind.PRODUCT ? handleProduct() : Outcome.HEALTH_OK;
                    } catch (Throwable t) {
                        outcome = Outcome.ERROR;
                    }
                    long latencyMs = (System.nanoTime() - arrivalNs) / 1_000_000L;
                    if (kind == Kind.HEALTH) {
                        outcome = latencyMs <= HEALTH_OK_MS ? Outcome.HEALTH_OK : Outcome.HEALTH_SLOW;
                    }
                    events.add(new Event(kind, second, outcome, latencyMs));
                });
            } catch (RejectedExecutionException e) {
                events.add(new Event(kind, second, Outcome.REJECTED, 0));
            }
        }

        /**
         * 상품 조회 한 건을 서버 스레드가 처리한다. 시나리오 차이(S1~S5)가 가장 크게 드러나는 곳이다.
         * <pre>
         *  1단계 Redis 조회 (서킷브레이커 경유)
         *     정상 hit        -> OK_CACHE (끝)
         *     정상 miss       -> 3단계로
         *     서킷이 막음      -> "Redis 못 씀" 표시(UNAVAILABLE) -> 3단계로
         *     Redis 실패      -> "Redis 못 씀" 표시(UNAVAILABLE) -> 3단계로
         *
         *  3단계 DB 조회
         *     dbGuard가 있고 자리가 없다 -> DEGRADED (기다리지 않고 대체 응답, S4)
         *     커넥션을 못 얻고 타임아웃   -> ERROR
         *     성공                     -> OK_DB
         * </pre>
         * 서킷브레이커는 Redis 호출 하나만 감싼다. DB는 서킷브레이커가 없고, DB 쪽 보호는 dbGuard(S4)뿐이다.
         * 그래서 서킷이 열려 Redis를 건너뛰면 요청이 전부 DB로 몰린다 (Phase 2의 핵심 현상).
         */
        private Outcome handleProduct() {
            Object cached;
            if (r4jCb != null) {
                // Resilience4j 경로. executeSupplier는 예외를 그대로 던지므로 두 종류를 직접 fallback 처리한다.
                // (SIMPLE 경로의 cb.call(fn, fallback)이 내부에서 해 주던 일이다.)
                try {
                    cached = r4jCb.executeSupplier(redis::get);
                } catch (CallNotPermittedException e) {
                    r4jRejected.incrementAndGet();      // 서킷이 열려 있어 Redis를 호출하지도 않고 막음 -> fallback
                    cached = UNAVAILABLE;
                } catch (FakeRedis.RedisDownException e) {
                    cached = UNAVAILABLE;               // Redis 호출이 200ms 매달린 뒤 실패 (이 실패가 서킷에 기록됨) -> fallback
                }
            } else if (cb != null) {
                // 직접 만든 구현 경로. 막힘/실패 모두 fallback(UNAVAILABLE)을 돌려준다.
                cached = cb.<Object>call(redis::get, () -> UNAVAILABLE);
            } else {
                try {
                    cached = redis.get();
                } catch (FakeRedis.RedisDownException e) {
                    cached = UNAVAILABLE;       // 서킷브레이커는 없어도 fallback은 있다
                }
            }
            if (cached != null && cached != UNAVAILABLE) {
                return Outcome.OK_CACHE;
            }

            // miss 이거나 Redis를 못 썼으므로 DB 조회
            if (dbGuard != null && !dbGuard.tryAcquire()) {
                return Outcome.DEGRADED;        // 허용량 초과: 기다리지 않고 대체 응답
            }
            try {
                db.query();
                return Outcome.OK_DB;
            } catch (FakeDb.DbTimeoutException e) {
                return Outcome.ERROR;
            } finally {
                if (dbGuard != null) {
                    dbGuard.release();
                }
            }
        }

        private void sampleLoop() {
            while (!stopSampler.get()) {
                int second = (int) ((System.nanoTime() - start) / 1_000_000_000L);
                gauges.add(new Gauge(second, server.getActiveCount(), server.getQueue().size(), db.waiting()));
                LockSupport.parkNanos(100_000_000L);
            }
        }
    }
}
