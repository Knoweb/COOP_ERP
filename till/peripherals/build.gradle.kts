// Peripheral ports and the device-independent protocols (research report section 5): the printer
// port and the ESC/POS encoder (raster GS v 0, cut, drawer kick), the scanner's keystroke reader,
// and simulators. Transports live in peripherals-jvm (desktop) and, later, peripherals-android.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val tillAndroid = gradle.extensions.extraProperties["till.android"] as Boolean
if (tillAndroid) {
    apply(plugin = "com.android.library")
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "lk.coopfed.knoweb.till.peripherals"
        compileSdk = 36
        defaultConfig { minSdk = 30 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
    }
    if (tillAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
