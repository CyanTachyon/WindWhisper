@file:Suppress("unused")
@file:OptIn(ExperimentalUuidApi::class)

package moe.tachyon.windwhisper.ai.internal.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.windwhisper.ai.AiRetryFailedException
import moe.tachyon.windwhisper.ai.ChatMessage
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.TokenUsage
import moe.tachyon.windwhisper.ai.UnknownAiResponseException
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.contentNegotiationJson
import moe.tachyon.windwhisper.utils.JsonSchema
import moe.tachyon.windwhisper.utils.ktorClientEngineFactory
import moe.tachyon.windwhisper.utils.toJsonElement
import kotlin.collections.set
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
private sealed interface AiResponse
{
    val id: String
    val `object`: String
    val model: String
    val usage: TokenUsage?
    val created: Long
    val choices: List<Choice>

    @Serializable
    sealed interface Choice
    {
        val finishReason: FinishReason?
        val index: Int?
        val message: Message

        @Serializable
        sealed interface Message
        {
            val content: String?
            @SerialName("reasoning_content")
            val reasoningContent: String?
            @SerialName("tool_calls")
            val toolCalls: List<ToolCall>?

            @Serializable
            data class ToolCall(
                val id: String? = null,
                val index: Int? = null,
                val function: Function,
            )
            {
                @Suppress("RedundantNullableReturnType")
                val type: String? = "function"
                @Serializable
                data class Function(
                    val name: String? = null,
                    val arguments: String? = null,
                )
            }
        }
    }

    @Serializable
    enum class FinishReason
    {
        @SerialName("stop")
        STOP,

        @SerialName("length")
        LENGTH,

        @SerialName("content_filter")
        CONTENT_FILTER,

        @SerialName("tool_calls")
        TOOL_CALLS,

        @SerialName("insufficient_system_resource")
        INSUFFICIENT_SYSTEM_RESOURCE,
    }
}

@Serializable
private data class DefaultAiResponse(
    override val id: String,
    override val choices: List<Choice>,
    override val created: Long,
    override val model: String,
    override val `object`: String,
    override val usage: TokenUsage?,
): AiResponse
{
    @Serializable
    data class Choice(
        @SerialName("finish_reason")
        override val finishReason: AiResponse.FinishReason? = null,
        override val index: Int? = null,
        override val message: Message,
    ): AiResponse.Choice
    {
        @Serializable
        data class Message(
            override val content: String,
            @SerialName("reasoning_content")
            override val reasoningContent: String? = null,
            @SerialName("tool_calls")
            override val toolCalls: List<AiResponse.Choice.Message.ToolCall>? = null,
        ): AiResponse.Choice.Message
    }
}

@Serializable
private data class StreamAiResponse(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    override val choices: List<Choice> = emptyList(),
    override val usage: TokenUsage? = null,
): AiResponse
{
    @Serializable
    data class Choice(
        override val index: Int? = null,
        @SerialName("finish_reason")
        override val finishReason: AiResponse.FinishReason? = null,
        @SerialName("delta")
        override val message: Message,
    ): AiResponse.Choice
    {
        @Serializable
        data class Message(
            override val content: String? = null,
            @SerialName("reasoning_content")
            val reasoningContent0: String? = null,
            @SerialName("reasoning")
            val reasoningContent1: String? = null,
            @SerialName("tool_calls")
            override val toolCalls: List<AiResponse.Choice.Message.ToolCall> = emptyList(),
        ): AiResponse.Choice.Message
        {
            override val reasoningContent: String?
                get() = reasoningContent0 ?: reasoningContent1
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class AiRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("max_tokens") val maxTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("temperature") val temperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("top_p") val topP: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("stop") val stop: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("tools") val tools: List<Tool> = emptyList(),
)
{
    @Serializable
    data class Message(
        val role: String,
        val content: Content = Content(),
        @SerialName("reasoning_content")
        val reasoningContent0: Content?,
        @SerialName("reasoning")
        val reasoningContent1: Content?,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("tool_call_id")
        val toolCallId: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("tool_calls")
        val toolCalls: List<ToolCall> = emptyList(),
    )
    {
        constructor(
            role: String,
            content: Content = Content(),
            reasoningContent: Content?,
            toolCallId: String? = null,
            toolCalls: List<ToolCall> = emptyList()
        ): this(role, content, reasoningContent, reasoningContent, toolCallId, toolCalls)

        @Serializable
        data class ToolCall(
            val id: String,
            val function: Function,
        )
        {
            @EncodeDefault(EncodeDefault.Mode.ALWAYS)
            val type: String = "function"
            @Serializable
            data class Function(
                val name: String,
                val arguments: String,
            )
        }
    }

    @Serializable
    data class ResponseFormat(
        val type: Type,
    )
    {
        @Serializable
        enum class Type
        {
            @SerialName("json_object")
            JSON,

            @SerialName("text")
            TEXT,
        }
    }

    @Serializable
    data class Tool(val function: Function)
    {
        @Suppress("RedundantNullableReturnType")
        val type: String? = "function"

        @Serializable
        data class Function(
            val name: String,
            val description: String? = null,
            val parameters: JsonSchema? = null,
        )
    }
}

private fun ChatMessages.toRequestMessages(escapeToolCalls: Boolean): List<AiRequest.Message>
{
    fun ChatMessage.toRequestMessage(): AiRequest.Message?
    {
        when (this.role)
        {
            is Role.USER         -> Unit
            is Role.SYSTEM       -> Unit
            is Role.ASSISTANT    -> Unit
            is Role.TOOL         -> Unit
            is Role.MARKING      -> return null
            is Role.SHOWING_DATA -> return null
        }

        return AiRequest.Message(
            role =
                if (escapeToolCalls && this.role is Role.TOOL) Role.USER.role
                else this.role.role,
            content =
                if (escapeToolCalls && this.role is Role.TOOL) Content("<|tool_call_result|>\n${toolCallNameTag}\n${this.role.name}${toolCallNameTag}\n<|tool_response|>\n") + this.content + Content(
                    "\n<|tool_response|>\n<|tool_call_result|>"
                )
                else if (escapeToolCalls && this.role is Role.ASSISTANT) this.content + Content(this.toolCalls.flatMap {
                    Content(
                        "$toolCallTag\n$toolCallNameTag${it.name}$toolCallNameTag\n${toolCallArgsTag}\n${it.arguments}\n${toolCallArgsTag}\n${toolCallTag}"
                    )
                })
                else this.content,
            reasoningContent = this.reasoningContent.takeIf { it.isNotEmpty() && this.role is Role.ASSISTANT } ?: Content(),
            toolCallId = (this.role as? Role.TOOL)?.id?.takeUnless { escapeToolCalls },
            toolCalls = (this.toolCalls.takeUnless { escapeToolCalls } ?: emptyList()).map { AiRequest.Message.ToolCall(it.id, function = AiRequest.Message.ToolCall.Function(it.name, it.arguments)) },
        )
    }

    return mapNotNull(ChatMessage::toRequestMessage)
}

private val logger = WindWhisperLogger.getLogger()

private val streamAiClient = HttpClient(ktorClientEngineFactory)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = Int.MAX_VALUE.toLong()
        endpoint()
        {
            keepAliveTime = aiConfig.timeout
            connectTimeout = aiConfig.timeout
        }
    }
    install(SSE)
    {
        bufferPolicy = SSEBufferPolicy.All
    }
}

private val defaultAiClient = HttpClient(ktorClientEngineFactory)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = Int.MAX_VALUE.toLong()
        endpoint()
        {
            keepAliveTime = aiConfig.timeout
            connectTimeout = aiConfig.timeout
        }
    }
    install(ContentNegotiation)
    {
        json(contentNegotiationJson)
    }
}

sealed class AiResult(val messages: ChatMessages, val usage: TokenUsage)
{
    class Success(messages: ChatMessages, usage: TokenUsage): AiResult(messages, usage)
    class TooManyRequests(messages: ChatMessages, usage: TokenUsage) : AiResult(messages, usage)
    class ServiceError(messages: ChatMessages, usage: TokenUsage) : AiResult(messages, usage)
    class Cancelled(messages: ChatMessages, usage: TokenUsage) : AiResult(messages, usage)
    class UnknownError(messages: ChatMessages, usage: TokenUsage, val error: Throwable) : AiResult(messages, usage)
}

/**
 * 发送流式AI请求，通过`onReceive`回调接收流式响应。最终结果通过返回值返回。
 *
 * 该函数理应永远不会抛出任何错误，返回详见[AiResult]
 */
suspend fun sendAiRequest(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    maxTokens: Int? = null,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    tools: List<AiToolInfo<*>> = emptyList(),
    plugins: List<LlmLoopPlugin> = emptyList(),
    stream: Boolean,
    onReceive: suspend (StreamAiResponseSlice) -> Unit = {},
): AiResult = model.semaphore.withPermit()
{
    val res = mutableListOf<ChatMessage>()
    var send = true
    var usage = TokenUsage()
    try
    {
        val context = LlmLoopPlugin.Context(
            model,
            messages,
            maxTokens,
            temperature,
            topP,
            frequencyPenalty,
            presencePenalty,
            stop,
            tools,
            res,

            onReceive = onReceive,
            addTokenUsage = { usage += it },
        )

        plugins.filterIsInstance<BeforeLlmLoop>().forEach()
        {
            it.beforeLoop(context)
        }

        while (send)
        {
            val beforeLlmRequestContext = BeforeLlmRequest.BeforeRequestContext(context.allMessages)
            plugins.filterIsInstance<BeforeLlmRequest>().forEach()
            {
                it.beforeRequest(context, beforeLlmRequestContext)
            }

            val url = context.model.url
            val body = AiRequest(
                model = context.model.model,
                messages = beforeLlmRequestContext.requestMessage.toRequestMessages(!model.supportToolCalls),
                stream = stream,
                maxTokens = context.maxTokens,
                thinkingBudget = context.model.thinkingBudget,
                temperature = context.temperature,
                topP = context.topP,
                frequencyPenalty = context.frequencyPenalty,
                presencePenalty = context.presencePenalty,
                stop = context.stop,
                tools = context.tools.map()
                {
                    AiRequest.Tool(
                        function = AiRequest.Tool.Function(
                            name = it.name,
                            description = it.description,
                            parameters = it.dataSchema,
                        )
                    )
                },
            ).let(contentNegotiationJson::encodeToJsonElement)
                .jsonObject
                .plus(context.model.customRequestParms?.toJsonElement()?.jsonObject ?: emptyMap())
                .let(::JsonObject)

            val requestResult = run()
            {
                val errors = mutableListOf<Throwable>()
                repeat(aiConfig.retry)
                {
                    currentCoroutineContext().ensureActive()
                    val tmp = sendRequest(url, context.model.key.random(), body, stream, onReceive)
                    tmp.error?.let(errors::add)
                    tmp.error =
                        if (errors.size <= 1) errors.firstOrNull()
                        else errors.map(::UnknownAiResponseException).let(::AiRetryFailedException)
                    if (!tmp.message.isBlank()) return@run tmp
                }
                RequestResult(emptyMap(),
                    ChatMessage(Role.ASSISTANT, ""),
                    TokenUsage(), if (errors.size == 1) errors[0] else errors.map(::UnknownAiResponseException).let(::AiRetryFailedException))
            }

            plugins.filterIsInstance<AfterLlmResponse>().forEach()
            {
                it.afterResponse(context, requestResult)
            }

            val (waitingTools, curMsg, usage0, e) = requestResult
            if (!curMsg.content.isEmpty() || !curMsg.reasoningContent.isEmpty() || !curMsg.toolCalls.isEmpty()) res += curMsg
            usage += usage0
            if (e != null) throw e
            if (waitingTools.isNotEmpty())
            {
                val parseToolCalls = parseToolCalls(waitingTools, tools, onReceive)
                res += parseToolCalls
                parseToolCalls.filter { it.role is Role.SHOWING_DATA }.forEach()
                {
                    onReceive(StreamAiResponseSlice.ShowingTool(it.content.toText(), (it.role as Role.SHOWING_DATA).type))
                }
            }
            else send = false
        }
    }
    catch (e: Throwable)
    {
        return when (e)
        {
            is CancellationException -> AiResult.Cancelled(res.toChatMessages(), usage)
            is SSEClientException if (e.response?.status?.value == 429) -> AiResult.TooManyRequests(res.toChatMessages(), usage)
            is SSEClientException if (e.response?.status?.value in listOf(500, 502, 504)) -> AiResult.ServiceError(res.toChatMessages(), usage)
            else -> AiResult.UnknownError(res.toChatMessages(), usage, e)
        }
    }
    AiResult.Success(res.toChatMessages(), usage)
}

data class RequestResult(
    var toolCalls : Map<String, Pair<String, String>>,
    var message: ChatMessage,
    var usage: TokenUsage,
    var error: Throwable?,
)

const val toolCallTag = "<|tool_call|>"
const val toolCallNameTag = "<|tool_name|>"
const val toolCallArgsTag = "<|tool_args|>"
val toolNameRegex = Regex("<\\|tool_name\\|>(.*?)<\\|tool_name\\|>", RegexOption.DOT_MATCHES_ALL)
val toolArgsRegex = Regex("<\\|tool_args\\|>(.*?)<\\|tool_args\\|>", RegexOption.DOT_MATCHES_ALL)

private suspend fun sendRequest(
    url: String,
    key: String,
    body: JsonElement,
    stream: Boolean,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
    useCustomToolCall: Boolean = true,
): RequestResult
{
    logger.config("AI请求$url : ${contentNegotiationJson.encodeToString(body)}")

    if (!stream) runCatching()
    {
        val response = defaultAiClient.post(url)
        {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(body)
        }
        val responseBody = response.bodyAsText()
        val json = contentNegotiationJson.parseToJsonElement(responseBody)
        val res = runCatching()
        {
            contentNegotiationJson.decodeFromJsonElement<DefaultAiResponse>(json)
        }.onFailure()
        {
            logger.warning("AI请求返回异常: $json")
        }.getOrThrow()
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            content = res.choices.joinToString("") { it.message.content }.let(::Content),
            reasoningContent = res.choices.joinToString("") { it.message.reasoningContent ?: "" }.let(::Content)
        )
        val usage = res.usage
        val calls = res.choices.mapNotNull { it.message.toolCalls }.flatten().mapNotNull()
        {
            it.id ?: return@mapNotNull null
            it.id to ((it.function.name ?: "") to (it.function.arguments ?: ""))
        }.toMap()
        return RequestResult(calls, msg, usage ?: TokenUsage(), null)
    }.onFailure()
    {
        return RequestResult(emptyMap(), ChatMessage(Role.ASSISTANT, ""), TokenUsage(), it)
    }


    var usage0 = TokenUsage()
    val waitingTools = mutableMapOf<Int, Pair<String, String>>()
    var lstIndex = -1
    var curMsg = ChatMessage(Role.ASSISTANT, "")
    val responses = mutableListOf<StreamAiResponse>()
    val loopId = Uuid.random().toHexString()
    var done = false

    val r = runCatching()
    {
        streamAiClient.sse(url, {
            method = HttpMethod.Post
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            setBody(contentNegotiationJson.encodeToString(body))
        })
        {
            if (!call.response.status.isSuccess())
                throw SSEClientException(call.response, message = "AI请求返回异常，HTTP状态码 ${call.response.status.value}, 响应体: ${call.response.bodyAsText()}")
            val buffer = StringBuilder()
            incoming
                .mapNotNull { it.data }
                .filterNot {
                    done = done || it.trim() == "[DONE]"
                    done
                }
                .map()
                {
                    runCatching()
                    {
                        contentNegotiationJson.decodeFromString<StreamAiResponse>(it)
                    }.onFailure { e ->
                        logger.warning("无法解析AI返回的数据流, data: $it")
                    }.getOrThrow()
                }
                .collect()
                {
                    responses += it
                    if (it.usage != null && it.usage.totalTokens > usage0.totalTokens)
                        usage0 = it.usage
                    it.choices.forEach()
                    { c ->
                        c.message.toolCalls.forEach()
                        { tool ->
                            val index = tool.index ?: lstIndex.takeIf { it1 -> it1 > 0 }
                            if (index == null)
                            {
                                logger.warning("出现错误: 工具调用ID丢失，无法将工具调用名称与参数对应。 id: ${tool.id}, lstIndex: $lstIndex waitingTools: $waitingTools")
                            }
                            else
                            {
                                val (name, args) = waitingTools[index] ?: ("" to "")
                                val newName = name + (tool.function.name ?: "")
                                val newArg = args + (tool.function.arguments ?: "")
                                waitingTools[index] = newName to newArg
                                lstIndex = index
                            }
                        }

                        if (!c.message.reasoningContent.isNullOrEmpty())
                        {
                            onReceive(
                                StreamAiResponseSlice.Message(
                                    content = "",
                                    reasoningContent = c.message.reasoningContent ?: "",
                                )
                            )
                            curMsg += ChatMessage(
                                role = Role.ASSISTANT,
                                content = "",
                                reasoningContent = c.message.reasoningContent ?: "",
                            )
                            return@forEach
                        }

                        if (!useCustomToolCall)
                        {
                            if (!c.message.content.isNullOrEmpty())
                            {
                                onReceive(
                                    StreamAiResponseSlice.Message(
                                        content = c.message.content,
                                        reasoningContent = "",
                                    )
                                )
                                curMsg += ChatMessage(
                                    role = Role.ASSISTANT,
                                    content = c.message.content,
                                )
                            }
                            return@forEach
                        }

                        val putContent: suspend (String) -> Unit = { contentPart ->
                            onReceive(
                                StreamAiResponseSlice.Message(
                                    content = contentPart,
                                    reasoningContent = "",
                                )
                            )
                            curMsg += ChatMessage(
                                role = Role.ASSISTANT,
                                content = Content(contentPart),
                            )
                        }

                        buffer.append(c.message.content ?: "")
                        val tmp = buffer.toString()
                        if (tmp.contains(toolCallTag))
                        {
                            val parts = tmp.split(toolCallTag)
                            parts.forEachIndexed()
                            { i, part ->
                                // 偶数部分是内容
                                if (i % 2 == 0 && i != parts.size - 1)
                                {
                                    if (part.isNotEmpty())
                                        putContent(part)
                                }
                                // 奇数部分是toolCall
                                else if (i % 2 == 1 && i != parts.size - 1)
                                {
                                    val index = Random.nextInt()
                                    val matchName = toolNameRegex.find(part)
                                    val matchArgs = toolArgsRegex.find(part)
                                    val toolName = matchName?.value
                                        ?.trim()
                                        ?.removePrefix(toolCallNameTag)
                                        ?.removeSuffix(toolCallNameTag)
                                        ?.trim()
                                        ?: ""
                                    val toolArgs = matchArgs?.value
                                        ?.trim()
                                        ?.removePrefix(toolCallArgsTag)
                                        ?.removeSuffix(toolCallArgsTag)
                                        ?.trim()
                                        ?: ""
                                    println("Parsed tool call: name='$toolName', args='$toolArgs'")
                                    waitingTools[index] = toolName to toolArgs
                                }
                                // 最后一部分可能是不完整的内容，保留在buffer中
                                else
                                {
                                    buffer.clear()
                                    if (parts.size % 2 == 0) buffer.append(toolCallTag)
                                    buffer.append(part)
                                }
                            }
                            return@forEach
                        }

                        // 检查结尾是否出现toolCallTag的部分，即可能有未完成的toolCallTag
                        for (len in toolCallTag.length - 1 downTo 1)
                        {
                            if (tmp.endsWith(toolCallTag.take(len)))
                            {
                                val completePart = tmp.dropLast(len)
                                if (completePart.isNotEmpty())
                                    putContent(completePart)
                                buffer.clear()
                                buffer.append(toolCallTag.take(len))
                                return@forEach
                            }
                        }
                        // 未出现toolCallTag并且不可能出现，全部作为内容
                        putContent(tmp)
                        buffer.clear()
                    }
                }
        }

        waitingTools.onEachIndexed()
        { i, it ->
            curMsg += ChatMessage(
                role = Role.ASSISTANT,
                content = Content(),
                toolCalls = listOf(ChatMessage.ToolCall("call-$loopId-$i", it.value.first, it.value.second)),
            )
        }
    }.let()
    {
        val e = it.exceptionOrNull() ?: return@let it
        if (e is SSEClientException)
        {
            val response = e.response ?: return@let it
            if (!response.status.isSuccess())
                return@let Result.failure(Exception("AI请求返回异常，HTTP状态码 ${response.status.value}, 响应体: ${response.bodyAsText()}"))
        }
        it
    }

    return RequestResult(
        waitingTools.values.mapIndexed { i, it -> "call-$loopId-$i" to it }.toMap(),
        curMsg,
        usage = usage0,
        r.exceptionOrNull() ?: if (done) null else UnknownAiResponseException(message = "AI请求未能正确完成，可能由于网络问题导致响应中断")
    )
}

private suspend fun parseToolCalls(
    waitingTools: Map<String, Pair<String, String>>,
    tools: List<AiToolInfo<*>>,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
): ChatMessages
{
    return waitingTools.map()
    {
        val (id, t) = it
        if (id.isEmpty()) Uuid.random().toHexString() to t
        else id to t
    }.flatMap()
    { (id, t) ->
        val (toolName, parm) = t
        val tool = tools.firstOrNull { it.name == toolName } ?: return@flatMap run()
        {
            ChatMessages(
                role = Role.TOOL(id, toolName),
                content = "error: 工具$toolName 并不存在，请确认你调用的工具名称正确且存在",
            )
        }

        logger.warning("fail to put toolcall message")
        {
            onReceive(StreamAiResponseSlice.ToolCall(id, tool, parm))
        }

        val data: Any
        try
        {
            data = tool.parse(parm)
        }
        catch (e: Throwable)
        {
            return@flatMap ChatMessages(
                Role.TOOL(id, toolName),
                "错误！你调用工具 $toolName 时传入的参数格式错误，请检查参数是否符合要求，并改正错误后重试。\n具体错误为：\n${e.message}",
            )
        }

        var toolReasoning = Content()
        val content = try
        {
            logger.config("Calling tool $toolName with parameters $parm")
            @Suppress("UNCHECKED_CAST")
            (tool as AiToolInfo<Any>).invoke(data)
            {
                toolReasoning += it
                logger.warning("fail to put tool message")
                {
                    onReceive(StreamAiResponseSlice.ToolMessage(id, it))
                }
            }
        }
        catch (e: Throwable)
        {
            logger.warning("Tool call failed for $toolName with parameters $parm", e)
            return@flatMap ChatMessages(
                role = Role.TOOL(id, toolName),
                content = Content("error: \n${e.stackTraceToString()}"),
                reasoningContent = toolReasoning,
            )
        }
        val toolCall = ChatMessage(
            role = Role.TOOL(id, toolName),
            content = content.content,
            reasoningContent = toolReasoning,
        )
        val showingContents = content.showingContent.map()
        {
            ChatMessage(
                role = Role.SHOWING_DATA(it.second),
                content = Content(it.first),
                reasoningContent = toolReasoning,
            )
        }
        return@flatMap listOf(toolCall) + showingContents
    }.toChatMessages()
}