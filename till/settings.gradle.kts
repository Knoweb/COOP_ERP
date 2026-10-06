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
    }
}

rootProject.name = "coop-erp-till"

// The Android targets build only where an Android SDK is installed (CI, Android Studio). A
// desktop-only machine builds and runs the desktop till without one. -Ptill.android=true|false
// overrides the guess.
val androidSdkFound = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
val tillAndroid = providers.gradleProperty("till.android").orNull?.toBoolean() ?: androidSdkFound
gradle.extensions.extraProperties["till.android"] = tillAndroid

// CR-30-1: one Kotlin Multiplatform codebase. Business rules live in commonMain of core; the
// platform modules (app for Android, desktop for Windows and Linux) only wire things together.
include(":core")
include(":sync")
include(":db")
include(":peripherals")
include(":peripherals-jvm")
include(":render")
include(":ui")
include(":desktop")
if (tillAndroid) {
    include(":app")
}
