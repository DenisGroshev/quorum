plugins {
    id("quorum.module-conventions")
    `java-library`
}

dependencies {
    api(project(":common"))
    implementation(project(":session"))
    implementation(project(":catalog"))
    implementation(project(":user"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-web")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}
