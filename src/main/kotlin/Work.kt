package moe.tachyon.windwhisper

import kotlinx.coroutines.delay
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.chat.tools.AiToolSet
import moe.tachyon.windwhisper.ai.chat.tools.Forum
import moe.tachyon.windwhisper.ai.chat.tools.WebSearch
import moe.tachyon.windwhisper.ai.internal.llm.AiResult
import moe.tachyon.windwhisper.ai.internal.llm.sendAiRequest
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.console.AnsiStyle
import moe.tachyon.windwhisper.console.SimpleAnsiColor
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getUnreadPosts
import moe.tachyon.windwhisper.forum.markAsRead
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import java.io.File
import kotlin.time.Duration.Companion.seconds

suspend fun workMain(user: LoginData, prompt: String)
{
    logger.info("Starting work loop...")
    while (true) work(user, prompt)
}

private val memoryFile by lazy()
{
    File(dataDir, "memory.md")
}
private var memory: String
    get() = memoryFile.takeIf { it.exists() }?.readText() ?: ""
    set(value) = memoryFile.writeText(value)

private val logger = WindWhisperLogger.getLogger()
private suspend fun work(user: LoginData, prompt: String)
{
    delay(1.seconds)
    val posts = user.getUnreadPosts().asReversed()
    val topics = posts.map { it.topicId }.distinct()
    if (posts.isNotEmpty()) logger.info(topics.toString())

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

    val tools = AiToolSet(Forum(user))
    if (aiConfig.webSearchKey.isNotEmpty()) tools.addProvider(WebSearch)

    val res = logger.warning("Failed to send AI request for posts $topics")
    {
        val putMessage: (String) -> Unit = { content ->
            content.split("\n").filter { it.isNotBlank() }.forEach { line -> logger.info(SimpleAnsiColor.CYAN.toString() + line + AnsiStyle.RESET) }
        }
        val sb = StringBuilder()
        var reasoning = false
        sendAiRequest(
            model = aiConfig.model,
            messages = ChatMessages(Role.SYSTEM, prompt),
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
                        putMessage(sb.toString())
                        sb.clear()
                        logger.info(SimpleAnsiColor.GREEN.toString() + "<thinking>" + AnsiStyle.RESET)
                        reasoning = true
                    }
                    sb.append(it.reasoningContent)
                }
                else if (it.content.isNotEmpty())
                {
                    if (reasoning)
                    {
                        putMessage(sb.toString())
                        sb.clear()
                        logger.info(SimpleAnsiColor.GREEN.toString() + "</thinking>" + AnsiStyle.RESET)
                        reasoning = false
                    }
                    sb.append(it.content)
                }
                if (sb.contains("\n"))
                {
                    putMessage(sb.toString().substringBeforeLast("\n"))
                    val after = sb.toString().substringAfterLast("\n")
                    sb.clear()
                    sb.append(after)
                }
            }
            else
            {
                putMessage(sb.toString())
                sb.clear()
                if (it is StreamAiResponseSlice.ToolCall)
                    logger.info(SimpleAnsiColor.PURPLE.toString() + "<tool: ${it.tool.name}>${it.parms}</tool>" + AnsiStyle.RESET)
            }
        }.also {
            putMessage(sb.toString())
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
    logger.info("successfully updated memory.")
}