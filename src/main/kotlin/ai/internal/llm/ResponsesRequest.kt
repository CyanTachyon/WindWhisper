package moe.tachyon.windwhisper.ai.internal.llm

import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.ContentNode
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.contentNegotiationJson
import moe.tachyon.windwhisper.utils.toJsonElement

internal fun buildResponsesRequest(
    context: LlmLoopPlugin.Context,
    messages: ChatMessages,
    stream: Boolean,
): JsonObject
{
    require(context.frequencyPenalty == null && context.presencePenalty == null && context.stop == null) {
        "Responses API does not support frequencyPenalty, presencePenalty or stop"
    }
    require(context.model.thinkingBudget == null) {
        "Responses API does not support thinking_budget; configure customRequestParms.reasoning instead"
    }
    val request = buildJsonObject {
        put("model", context.model.model)
        put("input", messages.toResponsesInput())
        put("stream", stream)
        // The application's compression plugins own history, so replay items instead of chaining IDs.
        put("store", false)
        putJsonArray("include") { add("reasoning.encrypted_content") }
        context.maxTokens?.let { put("max_output_tokens", it) }
        context.temperature?.let { put("temperature", it) }
        context.topP?.let { put("top_p", it) }
        if (context.tools.isNotEmpty()) putJsonArray("tools") {
            context.tools.forEach { tool ->
                add(buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", contentNegotiationJson.encodeToJsonElement(tool.dataSchema))
                    // Existing tool schemas have optional fields and were not authored for strict mode.
                    put("strict", false)
                })
            }
        }
    }
    val custom = context.model.customRequestParms?.toJsonElement()?.jsonObject ?: emptyMap()
    require("stream" !in custom || custom["stream"] == JsonPrimitive(stream)) {
        "customRequestParms.stream must match the requested transport mode"
    }
    require(custom["previous_response_id"]?.let { it != JsonNull } != true &&
        custom["conversation"]?.let { it != JsonNull } != true) {
        "WindWhisper manages Responses history locally; previous_response_id and conversation are not supported"
    }
    val customTools = custom["tools"]?.let {
        require(it is JsonArray && it.all { tool -> tool is JsonObject }) {
            "customRequestParms.tools must be an array of tool objects"
        }
        it.map { tool -> tool.jsonObject }
    }.orEmpty()
    // Extra tools must not replace the application's forum functions.
    val tools = ((request["tools"] as? JsonArray).orEmpty().map { it.jsonObject } + customTools)
        .distinctBy { tool -> if (tool["type"] == JsonPrimitive("function")) tool["name"] ?: tool else tool }
    val merged = (request + custom).toMutableMap()
    if (tools.isEmpty()) merged.remove("tools") else merged["tools"] = JsonArray(tools)
    val includes = (merged["include"] as? JsonArray).orEmpty() + JsonPrimitive("reasoning.encrypted_content")
    return JsonObject(merged + ("include" to JsonArray(includes.distinct())))
}

internal fun JsonObject.isResponsesWebSearchTool(): Boolean =
    (this["type"] as? JsonPrimitive)?.contentOrNull?.let { it == "web_search" || it.startsWith("web_search_") } == true

internal fun ChatMessages.toResponsesInput(): JsonArray = buildJsonArray {
    for (message in this@toResponsesInput)
    {
        when (val role = message.role)
        {
            is Role.MARKING, is Role.SHOWING_DATA -> continue
            is Role.TOOL -> add(buildJsonObject {
                put("type", "function_call_output")
                put("call_id", role.id)
                put("output", message.content.toResponsesContent())
            })
            Role.ASSISTANT -> {
                if (message.responsesOutput.isNotEmpty())
                {
                    // Replay the original ordering of reasoning, messages and calls exactly once.
                    message.responsesOutput.forEach { add(it) }
                }
                else
                {
                    if (!message.content.isEmpty()) add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.content.toResponsesContent())
                    })
                    message.toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", call.id)
                            put("name", call.name)
                            put("arguments", call.arguments)
                        })
                    }
                }
            }
            Role.SYSTEM, Role.USER -> add(buildJsonObject {
                put("role", role.role)
                put("content", message.content.toResponsesContent())
            })
        }
    }
}

private fun Content.toResponsesContent(): JsonElement =
    if (all { it is ContentNode.Text }) JsonPrimitive(toText())
    else buildJsonArray {
        for (node in this@toResponsesContent) add(buildJsonObject {
            when (node)
            {
                is ContentNode.Text -> {
                    put("type", "input_text")
                    put("text", node.text)
                }
                is ContentNode.Image -> {
                    put("type", "input_image")
                    put("image_url", node.image.url)
                    put("detail", "auto")
                }
                is ContentNode.File -> {
                    put("type", "input_file")
                    if (node.file.url.startsWith("https://") || node.file.url.startsWith("http://"))
                        put("file_url", node.file.url)
                    else {
                        put("filename", node.file.filename)
                        put("file_data", node.file.url)
                    }
                }
            }
        })
    }
