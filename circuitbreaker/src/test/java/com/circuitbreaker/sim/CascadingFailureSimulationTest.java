package com.circuitbreaker.sim;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 연쇄 장애 시뮬레이션 (docs/phase2-practice-plan.md).
 * 실행: ./gradlew simulationTest
 *
 * 여기의 단정(assert)은 "부하 생성기가 목표 속도를 냈는가" 같은 실험 자체의 유효성만 검사한다.
 * 계획서의 가설(S2에서 서버가 먼저 죽는다 등)은 일부러 단정하지 않는다. 결과를 눈으로 보고
 * docs/phase2-practice-result.md에서 가설과 비교한다. 가설을 단정으로 박으면 가설이 틀렸을 때
 * 테스트가 빨개질 뿐이고, 결과에 맞춰 단정을 고치면 실험의 의미가 없어지기 때문이다.
 */
@Tag("simulation")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CascadingFailureSimulationTest {

    private final SimulationRunner runner = new SimulationRunner();

    private SimulationResult runAndPrint(Scenario scenario) throws InterruptedException {
        SimulationResult result = runner.run(scenario);
        result.print();
        // 실험 유효성: 목표 200 rps의 ±10% 이내로 부하가 투입되었는가
        assertThat(result.offeredProductRps()).isBetween(180.0, 220.0);
        return result;
    }

    @Test
    @Order(1)
    void S1_기준선() throws InterruptedException {
        SimulationResult result = runAndPrint(Scenario.S1);
        // 기준선은 정상이어야 이후 비교가 의미 있다
        assertThat(result.healthOkRate()).isGreaterThan(0.99);
    }

    @Test
    @Order(2)
    void S2_Redis장애_서킷브레이커없음() throws InterruptedException {
        runAndPrint(Scenario.S2);
    }

    @Test
    @Order(3)
    void S3_서킷브레이커_DB_fallback() throws InterruptedException {
        runAndPrint(Scenario.S3);
    }

    @Test
    @Order(4)
    void S4_DB_허용량_제한() throws InterruptedException {
        runAndPrint(Scenario.S4);
    }

    @Test
    @Order(5)
    void S5_DB_대기_100ms() throws InterruptedException {
        runAndPrint(Scenario.S5);
    }

    // ------------------------------------------------------------------------------------------
    // Resilience4j 버전 (docs/phase3_3-phase2-simulation-with-resilience4j.md)
    // 위의 기존 메서드는 그대로 두고, 서킷브레이커 구현만 Resilience4j로 바꿔서 같은 시나리오를 돌린다.
    // S1, S2는 서킷브레이커를 쓰지 않는 시나리오라 구현을 바꿔도 달라지지 않으므로 추가하지 않는다.
    // ------------------------------------------------------------------------------------------

    private SimulationResult runAndPrint(Scenario scenario, SimulationRunner.CbImpl impl) throws InterruptedException {
        SimulationResult result = runner.run(scenario, impl);
        result.print();
        assertThat(result.offeredProductRps()).isBetween(180.0, 220.0);
        return result;
    }

    @Test
    @Order(13)
    void R4J_S3_서킷브레이커_DB_fallback() throws InterruptedException {
        runAndPrint(Scenario.S3, SimulationRunner.CbImpl.RESILIENCE4J);
    }

    @Test
    @Order(14)
    void R4J_S4_DB_허용량_제한() throws InterruptedException {
        runAndPrint(Scenario.S4, SimulationRunner.CbImpl.RESILIENCE4J);
    }

    @Test
    @Order(15)
    void R4J_S5_DB_대기_100ms() throws InterruptedException {
        runAndPrint(Scenario.S5, SimulationRunner.CbImpl.RESILIENCE4J);
    }
}
