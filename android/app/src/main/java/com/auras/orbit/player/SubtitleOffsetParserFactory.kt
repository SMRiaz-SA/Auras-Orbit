package com.auras.orbit.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import java.util.function.LongSupplier

/** Detects provider subtitle content and shifts cue times without replacing text renderers. */
@UnstableApi
class SubtitleOffsetParserFactory @JvmOverloads constructor(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
    private val offsetMs: LongSupplier,
    private val onUnrecognizedFormat: Consumer<String> = Consumer { },
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean =
        SubtitleFormat.UNKNOWN_MIME_TYPE.equals(format.sampleMimeType, ignoreCase = true)
                || delegate.supportsFormat(format)
                || SubtitleContentSniffer.declaresWebvtt(format.sampleMimeType, format.codecs)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (delegate.supportsFormat(format)) delegate.getCueReplacementBehavior(format)
        else WebvttParser.CUE_REPLACEMENT_BEHAVIOR

    override fun create(format: Format): SubtitleParser = AdaptiveOffsetSubtitleParser(
        delegate,
        format,
        offsetMs,
        onUnrecognizedFormat,
    )

    private class AdaptiveOffsetSubtitleParser(
        private val delegate: SubtitleParser.Factory,
        private val format: Format,
        private val offsetMs: LongSupplier,
        private val onUnrecognizedFormat: Consumer<String>,
    ) : SubtitleParser {
        private var activeParser: SubtitleParser? = null
        private var activeMimeType: String? = null
        private var reportedUnrecognized = false

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            val detectedMimeType = SubtitleContentSniffer.sniffMimeType(data, offset, length)
            val mimeType = detectedMimeType ?: declaredMimeType()
            val parser = mimeType?.let(::parserFor)
            if (parser == null) {
                reportUnrecognizedOnce()
                return
            }

            val delayMs = SubtitleTimelineShift.normalizeDelayMs(offsetMs.asLong)
            val delayUs = delayMs * 1_000L
            val adjustedOutputOptions = adjustOutputOptions(outputOptions, delayUs)
            parser.parse(data, offset, length, adjustedOutputOptions) { cues ->
                val shiftedStartUs = SubtitleTimelineShift.shiftStartUs(cues.startTimeUs, delayMs)
                val shiftedDurationUs = SubtitleTimelineShift.shiftDurationUs(
                    cues.startTimeUs, cues.durationUs, delayMs,
                )
                output.accept(CuesWithTiming(cues.cues, shiftedStartUs, shiftedDurationUs))
            }
        }

        override fun reset() {
            activeParser?.reset()
            activeParser = null
            activeMimeType = null
        }

        override fun getCueReplacementBehavior(): Int = activeParser?.cueReplacementBehavior
            ?: if (delegate.supportsFormat(format)) delegate.getCueReplacementBehavior(format)
            else WebvttParser.CUE_REPLACEMENT_BEHAVIOR

        private fun declaredMimeType(): String? {
            SubtitleFormat.allowedMime(format.sampleMimeType)?.let { return it }
            if (SubtitleContentSniffer.declaresWebvtt(format.sampleMimeType, format.codecs)) {
                return "text/vtt"
            }
            return null
        }

        private fun parserFor(mimeType: String): SubtitleParser? {
            if (activeMimeType == mimeType) return activeParser
            val parser = when (mimeType) {
                "text/vtt" -> WebvttParser()
                "application/x-subrip" -> SubripParser()
                else -> {
                    val detectedFormat = format.buildUpon()
                        .setSampleMimeType(mimeType)
                        .setCodecs(null)
                        .build()
                    if (!delegate.supportsFormat(detectedFormat)) return null
                    delegate.create(detectedFormat)
                }
            }
            activeParser?.reset()
            activeParser = parser
            activeMimeType = mimeType
            return parser
        }

        private fun reportUnrecognizedOnce() {
            if (reportedUnrecognized) return
            reportedUnrecognized = true
            onUnrecognizedFormat.accept(format.id ?: "")
        }
    }

    private companion object {
        fun adjustOutputOptions(
            outputOptions: SubtitleParser.OutputOptions,
            delayUs: Long,
        ): SubtitleParser.OutputOptions {
            if (delayUs == 0L || outputOptions.outputAllCues ||
                outputOptions.startTimeUs == C.TIME_UNSET
            ) {
                return outputOptions
            }

            // Shifted cues are clamped to timeline zero. A non-positive output threshold therefore
            // must not be translated to a positive source threshold: doing so would discard cues
            // that become visible at time zero after the offset is applied.
            val adjustedThresholdUs = if (outputOptions.startTimeUs <= 0L) {
                0L
            } else {
                subtractSaturated(outputOptions.startTimeUs, delayUs).coerceAtLeast(0L)
            }
            return SubtitleParser.OutputOptions.onlyCuesAfter(adjustedThresholdUs)
        }

        fun subtractSaturated(value: Long, amount: Long): Long = when {
            amount > 0L && value < Long.MIN_VALUE + amount -> Long.MIN_VALUE
            amount < 0L && value > Long.MAX_VALUE + amount -> Long.MAX_VALUE
            else -> value - amount
        }
    }
}
