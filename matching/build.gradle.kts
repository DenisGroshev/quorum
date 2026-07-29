plugins {
    id("quorum.module-conventions")
    `java-library`
}

dependencies {
    api(project(":common"))
    api(project(":user"))
    api(project(":catalog"))
    api(project(":session"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation(libs.resilience4j.spring)
}
