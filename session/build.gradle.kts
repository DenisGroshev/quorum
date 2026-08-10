plugins {
    id("quorum.module-conventions")
    `java-library`
}

dependencies {
    api(project(":common"))
    api(project(":user"))
    api(project(":catalog"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-web")

    // Только для тестов веб-слоя (MockMvc) — сам модуль остаётся библиотекой без
    // встроенного сервера, полный MVC-рантайм собирает :app.
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}
