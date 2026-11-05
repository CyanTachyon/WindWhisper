package moe.tachyon.windwhisper.forum

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.MainConfig
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.ai.UncertainToolExecutionException
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.tools.AiToolSet
import moe.tachyon.windwhisper.ai.tools.Forum
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import moe.tachyon.windwhisper.mainConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
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
            store.save("ig_$format", base64(bytes), format)
            store.save("ig_$format", base64(bytes), format)
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
            assertTrue("失败" in invoke(tools, "upload_generated_image", uploadArgs("ig_example", 7)))
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
            val result = invoke(forumTools(store), "upload_generated_image", uploadArgs("ig_example", 7))
            assertTrue("失败" in result)
            assertFalse("javascript" in result)
            assertTrue(server.requests.none { it.path == "/posts.json" })
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

    private data class Reply(val body: String = "", val status: Int = 200, val headers: Map<String, String> = emptyMap(), val disconnect: Boolean = false)

    private class MockForum(handler: (Request) -> Reply): AutoCloseable
    {
        val requests = CopyOnWriteArrayList<Request>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try
                {
                    val request = Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.toMap(), exchange.requestBody.readBytes())
                    requests += request
                    val response = handler(request)
                    if (!response.disconnect)
                    {
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                        val bytes = response.body.toByteArray(Charsets.UTF_8)
                        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }
                catch (error: Throwable) { failures += error }
                finally { exchange.close() }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}"
        fun verify() { failures.firstOrNull()?.let { throw AssertionError("Mock forum failed", it) } }
        override fun close() { server.stop(0) }
    }
}
