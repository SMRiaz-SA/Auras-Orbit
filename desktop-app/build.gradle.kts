import org.gradle.api.tasks.Copy

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    alias(libs.plugins.kotlin.serialization)
}

val appVersion =
    providers.gradleProperty("APP_VERSION").orNull
        ?.takeIf(String::isNotBlank)
        ?: error("APP_VERSION must be set in the root gradle.properties file.")
require(appVersion.matches(Regex("\\d+(?:\\.\\d+){2,3}"))) {
    "APP_VERSION must contain three or four numeric components: $appVersion"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        java.srcDirs("src/main/java")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

dependencies {
    // CloudStream Library (KMP, JVM target)
    // Contains: MainAPI, extractors, metaproviders, WebViewResolver (JVM actual), etc.
    implementation(project(":library"))
    implementation(libs.kotlinx.serialization.json)

    // ASM Bytecode Scanner
    implementation(libs.asm)
    implementation(libs.asm.tree)

    // Android Stubs
    implementation(project(":android-stubs"))

    implementation(project(":plugin-runtime"))
    implementation(project(":player-abstraction"))
    implementation(project(":common"))

    // HTTP
    implementation(libs.nicehttp)
    implementation(libs.newpipeextractor)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0") // Required for plugins using JsonParser.parseString (matches Android app)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    implementation(kotlin("reflect")) // Required for Jackson to deserialize plugin Kotlin data classes
    implementation(libs.json) // Required for plugins using org.json (natively included on Android)

    // Coroutines (swing provides Dispatchers.Main on desktop JVM)
    val coroutinesVersion = "1.10.2"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutinesVersion")

    // Desktop counterpart of Android's WebView system (now native CDP).
    // Android's built-in AES-GCM crypto is not available on desktop JVM.
    implementation(libs.bcprov)
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")

    // JNA for MPV
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // Compose Desktop UI
    implementation(compose.desktop.currentOs)
    implementation(compose.material3) // material3 already includes core icons
    implementation(compose.materialIconsExtended)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation("dev.chrisbanes.haze:haze:1.3.1")

    // Decompose Navigation
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))

    // SQLDelight
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqldelight.coroutines.extensions)
}

val orbitDistribution = project.findProperty("orbitDistribution")?.toString() ?: "release"
require(orbitDistribution == "release" || orbitDistribution == "tester") {
    "orbitDistribution must be either 'release' or 'tester' (was '$orbitDistribution')."
}
val testerDefaultRepositoryJvmArg =
    if (orbitDistribution == "tester") {
        "-Dauras.tester.defaultRepositoryUrl=https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
    } else {
        null
    }

// Compose Desktop application configuration
compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream3.desktop.MainKt"
        jvmArgs +=
            listOf(
                // StreamPlay-sized DEX files need more than the JVM's default 25% RAM cap to translate.
                "-XX:MaxRAMPercentage=35.0",
                "-Djava.net.preferIPv6Addresses=true",
                "-Djava.library.path=\$APPDIR/resources/jni",
                "-Djna.library.path=\$APPDIR/resources/mpv",
                "-Dcloudstream.version=$appVersion",
                "-Dfile.encoding=UTF-8",
            )
        jvmArgs += listOfNotNull(testerDefaultRepositoryJvmArg)
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // Inno Setup (installer/setup.iss) handles packaging — no native installer format needed here
            packageName = "Auras-Orbit"
            // jpackage STRICTLY requires version to be numeric (e.g. 0.1.5). Strip any -beta or -pre-alpha suffixes.
            packageVersion = appVersion.substringBefore('-')
            description = "Auras Orbit open-source desktop client"
            vendor = "Auras Prime Dynamics"
            includeAllModules = false
            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.dynalink",
                "jdk.unsupported", // Required by JNA & Coroutines Unsafe
                "jdk.crypto.ec", // Required for HTTPS
                "jdk.crypto.cryptoki",
                "jdk.crypto.mscapi", // Required on Windows for some HTTPS cert verifications
                "jdk.management",
                "jdk.charsets", // Required to decode some foreign websites
                "jdk.zipfs", // Required by dex2jar for JAR generation
                "java.compiler", // Required by Rhino JS compiler
                "jdk.compiler", // Required by Rhino JS compiler
                "jdk.localedata", // Required by Rhino JS Date functions
            )
            appResourcesRootDir.set(project.layout.buildDirectory.dir("generated/appResources"))

            windows {
                iconFile.set(project.file("src/main/resources/app_icon.ico"))
                menuGroup = "Auras Orbit"
                upgradeUuid = "d7e9b04f-723a-4467-84df-fcf470c1ae02"
                shortcut = true // Creates a Desktop shortcut during install
                perUserInstall = true // Installs per-user, avoids needing admin rights
            }
        }
    }
}

tasks.matching { it.name == "run" }.configureEach {
    dependsOn("compileNativeBridge")
    val runTask = this as JavaExec
    runTask.jvmArgs(
        "-Djna.library.path=${project.file("appResources/windows/mpv").absolutePath}",
        "-Djava.library.path=${project.file("build/native/jni").absolutePath}",
        "-Dcloudstream.version=$appVersion",
    )
    testerDefaultRepositoryJvmArg?.let { runTask.jvmArgs(it) }
}

val generateInstallerVersion by tasks.registering {
    val versionFile = layout.buildDirectory.file("generated/installer/version.iss").get().asFile
    inputs.property("APP_VERSION", appVersion)
    outputs.file(versionFile)
    doLast {
        versionFile.parentFile.mkdirs()
        versionFile.writeText("#define AppVersion \"$appVersion\"")
    }
}

tasks.named<Copy>("processResources") {
    dependsOn(generateInstallerVersion)
    from(project.rootProject.file("LICENSE")) {
        into("legal")
        rename { "LICENSE.txt" }
    }
    from(project.rootProject.file("NOTICE.md")) {
        into("legal")
        rename { "NOTICE.txt" }
    }
    from(project.rootProject.file("THIRD-PARTY-NOTICES.md")) {
        into("legal")
        rename { "THIRD-PARTY-NOTICES.txt" }
    }
}

tasks.register<Test>("nativeTest") {
    dependsOn("compileNativeBridge")
    dependsOn("testControlsUrlPolicy")
    description = "Runs native MPV integration checks with bundled runtime libraries."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("native") }
    systemProperty("jna.library.path", project.file("appResources/windows/mpv").absolutePath)
    systemProperty("java.library.path", project.file("build/native/jni").absolutePath)
}

tasks.register<Exec>("testControlsUrlPolicy") {
    group = "verification"
    description = "Compiles and runs native WebView controls navigation policy checks."
    workingDir = rootProject.projectDir
    commandLine(
        "pwsh",
        "-NoProfile",
        "-File",
        project.file("src/test/cpp/run_controls_url_policy_tests.ps1").absolutePath,
    )
}

val compileNativeBridge by tasks.registering(Exec::class) {
    group = "build"
    workingDir = rootProject.projectDir
    inputs.file(rootProject.file("compile_jni.ps1"))
    inputs.file(rootProject.file(".github/scripts/fetch-native-toolchain.ps1"))
    inputs.dir("src/main/cpp")
    outputs.dir(layout.buildDirectory.dir("native/jni"))
    commandLine("pwsh", "-NoProfile", "-File", rootProject.file("compile_jni.ps1").absolutePath)
}

val prepareRuntimeResources by tasks.registering(Sync::class) {
    dependsOn(compileNativeBridge)
    from("appResources") { exclude("windows/jni/player_bridge.dll") }
    from(layout.buildDirectory.dir("native/jni")) { into("windows/jni") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into(layout.buildDirectory.dir("generated/appResources"))
}

val copyDistributionLegalNotices by tasks.registering(Copy::class) {
    val requiredFiles =
        listOf(
            rootProject.file("LICENSE"),
            rootProject.file("NOTICE.md"),
            rootProject.file("THIRD-PARTY-NOTICES.md"),
            rootProject.file("android-reference/LICENSE"),
            project.file("src/main/cpp/webview2/LICENSE.txt"),
            project.file("src/main/cpp/webview2/NOTICE.txt"),
            project.file("appResources/legal/mpv-LICENSE.GPL.txt"),
            project.file("appResources/legal/MPV-PROVENANCE.txt"),
        )
    inputs.files(requiredFiles)
    into(layout.buildDirectory.dir("compose/binaries/main/app/Auras-Orbit"))

    from(rootProject.file("LICENSE")) {
        into("legal")
        rename { "AURAS-ORBIT-LICENSE.txt" }
    }
    from(rootProject.file("NOTICE.md")) {
        into("legal")
        rename { "AURAS-ORBIT-NOTICE.txt" }
    }
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) {
        into("legal")
        rename { "THIRD-PARTY-NOTICES.txt" }
    }
    from(rootProject.file("android-reference/LICENSE")) {
        into("legal")
        rename { "CLOUDSTREAM-UPSTREAM-LICENSE.txt" }
    }
    from(project.file("src/main/cpp/webview2/LICENSE.txt")) {
        into("legal")
        rename { "WEBVIEW2-SDK-LICENSE.txt" }
    }
    from(project.file("src/main/cpp/webview2/NOTICE.txt")) {
        into("legal")
        rename { "WEBVIEW2-SDK-NOTICE.txt" }
    }
    from(project.file("appResources/legal/mpv-LICENSE.GPL.txt")) {
        into("legal")
        rename { "MPV-LICENSE.GPL.txt" }
    }
    from(project.file("appResources/legal/MPV-PROVENANCE.txt")) {
        into("legal")
        rename { "MPV-PROVENANCE.txt" }
    }

    doFirst {
        val missing = requiredFiles.filterNot(File::isFile)
        check(missing.isEmpty()) {
            "Required distribution legal/provenance files are missing: ${missing.joinToString()}. Run .github/scripts/fetch-mpv.ps1 first."
        }
    }
}

tasks.matching { it.name == "createDistributable" || it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareRuntimeResources)
    if (name == "createDistributable") {
        // Compose's distributable task does not reliably fingerprint this generated
        // runtime-resource directory. Track it so a rebuilt JNI bridge cannot leave
        // a stale player_bridge.dll in an otherwise up-to-date app image.
        inputs.dir(layout.buildDirectory.dir("generated/appResources"))
        finalizedBy(copyDistributionLegalNotices)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        // Exclude integration tests that require native binaries (e.g. libmpv-2.dll)
        // Run them manually with: ./gradlew :desktop-app:nativeTest
        excludeTags("native")
    }
}

tasks.register<JavaExec>("runTestWebViewPlayer") {
    dependsOn(compileNativeBridge)
    mainClass.set("com.lagradost.cloudstream3.desktop.test.TestWebViewPlayerKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Djava.library.path=build/native/jni", "-Djna.library.path=appResources/windows/mpv")
}

tasks.register<JavaExec>("runTestMpvPlayer") {
    mainClass.set("com.lagradost.cloudstream3.desktop.test.TestMpvPlayerKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Djna.library.path=appResources/windows/mpv")
}
