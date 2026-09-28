package com.lagradost.common.logging

import org.slf4j.LoggerFactory

/** Redacts before writing to any application logging sink. */
object AppLogger {
    private val logger = LoggerFactory.getLogger("AurasOrbit")

    private fun emit(level: LogLevel, tag: String, message: String, t: Throwable?) {
        if (t is kotlinx.coroutines.CancellationException) return
        val safeTag = LogBuffer.sanitize(tag)
        val safeMessage = LogBuffer.sanitize(message)
        val safeThrowable = t?.let(LogBuffer::sanitizeThrowable)
        val text = "[$safeTag] $safeMessage"
        when (level) {
            LogLevel.VERBOSE -> logger.trace(text, safeThrowable)
            LogLevel.DEBUG -> logger.debug(text, safeThrowable)
            LogLevel.INFO -> logger.info(text, safeThrowable)
            LogLevel.WARN -> logger.warn(text, safeThrowable)
            LogLevel.ERROR -> logger.error(text, safeThrowable)
        }
        LogBuffer.record(level, safeTag, safeMessage, t)
    }

    @JvmOverloads
    fun v(tag: String, message: String, t: Throwable? = null) = emit(LogLevel.VERBOSE, tag, message, t)

    @JvmOverloads
    fun v(message: String, t: Throwable? = null) = emit(LogLevel.VERBOSE, "General", message, t)

    @JvmOverloads
    fun d(tag: String, message: String, t: Throwable? = null) = emit(LogLevel.DEBUG, tag, message, t)

    @JvmOverloads
    fun d(message: String, t: Throwable? = null) = emit(LogLevel.DEBUG, "General", message, t)

    @JvmOverloads
    fun i(tag: String, message: String, t: Throwable? = null) = emit(LogLevel.INFO, tag, message, t)

    @JvmOverloads
    fun i(message: String, t: Throwable? = null) = emit(LogLevel.INFO, "General", message, t)

    @JvmOverloads
    fun w(tag: String, message: String, t: Throwable? = null) = emit(LogLevel.WARN, tag, message, t)

    @JvmOverloads
    fun w(message: String, t: Throwable? = null) = emit(LogLevel.WARN, "General", message, t)

    @JvmOverloads
    fun e(tag: String, message: String, t: Throwable? = null) = emit(LogLevel.ERROR, tag, message, t)

    @JvmOverloads
    fun e(message: String, t: Throwable? = null) = emit(LogLevel.ERROR, "General", message, t)
}
