plugins {
    id("quorum.module-conventions")
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
