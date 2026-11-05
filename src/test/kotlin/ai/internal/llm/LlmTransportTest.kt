package moe.tachyon.windwhisper.ai.internal.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.StreamAiResponseSlice
import moe.tachyon.windwhisper.ai.TokenUsage
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class LlmTransportTest
{
    @Serializable
    data class EchoArgs(val text: String)

    @BeforeTest
    fun setUp() = initializeTestEnvironment()

    @Test
    fun `non-streaming tool roundtrip replays reasoning and original call ids`() = runBlocking {
        val calls = mutableListOf<String>()
        val first = completed(listOf(reasoning(), functionCall("a", "first")), 7, 3)
        val second = completed(listOf(message("Finished")), 11, 5)
        MockServer { index, request ->
            assertEquals("POST", request.method)
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer unit-test-key", request.authorization)
            assertFalse("messages" in request.body)
            assertFalse("max_tokens" in request.body)
            assertEquals(120, request.body.getValue("max_output_tokens").jsonPrimitive.int)
            assertEquals(false, request.body.getValue("store").jsonPrimitive.boolean)
            assertTrue(JsonPrimitive("reasoning.encrypted_content") in request.body.getValue("include").jsonArray)
            val tool = request.body.getValue("tools").jsonArray.single().jsonObject
            assertEquals("function", tool.getValue("type").jsonPrimitive.content)
            assertEquals("echo", tool.getValue("name").jsonPrimitive.content)
            assertEquals(false, tool.getValue("strict").jsonPrimitive.boolean)
            assertFalse("function" in tool)
            if (index == 0) MockResponse(first)
            else
            {
                assertEquals(1, index)
                val input = request.body.getValue("input").jsonArray.map { it.jsonObject }
                assertEquals("encrypted-test-state", input.single { it["type"] == JsonPrimitive("reasoning") }["encrypted_content"]?.jsonPrimitive?.content)
                val call = input.single { it["type"] == JsonPrimitive("function_call") }
                assertEquals("call_a", call["call_id"]?.jsonPrimitive?.content)
                assertEquals("{\"text\":\"first\"}", call["arguments"]?.jsonPrimitive?.content)
                val output = input.single { it["type"] == JsonPrimitive("function_call_output") }
                assertEquals("call_a", output["call_id"]?.jsonPrimitive?.content)
                assertEquals("echo:first", output["output"]?.jsonPrimitive?.content)
                MockResponse(second)
            }
        }.use { server ->
            val result = withTimeout(10_000) {
                sendAiRequest(server.model(), ChatMessages(Role.USER, "echo first"), maxTokens = 120,
                    tools = listOf(echoTool(calls)), stream = false)
            }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("first"), calls)
            assertEquals(2, server.requests.size)
            assertEquals("Finished", result.messages.last().content.toText())
            assertEquals(TokenUsage(completionTokens = 8, promptTokens = 18, totalTokens = 26), result.usage)
            assertEquals("call_a", result.messages.first().toolCalls.single().id)
            assertEquals(first["output"]?.jsonArray?.toList(), result.messages.first().responsesOutput)
        }
    }

    @Test
    fun `streaming parallel calls execute once after completed and continue without DONE sentinel`() = runBlocking {
        val calls = mutableListOf<String>()
        val slices = mutableListOf<StreamAiResponseSlice>()
        val first = completed(listOf(reasoning("Planning"), functionCall("a", "one"), functionCall("b", "two")), 7, 3)
        val final = completed(listOf(message("done")), 11, 5)
        MockServer { index, request ->
            assertTrue(request.body.getValue("stream").jsonPrimitive.boolean)
            when (index)
            {
                0 -> MockResponse.sse(
                    event("response.created", "response" to buildJsonObject { put("status", "in_progress") }),
                    delta("response.reasoning_summary_text.delta", "Planning", outputIndex = 0, itemId = "rs_test"),
                    event("response.output_item.added", "output_index" to JsonPrimitive(1), "item" to functionCall("a", "").let {
                        JsonObject(it + ("arguments" to JsonPrimitive("")))
                    }),
                    delta("response.function_call_arguments.delta", "{\"text\":", outputIndex = 1, itemId = "fc_a"),
                    event("response.output_item.added", "output_index" to JsonPrimitive(2), "item" to functionCall("b", "").let {
                        JsonObject(it + ("arguments" to JsonPrimitive("")))
                    }),
                    delta("response.function_call_arguments.delta", "{\"text\":\"two\"}", outputIndex = 2, itemId = "fc_b"),
                    delta("response.function_call_arguments.delta", "\"one\"}", outputIndex = 1, itemId = "fc_a"),
                    event("response.output_item.done", "output_index" to JsonPrimitive(1), "item" to functionCall("a", "one")),
                    event("response.output_item.done", "output_index" to JsonPrimitive(2), "item" to functionCall("b", "two")),
                    event("response.completed", "response" to first),
                )
                1 -> {
                    val input = request.body.getValue("input").jsonArray.map { it.jsonObject }
                    assertEquals(listOf("call_a", "call_b"), input.filter { it["type"] == JsonPrimitive("function_call_output") }
                        .map { it.getValue("call_id").jsonPrimitive.content })
                    MockResponse.sse(
                        delta("response.output_text.delta", "do"),
                        delta("response.output_text.delta", "ne"),
                        event("response.completed", "response" to final),
                    )
                }
                else -> error("Unexpected request $index")
            }
        }.use { server ->
            val result = withTimeout(10_000) {
                sendAiRequest(server.model(), ChatMessages(Role.USER, "echo twice"),
                    tools = listOf(echoTool(calls)), stream = true, onReceive = slices::add)
            }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("one", "two"), calls)
            assertEquals(2, server.requests.size)
            assertEquals("done", result.messages.last().content.toText())
            assertEquals("done", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.content })
            assertEquals("Planning", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.reasoningContent })
            assertEquals(listOf("call_a", "call_b"), slices.filterIsInstance<StreamAiResponseSlice.ToolCall>().map { it.id })
            assertEquals(TokenUsage(completionTokens = 8, promptTokens = 18, totalTokens = 26), result.usage)
        }
    }

    @Test
    fun `truncated stream retains partial text and never executes a pending tool`() = runBlocking {
        val calls = mutableListOf<String>()
        MockServer { _, _ -> MockResponse.sse(
            delta("response.output_text.delta", "partial"),
            event("response.output_item.done", "output_index" to JsonPrimitive(1), "item" to functionCall("a", "unsafe")),
        ) }.use { server ->
            val result = withTimeout(10_000) {
                sendAiRequest(server.model(), ChatMessages(Role.USER, "test truncation"),
                    tools = listOf(echoTool(calls)), stream = true)
            }
            server.verify()
            assertIs<AiResult.UnknownError>(result)
            assertTrue(calls.isEmpty())
            assertEquals("partial", result.messages.single().content.toText())
        }
    }

    @Test
    fun `failed and incomplete responses do not execute tools in either transport`() = runBlocking {
        for (stream in listOf(false, true)) for (status in listOf("failed", "incomplete"))
        {
            val calls = mutableListOf<String>()
            val response = JsonObject(completed(listOf(functionCall("a", "unsafe"))) + mapOf(
                "status" to JsonPrimitive(status),
                "error" to buildJsonObject { put("code", "test_failure"); put("message", "fixture failure") },
                "incomplete_details" to buildJsonObject { put("reason", "max_output_tokens") },
            ))
            MockServer { _, _ ->
                if (stream) MockResponse.sse(event("response.$status", "response" to response))
                else MockResponse(response)
            }.use { server ->
                val result = withTimeout(10_000) {
                    sendAiRequest(server.model(), ChatMessages(Role.USER, "test terminal failure"),
                        tools = listOf(echoTool(calls)), stream = stream)
                }
                server.verify()
                assertIs<AiResult.UnknownError>(result, "stream=$stream, status=$status")
                assertTrue(calls.isEmpty(), "stream=$stream, status=$status")
            }
        }
    }

    @Test
    fun `non-streaming rate limit is classified after retries`() = runBlocking {
        MockServer { _, _ -> MockResponse(
            buildJsonObject { putJsonObject("error") { put("message", "rate limited"); put("type", "rate_limit_error") } },
            status = 429,
        ) }.use { server ->
            val result = withTimeout(10_000) {
                sendAiRequest(server.model(), ChatMessages(Role.USER, "hello"), stream = false)
            }
            server.verify()
            assertIs<AiResult.TooManyRequests>(result)
            assertTrue(server.requests.isNotEmpty())
        }
    }

    @Test
    fun `successful retry does not inherit earlier HTTP failure`() = runBlocking {
        MockServer { index, _ ->
            if (index == 0) MockResponse(
                buildJsonObject { putJsonObject("error") { put("message", "temporarily unavailable") } }, status = 503)
            else MockResponse(completed(listOf(message("Recovered"))))
        }.use { server ->
            val result = withTimeout(10_000) {
                sendAiRequest(server.model(), ChatMessages(Role.USER, "hello"), stream = false)
            }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals("Recovered", result.messages.single().content.toText())
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `legacy configuration still sends Chat Completions body`() = runBlocking {
        MockServer { _, request ->
            assertEquals("/v1/chat/completions", request.path)
            assertTrue("messages" in request.body)
            assertFalse("input" in request.body)
            assertFalse("store" in request.body)
            assertEquals(64, request.body.getValue("max_tokens").jsonPrimitive.int)
            MockResponse(Json.parseToJsonElement("""{
                "choices":[{"message":{"role":"assistant","content":"legacy reply"},"finish_reason":"stop","index":0}],
                "usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}
            }""").jsonObject)
        }.use { server ->
            val model = AiConfig.LlmModel(url = server.url + "/v1/chat/completions", model = "legacy-model", key = listOf("unit-test-key"))
            val result = withTimeout(10_000) {
                sendAiRequest(model, ChatMessages(Role.USER, "hello"), maxTokens = 64, stream = false)
            }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals("legacy reply", result.messages.single().content.toText())
            assertEquals(TokenUsage(3, 2, 5), result.usage)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `Chat text containing old tool tags stays literal in both transports`() = runBlocking {
        val literal = """Before <|tool_call|><|tool_name|>echo<|tool_name|><|tool_args|>{"text":"unsafe"}<|tool_args|><|tool_call|> after <|tool_ca"""
        for (stream in listOf(false, true))
        {
            val calls = mutableListOf<String>()
            val slices = mutableListOf<StreamAiResponseSlice>()
            MockServer { _, request ->
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("echo", request.body.getValue("tools").jsonArray.single().jsonObject
                    .getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
                if (!stream) MockResponse(chatCompletion(literal)) else MockResponse.chatSse(
                    *(literal.chunked(9).map { chatChunk(content = it) } + chatChunk(finishReason = "stop")).toTypedArray(),
                )
            }.use { server ->
                val result = withTimeout(10_000) {
                    sendAiRequest(server.chatModel(), ChatMessages(Role.USER, "Repeat literal text"),
                        tools = listOf(echoTool(calls)), stream = stream, onReceive = slices::add)
                }
                server.verify()
                assertIs<AiResult.Success>(result, "stream=$stream")
                assertEquals(literal, result.messages.single().content.toText())
                assertTrue(calls.isEmpty())
                assertTrue(slices.filterIsInstance<StreamAiResponseSlice.ToolCall>().isEmpty())
                assertEquals(1, server.requests.size)
                if (stream) assertEquals(literal,
                    slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.content })
            }
        }
    }

    @Test
    fun `Chat streaming keeps original ids and accumulates interleaved native calls`() = runBlocking {
        val calls = mutableListOf<String>()
        val slices = mutableListOf<StreamAiResponseSlice>()
        MockServer { index, request ->
            when (index)
            {
                0 -> MockResponse.chatSse(
                    chatChunk(calls = listOf(JsonObject(chatTool(0, "chat_original_a") - "function"))),
                    chatChunk(calls = listOf(chatTool(0, name = "ec", arguments = "{\"te"))),
                    chatChunk(calls = listOf(chatTool(1, "chat_original_b", "e", "{\"text\":\""))),
                    chatChunk(calls = listOf(JsonObject(chatTool(0, name = "ho", arguments = "xt\":\"A\"}") +
                        mapOf("id" to JsonNull, "type" to JsonNull)))),
                    chatChunk(calls = listOf(JsonObject(chatTool(1) +
                        mapOf("function" to JsonNull, "type" to JsonNull)))),
                    chatChunk(calls = listOf(chatTool(1, name = "cho", arguments = "B\"}"))),
                    chatChunk(finishReason = "tool_calls"),
                )
                1 -> {
                    assertNativeChatHistory(request.body, listOf("chat_original_a" to "A", "chat_original_b" to "B"))
                    MockResponse.chatSse(chatChunk(content = "native done", reasoningContent = "Checked"), chatChunk(finishReason = "stop"))
                }
                else -> error("Unexpected request $index")
            }
        }.use { server ->
            val result = withTimeout(10_000) {
                sendAiRequest(server.chatModel(), ChatMessages(Role.USER, "Echo twice"), tools = listOf(echoTool(calls)),
                    stream = true, onReceive = slices::add)
            }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("A", "B"), calls)
            assertEquals(listOf("chat_original_a", "chat_original_b"), result.messages.first().toolCalls.map { it.id })
            assertEquals(listOf("chat_original_a", "chat_original_b"), slices.filterIsInstance<StreamAiResponseSlice.ToolCall>().map { it.id })
            assertEquals("native done", result.messages.last().content.toText())
            assertEquals("Checked", result.messages.last().reasoningContent.toText())
            assertEquals("native done", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.content })
            assertEquals("Checked", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.reasoningContent })
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `Chat non-streaming native call accepts null content and replays real tool role`() = runBlocking {
        val calls = mutableListOf<String>()
        MockServer { index, request ->
            when (index)
            {
                0 -> MockResponse(chatCompletion(null,
                    calls = listOf(chatTool(null, "chat_original_null", "echo", "{\"text\":\"A\"}")),
                    finishReason = "tool_calls"))
                1 -> {
                    assertNativeChatHistory(request.body, listOf("chat_original_null" to "A"))
                    MockResponse(chatCompletion("native done"))
                }
                else -> error("Unexpected request $index")
            }
        }.use { server ->
            val result = withTimeout(10_000) {
                sendAiRequest(server.chatModel(), ChatMessages(Role.USER, "Echo once"),
                    tools = listOf(echoTool(calls)), stream = false)
            }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("A"), calls)
            assertEquals("chat_original_null", result.messages.first().toolCalls.single().id)
            assertEquals("native done", result.messages.last().content.toText())
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `Chat malformed or unfinished native calls never execute tools`() = runBlocking {
        val validStreamCall = chatTool(0, "chat_valid", "echo", "{\"text\":\"unsafe\"}")
        val fixtures = listOf(
            Triple("stream missing id", true, MockResponse.chatSse(
                chatChunk(calls = listOf(chatTool(0, name = "echo", arguments = "{\"text\":\"unsafe\"}"))),
                chatChunk(finishReason = "tool_calls"))),
            Triple("stream missing index", true, MockResponse.chatSse(
                chatChunk(calls = listOf(chatTool(null, "chat_missing_index", "echo", "{\"text\":\"unsafe\"}"))),
                chatChunk(finishReason = "tool_calls"))),
            Triple("stream missing name", true, MockResponse.chatSse(
                chatChunk(calls = listOf(chatTool(0, "chat_missing_name", arguments = "{\"text\":\"unsafe\"}"))),
                chatChunk(finishReason = "tool_calls"))),
            Triple("stream premature EOF", true, MockResponse.chatSse(
                chatChunk(calls = listOf(validStreamCall)), done = false)),
            Triple("stream length finish", true, MockResponse.chatSse(
                chatChunk(calls = listOf(validStreamCall)), chatChunk(finishReason = "length"))),
            Triple("nonstream missing id", false, MockResponse(chatCompletion(null,
                calls = listOf(chatTool(null, name = "echo", arguments = "{\"text\":\"unsafe\"}")), finishReason = "tool_calls"))),
            Triple("nonstream missing arguments", false, MockResponse(chatCompletion(null,
                calls = listOf(chatTool(null, "chat_missing_args", "echo")), finishReason = "tool_calls"))),
            Triple("nonstream length finish", false, MockResponse(chatCompletion(null,
                calls = listOf(chatTool(null, "chat_valid", "echo", "{\"text\":\"unsafe\"}")), finishReason = "length"))),
        )
        for ((description, stream, response) in fixtures)
        {
            val calls = mutableListOf<String>()
            val slices = mutableListOf<StreamAiResponseSlice>()
            MockServer { _, _ -> response }.use { server ->
                val result = withTimeout(10_000) {
                    sendAiRequest(server.chatModel(), ChatMessages(Role.USER, "Reject invalid calls"),
                        tools = listOf(echoTool(calls)), stream = stream, onReceive = slices::add)
                }
                server.verify()
                assertIs<AiResult.UnknownError>(result, description)
                assertTrue(calls.isEmpty(), description)
                assertTrue(slices.filterIsInstance<StreamAiResponseSlice.ToolCall>().isEmpty(), description)
            }
        }
    }

    private fun assertNativeChatHistory(body: JsonObject, expected: List<Pair<String, String>>)
    {
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(1, messages.count { it["role"] == JsonPrimitive("user") })
        val assistantCalls = messages.single { it["role"] == JsonPrimitive("assistant") }
            .getValue("tool_calls").jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it.first }, assistantCalls.map { it.getValue("id").jsonPrimitive.content })
        expected.zip(assistantCalls).forEach { (expectedCall, actualCall) ->
            val function = actualCall.getValue("function").jsonObject
            assertEquals("echo", function.getValue("name").jsonPrimitive.content)
            assertEquals(buildJsonObject { put("text", expectedCall.second) },
                Json.parseToJsonElement(function.getValue("arguments").jsonPrimitive.content))
        }
        val outputs = messages.filter { it["role"] == JsonPrimitive("tool") }
        assertEquals(expected.map { it.first }, outputs.map { it.getValue("tool_call_id").jsonPrimitive.content })
        assertEquals(expected.map { "echo:${it.second}" }, outputs.map { it.getValue("content").jsonPrimitive.content })
    }

    private fun chatTool(index: Int?, id: String? = null, name: String? = null, arguments: String? = null) = buildJsonObject {
        index?.let { put("index", it) }
        id?.let { put("id", it); put("type", "function") }
        putJsonObject("function") {
            name?.let { put("name", it) }
            arguments?.let { put("arguments", it) }
        }
    }

    private fun chatChunk(content: String? = null, calls: List<JsonObject> = emptyList(), finishReason: String? = null,
        reasoningContent: String? = null) = buildJsonObject {
        putJsonArray("choices") {
            add(buildJsonObject {
                put("index", 0)
                put("finish_reason", finishReason?.let(::JsonPrimitive) ?: JsonNull)
                putJsonObject("delta") {
                    content?.let { put("content", it) }
                    reasoningContent?.let { put("reasoning_content", it) }
                    put("tool_calls", if (calls.isNotEmpty()) JsonArray(calls) else JsonNull)
                }
            })
        }
    }

    private fun chatCompletion(content: String?, calls: List<JsonObject> = emptyList(), finishReason: String = "stop") = buildJsonObject {
        putJsonArray("choices") {
            add(buildJsonObject {
                put("index", 0)
                put("finish_reason", finishReason)
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content?.let(::JsonPrimitive) ?: JsonNull)
                    if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
                }
            })
        }
        putJsonObject("usage") { put("prompt_tokens", 2); put("completion_tokens", 3); put("total_tokens", 5) }
    }

    private fun echoTool(calls: MutableList<String>) = AiToolInfo<EchoArgs>("echo", "Echo input text", "Echo") { args, _ ->
        calls += args.text
        AiToolInfo.ToolResult(Content("echo:${args.text}"))
    }

    private fun reasoning(summary: String = "") = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_test")
        put("encrypted_content", "encrypted-test-state")
        putJsonArray("summary") {
            if (summary.isNotEmpty()) add(buildJsonObject { put("type", "summary_text"); put("text", summary) })
        }
    }

    private fun functionCall(id: String, text: String) = buildJsonObject {
        put("type", "function_call")
        put("id", "fc_$id")
        put("call_id", "call_$id")
        put("name", "echo")
        put("arguments", buildJsonObject { put("text", text) }.toString())
        put("status", "completed")
    }

    private fun message(text: String) = buildJsonObject {
        put("type", "message")
        put("id", "msg_test")
        put("role", "assistant")
        put("status", "completed")
        putJsonArray("content") {
            add(buildJsonObject { put("type", "output_text"); put("text", text); put("annotations", JsonArray(emptyList())) })
        }
    }

    private fun completed(output: List<JsonObject>, inputTokens: Int = 2, outputTokens: Int = 3) = buildJsonObject {
        put("id", "resp_test")
        put("object", "response")
        put("status", "completed")
        put("output", JsonArray(output))
        putJsonObject("usage") {
            put("input_tokens", inputTokens)
            put("output_tokens", outputTokens)
            put("total_tokens", inputTokens + outputTokens)
        }
    }

    private fun event(type: String, vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields)

    private fun delta(type: String, text: String, outputIndex: Int = 0, itemId: String = "msg_test") = event(type,
        "delta" to JsonPrimitive(text), "output_index" to JsonPrimitive(outputIndex), "item_id" to JsonPrimitive(itemId),
        "content_index" to JsonPrimitive(0), "summary_index" to JsonPrimitive(0))

    private data class RecordedRequest(val method: String, val path: String, val authorization: String?, val body: JsonObject)

    private data class MockResponse(val body: String, val status: Int = 200, val contentType: String = "application/json")
    {
        constructor(body: JsonObject, status: Int = 200): this(body.toString(), status)

        companion object
        {
            fun sse(vararg events: JsonObject) = MockResponse(
                events.joinToString("") { "event: ${it.getValue("type").jsonPrimitive.content}\ndata: $it\n\n" },
                contentType = "text/event-stream",
            )

            fun chatSse(vararg chunks: JsonObject, done: Boolean = true) = MockResponse(
                chunks.joinToString("") { "data: $it\n\n" } + if (done) "data: [DONE]\n\n" else "",
                contentType = "text/event-stream",
            )
        }
    }

    private class MockServer(private val respond: (Int, RecordedRequest) -> MockResponse): AutoCloseable
    {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try
                {
                    val request = RecordedRequest(exchange.requestMethod, exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Authorization"),
                        Json.parseToJsonElement(exchange.requestBody.use { it.readBytes().decodeToString() }).jsonObject)
                    requests += request
                    val response = respond(requests.lastIndex, request)
                    exchange.responseHeaders.set("Content-Type", response.contentType)
                    val bytes = response.body.encodeToByteArray()
                    exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                catch (failure: Throwable)
                {
                    failures += failure
                    runCatching { exchange.sendResponseHeaders(500, -1) }
                }
                finally
                {
                    exchange.close()
                }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"

        fun model() = AiConfig.LlmModel(url = "$url/v1/responses", model = "test-model", key = listOf("unit-test-key"),
            apiType = AiConfig.ApiType.RESPONSES)

        fun chatModel() = AiConfig.LlmModel(url = "$url/v1/chat/completions", model = "test-model", key = listOf("unit-test-key"))

        fun verify()
        {
            failures.firstOrNull()?.let { throw AssertionError("Mock server assertion failed", it) }
        }

        override fun close() = server.stop(0)
    }
}
