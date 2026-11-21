@file:JvmName("ResultFragmentKt")
package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

enum class VideoWatchState { None, Watched }

@Serializable
data class ResultEpisode(
    @SerialName("headerName") val headerName: String,
    @SerialName("name") val name: String?,
    @SerialName("poster") val poster: String?,
    @SerialName("episode") val episode: Int,
    @SerialName("seasonIndex") val seasonIndex: Int?,
    @SerialName("season") val season: Int?,
    @SerialName("data") val data: String,
    @SerialName("apiName") val apiName: String,
    @SerialName("id") val id: Int,
    @SerialName("index") val index: Int,
    @SerialName("position") val position: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("score") @Serializable(with = ResultScoreSerializer::class) val score: Score?,
    @SerialName("description") val description: String?,
    @SerialName("isFiller") val isFiller: Boolean?,
    @SerialName("tvType") @Serializable(with = ResultTvTypeSerializer::class) val tvType: TvType,
    @SerialName("parentId") val parentId: Int,
    @SerialName("videoWatchState") val videoWatchState: VideoWatchState,
    @SerialName("totalEpisodeIndex") val totalEpisodeIndex: Int? = null,
    @SerialName("airDate") val airDate: Long? = null,
    @SerialName("runTime") val runTime: Int? = null,
    @SerialName("seasonData") @Serializable(with = ResultSeasonSerializer::class) val seasonData: SeasonData? = null,
)

fun ResultEpisode.getRealPosition(): Long {
    if (duration <= 0) return 0
    val percentage = position.toDouble() * 100 / duration
    return if (percentage <= 5 || percentage >= 95) 0 else position
}
fun ResultEpisode.getDisplayPosition(): Long {
    if (duration <= 0) return 0
    val percentage = position.toDouble() * 100 / duration
    return when { percentage <= 1 -> 0; percentage <= 5 -> 5 * duration / 100; percentage >= 95 -> duration; else -> position }
}

@Serializable @SerialName("com.lagradost.cloudstream3.Score") private data class ScoreValue(val data: Int)
object ResultScoreSerializer : KSerializer<Score?> {
    private val codec = ScoreValue.serializer().nullable
    override val descriptor = codec.descriptor
    override fun serialize(encoder: Encoder, value: Score?) = encoder.encodeSerializableValue(codec,
        value?.let { ScoreValue(it.toInt(Score.MAX)) })
    override fun deserialize(decoder: Decoder): Score? = decoder.decodeSerializableValue(codec)?.let { Score(it.data, null) }
}
@Serializable @SerialName("com.lagradost.cloudstream3.SeasonData") private data class SeasonValue(val season: Int, val name: String? = null, val displaySeason: Int? = null)
object ResultSeasonSerializer : KSerializer<SeasonData?> {
    private val codec = SeasonValue.serializer().nullable
    override val descriptor = codec.descriptor
    override fun serialize(encoder: Encoder, value: SeasonData?) = encoder.encodeSerializableValue(codec,
        value?.let { SeasonValue(it.season, it.name, it.displaySeason) })
    override fun deserialize(decoder: Decoder): SeasonData? = decoder.decodeSerializableValue(codec)?.let { SeasonData(it.season, it.name, it.displaySeason) }
}
object ResultTvTypeSerializer : KSerializer<TvType> {
    private val values: Map<String, TvType> = TvType::class.java.fields.filter { it.type == TvType::class.java }
        .associate { it.name to (it.get(null) as TvType) }
    override val descriptor = PrimitiveSerialDescriptor("com.lagradost.cloudstream3.TvType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TvType) = encoder.encodeString(
        values.entries.firstOrNull { it.value === value }?.key ?: throw SerializationException("Unknown TvType"))
    override fun deserialize(decoder: Decoder): TvType = values[decoder.decodeString()]
        ?: throw SerializationException("Unsupported TvType")
}
