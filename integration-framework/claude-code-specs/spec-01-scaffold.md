# Spec 01 — Project Scaffold

## Context
Multi-module Gradle project. The `framework` module is a publishable Java library consumed by
`demo-service` and `flaky-upstream` (Section C). No Spring Boot fat-jar for `framework`; it is
a plain library jar. `demo-service` and `flaky-upstream` are Spring Boot applications.

## Module layout

```
/
├── settings.gradle.kts
├── build.gradle.kts              # root — shared plugins + versions only
├── framework/
│   ├── build.gradle.kts
│   └── src/main/java/...
│   └── src/test/java/...
├── demo-service/
│   ├── build.gradle.kts
│   └── src/...
├── flaky-upstream/
│   ├── build.gradle.kts
│   └── src/...
├── docker-compose.yml
└── README.md
```

## Deliverables

### `settings.gradle.kts`
```kotlin
rootProject.name = "integration-framework"
include("framework", "demo-service", "flaky-upstream")
```

### Root `build.gradle.kts`
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.0" apply false
    id("io.spring.dependency-management") version "1.1.5" apply false
}

subprojects {
    apply(plugin = "java")
    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    repositories { mavenCentral() }
}
```

### `framework/build.gradle.kts`

Dependencies (no `spring-boot-starter` fat-jar plugin):

```kotlin
plugins {
    java
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.0")
        mavenBom("io.github.resilience4j:resilience4j-bom:2.2.0")
        mavenBom("io.opentelemetry:opentelemetry-bom:1.38.0")
    }
}

dependencies {
    // Spring core (no web, no boot starter)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Resilience4j
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("io.github.resilience4j:resilience4j-retry")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("io.github.resilience4j:resilience4j-timelimiter")

    // OpenTelemetry
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.4.0")

    // Micrometer + Prometheus
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Redis (Lettuce via Spring Data)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // JSON logging
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    implementation("ch.qos.logback:logback-classic")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // UUID v7 generation
    implementation("com.fasterxml.uuid:java-uuid-generator:5.0.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("com.github.tomakehurst:wiremock-standalone:3.5.4")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
}
```

### `demo-service/build.gradle.kts`
```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":framework"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
}
```

### `flaky-upstream/build.gradle.kts`
```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

## Base `application.yml` for `framework` consumers

Place in `demo-service/src/main/resources/application.yml`:

```yaml
integration:
  defaults:
    timeout:
      connect: 1s
      read: 3s
    retry:
      max-attempts: 3
      base-backoff: 200ms
      max-backoff: 2s
      retry-on-status: [502, 503, 504]
    circuit-breaker:
      failure-rate-threshold: 50
      sliding-window-size: 10
      wait-duration-in-open-state: 30s
      permitted-calls-in-half-open: 3
    idempotency:
      ttl: 24h
      header-name: Idempotency-Key
      auto-generate: true
    logging:
      redact: [password, ssn, card_number, authorization]
      log-bodies: false

spring:
  data:
    redis:
      host: localhost
      port: 6379

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

## Logback JSON config

File: `framework/src/main/resources/logback-spring.xml`

```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeCallerData>false</includeCallerData>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

## Acceptance criteria

- `./gradlew clean build` succeeds with no source files beyond the scaffold.
- Module dependency graph: `demo-service` → `framework`, `flaky-upstream` standalone.
- `framework` does NOT produce a fat-jar (no `bootJar` task).
- Java 21 toolchain enforced for all modules.
