plugins {
    id("quorum.module-conventions")
    `java-library`
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
