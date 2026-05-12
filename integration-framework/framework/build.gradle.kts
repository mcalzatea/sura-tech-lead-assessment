plugins {
    java
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
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.6.0")

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
