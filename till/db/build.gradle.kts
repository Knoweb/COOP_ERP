// The till's local database: SQLDelight schema and queries in commonMain (the TillStore port of
// core), the encrypted SQLite driver per platform. Desktop: sqlite-jdbc-crypt (SQLite3MultipleCiphers,
// SQLCipher v4 format). Android: SQLCipher for Android, to be wired with the Android screens.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

val tillAndroid = gradle.extensions.extraProperties["till.android"] as Boolean
if (tillAndroid) {
    apply(plugin = "com.android.library")
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "lk.coopfed.knoweb.till.db"
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
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // SQLDelight's JDBC driver, on the encrypting build of the same org.sqlite driver.
            implementation("app.cash.sqldelight:sqlite-driver:${libs.versions.sqldelight.get()}") {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
        }
    }
}

sqldelight {
    databases {
        create("TillDatabase") {
            packageName.set("lk.coopfed.knoweb.till.db")
        }
    }
}
