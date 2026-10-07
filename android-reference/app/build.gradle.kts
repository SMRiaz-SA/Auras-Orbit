import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import java.io.File
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

abstract class CanonicalAndroidApkNameTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val inputDirectory: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun applyCanonicalName() {
        val input = inputDirectory.get().asFile
        val apkFiles = input.walkTopDown().filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }.toList()
        check(apkFiles.size == 1) {
            "Expected one APK for this variant, found ${apkFiles.size} in $input"
        }

        val metadataFiles = input.walkTopDown().filter { it.isFile && it.name == "output-metadata.json" }.toList()
        check(metadataFiles.size == 1) {
            "Expected one APK output metadata file for this variant, found " + metadataFiles.size + " in " + input
        }

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        apkFiles.single().copyTo(File(output, "Auras-Orbit.apk"), overwrite = true)

        val metadata = metadataFiles.single().readText()
        val outputFileField = Regex("\"outputFile\"\\s*:\\s*\"[^\"]+\"")
        val rewritten = outputFileField.replace(metadata) { "\"outputFile\": \"Auras-Orbit.apk\"" }
        check(rewritten != metadata) { "APK output metadata did not contain an outputFile entry: " + metadataFiles.single() }
        File(output, "output-metadata.json").writeText(rewritten)
    }
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

val repositoryGradleProperties = providers.fileContents(
    rootProject.layout.projectDirectory.file("../gradle.properties")
).asText.get()
val aurasVersionName = Regex("(?m)^APP_VERSION\\s*=\\s*([^\\r\\n]+)")
    .find(repositoryGradleProperties)
    ?.groupValues
    ?.getOrNull(1)
    ?.trim()
    ?: error("APP_VERSION must be set in the repository root gradle.properties file.")
val aurasVersionParts = aurasVersionName.split(".")
require(aurasVersionParts.size in 3..4) {
    "APP_VERSION must contain three or four numeric components: $aurasVersionName"
}
val numericVersionParts = aurasVersionParts.map { part ->
    part.toIntOrNull() ?: error("APP_VERSION must contain only numeric components: $aurasVersionName")
}
val (versionMajor, versionMinor, versionPatch, versionBuild) =
    if (numericVersionParts.size == 3) numericVersionParts + 0 else numericVersionParts
require(versionMajor in 0..21 && versionMinor in 0..99 && versionPatch in 0..99 && versionBuild in 0..9999) {
    "Auras Android version components exceed the versionCode allocation: $aurasVersionName"
}
val aurasVersionCode =
    versionMajor * 100_000_000 + versionMinor * 1_000_000 + versionPatch * 10_000 + versionBuild
require(aurasVersionCode in 1..2_100_000_000) {
    "APP_VERSION exceeds Android's versionCode range: $aurasVersionName"
}

abstract class GenerateGitHashTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val head = headFile.get().asFile

        val hash = try {
            if (head.exists()) {
                // Read the commit hash from .git/HEAD
                val headContent = head.readText().trim()
                if (headContent.startsWith("ref:")) {
                    val refPath = headContent.substring(5) // e.g., refs/heads/main
                    val commitFile = File(head.parentFile, refPath)
                    if (commitFile.exists()) commitFile.readText().trim() else ""
                } else headContent // If it's a detached HEAD (commit hash directly)
            } else "" // If .git/HEAD doesn't exist
        } catch (_: Throwable) {
            "" // Just set to an empty string if any exception occurs
        }.take(7) // Get the short commit hash

        val outFile = outputDir.file("git-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash)
    }
}

val generateGitHash = tasks.register<GenerateGitHashTask>("generateGitHash") {
    // The Android project lives inside the desktop repository; resolve Git metadata
    // from the shared repository root rather than this nested project directory.
    val gitDir = rootProject.layout.projectDirectory.dir("../.git")

    headFile.set(gitDir.file("HEAD"))
    headsDir.set(gitDir.dir("refs/heads"))

    outputDir.set(layout.buildDirectory.dir("generated/git"))
}

val stableReleaseStoreFile = providers.environmentVariable("AURAS_RELEASE_STORE_FILE").orNull
val stableReleaseStorePassword = providers.environmentVariable("AURAS_RELEASE_STORE_PASSWORD").orNull
val stableReleaseKeyAlias = providers.environmentVariable("AURAS_RELEASE_KEY_ALIAS").orNull
val stableReleaseKeyPassword = providers.environmentVariable("AURAS_RELEASE_KEY_PASSWORD").orNull
val hasStableReleaseSigning = listOf(
    stableReleaseStoreFile,
    stableReleaseStorePassword,
    stableReleaseKeyAlias,
    stableReleaseKeyPassword
).all { !it.isNullOrBlank() }

val verifyStableReleaseSigning = tasks.register("verifyStableReleaseSigning") {
    doLast {
        val missing = mapOf(
            "AURAS_RELEASE_STORE_FILE" to stableReleaseStoreFile,
            "AURAS_RELEASE_STORE_PASSWORD" to stableReleaseStorePassword,
            "AURAS_RELEASE_KEY_ALIAS" to stableReleaseKeyAlias,
            "AURAS_RELEASE_KEY_PASSWORD" to stableReleaseKeyPassword
        ).filterValues { it.isNullOrBlank() }.keys

        check(missing.isEmpty()) {
            "stableRelease requires signing configuration. Missing: ${missing.joinToString()}"
        }
        check(file(stableReleaseStoreFile!!).isFile) {
            "AURAS_RELEASE_STORE_FILE does not point to an existing keystore."
        }
    }
}

android {
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Looks like google likes to add metadata only they can read https://gitlab.com/IzzyOnDroid/repo/-/work_items/491
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generateGitHash,
                GenerateGitHashTask::outputDir
            )

            tasks.register<CanonicalAndroidApkNameTask>(
                "canonical${variant.name.replaceFirstChar { it.uppercase() }}Apk"
            ) {
                inputDirectory.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
                outputDirectory.set(layout.buildDirectory.dir("outputs/canonical/" + variant.name))
            }
        }
    }

    signingConfigs {
        if (hasStableReleaseSigning) {
            create("stableRelease") {
                storeFile = file(stableReleaseStoreFile!!)
                storePassword = stableReleaseStorePassword
                keyAlias = stableReleaseKeyAlias
                keyPassword = stableReleaseKeyPassword
            }
        }

        // We just use SIGNING_KEY_ALIAS here since it won't change
        // so won't kill the configuration cache.
        if (System.getenv("SIGNING_KEY_ALIAS") != null) {
            create("prerelease") {
                val tmpFilePath = System.getProperty("user.home") + "/work/_temp/keystore/"
                val prereleaseStoreFile: File? = File(tmpFilePath).listFiles()?.first()

                storeFile = prereleaseStoreFile?.let { file(it) }
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.auras.orbit"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = aurasVersionCode
        versionName = aurasVersionName

        manifestPlaceholders["target_sdk_version"] = libs.versions.targetSdk.get()

        // Reads local.properties
        val localProperties = gradleLocalProperties(rootDir, project.providers)

        buildConfigField(
            "long",
            "BUILD_DATE",
            "${System.currentTimeMillis()}"
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_ID",
            "\"" + (System.getenv("SIMKL_CLIENT_ID") ?: localProperties["simkl.id"]) + "\""
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_SECRET",
            "\"" + (System.getenv("SIMKL_CLIENT_SECRET") ?: localProperties["simkl.secret"]) + "\""
        )
        buildConfigField(
            "String",
            "MAL_KEY",
            "\"" + (System.getenv("MAL_KEY") ?: localProperties["mal.key"]) + "\""
        )
        buildConfigField(
            "String",
            "ANILIST_KEY",
            "\"" + (System.getenv("ANILIST_KEY") ?: localProperties["anilist.key"]) + "\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasStableReleaseSigning) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("state")
    productFlavors {
        create("stable") {
            dimension = "state"
        }
        create("prerelease") {
            dimension = "state"
            applicationIdSuffix = ".prerelease"
            if (signingConfigs.names.contains("prerelease")) {
                signingConfig = signingConfigs.getByName("prerelease")
            } else {
                logger.warn("No prerelease signing config!")
            }
            versionNameSuffix = "-PRE"
            versionCode = aurasVersionCode
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(javaTarget.target)
        targetCompatibility = JavaVersion.toVersion(javaTarget.target)
    }

    java {
        // Use Java 17 toolchain even if a higher JDK runs the build.
        // We still use Java 8 for now which higher JDKs have deprecated.
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Enables legacy JNI packaging to reduce APK size (similar to builds before minSdk 23).
            // Note: This may increase app startup time slightly.
            useLegacyPackaging = true
        }
    }

    // Keep the source namespace stable for CloudStream extension compatibility.
    namespace = "com.lagradost.cloudstream3"
}

dependencies {
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.instancio.core)
    androidTestImplementation(libs.junit.ktx)
    androidTestImplementation(libs.kotlin.test)

    // Android Core & Lifecycle
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json) // JSON Parser

    // Design & UI
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Coil Image Loading
    implementation(libs.bundles.coil)
    implementation(libs.coil.svg)

    // Media 3 (ExoPlayer)
    implementation(libs.bundles.media3)
    implementation(libs.video)

    // FFmpeg Decoding
    implementation(libs.bundles.nextlib)

    // Anime-db for filler
    implementation(libs.anime.db)

    // PlayBack
    implementation(libs.colorpicker) // Subtitle Color Picker
    implementation(libs.newpipeextractor) // For Trailers
    implementation(libs.juniversalchardet) // Subtitle Decoding

    // UI Stuff
    implementation(libs.shimmer) // Shimmering Effect (Loading Skeleton)
    implementation(libs.palette.ktx) // Palette for Images -> Colors
    implementation(libs.tvprovider)
    implementation(libs.overlappingpanels) // Gestures
    implementation(libs.biometric) // Fingerprint Authentication
    implementation(libs.previewseekbar.media3) // SeekBar Preview
    implementation(libs.qrcode.kotlin) // QR Code for PIN Auth on TV

    // Extensions & Other Libs
    implementation(libs.jsoup) // HTML Parser
    implementation(libs.ksoup) // HTML Parser
    implementation(libs.rhino) // Run JavaScript
    implementation(libs.safefile) // To Prevent the URI File Fu*kery
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio) // NIO Flavor Needed for NewPipeExtractor
    implementation(libs.conscrypt.android) // To Fix SSL Fu*kery on Android 9
    implementation(libs.jackson.module.kotlin) // JSON Parser
    implementation(libs.zipline)

    // Temp/deprecated; will be removed once extensions have time to migrate from using it
    implementation("com.google.code.gson:gson:2.11.0")
    // Deprecated; will be removed once extensions have time to migrate from using it
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Torrent Support
    implementation(libs.torrentserver)

    // Downloading & Networking
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp) // HTTP Lib

    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.io.core) // Logcat parser

    implementation(project(":library"))
    implementation(project(":shared"))
}

// NiceHttp and Coil's OkHttp transport both publish JVM artifacts alongside
// okhttp-android. The Android artifact already contains these classes, so keep
// the duplicate JVM jar out of Android runtime packaging.
configurations.configureEach {
    exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.directories) // Full Sources
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")
    from(
        "build/intermediates/compile_app_classes_jar/prereleaseDebug/bundlePrereleaseDebugClassesToCompileJar",
        "../library/build/libs"
    )
    into("build/app-classes")
    include("classes.jar", "library-jvm*.jar")
    // Remove the version
    rename("library-jvm.*.jar", "library-jvm.jar")
}

tasks.matching { it.name == "packageStableRelease" }.configureEach {
    dependsOn(verifyStableReleaseSigning)
}

// Merge the app classes and the library classes into classes.jar
tasks.register<Jar>("makeJar") {
    // Duplicates cause hard to catch errors, better to fail at compile time.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(tasks.getByName("copyJar"))
    from(
        zipTree("build/app-classes/classes.jar"),
        zipTree("build/app-classes/library-jvm.jar")
    )
    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName = "classes"
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(javaTarget)
        jvmDefault.set(JvmDefaultMode.ENABLE)
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
        )
    }
}

dokka {
    moduleName = "App"
    dokkaSourceSets {
        configureEach {
            suppress = name != "prereleaseDebug"
            analysisPlatform = KotlinPlatform.JVM
            displayName = "JVM"
            documentedVisibilities(
                VisibilityModifier.Public,
                VisibilityModifier.Protected
            )

            sourceLink {
                localDirectory = file("..")
                remoteUrl("https://github.com/recloudstream/cloudstream/tree/master")
                remoteLineSuffix = "#L"
            }
        }
    }
}
