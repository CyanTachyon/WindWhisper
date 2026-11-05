package moe.tachyon.windwhisper.ai.speech

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.config.SpeechConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class LiveSpeechTest
{
    @BeforeTest
    fun setUp() = initializeTestEnvironment()

    @Test
    fun `real websocket waits for transcript quiet tail and session finalization`() = runBlocking {
        SpeechSocketServer { socket ->
            val start = socket.readJson().getValue("session").jsonObject
            assertEquals("voice_test", start.getValue("audio").jsonObject.getValue("output").jsonObject
                .getValue("voice").jsonObject.getValue("id").jsonPrimitive.content)
            socket.writeJson(buildJsonObject { put("type", "session.started") })
            assertEquals("session.commentary.append", socket.readJson().getValue("type").jsonPrimitive.content)
            socket.writeJson(audio(samples(1_000, 1000)))
            socket.writeJson(transcript("你好。"))
            Thread.sleep(1_650)
            socket.writeJson(audio(samples(36_000, 0)))
            var silenceChunks = 0
            while (true)
            {
                val event = socket.readJson()
                if (event.getValue("type").jsonPrimitive.content == "session.close") break
                assertEquals("session.input_audio.append", event.getValue("type").jsonPrimitive.content)
                assertEquals(4_800, Base64.getDecoder().decode(event.getValue("audio").jsonPrimitive.content).size)
                silenceChunks++
            }
            assertTrue(silenceChunks in 1..30, "Silence must be paced, not sent in an unbounded loop")
            socket.writeJson(buildJsonObject { put("type", "session.closed") })
        }.use { server ->
            val result = withTimeout(8_000) {
                generateLiveSpeech(server.config(), "test-key", "你好", 1.0, "平静")
            }
            assertEquals("你好。", result.transcript)
            assertEquals(74_000, result.pcm.size)
            server.verify()
            assertEquals(1, server.connections.get())
        }
    }

    @Test
    fun `websocket provider error is returned without another request`() = runBlocking {
        SpeechSocketServer { socket ->
            socket.readJson()
            socket.writeJson(buildJsonObject {
                put("type", "error")
                putJsonObject("error") { put("code", "voice_not_found"); put("message", "Voice is unavailable") }
            })
        }.use { server ->
            val error = assertFailsWith<IllegalStateException> {
                generateLiveSpeech(server.config(), "test-key", "你好", 1.0, "平静")
            }
            assertContains(error.message!!, "voice_not_found")
            server.verify()
            assertEquals(1, server.connections.get())
        }
    }

    @Test
    fun `cancelling a waiting websocket propagates cancellation and closes transport`() = runBlocking {
        val waiting = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        SpeechSocketServer { socket ->
            socket.readJson()
            waiting.countDown()
            try
            {
                while (socket.getInputStream().read() != -1) { }
                disconnected.countDown()
            }
            catch (_: java.net.SocketException) { disconnected.countDown() }
        }.use { server ->
            val operation = async(Dispatchers.IO) {
                generateLiveSpeech(server.config(), "test-key", "你好", 1.0, "平静")
            }
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            operation.cancel()
            assertFailsWith<CancellationException> { operation.await() }
            assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Cancellation must close the live socket")
            server.verify()
            assertEquals(1, server.connections.get())
        }
    }

    @Test
    fun `request fixes voice while carrying literal text pace and expression`()
    {
        val config = SpeechConfig(voiceId = "voice_test", voicePrompt = "Soft adult feminine voice")
        val session = buildLiveSpeechStart(config, "你好\n\"风语\"", 0.8, "开心").getValue("session").jsonObject
        assertEquals("voice_test", session.getValue("audio").jsonObject.getValue("output").jsonObject
            .getValue("voice").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(24_000, session.getValue("audio").jsonObject.getValue("format").jsonObject
            .getValue("rate").jsonPrimitive.int)
        assertEquals(false, session.getValue("store").jsonPrimitive.boolean)
        assertEquals("client", session.getValue("delegation").jsonObject.getValue("type").jsonPrimitive.content)
        val instructions = session.getValue("instructions").jsonPrimitive.content
        assertContains(instructions, "Soft adult feminine voice")
        assertContains(instructions, "0.8")
        assertContains(instructions, "开心")
        val literal = session.getValue("input").jsonArray.single().jsonObject.getValue("content")
            .jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertContains(literal, JsonPrimitive("你好\n\"风语\"").toString())
        assertFalse(session.getValue("audio").jsonObject.getValue("output").jsonObject.containsKey("speed"),
            "Live does not expose a numeric speed field")
    }

    @Test
    fun `matched transcript needs audible PCM and an actual quiet tail`()
    {
        val state = LiveSpeechCapture("你好，世界！")
        state.accept(transcript("你好，世界。"), 0)
        assertFalse(state.canClose(10_000), "Network inactivity is not proof of completed audio")
        state.accept(audio(samples(24_000, 1000)), 500)
        assertFalse(state.canClose(10_000))
        state.accept(audio(samples(36_000, 0)), 1_500)
        assertTrue(state.canClose(1_500))
        state.markClosing()
        val result = state.finish()
        assertEquals("你好，世界。", result.transcript)
        assertEquals(120_000, result.pcm.size)
        assertEquals(24_000, result.sampleRate)
    }

    @Test
    fun `partial transcript cannot finish even after silence`()
    {
        val state = LiveSpeechCapture("你好世界")
        state.accept(audio(samples(1_000, 1000)), 0)
        state.accept(transcript("你好"), 100)
        state.accept(audio(samples(100_000, 0)), 3_000)
        assertFalse(state.canClose(10_000))
        state.markClosing()
        assertFailsWith<IllegalStateException> { state.finish() }
    }

    @Test
    fun `silence received before final transcript does not satisfy tail`()
    {
        val state = LiveSpeechCapture("Hello")
        state.accept(audio(samples(1_000, 1000)), 0)
        state.accept(audio(samples(36_000, 0)), 1_500)
        state.accept(transcript("Hello"), 1_600)
        assertFalse(state.canClose(4_000))
        state.accept(audio(samples(36_000, 0)), 3_100)
        assertTrue(state.canClose(3_100))
    }

    @Test
    fun `late audio continues capture rather than truncating a word`()
    {
        val state = LiveSpeechCapture("你好")
        state.accept(audio(samples(1_000, 1000)), 0)
        state.accept(transcript("你好"), 100)
        state.accept(audio(samples(35_000, 0)), 1_600)
        assertFalse(state.canClose(2_000))
        state.accept(audio(samples(5_000, 1000)), 2_100)
        assertFalse(state.canClose(4_000))
        state.accept(audio(samples(36_000, 0)), 3_600)
        assertTrue(state.canClose(3_600))
    }

    @Test
    fun `extra or wrong spoken words are reported instead of returned`()
    {
        val state = LiveSpeechCapture("你好")
        state.accept(transcript("你好"), 0)
        val error = assertFailsWith<IllegalArgumentException> {
            state.accept(transcript("呀，欢迎回来"), 10)
        }
        assertContains(error.message!!, "实际朗读与指定文本不一致")
        assertContains(error.message!!, "你好呀，欢迎回来")
    }

    @Test
    fun `audio after requested close is not accepted as a completed clip`()
    {
        val state = completeCapture()
        state.markClosing()
        assertFailsWith<IllegalStateException> { state.accept(audio(samples(1, 1000)), 2_000) }
    }

    @Test
    fun `PCM sample split across websocket frames remains intact`()
    {
        val state = LiveSpeechCapture("hi")
        state.accept(audio(byteArrayOf(0xe8.toByte())), 0)
        state.accept(transcript("hi"), 0)
        state.accept(audio(byteArrayOf(0x03)), 10)
        state.accept(audio(samples(36_000, 0)), 1_500)
        assertTrue(state.canClose(1_500))
        state.markClosing()
        assertEquals(0xe8.toByte(), state.finish().pcm[0])
    }

    @Test
    fun `malformed audio and size bounds fail`()
    {
        assertFailsWith<IllegalArgumentException> {
            LiveSpeechCapture("hi").accept(event("session.output_audio.delta", "invalid base64!"), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LiveSpeechCapture("hi", maxBytes = 2).accept(audio(ByteArray(4)), 0)
        }
        val state = LiveSpeechCapture("hi", maxBytes = 4)
        state.accept(audio(ByteArray(4)), 0)
        assertFailsWith<IllegalArgumentException> { state.accept(audio(ByteArray(2)), 0) }
    }

    @Test
    fun `API error retains provider code and message`()
    {
        val state = LiveSpeechCapture("hi")
        val error = assertFailsWith<IllegalStateException> {
            state.accept(buildJsonObject {
                put("type", "error")
                putJsonObject("error") {
                    put("code", "rate_limit_exceeded")
                    put("message", "Too many requests; wait 12 seconds")
                }
            }, 0)
        }
        assertContains(error.message!!, "rate_limit_exceeded")
        assertContains(error.message!!, "wait 12 seconds")
    }

    @Test
    fun `normalization ignores typography but not wording`()
    {
        assertEquals("helloworld123你好", normalizedSpeechText("Hello, World! １２３ 你好。"))
        assertNotEquals(normalizedSpeechText("可以"), normalizedSpeechText("不可以"))
    }

    @Test
    fun `invalid parameters fail before network access`()
    {
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 2.1))
            assertFailsWith<IllegalArgumentException> { validateSpeechInput("你好", speed, "平静") }
        for (text in listOf("", " ", "！。", "你".repeat(1_001)))
            assertFailsWith<IllegalArgumentException> { validateSpeechInput(text, 1.0, "平静") }
        assertFailsWith<IllegalArgumentException> { validateSpeechInput("你好", 1.0, "") }
        assertFailsWith<IllegalArgumentException> { validateSpeechInput("你好", 1.0, "好".repeat(201)) }
    }

    private fun completeCapture() = LiveSpeechCapture("hi").apply {
        accept(audio(samples(1_000, 1000)), 0)
        accept(transcript("hi"), 0)
        accept(audio(samples(36_000, 0)), 1_500)
        assertTrue(canClose(1_500))
    }

    private fun transcript(value: String) = event("session.output_transcript.delta", value)
    private fun audio(value: ByteArray) = event("session.output_audio.delta", Base64.getEncoder().encodeToString(value))
    private fun event(type: String, delta: String) = buildJsonObject { put("type", type); put("delta", delta) }
    private fun samples(count: Int, amplitude: Int): ByteArray = ByteArray(count * 2) { index ->
        (if (index % 2 == 0) amplitude else amplitude shr 8).toByte()
    }

    /** Minimal real RFC6455 peer keeps transport tests independent of the application web server. */
    private class SpeechSocketServer(private val handle: (Socket) -> Unit): AutoCloseable
    {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        private val error = AtomicReference<Throwable?>()
        private val finished = CountDownLatch(1)
        val connections = AtomicInteger()
        private var accepted: Socket? = null

        init
        {
            executor.submit {
                try
                {
                    server.accept().use { socket ->
                        accepted = socket
                        connections.incrementAndGet()
                        socket.soTimeout = 6_000
                        val input = socket.getInputStream()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) headers.append(input.readRequired().toChar())
                        val key = headers.lines().single { it.startsWith("Sec-WebSocket-Key:", true) }
                            .substringAfter(':').trim()
                        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                                "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                            flush()
                        }
                        handle(socket)
                    }
                }
                catch (failure: Throwable) { error.set(failure) }
                finally { finished.countDown() }
            }
        }

        fun config() = SpeechConfig(url = "ws://127.0.0.1:${server.localPort}/v1/live/sessions",
            voiceId = "voice_test", voicePrompt = "Soft adult feminine voice", timeoutMillis = 6_000)

        fun verify()
        {
            assertTrue(finished.await(2, TimeUnit.SECONDS), "WebSocket peer did not finish")
            error.get()?.let { throw AssertionError("WebSocket peer failed", it) }
        }

        override fun close()
        {
            accepted?.close()
            server.close()
            executor.shutdownNow()
        }
    }

    companion object
    {
        private fun InputStream.readRequired(): Int = read().also { if (it < 0) throw EOFException() }

        private fun Socket.readJson(): JsonObject
        {
            val input = getInputStream()
            val opcode = input.readRequired() and 0x0f
            require(opcode == 1) { "Expected text frame, got opcode $opcode" }
            val marker = input.readRequired()
            var length = (marker and 127).toLong()
            if (length == 126L) length = (input.readRequired() shl 8 or input.readRequired()).toLong()
            else if (length == 127L) { length = 0; repeat(8) { length = (length shl 8) or input.readRequired().toLong() } }
            require(length in 1..1_000_000)
            val mask = if (marker and 128 != 0) input.readNBytes(4) else null
            val bytes = input.readNBytes(length.toInt())
            if (mask != null) bytes.indices.forEach { index -> bytes[index] = (bytes[index].toInt() xor mask[index % 4].toInt()).toByte() }
            return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }

        private fun Socket.writeJson(event: JsonObject)
        {
            val bytes = event.toString().toByteArray()
            getOutputStream().apply {
                write(0x81)
                when
                {
                    bytes.size < 126 -> write(bytes.size)
                    bytes.size <= 65_535 -> { write(126); write(bytes.size shr 8); write(bytes.size and 255) }
                    else -> { write(127); for (shift in 56 downTo 0 step 8) write((bytes.size.toLong() ushr shift).toInt() and 255) }
                }
                write(bytes)
                flush()
            }
        }
    }
}
