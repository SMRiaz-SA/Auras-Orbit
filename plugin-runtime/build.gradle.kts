plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Needs access to stubs to pass to plugins
    implementation(project(":android-stubs"))

    // Needs access to base CloudstreamPlugin and Extractors
    implementation(project(":library"))
    implementation(libs.nicehttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Logging and common utils
    implementation(project(":common"))

    // Coroutines for plugin dispatcher & invoker isolation
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Dalvik-to-JVM transcompiler
    implementation("de.femtopedia.dex2jar:dex-tools:2.4.38")
    implementation(libs.fastutil) // Required by the DEX method splitter

    // JSON for manifest parsing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")

    // ASM Bytecode Manipulation for Static Verification
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)

    // Rhino JS Engine Sandbox
    implementation(libs.rhino)

    // AXML Parser for plugin settings discovery
    implementation("net.dongliu:apk-parser:2.6.10")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
