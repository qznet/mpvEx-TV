package `is`.xyz.mpv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Represents an MPV node value (MPV_FORMAT_NODE), used for complex property types
 * like track-list and chapter-list.
 *
 * qznet's PlayerUtils.kt defines `MPVNode.toObject(json)` which calls [toJson] and
 * then `json.decodeFromString<T>(...)`. So [toJson] must reproduce mpv's native JSON
 * shape (a JSON array for node-array, a JSON object for node-map) so that kotlinx
 * can deserialize it directly into the target data class (e.g. TrackNode).
 */
data class MPVNode(
    val format: Int = 0,
    val string: String? = null,
    val int: Long? = null,
    val double: Double? = null,
    val bool: Boolean? = null,
    val nodeArray: List<MPVNode?>? = null,
    val nodeMap: Map<String, MPVNode?>? = null
) {
    /** Serialize this node back to mpv's native JSON representation. */
    fun toJson(): String {
        fun ser(n: MPVNode): String = when (n.format) {
            1 -> "\"" + (n.string ?: "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
            3 -> if (n.bool == true) "true" else "false"
            4 -> (n.int ?: 0L).toString()
            5 -> (n.double ?: 0.0).toString()
            7 -> "[" + (n.nodeArray?.mapNotNull { it?.let(ser) }?.joinToString(",") ?: "") + "]"
            8 -> "{" + (n.nodeMap?.entries?.joinToString(",") { "\"${it.key}\":" + (it.value?.let(ser) ?: "null") } ?: "") + "}"
            else -> "null"
        }
        return ser(this)
    }

    companion object {
        /** Parse mpv's node JSON string into an MPVNode. Returns null on failure. */
        fun fromJsonString(s: String): MPVNode? = runCatching {
            fromJsonElement(Json.parseToJsonElement(s))
        }.getOrNull()

        fun fromJsonElement(elem: JsonElement): MPVNode = when (elem) {
            is JsonNull -> MPVNode(format = 0)
            is JsonPrimitive -> when {
                elem.isString -> MPVNode(format = 1, string = elem.content)
                elem.content.equals("true", true) || elem.content.equals("false", true) ->
                    MPVNode(format = 3, bool = elem.content.equals("true", true))
                elem.content.toLongOrNull() != null ->
                    MPVNode(format = 4, int = elem.content.toLong())
                elem.content.toDoubleOrNull() != null ->
                    MPVNode(format = 5, double = elem.content.toDouble())
                else -> MPVNode(format = 0)
            }
            is JsonArray ->
                MPVNode(format = 7, nodeArray = elem.map { if (it is JsonNull) null else fromJsonElement(it) })
            is JsonObject ->
                MPVNode(format = 8, nodeMap = elem.mapValues { if (it.value is JsonNull) null else fromJsonElement(it.value) })
        }
    }
}
