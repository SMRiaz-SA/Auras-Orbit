package com.lagradost.cloudstream3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.SkipSerializationTest
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import dalvik.system.DexFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.util.Date
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class SerializationClassTester {
    // Same as app, or using app reference
    val jacksonMapper = mapper
    val kotlinxMapper = json

    @Test
    fun isIdenticalSerialization() {
        val serializableClasses = findSerializableClasses("com.lagradost")
        println("Number of serializable classes: ${serializableClasses.size}")

        val failures = mutableListOf<String>()

        serializableClasses.forEach { kClass ->
            runCatching {
                val instance = createSampleInstance(kClass)

                val jacksonJson = jacksonMapper.writeValueAsString(instance)
                val kotlinxJson = serializeWithKotlinx(kClass, instance)

                assertEquals(
                    jacksonJson,
                    kotlinxJson,
                    """
                    Serialization mismatch for:
                    ${kClass.qualifiedName}

                    Jackson:
                    $jacksonJson

                    Kotlinx:
                    $kotlinxJson
                    
                    """.trimIndent()
                )
                println("Identical serialization for: ${kClass.jvmName}")
            }.onFailure { e ->
                failures.add("FAILED ${kClass.qualifiedName}: ${e.message}")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} class(es) failed:\n${failures.joinToString("\n")}")
        }
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    @Test
    fun isIdenticalDeserialization() {
        val serializableClasses = findSerializableClasses("com.lagradost")
        println("Number of serializable classes: ${serializableClasses.size}")

        val failures = mutableListOf<String>()

        serializableClasses.forEach { kClass ->
            runCatching {
                val instance = createSampleInstance(kClass)
                // Convert to JSON to get example JSON object
                // We prefer jackson here because the app may have many jackson JSON strings in local storage
                val originalJson = jacksonMapper.writeValueAsString(instance)

                // Create an object from the JSON using kotlinx
                val serializer =
                    kClass.serializerOrNull() ?: kotlinxMapper.serializersModule.getContextual(kClass)
                assertNotNull(serializer, "The class: ${kClass.jvmName} must be serializable!")
                val kotlinxDecoded = kotlinxMapper.decodeFromString(serializer, originalJson)

                // Create an object from the JSON using jackson
                val mapperDecoded = jacksonMapper.readValue(originalJson, kClass.java)

                // Deep inspect both object using the mapper toJson function.
                // This deep equality check can be performed using other methods, but this just works.
                val jacksonJson = mapperDecoded.toJson()
                val kotlinxJson = kotlinxDecoded.toJson()

                assertEquals(
                    jacksonJson,
                    kotlinxJson,
                    """
                    Serialization mismatch for:
                    ${kClass.qualifiedName}

                    Jackson:
                    $jacksonJson

                    Kotlinx:
                    $kotlinxJson
                    
                    """.trimIndent()
                )
                println("Identical deserialization for: ${kClass.jvmName}")
            }.onFailure { e ->
                failures.add("FAILED ${kClass.qualifiedName}: ${e.message}")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} class(es) failed:\n${failures.joinToString("\n")}")
        }
    }

    // DEX files are the best solution to read all our classes dynamically.
    // classgraph could be used instead, but it only gives results on the JVM, not Android.
    @Suppress("DEPRECATION")
    private fun findSerializableClasses(packageName: String): List<KClass<*>> {
        val context = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        val dexFile = DexFile(context.packageCodePath)
        return dexFile.entries()
            .toList()
            .filter { it.startsWith(packageName) }
            .mapNotNull {
                runCatching { Class.forName(it).kotlin }.getOrNull()
            }.filter { kClass ->
                // Not possible to use .hasAnnotation() on newer Android versions.
                kClass.java.annotations.any { it is Serializable }
                    && kClass.java.annotations.none { it is SkipSerializationTest }
                    && !kClass.isAbstract
            }
    }

    private fun createSampleInstance(kClass: KClass<*>): Any =
        sampleValue(kClass.java, 0)
            ?: error("Could not create an Android-compatible sample for ${kClass.qualifiedName}")

    private fun sampleValue(type: Type, depth: Int): Any? {
        val rawType = when (type) {
            is Class<*> -> type
            is ParameterizedType -> type.rawType as? Class<*> ?: return null
            is GenericArrayType -> return arrayOf(sampleValue(type.genericComponentType, depth + 1))
            else -> return null
        }

        when (rawType) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> return true
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> return 1.toByte()
            java.lang.Short.TYPE, java.lang.Short::class.java -> return 1.toShort()
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> return 1
            java.lang.Long.TYPE, java.lang.Long::class.java -> return 1L
            java.lang.Float.TYPE, java.lang.Float::class.java -> return 1.0f
            java.lang.Double.TYPE, java.lang.Double::class.java -> return 1.0
            java.lang.Character.TYPE, java.lang.Character::class.java -> return 'x'
            String::class.java -> return "audit"
            BigInteger::class.java -> return BigInteger.ONE
            BigDecimal::class.java -> return BigDecimal.ONE
            UUID::class.java -> return UUID(0L, 1L)
            URI::class.java -> return URI("https://example.com/audit")
            URL::class.java -> return URL("https://example.com/audit")
            File::class.java -> return File("audit")
            Date::class.java -> return Date(1L)
        }

        if (rawType.isEnum) return rawType.enumConstants?.firstOrNull()
        if (rawType.isArray) {
            val value = sampleValue(rawType.componentType, depth + 1)
            return ReflectArray.newInstance(rawType.componentType, if (value == null) 0 else 1).also {
                if (value != null) ReflectArray.set(it, 0, value)
            }
        }
        if (rawType == Pair::class.java) {
            val arguments = (type as? ParameterizedType)?.actualTypeArguments ?: return null
            val first = arguments.getOrNull(0)?.let { sampleValue(it, depth + 1) } ?: return null
            val second = arguments.getOrNull(1)?.let { sampleValue(it, depth + 1) } ?: return null
            return Pair(first, second)
        }
        if (rawType == Triple::class.java) {
            val arguments = (type as? ParameterizedType)?.actualTypeArguments ?: return null
            val first = arguments.getOrNull(0)?.let { sampleValue(it, depth + 1) } ?: return null
            val second = arguments.getOrNull(1)?.let { sampleValue(it, depth + 1) } ?: return null
            val third = arguments.getOrNull(2)?.let { sampleValue(it, depth + 1) } ?: return null
            return Triple(first, second, third)
        }
        if (Collection::class.java.isAssignableFrom(rawType)) {
            val elementType = (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
            val element = elementType?.let { sampleValue(it, depth + 1) }
            return when {
                Set::class.java.isAssignableFrom(rawType) -> LinkedHashSet<Any>().apply {
                    element?.let(::add)
                }
                else -> ArrayList<Any>().apply { element?.let(::add) }
            }
        }
        if (Map::class.java.isAssignableFrom(rawType)) {
            val arguments = (type as? ParameterizedType)?.actualTypeArguments
            val key = arguments?.getOrNull(0)?.let { sampleValue(it, depth + 1) }
            val value = arguments?.getOrNull(1)?.let { sampleValue(it, depth + 1) }
            return LinkedHashMap<Any, Any>().apply {
                if (key != null && value != null) put(key, value)
            }
        }
        if (rawType == Any::class.java || rawType == Object::class.java) return Any()
        if (depth >= 8 || rawType.isInterface || Modifier.isAbstract(rawType.modifiers)) return null

        runCatching { rawType.kotlin.objectInstance }.getOrNull()?.let { return it }

        val constructors = rawType.declaredConstructors
            .filterNot { it.isSynthetic }
            .sortedByDescending { it.parameterTypes.size }
        for (constructor in constructors) {
            val arguments = constructor.genericParameterTypes.map { parameterType ->
                sampleValue(parameterType, depth + 1) ?: return@map null
            }
            try {
                constructor.isAccessible = true
                return constructor.newInstance(*arguments.toTypedArray())
            } catch (_: Throwable) {
                // Try a simpler constructor, including Kotlin's generated no-arg default constructor.
            }
        }
        return null
    }

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun serializeWithKotlinx(
        kClass: KClass<*>,
        value: Any
    ): String {
        val serializer = kClass.serializer() as KSerializer<Any>
        return kotlinxMapper.encodeToString(serializer, value)
    }
}
