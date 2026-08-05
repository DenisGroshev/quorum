plugins {
    id("quorum.module-conventions")
    `java-library`
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework:spring-web")

    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    // Keeps Lombok-generated getters visible to MapStruct's processor in the
    // same annotation-processing round (order between the two isn't otherwise
    // guaranteed).
    annotationProcessor(libs.lombok.mapstruct.binding)

    // Versions come from the Spring Boot BOM (imported by quorum.module-conventions).
    implementation("com.github.ben-manes.caffeine:caffeine")
    // CaffeineCacheManager lives here, not in plain spring-context.
    implementation("org.springframework:spring-context-support")

    // Даёт CircuitBreakerRegistry/RetryRegistry/RateLimiterRegistry, собранные
    // автоконфигурацией Spring Boot из resilience4j.* в application.yml.
    // Аннотации (@CircuitBreaker и т.п.) не используем — см. TmdbClient: порядок
    // размыкатель/повторы/ограничитель собран явно в коде, а не через AOP-аспекты
    // (их порядок по умолчанию у Resilience4j обратный тому, что нужно здесь).
    implementation(libs.resilience4j.spring)

    testImplementation(libs.wiremock)
    // Только для тестов веб-слоя (MockMvc) — сам модуль остаётся библиотекой без
    // встроенного сервера, полный MVC-рантайм собирает :app.
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}