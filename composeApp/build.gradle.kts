import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = "1.0.0"

kotlin {
    // Android and desktop are both JVM, so the app logic lives in one shared
    // "jvmShared" source set that can use java.io / java.net directly.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.documentfile:documentfile:1.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
    }
}

android {
    namespace = "app.mapstotom"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.mapstotom"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = appVersion
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

compose.resources {
    packageOfResClass = "app.mapstotom.res"
    publicResClass = false
}

compose.desktop {
    application {
        mainClass = "app.mapstotom.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MapsToTom"
            // HTTPS to Google/TomTom needs the elliptic-curve ciphers module in the bundled runtime.
            modules("jdk.crypto.ec", "java.naming")
            packageVersion = appVersion
            description = "Send Google Maps places and routes to an old TomTom, and keep its GPS healthy"
            vendor = "MapsToTom"
            linux { iconFile.set(project.file("icons/icon.png")) }
            windows { iconFile.set(project.file("icons/icon.ico")); menuGroup = "MapsToTom"; upgradeUuid = "6f1d1c1e-7a5e-4f1b-9a51-3a2f4b0c9e11" }
            macOS { iconFile.set(project.file("icons/icon.icns")); bundleID = "app.mapstotom" }
        }
    }
}

// Optional real-data tests: ./gradlew desktopTest -Pm2t.online=1 -Pm2t.goTtsystem=/path/to/ttsystem
tasks.withType<Test>().configureEach {
    listOf("m2t.online", "m2t.goTtsystem", "m2t.shots", "m2t.device", "m2t.gpx", "m2t.stage", "m2t.gpi", "m2t.gpiInstall", "m2t.fuelOut", "m2t.overlap", "m2t.osmInstall", "m2t.osmCountries", "m2t.osmKeyFile", "m2t.osmBudget", "m2t.osmTypes", "m2t.osmLang", "m2t.gui", "m2t.guiCountries", "m2t.guiType").forEach { k -> project.findProperty(k)?.let { systemProperty(k, it) } }
}
