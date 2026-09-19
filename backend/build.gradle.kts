plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    kotlin("jvm") version "2.0.21" apply false
    // Declared here with the others, not in app/: plugins resolved together share one class
    // path, so Gradle picks one Jackson version. Declared only in app/, Jib got the older
    // Jackson of the plugins above and failed with a NoSuchMethodError.
    id("com.google.cloud.tools.jib") version "3.5.4" apply false
}

allprojects {
    group = "lk.coopfed.knoweb"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}