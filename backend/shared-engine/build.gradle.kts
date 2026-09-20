plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())

    compilerOptions {
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
}

java {
    withSourcesJar()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            groupId = project.group.toString()
            artifactId = "shared-engine"
            version = project.version.toString()
        }
    }
}