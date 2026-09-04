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
    namespace = "dev.lucasdone.tvremote.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lucasdone.tvremote.agent"
        minSdk = 19
        targetSdk = 36
        versionCode = productVersionCode
        versionName = productVersion

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
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
