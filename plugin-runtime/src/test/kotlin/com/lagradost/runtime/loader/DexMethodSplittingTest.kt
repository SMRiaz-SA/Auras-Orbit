package com.lagradost.runtime.loader

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.raku.nqp.jast2bc.AutosplitMethodWriter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DexMethodSplittingTest {
    @Test
    fun splitsLargeMethodsAndPreservesBranchesFieldsAndArrays() {
        val internalName = "fixture/generated/LargePluginMethod"
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classWriter.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        classWriter.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null)?.visitEnd()
        classWriter.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "shared", "I", null, null)?.visitEnd()

        val constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()

        // Simulate a plugin-owned method that would collide with the splitter's default helper name.
        val reservedHelper = classWriter.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
            "calculate\$f0",
            "(I[Ljava/lang/Object;)I",
            null,
            null,
        )
        reservedHelper.visitCode()
        reservedHelper.visitInsn(Opcodes.ICONST_0)
        reservedHelper.visitInsn(Opcodes.IRETURN)
        reservedHelper.visitMaxs(0, 0)
        reservedHelper.visitEnd()

        val methodNames = mutableSetOf("<init>", "calculate", "calculate\$f0")
        val method: MethodVisitor = AutosplitMethodWriter(
            classWriter,
            internalName,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "calculate",
            "(I)I",
            null,
            null,
            methodNames,
        )
        method.visitCode()
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitVarInsn(Opcodes.ISTORE, 1)

        method.visitTypeInsn(Opcodes.NEW, internalName)
        method.visitInsn(Opcodes.DUP)
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false)
        method.visitVarInsn(Opcodes.ASTORE, 2)
        method.visitVarInsn(Opcodes.ALOAD, 2)
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitFieldInsn(Opcodes.PUTFIELD, internalName, "value", "I")
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "shared", "I")

        method.visitInsn(Opcodes.ICONST_1)
        method.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
        method.visitVarInsn(Opcodes.ASTORE, 3)
        method.visitVarInsn(Opcodes.ALOAD, 3)
        method.visitInsn(Opcodes.ICONST_0)
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitInsn(Opcodes.IASTORE)

        val loopStart = org.objectweb.asm.Label()
        val end = org.objectweb.asm.Label()
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitJumpInsn(Opcodes.IFGE, loopStart)
        method.visitJumpInsn(Opcodes.GOTO, end)
        method.visitLabel(loopStart)
        repeat(17_000) {
            method.visitVarInsn(Opcodes.ILOAD, 1)
            method.visitInsn(Opcodes.ICONST_1)
            method.visitInsn(Opcodes.IADD)
            method.visitVarInsn(Opcodes.ISTORE, 1)
        }
        method.visitLabel(end)
        method.visitVarInsn(Opcodes.ALOAD, 2)
        method.visitFieldInsn(Opcodes.GETFIELD, internalName, "value", "I")
        method.visitVarInsn(Opcodes.ALOAD, 3)
        method.visitInsn(Opcodes.ICONST_0)
        method.visitInsn(Opcodes.IALOAD)
        method.visitInsn(Opcodes.IADD)
        method.visitVarInsn(Opcodes.ILOAD, 1)
        method.visitInsn(Opcodes.IADD)
        method.visitFieldInsn(Opcodes.GETSTATIC, internalName, "shared", "I")
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        classWriter.visitEnd()

        val bytes = classWriter.toByteArray()
        val helperNames = mutableListOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and Opcodes.ACC_SYNTHETIC != 0 && descriptor == "(I[Ljava/lang/Object;)I") {
                        helperNames += name
                    }
                    return null
                }
            },
            0,
        )
        assertTrue(helperNames.size >= 2, "the oversized method should become multiple helpers")
        assertTrue(helperNames.none { it == "calculate\$f0" }, "generated helpers must not shadow plugin methods")

        val loadedClass = object : ClassLoader(javaClass.classLoader) {
            fun defineGeneratedClass() = defineClass(internalName.replace('/', '.'), bytes, 0, bytes.size)
        }.defineGeneratedClass()
        val calculate = loadedClass.getMethod("calculate", Integer.TYPE)
        assertEquals(17_000, calculate.invoke(null, 0))
        assertEquals(17_069, calculate.invoke(null, 23))
        assertEquals(-15, calculate.invoke(null, -5))
    }
}
