package icu.minq.memoh.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Keeps the original JSON so future/unknown message blocks remain inspectable. */
object MessageBlockSerializer : KSerializer<MessageBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MessageBlock")

    override fun deserialize(decoder: Decoder): MessageBlock {
        val jsonDecoder = decoder as? JsonDecoder ?: error("MessageBlock is JSON-only")
        val raw = jsonDecoder.decodeJsonElement().jsonObject
        return MessageBlock(
            id = raw.int("id"),
            type = raw.string("type"),
            content = raw.string("content"),
            name = raw.string("name"),
            input = raw["input"],
            output = raw["output"],
            toolCallId = raw.string("tool_call_id"),
            progress = raw["progress"]?.jsonArray?.toList().orEmpty(),
            running = raw["running"]?.jsonPrimitive?.booleanOrNull == true,
            approval = raw["approval"]?.let { runCatching { jsonDecoder.json.decodeFromJsonElement(Approval.serializer(), it) }.getOrNull() },
            userInput = raw["user_input"]?.let { runCatching { jsonDecoder.json.decodeFromJsonElement(UserInput.serializer(), it) }.getOrNull() },
            raw = raw,
        )
    }

    override fun serialize(encoder: Encoder, value: MessageBlock) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("MessageBlock is JSON-only")
        val values = value.raw.toMutableMap().apply {
            put("id", JsonPrimitive(value.id)); put("type", JsonPrimitive(value.type))
            if (value.content.isNotEmpty()) put("content", JsonPrimitive(value.content))
            if (value.name.isNotEmpty()) put("name", JsonPrimitive(value.name))
            value.input?.let { put("input", it) }; value.output?.let { put("output", it) }
            if (value.toolCallId.isNotEmpty()) put("tool_call_id", JsonPrimitive(value.toolCallId))
            if (value.progress.isNotEmpty()) put("progress", JsonArray(value.progress))
            if (value.running) put("running", JsonPrimitive(true))
            value.approval?.let { put("approval", jsonEncoder.json.encodeToJsonElement(Approval.serializer(), it)) }
            value.userInput?.let { put("user_input", jsonEncoder.json.encodeToJsonElement(UserInput.serializer(), it)) }
        }
        jsonEncoder.encodeJsonElement(JsonObject(values))
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
}
