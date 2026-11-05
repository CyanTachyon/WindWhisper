@file:Suppress("unused")

package moe.tachyon.windwhisper.ai.internal.llm

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.transformWhile
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
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
import kotlin.collections.set

@Serializable
private sealed interface AiResponse
{
    val usage: TokenUsage?
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
                val function: Function? = null,
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
    override val choices: List<Choice>,
    override val usage: TokenUsage? = null,
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
            override val content: String? = null,
            @SerialName("reasoning_content")
            override val reasoningContent: String? = null,
            @SerialName("tool_calls")
            override val toolCalls: List<AiResponse.Choice.Message.ToolCall>? = null,
        ): AiResponse.Choice.Message
    }
}

@Serializable
private data class StreamAiResponse(
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
            @SerialName("reasoning_text")
            val reasoningContent2: String? = null,
            @SerialName("tool_calls")
            override val toolCalls: List<AiResponse.Choice.Message.ToolCall>? = null,
        ): AiResponse.Choice.Message
        {
            override val reasoningContent: String?
                get() = reasoningContent0 ?: reasoningContent1 ?: reasoningContent2
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

private fun ChatMessages.toRequestMessages(): List<AiRequest.Message>
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
            role = this.role.role,
            content = this.content,
            reasoningContent = this.reasoningContent.takeIf { it.isNotEmpty() && this.role is Role.ASSISTANT } ?: Content(),
            toolCallId = (this.role as? Role.TOOL)?.id,
            toolCalls = this.toolCalls.map { AiRequest.Message.ToolCall(it.id, function = AiRequest.Message.ToolCall.Function(it.name, it.arguments)) },
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
        config {
            callTimeout(0, TimeUnit.MILLISECONDS)
            connectTimeout(aiConfig.timeout.coerceAtMost(Int.MAX_VALUE.toLong()), TimeUnit.MILLISECONDS)
            connectionPool(ConnectionPool(5, aiConfig.timeout, TimeUnit.MILLISECONDS))
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
        config {
            callTimeout(0, TimeUnit.MILLISECONDS)
            connectTimeout(aiConfig.timeout.coerceAtMost(Int.MAX_VALUE.toLong()), TimeUnit.MILLISECONDS)
            connectionPool(ConnectionPool(5, aiConfig.timeout, TimeUnit.MILLISECONDS))
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
            val body = if (context.model.apiType == AiConfig.ApiType.RESPONSES)
                buildResponsesRequest(context, beforeLlmRequestContext.requestMessage, stream)
            else AiRequest(
                model = context.model.model,
                messages = beforeLlmRequestContext.requestMessage.toRequestMessages(),
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
                var lastResult: RequestResult? = null
                repeat(aiConfig.retry)
                {
                    currentCoroutineContext().ensureActive()
                    val tmp = if (context.model.apiType == AiConfig.ApiType.RESPONSES)
                        sendResponsesRequest(url, context.model.key.random(), body, stream, onReceive)
                    else sendRequest(url, context.model.key.random(), body, stream, onReceive)
                    if (tmp.error is CancellationException) throw tmp.error!!
                    if (tmp.error == null && !tmp.message.isBlank()) return@run tmp
                    if (tmp.error == null) tmp.error = UnknownAiResponseException(message = "AI请求返回空响应")
                    tmp.error?.let(errors::add)
                    lastResult = tmp
                    // A partially delivered response must not be replayed into the UI or execute tools.
                    if (!tmp.message.isBlank()) return@run tmp
                }
                lastResult!!.also {
                    it.error = if (errors.size == 1) errors[0]
                    else errors.map(::UnknownAiResponseException).let(::AiRetryFailedException)
                }
            }

            plugins.filterIsInstance<AfterLlmResponse>().forEach()
            {
                it.afterResponse(context, requestResult)
            }

            val (waitingTools, curMsg, usage0, e) = requestResult
            if (!curMsg.isBlank()) res += curMsg
            usage += usage0
            if (e != null) throw e
            if (waitingTools.isNotEmpty())
            {
                val parseToolCalls = parseToolCalls(waitingTools, context.tools, onReceive)
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
        return when
        {
            e is CancellationException -> AiResult.Cancelled(res.toChatMessages(), usage)
            e.httpStatus() == 429 -> AiResult.TooManyRequests(res.toChatMessages(), usage)
            e.httpStatus() in listOf(500, 502, 503, 504) -> AiResult.ServiceError(res.toChatMessages(), usage)
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

private class AiHttpException(val status: Int, body: String): Exception("AI请求返回异常，HTTP状态码 $status, 响应体: $body")

private fun Throwable.httpStatus(): Int? = when (this)
{
    is AiHttpException -> status
    is SSEClientException -> response?.status?.value
    is AiRetryFailedException -> exceptions.lastOrNull()?.httpStatus()
    else -> cause?.takeUnless { it === this }?.httpStatus()
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun sendResponsesRequest(
    url: String,
    key: String,
    body: JsonObject,
    stream: Boolean,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
): RequestResult
{
    logger.config("Responses API请求$url : ${contentNegotiationJson.encodeToString(body)}")
    val state = ResponsesStreamState(bufferTextUntilCompleted =
        (body["tools"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.isResponsesWebSearchTool() == true })
    return try
    {
        if (!stream)
        {
            val response = withTimeout(aiConfig.timeout) {
                defaultAiClient.post(url) {
                    bearerAuth(key)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(body)
                }.let { it.status to it.bodyAsText() }
            }
            if (!response.first.isSuccess()) throw AiHttpException(response.first.value, response.second)
            parseResponsesResponse(contentNegotiationJson.parseToJsonElement(response.second).jsonObject)
        }
        else
        {
            streamAiClient.sse(url, {
                method = HttpMethod.Post
                bearerAuth(key)
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(contentNegotiationJson.encodeToString(body))
            }) {
                if (!call.response.status.isSuccess())
                    throw AiHttpException(call.response.status.value, call.response.bodyAsText())
                incoming.transformWhile { event ->
                    val data = event.data
                    if (!data.isNullOrBlank() && data.trim() != "[DONE]")
                    {
                        val parsed = contentNegotiationJson.parseToJsonElement(data).jsonObject
                        state.accept(parsed, onReceive)
                    }
                    emit(Unit)
                    !state.isTerminal
                }.collect { }
            }
            state.result()
        }
    }
    catch (e: Throwable)
    {
        state.result(e)
    }
}

private fun checkChatFinishReason(reason: AiResponse.FinishReason?)
{
    if (reason != null && reason != AiResponse.FinishReason.STOP && reason != AiResponse.FinishReason.TOOL_CALLS)
        throw UnknownAiResponseException(message = "Chat Completions 响应未完成: $reason")
}

private fun validatedChatToolCalls(calls: List<AiResponse.Choice.Message.ToolCall>): Map<String, Pair<String, String>>
{
    val result = linkedMapOf<String, Pair<String, String>>()
    calls.forEach { call ->
        val id = call.id
        val name = call.function?.name
        val arguments = call.function?.arguments
        require(call.type == "function") { "Chat Completions 返回了不支持的工具调用类型" }
        require(!id.isNullOrBlank()) { "Chat Completions 工具调用缺少 id" }
        require(!name.isNullOrBlank()) { "Chat Completions 工具调用缺少 function.name" }
        require(arguments != null) { "Chat Completions 工具调用缺少 function.arguments" }
        require(id !in result) { "Chat Completions 工具调用包含重复 id" }
        result[id] = name to arguments
    }
    return result
}

private class PendingChatToolCall
{
    var id: String? = null
    val name = StringBuilder()
    val arguments = StringBuilder()
    var hasArguments = false
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun sendRequest(
    url: String,
    key: String,
    body: JsonElement,
    stream: Boolean,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
): RequestResult
{
    logger.config("AI请求$url : ${contentNegotiationJson.encodeToString(body)}")
    var usage = TokenUsage()
    var message = ChatMessage(Role.ASSISTANT, "")

    if (!stream) return try
    {
        val response = defaultAiClient.post(url)
        {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(body)
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) throw AiHttpException(response.status.value, responseBody)
        val responseData = contentNegotiationJson.decodeFromString<DefaultAiResponse>(responseBody)
        usage = responseData.usage ?: TokenUsage()
        message = ChatMessage(
            role = Role.ASSISTANT,
            content = Content(responseData.choices.joinToString("") { it.message.content.orEmpty() }),
            reasoningContent = Content(responseData.choices.joinToString("") { it.message.reasoningContent.orEmpty() }),
        )
        responseData.choices.forEach { checkChatFinishReason(it.finishReason) }
        val calls = validatedChatToolCalls(responseData.choices.flatMap { it.message.toolCalls.orEmpty() })
        message = message.copy(toolCalls = calls.map { (id, call) -> ChatMessage.ToolCall(id, call.first, call.second) })
        RequestResult(calls, message, usage, null)
    }
    catch (error: Throwable)
    {
        RequestResult(emptyMap(), message, usage, error)
    }

    // Each choice has its own tool indexes. Never infer a missing index or manufacture call IDs.
    val waitingTools = mutableMapOf<Pair<Int, Int>, PendingChatToolCall>()
    var done = false
    return try
    {
        streamAiClient.sse(url, {
            method = HttpMethod.Post
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(contentNegotiationJson.encodeToString(body))
        })
        {
            if (!call.response.status.isSuccess())
                throw AiHttpException(call.response.status.value, call.response.bodyAsText())
            incoming.transformWhile { event ->
                val data = event.data
                if (data?.trim() == "[DONE]")
                {
                    done = true
                    false
                }
                else
                {
                    if (!data.isNullOrBlank())
                    {
                        val chunk = contentNegotiationJson.decodeFromString<StreamAiResponse>(data)
                        chunk.usage?.let { usage = it }
                        chunk.choices.forEach { choice ->
                            val content = choice.message.content.orEmpty()
                            val reasoning = choice.message.reasoningContent.orEmpty()
                            if (content.isNotEmpty() || reasoning.isNotEmpty())
                            {
                                message += ChatMessage(Role.ASSISTANT, content, reasoning)
                                onReceive(StreamAiResponseSlice.Message(content, reasoning))
                            }
                            choice.message.toolCalls.orEmpty().forEach { tool ->
                                val index = tool.index
                                require(index != null && index >= 0) { "Chat Completions 工具调用增量缺少有效 index" }
                                require(tool.type == null || tool.type == "function") { "Chat Completions 返回了不支持的工具调用类型" }
                                val pending = waitingTools.getOrPut((choice.index ?: 0) to index) { PendingChatToolCall() }
                                tool.id?.let { id ->
                                    require(id.isNotBlank() && (pending.id == null || pending.id == id)) {
                                        "Chat Completions 工具调用增量 id 无效或不一致"
                                    }
                                    pending.id = id
                                }
                                tool.function?.name?.let { pending.name.append(it) }
                                tool.function?.arguments?.let { arguments ->
                                    pending.arguments.append(arguments)
                                    pending.hasArguments = true
                                }
                            }
                            checkChatFinishReason(choice.finishReason)
                        }
                    }
                    emit(Unit)
                    true
                }
            }.collect { }
        }
        if (!done) throw UnknownAiResponseException(message = "Chat Completions 数据流缺少 [DONE]，响应可能已中断")
        val calls = validatedChatToolCalls(waitingTools.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (_, pending) ->
            AiResponse.Choice.Message.ToolCall(
                id = pending.id,
                function = AiResponse.Choice.Message.ToolCall.Function(
                    name = pending.name.toString(),
                    arguments = pending.arguments.toString().takeIf { pending.hasArguments },
                ),
            )
        })
        message = message.copy(toolCalls = calls.map { (id, call) -> ChatMessage.ToolCall(id, call.first, call.second) })
        RequestResult(calls, message, usage, null)
    }
    catch (error: Throwable)
    {
        RequestResult(emptyMap(), message, usage, error)
    }
}

private suspend fun parseToolCalls(
    waitingTools: Map<String, Pair<String, String>>,
    tools: List<AiToolInfo<*>>,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
): ChatMessages
{
    require(waitingTools.keys.all { it.isNotBlank() }) { "工具调用必须包含有效 id" }
    return waitingTools.flatMap()
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
