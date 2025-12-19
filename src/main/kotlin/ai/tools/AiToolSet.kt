package moe.tachyon.windwhisper.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.internal.llm.utils.aiNegotiationJson
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.utils.JsonSchema
import moe.tachyon.windwhisper.utils.generateSchema
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class AiToolSet<T>
{
    @Serializable data object EmptyToolParm
    typealias ToolGetter<T> = suspend (context: T, model: AiConfig.LlmModel?) -> List<AiToolInfo<*>>
    typealias ToolDataGetter<T> = suspend (context: T, path: String) -> ToolData?
    private val toolGetters = mutableListOf<ToolGetter<T>>()
    private val toolDataGetters = mutableMapOf<String, ToolDataGetter<T>>()
    private val logger = WindWhisperLogger.getLogger<AiToolSet<*>>()

    interface ToolProvider<in T>
    {
        val name: String
        suspend fun <S: T> AiToolSet<S>.registerTools()
    }

    companion object
    {
        suspend operator fun <T> invoke(vararg toolProvider: ToolProvider<T>) = AiToolSet<T>().apply()
        {
            toolProvider.forEach { addProvider(it) }
        }
    }

    suspend fun addProvider(provider: ToolProvider<T>) = provider.run { registerTools() }

    @Serializable
    data class ToolData(
        val type: Type,
        val value: String,
    )
    {
        @Serializable
        enum class Type
        {
            MARKDOWN,
            URL,
            TEXT,
            HTML,
            FILE,
            PAGE,
            IMAGE,
            MATH,
            VIDEO
        }
    }

    suspend fun getData(context: T, type: String, path: String): ToolData?
    {
        val getter = toolDataGetters[type]
        logger.fine("got data request: type=$type, path=$path, getter=$getter")
        return getter?.invoke(context, path)
    }

    suspend fun getTools(context: T, model: AiConfig.LlmModel?): List<AiToolInfo<*>> =
        toolGetters.flatMap { it(context, model) }

    data class ToolStatus<T, D>(val context: T, val model: AiConfig.LlmModel?, val parm: D, val sendMessage: suspend (msg: Content) -> Unit)
    {
        suspend fun sendMessage(msg: String) = sendMessage(Content(msg))
    }

    inline fun <reified D: Any> registerTool(
        name: String,
        displayName: String?,
        description: String,
        noinline condition: suspend (context: T, model: AiConfig.LlmModel?) -> Boolean = { _, _ -> true },
        noinline block: suspend ToolStatus<T, D>.() -> AiToolInfo.ToolResult
    ) = registerTool()
    { context, model ->
        if (!condition(context, model)) return@registerTool emptyList()
        val tool = AiToolInfo<D>(name, description, displayName)
        { parm, sendMessage ->
            block(ToolStatus(context, model, parm, sendMessage))
        }
        listOf(tool)
    }

    fun registerTool(getter: ToolGetter<T>)
    {
        this.toolGetters += getter
    }

    fun registerToolDataGetter(type: String, getter: ToolDataGetter<T>)
    {
        this.toolDataGetters[type] = getter
    }
}

data class AiToolInfo<T: Any>(
    val name: String,
    val description: String,
    val displayName: String?,
    val invoke: Invoker<T>,
    val dataSchema: JsonSchema,
    val type: KType,
)
{
    typealias Invoker<T> = suspend (parm: T, sendMessage: suspend (msg: Content) -> Unit) -> ToolResult
    data class ToolResult(
        val content: Content,
        val showingContent: List<Pair<String, AiToolSet.ToolData.Type>> = emptyList(),
    )
    {
        constructor(
            content: Content,
            showingContent: String,
            showingType: AiToolSet.ToolData.Type = AiToolSet.ToolData.Type.MARKDOWN
        ): this(
            content = content,
            showingContent = listOf(showingContent to showingType)
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(parm: String): T =
        aiNegotiationJson.decodeFromString(aiNegotiationJson.serializersModule.serializer(type), parm) as T

    @Suppress("UNCHECKED_CAST")
    suspend operator fun invoke(parm: String, sendMessage: suspend (msg: Content) -> Unit) =
        invoke(parse(parm), sendMessage)

    companion object
    {
        inline operator fun <reified T: Any> invoke(
            name: String,
            description: String,
            displayName: String?,
            noinline block: Invoker<T>
        ): AiToolInfo<T> = AiToolInfo(
            name = name,
            description = description,
            displayName = displayName,
            invoke = block,
            dataSchema = JsonSchema.generateSchema<T>(),
            type = typeOf<T>()
        )
    }
}