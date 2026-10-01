package com.lagradost.runtime.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

class PluginSecurityVerifierTest {
    @TempDir
    lateinit var tempDir: File

    private fun jarWithCall(className: String, opcode: Int, owner: String, method: String, descriptor: String): File {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        val methodWriter = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
        methodWriter.visitCode()
        methodWriter.visitMethodInsn(opcode, owner, method, descriptor, false)
        methodWriter.visitInsn(Opcodes.POP)
        methodWriter.visitInsn(Opcodes.RETURN)
        methodWriter.visitMaxs(1, 0)
        methodWriter.visitEnd()
        writer.visitEnd()

        val jar = File(tempDir, "$className.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("$className.class"))
            zip.write(writer.toByteArray())
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `normal JVM APIs are not filtered from plugin bytecode`() {
        val references = listOf(
            Triple("java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;") to Opcodes.INVOKEVIRTUAL,
            Triple("java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;") to Opcodes.INVOKESTATIC,
            Triple("java/net/URL", "openStream", "()Ljava/io/InputStream;") to Opcodes.INVOKEVIRTUAL,
            Triple("java/nio/file/Files", "readAllBytes", "(Ljava/nio/file/Path;)[B") to Opcodes.INVOKESTATIC,
            Triple("java/net/Socket", "toString", "()Ljava/lang/String;") to Opcodes.INVOKEVIRTUAL,
            Triple("java/io/RandomAccessFile", "readByte", "()B") to Opcodes.INVOKEVIRTUAL,
        )

        for ((reference, opcode) in references) {
            val (owner, method, descriptor) = reference
            val jar = jarWithCall("Plugin${owner.substringAfterLast('/')}", opcode, owner, method, descriptor)
            PluginSecurityVerifier.verifyJar(jar, jar.nameWithoutExtension, isTrusted = false)
        }
    }

    @Test
    fun `structurally invalid archive paths are still rejected`() {
        val jar = File(tempDir, "unsafe.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.class"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        assertFailsWith<IllegalArgumentException> {
            PluginSecurityVerifier.verifyJar(jar, "unsafe", isTrusted = false)
        }
    }
}
