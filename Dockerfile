# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY build-logic build-logic
RUN chmod +x gradlew

COPY . .
RUN ./gradlew --no-daemon :app:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]