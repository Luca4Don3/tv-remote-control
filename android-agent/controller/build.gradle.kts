plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

val productVersion = rootProject.file("../VERSION").readText().trim().also {
    require(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$|^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-rc[1-9][0-9]*$").matches(it)) {
        "VERSION must use major.minor.patch or major.minor-rcN"
    }
}
val stableVersionParts = productVersion.substringBefore('-').split('.').map(String::toInt)
val productVersionCode = stableVersionParts[0] * 1_000_000 + stableVersionParts[1] * 1_000 +
    (stableVersionParts.getOrNull(2) ?: productVersion.substringAfter("-rc", "0").toInt())

android {
    namespace = "dev.lucasdone.tvremote.controller"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lucasdone.tvremote.controller"
        minSdk = 24
        targetSdk = 36
        versionCode = productVersionCode
        versionName = productVersion
    }

    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        informational += setOf("OldTargetApi", "GradleDependency")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation(project(":protocol-core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
