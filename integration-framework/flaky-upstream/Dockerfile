FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY build/libs/flaky-upstream-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
