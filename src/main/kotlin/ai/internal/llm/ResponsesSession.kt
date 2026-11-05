package moe.tachyon.windwhisper.ai.internal.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    val deferredInput: List<JsonObject> = emptyList(),
)

@Serializable
internal data class PendingTurn(
    val id: String,
    val input: List<JsonObject>,
    val responseId: String? = null,
    val toolResults: Map<String, JsonObject> = emptyMap(),
    val imageNotices: Map<String, String> = emptyMap(),
    val executingToolId: String? = null,
    val deliveryIds: List<String> = listOf(id),
    val toolCallIds: List<String> = emptyList(),
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
    val pendingDeliveryIds: List<String> get() = state.turn?.deliveryIds.orEmpty()
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

    internal fun begin(turnId: String, input: List<JsonObject>, deliveryIds: List<String> = listOf(turnId)): Boolean
    {
        val pending = state.turn
        require(pending == null || pending.id == turnId) { "Finish the pending notification before starting another turn" }
        if (pending != null) return true
        require(deliveryIds.isNotEmpty() && deliveryIds.all { it.isNotBlank() } && deliveryIds.distinct() == deliveryIds)
        if (deliveryIds.all(::isCompleted)) return false
        require(deliveryIds.none(::isCompleted)) { "Remove already handled notifications before starting a batch" }
        update(state.copy(turn = PendingTurn(turnId, state.deferredInput + input, deliveryIds = deliveryIds), deferredInput = emptyList()))
        return true
    }

    internal fun finish(responseId: String)
    {
        completeDeliveries(responseId, emptyList())
    }

    /** End a failed batch without replaying its input or executing remaining calls. */
    internal fun failPendingTurn()
    {
        val turn = state.turn ?: return
        val retainExecutedResponse = turn.responseId != null && turn.toolCallIds.isNotEmpty() &&
            (turn.toolResults.isNotEmpty() || turn.executingToolId != null)
        val carried = if (retainExecutedResponse)
            turn.toolCallIds.map { id -> turn.toolResults[id] ?: buildJsonObject {
                put("type", "function_call_output")
                put("call_id", id)
                put("output", if (id == turn.executingToolId)
                    "本批处理已终止，此操作的执行结果未确认。"
                else "本批处理已失败，此调用未执行。")
            } }
        else turn.input.filter { it["type"] == JsonPrimitive("function_call_output") || it["role"] == JsonPrimitive("developer") }
        completeDeliveries(if (retainExecutedResponse) turn.responseId else state.previousResponseId, carried)
    }

    private fun completeDeliveries(responseId: String?, deferredInput: List<JsonObject>)
    {
        val turn = state.turn!!
        update(state.copy(previousResponseId = responseId, turn = null, deferredInput = deferredInput,
            completedTurnIds = (state.completedTurnIds + turn.deliveryIds + turn.id).distinct().takeLast(2048),
            unacknowledgedTurnIds = (state.unacknowledgedTurnIds + turn.deliveryIds).distinct()))
    }
}

/** Avoid mixing state across accounts without storing API keys in the session file. */
fun responsesSessionScope(forum: String, username: String, apiUrl: String, apiKey: String): String =
    MessageDigest.getInstance("SHA-256").digest(listOf(forum.trimEnd('/'), username, apiUrl, apiKey)
        .joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
