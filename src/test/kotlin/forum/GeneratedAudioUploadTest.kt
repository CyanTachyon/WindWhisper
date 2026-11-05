package moe.tachyon.windwhisper.forum

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import moe.tachyon.windwhisper.MainConfig
import moe.tachyon.windwhisper.ai.GeneratedAudioStore
import moe.tachyon.windwhisper.initializeTestEnvironment
import moe.tachyon.windwhisper.mainConfig
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class GeneratedAudioUploadTest
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
        previousConfig?.let { mainConfig = it }
        directories.forEach { it.deleteRecursively() }
    }

    private fun audio(): GeneratedAudioStore.Audio
    {
        val directory = Files.createTempDirectory("windwhisper-audio-upload-").toFile().also(directories::add)
        return GeneratedAudioStore(directory).apply { save("speech_fixture", byteArrayOf(0, 0, 1, 2, 3, 4)) }.read("speech_fixture")
    }

    @Test
    fun `upload sends authenticated WAV multipart and returns disclosed attachment markdown`() = runBlocking {
        val audio = audio()
        MockForum("""{"short_url":"upload://speech123.wav"}""").use { server ->
            configure(server)
            val markdown = LoginData("forum-session=fixture", "csrf-fixture").uploadGeneratedAudio(audio)
            assertEquals("[AI 生成语音](upload://speech123.wav)", markdown)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/uploads.json", request.path)
            assertEquals("forum-session=fixture", request.header("Cookie"))
            assertEquals("csrf-fixture", request.header("X-Csrf-Token"))
            val contentType = request.header("Content-Type")!!
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            val multipart = request.body.toString(Charsets.ISO_8859_1)
            val parts = multipart.split("--" + contentType.substringAfter("boundary=").trim('"'))
            fun part(name: String) = parts.single { Regex("""name="?$name"?(?:;|\r\n)""").containsMatchIn(it) }
            assertEquals("composer", part("type").substringAfter("\r\n\r\n").trimEnd('\r', '\n'))
            assertEquals("true", part("synchronous").substringAfter("\r\n\r\n").trimEnd('\r', '\n'))
            assertTrue(audio.filename in part("file"))
            assertTrue("Content-Type: audio/wav" in part("file"))
            assertTrue(audio.bytes.toString(Charsets.ISO_8859_1) in part("file"))
            server.verify()
        }
    }

    @Test
    fun `rate limit preserves status and complete body without resending upload`() = runBlocking {
        val body = """{"errors":["上传过快"],"error_type":"rate_limit","extras":{"wait_seconds":3}}"""
        MockForum(body, 429).use { server ->
            configure(server)
            val error = assertFailsWith<IllegalArgumentException> {
                LoginData("fixture", "csrf").uploadGeneratedAudio(audio())
            }
            assertTrue("429" in error.message.orEmpty())
            assertTrue(body in error.message.orEmpty())
            assertEquals(1, server.requests.size)
            server.verify()
        }
    }

    @Test
    fun `unsafe attachment urls are rejected and invalid local audio is never uploaded`() = runBlocking {
        val audio = audio()
        listOf("javascript:alert(1)", "upload://speech.png", "upload://speech.wav)evil", "https://example.com/speech.wav").forEach { url ->
            MockForum("""{"short_url":"$url"}""").use { server ->
                configure(server)
                assertFailsWith<IllegalArgumentException> { LoginData("fixture", "csrf").uploadGeneratedAudio(audio) }
                assertEquals(1, server.requests.size)
                server.verify()
            }
        }
        MockForum("{}").use { server ->
            configure(server)
            val user = LoginData("fixture", "csrf")
            assertFailsWith<IllegalArgumentException> { user.uploadGeneratedAudio(audio.copy(bytes = byteArrayOf(1, 2))) }
            assertFailsWith<IllegalArgumentException> { user.uploadGeneratedAudio(audio.copy(filename = "malicious\r\nheader.wav")) }
            assertFailsWith<IllegalArgumentException> { user.uploadGeneratedAudio(audio.copy(contentType = "text/html")) }
            assertTrue(server.requests.isEmpty())
            server.verify()
        }
    }

    private fun configure(server: MockForum)
    {
        mainConfig = MainConfig(server.url, "fixture", "fixture", 3, emptyMap(), emptyMap())
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, List<String>>, val body: ByteArray)
    {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }

    private class MockForum(body: String, status: Int = 200): AutoCloseable
    {
        val requests = CopyOnWriteArrayList<Request>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try
                {
                    requests += Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.toMap(), exchange.requestBody.readBytes())
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
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
