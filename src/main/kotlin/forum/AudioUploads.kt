package moe.tachyon.windwhisper.forum

import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.tachyon.windwhisper.ai.GeneratedAudioStore
import moe.tachyon.windwhisper.mainConfig

/** Upload a complete generated WAV; the returned attachment label discloses its synthetic origin. */
suspend fun LoginData.uploadGeneratedAudio(audio: GeneratedAudioStore.Audio): String
{
    GeneratedAudioStore.validateWav(audio.bytes)
    require(audio.contentType == "audio/wav" && audio.filename.matches(Regex("generated-audio-[0-9a-f]{64}\\.wav"))) {
        "生成语音附件元数据无效"
    }
    val response = post("${mainConfig.url}/uploads.json")
    {
        setBody(MultiPartFormDataContent(formData {
            append("type", "composer")
            append("synchronous", "true")
            append("file", audio.bytes, Headers.build {
                append(HttpHeaders.ContentType, audio.contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"${audio.filename}\"")
            })
        }))
    }
    require(response.status.isSuccess()) { "上传生成语音失败，HTTP 状态码 ${response.status.value}\n${response.bodyAsText()}" }
    val shortUrl = response.body<JsonObject>()["short_url"]?.jsonPrimitive?.content
    require(shortUrl != null && shortUrl.matches(Regex("upload://[A-Za-z0-9_-]+\\.wav", RegexOption.IGNORE_CASE))) {
        "论坛未返回有效的语音 short_url"
    }
    return "[AI 生成语音]($shortUrl)"
}
