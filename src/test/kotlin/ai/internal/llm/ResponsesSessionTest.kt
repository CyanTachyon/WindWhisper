package moe.tachyon.windwhisper.ai.internal.llm

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponsesSessionTest
{
    @Test
    fun completedBatchTracksEveryDeliveryAcrossRestartAndSkipsDuplicates()
    {
        withSessionFile { file ->
            val session = ResponsesSession(file, "test")
            val deliveries = listOf("notification:1", "notification:2")
            assertTrue(session.begin("batch:1", listOf(user("Combined notifications")), deliveries))
            assertEquals(deliveries, session.pendingDeliveryIds)
            session.finish("resp_batch")

            val restored = ResponsesSession(file, "test")
            assertNull(restored.pendingTurnId)
            assertTrue(deliveries.all(restored::isCompleted))
            assertEquals(deliveries, restored.completedUnacknowledgedTurnIds)
            assertEquals("resp_batch", restored.state.previousResponseId)
            assertFalse(restored.begin("batch:1", listOf(user("Duplicate batch")), deliveries))
            assertFalse(restored.begin("different-batch-id", listOf(user("Duplicate deliveries")), deliveries))
            restored.acknowledge("notification:1")
            assertEquals(listOf("notification:2"), ResponsesSession(file, "test").completedUnacknowledgedTurnIds)
        }
    }

    @Test
    fun failedContinuationKeepsToolOutputsAndAssetReceiptsButDiscardsOldUserInput()
    {
        withSessionFile { file ->
            val output = buildJsonObject { put("type", "function_call_output"); put("call_id", "call_saved"); put("output", "already posted") }
            val receipt = buildJsonObject { put("role", "developer"); put("content", "Saved asset ig_saved") }
            val session = ResponsesSession(file, "test")
            session.update(session.state.copy(previousResponseId = "resp_good"))
            session.begin("batch:failed", listOf(output, receipt, user("Old notification")), listOf("notification:1", "notification:2"))
            session.update(session.state.copy(turn = session.state.turn!!.copy(responseId = "resp_unusable")))
            session.failPendingTurn()

            val restored = ResponsesSession(file, "test")
            assertNull(restored.pendingTurnId)
            assertEquals("resp_good", restored.state.previousResponseId)
            assertTrue(restored.isCompleted("notification:1") && restored.isCompleted("notification:2"))
            assertEquals(listOf(output, receipt), restored.state.deferredInput)
            assertFalse(restored.state.deferredInput.any { it["role"] == JsonPrimitive("user") })
            val deferred = restored.state.deferredInput
            assertTrue(restored.begin("batch:new", listOf(user("New notification")), listOf("notification:3")))
            assertEquals(deferred + user("New notification"), restored.state.turn!!.input)
            assertTrue(restored.state.deferredInput.isEmpty())
            val resumed = ResponsesSession(file, "test")
            assertEquals(listOf("notification:3"), resumed.pendingDeliveryIds)
            assertEquals(deferred + user("New notification"), resumed.state.turn!!.input)
            assertTrue(resumed.state.deferredInput.isEmpty())
        }
    }

    @Test
    fun olderSessionFilesDefaultDeliveryIdsToThePendingTurn()
    {
        withSessionFile { file ->
            file.writeText("""{"scope":"test","previousResponseId":"resp_before","turn":{"id":"notification:7","input":[]}}""")
            val restored = ResponsesSession(file, "test")
            assertEquals(listOf("notification:7"), restored.pendingDeliveryIds)
            assertTrue(restored.state.turn!!.toolCallIds.isEmpty())
            assertTrue(restored.state.deferredInput.isEmpty())
            restored.failPendingTurn()
            assertNull(ResponsesSession(file, "test").pendingTurnId)
            assertTrue(ResponsesSession(file, "test").isCompleted("notification:7"))
        }
    }

    @Test
    fun unacknowledgedNotificationsSurviveRecentHistoryEvictionAndRestart()
    {
        val session = ResponsesSession(scope = "test")
        repeat(2050) { index ->
            assertTrue(session.begin("notification:$index", emptyList()))
            session.finish("resp_$index")
            if (index != 0) session.acknowledge("notification:$index")
        }
        assertFalse("notification:0" in session.state.completedTurnIds)
        assertTrue(session.isCompleted("notification:0"))
        assertFalse(session.begin("notification:0", emptyList()))

        val directory = Files.createTempDirectory("responses-unacknowledged-test-").toFile()
        try
        {
            val file = directory.resolve("session.json")
            ResponsesSession(file, "test").update(session.state)
            val restored = ResponsesSession(file, "test")
            assertEquals(listOf("notification:0"), restored.completedUnacknowledgedTurnIds)
            assertTrue(restored.isCompleted("notification:0"))
            restored.acknowledge("notification:0")
            assertTrue(ResponsesSession(file, "test").completedUnacknowledgedTurnIds.isEmpty())
        }
        finally { directory.deleteRecursively() }
    }

    private fun user(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

    private fun withSessionFile(block: (java.io.File) -> Unit)
    {
        val directory = Files.createTempDirectory("responses-session-test-").toFile()
        try { block(directory.resolve("session.json")) }
        finally { directory.deleteRecursively() }
    }
}
