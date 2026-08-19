package `is`.xyz.mpv

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer

/**
 * Represents an MPV node value (MPV_FORMAT_NODE), used for complex property types
 * like track-list and chapter-list.
 *
 * Converted to/from JSON for kotlinx.serialization interop.
 */
@kotlinx.serialization.Serializable
data class MPVNode(
    val format: Int = 0,
    val string: String? = null,
    @kotlinx.serialization.Transient
    val int: Long? = null,
    @kotlinx.serialization.Transient
    val double: Double? = null,
    @kotlinx.serialization.Transient
    val bool: Boolean? = null,
    @kotlinx.serialization.Transient
    val nodeArray: List<MPVNode?>? = null,
    @kotlinx.serialization.Transient
    val nodeMap: Map<String, MPVNode?>? = null
)

/**
 * Convert this MPVNode to a JSON string, then deserialize as T using kotlinx.serialization.
 */
inline fun <reified T> MPVNode?.toObject(json: Json): T {
    if (this == null) {
        return json.decodeFromString("null")
    }
    val jsonString = json.encodeToString(serializer<MPVNode>(), this)
    return json.decodeFromString(jsonString)
}

/** Convert a JsonElement to MPVNode */
fun JsonElement.toMPVNode(): MPVNode = when (this) {
    is JsonPrimitive -> {
        when {
            isString -> MPVNode(format = 1, string = contentOrNull)
            contentOrNull.toBooleanStrictOrNull() != null ->
                MPVNode(format = 3, bool = booleanOrNull)
            contentOrNull.toLongOrNull() != null ->
                MPVNode(format = 4, int = longOrNull)
            else -> MPVNode(format = 5, double = doubleOrNull)
        }
    }
    else -> this
}

/** Convert MPVNode to kotlinx.serialization JsonElement for encoding */
fun MPVNode.toJsonElement(): JsonElement {
    return when (format) {
        1 -> JsonPrimitive(string ?: "")
        3 -> JsonPrimitive(bool ?: false)
        4 -> JsonPrimitive(int ?: 0L)
        5 -> JsonPrimitive(double ?: 0.0)
        7 -> JsonPrimitive(nodeArray?.map { it?.toJsonElement() }
            ?: emptyList<JsonElement>())
        8 -> JsonPrimitive(nodeMap?.entries?.associate { it.key to it.value?.toJsonElement() }
            ?: emptyMap<String, JsonElement>())
        else -> JsonPrimitive("")
    }
}

/**
 * Serialize this MPVNode to a JSON string.
 * Used by qznet PlayerUtils.kt for deserializing MPV node data.
 */
fun MPVNode.toJson(): String {
    val sb = StringBuilder()
    fun serialize(node: MPVNode): String {
        return when (node.format) {
            1 -> "\"" + (node.string ?: "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
            3, 4 -> (node.int ?: 0L).toString()
            5 -> (node.double ?: 0.0).toString()
            6 -> (node.bool ?: false).toString()
            7 -> "[" + (node.nodeArray?.mapNotNull { n -> if (n != null) serialize(n) else null }?.joinToString(",") ?: "") + "]"
            8 -> "{" + (node.nodeMap?.entries?.joinToString(",") { kvp -> "\"" + kvp.key + "\":" + if (kvp.value != null) serialize(kvp.value!!) else "null" } ?: "") + "}"
            else -> "null"
        }
    }
    return serialize(this)
}
