plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":shared-engine"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.modulith:spring-modulith-starter-core:1.2.5")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // The JPA starter arrives with the hello module (S0-12); the @Table annotation is
    // needed now so the R4 own-schema rule in ArchitectureTests can read entity schemas.
    implementation("jakarta.persistence:jakarta.persistence-api")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test:1.2.5")

    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.springframework.modulith:spring-modulith-docs:1.2.5")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The container image (17A sections 10 and 11). Jib builds it straight from the Gradle build:
// no Dockerfile, dependencies in their own layer so a code change rebuilds in seconds, and
// the image a developer runs with `make up` is built the same way as the one CI packages.
//   ./gradlew :app:jibDockerBuild   builds into the local Docker daemon (used by `make image`)
//   ./gradlew :app:jib              builds and pushes to a registry (CI publish stage, later)
// The base image name lives in backend/gradle.properties (jibBaseImage).
// -PjibFromDaemon=true makes Jib take the base image from the local Docker daemon instead of
// the registry. `make image` uses it after a `docker pull`: on a machine where nobody is
// signed in to Docker Hub, Docker Desktop gives Jib an empty credential and the registry
// answers 401, while `docker pull` itself works anonymously. CI, which pushes with
// `./gradlew :app:jib` and has no daemon, leaves the flag off.
val jibBaseImage: String by project
val jibFromDaemon = providers.gradleProperty("jibFromDaemon").map { it.toBoolean() }.getOrElse(false)

jib {
    from {
        image = if (jibFromDaemon) "docker://$jibBaseImage" else jibBaseImage
    }
    to {
        image = "coop-erp/backend:dev"
    }
    container {
        mainClass = "lk.coopfed.knoweb.CoopErpApplication"
        ports = listOf("8080")
        user = "10001"   // never root inside the container
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
