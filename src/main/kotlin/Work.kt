package moe.tachyon.windwhisper

import kotlinx.coroutines.delay
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.chat.tools.AiToolSet
import moe.tachyon.windwhisper.ai.chat.tools.Forum
import moe.tachyon.windwhisper.ai.chat.tools.WebSearch
import moe.tachyon.windwhisper.ai.internal.llm.sendAiRequest
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getUnreadPosts
import moe.tachyon.windwhisper.forum.markAsRead
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import java.io.File
import kotlin.getValue
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
    if (posts.isNotEmpty()) logger.info(posts.map { it.topicId }.toString())
    val set = mutableSetOf<Int>()
    posts.forEach()
    {
        logger.severe("Failed to read notification ${it.notificationId}")
        {
            val success = user.markAsRead(it.notificationId)
            if (success) logger.info("Marked notification ${it.notificationId} as read.")
            else error("Failed to mark notification ${it.notificationId} as read.")
        }

        if (it.topicId in set) return@forEach
        set.add(it.topicId)

        val prompt = prompt
            .replace($$"${self_name}", mainConfig.username)
            .replace($$"${topic_id}", it.topicId.toString())
            .replace($$"${self_memory}", memory)

        val tools = AiToolSet(
            WebSearch,
            Forum(user),
        )

        val res = logger.warning("Failed to send AI request for notification ${it.notificationId}")
        {
            sendAiRequest(
                model = aiConfig.model,
                messages = ChatMessages(Role.USER, prompt),
                tools = tools.getTools(null, aiConfig.model),
                stream = true
            )
        }.getOrElse { return@forEach }
        val newMemory = res.messages.filter { role -> role.role is Role.ASSISTANT }.joinToString("\n\n") { msg -> msg.content.toText() }.trim()
        if (newMemory.isNotBlank())
            memory = newMemory
        logger.info("AI response for notification ${it.notificationId}:\n${newMemory}")
    }
}