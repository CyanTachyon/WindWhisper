package moe.tachyon.windwhisper.ai.internal.llm

import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.contentNegotiationJson
import moe.tachyon.windwhisper.utils.toJsonElement

internal fun buildResponsesRequest(
    model: AiConfig.LlmModel,
    input: List<JsonObject>,
    instructions: String,
    previousResponseId: String?,
    tools: List<AiToolInfo<*>>,
    stream: Boolean,
): JsonObject
{
    val custom = model.customRequestParms?.toJsonElement()?.jsonObject.orEmpty()
    val owned = setOf("model", "input", "instructions", "stream", "store", "previous_response_id", "conversation", "context_management", "tools", "background")
    require(custom.keys.none { it in owned }) { "customRequestParms cannot override Responses session, transport or tool configuration" }
    require(custom.keys.none { it in setOf("messages", "max_tokens", "thinking_budget", "frequency_penalty", "presence_penalty", "stop") }) {
        "Use native Responses request options, such as max_output_tokens and reasoning.effort"
    }
    return buildJsonObject {
        custom.forEach { (key, value) -> put(key, value) }
        put("model", model.model)
        put("input", JsonArray(input))
        // Top-level instructions do not carry over through previous_response_id.
        put("instructions", instructions)
        put("stream", stream)
        put("store", true)
        previousResponseId?.let { put("previous_response_id", it) }
        putJsonArray("context_management") {
            add(buildJsonObject { put("type", "compaction"); put("compact_threshold", model.compactThreshold) })
        }
        putJsonArray("tools") {
            tools.forEach { tool -> add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", contentNegotiationJson.encodeToJsonElement(tool.dataSchema))
                put("strict", false)
            }) }
            if (model.webSearch) add(buildJsonObject { put("type", "web_search") })
            if (model.imageGeneration) add(buildJsonObject { put("type", "image_generation") })
        }
    }
}

internal fun ChatMessages.toResponsesInput(): List<JsonObject> = mapNotNull { message ->
    when (val role = message.role)
    {
        is Role.MARKING, is Role.SHOWING_DATA -> null
        is Role.TOOL -> buildJsonObject {
            put("type", "function_call_output")
            put("call_id", role.id)
            put("output", message.content.toResponsesContent())
        }
        Role.USER, Role.SYSTEM -> buildJsonObject {
            put("role", if (role == Role.SYSTEM) "developer" else "user")
            put("content", message.content.toResponsesContent())
        }
        Role.ASSISTANT -> error("Send only new inputs; prior assistant output is managed by previous_response_id")
    }
}

private fun Content.toResponsesContent(): JsonElement =
    if (all { it is ContentNode.Text }) JsonPrimitive(toText())
    else buildJsonArray {
        for (node in this@toResponsesContent) add(buildJsonObject {
            when (node)
            {
                is ContentNode.Text -> { put("type", "input_text"); put("text", node.text) }
                is ContentNode.Image -> { put("type", "input_image"); put("image_url", node.image.url); put("detail", "auto") }
                is ContentNode.File -> {
                    put("type", "input_file")
                    if (node.file.url.startsWith("https://") || node.file.url.startsWith("http://")) put("file_url", node.file.url)
                    else { put("filename", node.file.filename); put("file_data", node.file.url) }
                }
            }
        })
    }
