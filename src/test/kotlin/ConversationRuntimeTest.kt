package moe.tachyon.windwhisper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.ai.internal.llm.ResponsesSession
import moe.tachyon.windwhisper.console.command.CommandSender
import moe.tachyon.windwhisper.console.command.CommandSet
import moe.tachyon.windwhisper.console.command.Context
import moe.tachyon.windwhisper.console.command.Send
import moe.tachyon.windwhisper.forum.LoginData
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ConversationRuntimeTest
{
    @BeforeTest
    fun initialize() = initializeTestEnvironment()

    @Test
    fun `send queues behind active notification without changing its input or response`() = runBlocking {
        withDirectory { directory ->
            val session = ResponsesSession(directory.resolve("session.json"), "test")
            session.begin("notifications:1", listOf(user("Current notification")), listOf("notification:1"))
            val pending = session.state
            val runtime = runtime(directory, session)
            val sender = RecordingSender()
            ConversationRuntime.attach(runtime)
            try
            {
                assertSame(Send, CommandSet.getCommand("send"))
                assertSame(Context, CommandSet.getCommand("context"))
                assertTrue(Send.execute(sender, listOf("请生成", "图片")))
                assertFalse(Send.execute(sender, emptyList()))
                assertEquals(1, runtime.queuedMessages)
                assertSame(pending, session.state)
                val queued = assertNotNull(runtime.nextMessage(0))
                assertEquals("请生成 图片", queued.text)
                assertTrue(isConsoleTurn(queued.id))
                assertEquals(0, runtime.queuedMessages)
                assertTrue(sender.output.single().contains(queued.id))
            }
            finally { ConversationRuntime.detach(runtime); runtime.close() }
        }
    }

    @Test
    fun `queue is bounded preserves order and rejects messages after shutdown`() = runBlocking {
        withDirectory { directory ->
            val runtime = runtime(directory, ResponsesSession(scope = "test"))
            val ids = (0 until 32).map { assertNotNull(runtime.enqueue("message $it")) }
            assertNull(runtime.enqueue("too many"))
            assertEquals(32, runtime.queuedMessages)
            for (index in ids.indices)
            {
                val next = assertNotNull(runtime.nextMessage(0))
                assertEquals(ids[index], next.id)
                assertEquals("message $index", next.text)
            }
            assertNull(runtime.nextMessage(1))
            assertEquals(0, runtime.queuedMessages)
            runtime.enqueue("not started")
            assertEquals(1, runtime.close())
            assertEquals(0, runtime.queuedMessages)
            assertNull(runtime.enqueue("closed"))
        }
    }

    @Test
    fun `pending console turn resumes its saved input and terminal states never reach forum acknowledgement`() = runBlocking {
        for (failure in listOf(false, true)) withDirectory { directory ->
            val file = directory.resolve("session.json")
            val first = ResponsesSession(file, "test")
            first.begin("notification:1", listOf(user("Forum notification")))
            first.finish("resp_notification")
            first.begin("command:pending", listOf(user("Original console message")))
            val restarted = ResponsesSession(file, "test")
            assertTrue(isConsoleTurn(restarted.pendingTurnId!!))
            val result = processConsoleDelivery(restarted, "command:pending") {
                assertTrue(restarted.begin("command:pending", emptyList()))
                assertEquals(listOf(user("Original console message")), restarted.state.turn!!.input)
                if (failure) restarted.failPendingTurn() else restarted.finish("resp_console")
                !failure
            }
            assertEquals(!failure, result)
            val restored = ResponsesSession(file, "test")
            assertNull(restored.pendingTurnId)
            assertTrue(restored.isCompleted("command:pending"))
            assertEquals(listOf("notification:1"), restored.completedUnacknowledgedTurnIds)
            val forumAcknowledgements = mutableListOf<Long>()
            processNotifications(emptyList(), restored.pendingTurnId, restored::isCompleted,
                { _, _ -> error("Completed console input must not run as a notification") },
                { forumAcknowledgements += it; true }, restored.completedUnacknowledgedTurnIds)
            assertEquals(listOf(1L), forumAcknowledgements)
        }
    }

    @Test
    fun `cancellation retains pending console turn and restart clears only terminal console acknowledgements`() = runBlocking {
        withDirectory { directory ->
            val session = ResponsesSession(directory.resolve("session.json"), "test")
            session.begin("notification:1", listOf(user("Forum notification")))
            session.finish("resp_notification")
            session.begin("command:cancelled", listOf(user("Console message")))
            assertFailsWith<CancellationException> {
                processConsoleDelivery(session, "command:cancelled") { throw CancellationException("Stopping") }
            }
            assertEquals("command:cancelled", session.pendingTurnId)
            assertFalse(session.isCompleted("command:cancelled"))
            session.finish("resp_console") // Crash after completion but before local acknowledgement.
            val restarted = ResponsesSession(directory.resolve("session.json"), "test")
            acknowledgeCompletedConsoleTurns(restarted)
            assertEquals(listOf("notification:1"), restarted.completedUnacknowledgedTurnIds)
            assertEquals("resp_console", restarted.state.previousResponseId)
        }
    }

    private fun runtime(directory: File, session: ResponsesSession) = ConversationRuntime(
        LoginData("fixture-cookie", "fixture-csrf"), "Unchanged persona", session,
        GeneratedImageStore(directory.resolve("images")), "test",
    )

    private fun user(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

    private suspend fun withDirectory(block: suspend (File) -> Unit)
    {
        val directory = Files.createTempDirectory("wind-conversation-runtime-").toFile()
        try { block(directory) }
        finally { directory.deleteRecursively() }
    }

    private class RecordingSender: CommandSender("test")
    {
        val output = mutableListOf<String>()
        override suspend fun out(line: String) { output += line }
        override suspend fun err(line: String) { error(line) }
        override suspend fun clear() {}
    }
}
