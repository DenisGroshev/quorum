plugins {
    id("quorum.module-conventions")
    `java-library`
}

dependencies {
    api(project(":common"))
    api(project(":user"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-web")
}
