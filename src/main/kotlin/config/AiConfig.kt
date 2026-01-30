package moe.tachyon.windwhisper.config

import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiConfig(
    @YamlComment("AI请求的超时时间，单位为毫秒，仅限非流式请求")
    val timeout: Long = 2 * 60 * 1_000,
    @YamlComment("AI服务的重试次数")
    val retry: Int = 3,
    val webSearchKey: List<String> = listOf("your web search key"),
    val model: LlmModel = LlmModel(),
    val vlmModel: LlmModel? = null,
)
{
    @Serializable
    data class LlmModel(
        val url: String = "https://api.deepseek.com/chat/completions",
        val model: String = "deepseek-reasoner",
        val maxConcurrency: Int = 50,
        val visible: Boolean = false,
        val toolable: Boolean = false,
        val supportToolCalls: Boolean = toolable,
        @YamlComment("思考预算，单位为token，null表示不设置")
        @SerialName("thinking_budget")
        val thinkingBudget: Int? = null,
        val key: List<String> = listOf("your api key"),

        val customRequestParms: YamlMap? = null
    )
    {
        val semaphore by lazy { Semaphore(maxConcurrency) }

        init
        {
            require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
            require(key.isNotEmpty()) { "chat key must not be empty" }
        }
    }

    @Serializable
    data class ChatModel(
        val model: String,
        val displayName: String = model,
    )

    @Serializable
    data class Model(
        val url: String = "your embedding/reranker url",
        val model: String = "your embedding/reranker model",
        val maxConcurrency: Int = 50,
        val key: List<String> = listOf("your api key"),
    )
    {
        val semaphore by lazy { Semaphore(maxConcurrency) }

        init
        {
            require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
            require(key.isNotEmpty()) { "embedding key must not be empty" }
        }
    }

    @Serializable
    data class VideoModel(
        val submitUrl: String = "https://api.siliconflow.cn/v1/video/submit",
        val statusUrl: String = "https://api.siliconflow.cn/v1/video/status",
        val i2vModel: String = "Wan-AI/Wan2.2-I2V-A14B",
        val t2vModel: String = "Wan-AI/Wan2.2-T2V-A14B",
        val sizes: List<String> = listOf("1280x720", "720x1280", "960x960"),
        val key: List<String> = listOf("your video model api key"),
    )

    init
    {
        require(timeout > 0) { "timeout must be greater than 0" }
        require(retry > 0) { "retry must be greater than 0" }
        require(vlmModel?.visible != false) { "vlmModel must has visible ability" }
    }
}

val aiConfig: AiConfig by config("ai.yml", AiConfig())