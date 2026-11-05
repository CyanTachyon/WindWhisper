@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.windwhisper.ai.internal.imageGeneration

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.contentNegotiationJson
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.utils.downloadHttpClient
import moe.tachyon.windwhisper.utils.ktorClientEngineFactory
import moe.tachyon.windwhisper.utils.toJpegBytes
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private val logger = WindWhisperLogger.getLogger()

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class Request(
    val model: String,
    val prompt: String,
    @SerialName("image_size")
    val imageSize: String,
    @SerialName("batch_size")
    val batchSize: Int = 1,
    @SerialName("num_inference_steps")
    val inferenceSteps: Int = 20,
    @SerialName("guidance_scale")
    val guidanceScale: Double = 7.5,
    @SerialName("seed")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val seed: Long? = null,
    @SerialName("negative_prompt")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val negativePrompt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val image: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val image2: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val image3: String? = null,
)

@Serializable
private data class Response(
    val images: List<Image>,
    val timings: Timings,
    val seed: Long,
)
{
    @Serializable
    data class Image(
        val url: String,
    )

    @Serializable
    data class Timings(
        val inference: Double,
    )
}

private val client = HttpClient(ktorClientEngineFactory)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        config { callTimeout(0, TimeUnit.MILLISECONDS) }
    }
    install(ContentNegotiation)
    {
        json(contentNegotiationJson)
    }
}

suspend fun sendImageGenerationRequest(
    url: String,
    key: String,
    model: String,
    prompt: String,
    imageSize: String = "512x512",
    batchSize: Int = 1,
    inferenceSteps: Int = 20,
    guidanceScale: Double = 7.5,
    seed: Long? = null,
    negativePrompt: String? = null,
    image: String? = null,
    image2: String? = null,
    image3: String? = null,
): List<ByteArray>
{
    val request = Request(
        model = model,
        prompt = prompt,
        imageSize = imageSize,
        batchSize = batchSize,
        inferenceSteps = inferenceSteps,
        guidanceScale = guidanceScale,
        seed = seed,
        negativePrompt = negativePrompt,
        image = image,
        image2 = image2,
        image3 = image3,
    )

    val response = client.post(url)
    {
        bearerAuth(key)
        contentType(ContentType.Application.Json)
        setBody(request)
        accept(ContentType.Any)
    }.bodyAsText()
    val json = runCatching()
    {
        contentNegotiationJson.decodeFromString<JsonElement>(response)
    }.onFailure()
    {
        logger.warning("Ai response is not a valid JSON: $response")
    }.getOrThrow()

    val res = runCatching()
    {
        contentNegotiationJson.decodeFromJsonElement<Response>(json)
    }.onFailure()
    {
        logger.warning("Failed to decode image generation response: $response")
    }.getOrThrow()

    return res.images.map { image ->
        val response = downloadHttpClient.get(image.url)
        require(response.status.isSuccess()) { "Image download failed with HTTP status ${response.status.value}" }
        val bytes = response.body<ByteArray>()
        val decoded = bytes.inputStream().use { ImageIO.read(it) }
            ?: error("Downloaded image has an unsupported format")
        decoded.toJpegBytes()
    }
}

suspend fun sendImageGenerationRequest(
    model: AiConfig.Model,
    prompt: String,
    imageSize: String = "512x512",
    batchSize: Int = 1,
    inferenceSteps: Int = 20,
    guidanceScale: Double = 7.5,
    seed: Long? = null,
    negativePrompt: String? = null,
    image: String? = null,
    image2: String? = null,
    image3: String? = null,
): List<ByteArray> = model.semaphore.withPermit()
{
    sendImageGenerationRequest(
        url = model.url,
        key = model.key.random(),
        model = model.model,
        prompt = prompt,
        imageSize = imageSize,
        batchSize = batchSize,
        inferenceSteps = inferenceSteps,
        guidanceScale = guidanceScale,
        seed = seed,
        negativePrompt = negativePrompt,
        image = image,
        image2 = image2,
        image3 = image3,
    )
}
