// https://developer.android.com/build#settings-file
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (providers.gradleProperty("useMavenLocal").orNull == "true") mavenLocal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "auras-orbit-android"
include(":app", ":shared", ":library", ":docs", ":desktopApp")
