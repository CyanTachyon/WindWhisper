package moe.tachyon.windwhisper.forum

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.MainConfig
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.UncertainToolExecutionException
import moe.tachyon.windwhisper.ai.internal.llm.parseToolCalls
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.tools.AiToolSet
import moe.tachyon.windwhisper.ai.tools.Forum
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import moe.tachyon.windwhisper.mainConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

class GeneratedImageUploadTest
{
    private val directories = mutableListOf<File>()
    private var previousConfig: MainConfig? = null

    @BeforeTest
    fun setUp()
    {
        initializeTestEnvironment()
        previousConfig = runCatching { mainConfig }.getOrNull()
    }

    @AfterTest
    fun tearDown()
    {
        directories.forEach { it.deleteRecursively() }
        previousConfig?.let { mainConfig = it }
    }

    private fun directory(): File = Files.createTempDirectory("windwhisper-generated-images-").toFile().also(directories::add)
    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun imageBytes(format: String = "png", color: Int = 0x22aa66): ByteArray
    {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        return ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, format, output))
            output.toByteArray()
        }
    }

    @Test
    fun `store persists original formats by stable ids without exposing paths`()
    {
        val directory = directory()
        val store = GeneratedImageStore(directory)
        val png = imageBytes()
        val jpeg = imageBytes("jpeg")
        val webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
        listOf("png" to png, "jpeg" to jpeg, "webp" to webp).forEach { (format, bytes) ->
            assertFalse(store.contains("ig_$format"))
            store.save("ig_$format", base64(bytes), format)
            store.save("ig_$format", base64(bytes), format)
            assertTrue(GeneratedImageStore(directory).contains("ig_$format"))
            val restored = GeneratedImageStore(directory).read("ig_$format")
            assertContentEquals(bytes, restored.bytes)
            assertEquals("image/$format", restored.contentType)
            assertTrue(restored.filename.matches(Regex("generated-[0-9a-f]{64}\\.$format")))
        }
        assertEquals(3, directory.listFiles()!!.size)
        assertFailsWith<IllegalArgumentException> { store.save("ig_png", base64(imageBytes(color = 0x112233)), "png") }
        assertContentEquals(png, store.read("ig_png").bytes)
    }

    @Test
    fun `store rejects unsafe ids oversized data invalid base64 and wrong image content`()
    {
        val store = GeneratedImageStore(directory())
        val png = base64(imageBytes())
        listOf("../outside", "/tmp/file", "", "uuid:outside").forEach { id ->
            assertFailsWith<IllegalArgumentException> { store.save(id, png, "png") }
            assertFailsWith<IllegalArgumentException> { store.read(id) }
            assertFailsWith<IllegalArgumentException> { store.contains(id) }
        }
        assertFailsWith<IllegalArgumentException> { store.save("ig_bad", "not base64", "png") }
        assertFailsWith<IllegalArgumentException> { store.save("ig_bad", png, "jpeg") }
        assertFailsWith<IllegalArgumentException> { store.save("ig_bad", png, "svg") }
        assertFailsWith<IllegalArgumentException> { store.save("ig_bad", base64(imageBytes().take(8).toByteArray()), "png") }
        assertFailsWith<IllegalArgumentException> { store.save("ig_bad", "A".repeat(((20 * 1024 * 1024 + 2) / 3) * 4 + 4), "png") }
    }

    @Test
    fun `upload sends authenticated multipart and returned markdown can be posted`() = runBlocking {
        val store = GeneratedImageStore(directory())
        val image = imageBytes()
        store.save("ig_example", base64(image), "png")
        MockForum { request ->
            when (request.path)
            {
                "/t/7.json" -> Reply(topic())
                "/uploads.json" -> Reply("""{"short_url":"upload://generated123.png"}""")
                "/posts.json" -> Reply("""{"id":123}""")
                else -> Reply("{}", 404)
            }
        }.use { server ->
            configure(server)
            val tools = forumTools(store)
            val result = invoke(tools, "upload_generated_image", buildJsonObject { put("imageId", "ig_example"); put("topicId", 7) })
            val markdown = "![生成图片](upload://generated123.png)"
            assertTrue(markdown in result)
            assertEquals(listOf("/t/7.json", "/uploads.json"), server.requests.map { it.path })
            val upload = server.requests.last()
            assertEquals("POST", upload.method)
            assertEquals("forum-session=fixture", upload.header("Cookie"))
            assertEquals("csrf-fixture", upload.header("X-Csrf-Token"))
            assertEquals("fixture", upload.header("X-Test-Header"))
            assertTrue(upload.header("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            val multipart = upload.body.toString(Charsets.ISO_8859_1)
            val boundary = upload.header("Content-Type")!!.substringAfter("boundary=").trim('"')
            val parts = multipart.split("--$boundary")
            fun part(name: String): String = parts.single {
                Regex("""name="?$name"?(?:;|\r\n)""").containsMatchIn(it)
            }
            assertEquals("composer", part("type").substringAfter("\r\n\r\n").trimEnd('\r', '\n'))
            assertEquals("true", part("synchronous").substringAfter("\r\n\r\n").trimEnd('\r', '\n'))
            assertTrue(part("file").isNotEmpty())
            assertTrue(store.read("ig_example").filename in multipart)
            assertTrue("Content-Type: image/png" in multipart, multipart.take(400))
            assertTrue(image.toString(Charsets.ISO_8859_1) in multipart)

            val posted = invoke(tools, "send_post", buildJsonObject { put("topicId", 7); put("content", "Here is the image:\n$markdown"); put("replyTo", 2) })
            assertTrue("成功" in posted)
            val body = Json.parseToJsonElement(server.requests.last().body.toString(Charsets.UTF_8)).jsonObject
            assertEquals("Here is the image:\n$markdown", body["raw"]?.jsonPrimitive?.content)
            assertEquals(2, body["reply_to_post_number"]?.jsonPrimitive?.int)
            server.verify()
        }
    }

    @Test
    fun `upload rejects blocked missing topics unknown ids and corrupted stored images`() = runBlocking {
        val directory = directory()
        val store = GeneratedImageStore(directory)
        store.save("ig_example", base64(imageBytes()), "png")
        MockForum { request -> if (request.path == "/t/7.json") Reply(topic()) else Reply("{}", 404) }.use { server ->
            configure(server)
            val blocked = forumTools(store, setOf(7))
            assertTrue("禁止" in invoke(blocked, "upload_generated_image", uploadArgs("ig_example", 7)))
            assertTrue(server.requests.isEmpty())
            val tools = forumTools(store)
            assertTrue("不存在" in invoke(tools, "upload_generated_image", uploadArgs("ig_example", 8)))
            assertTrue("无效" in invoke(tools, "upload_generated_image", uploadArgs("../outside", 7)))
            assertTrue("未找到" in invoke(tools, "upload_generated_image", uploadArgs("ig_unknown", 7)))
            directory.listFiles()!!.single().writeText("not an image")
            assertFailsWith<IllegalArgumentException> { invoke(tools, "upload_generated_image", uploadArgs("ig_example", 7)) }
            assertTrue(server.requests.none { it.path == "/uploads.json" || it.path == "/posts.json" })
            server.verify()
        }
    }

    @Test
    fun `upload rejects unsafe server attachment urls and is absent without image store`() = runBlocking {
        val store = GeneratedImageStore(directory())
        store.save("ig_example", base64(imageBytes()), "png")
        MockForum { request -> if (request.path == "/t/7.json") Reply(topic()) else Reply("""{"short_url":"javascript:alert(1)"}""") }.use { server ->
            configure(server)
            assertTrue(forumTools(null).none { it.name == "upload_generated_image" })
            val error = assertFailsWith<IllegalArgumentException> {
                invoke(forumTools(store), "upload_generated_image", uploadArgs("ig_example", 7))
            }
            assertTrue("short_url" in error.message.orEmpty())
            assertFalse("javascript" in error.message.orEmpty())
            assertTrue(server.requests.none { it.path == "/posts.json" })
            server.verify()
        }
    }

    @Test
    fun `forum implementations throw HTTP failures to the tool boundary without retrying writes`() = runBlocking {
        val store = GeneratedImageStore(directory())
        store.save("ig_example", base64(imageBytes()), "png")
        val cases = listOf(
            Triple("get_topic", "/t/7.json", Json.parseToJsonElement("""{"topicIds":[7]}""").jsonObject),
            Triple("get_posts", "/t/7/1.json", Json.parseToJsonElement("""{"posts":[{"topicId":7,"postNumber":1}]}""").jsonObject),
            Triple("send_post", "/posts.json", Json.parseToJsonElement("""{"topicId":7,"content":"One post","replyTo":0}""").jsonObject),
            Triple("upload_generated_image", "/uploads.json", uploadArgs("ig_example", 7)),
        )
        cases.forEach { (toolName, path, arguments) ->
            MockForum { request -> if (request.path == path) Reply("{}", 500) else Reply(topic()) }.use { server ->
                configure(server)
                val error = assertNotNull(runCatching {
                    withTimeout(5_000) { invoke(forumTools(store), toolName, arguments) }
                }.exceptionOrNull(), "$toolName must propagate HTTP failures")
                assertFalse(error is CancellationException, "$toolName must fail before the test timeout")
                assertTrue("500" in error.message.orEmpty())
                assertEquals(1, server.requests.count { it.path == path }, "$toolName must not retry the failed request")
                server.verify()
            }
        }
    }

    @Test
    fun `forum rate limit responses reach the model as tool errors with the complete response body`() = runBlocking {
        val store = GeneratedImageStore(directory())
        store.save("ig_example", base64(imageBytes()), "png")
        val body = """{"errors":["您回复的频率太快了。请等待几秒后再试。"],"error_type":"rate_limit","extras":{"wait_seconds":1}}"""
        val cases = listOf(
            Triple("get_posts", "/t/7/1.json", Json.parseToJsonElement("""{"posts":[{"topicId":7,"postNumber":1}]}""").jsonObject),
            Triple("send_post", "/posts.json", Json.parseToJsonElement("""{"topicId":7,"content":"One post","replyTo":0}""").jsonObject),
            Triple("upload_generated_image", "/uploads.json", uploadArgs("ig_example", 7)),
        )
        cases.forEach { (toolName, path, arguments) ->
            MockForum { request -> if (request.path == path) Reply(body, 429) else Reply(topic()) }.use { server ->
                configure(server)
                val callId = "call_$toolName"
                val result = withTimeout(5_000) {
                    parseToolCalls(mapOf(callId to (toolName to arguments.toString())), forumTools(store)) {}
                }.single()
                assertEquals(Role.TOOL(callId, toolName), result.role)
                val output = Json.parseToJsonElement(result.content.toText()).jsonObject
                assertEquals(false, output["ok"]!!.jsonPrimitive.boolean)
                val error = output["error"]!!.jsonObject
                val message = error["message"]!!.jsonPrimitive.content
                assertTrue("429" in message, "$toolName must retain the HTTP status")
                assertTrue(body in message, "$toolName must retain the entire forum error response")
                assertFalse("outcome" in error, "A received rate limit response is not an uncertain write")
                assertEquals(1, server.requests.count { it.path == path }, "$toolName must not retry the failed request")
                assertTrue(server.requests.groupingBy { it.method to it.path }.eachCount().values.all { it == 1 })
                server.verify()
            }
        }
    }

    @Test
    fun `forum read and upload malformed responses propagate instead of becoming tool text`() = runBlocking {
        val store = GeneratedImageStore(directory())
        store.save("ig_example", base64(imageBytes()), "png")
        val cases = listOf(
            Triple("get_topic", "/t/7.json", Json.parseToJsonElement("""{"topicIds":[7]}""").jsonObject),
            Triple("get_posts", "/t/7/1.json", Json.parseToJsonElement("""{"posts":[{"topicId":7,"postNumber":1}]}""").jsonObject),
            Triple("upload_generated_image", "/uploads.json", uploadArgs("ig_example", 7)),
        )
        cases.forEach { (toolName, path, arguments) ->
            MockForum { request -> if (request.path == path) Reply("not-json") else Reply(topic()) }.use { server ->
                configure(server)
                val error = assertNotNull(runCatching {
                    withTimeout(5_000) { invoke(forumTools(store), toolName, arguments) }
                }.exceptionOrNull(), "$toolName must propagate response parsing failures")
                assertFalse(error is CancellationException)
                assertEquals(1, server.requests.count { it.path == path })
                server.verify()
            }
        }
    }

    @Test
    fun `missing posts remain empty but HTTP and incomplete success responses fail in both readers`() = runBlocking {
        var reply = Reply("{}", 404)
        MockForum { reply }.use { server ->
            configure(server)
            val user = LoginData("forum-session=fixture", "csrf-fixture")
            assertEquals(emptyList(), user.getPosts(7, listOf(21)))
            assertEquals(emptyList(), user.getPosts(7, 1))
            assertTrue("不存在" in invoke(forumTools(null), "get_topic", Json.parseToJsonElement("""{"topicIds":[7]}""").jsonObject))
            listOf(Reply("{}", 500), Reply("{}"), Reply("""{"post_stream":{}}""")).forEach { response ->
                reply = response
                assertFailsWith<IllegalStateException> { user.getPosts(7, listOf(21)) }
                assertFailsWith<IllegalStateException> { user.getPosts(7, 1) }
            }
            reply = Reply("""{"post_stream":{"posts":[]}}""")
            assertEquals(emptyList(), user.getPosts(7, listOf(21)))
            assertEquals(emptyList(), user.getPosts(7, 1))
            server.verify()
        }
    }

    @Test
    fun `optional topic category is accepted but category request failures propagate`() = runBlocking {
        var categoryId: JsonElement? = null
        MockForum { request ->
            if (request.path == "/t/7.json")
            {
                val fields = Json.parseToJsonElement(topic()).jsonObject.toMutableMap()
                categoryId?.let { fields["category_id"] = it }
                Reply(JsonObject(fields).toString())
            }
            else Reply("{}", 500)
        }.use { server ->
            configure(server)
            val tools = forumTools(null)
            val arguments = Json.parseToJsonElement("""{"topicIds":[7]}""").jsonObject
            assertTrue("Test topic" in invoke(tools, "get_topic", arguments))
            categoryId = JsonNull
            assertTrue("Test topic" in invoke(tools, "get_topic", arguments))
            assertTrue(server.requests.none { it.path.startsWith("/c/") })
            categoryId = JsonPrimitive(2)
            val error = assertFailsWith<IllegalStateException> { invoke(tools, "get_topic", arguments) }
            assertTrue("500" in error.message.orEmpty())
            assertEquals(1, server.requests.count { it.path == "/c/2/show.json" })
            server.verify()
        }
    }

    @Test
    fun `forum writes can wait beyond the old ten second deadline without being replayed`() = runBlocking {
        MockForum { Reply("{\"accepted\":true}", delayMillis = 10_300) }.use { server ->
            configure(server)
            val user = LoginData("forum-session=fixture", "csrf-fixture")
            val response = withTimeout(20_000) {
                user.post("${server.url}/write") { setBody("one write") }.bodyAsText()
            }
            assertEquals("{\"accepted\":true}", response)
            assertEquals(1, server.requests.size)
            assertEquals("POST", server.requests.single().method)
            assertEquals("one write", server.requests.single().body.toString(Charsets.UTF_8))
            server.verify()
        }
    }

    @Test
    fun `configured write deadline still expires without replaying an accepted request`() = runBlocking {
        MockForum { Reply("{}", delayMillis = 1_500, allowClientDisconnect = true) }.use { server ->
            createForumWriteClient(500).use { client ->
                val error = assertFailsWith<IOException> {
                    withTimeout(5_000) {
                        client.post("${server.url}/write") { setBody("one write") }.bodyAsText()
                    }
                }
                assertTrue("timeout" in error.message.orEmpty(), error.toString())
            }
            server.awaitFirstResponse()
            assertEquals(1, server.requests.size)
            assertEquals("POST", server.requests.single().method)
            assertEquals("one write", server.requests.single().body.toString(Charsets.UTF_8))
            server.verify()
        }
    }

    @Test
    fun `uncertain POST and PUT failures are never retried`() = runBlocking {
        MockForum { Reply(disconnect = true) }.use { server ->
            configure(server)
            val user = LoginData("forum-session=fixture", "csrf-fixture")
            assertTrue(runCatching { withTimeout(5_000) { user.post("${server.url}/write") { setBody("payload") } } }.isFailure)
            assertEquals(1, server.requests.size)
            assertTrue(runCatching { withTimeout(5_000) { user.put("${server.url}/write") { setBody("payload") } } }.isFailure)
            assertEquals(2, server.requests.size)
            server.verify()
        }
    }

    @Test
    fun `forum tools propagate uncertain writes instead of returning retryable tool errors`() = runBlocking {
        val store = GeneratedImageStore(directory())
        store.save("ig_example", base64(imageBytes()), "png")
        MockForum { request ->
            if (request.path == "/t/7.json") Reply(topic()) else Reply(disconnect = true)
        }.use { server ->
            configure(server)
            val tools = forumTools(store)
            val cases = listOf(
                Triple("send_post", "/posts.json", buildJsonObject { put("topicId", 7); put("content", "A single post"); put("replyTo", 0) }),
                Triple("upload_generated_image", "/uploads.json", uploadArgs("ig_example", 7)),
            )
            cases.forEach { (toolName, path, arguments) ->
                val error = runCatching {
                    withTimeout(5_000) { invoke(tools, toolName, arguments) }
                }.exceptionOrNull()
                assertIs<UncertainToolExecutionException>(error, "$toolName must not swallow an uncertain write")
                val writes = server.requests.filter { it.path == path }
                assertEquals(1, writes.size, "$toolName must not resend a write after losing its response")
                assertEquals("POST", writes.single().method)
                assertTrue(writes.single().body.isNotEmpty())
            }
            assertEquals(3, server.requests.size)
            server.verify()
        }
    }

    @Test
    fun `GET still recovers from a lost connection`() = runBlocking {
        var requests = 0
        MockForum { if (requests++ == 0) Reply(disconnect = true) else Reply("{}") }.use { server ->
            configure(server)
            assertEquals("{}", withTimeout(5_000) { LoginData("forum-session=fixture", "csrf-fixture").get("${server.url}/read").bodyAsText() })
            assertTrue(server.requests.size >= 2)
            server.verify()
        }
    }

    @Test
    fun `explicit BAD CSRF can refresh credentials and safely retry rejected write`() = runBlocking {
        var writes = 0
        MockForum { request ->
            when (request.path)
            {
                "/write" -> if (writes++ == 0) Reply("BAD CSRF", 403) else Reply("{}")
                "/session/passkey/challenge.json" -> Reply("{}", headers = mapOf("Set-Cookie" to "forum-session=renewed"))
                "/session/csrf" -> Reply("""{"csrf":"renewed-csrf"}""", headers = mapOf("Set-Cookie" to "forum-session=renewed"))
                "/session" -> Reply("{}")
                else -> Reply("{}", 404)
            }
        }.use { server ->
            configure(server)
            val user = LoginData("forum-session=fixture", "csrf-fixture")
            assertEquals("{}", withTimeout(5_000) { user.post("${server.url}/write") { setBody("payload") }.bodyAsText() })
            val writes = server.requests.filter { it.path == "/write" }
            assertEquals(2, writes.size)
            assertEquals("renewed-csrf", writes.last().header("X-Csrf-Token"))
            assertEquals("forum-session=renewed", writes.last().header("Cookie"))
            server.verify()
        }
    }

    private fun configure(server: MockForum)
    {
        mainConfig = MainConfig(server.url, "fixture-user", "fixture-password", 3, mapOf("X-Test-Header" to "fixture"), emptyMap())
    }

    private fun topic(): String = """{"id":7,"title":"Test topic","highest_post_number":1,"archetype":"regular"}"""
    private fun uploadArgs(id: String, topic: Int): JsonObject = buildJsonObject { put("imageId", id); put("topicId", topic) }

    private suspend fun forumTools(store: GeneratedImageStore?, blocked: Set<Int> = emptySet()): List<AiToolInfo<*>>
    {
        val tools = AiToolSet<Any?>()
        tools.addProvider(Forum(LoginData("forum-session=fixture", "csrf-fixture"), blocked, store))
        return tools.getTools(null, AiConfig.LlmModel())
    }

    private suspend fun invoke(tools: List<AiToolInfo<*>>, name: String, args: JsonObject): String =
        tools.single { it.name == name }(args.toString()) {}.content.toText()

    private data class Request(val method: String, val path: String, val headers: Map<String, List<String>>, val body: ByteArray)
    {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }

    private data class Reply(
        val body: String = "",
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val disconnect: Boolean = false,
        val delayMillis: Long = 0,
        val allowClientDisconnect: Boolean = false,
    )

    private class MockForum(handler: (Request) -> Reply): AutoCloseable
    {
        val requests = CopyOnWriteArrayList<Request>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val firstResponse = CountDownLatch(1)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                var allowClientDisconnect = false
                try
                {
                    val request = Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.toMap(), exchange.requestBody.readBytes())
                    requests += request
                    val response = handler(request)
                    allowClientDisconnect = response.allowClientDisconnect
                    if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
                    if (!response.disconnect)
                    {
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                        val bytes = response.body.toByteArray(Charsets.UTF_8)
                        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }
                catch (error: Throwable)
                {
                    if (!(allowClientDisconnect && error is IOException)) failures += error
                }
                finally
                {
                    exchange.close()
                    firstResponse.countDown()
                }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}"
        fun awaitFirstResponse() { assertTrue(firstResponse.await(5, TimeUnit.SECONDS), "Mock forum did not finish its response") }
        fun verify() { failures.firstOrNull()?.let { throw AssertionError("Mock forum failed", it) } }
        override fun close() { server.stop(0) }
    }
}
