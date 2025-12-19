@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.windwhisper.ai.internal.llm.utils

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.SequenceStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.ai.AiResponseException
import moe.tachyon.windwhisper.ai.AiResponseFormatException
import moe.tachyon.windwhisper.ai.AiRetryFailedException
import moe.tachyon.windwhisper.ai.ChatMessage
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.TokenUsage
import moe.tachyon.windwhisper.ai.UnknownAiResponseException
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.internal.llm.AiResult
import moe.tachyon.windwhisper.ai.internal.llm.LlmLoopPlugin
import moe.tachyon.windwhisper.ai.internal.llm.sendAiRequest
import moe.tachyon.windwhisper.contentNegotiationJson
import kotlin.collections.plusAssign
import kotlin.reflect.KType
import kotlin.reflect.typeOf

val aiNegotiationJson = Json(contentNegotiationJson)
{
    ignoreUnknownKeys = false
    isLenient = true
    prettyPrint = true
}

val aiNegotiationYaml = Yaml(
    configuration = YamlConfiguration(
        sequenceStyle = SequenceStyle.Flow,
        strictMode = false,
        anchorsAndAliases = AnchorsAndAliases.Permitted(null),
    )
)

private val logger = WindWhisperLogger.getLogger()

interface ResultType<T>
{
    fun getValue(str: String): T

    companion object
    {
        val STRING = WrapResultType<String>()
        val BOOLEAN = WrapResultType<Boolean>()
        val INTEGER = WrapResultType<Long>()
        val FLOAT = WrapResultType<Double>()
    }

    class JsonResultType<T: Any>(private val type: KType): ResultType<T>
    {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(str: String): T =
            aiNegotiationJson.decodeFromString(aiNegotiationJson.serializersModule.serializer(type), str) as T
    }

    class YamlResultType<T: Any>(private val type: KType): ResultType<T>
    {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(str: String): T =
            aiNegotiationYaml.decodeFromString(aiNegotiationYaml.serializersModule.serializer(type), str) as T
    }

    private class WrapResultType<T: Any>(private val type: KType): ResultType<T>
    {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(str: String): T =
            aiNegotiationJson.decodeFromString(ResultWrapper.serializer(aiNegotiationJson.serializersModule.serializer(type)), str).result as T

        companion object
        {
            inline operator fun <reified T: Any> invoke(): ResultType<T> =
                WrapResultType(type = typeOf<T>())
        }
    }
}

inline fun <reified T: Any> jsonResultType(): ResultType<T>
{
    @Suppress("UNCHECKED_CAST")
    return when (T::class)
    {
        String::class               -> ResultType.STRING as ResultType<T>
        Boolean::class              -> ResultType.BOOLEAN as ResultType<T>
        Long::class, Int::class     -> ResultType.INTEGER as ResultType<T>
        Double::class, Float::class -> ResultType.FLOAT as ResultType<T>
        else                        -> ResultType.JsonResultType(typeOf<T>())
    }
}

inline fun <reified T: Any> yamlResultType(): ResultType<T> = ResultType.YamlResultType(typeOf<T>())

@Serializable private data class ResultWrapper<T>(val result: T)

/**
 * 重试方式，该方式仅决定当AI返回了内容，但AI的返回内容不符合ResultType时的处理方式
 *
 * 若AI请求失败（网络异常或AI服务返回错误），则直接重试发送请求，和该选项无关
 */
enum class RetryType
{
    /**
     * 重新发送请求
     */
    RESEND,
    /**
     * 添加一条消息，提示AI修正
     */
    ADD_MESSAGE,
}

suspend inline fun <reified T: Any> sendAiRequestAndGetResult(
    model: AiConfig.LlmModel,
    message: String,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    tools: List<AiToolInfo<*>> = emptyList(),
    plugins: List<LlmLoopPlugin> = emptyList(),
    resultType: ResultType<T> = jsonResultType<T>(),
    retryType: RetryType = RetryType.RESEND,
): Pair<Result<T>, TokenUsage> = sendAiRequestAndGetResult(
    model = model,
    messages = ChatMessages(Role.USER, message),
    temperature = temperature,
    topP = topP,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty,
    stop = stop,
    tools = tools,
    plugins = plugins,
    resultType = resultType,
    retryType = retryType,
)

suspend inline fun <reified T: Any> sendAiRequestAndGetResult(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    tools: List<AiToolInfo<*>> = emptyList(),
    plugins: List<LlmLoopPlugin> = emptyList(),
    retryType: RetryType = RetryType.RESEND,
): Pair<Result<T>, TokenUsage> = sendAiRequestAndGetResult(
    model = model,
    messages = messages,
    temperature = temperature,
    topP = topP,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty,
    stop = stop,
    tools = tools,
    plugins = plugins,
    resultType = jsonResultType<T>(),
    retryType = retryType,
)

suspend fun <T: Any> sendAiRequestAndGetResult(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    tools: List<AiToolInfo<*>> = emptyList(),
    plugins: List<LlmLoopPlugin> = emptyList(),
    resultType: ResultType<T>,
    retryType: RetryType,
): Pair<Result<T>, TokenUsage>
{
    var totalTokens = TokenUsage()
    val errors = mutableListOf<AiResponseException>()
    var messages = messages
    repeat(aiConfig.retry)
    {
        val res: ChatMessage
        try
        {
            res = withTimeout(aiConfig.timeout)
            {
                sendAiRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    topP = topP,
                    frequencyPenalty = frequencyPenalty,
                    presencePenalty = presencePenalty,
                    stop = stop,
                    tools = tools,
                    plugins = plugins,
                    stream = false,
                )
            }.let()
            {
                totalTokens += it.usage
                when (it)
                {
                    is AiResult.Cancelled       ->
                    {
                        currentCoroutineContext().ensureActive()
                        error("The ai request was cancelled, but the coroutine is still active")
                    }
                    is AiResult.ServiceError    -> error("Ai service answered an error")
                    is AiResult.Success         -> it.messages
                    is AiResult.TooManyRequests -> error("Ai service rate limit exceeded")
                    is AiResult.UnknownError    -> throw it.error
                }
            }.let()
            {
                if (it.size == 1) return@let it.first()
                errors += AiResponseFormatException(it, "AI错误的返回了多条消息")
                return@repeat
            }
        }
        catch (e: Throwable)
        {
            currentCoroutineContext().ensureActive()
            errors.add(UnknownAiResponseException(e))
            logger.config("发送AI请求失败", e)
            return@repeat
        }

        try
        {
            val content = res.content.toText().trim()
            if (content.startsWith("```") && content.endsWith("```"))
            {
                val jsonContent = content.substringAfter("\n").substringBeforeLast("```").trim()
                return Result.success(resultType.getValue(jsonContent)) to totalTokens
            }
            return Result.success(resultType.getValue(content)) to totalTokens
        }
        catch (e: Throwable)
        {
            currentCoroutineContext().ensureActive()
            val error = AiResponseFormatException(res, cause = e)
            errors.add(error)
            logger.config("检查AI响应格式失败", error)
            when (retryType)
            {
                RetryType.RESEND      -> Unit
                RetryType.ADD_MESSAGE ->
                {
                    messages += res
                    messages += ChatMessages(
                        Role.USER,
                        "你返回的内容格式不符合规定，请严格按照要求的格式返回: ${e.message}\n\n" +
                        "请你重新输出结果，注意仅输出对象，不要添加任何多余的文本或说明。并修正错误。"
                    )
                }
            }
        }
    }
    currentCoroutineContext().ensureActive()
    return Result.failure<T>(AiRetryFailedException(errors)) to totalTokens
}

suspend fun sendAiRequestAndGetReply(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    tools: List<AiToolInfo<*>> = emptyList(),
    plugins: List<LlmLoopPlugin> = emptyList(),
): Pair<String, TokenUsage>
{
    var totalTokens = TokenUsage()
    val errors = mutableListOf<AiResponseException>()
    repeat(aiConfig.retry)
    {
        val res: ChatMessage
        try
        {
            res = withTimeout(aiConfig.timeout)
            {
                sendAiRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    topP = topP,
                    frequencyPenalty = frequencyPenalty,
                    presencePenalty = presencePenalty,
                    stop = stop,
                    tools = tools,
                    plugins = plugins,
                    stream = false,
                )
            }.let()
            {
                totalTokens += it.usage
                when (it)
                {
                    is AiResult.Cancelled       ->
                    {
                        currentCoroutineContext().ensureActive()
                        error("The ai request was cancelled, but the coroutine is still active")
                    }
                    is AiResult.ServiceError    -> error("Ai service answered an error")
                    is AiResult.Success         -> it.messages
                    is AiResult.TooManyRequests -> error("Ai service rate limit exceeded")
                    is AiResult.UnknownError    -> throw it.error
                }
            }.let()
            {
                if (it.size == 1) return@let it.first()
                errors += AiResponseFormatException(it, "AI错误的返回了多条消息")
                return@repeat
            }
        }
        catch (e: Throwable)
        {
            currentCoroutineContext().ensureActive()
            errors += UnknownAiResponseException(e)
            logger.config("发送AI请求失败", e)
            return@repeat
        }

        return res.content.toText().trim() to totalTokens
    }
    currentCoroutineContext().ensureActive()
    throw AiRetryFailedException(errors)
}