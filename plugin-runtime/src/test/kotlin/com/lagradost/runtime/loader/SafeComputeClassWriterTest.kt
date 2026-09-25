package com.lagradost.runtime.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeComputeClassWriterTest {

    private class TestSafeComputeClassWriter : SafeComputeClassWriter() {
        fun resolveCommonSuperClass(type1: String, type2: String): String {
            return getCommonSuperClass(type1, type2)
        }
    }

    @Test
    fun testCommonSuperClassWithStandardTypes() {
        val writer = TestSafeComputeClassWriter()
        assertEquals("java/lang/String", writer.resolveCommonSuperClass("java/lang/String", "java/lang/String"))
        assertEquals("java/lang/Object", writer.resolveCommonSuperClass("java/lang/String", "java/lang/Integer"))
        assertEquals("java/lang/Object", writer.resolveCommonSuperClass("java/lang/Object", "java/lang/String"))
    }

    @Test
    fun testMissingTypeGracefulFallback() {
        val writer = TestSafeComputeClassWriter()
        // Missing synthetic classes should safely fall back to java/lang/Object instead of throwing TypeNotPresentException
        assertEquals("java/lang/Object", writer.resolveCommonSuperClass("com/nonexistent/FakeClass1", "com/nonexistent/FakeClass2"))
        assertEquals("java/lang/Object", writer.resolveCommonSuperClass("java/lang/String", "com/nonexistent/FakeClass2"))
        assertEquals("java/lang/Object", writer.resolveCommonSuperClass("com/nonexistent/FakeClass1", "java/lang/String"))
    }

    @Test
    fun testBytecodeTransformerRoundTrip() {
        val tempJar = File.createTempFile("test_plugin", ".jar")
        tempJar.deleteOnExit()

        // Generate a sample class with branch bytecode
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/test/SamplePlugin", null, "java/lang/Object", null)

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "testMethod", "(I)Ljava/lang/String;", null, null)
        mv.visitCode()
        val l1 = org.objectweb.asm.Label()
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitJumpInsn(Opcodes.IFEQ, l1)
        mv.visitLdcInsn("Branch A")
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(l1)
        mv.visitLdcInsn("Branch B")
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()

        val classBytes = cw.toByteArray()

        ZipOutputStream(FileOutputStream(tempJar)).use { zos ->
            zos.putNextEntry(ZipEntry("com/test/SamplePlugin.class"))
            zos.write(classBytes)
            zos.closeEntry()
        }

        // Run transformation
        PluginBytecodeTransformer.transform(tempJar)

        // Verify the jar can be opened and contains transformed class
        java.util.zip.ZipFile(tempJar).use { zip ->
            val entry = zip.getEntry("com/test/SamplePlugin.class")
            assertNotNull(entry)
        }
    }

    @Test
    fun testFileOperationsAreGuardedAfterTransformation() {
        val tempJar = File.createTempFile("test_file_guard", ".jar")
        tempJar.deleteOnExit()
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/test/FilePlugin", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "remove", "(Ljava/io/File;)Z", null, null)
        method.visitCode()
        method.visitVarInsn(Opcodes.ALOAD, 0)
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "delete", "()Z", false)
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        ZipOutputStream(FileOutputStream(tempJar)).use { zip ->
            zip.putNextEntry(ZipEntry("com/test/FilePlugin.class"))
            zip.write(writer.toByteArray())
            zip.closeEntry()
        }

        PluginBytecodeTransformer.transform(tempJar)

        java.util.zip.ZipFile(tempJar).use { zip ->
            val reader = org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("com/test/FilePlugin.class")))
            val node = org.objectweb.asm.tree.ClassNode()
            reader.accept(node, 0)
            val calls = node.methods.first { it.name == "remove" }.instructions
                .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
                .toList()
            assertTrue(calls.any { it.owner.endsWith("PluginFileSecurityStub") && it.name == "checkFile" })
            assertTrue(calls.any { it.owner == "java/io/File" && it.name == "delete" })
        }
    }
}
