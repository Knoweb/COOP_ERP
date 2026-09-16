plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "lk.coopfed.knoweb"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}