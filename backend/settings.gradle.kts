plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "coop-erp-backend"

include("app")
include("shared-engine")