package moe.tachyon.windwhisper.config

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LlmModelTest
{
    private val yaml = ConfigLoader.configSerializer

    @Test
    fun legacyConfigurationDefaultsToChatCompletions()
    {
        val config = yaml.decodeFromString<AiConfig>("""
            model:
              url: "https://api.deepseek.com/chat/completions"
              model: "deepseek-reasoner"
              key: ["test-key"]
        """.trimIndent())

        assertEquals(AiConfig.ApiType.CHAT_COMPLETIONS, config.model.apiType)
        assertEquals("https://api.deepseek.com/chat/completions", config.model.url)
    }

    @Test
    fun responsesCanBeSelectedForBothMainAndVisionModels()
    {
        val config = yaml.decodeFromString<AiConfig>("""
            model:
              apiType: responses
              url: "https://api.openai.com/v1/responses"
              model: "gpt-6-astra"
              key: ["test-key"]
              visible: true
            vlmModel:
              apiType: responses
              url: "https://api.openai.com/v1/responses"
              model: "gpt-6-astra"
              key: ["test-key"]
              visible: true
        """.trimIndent())

        assertEquals(AiConfig.ApiType.RESPONSES, config.model.apiType)
        assertEquals(AiConfig.ApiType.RESPONSES, config.vlmModel?.apiType)
        assertEquals(true, config.vlmModel?.visible)
    }

    @Test
    fun modelsCanSelectDifferentProtocols()
    {
        val config = yaml.decodeFromString<AiConfig>("""
            model:
              apiType: chat_completions
            vlmModel:
              apiType: responses
              url: "https://api.openai.com/v1/responses"
              visible: true
        """.trimIndent())

        assertEquals(AiConfig.ApiType.CHAT_COMPLETIONS, config.model.apiType)
        assertEquals(AiConfig.ApiType.RESPONSES, config.vlmModel?.apiType)
    }

    @Test
    fun unknownProtocolIsRejected()
    {
        assertFails {
            yaml.decodeFromString<AiConfig>("""
                model:
                  apiType: response
            """.trimIndent())
        }
    }

    @Test
    fun obsoleteToolCapabilityFlagsAreIgnoredInLegacyYaml()
    {
        for (protocol in listOf("chat_completions", "responses"))
        {
            val legacy = yaml.decodeFromString<AiConfig>("""
                model:
                  apiType: $protocol
                  toolable: false
                  supportToolCalls: false
            """.trimIndent())
            val current = yaml.decodeFromString<AiConfig>("""
                model:
                  apiType: $protocol
            """.trimIndent())

            assertEquals(current, legacy)
        }
    }

}
