import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("quorum.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom(SpringBootPlugin.BOM_COORDINATES) }   // BOM без boot-плагина: у модулей нет bootJar
}

dependencies {
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}