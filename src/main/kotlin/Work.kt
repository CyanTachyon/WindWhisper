package moe.tachyon.windwhisper

import kotlinx.coroutines.CancellationException
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

/** Deliver each poll as one batch and acknowledge terminal failures without repeating their work. */
internal suspend fun processNotifications(
    notifications: List<UnreadNotification>,
    pendingTurnId: String?,
    isCompleted: (String) -> Boolean,
    process: suspend (turnId: String, notifications: List<UnreadNotification>?) -> Boolean,
    acknowledge: suspend (notificationId: Long) -> Boolean,
    completedUnacknowledgedTurnIds: List<String> = emptyList(),
    pendingDeliveryIds: List<String> = emptyList(),
): Boolean
{
    fun notificationId(turnId: String): Long
    {
        require(turnId.startsWith("notification:")) { "Unexpected Responses notification turn: $turnId" }
        return turnId.removePrefix("notification:").toLongOrNull()
            ?: error("Responses turn does not identify a notification")
    }
    val acknowledgementAttempts = mutableSetOf<Long>()
    var acknowledgementsSucceeded = true
    suspend fun acknowledgeTurn(turnId: String)
    {
        val id = notificationId(turnId)
        if (!acknowledgementAttempts.add(id)) return
        if (!acknowledge(id)) acknowledgementsSucceeded = false
    }
    // Terminal deliveries can fall outside the unread page before acknowledgement succeeds.
    for (turnId in completedUnacknowledgedTurnIds)
        acknowledgeTurn(turnId)

    if (pendingTurnId != null)
    {
        require(pendingDeliveryIds.isNotEmpty()) { "Pending Responses batch has no delivery IDs" }
        val deliveries = pendingDeliveryIds.distinct()
        deliveries.forEach(::notificationId)
        // Only a cancellation or interrupted process leaves a pending batch. Its input is saved.
        val success = deliveries.all(isCompleted) || process(pendingTurnId, null)
        for (deliveryId in deliveries)
            acknowledgeTurn(deliveryId)
        if (!success) return false
    }

    val batch = mutableListOf<UnreadNotification>()
    for (notification in notifications.asReversed().distinctBy { it.notificationId })
    {
        if (notification.notificationId in acknowledgementAttempts) continue
        val deliveryId = "notification:${notification.notificationId}"
        if (isCompleted(deliveryId))
        {
            acknowledgeTurn(deliveryId)
        }
        else batch += notification
    }
    if (batch.isEmpty()) return acknowledgementsSucceeded

    val turnId = "notifications:" + batch.joinToString(",") { it.notificationId.toString() }
    val success = process(turnId, batch)
    // The Responses loop checkpoints both success and non-cancellation failure as terminal.
    // A read-ack failure retries only this operation, never the batch or its forum actions.
    for (notification in batch)
        acknowledgeTurn("notification:${notification.notificationId}")
    return success && acknowledgementsSucceeded
}

internal fun notificationInput(notifications: List<UnreadNotification>): ChatMessages = ChatMessages(
    Role.USER,
    buildString {
        appendLine("收到以下一批论坛通知，按从旧到新排列。请统筹处理相关话题，决定是否回复、点赞或仅浏览；同一话题无需因多个通知重复操作。")
        for (notification in notifications)
        {
            append("- notificationId=${notification.notificationId}，")
            if (notification.topicId != null) appendLine("topicId=${notification.topicId}")
            else appendLine("没有关联topicId；没有明确话题时无需执行论坛操作。")
        }
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
    check(currentSessionScope(model) == startupScope) {
        "Forum identity or Responses endpoint/key changed. Notification processing stopped; restore matching configuration and restart."
    }
    val notifications = user.getUnreadNotifications()
    if (notifications.isNotEmpty()) logger.info("Processing a batch of ${notifications.size} unread notifications in the Responses conversation")
    val tools = AiToolSet(Forum(user, blackList, images = images), ReadImage()).getTools(null, model)

    processNotifications(
        notifications = notifications,
        pendingTurnId = session.pendingTurnId,
        pendingDeliveryIds = session.pendingDeliveryIds,
        completedUnacknowledgedTurnIds = session.completedUnacknowledgedTurnIds,
        isCompleted = session::isCompleted,
        process = { turnId, batch ->
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
                    messages = batch?.let(::notificationInput) ?: ChatMessages.empty(),
                    instructions = instructions,
                    tools = tools,
                    session = session,
                    turnId = turnId,
                    deliveryIds = batch?.map { "notification:${it.notificationId}" } ?: session.pendingDeliveryIds,
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
                    logger.severe("Responses batch $turnId failed and was discarded", result.error)
                    false
                }
                is AiResult.Cancelled -> throw CancellationException("Responses batch $turnId was cancelled")
                else -> {
                    logger.severe("Responses batch $turnId failed and was discarded: ${result::class.simpleName}")
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
