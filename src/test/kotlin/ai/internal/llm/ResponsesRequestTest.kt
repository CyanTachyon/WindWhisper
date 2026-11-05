package moe.tachyon.windwhisper.ai.internal.llm

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.initializeTestEnvironment
import moe.tachyon.windwhisper.utils.JsonSchema
import kotlin.reflect.typeOf
import kotlin.test.*

class ResponsesRequestTest
{
    @BeforeTest
    fun initialize() = initializeTestEnvironment()

    @Test
    fun `requests own the persistent chain instructions compaction and native tools`()
    {
        val custom = Yaml.default.parseToYamlNode("reasoning: {effort: low}\nmax_output_tokens: 2048") as YamlMap
        val model = AiConfig.LlmModel(compactThreshold = 12000, customRequestParms = custom)
        val tool = AiToolInfo("read_post", "Read a post", null,
            { _: String, _ -> AiToolInfo.ToolResult(Content("unused")) }, JsonSchema.Object(), typeOf<String>())
        val input = ChatMessages(Role.USER, "New notification").toResponsesInput()
        val body = buildResponsesRequest(model, input, "Forum instructions", "resp_previous", listOf(tool), true)

        assertEquals(JsonArray(input), body["input"])
        assertEquals(JsonPrimitive("resp_previous"), body["previous_response_id"])
        assertEquals(JsonPrimitive("Forum instructions"), body["instructions"])
        assertEquals(JsonPrimitive(true), body["store"])
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive("low"), body["reasoning"]!!.jsonObject["effort"])
        assertEquals(JsonPrimitive(2048), body["max_output_tokens"])
        assertEquals(Json.parseToJsonElement("""[{"type":"compaction","compact_threshold":12000}]"""), body["context_management"])
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("function", "web_search", "image_generation"), tools.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("read_post"), tools[0]["name"])
        assertEquals(JsonPrimitive(false), tools[0]["strict"])
        assertEquals(JsonPrimitive("object"), tools[0]["parameters"]!!.jsonObject["type"])
        assertFalse("function" in tools[0])
        assertFalse(body.keys.any { it in setOf("messages", "max_tokens", "thinking_budget", "frequency_penalty", "presence_penalty", "stop", "conversation") })
    }

    @Test
    fun `hosted tool switches are independent and first request omits previous id`()
    {
        for (web in listOf(false, true)) for (image in listOf(false, true))
        {
            val body = buildResponsesRequest(AiConfig.LlmModel(webSearch = web, imageGeneration = image),
                emptyList(), "", null, emptyList(), false)
            val types = body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
            assertEquals(web, "web_search" in types)
            assertEquals(image, "image_generation" in types)
            assertEquals((if (web) 1 else 0) + (if (image) 1 else 0), types.size)
            assertFalse("previous_response_id" in body)
        }
    }

    @Test
    fun `custom options cannot replace chain transport tools or legacy protocol fields`()
    {
        val forbidden = listOf("model", "input", "instructions", "stream", "store", "previous_response_id",
            "conversation", "context_management", "tools", "background", "messages", "max_tokens",
            "thinking_budget", "frequency_penalty", "presence_penalty", "stop")
        for (field in forbidden)
        {
            val custom = Yaml.default.parseToYamlNode("$field: ignored") as YamlMap
            assertFailsWith<IllegalArgumentException>(field) {
                buildResponsesRequest(AiConfig.LlmModel(customRequestParms = custom), emptyList(), "", null, emptyList(), false)
            }
        }
    }

    @Test
    fun `new user and tool inputs retain media while internal markers are filtered`()
    {
        val media = Content(ContentNode.text("Look"), ContentNode.image("data:image/png;base64,AA=="),
            ContentNode.file("doc.pdf", "data:application/pdf;base64,AA=="),
            ContentNode.file("remote.pdf", "https://example.com/doc.pdf"))
        val input = ChatMessages(ChatMessage(Role.SYSTEM, "Developer guidance"), ChatMessage(Role.USER, media),
            ChatMessage(Role.MARKING("internal", JsonNull), "hidden"),
            ChatMessage(Role.TOOL("call_1", "read_image"), media)).toResponsesInput()
        assertEquals(3, input.size)
        assertEquals(JsonPrimitive("developer"), input[0]["role"])
        val user = input[1]["content"]!!.jsonArray
        assertEquals(user, input[2]["output"])
        assertEquals(JsonPrimitive("call_1"), input[2]["call_id"])
        assertEquals(listOf("input_text", "input_image", "input_file", "input_file"), user.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("data:image/png;base64,AA=="), user[1].jsonObject["image_url"])
        assertEquals(JsonPrimitive("data:application/pdf;base64,AA=="), user[2].jsonObject["file_data"])
        assertEquals(JsonPrimitive("https://example.com/doc.pdf"), user[3].jsonObject["file_url"])
        assertFalse("file_data" in user[3].jsonObject)
    }

    @Test
    fun `old assistant transcripts cannot be replayed into the persistent chain`()
    {
        val assistant = ChatMessage(Role.ASSISTANT, Content("Old answer"), responsesOutput = listOf(
            buildJsonObject { put("type", "reasoning"); put("encrypted_content", "old-state") }))
        assertFailsWith<IllegalStateException> { ChatMessages(assistant).toResponsesInput() }
    }
}
