package moe.tachyon.windwhisper.ai.internal.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Delivery checkpoints, not a transcript or a model-written memory. */
@Serializable
internal data class SessionState(
    val scope: String,
    val previousResponseId: String? = null,
    val turn: PendingTurn? = null,
    val completedTurnIds: List<String> = emptyList(),
    val unacknowledgedTurnIds: List<String> = emptyList(),
)

@Serializable
internal data class PendingTurn(
    val id: String,
    val input: List<JsonObject>,
    val responseId: String? = null,
    val toolResults: Map<String, JsonObject> = emptyMap(),
    val imageNotices: Map<String, String> = emptyMap(),
    val executingToolId: String? = null,
)

class ResponsesSession(private val file: File? = null, private val scope: String = "ephemeral")
{
    internal val mutex = Mutex()
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    internal var state: SessionState = file?.takeIf { it.exists() }?.let {
        json.decodeFromString<SessionState>(it.readText()).also { loaded ->
            require(loaded.scope == scope) {
                "Responses session belongs to another forum/API account. Use a separate data directory or explicitly reset the session."
            }
        }
    } ?: SessionState(scope)
        private set

    val pendingTurnId: String? get() = state.turn?.id
    val completedUnacknowledgedTurnIds: List<String> get() = state.unacknowledgedTurnIds
    fun isCompleted(turnId: String): Boolean = turnId in state.completedTurnIds || turnId in state.unacknowledgedTurnIds

    /** Called only after the forum confirms that this notification is read. */
    fun acknowledge(turnId: String)
    {
        if (turnId in state.unacknowledgedTurnIds)
            update(state.copy(unacknowledgedTurnIds = state.unacknowledgedTurnIds - turnId))
    }

    internal fun update(next: SessionState)
    {
        if (file != null)
        {
            val parent = file.absoluteFile.parentFile.toPath()
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".responses-session-", ".tmp")
            try
            {
                Files.writeString(temporary, json.encodeToString(SessionState.serializer(), next))
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            finally { Files.deleteIfExists(temporary) }
        }
        state = next
    }

    internal fun begin(turnId: String, input: List<JsonObject>): Boolean
    {
        if (isCompleted(turnId)) return false
        val pending = state.turn
        require(pending == null || pending.id == turnId) { "Finish the pending notification before starting another turn" }
        if (pending == null) update(state.copy(turn = PendingTurn(turnId, input)))
        return true
    }

    internal fun finish(responseId: String)
    {
        val turnId = state.turn!!.id
        update(state.copy(previousResponseId = responseId, turn = null,
            completedTurnIds = (state.completedTurnIds + turnId).distinct().takeLast(2048),
            unacknowledgedTurnIds = (state.unacknowledgedTurnIds + turnId).distinct()))
    }
}

/** Avoid mixing state across accounts without storing API keys in the session file. */
fun responsesSessionScope(forum: String, username: String, apiUrl: String, apiKey: String): String =
    MessageDigest.getInstance("SHA-256").digest(listOf(forum.trimEnd('/'), username, apiUrl, apiKey)
        .joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
