package moe.tachyon.windwhisper.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.tachyon.windwhisper.ai.GeneratedAudioStore
import moe.tachyon.windwhisper.ai.speech.SpeechAudio
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.SpeechConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeechTest
{
    private val config = SpeechConfig(enabled = true, voiceId = "voice_test", voicePrompt = "A soft adult voice.")
    private val model = AiConfig.LlmModel(key = listOf("test-key"))

    init { initializeTestEnvironment() }

    @Test
    fun disabledSpeechHasNoTool() = runBlocking<Unit> {
        val store = GeneratedAudioStore(Files.createTempDirectory("speech-test").toFile())
        val provider = Speech(SpeechConfig(), store, { error("must not upload") })
        assertTrue(AiToolSet(provider).getTools(null, model).isEmpty())
    }

    @Test
    fun usesConfiguredVoiceAndReturnsUploadedAttachment() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("speech-test").toFile()
        try
        {
            var generated = 0
            var uploaded = 0
            val provider = Speech(config, GeneratedAudioStore(directory), { audio ->
                uploaded++
                assertEquals("audio/wav", audio.contentType)
                assertTrue(audio.bytes.size > 44)
                "[AI 生成语音](upload://test.wav)"
            }) { actual, key, text, speed, style ->
                generated++
                assertEquals(config, actual)
                assertEquals("test-key", key)
                assertEquals("你好呀！", text)
                assertEquals(0.8, speed)
                assertEquals("开心", style)
                SpeechAudio(ByteArray(4800), text)
            }
            val tool = AiToolSet(provider).getTools(null, model).single()
            assertEquals("generate_speech", tool.name)
            val result = tool("""{"text":"你好呀！","speed":0.8,"style":"开心"}""") {}
            assertEquals(1, generated)
            assertEquals(1, uploaded)
            assertEquals("[AI 生成语音](upload://test.wav)", result.showingContent.single().first)
            assertTrue(directory.listFiles()!!.any { it.extension == "wav" })
        }
        finally { directory.deleteRecursively() }
    }

    @Test
    fun invalidParametersDoNotGenerateOrUpload() = runBlocking<Unit> {
        val store = GeneratedAudioStore(Files.createTempDirectory("speech-test").toFile())
        val provider = Speech(config, store, { error("must not upload") }) { _, _, _, _, _ -> error("must not generate") }
        val tool = AiToolSet(provider).getTools(null, model).single()
        for (json in listOf("""{"text":"","speed":1.0,"style":"平静"}""",
            """{"text":"你好","speed":3.0,"style":"平静"}""", """{"text":"你好","speed":1.0,"style":""}"""))
            assertFailsWith<IllegalArgumentException> { tool(json) {} }
    }

    @Test
    fun failedOrCancelledGenerationDoesNotUpload() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("speech-test").toFile()
        try
        {
            val store = GeneratedAudioStore(directory)
            var calls = 0
            val provider = Speech(config, store, { error("must not upload") }) { _, _, _, _, _ ->
                calls++
                throw IllegalStateException("Live error: test detail")
            }
            val tool = AiToolSet(provider).getTools(null, model).single()
            assertEquals("Live error: test detail", assertFailsWith<IllegalStateException> { tool("""{"text":"你好"}""") {} }.message)
            assertEquals(1, calls)
            assertTrue(directory.listFiles()!!.isEmpty())
            val cancelled = Speech(config, store, { error("must not upload") }) { _, _, _, _, _ -> throw CancellationException("stop") }
            assertFailsWith<CancellationException> { AiToolSet(cancelled).getTools(null, model).single()("""{"text":"你好"}""") {} }
        }
        finally { directory.deleteRecursively() }
    }

    @Test
    fun refusesToSendInheritedKeyToDifferentProvider()
    {
        assertFailsWith<IllegalArgumentException> { speechApiKey(config.copy(url = "wss://another.example/v1/live/sessions"), model) }
        assertFailsWith<IllegalArgumentException> { speechApiKey(config.copy(url = "ws://api.openai.com/v1/live/sessions"), model) }
        assertFailsWith<IllegalArgumentException> { speechApiKey(config.copy(url = "wss://api.openai.com:8443/v1/live/sessions"), model) }
        assertEquals("test-key", speechApiKey(config.copy(url = "wss://api.openai.com:443/v1/live/sessions"), model))
        assertEquals("speech-key", speechApiKey(config.copy(url = "wss://another.example/v1/live/sessions", key = "speech-key"), model))
    }
}
