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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(libs.nicehttp)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    // We may need to depend on the library to pass SubtitleData and ExtractorLink
    implementation(project(":library"))
    implementation(project(":common"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
