@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.windwhisper.ai.internal.rerank

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.ai.AiRetryFailedException
import moe.tachyon.windwhisper.ai.UnknownAiResponseException
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.contentNegotiationJson
import moe.tachyon.windwhisper.showJson
import moe.tachyon.windwhisper.utils.ktorClientEngineFactory
import java.util.concurrent.TimeUnit

private val logger = WindWhisperLogger.getLogger()
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

@Serializable
private data class RerankRequest(
    val model: String,
    val query: String,
    val documents: List<String>,
)

@Serializable
private data class RerankResponse(
    val results: List<Result>,
)
{
    @Serializable
    data class Result(
        val index: Int,
        @SerialName("relevance_score")
        val relevanceScore: Double,
    )
}

suspend fun sendRerankRequest(
    query: String,
    documents: List<String>,
    model: AiConfig.Model,
): List<String> = model.semaphore.withPermit()
{
    val request = RerankRequest(
        model = model.model,
        query = query,
        documents = documents,
    )

    logger.config("sending rerank request to ${model.url} with: ${showJson.encodeToString(request)}")

    val errors = mutableListOf<Throwable>()
    repeat(aiConfig.retry)
    {
        runCatching()
        {
            val response = client.post(model.url)
            {
                contentType(ContentType.Application.Json)
                bearerAuth(model.key.random())
                setBody(request)
            }.bodyAsText()
            val res = contentNegotiationJson.decodeFromString<RerankResponse>(response)
            require(res.results.all { it.index < documents.size })
            {
                "Some results have an index that is out of bounds for the documents list."
            }
            return res.results.map { documents[it.index] }
        }.onFailure(errors::add)
    }
    throw errors.map(::UnknownAiResponseException).let(::AiRetryFailedException)
}