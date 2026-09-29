// Printer transports for the desktop till (Windows and Linux): a network printer on TCP 9100, and
// a preview folder (PNG + the raw ESC/POS bytes) when no printer is attached. USB and serial
// (javax.print raw queues, jSerialComm) follow when the supported-hardware list is fixed.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":peripherals"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
