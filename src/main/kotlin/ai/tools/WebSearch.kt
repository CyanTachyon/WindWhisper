package moe.tachyon.windwhisper.ai.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.contentNegotiationJson
import moe.tachyon.windwhisper.showJson
import moe.tachyon.windwhisper.utils.JsonSchema
import moe.tachyon.windwhisper.utils.ktorClientEngineFactory

object WebSearch: AiToolSet.ToolProvider<Any?>
{
    override val name: String get() = "网络搜索"
    private val logger = WindWhisperLogger.getLogger<WebSearch>()
    private const val SEARCH_URL = "https://api.tavily.com/search"
    private const val EXTRACT_URL = "https://api.tavily.com/extract"
    private const val USAGE_URL = "https://api.tavily.com/usage"
    private const val CRAWL_URL = "https://api.tavily.com/crawl"

    private val client = HttpClient(ktorClientEngineFactory)
    {
        val timeout = 25_000L
        engine()
        {
            dispatcher = Dispatchers.IO
            requestTimeout = timeout
            endpoint {
                connectTimeout = timeout
                keepAliveTime = timeout
            }
        }
        install(ContentNegotiation)
        {
            json(contentNegotiationJson)
        }
    }

    @Serializable
    private data class Results<T>(val results: List<T>)

    @Serializable
    data class SearchResult(
        val url: String,
        val title: String,
        val content: String,
    )

    suspend fun search(key: String, count: Int = 5): List<SearchResult> = withContext(Dispatchers.IO)
    {
        val res = client.post(SEARCH_URL)
        {
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            bearerAuth(getKey())
            setBody(JsonObject(mapOf(
                "query" to showJson.encodeToJsonElement(key),
                "count" to showJson.encodeToJsonElement(count.coerceIn(1, 20)),
            )))
        }.bodyAsText()
        logger.warning("failed to parse search response: $res")
        {
            contentNegotiationJson.decodeFromString<Results<SearchResult>>(res).results
        }.getOrThrow()
    }

    @Serializable
    data class ExtractResult(
        val url: String,
        @SerialName("raw_content")
        val rawContent: String?,
    )

    suspend fun extract(url: String): List<ExtractResult> = withContext(Dispatchers.IO)
    {
        val res = client.post(EXTRACT_URL)
        {
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            bearerAuth(getKey())
            setBody(JsonObject(mapOf("urls" to showJson.encodeToJsonElement(url))))
        }.bodyAsText()
        logger.warning("failed to parse search response: $res")
        {
            contentNegotiationJson.decodeFromString<Results<ExtractResult>>(res).results
        }.getOrThrow()
    }

    suspend fun crawl(url: String, query: String, depth: Int): List<ExtractResult> = withContext(Dispatchers.IO)
    {
        val res = client.post(CRAWL_URL)
        {
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            bearerAuth(getKey())
            setBody(JsonObject(mapOf(
                "url" to showJson.encodeToJsonElement(url),
                "instructions" to showJson.encodeToJsonElement(query),
                "max_deep" to showJson.encodeToJsonElement(depth),
            )))
        }.bodyAsText()
        logger.warning("failed to parse crawl response: $res")
        {
            contentNegotiationJson.decodeFromString<Results<ExtractResult>>(res).results
        }.getOrThrow()
    }

    suspend fun getKey(): String
    {
        val keys = aiConfig.webSearchKey.shuffled()
        for (key in keys)
            if (checkKey(key)) return key
        error("No valid web search API key found")
    }
    suspend fun checkKey(key: String): Boolean = withContext(Dispatchers.IO)
    {
        val res = client.get(USAGE_URL)
        {
            accept(ContentType.Any)
            bearerAuth(key)
        }.body<JsonObject>()
        runCatching {
            require(res["key"]!!.jsonObject["usage"]!!.jsonPrimitive.long < (res["key"]!!.jsonObject["limit"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE))
            require(res["account"]!!.jsonObject["plan_usage"]!!.jsonPrimitive.long < (res["account"]!!.jsonObject["plan_limit"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE))
        }.isSuccess
    }

    @Serializable
    private data class AiSearchToolData(
        @JsonSchema.Description("搜索关键字")
        val key: String,
        @JsonSchema.Description("返回结果数量，不得超过20, 不填默认为10")
        val count: Int = 10,
    )
    @Serializable
    private data class AiExtractToolData(@JsonSchema.Description("要请求的url") val url: String)

    @Serializable
    private data class AiCrawlToolData(
        @JsonSchema.Description("起始url")
        val url: String,
        @JsonSchema.Description("爬取指令，例如：Find all pages about the Python SDK")
        val query: String,
        @JsonSchema.Description("爬取深度，不得超过5，默认为1")
        val depth: Int = 1,
    )

    override suspend fun <S> AiToolSet<S>.registerTools()
    {
        registerTool<AiSearchToolData>(
            name = "web_search",
            displayName = "联网搜索",
            description = """
                进行网络搜索，将返回若干相关的搜索结果及来源url等，如有需要可以再使用`web_extract`工具提取网页内容
            """.trimIndent(),
        )
        {
            val data = parm.key
            if (data.isBlank()) return@registerTool AiToolInfo.ToolResult(Content("error: key must not be empty"))
            val sb = StringBuilder()
            val res = search(data, parm.count.coerceIn(1, 20))
            for ((i, item) in res.withIndex())
            {
                sb.appendLine("${i + 1}. [${item.title}](${item.url})")
                val content = item.content.trim().lines().joinToString(" ")
                sb.appendLine(content)
                sb.appendLine()

                sendMessage("${i + 1}. ${item.title}: ${item.url}\n")
                sendMessage(content)
                sendMessage("\n\n")
            }
            AiToolInfo.ToolResult(Content(sb.toString()))
        }

        registerTool<AiExtractToolData>(
            name = "web_extract",
            displayName = "获取网页内容",
            description = """
                提取网页内容，将读取指定url的内容并返回
            """.trimIndent(),
        )
        {
            sendMessage("读取网页: [${parm.url}](${parm.url})")
            val data = parm.url
            if (data.isBlank()) return@registerTool AiToolInfo.ToolResult(Content("error: url must not be empty"))
            val res = extract(data)
            if (res.isEmpty()) return@registerTool AiToolInfo.ToolResult(Content("未找到任何内容"))
            val sb = StringBuilder()
            for ((i, item) in res.withIndex())
            {
                sb.appendLine("${i + 1}. [${item.url}](${item.url})")
                val content = item.rawContent?.trim()?.lines()?.joinToString(" ")
                sb.append("<!--content-truncate-start-->")
                sb.appendLine(content)
                sb.append("<!--content-truncate-end-->")
                sb.appendLine()
            }
            res.firstOrNull()?.rawContent?.let()
            {
                sendMessage(it)
            }
            AiToolInfo.ToolResult(Content(sb.toString()))
        }

        registerToolDataGetter("web")
        { _, path ->
            AiToolSet.ToolData(
                type = AiToolSet.ToolData.Type.URL,
                value = path,
            )
        }

        registerTool<AiCrawlToolData>(
            name = "web_crawl",
            displayName = "爬取网页内容",
            description = """
                爬取网页内容，将从指定url开始，按照指令爬取相关页面并返回
                当你需要某个特定的站点中搜索内容时非常有用
            """.trimIndent(),
        )
        {
            sendMessage("爬取网页: [${parm.url}](${parm.url})，指令: ${parm.query}，深度: ${parm.depth}")
            val url = parm.url
            val query = parm.query
            val depth = parm.depth.coerceIn(1, 5)
            if (url.isBlank()) return@registerTool AiToolInfo.ToolResult(Content("error: url must not be empty"))
            if (query.isBlank()) return@registerTool AiToolInfo.ToolResult(Content("error: query must not be empty"))
            val res = crawl(url, query, depth)
            if (res.isEmpty()) return@registerTool AiToolInfo.ToolResult(Content("未找到任何内容"))
            val sb = StringBuilder()
            for ((i, item) in res.withIndex())
            {
                sb.appendLine("${i + 1}. [${item.url}](${item.url})")
                val content = item.rawContent?.trim()?.lines()?.joinToString(" ")
                sb.append("<!--content-truncate-start-->")
                sb.appendLine(content)
                sb.append("<!--content-truncate-end-->")
                sb.appendLine()
            }

            for ((i, item) in res.filterNot { it.rawContent == null }.withIndex())
            {
                sendMessage("${i + 1}. ${item.url}\n")
                sendMessage(item.rawContent!!)
                sendMessage("\n\n")
            }
            AiToolInfo.ToolResult(Content(sb.toString()))
        }
    }
}