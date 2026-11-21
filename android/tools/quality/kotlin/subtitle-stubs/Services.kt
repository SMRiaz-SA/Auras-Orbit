package com.auras.orbit.extensions
import java.io.File
object OrbitCs3HostServices {
    @JvmStatic fun get(): OrbitCs3HostServices = this
    val applicationContext: TestContext = TestContext()
    class TestContext { val cacheDir: File = File(System.getProperty("java.io.tmpdir")) }
}
