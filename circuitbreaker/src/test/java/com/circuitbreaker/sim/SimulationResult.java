package com.circuitbreaker.sim;

import com.circuitbreaker.sim.SimulationRunner.Event;
import com.circuitbreaker.sim.SimulationRunner.Gauge;
import com.circuitbreaker.sim.SimulationRunner.Kind;
import com.circuitbreaker.sim.SimulationRunner.Outcome;

import java.util.Comparator;
import java.util.List;

import static com.circuitbreaker.sim.SimulationRunner.STEADY_FROM_SEC;
import static com.circuitbreaker.sim.SimulationRunner.TOTAL_SEC;

/** 시뮬레이션 한 번의 결과. 이벤트는 "요청이 도착한 초" 기준으로 집계한다. */
final class SimulationResult {

    private final Scenario scenario;
    private final List<Event> events;
    private final List<Gauge> gauges;
    private final String cbState;
    private final long cbNotPermitted;
    private final boolean drained;

    SimulationResult(Scenario scenario, List<Event> events, List<Gauge> gauges,
                     String cbState, long cbNotPermitted, boolean drained) {
        this.scenario = scenario;
        this.events = events;
        this.gauges = gauges;
        this.cbState = cbState;
        this.cbNotPermitted = cbNotPermitted;
        this.drained = drained;
    }

    // ------------------------------------------------------------ 집계

    private long count(Kind kind, Outcome outcome, int from, int to) {
        return events.stream()
                .filter(e -> e.kind() == kind && e.outcome() == outcome && e.second() >= from && e.second() < to)
                .count();
    }

    private long total(Kind kind, int from, int to) {
        return events.stream().filter(e -> e.kind() == kind && e.second() >= from && e.second() < to).count();
    }

    /** 안정 구간에서 초당 투입된 상품 요청 수. 부하 생성기가 목표 속도를 냈는지 확인용. */
    double offeredProductRps() {
        return total(Kind.PRODUCT, STEADY_FROM_SEC, TOTAL_SEC) / (double) (TOTAL_SEC - STEADY_FROM_SEC);
    }

    /** 안정 구간의 health 성공률 (0~1). 거절, 느린 응답은 실패. */
    double healthOkRate() {
        long total = total(Kind.HEALTH, STEADY_FROM_SEC, TOTAL_SEC);
        return total == 0 ? 0 : count(Kind.HEALTH, Outcome.HEALTH_OK, STEADY_FROM_SEC, TOTAL_SEC) / (double) total;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private List<Long> latencies(Kind kind, int from, int to) {
        return events.stream()
                .filter(e -> e.kind() == kind && e.outcome() != Outcome.REJECTED
                        && e.second() >= from && e.second() < to)
                .map(Event::latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    // ------------------------------------------------------------ 출력

    void print() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==================================================================\n");
        sb.append(scenario.name()).append('\n');
        sb.append("==================================================================\n");
        sb.append("초별 (요청이 도착한 초 기준)  [장애 시작: ").append(SimulationRunner.OUTAGE_AT_SEC)
                .append("초, 안정 구간: ").append(STEADY_FROM_SEC).append("초~]\n");
        sb.append(String.format("%3s | %6s %6s %6s %6s %6s | %8s%n",
                "초", "캐시OK", "DB_OK", "degrad", "에러", "거절", "health"));
        for (int sec = 0; sec < TOTAL_SEC; sec++) {
            long healthTotal = total(Kind.HEALTH, sec, sec + 1);
            long healthOk = count(Kind.HEALTH, Outcome.HEALTH_OK, sec, sec + 1);
            sb.append(String.format("%3d | %6d %6d %6d %6d %6d | %3d/%-3d%n", sec,
                    count(Kind.PRODUCT, Outcome.OK_CACHE, sec, sec + 1),
                    count(Kind.PRODUCT, Outcome.OK_DB, sec, sec + 1),
                    count(Kind.PRODUCT, Outcome.DEGRADED, sec, sec + 1),
                    count(Kind.PRODUCT, Outcome.ERROR, sec, sec + 1),
                    count(Kind.PRODUCT, Outcome.REJECTED, sec, sec + 1),
                    healthOk, healthTotal));
        }

        int from = STEADY_FROM_SEC;
        int to = TOTAL_SEC;
        double secs = to - from;
        long productTotal = total(Kind.PRODUCT, from, to);
        sb.append(String.format("%n[안정 구간 %d~%d초 요약]%n", from, to));
        sb.append(String.format("  투입된 상품 요청: %.0f rps (목표 %d)%n", productTotal / secs, SimulationRunner.PRODUCT_RPS));
        sb.append(String.format("  상품 결과 비율: 캐시OK %.0f%% / DB_OK %.0f%% / degrade %.0f%% / 에러 %.0f%% / 거절 %.0f%%%n",
                pct(count(Kind.PRODUCT, Outcome.OK_CACHE, from, to), productTotal),
                pct(count(Kind.PRODUCT, Outcome.OK_DB, from, to), productTotal),
                pct(count(Kind.PRODUCT, Outcome.DEGRADED, from, to), productTotal),
                pct(count(Kind.PRODUCT, Outcome.ERROR, from, to), productTotal),
                pct(count(Kind.PRODUCT, Outcome.REJECTED, from, to), productTotal)));
        sb.append(String.format("  DB로 실제 처리된 쿼리: 약 %.0f rps (DB 처리 능력 %d rps)%n",
                count(Kind.PRODUCT, Outcome.OK_DB, from, to) / secs,
                SimulationRunner.DB_CONNECTIONS * 1000 / SimulationRunner.DB_QUERY_MS));

        long healthTotal = total(Kind.HEALTH, from, to);
        sb.append(String.format("  health 성공률(%dms 이내): %.0f%% (%d/%d), 거절 %d, 느림 %d%n",
                SimulationRunner.HEALTH_OK_MS,
                pct(count(Kind.HEALTH, Outcome.HEALTH_OK, from, to), healthTotal),
                count(Kind.HEALTH, Outcome.HEALTH_OK, from, to), healthTotal,
                count(Kind.HEALTH, Outcome.REJECTED, from, to),
                count(Kind.HEALTH, Outcome.HEALTH_SLOW, from, to)));

        List<Long> hl = latencies(Kind.HEALTH, from, to);
        List<Long> pl = latencies(Kind.PRODUCT, from, to);
        sb.append(String.format("  health 지연(거절 제외) p50/p99: %d / %d ms%n", percentile(hl, 0.5), percentile(hl, 0.99)));
        sb.append(String.format("  상품 지연(거절 제외) p50/p99: %d / %d ms%n", percentile(pl, 0.5), percentile(pl, 0.99)));

        List<Gauge> g = gauges.stream().filter(x -> x.second() >= from && x.second() < to).toList();
        sb.append(String.format("  서버 활성 스레드 평균/최대: %.1f / %d (풀 크기 %d)%n",
                g.stream().mapToInt(Gauge::active).average().orElse(0),
                g.stream().mapToInt(Gauge::active).max().orElse(0), SimulationRunner.SERVER_THREADS));
        sb.append(String.format("  서버 대기열 평균/최대: %.1f / %d (한도 %d)%n",
                g.stream().mapToInt(Gauge::queued).average().orElse(0),
                g.stream().mapToInt(Gauge::queued).max().orElse(0), SimulationRunner.SERVER_QUEUE));
        sb.append(String.format("  DB 커넥션 대기 스레드 평균/최대: %.1f / %d%n",
                g.stream().mapToInt(Gauge::dbWaiting).average().orElse(0),
                g.stream().mapToInt(Gauge::dbWaiting).max().orElse(0)));
        sb.append(String.format("  서킷브레이커 종료 시 상태: %s, 거절 호출 누계: %d%n", cbState, cbNotPermitted));
        if (!drained) {
            sb.append("  경고: 부하 종료 후 30초 안에 서버 작업이 모두 끝나지 않았다\n");
        }
        System.out.println(sb);
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0 : part * 100.0 / whole;
    }
}
