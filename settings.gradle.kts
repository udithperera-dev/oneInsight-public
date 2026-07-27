pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-download missing JDKs if org.gradle.java.home is unavailable.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "OneInsight"

include(
    ":protocol",
    ":capture-sdk",
    ":capture-noop",
    ":studio-plugin",
    ":sample-app",
)
