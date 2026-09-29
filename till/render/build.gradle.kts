// Receipt to raster (CR-30-1 point 3): the ReceiptRasteriser port in commonMain; on the desktop JVM
// it draws with Skia's paragraph engine (HarfBuzz shaping, through Skiko) and the bundled Noto Sans,
// Noto Sans Sinhala and Noto Sans Tamil, then thresholds to 1 bit at the printer's dot width.
// Android draws with its platform text stack (StaticLayout) when the Android screens are built.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val tillAndroid = gradle.extensions.extraProperties["till.android"] as Boolean
if (tillAndroid) {
    apply(plugin = "com.android.library")
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "lk.coopfed.knoweb.till.render"
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
        jvmMain.dependencies {
            // Skiko (Skia for the JVM) with the native library of the OS the build runs on.
            implementation(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(project(":peripherals-jvm"))
        }
    }
}

// The golden images are written again with -Pgolden.update=true (then review the PNGs in the diff).
tasks.withType<Test>().configureEach {
    systemProperty("golden.update", providers.gradleProperty("golden.update").getOrElse("false"))
    systemProperty("golden.dir", layout.projectDirectory.dir("src/jvmTest/resources/golden").asFile.absolutePath)
    systemProperty("golden.out", layout.buildDirectory.dir("golden-actual").get().asFile.absolutePath)
}
