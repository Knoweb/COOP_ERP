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

// Spring Boot 3.3 manages Testcontainers 1.19, which speaks a Docker API version that Docker
// Engine 29 and newer refuse ("client version 1.32 is too old"). 1.21.4 is the 1.x line with
// the fix; the property below overrides the managed version for every Testcontainers module.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":shared-engine"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.modulith:spring-modulith-starter-core:1.2.5")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Entities and repositories (17A section 12). The starter also brings Spring AOP, which
    // the kernel uses to put the caller's scope on the database transaction.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test:1.2.5")

    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.springframework.modulith:spring-modulith-docs:1.2.5")

    // Integration tests run against a real PostgreSQL 16 in Docker, because row-level security
    // and grants cannot be tested against anything else.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

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

// `make test`: fast tests only. Anything tagged "integration" needs Docker and is left out.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// `make test-int`: the tests tagged "integration" (Testcontainers). Same source folder as the
// unit tests, so a module keeps all its tests in one place; the tag decides which task runs them.
val integrationTest by tasks.registering(Test::class) {
    description = "Runs the tests tagged integration against PostgreSQL in Docker."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // Run as if the server clock were in Colombo, on every machine and in CI (which is UTC).
    // A time bug that depends on the default zone then shows up here, not in production:
    // the first hello migration stored instants five and a half hours off only under this zone.
    systemProperty("user.timezone", "Asia/Colombo")
    shouldRunAfter(tasks.test)
}
