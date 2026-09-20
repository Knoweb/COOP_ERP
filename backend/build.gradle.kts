plugins {
    java
    id("org.springframework.boot") version "3.3.13" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.4.20" apply false
    // Declared here with the others, not in app/: plugins resolved together share one class
    // path, so Gradle picks one Jackson version. Declared only in app/, Jib got the older
    // Jackson of the plugins above and failed with a NoSuchMethodError.
    id("com.google.cloud.tools.jib") version "3.5.4" apply false
    // Software bill of materials (17A section 11, package stage): ./gradlew :app:cyclonedxBom
    id("org.cyclonedx.bom") version "3.4.1" apply false
    // Server-side interfaces and DTOs from the OpenAPI slices (17A: "openapi-generator, Java
    // server stubs"). Configured in app/build.gradle.kts.
    id("org.openapi.generator") version "7.25.0" apply false
    // One Java code style for everybody, whatever the IDE (configured in app/build.gradle.kts).
    id("com.diffplug.spotless") version "8.10.2" apply false
}

allprojects {
    group = "lk.coopfed.knoweb"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}