@file:Suppress("unused")

package moe.tachyon.windwhisper.ai.plugins

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.internal.llm.BeforeLlmRequest
import moe.tachyon.windwhisper.ai.internal.llm.utils.ResultType
import moe.tachyon.windwhisper.ai.internal.llm.utils.RetryType
import moe.tachyon.windwhisper.ai.internal.llm.utils.sendAiRequestAndGetResult
import moe.tachyon.windwhisper.ai.internal.llm.utils.yamlResultType
import moe.tachyon.windwhisper.ai.ChatMessage
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.TokenUsage
import moe.tachyon.windwhisper.ai.plugins.ContextCompressor.Companion.MARKING_TYPE
import moe.tachyon.windwhisper.dataJson
import moe.tachyon.windwhisper.showJson
import moe.tachyon.windwhisper.utils.JsonSchema
import kotlin.math.max
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 上下文压缩器
 */
interface ContextCompressor
{
    suspend fun shouldCompress(messages: ChatMessages): Boolean
    suspend fun compress(messages: ChatMessages): Pair<ChatMessages, TokenUsage>

    companion object
    {
        const val MARKING_TYPE = "CONTEXT_COMPRESSION"
    }
}

// 一个tool，仅作为占位，表示上下文压缩
private val tool = AiToolInfo<Any>(
    MARKING_TYPE,
    "",
    "上下文压缩",
    { _, _ -> error("not callable") },
    JsonSchema.Null(),
    typeOf<Nothing?>()
)

@OptIn(ExperimentalUuidApi::class)
fun ContextCompressor.toLlmPlugin(): BeforeLlmRequest = BeforeLlmRequest()
{
    val index = requestMessage.indexOfLast { it.role is Role.MARKING && it.role.type == MARKING_TYPE }
    val uncompressed =
        (if (index == -1) requestMessage
        else
        {
            val com = dataJson.decodeFromString<ChatMessages>(requestMessage[index].content.toText())
            com + requestMessage.subList(index + 1, requestMessage.size)
        }).toChatMessages()
    if (shouldCompress(uncompressed))
    {
        onReceive(StreamAiResponseSlice.ToolCall(MARKING_TYPE + "-" + Uuid.random().toHexString(), tool, ""))
        val compressed = compress(uncompressed)
        addTokenUsage(compressed.second)
        responseMessage += ChatMessage(
            role = Role.MARKING(MARKING_TYPE, JsonNull),
            content = dataJson.encodeToString(compressed.first)
        )
        requestMessage = compressed.first
    }
    else requestMessage = uncompressed
}

/**
 * 跳跃式压缩
 * 每隔 step 条消息保留一条，最多保留 maxSize 条消息
 */
class JumpContextCompressor(val step: Int = 2, val maxSize: Int = 20): ContextCompressor
{
    override suspend fun shouldCompress(messages: ChatMessages) =
        messages.size > maxSize
    override suspend fun compress(messages: ChatMessages): Pair<ChatMessages, TokenUsage>
    {
        if (messages.size <= maxSize) return messages to TokenUsage()
        val res = mutableListOf<ChatMessage>()
        var i = 0
        while (i <= messages.lastIndex)
        {
            res.add(messages[i])
            i += max(step, messages.size / maxSize)
        }
        return res.toChatMessages() to TokenUsage()
    }
}

/**
 * 保留最后的 maxSize 条消息
 */
class TailContextCompressor(val maxSize: Int = 20): ContextCompressor
{
    override suspend fun shouldCompress(messages: ChatMessages): Boolean =
        messages.size > maxSize
    override suspend fun compress(messages: ChatMessages): Pair<ChatMessages, TokenUsage>
    {
        if (messages.size <= maxSize) return messages to TokenUsage()
        return messages.takeLast(maxSize).toChatMessages() to TokenUsage()
    }
}

/**
 * 小模型上下文压缩器
 * 用小模型对上下文进行总结，保留关键信息
 *
 * 该压缩器会自动跳过（保留）位于最开始的若干个System消息以及设置的keepTail消息。支持设置压缩率
 *
 * @param model 小模型
 * @param compressLength 当待压缩的内容字数超过该值时才会进行压缩，默认10240
 * @param keepTail 保留最后的消息数
 * @param compressingRate 压缩率，0~1之间，表示压缩后消息数与压缩前消息数的比例，默认2/3
 */
class AiContextCompressor(
    val model: AiConfig.LlmModel,
    val compressLength: Int = 10240,
    val keepTail: Int = 5,
    val compressingRate: Double = 2.0/3,
): ContextCompressor
{
    companion object
    {
        private val logger = WindWhisperLogger.getLogger<AiContextCompressor>()
    }
    init
    {
        if (keepTail < 0) error("keepTail must be non-negative")
    }

    override suspend fun shouldCompress(messages: ChatMessages): Boolean =
        messages.subList(0, needCompress(messages)).sumOf()
        {
            it.content.toText().length + it.toolCalls.sumOf { t -> t.name.length + t.arguments.length + 20 }
        } > compressLength

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class Compress(
        val role: String,
        val content: Content,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val toolCall: ToolCall? = null,
    )
    @Serializable
    private data class ToolCall(
        val name: String,
        val arguments: String,
    )

    @OptIn(ExperimentalUuidApi::class)
    private fun List<Compress>.toChatMessages(): ChatMessages
    {
        val res = mutableListOf<ChatMessage>()
        var id: String? = null
        var toolName = ""
        for (c in this)
        {
            if (c.role != Role.TOOL.role && id != null)
                error("an assistant message with toolCall must be followed by a tool message")
            val toolCalls =
                if (c.toolCall == null) emptyList()
                else
                {
                    id = Uuid.random().toHexString()
                    toolName = c.toolCall.name
                    listOf(ChatMessage.ToolCall(id, c.toolCall.name, c.toolCall.arguments))
                }
            when (c.role)
            {
                Role.SYSTEM.role       -> Unit
                Role.MARKING.role      -> Unit
                Role.SHOWING_DATA.role -> Unit
                Role.USER.role         -> res.add(ChatMessage(Role.USER, content = c.content))
                Role.ASSISTANT.role    -> res.add(
                    ChatMessage(
                        Role.ASSISTANT,
                        content = c.content,
                        toolCalls = toolCalls
                    )
                )
                Role.TOOL.role         ->
                {
                    id ?: error("tool message must follow an assistant message with toolCall")
                    res.add(ChatMessage(Role.TOOL(id, toolName), content = c.content))
                    id = null
                }
            }
        }
        return res.toChatMessages()
    }

    private fun makePrompt(messages: ChatMessages): String
    {
        val prompt = StringBuilder()
        prompt.append("""
            # 核心任务
            你现在是一个聊天记录压缩助手，你的任务是将用户和大模型的聊天记录进行压缩，保留关键信息，删除冗余信息。
            你需要用尽可能少的文字来表达尽可能多的信息。
            
            输入聊天记录的格式为（一条消息）：
            ```json
            {
                "role": "user|assistant|tool",
                "content": "消息内容，（若为tool则为工具调用结果）",
                "toolCall": {
                    "name": "工具名称",
                    "arguments": "工具参数"
                }
            }
            ```
            - role 字段表示消息的角色，可以是 user（用户），assistant（大模型），tool（工具调用结果）
            - content 字段表示消息的内容
            - toolCalls 字段表示消息中包含的工具调用信息，只有当role为 assistant ，且调用了工具时才会有该字段
            
            你现在需要对输入的若干条消息进行压缩，输出格式与输入格式必须相同。
            
            # 注意事项
            - 对于工具的调用结果，你可以视情况保留、总结、压缩。例如若调用了搜索资料的工具，且结果较多，你可以对搜索结果总结，并替换掉原始的tool调用结果。
            - 若有多个连续的工具调用，且工具调用结果可以合并，你可以将多个工具调用合并为一个。
            - 若有一些无效的工具调用，可以直接不保留，例如模型调用了一个搜索工具，但没有搜索到有效信息，或工具报错。
            - 工具的名称非常重要，你可以直接去除工具调用，但若你决定保留工具调用，那你必须保证工具名称不变。
            - 若用户的输入包括图片，**必须**保留图片的url。但你可以将用户在多条聊天记录中上传的图片放在一条消息中，例如用户发了三条消息，每条消息都包含一张图片，你可以将这三张图片的url放在一条消息中。
            - 只有一条角色为assistant且有toolCall的消息后，后面才允许出现，且必须出现一条role为tool的消息，表示工具调用结果，不可出现一条role为tool的消息前不是一个带有toolCall的assistant消息，或带有toolCall的assistant消息后没有紧跟一条tool消息。
            - 一个user消息后，必须紧跟一条assistant消息。
            - 一个assistant消息后，要么紧跟一条user消息，要么紧跟一条tool消息。
            - tool消息后，一定紧跟一条assistant消息。
            - 你需要注意**绝对不能出现违背上述规则的消息**，例如连续两条assistant消息，或连续两条tool消息，或tool消息后没有紧跟assistant消息等。
            
            # 一些输入输出示例
            
            ## 示例
            ### 输入
            ```json
            [
                {
                    "role": "user",
                    "content": "请帮我制作一个PPT"
                },
                {
                    "role": "assistant",
                    "content": "好的，请问你需要什么主题的PPT？"
                },
                {
                    "role": "user",
                    "content": "关于人工智能的介绍"
                },
                {
                    "role": "assistant",
                    "content": "好的，请稍等，我来帮你制作一个关于人工智能的PPT",
                    "toolCall": {
                        "name": "web_search",
                        "arguments": "{\"key\":\"人工智能介绍\",\"count\":5}"
                    }
                },
                {
                    "role": "tool",
                    "content": "搜索结果1：...\n搜索结果2：...\n搜索结果3：...\n搜索结果4：...\n搜索结果5：..."
                },
                {
                    "role": "assistant",
                    "content": "让我进一步提取这些搜索结果的内容",
                    "toolCall": {
                        "name": "web_extract",
                        "arguments": "{\"url\":\"搜索结果1的url\"}"
                    }
                },
                {
                    "role": "tool",
                    "content": "提取内容：..."
                },
                {
                    "role": "assistant",
                    "content": "我将根据搜索结果为你制作PPT，请稍等",
                    "toolCall": {
                        "name": "create_ppt",
                        "arguments": "..."
                    }
                }
                { 
                    "role": "tool",
                    "content": "PPT制作完成，已展示给用户"
                },
                {
                    "role": "assistant",
                    "content": "PPT已经制作完成！若需要进一步修改，请告诉我！"
                }
            ]
            ```
            ### 输出
            ```yaml
            - role: user
              content: 请帮我制作一个关于人工智能的PPT
            - role: assistant
              content: 我将先收集资料
              toolCall: 
                name: web_search
                arguments: |
                {
                    "key":"人工智能介绍",
                    "count":5
                }
            - role: tool
              content: 这里你可以把搜索结果进行总结，把web_search和web_extract的结果合并为在这里，因为这样更简洁且与原先效果基本一致
            - role: assistant
              content: 我将根据资料为你制作PPT，请稍等
              toolCall:
                name: create_ppt
                arguments: |
                {
                    "content": "......"
                }
            - role: tool
              content: PPT制作完成，已展示给用户
            - role: assistant
              content: PPT已经制作完成！若需要进一步修改，请告诉我！
            ```
            以上就是一个简单的压缩示例，你需要根据实际输入的消息进行类似的压缩。
            简而言之，你需要用一个简短的聊天记录，来替换原聊天记录，使得短的聊天记录基本包含了长聊天记录的主要信息。
            你可以通过
            - 将多条对话合并为少数几条对话
            - 将多条工具调用合并为少数几条工具调用
            - 总结工具调用结果，替换掉冗长的工具调用结果
            等方式来达到压缩的目的。
            接下来，请你压缩下面的聊天记录，你需要将他们压缩到${max(2, (messages.sumOf { it.content.toText().length } * compressingRate).toInt())}字以内。
            注意你的回复必须是一个合法的yaml数组，格式如上文所示。善用yaml多行字符串来避免转义。
            
            ### 输入
            ```json
            
        """.trimIndent())
        val c = messages.mapNotNull()
        {
            val toolCall = if (it.toolCalls.isEmpty()) null
            else ToolCall(it.toolCalls[0].name, it.toolCalls[0].arguments)
            when (it.role)
            {
                is Role.USER,
                is Role.ASSISTANT,
                is Role.TOOL         -> Compress(it.role.role, it.content, toolCall)

                is Role.SYSTEM,
                is Role.MARKING,
                is Role.SHOWING_DATA -> null
            }
        }
        prompt.append(showJson.encodeToString(c))
        prompt.append("\n```\n")
        prompt.append("""
            现在请你按照上述要求，对这${messages.size}条消息进行压缩，输出格式与输入格式完全相同，请注意不要遗漏任何关键信息。
            **注意**: 你必须直接输出一个yaml数组，不能包含任何其他内容，且必须是合法的yaml格式，注意检查引号、逗号、括号等符号。
        """.trimIndent())
        return prompt.toString()
    }

    fun needCompress(messages: ChatMessages): Int
    {
        if (messages.size <= keepTail) return 0
        var splitIndex = messages.size - keepTail
        while ((splitIndex > 0 && messages[splitIndex - 1].role == Role.ASSISTANT)) splitIndex--
        return splitIndex
    }

    override suspend fun compress(messages: ChatMessages): Pair<ChatMessages, TokenUsage>
    {
        logger.config("start to compress ${messages.size} messages")
        val splitIndex = needCompress(messages)
        if (splitIndex <= 0) return messages to TokenUsage()
        val toCompress = messages.subList(0, splitIndex)
        val tail = messages.subList(splitIndex, messages.size)
        val prompt = makePrompt(toCompress)
        val (res, usage) = sendAiRequestAndGetResult<ChatMessages>(
            model = model,
            message = prompt,
            retryType = RetryType.ADD_MESSAGE,
            resultType = object: ResultType<ChatMessages>
            {
                private val impl = yamlResultType<List<Compress>>()
                private var time = 0

                override fun getValue(str: String): ChatMessages
                {
                    if ((++time) < aiConfig.retry) return impl.getValue(str).toChatMessages()
                    runCatching { return impl.getValue(str).toChatMessages() }
                    // 最后一次仍然失败，退而求其次，将未能解析为yaml的内容作为一条system消息，指导继续聊天
                    val sb = StringBuilder()
                    sb.append("============= 以下是你和用户之前的聊天记录 =============\n")
                    sb.append(str)
                    sb.append("============= 以上是你和用户之前的聊天记录 =============\n")
                    sb.append("现在，请你和用户继续对话")
                    return ChatMessages(Role.SYSTEM, Content(sb.toString()))
                }
            }
        )
        val r = res.getOrElse()
        {
            logger.warning("failed to compress messages, use jump compressor instead: ${it.message}")
            JumpContextCompressor(step = 2).compress(toCompress).first
        } + tail
        logger.config("successfully compressed ${messages.size} messages to ${r.size} messages, used tokens: $usage")
        return r to usage
    }
}