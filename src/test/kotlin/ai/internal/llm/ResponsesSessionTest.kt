package moe.tachyon.windwhisper.ai.internal.llm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponsesSessionTest
{
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
}
