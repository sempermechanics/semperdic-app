@file:OptIn(ExperimentalSerializationApi::class)

package com.indicvision.semper.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a JSON value the way Android's `org.json` `opt…` getters do, so a
 * kotlinx model can replace hand-written `optInt` / `optString` parsing
 * without changing what an old or odd file reads as.
 *
 * The rules (libcore `org.json.JSON` and `JSONTokener.readLiteral`): a bare
 * literal is an Int when it fits, else a Long, else a Double (octal after a
 * leading 0, hex after 0x); `optInt` truncates a number and parses a numeric
 * string; `optBoolean` takes `true`/`false` in any case, as a literal or a
 * string; `optString` turns any non-string into its text, JSON `null` into
 * `"null"`; and a value of the wrong kind reads as absent, so the caller's
 * default applies. Object and array text from `optString` is kotlinx's
 * compact form, which can differ from org.json's in number spelling.
 */
internal object OrgJson {

    /** JSON `null` as org.json holds it (`JSONObject.NULL`, which prints as "null"). */
    private object Null {
        override fun toString(): String = "null"
    }

    /** What org.json's tokenizer makes of [element]: String, Int, Long, Double, Boolean, [Null], or the element. */
    fun valueOf(element: JsonElement): Any = when (element) {
        JsonNull -> Null
        is JsonPrimitive -> if (element.isString) element.content else literal(element.content)
        else -> element
    }

    private fun literal(text: String): Any = when {
        text.equals("null", ignoreCase = true) -> Null
        text.equals("true", ignoreCase = true) -> true
        text.equals("false", ignoreCase = true) -> false
        else -> (if ('.' in text) null else integral(text)) ?: parseDouble(text) ?: text
    }

    private fun integral(text: String): Any? {
        val (digits, radix) = when {
            text.startsWith("0x") || text.startsWith("0X") -> text.substring(2) to HEX
            text.startsWith("0") && text.length > 1 -> text.substring(1) to OCTAL
            else -> text to DECIMAL
        }
        val value = try {
            java.lang.Long.parseLong(digits, radix)
        } catch (_: NumberFormatException) {
            return null
        }
        return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
    }

    private fun parseDouble(text: String): Double? = try {
        java.lang.Double.valueOf(text)
    } catch (_: NumberFormatException) {
        null
    }

    /** `JSON.toInteger`. */
    fun toInt(value: Any): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        is String -> parseDouble(value)?.toInt()
        else -> null
    }

    /** `JSON.toDouble`. */
    fun toDouble(value: Any): Double? = when (value) {
        is Double -> value
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is String -> parseDouble(value)
        else -> null
    }

    /** `JSON.toBoolean`. */
    fun toBoolean(value: Any): Boolean? = when (value) {
        is Boolean -> value
        is String -> when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> null
        }
        else -> null
    }

    /** `JSON.toString`: never null for a present value. */
    fun toText(value: Any): String = value.toString()

    private const val DECIMAL = 10
    private const val OCTAL = 8
    private const val HEX = 16
}

/** Base of the scalar serializers: decode through [OrgJson], encode plainly. */
internal abstract class OrgJsonScalarSerializer<T : Any>(name: String, kind: PrimitiveKind) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OrgJson.$name", kind).nullable

    protected abstract fun coerce(value: Any): T?

    protected abstract fun encodeValue(encoder: Encoder, value: T)

    override fun deserialize(decoder: Decoder): T? =
        coerce(OrgJson.valueOf((decoder as JsonDecoder).decodeJsonElement()))

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encodeValue(encoder, value)
    }
}

/** `optInt(key, d)`: null (so `?: d`) when absent or not a number. */
internal object OptIntSerializer : OrgJsonScalarSerializer<Int>("Int", PrimitiveKind.INT) {
    override fun coerce(value: Any): Int? = OrgJson.toInt(value)

    override fun encodeValue(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** `optDouble(key, d)`. */
internal object OptDoubleSerializer : OrgJsonScalarSerializer<Double>("Double", PrimitiveKind.DOUBLE) {
    override fun coerce(value: Any): Double? = OrgJson.toDouble(value)

    override fun encodeValue(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

/** `optBoolean(key, d)`. */
internal object OptBooleanSerializer : OrgJsonScalarSerializer<Boolean>("Boolean", PrimitiveKind.BOOLEAN) {
    override fun coerce(value: Any): Boolean? = OrgJson.toBoolean(value)

    override fun encodeValue(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/** `optString(key, d)`: JSON `null` reads as "null", a number as its text. */
internal object OptStringSerializer : OrgJsonScalarSerializer<String>("String", PrimitiveKind.STRING) {
    override fun coerce(value: Any): String = OrgJson.toText(value)

    override fun encodeValue(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Base of the array serializers: anything but an array reads as absent (`optJSONArray`). */
internal abstract class OptArraySerializer<E>(protected val itemSerializer: KSerializer<E>) : KSerializer<List<E>?> {
    private val list = ListSerializer(itemSerializer)

    override val descriptor: SerialDescriptor = list.descriptor.nullable

    /** One element as read by the array's `opt…(index)` (or `get…(index)`). */
    protected abstract fun element(json: JsonDecoder, index: Int, value: JsonElement): E

    override fun deserialize(decoder: Decoder): List<E>? {
        val json = decoder as JsonDecoder
        val array = json.decodeJsonElement() as? JsonArray ?: return null
        return array.mapIndexed { i, value -> element(json, i, value) }
    }

    override fun serialize(encoder: Encoder, value: List<E>?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(list, value)
    }
}

/** `optJSONArray(key)` read with `optInt(i)`: an element that is not a number is 0. */
internal object OptIntListSerializer : OptArraySerializer<Int>(Int.serializer()) {
    override fun element(json: JsonDecoder, index: Int, value: JsonElement): Int =
        OrgJson.toInt(OrgJson.valueOf(value)) ?: 0
}

/** `optJSONArray(key)` read with `optString(i)`. */
internal object OptStringListSerializer : OptArraySerializer<String>(String.serializer()) {
    override fun element(json: JsonDecoder, index: Int, value: JsonElement): String =
        OrgJson.toText(OrgJson.valueOf(value))
}

/** `optJSONArray(key)` read with `optDouble(i, 0.0).toFloat()`, written as floats. */
internal object OptFloatListSerializer : OptArraySerializer<Float>(Float.serializer()) {
    override fun element(json: JsonDecoder, index: Int, value: JsonElement): Float =
        (OrgJson.toDouble(OrgJson.valueOf(value)) ?: 0.0).toFloat()
}

/** `optJSONObject(key)`: anything but an object reads as absent. */
internal abstract class OptObjectSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement() as? JsonObject ?: return null
        return json.json.decodeFromJsonElement(inner, element)
    }

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, value)
    }
}

/**
 * `optJSONArray(key)` read with `getJSONObject(i)`: an element that is not an
 * object fails the whole read, as `getJSONObject` throws.
 */
internal abstract class StrictObjectListSerializer<T : Any>(inner: KSerializer<T>) : OptArraySerializer<T>(inner) {
    override fun element(json: JsonDecoder, index: Int, value: JsonElement): T {
        val obj = value as? JsonObject ?: throw SerializationException("element $index is not an object")
        return json.json.decodeFromJsonElement(itemSerializer, obj)
    }
}

/**
 * `optJSONArray(key)` read with `optJSONObject(i) ?: continue`: an element that
 * is not an object is kept as null, so the array's length (which the caller
 * may test before skipping) is the stored one. Takes the element's nullable
 * serializer.
 */
internal abstract class LenientObjectListSerializer<T : Any>(inner: KSerializer<T?>) : OptArraySerializer<T?>(inner) {
    override fun element(json: JsonDecoder, index: Int, value: JsonElement): T? =
        (value as? JsonObject)?.let { json.json.decodeFromJsonElement(itemSerializer, it) }
}
