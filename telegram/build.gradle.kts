plugins { id("quorum.module-conventions") }

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-json")  // RestClient + Jackson
    implementation("org.springframework:spring-web")                     // RestClient без web-сервера
    testImplementation(libs.wiremock)
}