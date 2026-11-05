package moe.tachyon.windwhisper.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import moe.tachyon.windwhisper.utils.toJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmModelTest
{
    private val yaml = ConfigLoader.configSerializer

    @Test
    fun defaultConfigurationUsesResponsesAndHostedCapabilities()
    {
        val model = yaml.decodeFromString<AiConfig>("{}").model
        assertEquals("https://api.openai.com/v1/responses", model.url)
        assertEquals("gpt-6-astra", model.model)
        assertTrue(model.visible)
        assertTrue(model.webSearch)
        assertTrue(model.imageGeneration)
        assertEquals("gpt-image-2.6-sunburst", model.imageGenerationModel)
        assertEquals(100_000, model.compactThreshold)
    }

    @Test
    fun nativeCapabilitiesAndCompactionThresholdDecodeFromYaml()
    {
        val config = yaml.decodeFromString<AiConfig>("""
            model:
              url: "https://api.openai.com/v1/responses"
              key: ["first-key", "second-key"]
              compactThreshold: 64000
              webSearch: false
              imageGeneration: false
              imageGenerationModel: gpt-image-2.6-flare
              customRequestParms:
                reasoning:
                  effort: low
        """.trimIndent())
        assertEquals(64_000, config.model.compactThreshold)
        assertFalse(config.model.webSearch)
        assertFalse(config.model.imageGeneration)
        assertEquals("gpt-image-2.6-flare", config.model.imageGenerationModel)
        assertEquals(listOf("first-key", "second-key"), config.model.key)
        val decoded = yaml.decodeFromString<AiConfig>(yaml.encodeToString(config))
        assertEquals(config.model.copy(customRequestParms = null), decoded.model.copy(customRequestParms = null))
        assertEquals(config.model.customRequestParms?.toJsonElement(), decoded.model.customRequestParms?.toJsonElement())
    }

    @Test
    fun rejectsInvalidCompactionThresholdAndMissingCredentials()
    {
        for (threshold in listOf(-1, 0, 999))
            assertFails { AiConfig.LlmModel(compactThreshold = threshold) }
        assertEquals(1_000, AiConfig.LlmModel(compactThreshold = 1_000).compactThreshold)
        assertFails { AiConfig.LlmModel(key = emptyList()) }
        assertFails { AiConfig.LlmModel(key = listOf("")) }
        assertFails { AiConfig.LlmModel(key = listOf("valid", " ")) }
        assertFails { AiConfig.LlmModel(maxConcurrency = 0) }
        assertFails { AiConfig.LlmModel(imageGenerationModel = " ") }
        for (url in listOf("https://api.openai.com/v1/chat/completions", "https://api.openai.com/v1/responses?key=secret", "file:///v1/responses"))
            assertFails { AiConfig.LlmModel(url = url) }
    }

    @Test
    fun serializedConfigurationContainsNoLegacyProtocolOrMemoryOptions()
    {
        val text = yaml.encodeToString(AiConfig())
        for (name in listOf("retry:", "apiType", "search:", "webSearchKey", "vlmModel", "thinking_budget", "toolable", "supportToolCalls"))
            assertFalse(name in text, name)
    }
}
