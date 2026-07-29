pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
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