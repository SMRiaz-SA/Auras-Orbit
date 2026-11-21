package com.lagradost.cloudstream3

import android.util.Log
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Date parsing behavior selected from the local CS3 prerelease reference. */
internal object OrbitCs3DateHelpers {
    private const val TAG = "OrbitCs3DateHelpers"

    @OptIn(FormatStringsInDatetimeFormats::class, ExperimentalTime::class)
    @JvmStatic
    fun addDate(episode: Episode, date: String?, format: String) {
        if (date.isNullOrBlank()) return

        episode.date = runCatching {
            // Prefer ISO instant parsing before applying the caller's format.
            runCatching { Instant.parse(date).toEpochMilliseconds() }
                .getOrElse {
                    val formatter = DateTimeComponents.Format { byUnicodePattern(format) }
                    val components = runCatching {
                        formatter.parse(date)
                    }.recoverCatching {
                        formatter.parse(date.trimStart().take(format.length))
                    }.getOrThrow()

                    components.toInstantUsingAvailableZone().toEpochMilliseconds()
                }
        }.onFailure {
            // Date text and exception details can contain provider-controlled data; omit both.
            runCatching { Log.w(TAG, "Unable to parse episode date; input omitted") }
        }.getOrNull()
    }

    /** Uses an explicit offset or named zone; otherwise treats components as device-local time. */
    @OptIn(ExperimentalTime::class)
    private fun DateTimeComponents.toInstantUsingAvailableZone(): Instant {
        if (offsetHours != null) return toInstantUsingOffset()

        val timeZone = timeZoneId?.let(TimeZone::of) ?: TimeZone.currentSystemDefault()
        return runCatching { toLocalDateTime().toInstant(timeZone) }
            .getOrElse { toLocalDate().atStartOfDayIn(timeZone) }
    }
}
