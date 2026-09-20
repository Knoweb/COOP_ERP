plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib")
    id("org.cyclonedx.bom")
    id("org.openapi.generator")
    id("com.diffplug.spotless")
    jacoco
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

// ---- OpenAPI first (17A sections 3 and 12) ------------------------------------------------
// The slice openapi/<module>.yaml is the source. From each slice this generates, into
// build/generated/openapi/<module>, a Java interface per tag (hello.yaml, tag Hello ->
// HelloApi) and a class per schema (GreetingResponse ...), in the package
// lk.coopfed.knoweb.<module>.web.generated. The module's controller implements the interface,
// so a slice and its controller cannot drift apart: an operation added to the slice is a
// compile error until the controller has it, and so is a changed parameter or response type.
//
// Nothing generated is committed or edited: it is build output, regenerated on every compile.
// One oddity: for a response defined in common.yaml (the 400 and 422 problem documents) the
// generator names the class after the operation, for example RegisterGreeting400Response. It
// is the Problem document. No module uses it: the kernel writes every error response.
// A new slice needs no change here: every file in openapi/ except common.yaml gets a task.
val openApiDir = layout.projectDirectory.dir("src/main/resources/openapi")
val openApiSlices = openApiDir.asFileTree.matching { include("*.yaml"); exclude("common.yaml") }.files
    .sortedBy { it.name }

val generateOpenApi = tasks.register("generateOpenApi") {
    description = "Generates the server interfaces and DTOs of every OpenAPI slice."
    group = "build"
}

openApiSlices.forEach { slice ->
    val module = slice.nameWithoutExtension
    val output = layout.buildDirectory.dir("generated/openapi/$module")
    val generated = "lk.coopfed.knoweb.$module.web.generated"

    val task = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateOpenApi_$module") {
        generatorName.set("spring")
        inputSpec.set(slice.absolutePath)
        outputDir.set(output.get().asFile.absolutePath)
        apiPackage.set(generated)
        modelPackage.set(generated)
        // Only the two kinds of file we use: no pom.xml, README or sample application.
        globalProperties.set(mapOf("apis" to "", "models" to ""))
        configOptions.set(
            mapOf(
                "interfaceOnly" to "true",            // interfaces, not controllers: the module writes the controller
                "skipDefaultInterface" to "true",     // no default bodies, so a missing operation does not compile
                "useTags" to "true",                  // interface name from the tag (Hello -> HelloApi), not from the path
                "useSpringBoot3" to "true",           // jakarta.*, not javax.*
                "useBeanValidation" to "true",        // the slice is enforced: required, minLength ... are checked before the controller runs
                "openApiNullable" to "false",         // plain nullable fields, no JsonNullable wrapper type
                "documentationProvider" to "none",    // no Swagger annotations, so no Swagger dependency
                "annotationLibrary" to "none",
                "useResponseEntity" to "true",        // the controller chooses the status: 201, 404 ...
                "dateLibrary" to "java8",
                "hideGenerationTimestamp" to "true"   // no build time in the files: same input, same bytes
            )
        )
        // An instant on the wire is an Instant in Java (hello/README.md, rule 6), not the
        // generator's default OffsetDateTime.
        typeMappings.set(mapOf("OffsetDateTime" to "Instant"))
        importMappings.set(mapOf("java.time.OffsetDateTime" to "java.time.Instant"))
        // common.yaml is referenced by the slices, so a change to it must regenerate them too.
        inputs.file(openApiDir.file("common.yaml"))
    }

    generateOpenApi { dependsOn(task) }
    sourceSets["main"].java.srcDir(output.map { it.dir("src/main/java") })
}

tasks.compileJava { dependsOn(generateOpenApi) }

// Overrides the Testcontainers version Spring Boot manages; the reason is in the catalogue.
extra["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
    implementation(project(":shared-engine"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation(libs.spring.modulith.starter.core)

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Entities and repositories (17A section 12). The starter also brings Spring AOP, which
    // the kernel uses to put the caller's scope on the database transaction.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // What a slice says about the shape of a request (required, minLength, maximum ...) is
    // generated as constraint annotations and checked before the controller runs. The kernel
    // turns a violation into a problem document with message ids (RequestValidationHandler),
    // so no module handles validation itself. Business rules stay guards in the handler.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    runtimeOnly("org.postgresql:postgresql")
    // /actuator/prometheus (17A S0-02: health and metrics). The version is managed by Spring Boot.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.spring.modulith.starter.test)

    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.modulith.docs)

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
val jibBaseImage = project.property("jibBaseImage") as String
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
    // ArchitectureTests writes the module diagrams to docs/modules. Declared as an output, so
    // Gradle runs the tests again when those files were deleted or edited by hand, and does
    // not answer "up to date" while the committed diagrams are wrong.
    outputs.dir(rootProject.file("../docs/modules"))
}

// `make test-int`: the tests tagged "integration" (Testcontainers). Same source folder as the
// unit tests, so a module keeps all its tests in one place; the tag decides which task runs them.
// ---- Code style ------------------------------------------------------------------------------
// One formatter decides, so a review is about the change and not about whose IDE wrapped the
// line. `make format` rewrites your files; `make test` and the pipeline run spotlessCheck.
//
// ratchetFrom: only files that differ from origin/main are checked and formatted. The code
// that was there before the formatter arrived stays as it is until somebody touches it, so
// there is no commit that reformats the whole repository and breaks every open branch.
// (In the pipeline this needs the history: the checkout steps use fetch-depth 0.)
spotless {
    ratchetFrom("origin/main")
    java {
        target("src/*/java/**/*.java")               // not build/generated: nobody edits that
        palantirJavaFormat(libs.versions.palantir.java.format.get())               // 4 spaces, 120 columns: what the code already looks like
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---- Coverage ----------------------------------------------------------------------------------
// A report, not a gate: the floors are set per module by its implementation guide (17A section
// 11). Until then this gives them a baseline. One report over both test tasks, because a
// handler is covered by its integration test and a kernel class by its unit test.
//   ./gradlew :app:jacocoTestReport   ->   app/build/reports/jacoco/test/html/index.html
tasks.named<JacocoReport>("jacocoTestReport") {
    executionData(fileTree(layout.buildDirectory) { include("jacoco/*.exec") })
    reports {
        xml.required.set(true)                       // for tools
        csv.required.set(true)                       // for the summary the pipeline prints
        html.required.set(true)                      // for people
    }
    // Generated from the OpenAPI slices: not ours to test.
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude("**/web/generated/**") }
    }))
    mustRunAfter(tasks.test, "integrationTest")
}

val integrationTest = tasks.register<Test>("integrationTest") {
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
