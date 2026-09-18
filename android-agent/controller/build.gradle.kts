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

val cameraxVersion = "1.3.4"

android {
    namespace = "dev.tvremote.controller"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tvremote.controller"
        // 兼容 Android 5.0+（Compose/Material3/CameraX 的技术下限为 API 21）
        minSdk = 21
        targetSdk = 36
        versionCode = productVersionCode
        versionName = productVersion
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // 测试构建与生产构建使用不同应用标识（规范 17），可与生产包并存
            applicationIdSuffix = ".debug"
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
        jniLibs {
            // minSdk < 23：解压原生库，避免 Android 5.x 无法加载未压缩 .so
            useLegacyPackaging = true
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 本地扫码（无 Google Play 服务依赖）：CameraX 预览/分析 + ZXing 纯 Java 解码
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
