package moe.tachyon.windwhisper

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.internal.llm.AiResult
import moe.tachyon.windwhisper.ai.internal.llm.sendAiRequest
import moe.tachyon.windwhisper.ai.tools.AiToolSet
import moe.tachyon.windwhisper.ai.tools.Forum
import moe.tachyon.windwhisper.ai.tools.WebSearch
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.console.AnsiStyle
import moe.tachyon.windwhisper.console.SimpleAnsiColor
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getUnreadNotifications
import moe.tachyon.windwhisper.forum.markAsRead
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import java.io.File
import kotlin.time.Duration.Companion.seconds

suspend fun workMain(user: LoginData, prompt: String)
{
    logger.info("Starting work loop...")
    while (true)
    {
        delay(5.seconds)
        withContext(NonCancellable) { work(user, prompt) }
    }
}

private val memoryFile by lazy()
{
    File(dataDir, "memory.md")
}
var memory: String
    get() = memoryFile.takeIf { it.exists() }?.readText() ?: ""
    private set(value) = memoryFile.writeText(value)
var blackList: Set<Int>
    get()
    {
        val file = File(dataDir, "blacklist.txt")
        if (!file.exists()) return emptySet()
        return file.readLines().mapNotNull { it.toIntOrNull() }.toSet()
    }
    set(value)
    {
        val file = File(dataDir, "blacklist.txt")
        file.writeText(value.joinToString("\n"))
    }

private val logger = WindWhisperLogger.getLogger()
private suspend fun work(user: LoginData, prompt: String)
{
    val posts = user.getUnreadNotifications().asReversed()
    val topics = posts.mapNotNull { it.topicId }.distinct()
    if (posts.isNotEmpty()) logger.info("Starting to process ${posts.size} unread notifications for topics $topics")

    for (it in posts) logger.severe("Failed to read notification ${it.notificationId}")
    {
        val success = user.markAsRead(it.notificationId)
        if (success) logger.info("Marked notification ${it.notificationId} as read.")
        else error("Failed to mark notification ${it.notificationId} as read.")
    }

    if (topics.isEmpty()) return

    val prompt = prompt
        .replace($$"${self_name}", mainConfig.username)
        .replace($$"${topic_id}", topics.toString())
        .replace($$"${self_memory}", memory)

    val tools = AiToolSet(Forum(user, blackList))
    if (aiConfig.webSearchKey.isNotEmpty()) tools.addProvider(WebSearch)

    val res = logger.warning("Failed to send AI request for posts $topics")
    {
        var lastLineIsReasoning = false
        val putMessage: (String, Boolean) -> Unit = putMessage@{ content, isReasoning ->
            val lines = content.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) return@putMessage
            if (isReasoning && !lastLineIsReasoning)
            {
                logger.info(SimpleAnsiColor.GREEN.toString() + "<thinking>" + AnsiStyle.RESET)
                lastLineIsReasoning = true
            }
            else if (!isReasoning && lastLineIsReasoning)
            {
                logger.info(SimpleAnsiColor.GREEN.toString() + "</thinking>" + AnsiStyle.RESET)
                lastLineIsReasoning = false
            }
            lines.forEach { line -> logger.info(SimpleAnsiColor.CYAN.toString() + line + AnsiStyle.RESET) }
        }

        val sb = StringBuilder()
        var reasoning = false
        sendAiRequest(
            model = aiConfig.model,
            messages = ChatMessages(Role.USER, prompt),
            tools = tools.getTools(null, aiConfig.model),
            stream = true
        )
        {
            if (it is StreamAiResponseSlice.Message)
            {
                if (it.reasoningContent.isNotEmpty())
                {
                    if (!reasoning)
                    {
                        putMessage(sb.toString(), false)
                        sb.clear()
                        reasoning = true
                    }
                    sb.append(it.reasoningContent)
                }
                else if (it.content.isNotEmpty())
                {
                    if (reasoning)
                    {
                        putMessage(sb.toString(), true)
                        sb.clear()
                        reasoning = false
                    }
                    sb.append(it.content)
                }
                if (sb.contains("\n"))
                {
                    putMessage(sb.toString().substringBeforeLast("\n"), reasoning)
                    val after = sb.toString().substringAfterLast("\n")
                    sb.clear()
                    sb.append(after)
                }
            }
            else
            {
                putMessage(sb.toString(), reasoning)
                sb.clear()
                if (it is StreamAiResponseSlice.ToolCall)
                    logger.info(SimpleAnsiColor.PURPLE.toString() + "<tool: ${it.tool.name}>${it.parms}</tool>" + AnsiStyle.RESET)
            }
        }.also {
            putMessage(sb.toString(), reasoning)
            sb.clear()
        }
    }.getOrElse { return }
    if (res !is AiResult.Success)
    {
        if (res is AiResult.UnknownError)
            logger.severe("AI request for posts $topics failed: $res", res.error)
        else
            logger.severe("AI request for posts $topics failed: $res")
        return
    }
    val newMemory = res.messages.filter { role -> role.role is Role.ASSISTANT }.joinToString("\n\n") { msg -> msg.content.toText() }.trim()
    if (newMemory.isNotBlank()) memory = newMemory
    logger.info("AI request for posts $topics completed successfully.")
}