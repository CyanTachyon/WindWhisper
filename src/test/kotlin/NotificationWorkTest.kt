package moe.tachyon.windwhisper

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.forum.UnreadNotification
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationWorkTest
{
    @BeforeTest
    fun initialize() = initializeTestEnvironment()

    @Test
    fun `each notification gets its own turn in oldest first order`() = runBlocking {
        val events = mutableListOf<String>()
        val notifications = listOf(UnreadNotification(42, 2), UnreadNotification(42, 1))
        assertTrue(processNotifications(notifications, null, { false }, { turn, notification ->
            events += "process:$turn:${notification!!.topicId}"
            true
        }, { id -> events += "ack:$id"; true }))
        assertEquals(listOf("process:notification:1:42", "ack:1", "process:notification:2:42", "ack:2"), events)
    }

    @Test
    fun `failed turn stays unread and prevents later turns`() = runBlocking {
        val events = mutableListOf<String>()
        assertFalse(processNotifications(listOf(UnreadNotification(2, 2), UnreadNotification(1, 1)),
            null, { false }, { turn, _ -> events += "process:$turn"; false },
            { id -> events += "ack:$id"; true }))
        assertEquals(listOf("process:notification:1"), events)
    }

    @Test
    fun `cancelling a turn leaves it unread and prevents later notifications`() = runBlocking {
        val events = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            processNotifications(listOf(UnreadNotification(2, 2), UnreadNotification(1, 1)),
                null, { false }, { turn, _ ->
                    events += "process:$turn"
                    throw CancellationException("Application is stopping")
                }, { id -> events += "ack:$id"; true })
        }
        assertEquals(listOf("process:notification:1"), events)
    }

    @Test
    fun `completed notifications only retry acknowledgement after failure`() = runBlocking {
        val events = mutableListOf<String>()
        val completed = mutableSetOf<String>()
        val notifications = listOf(UnreadNotification(2, 2), UnreadNotification(1, 1))
        assertFalse(processNotifications(notifications, null, completed::contains, { turn, _ ->
            events += "process:$turn"
            completed += turn
            true
        }, { id -> events += "failed-ack:$id"; false }))
        assertEquals(listOf("process:notification:1", "failed-ack:1"), events)
        events.clear()
        assertTrue(processNotifications(notifications, null, completed::contains, { turn, _ ->
            events += "process:$turn"
            completed += turn
            true
        }, { id -> events += "ack:$id"; true }))
        assertEquals(listOf("ack:1", "process:notification:2", "ack:2"), events)
    }

    @Test
    fun `pending turn resumes before unread notifications even when missing from the list`() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(processNotifications(listOf(UnreadNotification(2, 2)), "notification:1", { false },
            { turn, notification ->
                if (turn == "notification:1") assertNull(notification)
                else assertEquals(2, notification!!.topicId)
                events += "process:$turn"
                true
            }, { id -> events += "ack:$id"; true }))
        assertEquals(listOf("process:notification:1", "ack:1", "process:notification:2", "ack:2"), events)
    }

    @Test
    fun `pending turn also present in unread list is not delivered twice`() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(processNotifications(listOf(UnreadNotification(1, 1)), "notification:1", { false },
            { turn, notification -> assertNull(notification); events += "process:$turn"; true },
            { id -> events += "ack:$id"; true }))
        assertEquals(listOf("process:notification:1", "ack:1"), events)
    }

    @Test
    fun `completed acknowledgement ledger drains before new work after restart`() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(processNotifications(
            notifications = listOf(UnreadNotification(4, 4), UnreadNotification(3, 3)),
            pendingTurnId = "notification:2",
            isCompleted = { it == "notification:1" || it == "notification:3" },
            process = { turn, notification ->
                if (turn == "notification:2") assertNull(notification)
                events += "process:$turn"
                true
            },
            acknowledge = { id -> events += "ack:$id"; true },
            completedUnacknowledgedTurnIds = listOf("notification:1", "notification:3"),
        ))
        assertEquals(listOf("process:notification:2", "ack:2", "ack:1", "ack:3",
            "process:notification:4", "ack:4"), events)
    }

    @Test
    fun `failed ledger acknowledgement blocks new work without repeating the model`() = runBlocking {
        val events = mutableListOf<String>()
        assertFalse(processNotifications(
            notifications = listOf(UnreadNotification(2, 2)),
            pendingTurnId = null,
            isCompleted = { it == "notification:1" },
            process = { turn, _ -> events += "process:$turn"; true },
            acknowledge = { id -> events += "failed-ack:$id"; false },
            completedUnacknowledgedTurnIds = listOf("notification:1"),
        ))
        assertEquals(listOf("failed-ack:1"), events)
    }

    @Test
    fun `notification input contains only the current notification details`()
    {
        val message = notificationInput(UnreadNotification(42, 123)).single()
        assertEquals(Role.USER, message.role)
        assertTrue("notificationId=123" in message.content.toText())
        assertTrue("topicId=42" in message.content.toText())
        val noTopic = notificationInput(UnreadNotification(null, 124)).single()
        assertEquals(Role.USER, noTopic.role)
        assertTrue("notificationId=124" in noTopic.content.toText())
        assertTrue("没有关联topicId" in noTopic.content.toText())
    }
}
