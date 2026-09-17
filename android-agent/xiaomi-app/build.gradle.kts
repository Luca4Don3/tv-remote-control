plugins { id("com.android.application") }

// Use the repository version and existing external signing property names.
val productVersion = rootProject.file("../VERSION").readText().trim()
require(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$|^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-rc[1-9][0-9]*$").matches(productVersion))
val parts = productVersion.substringBefore('-').split('.').map(String::toInt)
val code = parts[0] * 1_000_000 + parts[1] * 1_000 + (parts.getOrNull(2) ?: productVersion.substringAfter("-rc", "0").toInt())
fun signingValue(property: String, environment: String) = providers.gradleProperty(property).orNull ?: System.getenv(environment)
val signingValues = listOf(
    signingValue("tvrc.release.storeFile", "TVRC_RELEASE_STORE_FILE"),
    signingValue("tvrc.release.storePassword", "TVRC_RELEASE_STORE_PASSWORD"),
    signingValue("tvrc.release.keyAlias", "TVRC_RELEASE_KEY_ALIAS"),
    signingValue("tvrc.release.keyPassword", "TVRC_RELEASE_KEY_PASSWORD"),
)
require(signingValues.all { it.isNullOrBlank() } || signingValues.all { !it.isNullOrBlank() }) { "release signing configuration is incomplete" }

android {
    namespace = "dev.lucasdone.tvremote.xiaomi"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.lucasdone.tvremote.agent.xiaomi"
        minSdk = 19
        targetSdk = 36
        versionCode = code
        versionName = productVersion
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    if (signingValues.all { !it.isNullOrBlank() }) {
        signingConfigs.create("releaseExternal") {
            storeFile = file(checkNotNull(signingValues[0]))
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            if (signingValues.all { !it.isNullOrBlank() }) signingConfig = signingConfigs.getByName("releaseExternal")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { informational += setOf("OldTargetApi", "GradleDependency") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = false }
}

abstract class PrepareSharedAgentSources : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty
    @get:Input abstract val sourcePaths: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations
    @TaskAction fun prepare() {
        fileSystem.sync {
            from(sourceDirectory) { include(sourcePaths.get()) }
            into(outputDirectory)
        }
    }
}

val sharedSources = tasks.register<PrepareSharedAgentSources>("prepareSharedAgentSources") {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("app/src/main/kotlin"))
    outputDirectory.set(layout.buildDirectory.dir("generated/shared-agent"))
    sourcePaths.set(listOf(
        "dev/lucasdone/tvremote/agent/auth/KeystoreCredentialStore.kt",
        "dev/lucasdone/tvremote/agent/auth/KeystoreFaults.kt",
        "dev/lucasdone/tvremote/agent/auth/KeystoreRecovery.kt",
        "dev/lucasdone/tvremote/agent/transport/ControlServer.kt",
        "dev/lucasdone/tvremote/agent/transport/DiscoveryServer.kt",
        "dev/lucasdone/tvremote/agent/transport/TlsIdentityRecovery.kt",
        "dev/lucasdone/tvremote/agent/transport/TlsIdentityStore.kt",
        "dev/lucasdone/tvremote/agent/transport/TlsPolicy.kt",
        "dev/lucasdone/tvremote/agent/command/CommandExecutor.kt",
        "dev/lucasdone/tvremote/agent/command/KeyStateTracker.kt",
        "dev/lucasdone/tvremote/agent/command/MediaCommandExecutor.kt",
        "dev/lucasdone/tvremote/agent/command/TextCommandExecutor.kt",
        "dev/lucasdone/tvremote/agent/media/ControlMediaSession.kt",
        "dev/lucasdone/tvremote/agent/media/MediaPacket.kt",
        "dev/lucasdone/tvremote/agent/media/MediaPacketChannel.kt",
        "dev/lucasdone/tvremote/agent/service/AgentStatusRegistry.kt",
    ))
}
androidComponents.onVariants { variant ->
    checkNotNull(variant.sources.kotlin).addGeneratedSourceDirectory(sharedSources, PrepareSharedAgentSources::outputDirectory)
}

dependencies {
    implementation(project(":protocol-core"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.mobile:dadb:1.2.10")
    testImplementation("junit:junit:4.13.2")
}
