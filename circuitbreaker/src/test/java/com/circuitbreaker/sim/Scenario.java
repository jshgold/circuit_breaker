package com.circuitbreaker.sim;

/**
 * @param outage              3초 시점에 Redis를 장애로 만들지
 * @param circuitBreaker      Redis 호출에 서킷브레이커를 걸지
 * @param dbGuard             DB 접근에 허용량 제한(대기 없이 즉시 degrade)을 둘지
 * @param dbAcquireTimeoutMs  DB 커넥션 획득 대기 시간
 */
record Scenario(String name, boolean outage, boolean circuitBreaker, boolean dbGuard, long dbAcquireTimeoutMs) {

    static final Scenario S1 = new Scenario("S1 기준선 (Redis 정상)", false, false, false, 3000);
    static final Scenario S2 = new Scenario("S2 Redis 장애, 서킷브레이커 없음", true, false, false, 3000);
    static final Scenario S3 = new Scenario("S3 서킷브레이커 + DB fallback (DB 대기 3초)", true, true, false, 3000);
    static final Scenario S4 = new Scenario("S4 S3 + DB 허용량 제한 (대기 없이 즉시 degrade)", true, true, true, 3000);
    static final Scenario S5 = new Scenario("S5 S3에서 DB 대기만 100ms로 단축", true, true, false, 100);
}
