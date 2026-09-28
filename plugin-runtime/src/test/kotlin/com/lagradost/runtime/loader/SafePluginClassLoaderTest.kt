package com.lagradost.runtime.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafePluginClassLoaderTest {

    @Test
    fun cloudStreamApisAreAvailableRegardlessOfTrustMetadata() {
        for (trusted in listOf(false, true)) {
            val loader = SafePluginClassLoader(javaClass.classLoader, trusted)
            val apiClasses = listOf(
                "com.lagradost.cloudstream3.syncproviders.AccountManager",
                "com.lagradost.cloudstream3.utils.DataStore",
            )
            for (className in apiClasses) {
                assertEquals(className, loader.loadClass(className).name)
                assertTrue(com.lagradost.runtime.security.PluginSecurityPolicy.isClassAllowed(className, isTrusted = trusted))
            }
        }
    }

    @Test
    fun pluginsCanLoadNormalJvmApisWithoutAnAllowlist() {
        val classLoader = SafePluginClassLoader(this::class.java.classLoader)
        val classes = listOf(
            "java.lang.Thread",
            "java.lang.ProcessBuilder",
            "java.nio.file.Files",
            "java.nio.file.Path",
            "java.net.Socket",
            "com.lagradost.cloudstream3.syncproviders.AccountManager",
        )

        for (className in classes) {
            assertEquals(className, classLoader.loadClass(className).name)
            assertTrue(com.lagradost.runtime.security.PluginSecurityPolicy.isClassAllowed(className))
        }
    }

    @Test
    fun testAllowedClassesLoadedNormally() {
        val classLoader = SafePluginClassLoader(this::class.java.classLoader)

        // java.lang.String is perfectly safe
        val strClass = classLoader.loadClass("java.lang.String")
        assertEquals("java.lang.String", strClass.name)

        // In-memory NIO buffers and charsets are allowed for decoders
        val byteBufferClass = classLoader.loadClass("java.nio.ByteBuffer")
        assertEquals("java.nio.ByteBuffer", byteBufferClass.name)

        val charsetsClass = classLoader.loadClass("java.nio.charset.StandardCharsets")
        assertEquals("java.nio.charset.StandardCharsets", charsetsClass.name)

        // File APIs retain normal JVM behavior.
        val fileClass = classLoader.loadClass("java.io.File")
        assertEquals("java.io.File", fileClass.name)

        val fileInputStreamClass = classLoader.loadClass("java.io.FileInputStream")
        assertEquals("java.io.FileInputStream", fileInputStreamClass.name)
    }

    @Test
    fun testNormalJvmClassesAreNotBlocked() {
        val classLoader = SafePluginClassLoader(this::class.java.classLoader)

        val regularClasses = listOf(
            "java.lang.Thread",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessHandle",
            "java.lang.ClassLoader",
            "java.lang.invoke.MethodHandles\$Lookup",
            "java.util.concurrent.Executors",
            "java.util.ServiceLoader",
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.nio.file.Files",
            "java.nio.file.Paths",
            "java.nio.file.Path",
            "java.awt.Desktop",
            "java.awt.Robot",
            "java.net.NetworkInterface",
            "java.io.RandomAccessFile",
            "sun.misc.Unsafe",
        )

        for (className in regularClasses) {
            assertEquals(className, classLoader.loadClass(className).name)
        }

        // The JVM may resolve plugin native libraries through java.library.path.
        assertNull(classLoader.findLibrary("native_exploit"))
    }

    @Test
    fun testUnknownPluginClassThrowsClassNotFound() {
        val classLoader = SafePluginClassLoader(this::class.java.classLoader)

        // Unknown 3rd party or plugin internal classes must throw ClassNotFoundException
        // so the child CompatPluginClassLoader checks the plugin's JAR
        assertFailsWith<ClassNotFoundException> {
            classLoader.loadClass("com.example.provider.MyCustomExtractor")
        }
    }

    @Test
    fun testGhostStubGenerationForAndroidAndGoogleClasses() {
        val classLoader = SafePluginClassLoader(this::class.java.classLoader)
        val stubClass = classLoader.loadClass("com.google.android.material.bottomsheet.BottomSheetDialogFragment")
        assertEquals("com.google.android.material.bottomsheet.BottomSheetDialogFragment", stubClass.name)
    }

    @Test
    fun testPrivacySpooferReturnsGenericData() {
        val locale = com.lagradost.cloudstream3.PrivacySpoofer.getSpoofedLocale()
        assertEquals("en", locale.language)
        assertEquals("US", locale.country)

        val tz = com.lagradost.cloudstream3.PrivacySpoofer.getSpoofedTimeZone()
        assertEquals("UTC", tz.id)

        val zoneId = com.lagradost.cloudstream3.PrivacySpoofer.getSpoofedZoneId()
        assertEquals("UTC", zoneId.id)
    }

    @Test
    fun testSystemStubMatchesNormalJvmValues() {
        assertEquals(System.getProperty("line.separator"), com.lagradost.runtime.loader.stubs.SystemStub.getProperty("line.separator"))
        assertEquals(System.getProperty("user.home"), com.lagradost.runtime.loader.stubs.SystemStub.getProperty("user.home"))
        assertEquals(System.getenv("PATH"), com.lagradost.runtime.loader.stubs.SystemStub.getenv("PATH"))
        assertEquals(System.getenv(), com.lagradost.runtime.loader.stubs.SystemStub.getenv())
        assertEquals(System.getProperties(), com.lagradost.runtime.loader.stubs.SystemStub.getProperties())
    }
}
