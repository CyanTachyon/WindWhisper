package moe.tachyon.windwhisper.config

import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class AiConfig(
    @YamlComment("超时时间（毫秒）：非流式请求/恢复GET为总时限；流式请求为无新事件的最长等待时间，包含等待响应头")
    val timeout: Long = 2 * 60 * 1_000,
    val model: LlmModel = LlmModel(),
)
{
    @Serializable
    data class LlmModel(
        @YamlComment("Responses API的完整接口地址")
        val url: String = "https://api.openai.com/v1/responses",
        val model: String = "gpt-6-astra",
        val maxConcurrency: Int = 50,
        val visible: Boolean = true,
        @YamlComment("持续会话固定使用第一个密钥，请确保它可以访问同一服务端会话")
        val key: List<String> = listOf("your api key"),
        val customRequestParms: YamlMap? = null,
        @YamlComment("服务端自动压缩的token阈值，至少1000，且应低于模型上下文上限")
        val compactThreshold: Int = 100_000,
        @YamlComment("启用Responses内置联网搜索")
        val webSearch: Boolean = true,
        @YamlComment("启用Responses内置图片生成")
        val imageGeneration: Boolean = true,
        @YamlComment("Responses图片工具使用的模型；显式指定以支持主模型传入的complexity参数")
        val imageGenerationModel: String = "gpt-image-2.6-sunburst",
    )
    {
        val semaphore by lazy { Semaphore(maxConcurrency) }

        init
        {
            val endpoint = URI(url)
            require(endpoint.scheme in setOf("http", "https") && !endpoint.host.isNullOrBlank() &&
                endpoint.path.trimEnd('/').endsWith("/responses") && endpoint.rawQuery == null && endpoint.rawFragment == null) {
                "url must be a complete Responses endpoint, without query or fragment"
            }
            require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
            require(key.isNotEmpty() && key.all { it.isNotBlank() }) { "Responses API keys must not be empty" }
            require(compactThreshold >= 1_000) { "compactThreshold must be at least 1000" }
            require(imageGenerationModel.isNotBlank()) { "imageGenerationModel must not be blank" }
        }
    }

    init
    {
        require(timeout > 0) { "timeout must be greater than 0" }
    }
}

val aiConfig: AiConfig by config("ai.yml", AiConfig())
