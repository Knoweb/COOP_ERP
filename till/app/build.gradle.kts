// The Android till (CR-30-1: the primary target). It shows the shared greeting from ui for now;
// wiring it to the shared screens (SQLCipher driver, Android Keystore, the Android rasteriser and
// printer transports) is the next Android step, see till/README.md.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "lk.coopfed.knoweb.till"
    compileSdk = 36

    defaultConfig {
        applicationId = "lk.coopfed.knoweb.till"
        minSdk = 30
        targetSdk = 34

        versionCode = 1
        versionName = providers.gradleProperty("till.version").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Two libraries carry the same licence notices; the APK needs one copy.
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit4)
}
