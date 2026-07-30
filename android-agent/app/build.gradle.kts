plugins {
    id("com.android.application")
}

android {
    namespace = "dev.lucasdone.tvremote.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lucasdone.tvremote.agent"
        minSdk = 19
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
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
    testImplementation("junit:junit:4.13.2")
}
