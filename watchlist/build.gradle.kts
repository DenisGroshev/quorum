plugins {
    id("quorum.module-conventions")
    `java-library`
}

dependencies {
    api(project(":common"))
    api(project(":catalog"))
    api(project(":user"))
    api(project(":session"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-web")

    testImplementation("org.springframework.boot:spring-boot-starter-web")
}
