package moe.tachyon.windwhisper.ai.internal.llm

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.dataJson
import moe.tachyon.windwhisper.initializeTestEnvironment
import moe.tachyon.windwhisper.utils.JsonSchema
import kotlin.reflect.typeOf
import kotlin.test.*

class ResponsesRequestTest
{
    @BeforeTest
    fun initialize() = initializeTestEnvironment()

    private fun context(model: AiConfig.LlmModel = AiConfig.LlmModel(
        apiType = AiConfig.ApiType.RESPONSES, model = "gpt-6-astra",
    )) = LlmLoopPlugin.Context(model, ChatMessages.empty(), null, null, null, null, null, null,
        emptyList(), mutableListOf(), {}, {})

    @Test
    fun `maps request fields and native tool schemas without chat fields`()
    {
        val context = context().apply {
            maxTokens = 2048
            tools = listOf(AiToolInfo("lookup", "Look up a post", null,
                { _: String, _ -> AiToolInfo.ToolResult(Content("unused")) },
                JsonSchema.Object(properties = mapOf("query" to JsonSchema.String(null))), typeOf<String>()))
        }
        val body = buildResponsesRequest(context, ChatMessages(ChatMessage(Role.SYSTEM, "Instructions"),
            ChatMessage(Role.USER, "Question")), true)

        assertEquals("gpt-6-astra", body["model"]?.jsonPrimitive?.content)
        assertEquals(2048, body["max_output_tokens"]?.jsonPrimitive?.int)
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive("reasoning.encrypted_content"), body["include"]?.jsonArray?.single())
        assertEquals(listOf("system", "user"), body["input"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertFalse(body.keys.any { it in setOf("messages", "max_tokens", "thinking_budget", "frequency_penalty", "presence_penalty", "stop") })
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("lookup"), tool["name"])
        assertEquals(JsonPrimitive(false), tool["strict"])
        assertFalse("function" in tool)
        assertEquals(JsonPrimitive("object"), tool["parameters"]!!.jsonObject["type"])
    }

    @Test
    fun `preserves images and files in user input and function output`()
    {
        val media = Content(ContentNode.text("Look"), ContentNode.image("data:image/png;base64,AA=="),
            ContentNode.file("doc.pdf", "data:application/pdf;base64,AA=="),
            ContentNode.file("remote.pdf", "https://example.com/doc.pdf"))
        val input = ChatMessages(ChatMessage(Role.USER, media), ChatMessage(Role.TOOL("call_1", "read_image"), media)).toResponsesInput()
        val user = input[0].jsonObject["content"]!!.jsonArray
        assertEquals(user, input[1].jsonObject["output"])
        assertEquals(JsonPrimitive("call_1"), input[1].jsonObject["call_id"])
        assertEquals(listOf("input_text", "input_image", "input_file", "input_file"), user.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("data:image/png;base64,AA=="), user[1].jsonObject["image_url"])
        assertEquals(JsonPrimitive("data:application/pdf;base64,AA=="), user[2].jsonObject["file_data"])
        assertEquals(JsonPrimitive("https://example.com/doc.pdf"), user[3].jsonObject["file_url"])
        assertFalse("file_data" in user[3].jsonObject)
    }

    @Test
    fun `replays raw output once and retains it across serialization and optimization`()
    {
        val output = Json.parseToJsonElement("""[
            {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"cipher"},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Checking","annotations":[]}]},
            {"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}
        ]""").jsonArray.map { it.jsonObject }
        val response = ChatMessage(Role.ASSISTANT, Content("Checking"), toolCalls = listOf(ChatMessage.ToolCall("call_1", "lookup", "{}")), responsesOutput = output)
        val restored = dataJson.decodeFromString<ChatMessage>(dataJson.encodeToString(response))
        assertEquals(output, restored.responsesOutput)
        val messages = (ChatMessages(ChatMessage(Role.ASSISTANT, "Older reply")) + restored) +
            ChatMessage(Role.TOOL("call_1", "lookup"), "Found")
        assertEquals(3, messages.size)
        val input = messages.toResponsesInput()
        assertEquals(output, input.subList(1, 4))
        assertEquals(1, input.count { it.jsonObject["type"] == JsonPrimitive("function_call") })
        val twice = (ChatMessages(response) + restored).single()
        assertEquals(output + output, twice.responsesOutput)
        assertEquals(JsonArray(output + output), ChatMessages(twice).toResponsesInput())
    }

    @Test
    fun `converts legacy assistant calls and filters internal markers`()
    {
        val legacy = ChatMessage(Role.ASSISTANT, Content("Checking"), toolCalls = listOf(ChatMessage.ToolCall("call_1", "lookup", "{}")))
        val input = ChatMessages(legacy, ChatMessage(Role.MARKING("internal", JsonNull), "hidden"),
            ChatMessage(Role.TOOL("call_1", "lookup"), "Found")).toResponsesInput()
        assertEquals(3, input.size)
        assertEquals(JsonPrimitive("function_call"), input[1].jsonObject["type"])
        assertEquals(JsonPrimitive("call_1"), input[1].jsonObject["call_id"])
        assertEquals(JsonPrimitive("Found"), input[2].jsonObject["output"])
    }

    @Test
    fun `custom native options retain encrypted reasoning include`()
    {
        val custom = Yaml.default.parseToYamlNode("""
            reasoning: {effort: low}
            include: [web_search_call.action.sources]
            max_output_tokens: 4096
        """.trimIndent()) as YamlMap
        val body = buildResponsesRequest(context(context().model.copy(customRequestParms = custom)), ChatMessages(Role.USER, "Hello"), false)
        assertEquals(JsonPrimitive("low"), body["reasoning"]!!.jsonObject["effort"])
        assertEquals(4096, body["max_output_tokens"]?.jsonPrimitive?.int)
        assertEquals(setOf("reasoning.encrypted_content", "web_search_call.action.sources"), body["include"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `rejects unsupported legacy options and server managed history`()
    {
        assertFailsWith<IllegalArgumentException> {
            buildResponsesRequest(context().apply { stop = listOf("END") }, ChatMessages.empty(), false)
        }
        assertFailsWith<IllegalArgumentException> {
            buildResponsesRequest(context(context().model.copy(thinkingBudget = 1024)), ChatMessages.empty(), false)
        }
        val custom = Yaml.default.parseToYamlNode("previous_response_id: resp_old") as YamlMap
        assertFailsWith<IllegalArgumentException> {
            buildResponsesRequest(context(context().model.copy(customRequestParms = custom)), ChatMessages.empty(), false)
        }
        val streaming = Yaml.default.parseToYamlNode("stream: true") as YamlMap
        assertFailsWith<IllegalArgumentException> {
            buildResponsesRequest(context(context().model.copy(customRequestParms = streaming)), ChatMessages.empty(), false)
        }
    }

}
