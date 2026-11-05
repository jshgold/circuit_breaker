package com.circuitbreaker.sim;

import com.circuitbreaker.simple.SimpleCircuitBreaker;
import com.circuitbreaker.simple.SimpleCircuitBreaker.Config;

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
import java.util.concurrent.locks.LockSupport;

/**
 * 연쇄 장애 시뮬레이션 (docs/phase2-practice-plan.md).
 *
 * 구조: 부하 생성기(open-loop) -> 서버(스레드 풀 + 제한된 대기열) -> Redis -> (miss/장애) -> DB
 * health 요청은 의존 대상이 없지만 상품 요청과 같은 스레드 풀을 공유한다.
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

    SimulationResult run(Scenario scenario) throws InterruptedException {
        return new Run(scenario).execute();
    }

    private static final class Run {

        private final Scenario s;
        private final FakeRedis redis = new FakeRedis(CACHE_HIT_RATE, REDIS_DOWN_HANG_MS);
        private final FakeDb db;
        private final SimpleCircuitBreaker cb;
        private final Semaphore dbGuard;
        private final ThreadPoolExecutor server;
        private final Queue<Event> events = new ConcurrentLinkedQueue<>();
        private final Queue<Gauge> gauges = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean stopSampler = new AtomicBoolean();
        private final long start;

        Run(Scenario s) {
            this.s = s;
            this.db = new FakeDb(DB_CONNECTIONS, DB_QUERY_MS, s.dbAcquireTimeoutMs());
            this.cb = s.circuitBreaker()
                    ? new SimpleCircuitBreaker(new Config(20, 20, 50.0, Duration.ofSeconds(5), 3))
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

        SimulationResult execute() throws InterruptedException {
            Thread sampler = new Thread(this::sampleLoop, "sampler");
            sampler.setDaemon(true);
            sampler.start();

            generateLoad();

            server.shutdown();
            boolean drained = server.awaitTermination(30, TimeUnit.SECONDS);
            if (!drained) {
                server.shutdownNow();
            }
            stopSampler.set(true);
            sampler.join(1000);

            return new SimulationResult(
                    s,
                    new ArrayList<>(events),
                    new ArrayList<>(gauges),
                    cb == null ? "-" : cb.getState().name(),
                    cb == null ? 0 : cb.getNotPermittedCalls(),
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

        /** 상품 조회: Redis -> (miss 또는 Redis 실패) -> DB. */
        private Outcome handleProduct() {
            Object cached;
            if (cb != null) {
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
