plugins {
    java
    id("com.diffplug.spotless")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Werror", "-parameters"))   // NFR-QA-2
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}

spotless {
    java {
        palantirJavaFormat()
        removeUnusedImports()
    }
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("lombok").get())
    annotationProcessor(libs.findLibrary("lombok").get())
    testCompileOnly(libs.findLibrary("lombok").get())
    testAnnotationProcessor(libs.findLibrary("lombok").get())

    // Gradle bundles its own junit-platform-launcher, which can lag behind the
    // junit-jupiter version the Spring Boot BOM manages. Pinning it explicitly
    // keeps the launcher and engine aligned (see: OutputDirectoryProvider errors
    // otherwise). Modules without the Spring BOM (e.g. common) need an explicit
    // version, so this is pinned via the catalog rather than left unversioned.
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}