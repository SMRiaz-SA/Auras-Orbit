package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class OrbitShortLinkResolverBoundedTextTest {
    @Test
    fun zeroBulkReadsFallBackToSingleByteReads() {
        assertArrayEquals(
            byteArrayOf(10, 20, 30),
            readBoundedTextBytes(ZeroBulkInputStream(byteArrayOf(10, 20, 30)), 3L),
        )
    }

    @Test
    fun zeroBulkReadsAtEndPreserveEof() {
        assertArrayEquals(byteArrayOf(), readBoundedTextBytes(ZeroBulkInputStream(byteArrayOf()), 3L))
    }

    @Test
    fun fallbackBytesCountTowardTheSizeLimit() {
        assertTooLarge {
            readBoundedTextBytes(ZeroBulkInputStream(byteArrayOf(10, 20)), 1L)
        }
    }

    @Test
    fun repeatedSingleByteZerosStillStopAtTheSizeLimit() {
        val endlessZeroBytes = object : InputStream() {
            private var bulkReadAttempts = 0

            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (++bulkReadAttempts > 8) {
                    throw AssertionError("bounded text reader did not fall back to single-byte reads")
                }
                return 0
            }
        }
        assertTooLarge { readBoundedTextBytes(endlessZeroBytes, 3L) }
    }

    private fun assertTooLarge(action: () -> Unit) {
        val error = assertThrows(Exception::class.java) { action() }
        assertEquals("ResolverBodyTooLarge", error.javaClass.simpleName)
    }

    private class ZeroBulkInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        private var bulkReadAttempts = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (++bulkReadAttempts > 8) {
                throw AssertionError("bounded text reader did not fall back to single-byte reads")
            }
            return 0 // Simulate a provider that violates the positive-length progress contract.
        }
    }
}
