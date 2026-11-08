package com.circuitbreaker.r4j.spring;

import com.circuitbreaker.r4j.spring.SpringFixtures.HealthService;
import com.circuitbreaker.r4j.spring.SpringFixtures.SvcService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 3_4 실습 Q: actuator (docs/phase3_4-resilience4j-spring-integration.md 4장 Q).
 * 실행: ./gradlew simulationTest --tests '*ResilienceSpringActuatorTest*'
 *
 * 서버를 임의 포트로 띄우고 JDK HttpClient로 actuator 엔드포인트를 호출한다.
 */
@Tag("simulation")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("r4jspring")
@Import(SpringFixtures.Beans.class)
class ResilienceSpringActuatorTest {

    @Value("${local.server.port}")
    int port;
    @Autowired
    CircuitBreakerRegistry registry;
    @Autowired
    SvcService svc;
    @Autowired
    HealthService healthService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetAll() {
        registry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        svc.reset();
    }

    private record Resp(int status, String body) {
    }

    private Resp get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    private Resp post(String path, String json) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...(총 " + s.length() + "자)";
    }

    // ------------------------------------------------------------ Q1

    @Test
    void Q1_circuitbreakers_엔드포인트로_상태를_조회한다() throws Exception {
        Resp r = get("/actuator/circuitbreakers");
        System.out.println("\n[Q1] GET /actuator/circuitbreakers -> " + r.status() + "\n  " + cut(r.body(), 1800));
        assertSoftly(softly -> {
            softly.assertThat(r.status()).as("[가설] 200").isEqualTo(200);
            softly.assertThat(r.body()).as("[가설] 등록된 인스턴스 이름이 나온다").contains("svc").contains("basic");
            softly.assertThat(r.body()).as("[가설] 상태가 나온다").contains("CLOSED");
        });
    }

    // ------------------------------------------------------------ Q2

    @Test
    void Q2_상태_변경_엔드포인트() throws Exception {
        StringBuilder log = new StringBuilder("\n[Q2] POST /actuator/circuitbreakers/svc 로 상태 변경\n");
        CircuitBreaker cb = registry.circuitBreaker("svc");

        Resp force = post("/actuator/circuitbreakers/svc", "{\"updateState\":\"FORCE_OPEN\"}");
        log.append("  FORCE_OPEN 요청 -> ").append(force.status()).append(" ").append(cut(force.body(), 300))
                .append(" | 레지스트리 상태=").append(cb.getState()).append('\n');
        String whileForced = svc.call("a");
        log.append("  FORCE_OPEN 중 어노테이션 호출 -> ").append(whileForced).append(" | 대상 호출 ").append(svc.targetCalls())
                .append(", fallback 원인=").append(svc.lastFallbackCause() == null ? null : svc.lastFallbackCause().getClass().getSimpleName()).append('\n');
        CircuitBreaker.State stateForced = cb.getState();

        Resp close = post("/actuator/circuitbreakers/svc", "{\"updateState\":\"CLOSE\"}");
        log.append("  CLOSE 요청 -> ").append(close.status()).append(" ").append(cut(close.body(), 300))
                .append(" | 레지스트리 상태=").append(cb.getState()).append('\n');
        String afterClose = svc.call("b");
        log.append("  CLOSE 후 어노테이션 호출 -> ").append(afterClose).append(" | 대상 호출 ").append(svc.targetCalls()).append('\n');

        Resp disable = post("/actuator/circuitbreakers/svc", "{\"updateState\":\"DISABLE\"}");
        log.append("  DISABLE 요청 -> ").append(disable.status()).append(" | 레지스트리 상태=").append(cb.getState()).append('\n');

        Resp bad = post("/actuator/circuitbreakers/svc", "{\"updateState\":\"METRICS_ONLY\"}");
        log.append("  [추가] METRICS_ONLY 요청 -> ").append(bad.status()).append(" ").append(cut(bad.body(), 300))
                .append(" | 레지스트리 상태=").append(cb.getState()).append('\n');

        Resp unknown = post("/actuator/circuitbreakers/nothere", "{\"updateState\":\"CLOSE\"}");
        log.append("  [추가] 존재하지 않는 이름 -> ").append(unknown.status()).append(" ").append(cut(unknown.body(), 300)).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(force.status()).as("[가설] FORCE_OPEN 요청 성공(200)").isEqualTo(200);
            softly.assertThat(stateForced).as("[가설] 레지스트리 상태가 FORCED_OPEN").isEqualTo(CircuitBreaker.State.FORCED_OPEN);
            softly.assertThat(whileForced).as("[가설] FORCE_OPEN 중에는 fallback").isEqualTo("fallback:a");
            softly.assertThat(close.status()).isEqualTo(200);
            softly.assertThat(afterClose).as("[가설] CLOSE 후에는 정상 호출").isEqualTo("real:b");
            softly.assertThat(disable.status()).isEqualTo(200);
        });
    }

    // ------------------------------------------------------------ Q3

    @Test
    void Q3_Micrometer_메트릭이_actuator에_노출된다() throws Exception {
        svc.call("warmup");
        Resp list = get("/actuator/metrics");
        Resp state = get("/actuator/metrics/resilience4j.circuitbreaker.state");
        Resp failureRate = get("/actuator/metrics/resilience4j.circuitbreaker.failure.rate");
        Resp notPermitted = get("/actuator/metrics/resilience4j.circuitbreaker.not.permitted.calls");
        System.out.println("\n[Q3] actuator 메트릭"
                + "\n  GET /actuator/metrics -> " + list.status() + " (resilience4j 포함: "
                + list.body().lines().anyMatch(l -> l.contains("resilience4j")) + ")"
                + "\n  resilience4j 메트릭 이름들: " + java.util.Arrays.stream(list.body().split("\""))
                .filter(s -> s.startsWith("resilience4j")).toList()
                + "\n  GET .../resilience4j.circuitbreaker.state -> " + state.status() + " " + cut(state.body(), 900)
                + "\n  GET .../failure.rate -> " + failureRate.status() + " " + cut(failureRate.body(), 400)
                + "\n  GET .../not.permitted.calls -> " + notPermitted.status() + " " + cut(notPermitted.body(), 400));

        assertSoftly(softly -> {
            softly.assertThat(list.status()).isEqualTo(200);
            softly.assertThat(list.body()).as("[가설] 자동 등록된 메트릭이 목록에 있다").contains("resilience4j.circuitbreaker.state");
            softly.assertThat(state.status()).as("[가설] state 메트릭 조회 가능").isEqualTo(200);
            softly.assertThat(state.body()).as("[가설] state 태그에 metrics_only 포함 (실습 G2와 같음)").contains("metrics_only");
        });
    }

    // ------------------------------------------------------------ Q4

    @Test
    void Q4_헬스_인디케이터() throws Exception {
        StringBuilder log = new StringBuilder("\n[Q4] management.health.circuitbreakers.enabled=true, health1 인스턴스 registerHealthIndicator=true\n");
        Resp before = get("/actuator/health");
        log.append("  서킷 CLOSED 상태: GET /actuator/health -> ").append(before.status()).append(" ").append(cut(before.body(), 1500)).append('\n');

        for (int i = 0; i < 4; i++) healthService.call();         // 4번 실패 -> health1 OPEN
        Resp open = get("/actuator/health");
        log.append("  health1 OPEN 후: GET /actuator/health -> ").append(open.status()).append(" ").append(cut(open.body(), 1800)).append('\n');
        log.append("  health1 상태: ").append(registry.circuitBreaker("health1").getState()).append('\n');
        System.out.println(log);

        // 가설 없음(관찰). 단정은 실험이 유효했는지에만 둔다.
        assertSoftly(softly -> {
            softly.assertThat(registry.circuitBreaker("health1").getState()).isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(before.status()).isBetween(200, 599);
        });
    }
}
