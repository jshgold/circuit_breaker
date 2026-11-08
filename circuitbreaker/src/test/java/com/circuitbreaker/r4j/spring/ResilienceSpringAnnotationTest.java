package com.circuitbreaker.r4j.spring;

import com.circuitbreaker.r4j.spring.SpringFixtures.ErrA;
import com.circuitbreaker.r4j.spring.SpringFixtures.ErrB;
import com.circuitbreaker.r4j.spring.SpringFixtures.ExceptionService;
import com.circuitbreaker.r4j.spring.SpringFixtures.FallbackRuleService;
import com.circuitbreaker.r4j.spring.SpringFixtures.SelfInvocationService;
import com.circuitbreaker.r4j.spring.SpringFixtures.SvcService;
import com.circuitbreaker.r4j.spring.SpringFixtures.UndefinedService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 3_4 실습 P: Spring 설정(yaml)과 @CircuitBreaker 어노테이션
 * (docs/phase3_4-resilience4j-spring-integration.md 4장 P).
 * 실행: ./gradlew simulationTest --tests '*ResilienceSpringAnnotationTest*'
 */
@Tag("simulation")
@SpringBootTest
@ActiveProfiles("r4jspring")
@Import(SpringFixtures.Beans.class)
class ResilienceSpringAnnotationTest {

    @Autowired
    CircuitBreakerRegistry registry;
    @Autowired
    SvcService svc;
    @Autowired
    UndefinedService undefinedService;
    @Autowired
    ExceptionService exceptionService;
    @Autowired
    SelfInvocationService selfInv;
    @Autowired
    FallbackRuleService fallbackRules;

    @BeforeEach
    void resetAll() {
        registry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        svc.reset();
        undefinedService.reset();
        selfInv.reset();
        fallbackRules.reset();
    }

    private static String snap(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        return String.format("state=%-9s failureRate=%6.1f buffered=%d failed=%d successful=%d notPermitted=%d",
                cb.getState(), m.getFailureRate(), m.getNumberOfBufferedCalls(),
                m.getNumberOfFailedCalls(), m.getNumberOfSuccessfulCalls(), m.getNumberOfNotPermittedCalls());
    }

    private static String describe(CircuitBreakerConfig c) {
        return String.format("type=%s window=%d min=%d threshold=%.0f permitted=%d waitInOpen=%dms slowRate=%.0f slowDuration=%dms"
                        + " maxWaitHalfOpen=%dms targetAfterWait=%s autoTransition=%s ignorePrecedence=%s",
                c.getSlidingWindowType(), c.getSlidingWindowSize(), c.getMinimumNumberOfCalls(),
                c.getFailureRateThreshold(), c.getPermittedNumberOfCallsInHalfOpenState(),
                c.getWaitIntervalFunctionInOpenState().apply(1), c.getSlowCallRateThreshold(),
                c.getSlowCallDurationThreshold().toMillis(), c.getMaxWaitDurationInHalfOpenState().toMillis(),
                c.getTransitionToStateAfterWaitDuration(), c.isAutomaticTransitionFromOpenToHalfOpenEnabled(),
                c.isIgnoreExceptionsPrecedenceEnabled());
    }

    private static <T extends Throwable> Throwable thrownBy(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    // ------------------------------------------------------------ P1

    @Test
    void P1_yaml_설정이_그대로_바인딩된다() {
        StringBuilder log = new StringBuilder("\n[P1] yaml 설정 바인딩 (등록된 인스턴스와 설정 객체)\n");
        registry.getAllCircuitBreakers().stream()
                .sorted(java.util.Comparator.comparing(CircuitBreaker::getName))
                .forEach(cb -> log.append(String.format("  %-10s %s%n", cb.getName(), describe(cb.getCircuitBreakerConfig()))));
        System.out.println(log);

        CircuitBreakerConfig basic = registry.circuitBreaker("basic").getCircuitBreakerConfig();
        CircuitBreakerConfig svcCfg = registry.circuitBreaker("svc").getCircuitBreakerConfig();
        assertSoftly(softly -> {
            softly.assertThat(basic.getSlidingWindowSize()).as("baseConfig 상속: 윈도우").isEqualTo(10);
            softly.assertThat(basic.getMinimumNumberOfCalls()).isEqualTo(10);
            softly.assertThat(basic.getFailureRateThreshold()).isEqualTo(50f);
            softly.assertThat(basic.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
            softly.assertThat(basic.getWaitIntervalFunctionInOpenState().apply(1)).as("300ms 파싱").isEqualTo(300L);
            softly.assertThat(basic.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
            softly.assertThat(svcCfg.getSlidingWindowSize()).as("svc는 small 상속").isEqualTo(4);
            softly.assertThat(svcCfg.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        });
    }

    // ------------------------------------------------------------ P2

    @Test
    void P2_어노테이션과_fallback_기본_동작() {
        StringBuilder log = new StringBuilder("\n[P2] @CircuitBreaker(name=svc, fallbackMethod) : 성공 2 + 실패 2 + 이후\n");
        CircuitBreaker cb = registry.circuitBreaker("svc");

        String s1 = svc.call("a");
        String s2 = svc.call("b");
        log.append("  성공 호출 2번 -> ").append(s1).append(", ").append(s2).append(" | ").append(snap(cb)).append('\n');

        svc.setFail(true);
        String f1 = svc.call("c");
        Throwable firstCause = svc.lastFallbackCause();
        String f2 = svc.call("d");
        log.append("  실패 호출 2번 -> ").append(f1).append(", ").append(f2).append(" | ").append(snap(cb)).append('\n');
        log.append("  첫 fallback이 받은 예외: ").append(firstCause).append('\n');

        int targetBefore = svc.targetCalls();
        String blocked = svc.call("e");
        log.append("  서킷이 열린 뒤 호출 -> ").append(blocked).append(" | 대상 호출 횟수 ").append(targetBefore)
                .append(" -> ").append(svc.targetCalls()).append(" | ").append(snap(cb)).append('\n');
        log.append("  마지막 fallback이 받은 예외: ").append(svc.lastFallbackCause()).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(s1).isEqualTo("real:a");
            softly.assertThat(s2).isEqualTo("real:b");
            softly.assertThat(f1).as("[가설] 실패는 예외 대신 fallback 값").isEqualTo("fallback:c");
            softly.assertThat(f2).isEqualTo("fallback:d");
            softly.assertThat(firstCause).isInstanceOf(IllegalStateException.class);
            softly.assertThat(cb.getState()).as("[가설] 4번째 호출에서 열림").isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(blocked).as("[가설] 열린 뒤에도 fallback 값").isEqualTo("fallback:e");
            softly.assertThat(svc.targetCalls()).as("[가설] 열린 뒤에는 대상이 호출되지 않음 (총 4)").isEqualTo(4);
            softly.assertThat(svc.lastFallbackCause()).as("[가설] 차단 시 fallback이 받는 예외").isInstanceOf(CallNotPermittedException.class);
            softly.assertThat(svc.fallbackCalls()).as("fallback은 실패 2 + 차단 1").isEqualTo(3);
        });
    }

    // ------------------------------------------------------------ P3

    @Test
    void P3_코어_경로와_같은_규칙_관문과_경계() {
        StringBuilder log = new StringBuilder("\n[P3] 어노테이션 경로에서도 관문(최소 호출 수)과 경계가 같은가: 연속 실패\n");
        CircuitBreaker cb = registry.circuitBreaker("svc");
        svc.setFail(true);
        String[] after = new String[4];
        for (int i = 0; i < 4; i++) {
            svc.call("x" + i);
            after[i] = snap(cb);
            log.append(String.format("  실패 %d번째 후: %s%n", i + 1, after[i]));
        }
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(after[2]).as("[가설] 3번째까지는 관문으로 CLOSED, 실패율 -1.0").contains("state=CLOSED").contains("-1.0");
            softly.assertThat(after[3]).as("[가설] 4번째에 OPEN").contains("state=OPEN");
        });
    }

    // ------------------------------------------------------------ P4

    @Test
    void P4_yaml에_없는_인스턴스는_기본값을_쓴다() {
        StringBuilder log = new StringBuilder("\n[P4] yaml에 정의하지 않은 인스턴스 'undefined' (fallback 없음)\n");
        int failures = 0;
        String after99 = null;
        for (int i = 1; i <= 100; i++) {
            if (thrownBy(() -> undefinedService.call()) instanceof IllegalStateException) failures++;
            if (i == 99) after99 = snap(registry.circuitBreaker("undefined"));
        }
        CircuitBreaker cb = registry.circuitBreaker("undefined");
        CircuitBreakerConfig c = cb.getCircuitBreakerConfig();
        log.append("  설정: ").append(describe(c)).append('\n');
        log.append("  실패 99번 후: ").append(after99).append('\n');
        log.append("  실패 100번 후: ").append(snap(cb)).append(" (대상 호출 ").append(undefinedService.targetCalls()).append("회)\n");
        System.out.println(log);

        final String a99 = after99;
        final int failureCount = failures;
        assertSoftly(softly -> {
            softly.assertThat(failureCount).as("fallback이 없어서 100번 모두 예외가 호출자에게 전달 (100번째 호출이 서킷을 연다)").isEqualTo(100);
            softly.assertThat(c.getSlidingWindowSize()).as("[가설] 코어 기본값 100").isEqualTo(100);
            softly.assertThat(c.getMinimumNumberOfCalls()).isEqualTo(100);
            softly.assertThat(c.getFailureRateThreshold()).isEqualTo(50f);
            softly.assertThat(c.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(60_000L);
            softly.assertThat(c.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(10);
            softly.assertThat(c.getSlowCallRateThreshold()).isEqualTo(100f);
            softly.assertThat(c.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(60));
            softly.assertThat(c.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ZERO);
            softly.assertThat(c.getTransitionToStateAfterWaitDuration()).isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(a99).as("[가설] 99번 실패는 CLOSED").contains("state=CLOSED");
            softly.assertThat(cb.getState()).as("[가설] 100번째에 OPEN").isEqualTo(CircuitBreaker.State.OPEN);
        });
    }

    // ------------------------------------------------------------ P5

    @Test
    void P5_yaml로_예외를_분류한다() {
        StringBuilder log = new StringBuilder("\n[P5] yaml recordExceptions / ignoreExceptions\n");

        Throwable recA = thrownBy(() -> exceptionService.viaRecord(new ErrA()));
        Throwable recB = thrownBy(() -> exceptionService.viaRecord(new ErrB()));
        CircuitBreaker rec = registry.circuitBreaker("excRecord");
        log.append("  excRecord (recordExceptions=[ErrA]) ErrA, ErrB 던짐 -> 전달 ").append(recA.getClass().getSimpleName())
                .append(", ").append(recB.getClass().getSimpleName()).append(" | ").append(snap(rec)).append('\n');

        Throwable ign = thrownBy(() -> exceptionService.viaIgnore(new ErrA()));
        CircuitBreaker ig = registry.circuitBreaker("excIgnore");
        log.append("  excIgnore (ignoreExceptions=[ErrA]) ErrA 던짐 -> 전달 ").append(ign.getClass().getSimpleName())
                .append(" | ").append(snap(ig)).append('\n');

        Throwable both = thrownBy(() -> exceptionService.viaBoth(new ErrA()));
        CircuitBreaker bo = registry.circuitBreaker("excBoth");
        log.append("  excBoth (record=[ErrA], ignore=[ErrA]) ErrA 던짐 -> 전달 ").append(both.getClass().getSimpleName())
                .append(" | ").append(snap(bo)).append('\n');
        log.append("  excBoth 설정의 ignoreExceptionsPrecedenceEnabled = ")
                .append(bo.getCircuitBreakerConfig().isIgnoreExceptionsPrecedenceEnabled()).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(rec.getMetrics().getNumberOfFailedCalls()).as("record 목록의 ErrA는 실패").isEqualTo(1);
            softly.assertThat(rec.getMetrics().getNumberOfSuccessfulCalls()).as("[가설] 목록 밖 ErrB는 성공으로 집계").isEqualTo(1);
            softly.assertThat(ig.getMetrics().getNumberOfBufferedCalls()).as("[가설] ignore된 예외는 집계 제외").isZero();
            softly.assertThat(bo.getMetrics().getNumberOfBufferedCalls()).as("[가설] 양쪽에 있으면 ignore가 이김 (코어 경로와 같음)").isZero();
        });
    }

    // ------------------------------------------------------------ P6

    @Test
    void P6_같은_클래스_안_호출은_서킷브레이커를_거치지_않는다() {
        StringBuilder log = new StringBuilder("\n[P6] self-invocation: outer()가 this.inner()를 호출\n");
        CircuitBreaker cb = registry.circuitBreaker("selfinv");

        selfInv.setFail(true);
        for (int i = 0; i < 4; i++) selfInv.inner();             // 프록시 경유 4번 -> OPEN
        log.append("  프록시 경유 실패 4번 후: ").append(snap(cb)).append(" | 대상 호출 ").append(selfInv.targetCalls())
                .append(", fallback ").append(selfInv.fallbackCalls()).append('\n');

        String viaProxy = selfInv.inner();                         // 열렸으니 차단 -> fallback
        log.append("  열린 뒤 inner() 직접 호출(프록시 경유) -> ").append(viaProxy).append(" | 대상 호출 ").append(selfInv.targetCalls())
                .append(", fallback ").append(selfInv.fallbackCalls()).append(" | ").append(snap(cb)).append('\n');

        int targetBefore = selfInv.targetCalls();
        long notPermittedBefore = cb.getMetrics().getNumberOfNotPermittedCalls();
        Throwable viaOuter = thrownBy(() -> selfInv.outer());      // this.inner() -> 프록시 우회
        log.append("  열린 뒤 outer() 호출 -> ").append(viaOuter == null ? "예외 없음" : viaOuter.getClass().getSimpleName())
                .append(" | 대상 호출 ").append(targetBefore).append(" -> ").append(selfInv.targetCalls())
                .append(", 거절 카운터 ").append(notPermittedBefore).append(" -> ")
                .append(cb.getMetrics().getNumberOfNotPermittedCalls()).append(" | ").append(snap(cb)).append('\n');
        System.out.println(log);

        assertSoftly(softly -> {
            softly.assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            softly.assertThat(viaProxy).as("프록시 경유는 차단되어 fallback").isEqualTo("fallback");
            softly.assertThat(selfInv.targetCalls()).as("[가설] outer()를 통한 호출은 열린 서킷을 무시하고 대상이 실행됨")
                    .isEqualTo(targetBefore + 1);
            softly.assertThat(viaOuter).as("[가설] self-invocation은 fallback도 적용되지 않아 예외가 그대로 전달")
                    .isInstanceOf(IllegalStateException.class);
            softly.assertThat(cb.getMetrics().getNumberOfNotPermittedCalls()).as("[가설] 거절 카운터는 변하지 않음")
                    .isEqualTo(notPermittedBefore);
        });
    }

    // ------------------------------------------------------------ P8

    @Test
    void P8_fallback_해석_규칙() {
        StringBuilder log = new StringBuilder("\n[P8] fallback 해석 규칙\n");

        // ① fallback이 CallNotPermittedException 만 받는 경우
        CircuitBreaker cb2 = registry.circuitBreaker("svc2");
        fallbackRules.setFail(true);
        Throwable[] failures = new Throwable[4];
        for (int i = 0; i < 4; i++) {
            failures[i] = thrownBy(() -> fallbackRules.callCnpeOnly());
        }
        log.append("  ① 대상 실패 4번 (fallback은 CallNotPermittedException 전용) -> 호출자가 받은 것: ")
                .append(failures[0] == null ? "값" : failures[0].getClass().getSimpleName()).append(" | ").append(snap(cb2)).append('\n');
        String blocked = fallbackRules.callCnpeOnly();
        log.append("  ①' 열린 뒤 호출 -> ").append(blocked).append(" | 대상 호출 ").append(fallbackRules.targetCalls()).append('\n');

        // ② private fallback
        CircuitBreaker cb3 = registry.circuitBreaker("svc3");
        Throwable privateThrown = null;
        String privateValue = null;
        try {
            privateValue = fallbackRules.callPrivate();
        } catch (RuntimeException e) {
            privateThrown = e;
        }
        log.append("  ② private fallback (대상은 정상이라 fallback 미사용) -> ").append(privateValue).append('\n');
        fallbackRules.setFail(true);
        String privateFallback = null;
        Throwable privateFallbackError = null;
        try {
            privateFallback = fallbackRules.callPrivate();
        } catch (RuntimeException e) {
            privateFallbackError = e;
        }
        log.append("  ②' private fallback 호출 (대상 실패) -> ")
                .append(privateFallbackError != null ? "예외 " + privateFallbackError : privateFallback)
                .append(" | ").append(snap(cb3)).append('\n');
        System.out.println(log);

        final Throwable error = privateFallbackError;
        final String value = privateFallback;
        assertSoftly(softly -> {
            softly.assertThat(failures[0]).as("[가설] ① 일치하는 fallback이 없으면 예외가 호출자에게 전달").isInstanceOf(IllegalStateException.class);
            softly.assertThat(blocked).as("[가설] ①' 차단 시 CallNotPermittedException 전용 fallback이 호출됨").isEqualTo("cnpe-fallback");
            softly.assertThat(error).as("[가설] ② private fallback도 동작 (근거 약함)").isNull();
            softly.assertThat(value).isEqualTo("private-fallback");
        });
    }
}
