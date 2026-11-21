package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass

/**
 * Response parser configured on Orbit's shared CS3 NiceHttp client.
 *
 * This follows the pinned CloudStream selection order: Kotlin serialization first,
 * Jackson fallback second, and safe parsing returns null for ordinary parse errors.
 */
internal object OrbitCs3ResponseParser : ResponseParser {
    private val json = MainAPIKt.getJson()
    private val mapper = MainAPIKt.getMapper()

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        val serializer = kClass.serializerOrNull() ?: json.serializersModule.getContextual(kClass)
        if (serializer != null) {
            try {
                return json.decodeFromString(serializer, text)
            } catch (_: SerializationException) {
                // Pinned CloudStream falls back to Jackson after a kotlinx serialization error.
            }
        }
        return mapper.readValue(text, kClass.java)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
        try {
            parse(text, kClass)
        } catch (_: Exception) {
            null
        }

    override fun writeValueAsString(obj: Any): String = obj.toJson()
}
