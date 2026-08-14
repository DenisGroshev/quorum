pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "quorum"   // или "pollyfilm", как назвал проект

include(
        "app", "common", "telegram",
        "user", "auth", "catalog", "watchlist",
        "session", "matching", "voting", "notification",
)