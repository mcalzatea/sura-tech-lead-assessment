plugins {
    java
    id("org.springframework.boot") version "3.3.0" apply false
    id("io.spring.dependency-management") version "1.1.5" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    repositories { mavenCentral() }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.0")
            mavenBom("io.github.resilience4j:resilience4j-bom:2.2.0")
            mavenBom("io.opentelemetry:opentelemetry-bom:1.40.0")
        }
    }
}
