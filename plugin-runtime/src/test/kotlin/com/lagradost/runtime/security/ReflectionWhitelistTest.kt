package com.lagradost.runtime.security

import com.lagradost.runtime.loader.stubs.ReflectionStub
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReflectionWhitelistTest {
    @Test
    fun `standard JVM and host types retain reflection support`() {
        val types = listOf(
            String::class.java,
            java.util.ArrayList::class.java,
            java.util.HashMap::class.java,
            java.lang.System::class.java,
            java.lang.Runtime::class.java,
            java.lang.ProcessBuilder::class.java,
            java.lang.ClassLoader::class.java,
            java.lang.Thread::class.java,
            File::class.java,
            java.nio.file.Files::class.java,
            com.lagradost.common.logging.AppLogger::class.java,
        )

        for (type in types) assertTrue(ReflectionStub.isReflectionAllowed(type))
    }

    @Test
    fun `reflection invokes the target method normally`() {
        val method = System::class.java.getMethod("getProperty", String::class.java)
        val result = ReflectionStub.invoke(method, null, arrayOf("java.version"))

        assertEquals(System.getProperty("java.version"), result)
    }
}
