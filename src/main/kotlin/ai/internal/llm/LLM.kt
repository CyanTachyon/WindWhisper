package moe.tachyon.windwhisper.ai.internal.llm

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.tools.AiToolSet
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.contentNegotiationJson
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.utils.ktorClientEngineFactory
import okhttp3.ConnectionPool
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = WindWhisperLogger.getLogger()

private val streamAiClient = HttpClient(ktorClientEngineFactory)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        config {
            callTimeout(0, TimeUnit.MILLISECONDS)
            retryOnConnectionFailure(false)
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
            retryOnConnectionFailure(false)
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

/** Continue a server-managed response chain with only the new user/tool input. */
suspend fun sendAiRequest(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    instructions: String = "",
    tools: List<AiToolInfo<*>> = emptyList(),
    session: ResponsesSession = ResponsesSession(),
    turnId: String = java.util.UUID.randomUUID().toString(),
    stream: Boolean = true,
    onGeneratedImage: (suspend (GeneratedImage) -> String)? = null,
    onReceive: suspend (StreamAiResponseSlice) -> Unit = {},
): AiResult = session.mutex.withLock {
    model.semaphore.withPermit {
        val res = mutableListOf<ChatMessage>()
        var usage = TokenUsage()
        try
        {
            if (!session.begin(turnId, messages.toResponsesInput()))
                return@withPermit AiResult.Success(ChatMessages.empty(), usage)
            // A crash between a forum write and its checkpoint is ambiguous. Do not replay it.
            check(session.state.turn!!.executingToolId == null) {
                "A tool was interrupted before its result was saved (${session.state.turn!!.executingToolId}). " +
                    "Check the forum and session checkpoint before resuming; the tool will not be replayed automatically."
            }
            while (true)
            {
                currentCoroutineContext().ensureActive()
                val turn = session.state.turn!!
                val body = buildResponsesRequest(model, turn.input, instructions, session.state.previousResponseId, tools, stream)
                val errors = mutableListOf<Throwable>()
                var result: RequestResult? = null
                for (attempt in 0 until aiConfig.retry)
                {
                    val pendingId = session.state.turn!!.responseId
                    val requestDescription = "${if (pendingId != null) "GET" else "POST"} " +
                        "turn=${safeLogValue(turnId)} attempt=${attempt + 1}/${aiConfig.retry} model=${safeLogValue(model.model)}"
                    logger.info("Responses API request started: $requestDescription")
                    val received = withResponsesWaitingLog(requestDescription) {
                        if (pendingId != null) retrieveResponse(model, pendingId)
                        else sendResponsesRequest(model.url, model.key.first(), body, stream, onReceive) { id ->
                            session.update(session.state.copy(turn = session.state.turn!!.copy(responseId = id)))
                            logger.info("Responses API response created: $requestDescription response=${safeLogValue(id)}")
                        }
                    }
                    if (received.error is CancellationException)
                    {
                        logger.info("Responses API request cancelled: $requestDescription")
                        throw received.error!!
                    }
                    if (received.error == null)
                    {
                        logger.info("Responses API request completed: $requestDescription response=${safeLogValue(received.responseId.orEmpty())}")
                        result = received
                        break
                    }
                    logger.warning("Responses API request failed: $requestDescription reason=${safeRequestFailure(received.error!!)}")
                    errors += received.error!!
                    if (received.responseStatus in setOf("failed", "incomplete", "cancelled") &&
                        session.state.turn!!.toolResults.isEmpty() && session.state.turn!!.imageNotices.isEmpty())
                    {
                        // This response produced no application side effects. Retry the same
                        // pending input from the last completed response, not its failed branch.
                        session.update(session.state.copy(turn = session.state.turn!!.copy(responseId = null)))
                        throw received.error!!
                    }
                    if (pendingId != null || session.state.turn!!.responseId != null || !received.message.isBlank())
                        throw received.error!!
                    // Invalid requests and missing server state cannot be repaired by repeating.
                    if (received.error!!.httpStatus() in 400..499 && received.error!!.httpStatus() != 429)
                        throw received.error!!
                    if (attempt + 1 < aiConfig.retry)
                        logger.info("Responses API retry scheduled: turn=${safeLogValue(turnId)} nextAttempt=${attempt + 2}/${aiConfig.retry}")
                }
                val completed = result ?: throw AiRetryFailedException(errors.map(::UnknownAiResponseException))
                val responseId = completed.responseId?.takeIf { it.isNotBlank() }
                    ?: error("Responses API completed response is missing its id")
                val pendingId = session.state.turn!!.responseId
                check(pendingId == null || pendingId == responseId) { "Responses API returned an unexpected response id" }
                session.update(session.state.copy(turn = session.state.turn!!.copy(responseId = responseId)))
                usage += completed.usage
                if (!completed.message.isBlank()) res += completed.message

                for (image in completed.generatedImages)
                {
                    if (image.id in session.state.turn!!.imageNotices) continue
                    val saveImage = onGeneratedImage ?: error("No generated-image handler is configured")
                    val notice = saveImage(image)
                    session.update(session.state.copy(turn = session.state.turn!!.copy(
                        imageNotices = session.state.turn!!.imageNotices + (image.id to notice))))
                }
                for ((id, call) in completed.toolCalls)
                {
                    if (id in session.state.turn!!.toolResults) continue
                    session.update(session.state.copy(turn = session.state.turn!!.copy(executingToolId = id)))
                    val outputs = parseToolCalls(mapOf(id to call), tools, onReceive)
                    val wireOutput = outputs.toResponsesInput().single()
                    session.update(session.state.copy(turn = session.state.turn!!.copy(
                        toolResults = session.state.turn!!.toolResults + (id to wireOutput), executingToolId = null)))
                    res += outputs
                    outputs.filter { it.role is Role.SHOWING_DATA }.forEach {
                        onReceive(StreamAiResponseSlice.ShowingTool(it.content.toText(), (it.role as Role.SHOWING_DATA).type))
                    }
                }
                val pending = session.state.turn!!
                val nextInput = completed.toolCalls.keys.map { pending.toolResults.getValue(it) }.toMutableList()
                if (pending.imageNotices.isNotEmpty()) nextInput += buildJsonObject {
                    put("role", "developer")
                    put("content", pending.imageNotices.values.joinToString("\n") +
                        "\nUse upload_generated_image and send_post if an image should be posted. These are application asset receipts, not a new user request.")
                }
                if (nextInput.isEmpty())
                {
                    session.finish(responseId)
                    break
                }
                session.update(session.state.copy(previousResponseId = responseId,
                    turn = PendingTurn(turnId, nextInput)))
            }
            AiResult.Success(res.toChatMessages(), usage)
        }
        catch (error: Throwable)
        {
            when
            {
                error is CancellationException -> AiResult.Cancelled(res.toChatMessages(), usage)
                error.httpStatus() == 429 -> AiResult.TooManyRequests(res.toChatMessages(), usage)
                error.httpStatus() in listOf(500, 502, 503, 504) -> AiResult.ServiceError(res.toChatMessages(), usage)
                else -> AiResult.UnknownError(res.toChatMessages(), usage, error)
            }
        }
    }
}

data class RequestResult(
    var toolCalls: Map<String, Pair<String, String>>,
    var message: ChatMessage,
    var usage: TokenUsage,
    var error: Throwable?,
    val responseId: String? = null,
    val generatedImages: List<GeneratedImage> = emptyList(),
    val responseStatus: String? = null,
)

private class AiHttpException(val status: Int, body: String): Exception("Responses API HTTP $status: $body")

private fun Throwable.httpStatus(): Int? = when (this)
{
    is AiHttpException -> status
    is SSEClientException -> response?.status?.value
    is AiRetryFailedException -> exceptions.lastOrNull()?.httpStatus()
    else -> cause?.takeUnless { it === this }?.httpStatus()
}

internal class ResponsesStreamIdleTimeoutException(val timeoutMillis: Long): IOException(
    "Responses API stream received no event for $timeoutMillis ms (including response headers)")

private class ResponsesRequestTimeoutException(val timeoutMillis: Long): IOException(
    "Responses API request exceeded its $timeoutMillis ms timeout")

/** The deadline starts before connecting and resets for every SSE event, including non-text events. */
internal suspend fun <T> withResponsesStreamIdleTimeout(
    timeoutMillis: Long,
    block: suspend (activity: () -> Unit) -> T,
): T = coroutineScope {
    require(timeoutMillis > 0) { "Responses API idle timeout must be positive" }
    val activity = Channel<Unit>(Channel.CONFLATED)
    val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
        while (withTimeoutOrNull(timeoutMillis) { activity.receive(); true } == true) { }
        // A normal exception cancels the in-flight HTTP call but is not mistaken for user cancellation.
        throw ResponsesStreamIdleTimeoutException(timeoutMillis)
    }
    try { block { activity.trySend(Unit) } }
    finally
    {
        watchdog.cancel()
        activity.close()
    }
}

private suspend fun <T> withResponsesRequestTimeout(timeoutMillis: Long, block: suspend () -> T): T
{
    val result = withTimeoutOrNull(timeoutMillis) { Result.success(block()) }
        ?: throw ResponsesRequestTimeoutException(timeoutMillis)
    return result.getOrThrow()
}

private suspend fun <T> withResponsesWaitingLog(description: String, block: suspend () -> T): T = coroutineScope {
    val started = System.nanoTime()
    val reporter = launch {
        while (isActive)
        {
            delay(30_000)
            val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
            logger.info("Responses API waiting: $description elapsed=${elapsedSeconds}s")
        }
    }
    try { block() }
    finally { reporter.cancel() }
}

private fun safeLogValue(value: String): String = value.replace(Regex("[^A-Za-z0-9_.:/-]"), "_").take(120)

private fun safeRequestFailure(error: Throwable): String = when (error)
{
    is ResponsesStreamIdleTimeoutException -> "no SSE event for ${error.timeoutMillis} ms"
    is ResponsesRequestTimeoutException -> "request timeout after ${error.timeoutMillis} ms"
    else -> error.httpStatus()?.let { "HTTP $it" } ?: error.javaClass.simpleName
}

private suspend fun retrieveResponse(model: AiConfig.LlmModel, id: String): RequestResult = try
{
    require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid saved response ID" }
    val response = withResponsesRequestTimeout(aiConfig.timeout) {
        defaultAiClient.get("${model.url.trimEnd('/')}/$id") { bearerAuth(model.key.first()) }
            .let { it.status to it.bodyAsText() }
    }
    if (!response.first.isSuccess()) throw AiHttpException(response.first.value, response.second)
    parseResponsesResponse(contentNegotiationJson.parseToJsonElement(response.second).jsonObject)
}
catch (error: Throwable) { RequestResult(emptyMap(), ChatMessage(Role.ASSISTANT, ""), TokenUsage(), error) }

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun sendResponsesRequest(
    url: String,
    key: String,
    body: JsonObject,
    stream: Boolean,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
    timeoutMillis: Long = aiConfig.timeout,
    onResponseCreated: (String) -> Unit,
): RequestResult
{
    val state = ResponsesStreamState(bufferTextUntilCompleted =
        (body["tools"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.get("type") == JsonPrimitive("web_search") })
    return try
    {
        if (!stream)
        {
            val response = withResponsesRequestTimeout(timeoutMillis) {
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
            var requestJob: Job? = null
            try
            {
                withResponsesStreamIdleTimeout(timeoutMillis) { activity ->
                    streamAiClient.sse(url, {
                        // Ktor starts the SSE handshake in HttpClient's scope. Cancelling only
                        // its caller can leave that detached request waiting for headers.
                        requestJob = executionContext
                        method = HttpMethod.Post
                        bearerAuth(key)
                        contentType(ContentType.Application.Json)
                        accept(ContentType.Text.EventStream)
                        setBody(contentNegotiationJson.encodeToString(body))
                    }) {
                        if (!call.response.status.isSuccess())
                            throw AiHttpException(call.response.status.value, call.response.bodyAsText())
                        incoming.transformWhile { event ->
                            activity()
                            val data = event.data
                            if (!data.isNullOrBlank() && data.trim() != "[DONE]")
                            {
                                val parsed = contentNegotiationJson.parseToJsonElement(data).jsonObject
                                if (parsed["type"] == JsonPrimitive("response.created"))
                                    (parsed["response"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                                        ?.takeIf { it.isNotBlank() }?.let(onResponseCreated)
                                state.accept(parsed, onReceive)
                            }
                            emit(Unit)
                            !state.isTerminal
                        }.collect { }
                    }
                }
            }
            finally { requestJob?.cancel() }
            state.result()
        }
    }
    catch (e: Throwable)
    {
        state.result(e)
    }
}

suspend fun parseToolCalls(
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
            if (e is CancellationException || e is UncertainToolExecutionException) throw e
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
