import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The desktop till for Windows 10/11 64-bit and Linux 64-bit (CR-30-1): a Compose Desktop window
// around the shared screens (ui), wired to the encrypted database (db), the sync client (sync),
// the printers (peripherals-jvm) and the OS key vault (DPAPI on Windows, a private key file on
// Linux). Packaged with its own trimmed Java 21 runtime; see till/README.md, "Packaging".
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

val tillVersion = providers.gradleProperty("till.version").get()

dependencies {
    implementation(project(":ui"))
    implementation(project(":sync"))
    implementation(project(":db"))
    implementation(project(":peripherals-jvm"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.client.java)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test)
}

compose.desktop {
    application {
        mainClass = "lk.coopfed.knoweb.till.desktop.MainKt"
        // A small heap and the serial collector suit a one-window till on a 4 GB PC. (A class-data
        // archive would start it faster, but the jlinked runtime has no base CDS archive for
        // -XX:+AutoCreateSharedArchive to build on: a follow-up, see till/README.md.)
        jvmArgs += listOf("-Xms32m", "-Xmx384m", "-XX:+UseSerialGC", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "coop-till"
            packageVersion = tillVersion
            description = "COOP ERP till"
            vendor = "COOPFED"
            // The JDK modules the till needs (:desktop:suggestRuntimeModules, plus jdk.crypto.ec, the
            // JDK 21 home of Ed25519, which a provider lookup hides from jdeps).
            modules("java.sql", "java.net.http", "java.management", "java.instrument", "java.naming", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                menuGroup = "COOP ERP"
                // Installs for the till's own Windows user: no administrator at the shop (CR-30-1 point 4).
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "0190f0de-0000-7000-8000-00000000c0de"
            }
            linux {
                packageName = "coop-till"
                debMaintainer = "till@coopfed.lk"
                menuGroup = "Office"
            }
        }
    }
}

// The trial's helper against the local stack (till/README.md): registers DESKTOP-TRIAL-S01 at the
// demo town shop as its manager would, issues a one-time enrolment code and writes the shop's
// published shelf prices as the till's price book. Talks to COOP_ERP_API (default localhost:8080).
tasks.register<JavaExec>("devEnrolmentCode") {
    group = "till"
    description = "Registers the desktop trial till at the demo town shop and issues an enrolment code (local stack)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("lk.coopfed.knoweb.till.desktop.dev.DevEnrolmentCodeKt")
}

// Opt-in integration test against a running local stack: ./gradlew :desktop:stackTest -Ptill.stack=true
tasks.test {
    useJUnitPlatform()
    systemProperty("till.stack", providers.gradleProperty("till.stack").getOrElse("false"))
}
