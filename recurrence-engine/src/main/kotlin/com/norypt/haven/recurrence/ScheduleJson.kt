package com.norypt.haven.recurrence

import java.time.ZoneOffset
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Versioned JSON codec for [Schedule]. Output is `{"v":1, ...}`. Decoding rejects unknown versions
 * with [IllegalArgumentException], ignores unknown keys for forward compatibility, and validates the
 * decoded schedule (dates, time, interval, zone id, keys).
 */
public object ScheduleJson {
    public const val VERSION: Int = 1
    private const val VERSION_KEY: String = "v"

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    public fun encode(schedule: Schedule): String {
        val body = json.encodeToJsonElement(Schedule.serializer(), schedule).jsonObject
        val envelope = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(body.size + 1)
        envelope[VERSION_KEY] = JsonPrimitive(VERSION)
        envelope.putAll(body)
        return json.encodeToString(JsonObject.serializer(), JsonObject(envelope))
    }

    /** @throws IllegalArgumentException on malformed JSON, unknown version or invalid schedule content. */
    public fun decode(json: String): Schedule {
        val element = try {
            this.json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Malformed schedule JSON", e)
        }
        val obj = element as? JsonObject ?: throw IllegalArgumentException("Schedule JSON must be an object")
        val version = obj[VERSION_KEY]?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: throw IllegalArgumentException("Schedule JSON has no integer '$VERSION_KEY' field")
        require(version == VERSION) { "Unsupported schedule version $version (supported: $VERSION)" }
        val schedule = try {
            this.json.decodeFromJsonElement(Schedule.serializer(), JsonObject(obj.filterKeys { it != VERSION_KEY }))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid schedule JSON: ${e.message}", e)
        }
        Series(schedule, ZoneOffset.UTC) // validates every field; throws IllegalArgumentException
        return schedule
    }
}
