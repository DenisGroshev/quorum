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

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation(libs.springdoc.webmvc)

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation("org.flywaydb:flyway-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.archunit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
}

tasks.bootBuildImage {
    imageName = "quorum/app:${project.version}"
}
