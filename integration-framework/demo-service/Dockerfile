FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY build/libs/demo-service-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
