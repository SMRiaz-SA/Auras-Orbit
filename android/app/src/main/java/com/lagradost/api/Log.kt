package com.lagradost.api

import android.util.Log as AndroidLog

/** Orbit host ABI shim for the stable CloudStream logging object. */
object Log {
    fun d(tag: String, message: String) {
        AndroidLog.d(tag, message)
    }

    fun i(tag: String, message: String) {
        AndroidLog.i(tag, message)
    }

    fun w(tag: String, message: String) {
        AndroidLog.w(tag, message)
    }

    fun e(tag: String, message: String) {
        AndroidLog.e(tag, message)
    }
}
