@file:Suppress("unused")

package moe.tachyon.windwhisper.ai.internal.llm

import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.ai.ChatMessage
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.TokenUsage
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.internal.llm.LlmLoopPlugin.Context

data object PluginScope
{
    /// LlmLoopPlugin
    context(c: Context) var model get() = c.model; set(value) { c.model = value }
    context(c: Context) var messages get() = c.messages; set(value) { c.messages = value }
    context(c: Context) var maxTokens get() = c.maxTokens; set(value) { c.maxTokens = value }
    context(c: Context) var temperature get() = c.temperature; set(value) { c.temperature = value }
    context(c: Context) var topP get() = c.topP; set(value) { c.topP = value }
    context(c: Context) var frequencyPenalty get() = c.frequencyPenalty; set(value) { c.frequencyPenalty = value }
    context(c: Context) var presencePenalty get() = c.presencePenalty; set(value) { c.presencePenalty = value }
    context(c: Context) var stop get() = c.stop; set(value) { c.stop = value }
    context(c: Context) var tools get() = c.tools; set(value) { c.tools = value }
    context(c: Context) val responseMessage get() = c.responseMessage
    context(c: Context) val allMessages get() = c.allMessages
    context(c: Context) fun addTokenUsage(u: TokenUsage) = c.addTokenUsage(u)
    context(c: Context) suspend fun onReceive(slice: StreamAiResponseSlice) = c.onReceive(slice)


    /// BeforeLlmRequest
    context(c: BeforeLlmRequest.BeforeRequestContext) var requestMessage get() = c.requestMessage; set(value) { c.requestMessage = value }


    // AfterLlmResponse
    context(c: RequestResult) var toolCalls : Map<String, Pair<String, String>> get() = c.toolCalls; set(value) { c.toolCalls = value }
    context(c: RequestResult) var message: ChatMessage get() = c.message; set(value) { c.message = value }
    context(c: RequestResult) var usage: TokenUsage get() = c.usage; set(value) { c.usage = value }
    context(c: RequestResult) var error: Throwable? get() = c.error; set(value) { c.error = value }
}

sealed interface LlmLoopPlugin
{
    data class Context(
        var model: AiConfig.LlmModel,
        var messages: ChatMessages,
        var maxTokens: Int?,
        var temperature: Double?,
        var topP: Double?,
        var frequencyPenalty: Double?,
        var presencePenalty: Double?,
        var stop: List<String>?,
        var tools: List<AiToolInfo<*>>,
        val responseMessage: MutableList<ChatMessage>,

        val addTokenUsage: (TokenUsage) -> Unit,
        val onReceive: suspend (StreamAiResponseSlice) -> Unit,
    )
    {
        val allMessages: ChatMessages get() = messages + responseMessage
    }
}


interface BeforeLlmRequest: LlmLoopPlugin
{
    data class BeforeRequestContext(
        var requestMessage: ChatMessages
    )

    context(_: Context, _: BeforeRequestContext)
    suspend fun PluginScope.beforeRequest()

    class Default(private val block: suspend context(Context, BeforeRequestContext) PluginScope.() -> Unit): BeforeLlmRequest
    {
        context(_: Context, _: BeforeRequestContext)
        override suspend fun PluginScope.beforeRequest() = block()
    }

    companion object
    {
        operator fun invoke(block: suspend context(Context, BeforeRequestContext) PluginScope.() -> Unit) = Default(block)
    }
}
suspend fun BeforeLlmRequest.beforeRequest(context: Context, beforeRequestContext: BeforeLlmRequest.BeforeRequestContext) = context(context, beforeRequestContext) { PluginScope.beforeRequest() }

interface BeforeLlmLoop: LlmLoopPlugin
{
    context(_: Context)
    suspend fun PluginScope.beforeLoop()

    class Default(private val block: suspend context(Context) PluginScope.() -> Unit): BeforeLlmLoop
    {
        context(_: Context)
        override suspend fun PluginScope.beforeLoop() = block()
    }

    companion object
    {
        operator fun invoke(block: suspend context(Context) PluginScope.() -> Unit) = Default(block)
    }
}
suspend fun BeforeLlmLoop.beforeLoop(context: Context) = context(context) { PluginScope.beforeLoop() }

interface AfterLlmResponse: LlmLoopPlugin
{
    context(_: Context, _: RequestResult)
    suspend fun PluginScope.afterResponse()

    class Default(private val block: suspend context(Context, RequestResult) PluginScope.() -> Unit): AfterLlmResponse
    {
        context(_: Context, _: RequestResult)
        override suspend fun PluginScope.afterResponse() = block()
    }

    companion object
    {
        operator fun invoke(block: suspend context(Context, RequestResult) PluginScope.() -> Unit) = Default(block)
    }
}
suspend fun AfterLlmResponse.afterResponse(context: Context, result: RequestResult) = context(context, result) { PluginScope.afterResponse() }