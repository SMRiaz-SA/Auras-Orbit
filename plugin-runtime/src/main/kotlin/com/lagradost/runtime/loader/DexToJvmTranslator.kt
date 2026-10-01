package com.lagradost.runtime.loader

import com.googlecode.d2j.dex.ClassVisitorFactory
import com.googlecode.d2j.dex.Dex2Asm
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.DexFileReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodTooLargeException
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.raku.nqp.jast2bc.AutosplitMethodWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A DEX archive could not be converted into a complete desktop plugin archive. */
open class PluginDexConversionException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

/** A valid DEX method still exceeded the JVM limit after bytecode splitting. */
class PluginDexMethodTooLargeException(
    val dexMethod: String,
    cause: Throwable,
) : PluginDexConversionException(
    "The converted method still exceeds the JVM's 65,535-byte limit: $dexMethod. " +
        "Auras rejected the incomplete conversion; this plugin can run in an Android Cloudstream runtime that executes DEX directly.",
    cause,
)

/**
 * Converts every DEX file in an Android plugin archive into one JVM archive.
 *
 * Long JVM methods are split into private helper methods before ASM writes the class.
 * Conversion is staged, and any parser, bytecode, or class-file error rejects the
 * entire output before it can be cached or loaded.
 */
object DexToJvmTranslator {
    private val DEX_ENTRY_PATTERN = Regex("^classes(?:([2-9]|[1-9][0-9]+))?\\.dex$")

    internal fun isDexEntry(name: String): Boolean = DEX_ENTRY_PATTERN.matches(name)

    internal fun methodTooLargeFailure(method: String, cause: Throwable): PluginDexMethodTooLargeException? =
        if (generateSequence(cause) { it.cause }.any { it is MethodTooLargeException }) {
            PluginDexMethodTooLargeException(method, cause)
        } else {
            null
        }

    internal fun memoryFailure(cause: OutOfMemoryError): PluginDexConversionException =
        PluginDexConversionException(
            "The desktop JVM ran out of heap memory while translating this plugin's DEX. " +
                "Close other applications or try a smaller plugin build.",
            cause,
        )

    @Suppress("DEPRECATION")
    private fun isFatalFailure(failure: Throwable): Boolean =
        failure is VirtualMachineError || failure is ThreadDeath

    @Synchronized
    fun translate(dexFiles: List<File>, outputJar: File) {
        require(dexFiles.isNotEmpty()) { "The plugin archive contains no DEX files." }
        require(dexFiles.all(File::isFile)) { "One or more extracted DEX files are missing." }

        val workDir = Files.createTempDirectory(outputJar.parentFile.toPath(), "auras-dex-translate-").toFile()
        val stagedJar = File(workDir, "translated.jar")
        val translatedJars = mutableListOf<File>()

        try {
            dexFiles.forEachIndexed { index, dexFile ->
                val dexJar = File(workDir, "classes-${index + 1}.jar")
                translateSingleDex(dexFile, dexJar)
                translatedJars += dexJar
            }

            ZipOutputStream(stagedJar.outputStream().buffered()).use { output ->
                val writtenEntries = mutableSetOf<String>()
                translatedJars.forEach { translatedJar ->
                    ZipFile(translatedJar).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory || entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                            if (!writtenEntries.add(entry.name)) {
                                throw IllegalStateException("Duplicate entry while merging DEX files: ${entry.name}")
                            }
                            output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(output) }
                            output.closeEntry()
                        }
                    }
                }
            }

            Files.move(stagedJar.toPath(), outputJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            outputJar.delete()
            throw failure
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun translateArchive(sourceArchive: File, outputJar: File) {
        require(sourceArchive.isFile) { "The Android plugin archive does not exist." }
        val extractionDir = Files.createTempDirectory(outputJar.parentFile.toPath(), "auras-plugin-dex-").toFile()
        try {
            val dexFiles = ZipFile(sourceArchive).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { isDexEntry(it.name) }
                    .sortedBy { dexEntryOrdinal(it.name) }
                    .toList()
                require(dexEntries.isNotEmpty()) { "The plugin archive contains no classes.dex file." }
                dexEntries.mapIndexed { index, entry ->
                    val dexFile = File(extractionDir, "classes${index + 1}.dex")
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, dexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    dexFile
                }
            }
            translate(dexFiles, outputJar)
        } catch (failure: Throwable) {
            outputJar.delete()
            File(outputJar.path + ".identity").delete()
            if (failure is OutOfMemoryError) throw memoryFailure(failure)
            if (isFatalFailure(failure)) throw failure
            throw failure
        } finally {
            extractionDir.deleteRecursively()
        }
    }

    private fun dexEntryOrdinal(name: String): Int =
        DEX_ENTRY_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun translateSingleDex(dexFile: File, outputJar: File) {
        try {
            val dexFileNode = DexFileNode()
            DexFileReader(Files.readAllBytes(dexFile.toPath())).accept(dexFileNode)
            val usedMethodNames = dexFileNode.clzs.orEmpty().associate { dexClass ->
                val internalName = dexClass.className.removePrefix("L").removeSuffix(";")
                internalName to dexClass.methods.orEmpty().mapTo(mutableSetOf()) { it.method.name }
            }

            ZipOutputStream(outputJar.outputStream().buffered()).use { output ->
                Dex2Asm().convertDex(
                    dexFileNode,
                    ClassVisitorFactory { className ->
                        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
                        val classMethodNames = usedMethodNames[className] ?: mutableSetOf()
                        object : ClassVisitor(Opcodes.ASM9, classWriter) {
                            private var owner = className

                            override fun visit(
                                version: Int,
                                access: Int,
                                name: String,
                                signature: String?,
                                superName: String?,
                                interfaces: Array<out String>?,
                            ) {
                                owner = name
                                super.visit(version, access, name, signature, superName, interfaces)
                            }

                            override fun visitMethod(
                                access: Int,
                                name: String,
                                descriptor: String,
                                signature: String?,
                                exceptions: Array<out String>?,
                            ): MethodVisitor = AutosplitMethodWriter(
                                classWriter,
                                owner,
                                access,
                                name,
                                descriptor,
                                signature,
                                exceptions,
                                classMethodNames,
                            )

                            override fun visitEnd() {
                                super.visitEnd()
                                output.putNextEntry(java.util.zip.ZipEntry("$owner.class"))
                                output.write(classWriter.toByteArray())
                                output.closeEntry()
                            }
                        }
                    },
                )
            }
        } catch (failure: Throwable) {
            outputJar.delete()
            if (isFatalFailure(failure)) throw failure
            val methodTooLarge = generateSequence(failure) { it.cause }
                .filterIsInstance<MethodTooLargeException>()
                .firstOrNull()
            val dexMethod = methodTooLarge?.let { "L${it.className};->${it.methodName}${it.descriptor}" }
            if (dexMethod != null) methodTooLargeFailure(dexMethod, failure)?.let { throw it }
            throw PluginDexConversionException(
                "Could not completely translate ${dexFile.name} for the desktop JVM: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }

        if (!outputJar.isFile || outputJar.length() == 0L) {
            outputJar.delete()
            throw IllegalStateException("DEX translation completed without producing a usable JVM JAR.")
        }
    }
}
