plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "circuitbreaker"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Resilience4j (Boot 4 전용 모듈, 버전 직접 명시)
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    // @CircuitBreaker 같은 어노테이션 방식을 쓰려면 AOP 필요
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // 상태/메트릭을 /actuator로 보고 싶으면 (선택)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}

// 일반 테스트: 시간이 오래 걸리고 타이밍에 의존하는 시뮬레이션은 제외한다.
tasks.test {
    useJUnitPlatform {
        excludeTags("simulation")
    }
}

// Phase 2 연쇄 장애 시뮬레이션 전용 태스크: ./gradlew simulationTest
tasks.register<Test>("simulationTest") {
    description = "Runs @Tag(\"simulation\") tests (Phase 2 cascading failure simulation)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("simulation")
    }
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}
