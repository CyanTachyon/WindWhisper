package moe.tachyon.windwhisper.ai.internal.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponsesResponseTest
{
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun response(output: String, status: String = "completed", extra: String = ""): JsonObject =
        json("""{"status":"$status","output":[$output]$extra}""")

    private fun message(text: String): String =
        """{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"$text","annotations":[]}]}"""

    private fun call(id: String, name: String = "lookup"): String =
        """{"id":"fc_$id","type":"function_call","call_id":"$id","name":"$name","arguments":"{}","status":"completed"}"""

    private fun terminal(response: JsonObject, type: String = "response.completed"): JsonObject =
        json("""{"type":"$type","response":$response}""")

    private fun citation(start: Int, end: Int, url: String = "https://example.com/source", title: String = "Source"): JsonObject =
        buildJsonObject()
        {
            put("type", "url_citation")
            put("start_index", start)
            put("end_index", end)
            put("url", url)
            put("title", title)
        }

    private fun citedMessage(text: String, vararg annotations: JsonElement): JsonObject = buildJsonObject()
    {
        put("id", "msg_cited")
        put("type", "message")
        put("role", "assistant")
        put("status", "completed")
        putJsonArray("content")
        {
            addJsonObject()
            {
                put("type", "output_text")
                put("text", text)
                put("annotations", JsonArray(annotations.toList()))
            }
        }
    }

    @Test
    fun `decodes ordered output and preserves reasoning and native tool call ids`()
    {
        val reasoning = """{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"Checking sources"}],"encrypted_content":"encrypted reasoning"}"""
        val response = response("$reasoning,${message("Let me check.")},${call("call_one")},${call("call_two", "read")}",
            extra = ""","usage":{"input_tokens":12,"output_tokens":8,"total_tokens":20}""")

        val result = parseResponsesResponse(response)

        assertNull(result.error)
        assertEquals("Let me check.", result.message.content.toText())
        assertEquals("Checking sources", result.message.reasoningContent.toText())
        assertEquals(listOf("call_one", "call_two"), result.toolCalls.keys.toList())
        assertEquals(listOf("call_one", "call_two"), result.message.toolCalls.map { it.id })
        assertEquals("read" to "{}", result.toolCalls["call_two"])
        assertEquals(TokenUsage(8, 12, 20), result.usage)
        assertEquals(response["output"], JsonArray(result.message.responsesOutput))
    }

    @Test
    fun `tool only completed response is not blank`()
    {
        val result = parseResponsesResponse(response(call("call_one")))
        assertNull(result.error)
        assertTrue(!result.message.isBlank())
        assertEquals("lookup" to "{}", result.toolCalls["call_one"])
    }

    @Test
    fun `refusal is displayed as text`()
    {
        val result = parseResponsesResponse(response("""{"type":"message","content":[{"type":"refusal","refusal":"Cannot help with that."}]}"""))
        assertNull(result.error)
        assertEquals("Cannot help with that.", result.message.content.toText())
    }

    @Test
    fun `status failures retain partial text and usage but never dispatch calls`()
    {
        val result = parseResponsesResponse(response("${message("Partial")},${call("call_partial")}", "incomplete",
            ""","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":4,"output_tokens":2}"""))
        assertNotNull(result.error)
        assertTrue(result.error!!.message!!.contains("max_output_tokens"))
        assertEquals("Partial", result.message.content.toText())
        assertEquals(TokenUsage(2, 4, 6), result.usage)
        assertTrue(result.toolCalls.isEmpty())
        assertTrue(result.message.toolCalls.isEmpty())
    }

    @Test
    fun `failed response exposes API error`()
    {
        val result = parseResponsesResponse(response("", "failed",
            ""","error":{"code":"server_error","message":"Try again later"}"""))
        assertNotNull(result.error)
        assertTrue(result.error!!.message!!.contains("server_error"))
        assertTrue(result.error!!.message!!.contains("Try again later"))
    }

    @Test
    fun `missing status malformed calls and unsupported only output fail`()
    {
        val invalidResponses = listOf(
            json("""{"output":[${message("Hello")}]}"""),
            response("""{"type":"function_call","id":"fc_wrong_id","name":"lookup","arguments":"{}"}"""),
            response("${call("same")},${call("same")}"),
            response("""{"type":"image_generation_call","result":"base64"}"""),
            response("""{"type":"reasoning","summary":[],"encrypted_content":"cipher"}"""),
            json("""{"status":"completed","output":"invalid"}"""),
        )
        invalidResponses.forEach { response ->
            val result = parseResponsesResponse(response)
            assertNotNull(result.error, response.toString())
            assertTrue(result.toolCalls.isEmpty())
        }
    }

    @Test
    fun `stream emits deltas once and takes final snapshot calls and usage`() = runBlocking()
    {
        val state = ResponsesStreamState()
        val slices = mutableListOf<StreamAiResponseSlice>()
        val receive: suspend (StreamAiResponseSlice) -> Unit = { slices += it }
        state.accept(json("""{"type":"response.created","response":{"status":"in_progress"}}"""), receive)
        state.accept(json("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hel"}"""), receive)
        state.accept(json("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"lo"}"""), receive)
        state.accept(json("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{"}"""), receive)
        val final = response("${message("Hello")},${call("real_call_id")}", extra = ""","usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}""")
        state.accept(terminal(final), receive)
        state.accept(terminal(final), receive)

        val result = state.result()
        assertNull(result.error)
        assertEquals(listOf("Hel", "lo"), slices.filterIsInstance<StreamAiResponseSlice.Message>().map { it.content })
        assertEquals("Hello", result.message.content.toText())
        assertEquals("lookup" to "{}", result.toolCalls["real_call_id"])
        assertEquals(TokenUsage(3, 7, 10), result.usage)
        assertEquals(final["output"], JsonArray(result.message.responsesOutput))
    }

    @Test
    fun `stream fills missing suffix and missing message parts from snapshot`() = runBlocking()
    {
        val state = ResponsesStreamState()
        val slices = mutableListOf<StreamAiResponseSlice>()
        val receive: suspend (StreamAiResponseSlice) -> Unit = { slices += it }
        state.accept(json("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hel"}"""), receive)
        state.accept(terminal(response("${message("Hello")},${message(" world")}")), receive)
        assertEquals("Hello world", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.content })
        assertEquals("Hello world", state.result().message.content.toText())
    }

    @Test
    fun `streamed reasoning summary and refusal do not repeat at completion`() = runBlocking()
    {
        val state = ResponsesStreamState()
        val slices = mutableListOf<StreamAiResponseSlice>()
        val receive: suspend (StreamAiResponseSlice) -> Unit = { slices += it }
        state.accept(json("""{"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":0,"delta":"Checking"}"""), receive)
        state.accept(json("""{"type":"response.refusal.delta","output_index":1,"content_index":0,"delta":"Cannot help."}"""), receive)
        state.accept(terminal(response("""{"type":"reasoning","summary":[{"type":"summary_text","text":"Checking"}],"encrypted_content":"cipher"},{"type":"message","content":[{"type":"refusal","refusal":"Cannot help."}]}""")), receive)

        assertNull(state.result().error)
        assertEquals(2, slices.size)
        assertEquals("Checking", state.result().message.reasoningContent.toText())
        assertEquals("Cannot help.", state.result().message.content.toText())
    }

    @Test
    fun `stream truncation retains visible text but cannot execute partial calls`() = runBlocking()
    {
        val state = ResponsesStreamState()
        state.accept(json("""{"type":"response.output_text.delta","delta":"Partial"}""")) {}
        state.accept(json("""{"type":"response.output_item.done","output_index":1,"item":${call("call_partial")}}""")) {}
        val result = state.result()
        assertNotNull(result.error)
        assertEquals("Partial", result.message.content.toText())
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `terminal failure and incomplete events cannot dispatch calls`() = runBlocking()
    {
        for ((eventType, status) in listOf("response.failed" to "failed", "response.incomplete" to "incomplete"))
        {
            val state = ResponsesStreamState()
            state.accept(json("""{"type":"response.output_text.delta","delta":"Partial"}""")) {}
            state.accept(terminal(response(call("call_partial"), status), eventType)) {}
            val result = state.result()
            assertNotNull(result.error)
            assertEquals("Partial", result.message.content.toText())
            assertTrue(result.toolCalls.isEmpty())
        }
    }

    @Test
    fun `error event preserves partial answer and API message`() = runBlocking()
    {
        val state = ResponsesStreamState()
        state.accept(json("""{"type":"response.output_text.delta","delta":"Partial"}""")) {}
        state.accept(json("""{"type":"error","code":"server_error","message":"Connection lost"}""")) {}
        val result = state.result()
        assertNotNull(result.error)
        assertTrue(result.error!!.message!!.contains("Connection lost"))
        assertEquals("Partial", result.message.content.toText())
    }

    @Test
    fun `transport error overrides completed snapshot and prevents dispatch`() = runBlocking()
    {
        val state = ResponsesStreamState()
        state.accept(terminal(response(call("call_one")))) {}
        val failure = IllegalStateException("transport failed")
        val result = state.result(failure)
        assertEquals(failure, result.error)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `malformed terminal event is a failure`(): Unit = runBlocking()
    {
        val state = ResponsesStreamState()
        state.accept(json("""{"type":"response.completed"}""")) {}
        assertNotNull(state.result().error)
    }

    @Test
    fun `hosted search items remain raw context and never become local tool calls`()
    {
        val search = """{"id":"ws_1","type":"web_search_call","status":"completed","action":{"type":"search","query":"weather"}}"""
        val cited = citedMessage("Found it.", citation(0, 9))
        val response = response("$search,$cited")
        val result = parseResponsesResponse(response)

        assertNull(result.error)
        assertTrue(result.toolCalls.isEmpty())
        assertTrue(result.message.toolCalls.isEmpty())
        assertEquals("Found it. [Source](<https://example.com/source>)", result.message.content.toText())
        assertEquals(response["output"], JsonArray(result.message.responsesOutput))
    }

    @Test
    fun `citations replace known markers while retaining raw output for replay`()
    {
        val marker = "\uE200cite\uE202turn0search0\uE201"
        val cited = citedMessage("A fact.$marker More.", citation(7, 7 + marker.length))
        val response = response(cited.toString())
        val result = parseResponsesResponse(response)

        assertEquals("A fact. [Source](<https://example.com/source>) More.", result.message.content.toText())
        assertEquals(cited, result.message.responsesOutput.single())
    }

    @Test
    fun `citation offsets preserve claims and unicode supplementary characters`()
    {
        val result = parseResponsesResponse(response(citedMessage("😀事实。尾部", citation(1, 4)).toString()))
        assertEquals("😀事实。 [Source](<https://example.com/source>)尾部", result.message.content.toText())
    }

    @Test
    fun `multiple citation labels and destinations are escaped as markdown`()
    {
        val cited = citedMessage("Claim", citation(0, 5, "https://example.com/a_(b)?x=1&y=2", "A [site] *report* <x> \\ end"),
            citation(0, 5, "https://example.org/路径", "Second"))
        val result = parseResponsesResponse(response(cited.toString()))
        assertEquals("Claim [A \\[site\\] \\*report\\* \\<x\\> \\\\ end](<https://example.com/a_(b)?x=1&y=2>) [Second](<https://example.org/%E8%B7%AF%E5%BE%84>)", result.message.content.toText())
    }

    @Test
    fun `malformed annotations cannot erase text or create unsafe links`()
    {
        val cited = citedMessage("Keep this claim.", JsonNull, JsonPrimitive("invalid"),
            citation(-10, 999, title = "Fallback"), citation(10, 2, "https://example.org", "Reversed"),
            citation(0, 4, "javascript:alert(1)", "Unsafe"),
            citation(0, 4, "https://exa mple.com", "Invalid"),
            json("""{"type":"url_citation","url":"https://example.net","start_index":"bad"}"""))
        val result = parseResponsesResponse(response(cited.toString()))
        assertNull(result.error)
        assertEquals("Keep this claim. [Fallback](<https://example.com/source>) [Reversed](<https://example.org>) [example\\.net](<https://example.net>)", result.message.content.toText())
        assertEquals(cited, result.message.responsesOutput.single())
    }

    @Test
    fun `hosted search buffering emits final cited text once while reasoning streams`() = runBlocking()
    {
        val state = ResponsesStreamState(bufferTextUntilCompleted = true)
        val slices = mutableListOf<StreamAiResponseSlice>()
        val receive: suspend (StreamAiResponseSlice) -> Unit = { slices += it }
        state.accept(json("""{"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":0,"delta":"Checking"}"""), receive)
        state.accept(json("""{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"One"}"""), receive)
        state.accept(json("""{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":" fact."}"""), receive)
        state.accept(json("""{"type":"response.output_text.delta","output_index":2,"content_index":0,"delta":" Two facts."}"""), receive)
        assertEquals(listOf<StreamAiResponseSlice>(StreamAiResponseSlice.Message("", "Checking")), slices)

        val reasoning = """{"type":"reasoning","summary":[{"type":"summary_text","text":"Checking"}],"encrypted_content":"cipher"}"""
        val final = response("$reasoning,${citedMessage("One fact.", citation(0, 9))},${citedMessage(" Two facts.", citation(1, 11, "https://example.org", "Second"))}")
        state.accept(terminal(final), receive)
        state.accept(terminal(final), receive)

        val visibleSlices = slices.filterIsInstance<StreamAiResponseSlice.Message>().filter { it.content.isNotEmpty() }
        assertEquals(1, visibleSlices.size)
        assertEquals(parseResponsesResponse(final).message.content.toText(), visibleSlices.single().content)
        assertEquals(visibleSlices.single().content, state.result().message.content.toText())
        assertEquals(final["output"], JsonArray(state.result().message.responsesOutput))
    }

    @Test
    fun `buffered refusal emits once at terminal`() = runBlocking()
    {
        val state = ResponsesStreamState(bufferTextUntilCompleted = true)
        val slices = mutableListOf<StreamAiResponseSlice>()
        val receive: suspend (StreamAiResponseSlice) -> Unit = { slices += it }
        state.accept(json("""{"type":"response.refusal.delta","output_index":0,"content_index":0,"delta":"Cannot help."}"""), receive)
        assertTrue(slices.isEmpty())
        state.accept(terminal(response("""{"type":"message","content":[{"type":"refusal","refusal":"Cannot help."}]}""")), receive)
        assertEquals(listOf<StreamAiResponseSlice>(StreamAiResponseSlice.Message("Cannot help.")), slices)
    }

    @Test
    fun `unbuffered snapshot with no deltas emits rendered citations`() = runBlocking()
    {
        val state = ResponsesStreamState()
        val slices = mutableListOf<StreamAiResponseSlice>()
        state.accept(terminal(response(citedMessage("Fact", citation(0, 4)).toString()))) { slices += it }
        assertEquals(listOf<StreamAiResponseSlice>(StreamAiResponseSlice.Message("Fact [Source](<https://example.com/source>)")), slices)
    }

    @Test
    fun `unbuffered citation annotations do not repeat text already streamed`() = runBlocking()
    {
        val state = ResponsesStreamState()
        val slices = mutableListOf<StreamAiResponseSlice>()
        val receive: suspend (StreamAiResponseSlice) -> Unit = { slices += it }
        state.accept(json("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Fact later"}"""), receive)
        state.accept(terminal(response(citedMessage("Fact later", citation(0, 4)).toString())), receive)
        assertEquals("Fact later [Source](<https://example.com/source>)", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.content })
        assertEquals("Fact [Source](<https://example.com/source>) later", state.result().message.content.toText())
    }
}
