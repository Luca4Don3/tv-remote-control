plugins {
    id("com.android.library")
}

android {
    namespace = "dev.lucasdone.tvremote.agent.protocol.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 19
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
