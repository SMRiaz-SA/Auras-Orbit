plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// AniList client IDs are public OAuth configuration supplied by the Orbit owner.
// The generated field stays empty unless a build property or environment variable is set.
val configuredAniListClientId = providers.gradleProperty("ORBIT_ANILIST_CLIENT_ID")
    .orElse(providers.environmentVariable("ORBIT_ANILIST_CLIENT_ID"))
    .getOrElse("")
val orbitAniListClientId = configuredAniListClientId
    .takeIf { it.matches(Regex("[0-9]{1,20}")) }
    .orEmpty()

// Trakt's client ID is public app-owned API configuration. The legacy APK has
// no usable value, so requests stay disabled until a valid value is supplied.
val configuredTraktClientId = providers.gradleProperty("ORBIT_TRAKT_CLIENT_ID")
    .orElse(providers.environmentVariable("ORBIT_TRAKT_CLIENT_ID"))
    .getOrElse("")
    .trim()
val orbitTraktClientId = configuredTraktClientId
    .takeIf {
        it.matches(Regex("[A-Za-z0-9_-]{1,256}")) &&
            !it.equals("null", ignoreCase = true)
    }
    .orEmpty()

// This isolated release shares the legacy app ID and updater. Keep its
// user-facing numeric version independent from the legacy source's version.
val orbitReleaseVersionName = providers.gradleProperty("ORBIT_RELEASE_VERSION_NAME")
    .orNull
    ?.trim()
    ?: error("ORBIT_RELEASE_VERSION_NAME must be set for the release variant.")
val orbitReleaseVersionParts = orbitReleaseVersionName.split(".")
require(orbitReleaseVersionParts.size in 3..4) {
    "ORBIT_RELEASE_VERSION_NAME must have three or four numeric components."
}
val orbitReleaseVersionNumbers = orbitReleaseVersionParts.map { part ->
    part.toIntOrNull() ?: error("ORBIT_RELEASE_VERSION_NAME must contain only numeric components.")
}
val (orbitVersionMajor, orbitVersionMinor, orbitVersionPatch, orbitVersionBuild) =
    if (orbitReleaseVersionNumbers.size == 3) orbitReleaseVersionNumbers + 0 else orbitReleaseVersionNumbers
require(
    orbitVersionMajor in 0..21 && orbitVersionMinor in 0..99 &&
        orbitVersionPatch in 0..99 && orbitVersionBuild in 0..9999
) {
    "ORBIT_RELEASE_VERSION_NAME components exceed the Android versionCode allocation."
}
val orbitReleaseVersionCode =
    orbitVersionMajor * 100_000_000 + orbitVersionMinor * 1_000_000 +
        orbitVersionPatch * 10_000 + orbitVersionBuild
require(orbitReleaseVersionCode in 1..2_100_000_000) {
    "ORBIT_RELEASE_VERSION_NAME exceeds Android's versionCode range."
}

// CI can supply credentials through the environment. Local builds load the
// existing update-compatible signer from this ignored properties file, so a
// fresh Gradle process does not depend on credentials copied into its parent shell.
val orbitLocalSigningPropertiesFile = rootProject.projectDir.parentFile
    .resolve("android-reference/app/build/test-signing/local-signing.properties")
val orbitLocalSigningProperties = mutableMapOf<String, String>().apply {
    if (orbitLocalSigningPropertiesFile.isFile) {
        orbitLocalSigningPropertiesFile.forEachLine { line ->
            val trimmedLine = line.trim()
            val separatorIndex = line.indexOf('=')
            if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith('#') &&
                !trimmedLine.startsWith('!') && separatorIndex > 0) {
                put(line.substring(0, separatorIndex).trim(), line.substring(separatorIndex + 1).trim())
            }
        }
    }
}
fun orbitSigningValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }
        ?: orbitLocalSigningProperties[propertyName]?.trim()?.takeIf { it.isNotBlank() }

val orbitEnvironmentStoreFile = providers.environmentVariable("AURAS_RELEASE_STORE_FILE")
    .orNull?.takeIf { it.isNotBlank() }
val orbitLocalStoreFile = orbitLocalSigningProperties["storeFile"]
    ?.trim()?.takeIf { it.isNotBlank() }
val orbitReleaseStoreFile = orbitEnvironmentStoreFile ?: orbitLocalStoreFile?.let { configuredPath ->
    val candidate = File(configuredPath)
    if (candidate.isAbsolute) candidate.path else File(orbitLocalSigningPropertiesFile.parentFile, configuredPath).path
}
val orbitReleaseStorePassword = orbitSigningValue("AURAS_RELEASE_STORE_PASSWORD", "storePassword")
val orbitReleaseKeyAlias = orbitSigningValue("AURAS_RELEASE_KEY_ALIAS", "keyAlias")
val orbitReleaseKeyPassword = orbitSigningValue("AURAS_RELEASE_KEY_PASSWORD", "keyPassword")
val orbitReleaseSigningValues = listOf(
    orbitReleaseStoreFile,
    orbitReleaseStorePassword,
    orbitReleaseKeyAlias,
    orbitReleaseKeyPassword
)
val hasOrbitReleaseSigning = orbitReleaseSigningValues.all { !it.isNullOrBlank() }
val verifyOrbitReleaseSigningInputs = tasks.register("verifyOrbitReleaseSigningInputs") {
    doLast {
        require(hasOrbitReleaseSigning) {
            "Provide the existing release signer through AURAS_RELEASE_* variables or the ignored local-signing.properties file."
        }
        require(!hasOrbitReleaseSigning || File(orbitReleaseStoreFile!!).isFile) {
            "AURAS_RELEASE_STORE_FILE must refer to an existing keystore."
        }
    }
}

dependencies {
    // Match CloudStream's shared Compose runtime for standard plugin settings callbacks.
    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)

    // Runtime surfaces observed in the pinned 11-archive CS3 contract. This is
    // a selected direct runtime set, not a claim that arbitrary plugin graphs close.
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.ksoup.android)
    implementation(libs.nicehttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(libs.okhttp.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.tmdb.java)
    implementation(libs.ktor.http)
    implementation(libs.ktor.utils)
    implementation(libs.jetbrains.annotations)
    // Align the runtime stdlib with AGP 9.4.1's built-in Kotlin 2.4.21 compiler;
    // keep it runtime-only because the compiler supplies the compile-time stdlib.
    runtimeOnly(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    // Narrow runtime DEX normalization for one historical CS3 coroutine owner mismatch.
    implementation(libs.smali.dexlib2)
    implementation(libs.kotlinx.datetime)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    // Orbit-only Torrentserver build: HTTP routes are loopback-bound and token-gated.
    implementation(files("libs/torrentserver-orbit-loopback.aar"))

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
}

val orbitQueueRenderAndroidTest =
    providers.gradleProperty("orbitQueueRenderAndroidTest").orNull == "true"
val orbitCs3CanaryAndroidTest =
    providers.gradleProperty("orbitCs3CanaryAndroidTest").orNull == "true"
require(!orbitCs3CanaryAndroidTest) {
    "The CS3 Canary build and AndroidTest variant are disabled; use cs3Acceptance."
}
val orbitWebViewResolverAndroidTest =
    providers.gradleProperty("orbitWebViewResolverAndroidTest").orNull == "true"
val orbitIncomingRequestAndroidTest =
    providers.gradleProperty("orbitIncomingRequestAndroidTest").orNull == "true"
val orbitCs3AcceptanceAndroidTest =
    providers.gradleProperty("orbitCs3AcceptanceAndroidTest").orNull == "true"

require(!(orbitIncomingRequestAndroidTest &&
    (orbitQueueRenderAndroidTest || orbitWebViewResolverAndroidTest || orbitCs3AcceptanceAndroidTest))) {
    "orbitIncomingRequestAndroidTest cannot be combined with another isolated AndroidTest variant"
}
require(!(orbitCs3AcceptanceAndroidTest &&
    (orbitQueueRenderAndroidTest || orbitWebViewResolverAndroidTest))) {
    "orbitCs3AcceptanceAndroidTest cannot be combined with another isolated AndroidTest variant"
}

android {
    namespace = "com.auras.orbit"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinorApiLevel.get().toInt()
        }
    }
    testBuildType = when {
        orbitQueueRenderAndroidTest -> "queueRender"
        orbitWebViewResolverAndroidTest -> "webviewFixture"
        orbitIncomingRequestAndroidTest -> "incomingRequestFixture"
        orbitCs3AcceptanceAndroidTest -> "cs3Acceptance"
        else -> "debug"
    }

    defaultConfig {
        applicationId = "com.auras.orbit"
        testApplicationId = when {
            orbitWebViewResolverAndroidTest -> "com.auras.orbit.webviewfixture.test"
            orbitIncomingRequestAndroidTest -> "com.auras.orbit.debug.test"
            else -> "com.auras.orbit.test"
        }
        testInstrumentationRunner = "com.auras.orbit.testing.OrbitIsolatedAndroidJUnitRunner"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-shell"
        buildConfigField("boolean", "CS3_CANARY_BUILD", "false")
        buildConfigField("boolean", "CS3_ACCEPTANCE_BUILD", "false")
        buildConfigField("String", "ORBIT_ANILIST_CLIENT_ID", "\"$orbitAniListClientId\"")
        buildConfigField("String", "ORBIT_TRAKT_CLIENT_ID", "\"$orbitTraktClientId\"")
    }

    signingConfigs {
        if (hasOrbitReleaseSigning) {
            create("stableRelease") {
                storeFile = File(orbitReleaseStoreFile!!)
                storePassword = orbitReleaseStorePassword
                keyAlias = orbitReleaseKeyAlias
                keyPassword = orbitReleaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasOrbitReleaseSigning) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
        create("queueRender") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".queuerender"
        }
        create("webviewFixture") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".webviewfixture"
            buildConfigField("boolean", "CS3_CANARY_BUILD", "false")
        }
        if (orbitIncomingRequestAndroidTest) {
            create("incomingRequestFixture") {
                initWith(getByName("debug"))
                applicationIdSuffix = ".debug"
                buildConfigField("boolean", "CS3_CANARY_BUILD", "false")
            }
        }
        create("cs3Acceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".cs3acceptance"
            buildConfigField("boolean", "CS3_CANARY_BUILD", "false")
            buildConfigField("boolean", "CS3_ACCEPTANCE_BUILD", "true")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Apply the updater-facing numeric version to the production release APK only;
// Debug and isolated fixture variants keep their existing development version.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.lifecycleTasks.registerPreBuild(verifyOrbitReleaseSigningInputs)
        variant.outputs.forEach { output ->
            output.versionCode.set(orbitReleaseVersionCode)
            output.versionName.set(orbitReleaseVersionName)
        }
    }
}
