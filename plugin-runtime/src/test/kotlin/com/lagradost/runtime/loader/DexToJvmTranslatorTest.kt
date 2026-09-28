package com.lagradost.runtime.loader

import org.junit.jupiter.api.Test
import org.objectweb.asm.MethodTooLargeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DexToJvmTranslatorTest {
    @Test
    fun identifiesJvmMethodSizeFailureAndKeepsTheDexMethodName() {
        val method = "Lcom/phisher98/StreamPlayExtractor;->invokeMovieBox()Ljava/lang/Object;"
        val conversionFailure = IllegalStateException(
            "DEX translation failed",
            MethodTooLargeException("com/phisher98/StreamPlayExtractor", "invokeMovieBox", "()V", 65_536),
        )

        val compatibilityFailure = DexToJvmTranslator.methodTooLargeFailure(method, conversionFailure)

        assertNotNull(compatibilityFailure)
        assertEquals(method, compatibilityFailure.dexMethod)
        assertSame(conversionFailure, compatibilityFailure.cause)
        assertTrue(compatibilityFailure.message.orEmpty().contains("65,535-byte limit"))
        assertTrue(compatibilityFailure.message.orEmpty().contains("Android Cloudstream runtime"))
    }

    @Test
    fun leavesOtherTranslationFailuresAsOrdinaryConversionErrors() {
        val failure = IllegalStateException("invalid instruction")

        assertNull(DexToJvmTranslator.methodTooLargeFailure("Lfixture/Plugin;->load()V", failure))
    }

    @Test
    fun recognizesMultidexFilesAboveClassesNine() {
        assertTrue(DexToJvmTranslator.isDexEntry("classes.dex"))
        assertTrue(DexToJvmTranslator.isDexEntry("classes2.dex"))
        assertTrue(DexToJvmTranslator.isDexEntry("classes10.dex"))
        assertTrue(DexToJvmTranslator.isDexEntry("classes19.dex"))
        assertTrue(DexToJvmTranslator.isDexEntry("classes20.dex"))
        assertFalse(DexToJvmTranslator.isDexEntry("classes1.dex"))
        assertFalse(DexToJvmTranslator.isDexEntry("backup/classes.dex"))
    }

    @Test
    fun classifiesDexConversionHeapExhaustionForThePluginFailurePath() {
        val failure = OutOfMemoryError("Java heap space")

        val conversionFailure = DexToJvmTranslator.memoryFailure(failure)

        assertSame(failure, conversionFailure.cause)
        assertTrue(conversionFailure.message.orEmpty().contains("ran out of heap memory"))
    }
}
