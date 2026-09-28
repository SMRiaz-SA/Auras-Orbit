package com.lagradost.common.net

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedInputTest {
    @Test
    fun unknownLengthStreamStopsAfterOneExcessByte() {
        var consumed = 0
        val endless = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 65
            }
        }
        assertFailsWith<IOException> { endless.readBoundedBytes(1024) }
        assertEquals(1025, consumed)
        val exact = ByteArray(1024) { 42 }
        assertContentEquals(exact, ByteArrayInputStream(exact).readBoundedBytes(1024))
    }

    @Test
    fun zeroBudgetStillAllowsAnEmptyStreamAndRejectsOneByte() {
        assertContentEquals(ByteArray(0), ByteArrayInputStream(ByteArray(0)).readBoundedBytes(0))
        assertFailsWith<IOException> { ByteArrayInputStream(byteArrayOf(1)).readBoundedBytes(0) }
    }
}
