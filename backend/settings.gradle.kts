pluginManagement {
    repositories {
        mavenCentral {
            url = uri("https://repo.maven.apache.org/maven2/")
        }
        maven {
            name = "MavenCentralRepo1"
            url = uri("https://repo1.maven.org/maven2/")
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral {
            url = uri("https://repo.maven.apache.org/maven2/")
        }
        maven {
            name = "MavenCentralRepo1"
            url = uri("https://repo1.maven.org/maven2/")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "coop-erp-backend"

include("app")
include("shared-engine")
