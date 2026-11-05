package moe.tachyon.windwhisper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
    fun `one poll delivers one oldest first batch with notification IDs deduplicated`() = runBlocking {
        val events = mutableListOf<String>()
        val notifications = listOf(UnreadNotification(42, 2), UnreadNotification(42, 1), UnreadNotification(42, 1))
        assertTrue(processNotifications(notifications, null, { false }, { turn, batch ->
            events += "process:$turn"
            assertEquals(listOf(1L, 2L), batch!!.map { it.notificationId })
            assertEquals(listOf(42, 42), batch.map { it.topicId })
            true
        }, { id -> events += "ack:$id"; true }))
        assertEquals(listOf("process:notifications:1,2", "ack:1", "ack:2"), events)
    }

    @Test
    fun `failed batch is acknowledged and not repeated on the next poll`() = runBlocking {
        val events = mutableListOf<String>()
        val completed = mutableSetOf<String>()
        val notifications = listOf(UnreadNotification(2, 2), UnreadNotification(1, 1))
        assertFalse(processNotifications(notifications, null, completed::contains, { turn, batch ->
            events += "failed:$turn"
            completed += batch!!.map { "notification:${it.notificationId}" }
            false
        }, { id -> events += "ack:$id"; true }))
        assertEquals(listOf("failed:notifications:1,2", "ack:1", "ack:2"), events)
        events.clear()
        assertTrue(processNotifications(notifications, null, completed::contains, { _, _ ->
            error("A failed batch must not run again")
        }, { id -> events += "ack:$id"; true }))
        assertEquals(listOf("ack:1", "ack:2"), events)
    }

    @Test
    fun `partial acknowledgement failure retries only remaining acknowledgements before new batch`() = runBlocking {
        val events = mutableListOf<String>()
        val completed = mutableSetOf<String>()
        val unacknowledged = mutableSetOf<String>()
        assertFalse(processNotifications(
            notifications = listOf(UnreadNotification(2, 2), UnreadNotification(1, 1)),
            pendingTurnId = null,
            isCompleted = completed::contains,
            process = { turn, batch ->
                events += "process:$turn"
                val deliveries = batch!!.map { "notification:${it.notificationId}" }
                completed += deliveries
                unacknowledged += deliveries
                true
            },
            acknowledge = { id ->
                events += "ack:$id"
                if (id == 1L) { unacknowledged -= "notification:$id"; true } else false
            },
        ))
        assertEquals(listOf("process:notifications:1,2", "ack:1", "ack:2"), events)
        assertEquals(setOf("notification:2"), unacknowledged)
        events.clear()
        assertTrue(processNotifications(
            notifications = listOf(UnreadNotification(3, 3), UnreadNotification(2, 2)),
            pendingTurnId = null,
            isCompleted = completed::contains,
            process = { turn, batch ->
                events += "process:$turn"
                assertEquals(listOf(3L), batch!!.map { it.notificationId })
                completed += batch.map { "notification:${it.notificationId}" }
                true
            },
            acknowledge = { id -> events += "ack:$id"; unacknowledged -= "notification:$id"; true },
            completedUnacknowledgedTurnIds = unacknowledged.toList(),
        ))
        assertEquals(listOf("ack:2", "process:notifications:3", "ack:3"), events)
        assertTrue(unacknowledged.isEmpty())
    }

    @Test
    fun `restart drains acknowledgement ledger then resumes pending batch before new notifications`() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(processNotifications(
            notifications = listOf(UnreadNotification(4, 4), UnreadNotification(3, 3)),
            pendingTurnId = "notifications:2,3",
            pendingDeliveryIds = listOf("notification:2", "notification:3"),
            isCompleted = { it == "notification:1" },
            process = { turn, batch ->
                if (turn == "notifications:2,3") assertNull(batch)
                else assertEquals(listOf(4L), batch!!.map { it.notificationId })
                events += "process:$turn"
                true
            },
            acknowledge = { id -> events += "ack:$id"; true },
            completedUnacknowledgedTurnIds = listOf("notification:1"),
        ))
        assertEquals(listOf("ack:1", "process:notifications:2,3", "ack:2", "ack:3",
            "process:notifications:4", "ack:4"), events)
    }

    @Test
    fun `failed resumed batch is acknowledged without starting another batch in the same poll`() = runBlocking {
        val events = mutableListOf<String>()
        assertFalse(processNotifications(
            notifications = listOf(UnreadNotification(3, 3)),
            pendingTurnId = "notifications:1,2",
            pendingDeliveryIds = listOf("notification:1", "notification:2"),
            isCompleted = { false },
            process = { turn, batch -> assertNull(batch); events += "failed:$turn"; false },
            acknowledge = { id -> events += "ack:$id"; true },
        ))
        assertEquals(listOf("failed:notifications:1,2", "ack:1", "ack:2"), events)
    }

    @Test
    fun `cancelling a batch leaves all its notifications unread`() = runBlocking {
        val events = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            processNotifications(listOf(UnreadNotification(2, 2), UnreadNotification(1, 1)),
                null, { false }, { turn, _ ->
                    events += "process:$turn"
                    throw CancellationException("Application is stopping")
                }, { id -> events += "ack:$id"; true })
        }
        assertEquals(listOf("process:notifications:1,2"), events)
    }

    @Test
    fun `persistent acknowledgement failure does not block pending or new work or repeat old deliveries`() = runBlocking {
        val events = mutableListOf<String>()
        val completed = mutableSetOf("notification:1")
        assertFalse(processNotifications(
            notifications = listOf(UnreadNotification(3, 3), UnreadNotification(1, 1)),
            pendingTurnId = "notifications:2",
            pendingDeliveryIds = listOf("notification:2"),
            isCompleted = completed::contains,
            process = { turn, batch ->
                events += "process:$turn"
                if (batch == null) completed += "notification:2"
                else completed += batch.map { "notification:${it.notificationId}" }
                true
            },
            acknowledge = { id -> events += "ack:$id"; id != 1L },
            completedUnacknowledgedTurnIds = listOf("notification:1"),
        ))
        assertEquals(listOf("ack:1", "process:notifications:2", "ack:2", "process:notifications:3", "ack:3"), events)
        events.clear()
        assertFalse(processNotifications(
            notifications = listOf(UnreadNotification(4, 4), UnreadNotification(1, 1)),
            pendingTurnId = null,
            isCompleted = completed::contains,
            process = { turn, batch ->
                assertEquals(listOf(4L), batch!!.map { it.notificationId })
                events += "process:$turn"
                true
            },
            acknowledge = { id -> events += "ack:$id"; id != 1L },
            completedUnacknowledgedTurnIds = listOf("notification:1"),
        ))
        assertEquals(listOf("ack:1", "process:notifications:4", "ack:4"), events)
    }

    @Test
    fun `failed batch attempts every acknowledgement even when the first one fails`() = runBlocking {
        val events = mutableListOf<String>()
        assertFalse(processNotifications(
            notifications = listOf(UnreadNotification(3, 3), UnreadNotification(2, 2), UnreadNotification(1, 1)),
            pendingTurnId = null,
            isCompleted = { false },
            process = { turn, _ -> events += "failed:$turn"; false },
            acknowledge = { id -> events += "ack:$id"; id != 1L },
        ))
        assertEquals(listOf("failed:notifications:1,2,3", "ack:1", "ack:2", "ack:3"), events)
    }

    @Test
    fun `notification input is one user message containing every delivery`()
    {
        val message = notificationInput(listOf(UnreadNotification(42, 123), UnreadNotification(42, 124),
            UnreadNotification(null, 125))).single()
        assertEquals(Role.USER, message.role)
        val text = message.content.toText()
        assertTrue("notificationId=123，topicId=42" in text)
        assertTrue("notificationId=124，topicId=42" in text)
        assertTrue("notificationId=125，没有关联topicId" in text)
        assertTrue(text.indexOf("notificationId=123") < text.indexOf("notificationId=124"))
        assertTrue(text.indexOf("notificationId=124") < text.indexOf("notificationId=125"))
    }
}
