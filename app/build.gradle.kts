plugins {
    id("quorum.app-conventions")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":telegram"))
    implementation(project(":user"))
    implementation(project(":auth"))
    implementation(project(":catalog"))
    implementation(project(":watchlist"))
    implementation(project(":session"))
    implementation(project(":matching"))
    implementation(project(":voting"))
    implementation(project(":notification"))
    implementation(project(":analytics"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation(libs.springdoc.webmvc)
    // ApiExceptionHandler catches CallNotPermittedException (open circuit breaker)
    // alongside TMDB network errors, so it needs the type on the compile classpath.
    implementation(libs.resilience4j.spring)

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation("org.flywaydb:flyway-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.archunit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.wiremock)
    // Только чтобы тест мог обратиться к CircuitBreakerRegistry/RetryRegistry/
    // RateLimiterRegistry напрямую и проверить, что YAML реально в них попал.
    testImplementation(libs.resilience4j.spring)
}

tasks.bootBuildImage {
    imageName = "quorum/app:${project.version}"
}
