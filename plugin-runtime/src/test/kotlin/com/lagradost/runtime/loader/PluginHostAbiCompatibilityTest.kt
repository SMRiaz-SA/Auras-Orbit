package com.lagradost.runtime.loader

import com.lagradost.cloudstream3.plugins.Plugin
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals

class PluginHostAbiCompatibilityTest {
    @Test
    fun pluginBytecodeCanReadTheCloudstreamPluginStabilityField() {
        val internalName = "fixture/PluginStableFieldReader"
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "read", "()I", null, null)
        method.visitCode()
        method.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/lagradost/cloudstream3/plugins/Plugin",
            "\$stable",
            "I",
        )
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(1, 0)
        method.visitEnd()
        writer.visitEnd()

        val pluginClassLoader = object : ClassLoader(Plugin::class.java.classLoader) {
            fun defineFixture(): Class<*> {
                val bytes = writer.toByteArray()
                return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.size)
            }
        }
        val fixture = pluginClassLoader.defineFixture()

        assertEquals(8, fixture.getMethod("read").invoke(null))
    }
}
