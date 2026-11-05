package moe.tachyon.windwhisper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.ai.internal.llm.ResponsesSession
import moe.tachyon.windwhisper.forum.LoginData
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The notification loop and console commands share this single conversation. */
internal class ConversationRuntime(
    val user: LoginData,
    val instructions: String,
    val session: ResponsesSession,
    val images: GeneratedImageStore,
    val startupScope: String,
)
{
    internal data class ManualMessage(val id: String, val text: String)

    private val messages = Channel<ManualMessage>(32)
    private val queued = AtomicInteger()
    val queuedMessages: Int get() = queued.get()

    fun enqueue(text: String): String?
    {
        require(text.isNotBlank())
        val message = ManualMessage("command:${UUID.randomUUID()}", text)
        queued.incrementAndGet()
        if (messages.trySend(message).isSuccess) return message.id
        queued.decrementAndGet()
        return null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun nextMessage(timeoutMillis: Long): ManualMessage?
    {
        val message = if (timeoutMillis == 0L) messages.tryReceive().getOrNull()
        else select {
            messages.onReceive { it }
            onTimeout(timeoutMillis) { null }
        }
        if (message != null) queued.decrementAndGet()
        return message
    }

    fun close(): Int
    {
        messages.close()
        var abandoned = 0
        while (messages.tryReceive().isSuccess)
        {
            queued.decrementAndGet()
            abandoned++
        }
        return abandoned
    }

    companion object
    {
        @Volatile
        var current: ConversationRuntime? = null
            private set

        @Synchronized
        fun attach(runtime: ConversationRuntime)
        {
            check(current == null) { "A Responses conversation is already running" }
            current = runtime
        }

        @Synchronized
        fun detach(runtime: ConversationRuntime)
        {
            if (current === runtime) current = null
        }
    }
}
