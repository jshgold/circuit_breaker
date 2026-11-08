package com.circuitbreaker.r4j.spring;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Phase 3_4 실습 P5b: yaml에 ignoreExceptionsPrecedenceEnabled 를 쓰면 어떻게 되나.
 * (docs/phase3_4-resilience4j-spring-integration.md 4장 P5b)
 *
 * 인스턴스 속성(InstanceProperties)의 setter 목록에 이 옵션이 없다는 것을 jar에서 확인했다.
 * 알 수 없는 속성이 스프링 부트 기본 동작(무시)대로 처리되는지, 시작 자체가 실패하는지 본다.
 * 시작이 실패할 수 있어서 다른 시험과 컨텍스트를 분리했다.
 */
@Tag("simulation")
@SpringBootTest(properties = {
        "resilience4j.circuitbreaker.instances.prec.slidingWindowSize=4",
        "resilience4j.circuitbreaker.instances.prec.minimumNumberOfCalls=4",
        "resilience4j.circuitbreaker.instances.prec.ignoreExceptionsPrecedenceEnabled=true"
})
class ResilienceSpringPrecedenceYamlTest {

    @Autowired
    CircuitBreakerRegistry registry;

    @Test
    void P5b_yaml의_ignoreExceptionsPrecedenceEnabled는_설정에_반영되지_않는다() {
        CircuitBreaker cb = registry.circuitBreaker("prec");
        boolean enabled = cb.getCircuitBreakerConfig().isIgnoreExceptionsPrecedenceEnabled();
        System.out.println("\n[P5b] yaml에 ignoreExceptionsPrecedenceEnabled=true 를 썼을 때"
                + "\n  컨텍스트 시작: 성공"
                + "\n  prec 인스턴스: 윈도우=" + cb.getCircuitBreakerConfig().getSlidingWindowSize()
                + ", 최소=" + cb.getCircuitBreakerConfig().getMinimumNumberOfCalls()
                + ", isIgnoreExceptionsPrecedenceEnabled=" + enabled);

        assertSoftly(softly -> {
            softly.assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).as("같은 인스턴스의 다른 속성은 반영됨").isEqualTo(4);
            softly.assertThat(enabled).as("[가설] 알 수 없는 속성이라 조용히 무시되어 false로 남는다 (시작은 실패하지 않음)").isFalse();
        });
    }
}
