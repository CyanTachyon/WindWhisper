package moe.tachyon.windwhisper.config

import com.charleskorn.kaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SpeechConfigTest
{
    @Test
    fun oldConfigurationsKeepSpeechDisabled()
    {
        assertFalse(Yaml.default.decodeFromString(AiConfig.serializer(), "timeout: 120000").speech.enabled)
    }

    @Test
    fun voiceSettingsSurviveReloadSerialization()
    {
        val config = AiConfig(speech = SpeechConfig(enabled = true, voiceId = "voice_saved", voicePrompt = "Soft adult voice."))
        assertEquals(config, Yaml.default.decodeFromString(AiConfig.serializer(), Yaml.default.encodeToString(AiConfig.serializer(), config)))
    }

    @Test
    fun validatesVoiceAndEndpoint()
    {
        assertFailsWith<IllegalArgumentException> { SpeechConfig(enabled = true) }
        assertFailsWith<IllegalArgumentException> { SpeechConfig(enabled = true, voiceId = "marin", voicePrompt = "voice") }
        assertFailsWith<IllegalArgumentException> { SpeechConfig(url = "wss://example.com/v1/live/sessions?key=secret") }
        assertFailsWith<IllegalArgumentException> { SpeechConfig(timeoutMillis = 0) }
    }
}
