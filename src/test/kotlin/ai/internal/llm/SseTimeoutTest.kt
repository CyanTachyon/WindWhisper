package moe.tachyon.windwhisper.ai.internal.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class SseTimeoutTest
{
    @Serializable
    data class EchoArgs(val text: String)

    @BeforeTest
    fun setUp() = initializeTestEnvironment()

    @Test
    fun `response header timeout closes the actual TCP connection without advancing the notification`() = runBlocking {
        HangingHeaderServer().use { server ->
            val session = ResponsesSession()
            session.update(session.state.copy(previousResponseId = "resp_before"))
            session.begin("n1", ChatMessages(Role.USER, "Pending notification").toResponsesInput())
            val result = withTimeout(4_000) { request(server.model(), session, idleMillis = 600) }
            assertIs<ResponsesStreamIdleTimeoutException>(result.error)
            assertTrue(server.requestStarted.await(1, TimeUnit.SECONDS), "The HTTP request must reach the server")
            assertTrue(server.peerClosed.await(2, TimeUnit.SECONDS),
                "Idle timeout must close the client socket, including a detached SSE response-header handshake")
            assertTrue(result.toolCalls.isEmpty())
            assertTrue(result.generatedImages.isEmpty())
            assertEquals("resp_before", session.state.previousResponseId)
            assertEquals("n1", session.pendingTurnId)
            assertNull(session.state.turn?.responseId)
            assertFalse(session.isCompleted("n1"))
            server.verify()
        }
    }

    @Test
    fun `created response idle timeout retains its checkpoint and restarts with GET`() = runBlocking {
        val directory = Files.createTempDirectory("windwhisper-idle-session-").toFile()
        val release = CountDownLatch(1)
        var executions = 0
        val tool = AiToolInfo<EchoArgs>("echo", "Echo test input", "Echo") { args, _ ->
            executions++
            AiToolInfo.ToolResult(Content("echo:${args.text}"))
        }
        val pendingCall = json("""{"type":"function_call","id":"fc_resume","call_id":"call_resume","name":"echo","arguments":"{\"text\":\"once\"}","status":"completed"}""")
        try
        {
            ScriptedServer { request, exchange ->
                when (request.index)
                {
                    0 -> {
                        exchange.startEvents()
                        exchange.event(created("resp_idle"))
                        exchange.event(buildJsonObject {
                            put("type", "response.output_item.done"); put("output_index", 0); put("item", pendingCall)
                        })
                        release.await(5, TimeUnit.SECONDS)
                    }
                    1 -> {
                        assertEquals("GET", request.method)
                        assertEquals("/v1/responses/resp_idle", request.path)
                        exchange.respond(completed("resp_idle", listOf(pendingCall)))
                    }
                    2 -> {
                        assertEquals("POST", request.method)
                        assertEquals(JsonPrimitive("resp_idle"), request.body["previous_response_id"])
                        val input = request.body.getValue("input").jsonArray.single().jsonObject
                        assertEquals(JsonPrimitive("function_call_output"), input["type"])
                        assertEquals(JsonPrimitive("call_resume"), input["call_id"])
                        assertEquals(JsonPrimitive("echo:once"), input["output"])
                        exchange.respond(completed("resp_recovered", listOf(message("Recovered"))))
                    }
                    else -> error("Unexpected request ${request.index}")
                }
            }.use { server ->
                val file = File(directory, "session.json")
                val session = ResponsesSession(file, "test-account")
                session.update(session.state.copy(previousResponseId = "resp_before"))
                session.begin("n1", ChatMessages(Role.USER, "Original notification").toResponsesInput())
                val result = try
                {
                    withTimeout(4_000) { request(server.model(), session, idleMillis = 600, tools = listOf(tool)) }
                }
                finally { release.countDown() }
                assertIs<ResponsesStreamIdleTimeoutException>(result.error)
                assertTrue(result.toolCalls.isEmpty())
                assertEquals(0, executions)
                val restarted = ResponsesSession(file, "test-account")
                assertEquals("resp_idle", restarted.state.turn?.responseId)
                assertEquals("resp_before", restarted.state.previousResponseId)
                assertFalse(restarted.isCompleted("n1"))

                val recovered = withTimeout(4_000) {
                    sendAiRequest(server.model(), ChatMessages.empty(), instructions = "Instructions", tools = listOf(tool),
                        session = restarted, turnId = "n1", stream = false)
                }
                server.verify()
                assertIs<AiResult.Success>(recovered)
                assertEquals(1, executions)
                assertEquals(listOf("POST", "GET", "POST"), server.requests.map { it.method })
                assertEquals("resp_recovered", restarted.state.previousResponseId)
                assertTrue(restarted.isCompleted("n1"))
            }
        }
        finally { release.countDown(); directory.deleteRecursively() }
    }

    @Test
    fun `active event stream resets idle deadline and may outlive a single timeout window`() = runBlocking {
        ScriptedServer { _, exchange ->
            exchange.startEvents()
            exchange.event(created("resp_active"))
            for (part in 1..6)
            {
                Thread.sleep(200)
                exchange.event(buildJsonObject {
                    put("type", "response.output_text.delta"); put("output_index", 0); put("content_index", 0); put("delta", "$part")
                })
            }
            exchange.event(buildJsonObject { put("type", "response.completed"); put("response", completed("resp_active", listOf(message("123456")))) })
        }.use { server ->
            val session = ResponsesSession()
            session.begin("n1", ChatMessages(Role.USER, "Active stream").toResponsesInput())
            val slices = mutableListOf<StreamAiResponseSlice>()
            val start = System.nanoTime()
            val result = withTimeout(5_000) { request(server.model(), session, idleMillis = 600, onReceive = slices::add) }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            server.verify()
            assertNull(result.error)
            assertTrue(elapsedMillis > 600, "The fixture must last longer than the idle timeout")
            assertEquals("resp_active", result.responseId)
            assertEquals("123456", result.message.content.toText())
            assertEquals("123456", slices.filterIsInstance<StreamAiResponseSlice.Message>().joinToString("") { it.content })
        }
    }

    @Test
    fun `outer coroutine cancellation is not reclassified as an idle failure`(): Unit = runBlocking {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) {
                withResponsesStreamIdleTimeout(2_000) { awaitCancellation() }
            }
        }
    }

    private suspend fun request(
        model: AiConfig.LlmModel,
        session: ResponsesSession,
        idleMillis: Long,
        tools: List<AiToolInfo<*>> = emptyList(),
        onReceive: suspend (StreamAiResponseSlice) -> Unit = {},
    ): RequestResult = sendResponsesRequest(
        url = model.url,
        key = model.key.first(),
        body = buildResponsesRequest(model, session.state.turn!!.input, "Instructions", session.state.previousResponseId, tools, true),
        stream = true,
        onReceive = onReceive,
        timeoutMillis = idleMillis,
        onResponseCreated = { id -> session.update(session.state.copy(turn = session.state.turn!!.copy(responseId = id))) },
    )

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun created(id: String) = buildJsonObject { put("type", "response.created"); putJsonObject("response") { put("id", id); put("status", "in_progress") } }
    private fun completed(id: String, output: List<JsonObject>) = buildJsonObject { put("id", id); put("status", "completed"); put("output", JsonArray(output)) }
    private fun message(text: String) = buildJsonObject {
        put("type", "message"); put("id", "msg_test"); put("role", "assistant"); put("status", "completed")
        putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", text); putJsonArray("annotations") {} }) }
    }

    private fun HttpExchange.startEvents()
    {
        responseHeaders.set("Content-Type", "text/event-stream")
        sendResponseHeaders(200, 0)
    }
    private fun HttpExchange.event(event: JsonObject)
    {
        responseBody.write("data: $event\n\n".encodeToByteArray())
        responseBody.flush()
    }
    private fun HttpExchange.respond(body: JsonObject)
    {
        val bytes = body.toString().encodeToByteArray()
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private data class Request(val index: Int, val method: String, val path: String, val body: JsonObject)

    /** A raw socket lets the test observe client FIN/reset without sending any response headers. */
    private class HangingHeaderServer: AutoCloseable
    {
        val requestStarted = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val connection = AtomicReference<Socket?>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val executor = Executors.newSingleThreadExecutor()

        init
        {
            executor.submit {
                try
                {
                    listener.accept().use { socket ->
                        connection.set(socket)
                        socket.soTimeout = 3_000
                        val buffer = ByteArray(4096)
                        try
                        {
                            while (socket.getInputStream().read(buffer) != -1) requestStarted.countDown()
                            peerClosed.countDown()
                        }
                        catch (_: SocketException) { peerClosed.countDown() }
                        catch (_: SocketTimeoutException) { /* Missing peer close is asserted by the test. */ }
                    }
                }
                catch (failure: Throwable)
                {
                    if (!listener.isClosed) failures += failure
                }
            }
        }

        fun model() = AiConfig.LlmModel(url = "http://127.0.0.1:${listener.localPort}/v1/responses", model = "test-model", key = listOf("unit-test-key"))
        fun verify() { failures.firstOrNull()?.let { throw AssertionError("Raw socket fixture failed", it) } }
        override fun close() { listener.close(); connection.get()?.close(); executor.shutdownNow() }
    }

    private class ScriptedServer(private val respond: (Request, HttpExchange) -> Unit): AutoCloseable
    {
        val requests = CopyOnWriteArrayList<Request>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@ScriptedServer.executor
            createContext("/") { exchange ->
                try
                {
                    val text = exchange.requestBody.use { it.readBytes().decodeToString() }
                    val request = Request(requests.size, exchange.requestMethod, exchange.requestURI.path,
                        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject)
                    requests += request
                    respond(request, exchange)
                }
                catch (_: IOException) { /* The timed-out client may close its socket before the fixture does. */ }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                catch (failure: Throwable) { failures += failure; runCatching { exchange.sendResponseHeaders(500, -1) } }
                finally { exchange.close() }
            }
            start()
        }
        fun model() = AiConfig.LlmModel(url = "http://127.0.0.1:${server.address.port}/v1/responses", model = "test-model", key = listOf("unit-test-key"))
        fun verify() { failures.firstOrNull()?.let { throw AssertionError("Mock server assertion failed", it) } }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}
