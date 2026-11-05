package moe.tachyon.windwhisper.ai.internal.llm

import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.ChatMessage
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.TokenUsage
import moe.tachyon.windwhisper.ai.UnknownAiResponseException
import java.net.URI

/** Decode the wire response, rather than the SDK-only output_text convenience property. */
internal fun parseResponsesResponse(response: JsonObject): RequestResult
{
    val output = (response["output"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val calls = linkedMapOf<String, Pair<String, String>>()
    var formatError: String? = null

    output.forEach { item ->
        when (item.string("type"))
        {
            "message" -> (item["content"] as? JsonArray).orEmpty().forEach contentLoop@ { part ->
                val content = part as? JsonObject ?: return@contentLoop
                when (content.string("type"))
                {
                    "output_text" -> text.append(renderOutputText(content))
                    "refusal" -> text.append(content.string("refusal").orEmpty())
                }
            }
            "reasoning" -> (item["summary"] as? JsonArray).orEmpty().forEach summaryLoop@ { part ->
                val summary = part as? JsonObject ?: return@summaryLoop
                if (summary.string("type") == "summary_text")
                    reasoning.append(summary.string("text").orEmpty())
            }
            "function_call" ->
            {
                val callId = item.string("call_id")
                val name = item.string("name")
                val arguments = item.string("arguments")
                when
                {
                    callId.isNullOrBlank() || name.isNullOrBlank() || arguments == null ->
                        formatError = "function_call 缺少 call_id、name 或 arguments"
                    callId in calls -> formatError = "function_call 包含重复的 call_id"
                    else -> calls[callId] = name to arguments
                }
            }
        }
    }

    val usageJson = response["usage"] as? JsonObject
    val inputTokens = usageJson?.long("input_tokens") ?: 0
    val outputTokens = usageJson?.long("output_tokens") ?: 0
    val usage = TokenUsage(outputTokens, inputTokens, usageJson?.long("total_tokens") ?: inputTokens + outputTokens)
    val responseError = response["error"] as? JsonObject
    val error = when
    {
        responseError != null -> responsesError(responseError)
        response.string("status") == "incomplete" -> UnknownAiResponseException(message =
            "Responses API 响应未完成: ${(response["incomplete_details"] as? JsonObject)?.string("reason") ?: "unknown"}")
        response.string("status") != "completed" -> UnknownAiResponseException(message =
            "Responses API 响应状态异常: ${response.string("status") ?: "missing status"}")
        formatError != null -> UnknownAiResponseException(message = "Responses API 响应格式无效: $formatError")
        response["output"] !is JsonArray || output.size != (response["output"] as JsonArray).size ->
            UnknownAiResponseException(message = "Responses API 响应格式无效: output 必须是对象数组")
        text.isBlank() && calls.isEmpty() -> UnknownAiResponseException(message =
            "Responses API 未返回可用的文本或函数调用")
        else -> null
    }
    // Never dispatch partial or failed calls, even if a terminal error contains function_call Items.
    val completedCalls = if (error == null) calls else emptyMap()
    return RequestResult(
        toolCalls = completedCalls,
        message = ChatMessage(
            role = Role.ASSISTANT,
            content = Content(text.toString()),
            reasoningContent = Content(reasoning.toString()),
            toolCalls = completedCalls.map { (id, call) -> ChatMessage.ToolCall(id, call.first, call.second) },
            responsesOutput = output,
        ),
        usage = usage,
        error = error,
    )
}

/** Responses SSE terminates with a typed event, not the Chat Completions [DONE] sentinel. */
internal class ResponsesStreamState(private val bufferTextUntilCompleted: Boolean = false)
{
    private data class Part(val outputIndex: Int, val contentIndex: Int, val reasoning: Boolean)

    private val receivedParts = mutableMapOf<Part, StringBuilder>()
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var terminal: RequestResult? = null

    val isTerminal: Boolean get() = terminal != null

    suspend fun accept(event: JsonObject, onReceive: suspend (StreamAiResponseSlice) -> Unit)
    {
        if (terminal != null) return
        when (val type = event.string("type"))
        {
            "response.output_text.delta", "response.refusal.delta", "response.reasoning_summary_text.delta" ->
            {
                val delta = event.string("delta")
                if (delta == null)
                {
                    terminal = partialResult(UnknownAiResponseException(message = "Responses API 数据流缺少 delta"))
                    return
                }
                val isReasoning = type == "response.reasoning_summary_text.delta"
                val part = Part(
                    event.int("output_index") ?: 0,
                    event.int(if (isReasoning) "summary_index" else "content_index") ?: 0,
                    isReasoning,
                )
                receivedParts.getOrPut(part) { StringBuilder() }.append(delta)
                if (bufferTextUntilCompleted && !isReasoning) text.append(delta)
                else emit(delta, isReasoning, onReceive)
            }
            "response.completed", "response.failed", "response.incomplete" ->
            {
                val response = event["response"] as? JsonObject
                if (response == null)
                {
                    terminal = partialResult(UnknownAiResponseException(message = "Responses API 终止事件缺少 response"))
                    return
                }
                val result = parseResponsesResponse(response)
                if (type != "response.completed" && result.error == null)
                {
                    result.error = UnknownAiResponseException(message = "Responses API 响应未成功完成: $type")
                    result.toolCalls = emptyMap()
                    result.message = result.message.copy(toolCalls = emptyList())
                }
                terminal = result
                emitMissingParts(result.message.responsesOutput, onReceive)
                if (bufferTextUntilCompleted)
                {
                    val rendered = result.message.content.toText().ifEmpty { text.toString() }
                    text.clear()
                    emit(rendered, false, onReceive)
                }
            }
            "error" -> terminal = partialResult(responsesError(event))
            null -> terminal = partialResult(UnknownAiResponseException(message = "Responses API 数据流缺少事件 type"))
            // Function arguments are dispatched only from the completed response snapshot. Other
            // events (including item/argument deltas) can safely arrive before that snapshot.
        }
    }

    fun result(error: Throwable? = null): RequestResult
    {
        val result = terminal ?: return partialResult(error ?: UnknownAiResponseException(message =
            "Responses API 数据流未收到终止事件，响应可能已中断"))
        val finalError = error ?: result.error
        if (finalError == null) return result
        return result.copy(
            toolCalls = emptyMap(),
            message = result.message.copy(
                content = result.message.content.takeUnless { it.isEmpty() } ?: Content(text.toString()),
                reasoningContent = result.message.reasoningContent.takeUnless { it.isEmpty() } ?: Content(reasoning.toString()),
                toolCalls = emptyList(),
            ),
            error = finalError,
        )
    }

    private fun partialResult(error: Throwable): RequestResult = RequestResult(
        emptyMap(),
        ChatMessage(Role.ASSISTANT, text.toString(), reasoning.toString()),
        TokenUsage(),
        error,
    )

    private suspend fun emitMissingParts(output: List<JsonObject>, onReceive: suspend (StreamAiResponseSlice) -> Unit)
    {
        output.forEachIndexed { outputIndex, item ->
            val isReasoning = item.string("type") == "reasoning"
            if (bufferTextUntilCompleted && !isReasoning) return@forEachIndexed
            val parts = item[if (isReasoning) "summary" else "content"] as? JsonArray ?: return@forEachIndexed
            parts.forEachIndexed partLoop@ { contentIndex, element ->
                val part = element as? JsonObject ?: return@partLoop
                val fullText = when (part.string("type"))
                {
                    "output_text" -> renderOutputText(part)
                    "summary_text" -> part.string("text")
                    "refusal" -> part.string("refusal")
                    else -> null
                } ?: return@partLoop
                val key = Part(outputIndex, contentIndex, isReasoning)
                val received = receivedParts[key]?.toString().orEmpty()
                // A final snapshot is authoritative. Only append a matching suffix to the UI;
                // replaying the whole snapshot would duplicate already displayed deltas.
                if (fullText.startsWith(received)) emit(fullText.removePrefix(received), isReasoning, onReceive)
                else if (part.string("type") == "output_text")
                {
                    // Unbuffered callers cannot insert citations into text already shown. Preserve
                    // the remaining raw text and append its sources without repeating the answer.
                    val raw = part.string("text").orEmpty()
                    if (raw.startsWith(received))
                    {
                        emit(raw.removePrefix(received), false, onReceive)
                        val links = urlCitations(part, raw).map { it.link }.distinct()
                        if (links.isNotEmpty()) emit(" " + links.joinToString(" "), false, onReceive)
                    }
                }
            }
        }
    }

    private suspend fun emit(delta: String, isReasoning: Boolean, onReceive: suspend (StreamAiResponseSlice) -> Unit)
    {
        if (delta.isEmpty()) return
        if (isReasoning)
        {
            reasoning.append(delta)
            onReceive(StreamAiResponseSlice.Message(content = "", reasoningContent = delta))
        }
        else
        {
            text.append(delta)
            onReceive(StreamAiResponseSlice.Message(content = delta))
        }
    }
}

private data class UrlCitation(val start: Int, val end: Int, val link: String)

private val citationMarker = Regex("(?:\uE200cite\uE202[^\uE201]+\uE201|【[0-9]+†[^】]+】)")

/** Keep annotation metadata untouched for replay; only the user-visible text gains Markdown links. */
private fun renderOutputText(part: JsonObject): String
{
    val raw = part.string("text").orEmpty()
    val citations = urlCitations(part, raw)
    if (citations.isEmpty()) return raw
    val linksAt = citations.groupBy { it.end }
    val omitted = BooleanArray(raw.length)
    citations.forEach { citation ->
        if (citationMarker.matches(raw.substring(citation.start, citation.end)))
            for (index in citation.start until citation.end) omitted[index] = true
    }
    return buildString()
    {
        for (index in 0..raw.length)
        {
            linksAt[index]?.map { it.link }?.distinct()?.forEach { append(" ").append(it) }
            if (index < raw.length && !omitted[index]) append(raw[index])
        }
    }
}

private fun urlCitations(part: JsonObject, raw: String): List<UrlCitation>
{
    val characterCount = raw.codePointCount(0, raw.length)
    return (part["annotations"] as? JsonArray).orEmpty().mapNotNull { element ->
        val annotation = element as? JsonObject ?: return@mapNotNull null
        if (annotation.string("type") != "url_citation") return@mapNotNull null
        val url = annotation.string("url") ?: return@mapNotNull null
        val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank()) return@mapNotNull null
        val title = annotation.string("title")?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: uri.host
        val label = buildString()
        {
            title.forEach { character ->
                if (character in "\\`*_{}[]()#+-.!|<>&") append('\\')
                append(character)
            }
        }
        // Angle-delimited destinations tolerate parentheses; the URI rejects whitespace/control
        // characters and toASCIIString percent-encodes Unicode without changing query separators.
        val destination = uri.toASCIIString().replace("<", "%3C").replace(">", "%3E")
        val start = annotation.int("start_index")
        val end = annotation.int("end_index")
        val validOffsets = start != null && end != null && start >= 0 && start <= end && end <= characterCount
        // The API reports character offsets. Convert code points explicitly so emoji surrogate
        // pairs are not split by Kotlin's UTF-16 string indexing. Bad offsets append a source.
        UrlCitation(
            start = if (validOffsets) raw.offsetByCodePoints(0, start) else raw.length,
            end = if (validOffsets) raw.offsetByCodePoints(0, end) else raw.length,
            link = "[$label](<$destination>)",
        )
    }
}

private fun responsesError(error: JsonObject): Throwable = UnknownAiResponseException(message =
    "Responses API 错误${error.string("code")?.let { " ($it)" }.orEmpty()}: ${error.string("message") ?: "unknown error"}")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
