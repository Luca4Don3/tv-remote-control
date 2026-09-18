plugins {
    id("com.android.application")
}

val productVersion = rootProject.file("../VERSION").readText().trim().also {
    require(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$|^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-rc[1-9][0-9]*$").matches(it)) {
        "VERSION must use major.minor.patch or major.minor-rcN"
    }
}
val stableVersionParts = productVersion.substringBefore('-').split('.').map(String::toInt)
val productVersionCode = stableVersionParts[0] * 1_000_000 + stableVersionParts[1] * 1_000 +
    (stableVersionParts.getOrNull(2) ?: productVersion.substringAfter("-rc", "0").toInt())
val releaseStoreFile = providers.gradleProperty("tvrc.release.storeFile").orNull ?: System.getenv("TVRC_RELEASE_STORE_FILE")
val releaseStorePassword = providers.gradleProperty("tvrc.release.storePassword").orNull ?: System.getenv("TVRC_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("tvrc.release.keyAlias").orNull ?: System.getenv("TVRC_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("tvrc.release.keyPassword").orNull ?: System.getenv("TVRC_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    namespace = "dev.tvremote.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tvremote.agent"
        minSdk = 19
        targetSdk = 36
        versionCode = productVersionCode
        versionName = productVersion

        // minSdk 19 keeps Dalvik devices in scope; Material pushes the app past the 64K method limit.
        multiDexEnabled = true

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("releaseExternal") {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("releaseExternal")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.multidex:multidex:2.0.1")
    testImplementation("junit:junit:4.13.2")
}
