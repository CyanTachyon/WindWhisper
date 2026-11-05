package moe.tachyon.windwhisper

import kotlinx.coroutines.delay
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.internal.llm.AiResult
import moe.tachyon.windwhisper.ai.internal.llm.ResponsesSession
import moe.tachyon.windwhisper.ai.internal.llm.responsesSessionScope
import moe.tachyon.windwhisper.ai.internal.llm.sendAiRequest
import moe.tachyon.windwhisper.ai.tools.AiToolSet
import moe.tachyon.windwhisper.ai.tools.Forum
import moe.tachyon.windwhisper.ai.tools.ReadImage
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.console.AnsiColor
import moe.tachyon.windwhisper.console.AnsiStyle
import moe.tachyon.windwhisper.console.SimpleAnsiColor
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.UnreadNotification
import moe.tachyon.windwhisper.forum.getUnreadNotifications
import moe.tachyon.windwhisper.forum.markAsRead
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import java.io.File
import kotlin.time.Duration.Companion.seconds

suspend fun workMain(user: LoginData, prompt: String)
{
    val scope = currentSessionScope(aiConfig.model)
    val session = ResponsesSession(File(dataDir, "responses-session.json"), scope)
    val images = GeneratedImageStore(File(dataDir, "generated-images"))
    val instructions = prompt.replace($$"${self_name}", mainConfig.username)
    logger.info("Starting Responses notification loop...")
    while (true)
    {
        delay(5.seconds)
        work(user, instructions, session, images, scope)
    }
}

private fun currentSessionScope(model: AiConfig.LlmModel): String =
    responsesSessionScope(mainConfig.url, mainConfig.username, model.url, model.key.first())

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

class MessagePrinter(
    val reasoningColor: AnsiColor,
    val messageColor: AnsiColor,
    val thinkingTagColor: AnsiColor,
)
{
    private var reasoning = false
    private val sb = StringBuilder()
    private var lastLineIsReasoning = false
    private fun putMessage(content: String, isReasoning: Boolean)
    {
        val lines = content.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return@putMessage
        if (isReasoning && !lastLineIsReasoning)
        {
            logger.info(thinkingTagColor.toString() + "<thinking>" + AnsiStyle.RESET)
            lastLineIsReasoning = true
        }
        else if (!isReasoning && lastLineIsReasoning)
        {
            logger.info(thinkingTagColor.toString() + "</thinking>" + AnsiStyle.RESET)
            lastLineIsReasoning = false
        }
        lines.forEach { line -> logger.info(
            (if (isReasoning) reasoningColor else messageColor).toString() +
            line +
            AnsiStyle.RESET
        ) }
    }

    fun put(content: String, isReasoning: Boolean)
    {
        if (content.isEmpty()) return
        if (isReasoning != reasoning)
        {
            flush()
            reasoning = isReasoning
        }
        sb.append(content)
        if (sb.contains("\n"))
        {
            putMessage(sb.toString().substringBeforeLast("\n"), reasoning)
            val after = sb.toString().substringAfterLast("\n")
            sb.clear()
            sb.append(after)
        }
    }
    fun flush()
    {
        putMessage(sb.toString(), reasoning)
        sb.clear()
    }
}

private val logger = WindWhisperLogger.getLogger()

/** Process the API's newest-first notification list without acknowledging unfinished turns. */
internal suspend fun processNotifications(
    notifications: List<UnreadNotification>,
    pendingTurnId: String?,
    isCompleted: (String) -> Boolean,
    process: suspend (turnId: String, notification: UnreadNotification?) -> Boolean,
    acknowledge: suspend (notificationId: Long) -> Boolean,
    completedUnacknowledgedTurnIds: List<String> = emptyList(),
): Boolean
{
    fun notificationId(turnId: String): Long
    {
        require(turnId.startsWith("notification:")) { "Unexpected Responses notification turn: $turnId" }
        return turnId.removePrefix("notification:").toLongOrNull()
            ?: error("Responses turn does not identify a notification")
    }
    val acknowledgedIds = mutableSetOf<Long>()
    suspend fun acknowledgeTurn(turnId: String): Boolean
    {
        val id = notificationId(turnId)
        if (id in acknowledgedIds) return true
        if (!acknowledge(id)) return false
        acknowledgedIds += id
        return true
    }
    if (pendingTurnId != null)
    {
        notificationId(pendingTurnId)
        // The session already owns the original input, even if the notification is no longer unread.
        if (!isCompleted(pendingTurnId) && !process(pendingTurnId, null)) return false
        if (!acknowledgeTurn(pendingTurnId)) return false
    }
    // A completed turn can fall outside the forum's unread page before acknowledgement succeeds.
    // Drain the durable acknowledgement ledger before accepting more work.
    for (turnId in completedUnacknowledgedTurnIds)
        if (!acknowledgeTurn(turnId)) return false
    for (notification in notifications.asReversed().distinctBy { it.notificationId })
    {
        if (notification.notificationId in acknowledgedIds) continue
        val turnId = "notification:${notification.notificationId}"
        if (!isCompleted(turnId) && !process(turnId, notification)) return false
        // Completion is checkpointed by the Responses loop before this acknowledgement. If it
        // fails, the next poll retries only this operation, never the model or its forum actions.
        if (!acknowledgeTurn(turnId)) return false
    }
    return true
}

internal fun notificationInput(notification: UnreadNotification): ChatMessages = ChatMessages(
    Role.USER,
    buildString {
        append("收到新的论坛通知，notificationId=${notification.notificationId}。")
        if (notification.topicId != null)
            append("关联topicId=${notification.topicId}，请查看相关话题并决定是否回复、点赞或仅浏览。")
        else append("这条通知没有关联topicId；没有明确话题时无需执行论坛操作。")
    },
)

private suspend fun work(
    user: LoginData,
    instructions: String,
    session: ResponsesSession,
    images: GeneratedImageStore,
    startupScope: String,
)
{
    val model = aiConfig.model
    if (currentSessionScope(model) != startupScope)
    {
        logger.severe("Forum identity or Responses endpoint/key changed. Restart with the matching session data directory before processing more notifications.")
        return
    }
    val notifications = user.getUnreadNotifications()
    if (notifications.isNotEmpty()) logger.info("Processing ${notifications.size} unread notifications in the Responses conversation")
    val tools = AiToolSet(Forum(user, blackList, images = images), ReadImage()).getTools(null, model)

    logger.warning("Failed to process forum notifications") {
        processNotifications(
            notifications = notifications,
            pendingTurnId = session.pendingTurnId,
            completedUnacknowledgedTurnIds = session.completedUnacknowledgedTurnIds,
            isCompleted = session::isCompleted,
            process = { turnId, notification ->
                val defaultPrinter = MessagePrinter(
                    reasoningColor = SimpleAnsiColor.CYAN,
                    messageColor = SimpleAnsiColor.CYAN,
                    thinkingTagColor = SimpleAnsiColor.GREEN,
                )
                var toolPrinter: MessagePrinter? = null
                val result = try
                {
                    sendAiRequest(
                        model = model,
                        messages = notification?.let(::notificationInput) ?: ChatMessages.empty(),
                        instructions = instructions,
                        tools = tools,
                        session = session,
                        turnId = turnId,
                        stream = true,
                        onGeneratedImage = { image ->
                            images.save(image.id, image.base64, image.format)
                            "图片已保存，imageId=${image.id}。如需在论坛发送，请调用upload_generated_image并传入该imageId与目标topicId，获得Markdown后放入send_post的内容。"
                        },
                    ) { slice ->
                        if (slice is StreamAiResponseSlice.Message)
                        {
                            toolPrinter?.flush()
                            toolPrinter = null
                            defaultPrinter.put(slice.reasoningContent, true)
                            defaultPrinter.put(slice.content, false)
                        }
                        else
                        {
                            defaultPrinter.flush()
                            if (slice is StreamAiResponseSlice.ToolMessage)
                            {
                                toolPrinter = toolPrinter ?: MessagePrinter(
                                    reasoningColor = SimpleAnsiColor.YELLOW,
                                    messageColor = SimpleAnsiColor.YELLOW,
                                    thinkingTagColor = SimpleAnsiColor.BLUE,
                                )
                                toolPrinter!!.put(slice.reasoningContent.toText(), false)
                            }
                            else
                            {
                                toolPrinter?.flush()
                                toolPrinter = null
                            }
                            if (slice is StreamAiResponseSlice.ToolCall)
                                logger.info(SimpleAnsiColor.PURPLE.toString() + "<tool: ${slice.tool.name}>${slice.parms}</tool>" + AnsiStyle.RESET)
                        }
                    }
                }
                finally
                {
                    toolPrinter?.flush()
                    defaultPrinter.flush()
                }
                when (result)
                {
                    is AiResult.Success -> {
                        logger.fine("Completed $turnId in the Responses conversation")
                        true
                    }
                    is AiResult.UnknownError -> {
                        logger.severe("Responses turn $turnId failed", result.error)
                        false
                    }
                    else -> {
                        logger.severe("Responses turn $turnId failed: ${result::class.simpleName}")
                        false
                    }
                }
            },
            acknowledge = { notificationId ->
                user.markAsRead(notificationId).also { success ->
                    if (success)
                    {
                        session.acknowledge("notification:$notificationId")
                        logger.fine("Marked notification $notificationId as read")
                    }
                    else logger.warning("Notification $notificationId is complete, but marking it as read failed; only acknowledgement will be retried")
                }
            },
        )
    }
}
