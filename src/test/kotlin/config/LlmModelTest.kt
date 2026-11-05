package moe.tachyon.windwhisper.config

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmModelTest
{
    @Test
    fun obsoleteToolCapabilityFlagsAreIgnoredInLegacyYaml()
    {
        val yaml = ConfigLoader.configSerializer
        val legacy = yaml.decodeFromString<AiConfig>("""
            model:
              toolable: false
              supportToolCalls: false
        """.trimIndent())
        assertEquals(AiConfig(), legacy)
    }
}
