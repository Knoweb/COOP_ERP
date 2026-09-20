// Versions live in gradle/libs.versions.toml (17A section 3), not here.
plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared here with the others, not in app/: plugins resolved together share one class
    // path, so Gradle picks one Jackson version. Declared only in app/, Jib got the older
    // Jackson of the plugins above and failed with a NoSuchMethodError.
    alias(libs.plugins.jib) apply false
    // Software bill of materials (17A section 11, package stage): ./gradlew :app:cyclonedxBom
    alias(libs.plugins.cyclonedx) apply false
    // Server-side interfaces and DTOs from the OpenAPI slices (17A: "openapi-generator, Java
    // server stubs"). Configured in app/build.gradle.kts.
    alias(libs.plugins.openapi.generator) apply false
    // One Java code style for everybody, whatever the IDE (configured in app/build.gradle.kts).
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "lk.coopfed.knoweb"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}