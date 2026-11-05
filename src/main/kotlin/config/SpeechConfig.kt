package moe.tachyon.windwhisper.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class SpeechConfig(
    @YamlComment("启用 generate_speech 工具；须先创建并配置 GPT-Live 专属音色")
    val enabled: Boolean = false,
    val url: String = "wss://api.openai.com/v1/live/sessions",
    val model: String = "gpt-live-1",
    @YamlComment("留空时沿用 Responses 的第一个密钥；地址须属于同一服务商")
    val key: String? = null,
    @YamlComment("已创建的专属音色 ID；不会在每次生成时重新创建音色")
    val voiceId: String = "",
    @YamlComment("创建音色时使用的原始声音描述，用于保持声线一致")
    val voicePrompt: String = "",
    @YamlComment("单段语音生成总时限（毫秒），超时或文本不完整会返回工具错误")
    val timeoutMillis: Long = 120_000,
)
{
    init
    {
        val endpoint = URI(url)
        require(endpoint.scheme in setOf("ws", "wss") && !endpoint.host.isNullOrBlank() &&
            endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null &&
            endpoint.path.trimEnd('/').endsWith("/live/sessions")) {
            "speech.url must be a complete Live WebSocket endpoint without credentials, query or fragment"
        }
        require(model.isNotBlank()) { "speech.model must not be blank" }
        require(key == null || key.isNotBlank()) { "speech.key must be null or nonblank" }
        require(timeoutMillis in 1_000..600_000) { "speech.timeoutMillis must be between 1000 and 600000" }
        if (enabled)
        {
            require(voiceId.matches(Regex("voice_[A-Za-z0-9_-]+"))) { "speech.voiceId must be a saved custom voice ID" }
            require(voicePrompt.isNotBlank()) { "speech.voicePrompt must retain the original voice description" }
        }
    }
}
