package moe.tachyon.windwhisper.ai.tools

import kotlinx.coroutines.runBlocking
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSearchSelectionTest
{
    @BeforeTest
    fun setUp() = initializeTestEnvironment()

    @Test
    fun `default and explicit Tavily selections expose all three existing tools`() = runBlocking {
        val tools = AiToolSet(WebSearch)
        val expected = setOf("web_search", "web_extract", "web_crawl")
        for (model in listOf(null, AiConfig.LlmModel(), AiConfig.LlmModel(search = AiConfig.Search.TAVILY)))
        {
            assertEquals(expected, tools.getTools(null, model).map { it.name }.toSet())
        }
    }

    @Test
    fun `built-in Responses search suppresses every Tavily function`() = runBlocking {
        val tools = AiToolSet(WebSearch)
        val model = AiConfig.LlmModel(apiType = AiConfig.ApiType.RESPONSES, search = AiConfig.Search.RESPONSES)
        assertTrue(tools.getTools(null, model).isEmpty())
    }

    @Test
    fun `disabled search suppresses every Tavily function for both APIs`() = runBlocking {
        val tools = AiToolSet(WebSearch)
        for (apiType in AiConfig.ApiType.entries)
        {
            val model = AiConfig.LlmModel(apiType = apiType, search = AiConfig.Search.NONE)
            assertTrue(tools.getTools(null, model).isEmpty(), "apiType=$apiType")
        }
    }
}
