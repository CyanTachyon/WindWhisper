package moe.tachyon.windwhisper.ai.internal.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.tools.AiToolSet
import moe.tachyon.windwhisper.ai.tools.ReadImage
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class LlmTransportTest
{
    @Serializable
    data class TextArgs(val text: String)

    @BeforeTest
    fun setUp() = initializeTestEnvironment()

    @Test
    fun `context count uses the count endpoint and preserves request context without generation`() = runBlocking {
        val calls = mutableListOf<String>()
        val input = listOf(toolOutput("call_previous", "Saved tool result"), userInput("Next message"))
        MockServer { index, request ->
            assertEquals(0, index)
            assertEquals("POST", request.method)
            assertEquals("/v1/responses/input_tokens", request.path)
            assertEquals("Bearer unit-test-key", request.authorization)
            assertEquals(JsonPrimitive("test-model"), request.body["model"])
            assertEquals(JsonPrimitive("resp_previous"), request.body["previous_response_id"])
            assertEquals(JsonPrimitive(INSTRUCTIONS), request.body["instructions"])
            assertEquals(input, request.input())
            val tools = request.body.getValue("tools").jsonArray.map { it.jsonObject }
            assertEquals(listOf("function", "web_search", "image_generation"), tools.map { it.getValue("type").jsonPrimitive.content })
            assertEquals(JsonPrimitive("echo"), tools.first()["name"])
            assertEquals(JsonPrimitive("string"), tools.first().getValue("parameters").jsonObject
                .getValue("properties").jsonObject.getValue("text").jsonObject["type"])
            for (field in listOf("store", "stream", "context_management", "background"))
                assertFalse(field in request.body, "Token counting must not send $field")
            MockResponse(json("""{"object":"response.input_tokens","input_tokens":12345}"""))
        }.use { server ->
            val count = bounded { countResponsesInputTokens(server.model(), input, INSTRUCTIONS,
                "resp_previous", listOf(echoTool(calls))) }
            server.verify()
            assertEquals(12345L, count)
            assertEquals(1, server.requests.size)
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun `native search and local tools continue with only tool output and keep citations`() = runBlocking {
        val calls = mutableListOf<String>()
        val session = ResponsesSession()
        val source = "https://example.com/facts"
        val search = json("""{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","queries":["facts"]}}""")
        val first = completed("resp_first", listOf(search, reasoning(), call("call_a", "echo", "facts")), 7, 3)
        val cited = message("Found facts.", source)
        MockServer { index, request ->
            assertCommonRequest(request, previous = if (index == 0) null else "resp_first")
            assertEquals(listOf("function", "web_search", "image_generation"), request.body["tools"]!!.jsonArray
                .map { it.jsonObject["type"]!!.jsonPrimitive.content })
            when (index)
            {
                0 -> MockResponse(first)
                1 -> {
                    assertEquals(listOf(toolOutput("call_a", "echo:facts")), request.input())
                    MockResponse(completed("resp_final", listOf(cited), 11, 5))
                }
                else -> error("Unexpected request $index")
            }
        }.use { server ->
            val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Find facts"),
                instructions = INSTRUCTIONS, tools = listOf(echoTool(calls)), session = session, turnId = "batch-native",
                deliveryIds = listOf("n1", "n2"), stream = false) }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("facts"), calls)
            assertEquals(TokenUsage(8, 18, 26), result.usage)
            assertEquals(first["output"], JsonArray(result.messages.first().responsesOutput))
            assertEquals(listOf(cited), result.messages.last().responsesOutput)
            assertTrue(result.messages.last().content.toText().contains(source))
            assertEquals("resp_final", session.state.previousResponseId)
            assertTrue(session.isCompleted("n1") && session.isCompleted("n2"))
            assertEquals(listOf("n1", "n2"), session.completedUnacknowledgedTurnIds)
        }
    }

    @Test
    fun `streamed interleaved tool calls execute once and final cited text is emitted once`() = runBlocking {
        val calls = mutableListOf<String>()
        val slices = mutableListOf<StreamAiResponseSlice>()
        val first = completed("resp_tools", listOf(reasoning("Planning"), call("call_a", "echo", "A"), call("call_b", "echo", "B")))
        val final = completed("resp_answer", listOf(message("Found facts.", "https://example.com/facts")))
        MockServer { index, request ->
            assertCommonRequest(request, previous = if (index == 0) null else "resp_tools")
            when (index)
            {
                0 -> MockResponse.sse(created("resp_tools"),
                    delta("response.reasoning_summary_text.delta", "Planning"),
                    delta("response.function_call_arguments.delta", "{\"text\":", 1),
                    delta("response.function_call_arguments.delta", "{\"text\":\"B\"}", 2),
                    delta("response.function_call_arguments.delta", "\"A\"}", 1), terminal(first))
                1 -> {
                    assertEquals(listOf(toolOutput("call_a", "echo:A"), toolOutput("call_b", "echo:B")), request.input())
                    MockResponse.sse(created("resp_answer"), delta("response.output_text.delta", "Found "),
                        delta("response.output_text.delta", "facts."), terminal(final))
                }
                else -> error("Unexpected request $index")
            }
        }.use { server ->
            val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Echo twice"),
                instructions = INSTRUCTIONS, tools = listOf(echoTool(calls)), onReceive = slices::add) }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("A", "B"), calls)
            assertEquals(listOf("call_a", "call_b"), slices.filterIsInstance<StreamAiResponseSlice.ToolCall>().map { it.id })
            assertEquals("Planning", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.reasoningContent })
            assertEquals(result.messages.last().content.toText(), slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.content })
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `restart continues the chain with one new notification and duplicate turns send nothing`() = runBlocking {
        withSessionFile { file ->
            MockServer { index, request ->
                assertCommonRequest(request, previous = if (index == 0) null else "resp_n1")
                assertEquals(listOf(userInput("Notification ${index + 1}")), request.input())
                MockResponse(completed("resp_n${index + 1}", listOf(message("Handled"))))
            }.use { server ->
                val first = ResponsesSession(file, "account-a")
                assertIs<AiResult.Success>(bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Notification 1"),
                    instructions = INSTRUCTIONS, session = first, turnId = "n1", stream = false) })
                val restarted = ResponsesSession(file, "account-a")
                assertEquals("resp_n1", restarted.state.previousResponseId)
                assertIs<AiResult.Success>(bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Notification 2"),
                    instructions = INSTRUCTIONS, session = restarted, turnId = "n2", stream = false) })
                val duplicate = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Duplicate must be ignored"),
                    instructions = INSTRUCTIONS, session = ResponsesSession(file, "account-a"), turnId = "n1", stream = false) }
                server.verify()
                assertIs<AiResult.Success>(duplicate)
                assertTrue(duplicate.messages.isEmpty())
                assertEquals(2, server.requests.size)
                val restored = ResponsesSession(file, "account-a")
                assertEquals("resp_n2", restored.state.previousResponseId)
                assertNull(restored.pendingTurnId)
                assertTrue(restored.isCompleted("n1") && restored.isCompleted("n2"))
                assertFalse(file.readText().contains("unit-test-key"))
            }
        }
    }

    @Test
    fun `HTTP and terminal SSE failures finish the batch once without executing tools or advancing chain`() = runBlocking {
        for (kind in listOf("429", "503", "failed", "incomplete", "missing-id"))
        {
            val calls = mutableListOf<String>()
            var saved = 0
            val session = ResponsesSession()
            session.update(session.state.copy(previousResponseId = "resp_before"))
            MockServer { _, _ ->
                when (kind)
                {
                    "429", "503" -> MockResponse(json("""{"error":{"message":"fixture error"}}"""), status = kind.toInt())
                    "missing-id" -> MockResponse(JsonObject(completed("resp_invalid", listOf(call("call_bad", "echo", "unsafe"))) - "id"))
                    else -> {
                        val failed = JsonObject(completed("resp_failed", listOf(call("call_bad", "echo", "unsafe"), image())) + ("status" to JsonPrimitive(kind)))
                        MockResponse.sse(created("resp_failed"), terminal(failed, "response.$kind"))
                    }
                }
            }.use { server ->
                val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Fail"), instructions = INSTRUCTIONS,
                    tools = listOf(echoTool(calls)), session = session, turnId = "failed-turn", stream = kind in listOf("failed", "incomplete"),
                    onGeneratedImage = { saved++; "asset" }) }
                server.verify()
                when (kind)
                {
                    "429" -> assertIs<AiResult.TooManyRequests>(result)
                    "503" -> assertIs<AiResult.ServiceError>(result)
                    else -> assertIs<AiResult.UnknownError>(result, kind)
                }
                assertTrue(calls.isEmpty(), kind)
                assertEquals(0, saved, kind)
                assertEquals("resp_before", session.state.previousResponseId, kind)
                assertTrue(session.isCompleted("failed-turn"))
                assertEquals(listOf("failed-turn"), session.completedUnacknowledgedTurnIds)
                assertNull(session.pendingTurnId)
                assertEquals(1, server.requests.size, "$kind must not retry")
                assertTrue(session.state.deferredInput.isEmpty())
            }
        }
    }

    @Test
    fun `HTTP error drops its batch without retry and a new batch can succeed`() = runBlocking {
        val session = ResponsesSession()
        MockServer { index, request ->
            if (index == 0) MockResponse(json("""{"error":{"message":"temporarily unavailable"}}"""), status = 503)
            else {
                assertEquals(1, index)
                assertEquals(listOf(userInput("New notification")), request.input())
                MockResponse(completed("resp_next", listOf(message("Handled new notification"))))
            }
        }.use { server ->
            val failed = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Drop this"),
                session = session, turnId = "failed", stream = false) }
            assertIs<AiResult.ServiceError>(failed)
            assertEquals(1, server.requests.size)
            val duplicate = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Never resubmit"),
                session = session, turnId = "failed", stream = false) }
            assertIs<AiResult.Success>(duplicate)
            assertEquals(1, server.requests.size)
            val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "New notification"),
                session = session, turnId = "new", stream = false) }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals("Handled new notification", result.messages.single().content.toText())
            assertEquals(2, server.requests.size)
            assertTrue(session.isCompleted("failed") && session.isCompleted("new"))
            assertTrue(session.state.deferredInput.isEmpty())
        }
    }

    @Test
    fun `explicit cancellation after created resumes by GET after restart without resubmitting user input`() = runBlocking {
        withSessionFile { file ->
            val calls = mutableListOf<String>()
            MockServer { index, request ->
                when (index)
                {
                    0 -> {
                        assertCommonRequest(request, null)
                        MockResponse.sse(created("resp_interrupted"), delta("response.reasoning_summary_text.delta", "Partial"))
                    }
                    1 -> {
                        assertEquals("GET", request.method)
                        assertEquals("/v1/responses/resp_interrupted", request.path)
                        MockResponse(completed("resp_interrupted", listOf(call("call_resume", "echo", "once"))))
                    }
                    2 -> {
                        assertCommonRequest(request, "resp_interrupted")
                        assertEquals(listOf(toolOutput("call_resume", "echo:once")), request.input())
                        MockResponse.sse(created("resp_recovered"), terminal(completed("resp_recovered", listOf(message("Recovered")))))
                    }
                    else -> error("Unexpected request $index")
                }
            }.use { server ->
                val first = ResponsesSession(file, "account-a")
                val interrupted = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Original notification"),
                    instructions = INSTRUCTIONS, tools = listOf(echoTool(calls)), session = first, turnId = "n1",
                    onReceive = { throw CancellationException("Test explicit cancellation") }) }
                assertIs<AiResult.Cancelled>(interrupted)
                assertTrue(calls.isEmpty())
                val restarted = ResponsesSession(file, "account-a")
                assertFalse(restarted.isCompleted("n1"))
                assertEquals("resp_interrupted", restarted.state.turn?.responseId)
                assertNull(restarted.state.previousResponseId)
                val resumed = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Do not resend this"),
                    instructions = INSTRUCTIONS, tools = listOf(echoTool(calls)), session = restarted, turnId = "n1") }
                server.verify()
                assertIs<AiResult.Success>(resumed)
                assertEquals(listOf("once"), calls)
                assertEquals(listOf("POST", "GET", "POST"), server.requests.map { it.method })
                assertEquals("resp_recovered", ResponsesSession(file, "account-a").state.previousResponseId)
            }
        }
    }

    @Test
    fun `generated image is saved then provided as a receipt before local upload and posting`() = runBlocking {
        withSessionFile { file ->
            val operations = mutableListOf<String>()
            val savedFile = File(file.parentFile, "generated.png")
            val tools = listOf(
                textTool("upload_generated_image") { asset ->
                    assertEquals("ig_1", asset)
                    assertTrue(savedFile.exists())
                    operations += "upload"
                    "![generated](https://forum.example/uploads/image.png)"
                },
                textTool("send_post") { markdown ->
                    assertEquals("![generated](https://forum.example/uploads/image.png)", markdown)
                    operations += "post"
                    "posted"
                },
            )
            val ids = listOf("resp_image", "resp_upload", "resp_post", "resp_done")
            MockServer { index, request ->
                assertCommonRequest(request, ids.getOrNull(index - 1))
                when (index)
                {
                    0 -> MockResponse(completed(ids[0], listOf(image())))
                    1 -> {
                        val receipt = request.input().single()
                        assertEquals(JsonPrimitive("developer"), receipt["role"])
                        assertTrue(receipt.getValue("content").jsonPrimitive.content.contains("ig_1"))
                        assertFalse("call_id" in receipt)
                        MockResponse(completed(ids[1], listOf(call("call_upload", "upload_generated_image", "ig_1"))))
                    }
                    2 -> {
                        assertEquals(listOf(toolOutput("call_upload", "![generated](https://forum.example/uploads/image.png)")), request.input())
                        MockResponse(completed(ids[2], listOf(call("call_post", "send_post", "![generated](https://forum.example/uploads/image.png)"))))
                    }
                    3 -> {
                        assertEquals(listOf(toolOutput("call_post", "posted")), request.input())
                        MockResponse(completed(ids[3], listOf(message("Posted"))))
                    }
                    else -> error("Unexpected request $index")
                }
            }.use { server ->
                val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Create and post an image"),
                    instructions = INSTRUCTIONS, tools = tools, session = ResponsesSession(file, "account-a"), turnId = "image-turn", stream = false,
                    onGeneratedImage = {
                        operations += "save"
                        savedFile.writeBytes(Base64.getDecoder().decode(it.base64))
                        "Generated image asset_id=${it.id}"
                    }) }
                server.verify()
                assertIs<AiResult.Success>(result)
                assertEquals(listOf("save", "upload", "post"), operations)
                assertEquals("resp_done", ResponsesSession(file, "account-a").state.previousResponseId)
                assertEquals(4, server.requests.size)
            }
        }
    }

    @Test
    fun `restart reuses checkpointed tool results and executes only the unfinished tool`() = runBlocking {
        withSessionFile { file ->
            val seed = ResponsesSession(file, "account-a")
            seed.update(seed.state.copy(previousResponseId = "resp_before", turn = PendingTurn("n1", listOf(userInput("Original")),
                responseId = "resp_tools", toolResults = mapOf("call_a" to toolOutput("call_a", "echo:A")),
                toolCallIds = listOf("call_a", "call_b"))))
            val calls = mutableListOf<String>()
            MockServer { index, request ->
                when (index)
                {
                    0 -> {
                        assertEquals("GET", request.method)
                        assertEquals("/v1/responses/resp_tools", request.path)
                        MockResponse(completed("resp_tools", listOf(call("call_a", "echo", "A"), call("call_b", "echo", "B"))))
                    }
                    1 -> {
                        assertCommonRequest(request, "resp_tools")
                        assertEquals(listOf(toolOutput("call_a", "echo:A"), toolOutput("call_b", "echo:B")), request.input())
                        MockResponse(completed("resp_done", listOf(message("Done"))))
                    }
                    else -> error("Unexpected request $index")
                }
            }.use { server ->
                val result = bounded { sendAiRequest(server.model(), ChatMessages.empty(), instructions = INSTRUCTIONS,
                    tools = listOf(echoTool(calls)), session = ResponsesSession(file, "account-a"), turnId = "n1", stream = false) }
                server.verify()
                assertIs<AiResult.Success>(result)
                assertEquals(listOf("B"), calls)
                assertTrue(ResponsesSession(file, "account-a").isCompleted("n1"))
            }
        }
    }

    @Test
    fun `uncertain in-flight checkpoint fails its old batch without requests and allows a new batch`() = runBlocking {
        withSessionFile { file ->
            val seed = ResponsesSession(file, "account-a")
            seed.update(seed.state.copy(previousResponseId = "resp_before", turn = PendingTurn("n1", listOf(userInput("Original")),
                responseId = "resp_tools", executingToolId = "call_uncertain", toolCallIds = listOf("call_uncertain"))))
            val calls = mutableListOf<String>()
            MockServer { index, request ->
                assertEquals(0, index)
                assertCommonRequest(request, "resp_tools")
                assertEquals(listOf(
                    toolOutput("call_uncertain", "本批处理已终止，此操作的执行结果未确认。"),
                    userInput("New notification"),
                ), request.input())
                MockResponse(completed("resp_new", listOf(message("New batch complete"))))
            }.use { server ->
                val session = ResponsesSession(file, "account-a")
                val failed = bounded { sendAiRequest(server.model(), ChatMessages.empty(), tools = listOf(echoTool(calls)),
                    session = session, turnId = "n1", stream = false) }
                assertIs<AiResult.UnknownError>(failed)
                assertTrue(failed.error.message.orEmpty().contains("call_uncertain"))
                assertTrue(server.requests.isEmpty())
                assertTrue(calls.isEmpty())
                val restarted = ResponsesSession(file, "account-a")
                assertNull(restarted.pendingTurnId)
                assertTrue(restarted.isCompleted("n1"))
                assertEquals("resp_tools", restarted.state.previousResponseId)
                val duplicate = bounded { sendAiRequest(server.model(), ChatMessages.empty(), session = restarted, turnId = "n1", stream = false) }
                assertIs<AiResult.Success>(duplicate)
                assertTrue(server.requests.isEmpty())
                val next = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "New notification"), instructions = INSTRUCTIONS,
                    session = restarted, turnId = "n2", stream = false) }
                server.verify()
                assertIs<AiResult.Success>(next)
                assertTrue(calls.isEmpty())
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test
    fun `ordinary and uncertain tool failures reach the model in the same turn and other calls still run`() = runBlocking {
        for (uncertain in listOf(false, true)) withSessionFile { file ->
            val executions = mutableListOf<String>()
            val errorMessage = if (uncertain) "Write may have succeeded" else "Fixture tool execution failed"
            val tools = listOf(
                echoTool(executions),
                AiToolInfo<TextArgs>("write_post", "Test remote write", "Write") { _, _ ->
                    executions += "failed write"
                    if (uncertain)
                        throw UncertainToolExecutionException(errorMessage, java.io.IOException("Connection lost after write"))
                    else throw java.io.IOException(errorMessage)
                },
            )
            MockServer { index, request ->
                assertCommonRequest(request, if (index == 0) "resp_before" else "resp_write")
                when (index)
                {
                    0 -> MockResponse(completed("resp_write", listOf(call("call_saved", "echo", "saved"),
                        call("call_write", "write_post", "post once"), call("call_after", "echo", "after failure"))))
                    1 -> {
                        val outputs = request.input()
                        assertEquals(3, outputs.size)
                        assertEquals(toolOutput("call_saved", "echo:saved"), outputs[0])
                        val detail = toolError(outputs[1], "call_write")
                        assertEquals(errorMessage, detail.getValue("message").jsonPrimitive.content)
                        assertEquals(if (uncertain) "UncertainToolExecutionException" else "IOException",
                            detail.getValue("type").jsonPrimitive.content)
                        if (uncertain) assertEquals(JsonPrimitive("unknown"), detail["outcome"])
                        else assertNull(detail["outcome"])
                        assertEquals(toolOutput("call_after", "echo:after failure"), outputs[2])
                        MockResponse(completed("resp_done", listOf(message("Handled the failure"))))
                    }
                    else -> error("A tool error must not trigger an automatic replay")
                }
            }.use { server ->
                val session = ResponsesSession(file, "account-a")
                session.update(session.state.copy(previousResponseId = "resp_before"))
                val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Write a post"),
                    instructions = INSTRUCTIONS, tools = tools, session = session, turnId = "batch-tools",
                    deliveryIds = listOf("n1", "n2"), stream = false) }
                server.verify()
                assertIs<AiResult.Success>(result)
                assertEquals(listOf("saved", "failed write", "after failure"), executions)
                val restarted = ResponsesSession(file, "account-a")
                assertNull(restarted.pendingTurnId)
                assertEquals("resp_done", restarted.state.previousResponseId)
                assertTrue(restarted.isCompleted("n1") && restarted.isCompleted("n2"))
                assertEquals(listOf("n1", "n2"), restarted.completedUnacknowledgedTurnIds)
                val duplicate = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Never repeat"),
                    instructions = INSTRUCTIONS, tools = tools, session = restarted, turnId = "batch-tools",
                    deliveryIds = listOf("n1", "n2"), stream = false) }
                assertIs<AiResult.Success>(duplicate)
                assertTrue(duplicate.messages.isEmpty())
                assertEquals(2, server.requests.size)
                assertEquals(listOf("saved", "failed write", "after failure"), executions)
                assertTrue(restarted.state.deferredInput.isEmpty())
            }
        }
    }

    @Test
    fun `rate limit details reach the model and only its new call retries the write`() = runBlocking {
        val forumError = """{"errors":["您回复的频率太快了。请等待几秒后再试。"],"error_type":"rate_limit","extras":{"wait_seconds":1}}"""
        val executions = mutableListOf<String>()
        val tool = AiToolInfo<TextArgs>("send_post", "Test forum post", "Post") { args, _ ->
            executions += args.text
            if (executions.size == 1) throw IllegalStateException("Failed to send post: 429\n$forumError")
            AiToolInfo.ToolResult(Content("posted"))
        }
        val session = ResponsesSession()
        MockServer { index, request ->
            assertCommonRequest(request, listOf(null, "resp_rate_limited", "resp_model_retry")[index])
            val response = when (index)
            {
                0 -> completed("resp_rate_limited", listOf(call("call_original", "send_post", "hello")))
                1 -> {
                    assertEquals(listOf("hello"), executions, "The client must not retry before the model sees the failure")
                    val detail = toolError(request.input().single(), "call_original")
                    assertEquals("Failed to send post: 429\n$forumError", detail.getValue("message").jsonPrimitive.content)
                    assertEquals("IllegalStateException", detail.getValue("type").jsonPrimitive.content)
                    assertNull(detail["outcome"])
                    completed("resp_model_retry", listOf(call("call_new_attempt", "send_post", "hello")))
                }
                2 -> {
                    assertEquals(listOf(toolOutput("call_new_attempt", "posted")), request.input())
                    completed("resp_done", listOf(message("Posted after rate limit")))
                }
                else -> error("Unexpected extra request")
            }
            MockResponse.sse(created(response.getValue("id").jsonPrimitive.content), terminal(response))
        }.use { server ->
            val slices = mutableListOf<StreamAiResponseSlice>()
            val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Post hello"),
                instructions = INSTRUCTIONS, tools = listOf(tool), session = session, turnId = "rate-limit",
                onReceive = slices::add) }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("hello", "hello"), executions)
            assertEquals(listOf("call_original", "call_new_attempt"), slices.filterIsInstance<StreamAiResponseSlice.ToolCall>().map { it.id })
            assertEquals(3, server.requests.size)
            assertEquals("resp_done", session.state.previousResponseId)
            assertTrue(session.isCompleted("rate-limit"))
        }
    }

    @Test
    fun `unknown tools and malformed arguments return separate errors without blocking valid calls`() = runBlocking {
        val executions = mutableListOf<String>()
        MockServer { index, request ->
            assertCommonRequest(request, if (index == 0) null else "resp_invalid_calls")
            when (index)
            {
                0 -> MockResponse(completed("resp_invalid_calls", listOf(
                    call("call_unknown", "not_registered", "unused"),
                    JsonObject(call("call_bad_json", "echo", "unused") + ("arguments" to JsonPrimitive("{"))),
                    call("call_valid", "echo", "valid"),
                )))
                1 -> {
                    val outputs = request.input()
                    assertEquals(3, outputs.size)
                    assertTrue(toolError(outputs[0], "call_unknown").getValue("message").jsonPrimitive.content.contains("not_registered"))
                    assertTrue(toolError(outputs[1], "call_bad_json").getValue("message").jsonPrimitive.content.isNotBlank())
                    assertEquals(toolOutput("call_valid", "echo:valid"), outputs[2])
                    MockResponse(completed("resp_done", listOf(message("Corrected invalid calls"))))
                }
                else -> error("Unexpected request")
            }
        }.use { server ->
            val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Use tools"),
                instructions = INSTRUCTIONS, tools = listOf(echoTool(executions)), stream = false) }
            server.verify()
            assertIs<AiResult.Success>(result)
            assertEquals(listOf("valid"), executions)
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `cancellation while model considers a tool error resumes without executing that tool again`() = runBlocking {
        withSessionFile { file ->
            var executions = 0
            val tool = AiToolInfo<TextArgs>("failing_tool", "Test failure", "Failure") { _, _ ->
                executions++
                throw java.io.IOException("Temporary failure")
            }
            MockServer { index, request ->
                when (index)
                {
                    0 -> {
                        assertCommonRequest(request, null)
                        MockResponse.sse(created("resp_failed_tool"), terminal(completed("resp_failed_tool",
                            listOf(call("call_failure", "failing_tool", "once")))))
                    }
                    1 -> {
                        assertCommonRequest(request, "resp_failed_tool")
                        assertEquals("Temporary failure", toolError(request.input().single(), "call_failure")
                            .getValue("message").jsonPrimitive.content)
                        MockResponse.sse(created("resp_considering_error"), delta("response.reasoning_summary_text.delta", "Pause here"))
                    }
                    2 -> {
                        assertEquals("GET", request.method)
                        assertEquals("/v1/responses/resp_considering_error", request.path)
                        MockResponse(completed("resp_considering_error", listOf(message("Skip this failed operation"))))
                    }
                    else -> error("Cancellation must not replay the failed tool or its model input")
                }
            }.use { server ->
                val interrupted = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Use tool"),
                    instructions = INSTRUCTIONS, tools = listOf(tool), session = ResponsesSession(file, "account-a"), turnId = "n1",
                    onReceive = { if (it is StreamAiResponseSlice.Message && it.reasoningContent == "Pause here")
                        throw CancellationException("Pause the test") }) }
                assertIs<AiResult.Cancelled>(interrupted)
                assertEquals(1, executions)
                val restarted = ResponsesSession(file, "account-a")
                assertFalse(restarted.isCompleted("n1"))
                assertEquals("resp_considering_error", restarted.state.turn?.responseId)
                assertEquals("Temporary failure", toolError(restarted.state.turn!!.input.single(), "call_failure")
                    .getValue("message").jsonPrimitive.content)
                val resumed = bounded { sendAiRequest(server.model(), ChatMessages.empty(), instructions = INSTRUCTIONS,
                    tools = listOf(tool), session = restarted, turnId = "n1") }
                server.verify()
                assertIs<AiResult.Success>(resumed)
                assertEquals(1, executions)
                assertEquals(listOf("POST", "POST", "GET"), server.requests.map { it.method })
                assertTrue(restarted.isCompleted("n1"))
            }
        }
    }

    @Test
    fun `tool cancellation stays cancelled and is not sent as a model error`() = runBlocking {
        var executions = 0
        val tool = AiToolInfo<TextArgs>("cancelled_tool", "Cancelled operation", "Cancel") { _, _ ->
            executions++
            throw CancellationException("Stop requested")
        }
        val session = ResponsesSession()
        MockServer { index, _ ->
            assertEquals(0, index, "Cancellation must not produce a function_call_output request")
            MockResponse(completed("resp_cancel", listOf(call("call_cancel", "cancelled_tool", "once"))))
        }.use { server ->
            val result = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Use tool"),
                tools = listOf(tool), session = session, turnId = "cancelled-turn", stream = false) }
            server.verify()
            assertIs<AiResult.Cancelled>(result)
            assertEquals(1, executions)
            assertEquals(1, server.requests.size)
            assertFalse(session.isCompleted("cancelled-turn"))
            assertEquals("call_cancel", session.state.turn?.executingToolId)
            assertTrue(session.state.turn!!.toolResults.isEmpty())
        }
    }

    @Test
    fun `tool event callback cancellation escapes before invocation or from progress without running later calls`() = runBlocking {
        for (cancelOnProgress in listOf(false, true))
        {
            val executions = mutableListOf<String>()
            val events = mutableListOf<StreamAiResponseSlice>()
            val cancellation = CancellationException("Cancel from tool event")
            val progressTool = AiToolInfo<TextArgs>("progress_tool", "Tool with progress", "Progress") { _, sendMessage ->
                executions += "started"
                sendMessage(Content("Progress before completion"))
                executions += "finished"
                AiToolInfo.ToolResult(Content("done"))
            }
            val thrown = assertFailsWith<CancellationException> {
                parseToolCalls(
                    linkedMapOf("call_first" to ("progress_tool" to "{\"text\":\"first\"}"),
                        "call_later" to ("echo" to "{\"text\":\"later\"}")),
                    listOf(progressTool, echoTool(executions)),
                ) { event ->
                    events += event
                    if ((!cancelOnProgress && event is StreamAiResponseSlice.ToolCall) ||
                        (cancelOnProgress && event is StreamAiResponseSlice.ToolMessage)) throw cancellation
                }
            }
            assertSame(cancellation, thrown)
            assertEquals(if (cancelOnProgress) listOf("started") else emptyList(), executions)
            assertEquals(listOf("call_first"), events.filterIsInstance<StreamAiResponseSlice.ToolCall>().map { it.id })
            assertEquals(if (cancelOnProgress) 1 else 0, events.filterIsInstance<StreamAiResponseSlice.ToolMessage>().size)
        }
    }

    @Test
    fun `completed response with still generating image drops the entire batch and never dispatches its post`() = runBlocking {
        for (stream in listOf(false, true)) withSessionFile { file ->
            val posts = mutableListOf<String>()
            var saved = 0
            MockServer { index, request ->
                assertCommonRequest(request, "resp_before")
                when (index)
                {
                    0 -> {
                        val response = completed("resp_invalid_image", listOf(
                            json("""{"type":"image_generation_call","id":"ig_pending","status":"generating"}"""),
                            call("call_post", "send_post", "must not post")))
                        if (stream) MockResponse.sse(created("resp_invalid_image"), terminal(response)) else MockResponse(response)
                    }
                    1 -> {
                        assertEquals(listOf(userInput("Only new notification")), request.input())
                        MockResponse(completed("resp_new", listOf(message("New notification handled"))))
                    }
                    else -> error("Invalid completed response must never be polled or resubmitted")
                }
            }.use { server ->
                val session = ResponsesSession(file, "account-a")
                session.update(session.state.copy(previousResponseId = "resp_before"))
                val failed = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Old combined notification batch"),
                    instructions = INSTRUCTIONS, tools = listOf(textTool("send_post") { posts += it; "posted" }),
                    session = session, turnId = "batch-invalid", deliveryIds = listOf("n1", "n2"), stream = stream,
                    onGeneratedImage = { saved++; "unexpected asset" }) }
                assertIs<AiResult.UnknownError>(failed)
                assertTrue(posts.isEmpty())
                assertEquals(0, saved)
                val restarted = ResponsesSession(file, "account-a")
                assertNull(restarted.pendingTurnId)
                assertEquals("resp_before", restarted.state.previousResponseId)
                assertTrue(restarted.isCompleted("n1") && restarted.isCompleted("n2"))
                val duplicate = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Duplicate old batch"),
                    session = restarted, turnId = "batch-invalid", deliveryIds = listOf("n1", "n2"), stream = false) }
                assertIs<AiResult.Success>(duplicate)
                assertEquals(1, server.requests.size)
                val next = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Only new notification"),
                    instructions = INSTRUCTIONS, session = restarted, turnId = "batch-next", deliveryIds = listOf("n3"), stream = false) }
                server.verify()
                assertIs<AiResult.Success>(next)
                assertEquals(listOf("POST", "POST"), server.requests.map { it.method })
                assertTrue(posts.isEmpty())
            }
        }
    }

    @Test
    fun `failed incomplete and truncated responses discard old input and continue new batches from last good id`() = runBlocking {
        for (status in listOf("failed", "incomplete", "truncated")) for (stream in listOf(false, true))
        {
            if (status == "truncated" && !stream) continue
            withSessionFile { file ->
                val calls = mutableListOf<String>()
                var saved = 0
                MockServer { index, request ->
                    assertCommonRequest(request, "resp_before")
                    when (index)
                    {
                        0 -> {
                            assertEquals(listOf(userInput("Old notification")), request.input())
                            val failed = JsonObject(completed("resp_failed", listOf(call("call_discard", "echo", "discard"), image())) +
                                ("status" to JsonPrimitive(status)))
                            if (status == "truncated") MockResponse.sse(created("resp_failed"), delta("response.reasoning_summary_text.delta", "Partial"))
                            else if (stream) MockResponse.sse(created("resp_failed"), terminal(failed, "response.$status"))
                            else MockResponse(failed)
                        }
                        1 -> {
                            assertEquals(listOf(userInput("New notification")), request.input())
                            MockResponse(completed("resp_new", listOf(message("New batch complete"))))
                        }
                        else -> error("Unexpected request $index")
                    }
                }.use { server ->
                    val first = ResponsesSession(file, "account-a")
                    first.update(first.state.copy(previousResponseId = "resp_before"))
                    val failed = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Old notification"),
                        instructions = INSTRUCTIONS, tools = listOf(echoTool(calls)), session = first, turnId = "n1", stream = stream,
                        onGeneratedImage = { saved++; "unexpected image receipt" }) }
                    assertIs<AiResult.UnknownError>(failed, "$status stream=$stream")
                    val restarted = ResponsesSession(file, "account-a")
                    assertNull(restarted.pendingTurnId)
                    assertEquals("resp_before", restarted.state.previousResponseId)
                    assertTrue(restarted.isCompleted("n1"))
                    val duplicate = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "Never repeat"),
                        session = restarted, turnId = "n1", stream = false) }
                    assertIs<AiResult.Success>(duplicate)
                    assertEquals(1, server.requests.size)
                    val next = bounded { sendAiRequest(server.model(), ChatMessages(Role.USER, "New notification"),
                        instructions = INSTRUCTIONS, tools = listOf(echoTool(calls)), session = restarted, turnId = "n2", stream = false) }
                    server.verify()
                    assertIs<AiResult.Success>(next)
                    assertTrue(calls.isEmpty())
                    assertEquals(0, saved)
                    assertEquals(listOf("POST", "POST"), server.requests.map { it.method })
                    assertEquals("resp_new", restarted.state.previousResponseId)
                    assertTrue(restarted.isCompleted("n2"))
                }
            }
        }
    }

    @Test
    fun `real read_image failures reach the model without replaying the download`() = runBlocking {
        for (download in listOf(false, true)) withSessionFile { file ->
            var imageUrl = "data:image/png;base64,%%%invalid%%%"
            MockServer { index, request ->
                when
                {
                    index == 0 -> {
                        assertCommonRequest(request, null)
                        val readImage = JsonObject(call("call_image", "read_image", "") +
                            ("arguments" to JsonPrimitive(buildJsonObject { putJsonArray("urls") { add(imageUrl) } }.toString())))
                        MockResponse(completed("resp_read_image", listOf(readImage)))
                    }
                    download && index == 1 -> {
                        assertEquals("GET", request.method)
                        assertEquals("/missing-image.png", request.path)
                        MockResponse("missing image", status = 404, contentType = "text/plain")
                    }
                    index == (if (download) 2 else 1) -> {
                        assertCommonRequest(request, "resp_read_image")
                        val detail = toolError(request.input().single(), "call_image")
                        assertEquals("IllegalArgumentException", detail.getValue("type").jsonPrimitive.content)
                        if (download) assertTrue(detail.getValue("message").jsonPrimitive.content.contains("404"))
                        else assertTrue(detail.getValue("message").jsonPrimitive.content.isNotBlank())
                        MockResponse(completed("resp_done", listOf(message("Image could not be read"))))
                    }
                    else -> error("The client must not replay the image download after reporting its failure")
                }
            }.use { server ->
                val model = server.model().copy(visible = true)
                if (download) imageUrl = model.url.removeSuffix("/v1/responses") + "/missing-image.png"
                val tools = AiToolSet(ReadImage()).getTools(null, model)
                assertEquals(listOf("read_image"), tools.map { it.name })
                val session = ResponsesSession(file, "account-a")
                val result = bounded { sendAiRequest(model, ChatMessages(Role.USER, "Read this image"), instructions = INSTRUCTIONS,
                    tools = tools, session = session, turnId = "image-batch", deliveryIds = listOf("n1", "n2"), stream = false) }
                assertIs<AiResult.Success>(result)
                val restored = ResponsesSession(file, "account-a")
                assertNull(restored.pendingTurnId)
                assertEquals("resp_done", restored.state.previousResponseId)
                assertTrue(restored.isCompleted("n1") && restored.isCompleted("n2"))
                val duplicate = bounded { sendAiRequest(model, ChatMessages(Role.USER, "Duplicate image notification"),
                    tools = tools, session = restored, turnId = "image-batch", deliveryIds = listOf("n1", "n2"), stream = false) }
                server.verify()
                assertIs<AiResult.Success>(duplicate)
                assertTrue(duplicate.messages.isEmpty())
                assertEquals(if (download) listOf("POST", "GET", "POST") else listOf("POST", "POST"), server.requests.map { it.method })
            }
        }
    }

    @Test
    fun `session scope mismatch rejects state without replacing it`()
    {
        val directory = Files.createTempDirectory("windwhisper-session-test-").toFile()
        try
        {
            val file = File(directory, "session.json")
            val session = ResponsesSession(file, "account-a")
            session.begin("n1", listOf(userInput("Original")))
            val before = file.readText()
            assertFailsWith<IllegalArgumentException> { ResponsesSession(file, "account-b") }
            assertEquals(before, file.readText())
        }
        finally { directory.deleteRecursively() }
    }

    private fun echoTool(calls: MutableList<String>) = textTool("echo") { calls += it; "echo:$it" }
    private fun textTool(name: String, block: (String) -> String) = AiToolInfo<TextArgs>(name, "Test tool", name) { args, _ ->
        AiToolInfo.ToolResult(Content(block(args.text)))
    }

    private fun assertCommonRequest(request: RecordedRequest, previous: String?)
    {
        assertEquals("POST", request.method)
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer unit-test-key", request.authorization)
        assertEquals(JsonPrimitive(true), request.body["store"])
        assertEquals(JsonPrimitive(INSTRUCTIONS), request.body["instructions"])
        assertEquals(previous?.let(::JsonPrimitive), request.body["previous_response_id"])
        assertEquals(json("""{"type":"compaction","compact_threshold":12000}"""), request.body["context_management"]!!.jsonArray.single())
        assertFalse("messages" in request.body)
    }

    private suspend fun <T> bounded(block: suspend () -> T): T = withTimeout(15_000) { block() }
    private suspend fun withSessionFile(block: suspend (File) -> Unit)
    {
        val directory = Files.createTempDirectory("windwhisper-session-test-").toFile()
        try { block(File(directory, "session.json")) } finally { directory.deleteRecursively() }
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    private fun userInput(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
    private fun toolOutput(id: String, text: String) = buildJsonObject { put("type", "function_call_output"); put("call_id", id); put("output", text) }
    private fun toolError(output: JsonObject, id: String): JsonObject
    {
        assertEquals(JsonPrimitive("function_call_output"), output["type"])
        assertEquals(JsonPrimitive(id), output["call_id"])
        val result = json(output.getValue("output").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), result["ok"])
        return result.getValue("error").jsonObject
    }
    private fun reasoning(text: String = "") = buildJsonObject {
        put("type", "reasoning"); put("id", "rs_test")
        putJsonArray("summary") { if (text.isNotEmpty()) add(buildJsonObject { put("type", "summary_text"); put("text", text) }) }
    }
    private fun call(id: String, name: String, text: String) = buildJsonObject {
        put("type", "function_call"); put("id", "fc_$id"); put("call_id", id); put("name", name)
        put("arguments", buildJsonObject { put("text", text) }.toString()); put("status", "completed")
    }
    private fun message(text: String, source: String? = null) = buildJsonObject {
        put("type", "message"); put("id", "msg_test"); put("role", "assistant"); put("status", "completed")
        putJsonArray("content") { add(buildJsonObject {
            put("type", "output_text"); put("text", text)
            putJsonArray("annotations") { if (source != null) add(buildJsonObject {
                put("type", "url_citation"); put("url", source); put("title", "Source")
                put("start_index", text.length); put("end_index", text.length)
            }) }
        }) }
    }
    private fun image() = json("""{"type":"image_generation_call","id":"ig_1","status":"completed","output_format":"png","result":"aW1hZ2U="}""")
    private fun completed(id: String, output: List<JsonObject>, inputTokens: Int = 2, outputTokens: Int = 3) = buildJsonObject {
        put("id", id); put("status", "completed"); put("output", JsonArray(output))
        putJsonObject("usage") { put("input_tokens", inputTokens); put("output_tokens", outputTokens); put("total_tokens", inputTokens + outputTokens) }
    }
    private fun created(id: String) = buildJsonObject {
        put("type", "response.created"); putJsonObject("response") { put("id", id); put("status", "in_progress") }
    }
    private fun terminal(response: JsonObject, type: String = "response.completed") = buildJsonObject { put("type", type); put("response", response) }
    private fun delta(type: String, text: String, index: Int = 0) = buildJsonObject {
        put("type", type); put("delta", text); put("output_index", index); put("content_index", 0); put("summary_index", 0)
    }

    private data class RecordedRequest(val method: String, val path: String, val authorization: String?, val body: JsonObject)
    {
        fun input(): List<JsonObject> = body.getValue("input").jsonArray.map { it.jsonObject }
    }
    private data class MockResponse(val body: String, val status: Int = 200, val contentType: String = "application/json")
    {
        constructor(body: JsonObject, status: Int = 200): this(body.toString(), status)
        companion object
        {
            fun sse(vararg events: JsonObject) = MockResponse(events.joinToString("") { "event: ${it.getValue("type").jsonPrimitive.content}\ndata: $it\n\n" }, contentType = "text/event-stream")
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
                    val text = exchange.requestBody.use { it.readBytes().decodeToString() }
                    val request = RecordedRequest(exchange.requestMethod, exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Authorization"),
                        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject)
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
                finally { exchange.close() }
            }
            start()
        }
        fun model() = AiConfig.LlmModel(url = "http://127.0.0.1:${server.address.port}/v1/responses", model = "test-model",
            key = listOf("unit-test-key"), compactThreshold = 12000)
        fun verify() { failures.firstOrNull()?.let { throw AssertionError("Mock server assertion failed", it) } }
        override fun close() = server.stop(0)
    }
    private companion object { const val INSTRUCTIONS = "Follow the forum instructions every turn." }
}
