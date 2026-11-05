package moe.tachyon.windwhisper.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.GeneratedAudioStore
import moe.tachyon.windwhisper.ai.speech.SpeechAudio
import moe.tachyon.windwhisper.ai.speech.generateLiveSpeech
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.SpeechConfig
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.uploadGeneratedAudio
import moe.tachyon.windwhisper.utils.JsonSchema
import java.net.URI
import java.util.UUID

class Speech(
    private val configuration: SpeechConfig,
    private val audioStore: GeneratedAudioStore,
    private val upload: suspend (GeneratedAudioStore.Audio) -> String,
    private val generate: suspend (SpeechConfig, String, String, Double, String) -> SpeechAudio = ::generateLiveSpeech,
): AiToolSet.ToolProvider<Any?>
{
    constructor(configuration: SpeechConfig, audioStore: GeneratedAudioStore, user: LoginData):
        this(configuration, audioStore, { user.uploadGeneratedAudio(it) })

    override val name: String = "Speech"

    @Serializable
    data class GenerateSpeechParams(
        @JsonSchema.Description("要朗读的完整文本，1 至 500 字符。仅提供朗读内容。")
        val text: String,
        @JsonSchema.Description("目标说话速度，0.5 至 2.0，1.0 为自然速度。通过说话节奏引导实现，是近似值，不是精确播放倍率。")
        val speed: Double = 1.0,
        @JsonSchema.Description("表达方式，例如平静、开心、温柔、兴奋；描述语气与情绪，保持配置中的同一个音色。")
        val style: String = "平静",
    )

    override suspend fun <S> AiToolSet<S>.registerTools()
    {
        if (!configuration.enabled) return
        registerTool<GenerateSpeechParams>(
            "generate_speech",
            "生成语音",
            "使用固定专属音色朗读文本并上传音频，返回可放入 send_post.content 的语音 Markdown。" +
                "上传不会发送帖子。可指定文本、目标语速与情绪表达；语速为近似引导。" +
                "程序检查转写文本及音频结尾，发现不完整或改述会返回错误。",
        )
        {
            require(parm.text.isNotBlank() && parm.text.length <= 500) { "语音文本必须为 1 至 500 字符" }
            require(parm.speed.isFinite() && parm.speed in 0.5..2.0) { "语速必须在 0.5 至 2.0 之间" }
            require(parm.style.isNotBlank() && parm.style.length <= 200) { "表达方式必须为 1 至 200 字符" }
            val key = speechApiKey(configuration, requireNotNull(model) { "语音工具缺少模型配置" })
            val result = generate(configuration, key, parm.text, parm.speed, parm.style)
            val id = "speech_${UUID.randomUUID().toString().replace("-", "") }"
            audioStore.save(id, result.pcm, result.sampleRate)
            val markdown = upload(audioStore.read(id))
            AiToolInfo.ToolResult(Content(buildJsonObject {
                put("audioId", id)
                put("transcript", result.transcript)
                put("markdown", markdown)
                put("durationSeconds", result.pcm.size.toDouble() / (result.sampleRate * 2))
            }.toString()), markdown)
        }
    }
}

internal fun speechApiKey(configuration: SpeechConfig, model: AiConfig.LlmModel): String
{
    configuration.key?.let { return it }
    val speech = URI(configuration.url)
    val responses = URI(model.url)
    val speechPort = if (speech.port >= 0) speech.port else if (speech.scheme == "wss") 443 else 80
    val responsesPort = if (responses.port >= 0) responses.port else if (responses.scheme == "https") 443 else 80
    require(speech.host.equals(responses.host, ignoreCase = true) && speechPort == responsesPort &&
        ((speech.scheme == "wss" && responses.scheme == "https") ||
            (speech.scheme == "ws" && responses.scheme == "http"))) {
        "语音服务与 Responses 的安全协议、主机或端口不同，请显式配置 speech.key"
    }
    return model.key.first()
}
