@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // ffmpeg-kit was retired and its artifacts removed from Maven Central. The official
        // android aar is still hosted here; restrict to the group to avoid extra lookups.
        maven("https://artifactory.appodeal.com/appodeal-public/") {
            content { includeGroup("com.arthenica") }
        }
    }
}

rootProject.name = "Sanin"
include(":app")