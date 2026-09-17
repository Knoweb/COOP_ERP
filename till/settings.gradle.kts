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
        mavenLocal()
    }
}

rootProject.name = "coop-erp-till"

include(":app")
include(":core")
include(":peripherals")
include(":sync")