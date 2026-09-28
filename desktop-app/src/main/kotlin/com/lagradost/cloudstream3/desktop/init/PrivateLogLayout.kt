package com.lagradost.cloudstream3.desktop.init

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.LayoutBase
import com.lagradost.common.logging.LogBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap

/** Final privacy boundary shared by file and console appenders, including dependency logs. */
class PrivateLogLayout : LayoutBase<ILoggingEvent>() {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    override fun doLayout(event: ILoggingEvent): String = buildString {
        append(time.format(Instant.ofEpochMilli(event.timeStamp)))
        append(" [").append(LogBuffer.sanitize(event.threadName)).append("] ")
        append(event.level).append(' ').append(LogBuffer.sanitize(event.loggerName))
        append(" - ").appendLine(LogBuffer.sanitize(event.formattedMessage))
        val visited = Collections.newSetFromMap(IdentityHashMap<IThrowableProxy, Boolean>())
        fun appendFailure(failure: IThrowableProxy, prefix: String) {
            if (!visited.add(failure)) return
            append(prefix).append(failure.className).appendLine(": [message redacted]")
            failure.stackTraceElementProxyArray?.forEach { frame ->
                append("\tat ").appendLine(LogBuffer.sanitize(frame.stackTraceElement.toString()))
            }
            failure.suppressed?.forEach { appendFailure(it, "Suppressed: ") }
            failure.cause?.let { appendFailure(it, "Caused by: ") }
        }
        event.throwableProxy?.let { appendFailure(it, "") }
    }
}
