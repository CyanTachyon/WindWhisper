package moe.tachyon.windwhisper.ai.tools

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.utils.*
import kotlin.math.sqrt

class ReadImage: AiToolSet.ToolProvider<Any?>
{
    override val name: String get() = "查看图片"

    @Serializable
    private data class SimpleParm(val urls: List<String>)

    private suspend fun List<String>.urlsToContent(): Either<List<ContentNode>, String> = runCatching()
    {
        buildList<ContentNode> {
            for (url in this@urlsToContent)
            {
                val bytes =
                    if (url.startsWith("data:")) url.substringAfter(",").decodeBase64Bytes()
                    else downloadHttpClient.get(url).let { response ->
                        require(response.status.isSuccess()) { "图片/PDF下载失败，HTTP状态码：${response.status.value}" }
                        response.body<ByteArray>()
                    }
                val images = DocumentConversion.documentToImages(bytes)
                    ?: error("不支持的图片/PDF格式，URL：$url")
                for (img in images)
                {
                    val totalPixels = img.width.toLong() * img.height.toLong()
                    val resized = if (totalPixels > 10_000_000)
                    {
                        val scale = sqrt(10_000_000.0 / totalPixels.toDouble())
                        val newWidth = (img.width * scale).toInt().coerceAtLeast(1)
                        val newHeight = (img.height * scale).toInt().coerceAtLeast(1)
                        img.resize(newWidth, newHeight)
                    }
                    else img
                    add(ContentNode.image("data:image/jpeg;base64," + resized.toJpegBytes().encodeBase64()))
                }
            }
        }.let { Either.Left(it) }
    }.getOrElse {
        if (it is CancellationException) throw it
        Either.Right(it.message ?: "未知错误")
    }

    override suspend fun <S> AiToolSet<S>.registerTools()
    {
        registerTool<SimpleParm>(
            name = "read_image",
            displayName = "查看图片",
            description = """
                **如果**你有一个url，其内容是图片，你想获得图片内容，请使用该工具。
                注意！，如果你已经能得知图片内容，则不应使用该工具。
                该工具支持2类URL：
                - 直接的图片/PDF URL，如 `https://example.com/image.png`，该URL必须是公开可访问的。
                - data URL，如 `data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...`，该URL必须是合法的data URL。
            """.trimIndent(),
            condition = { _, model -> model?.visible == true },
        )
        {
            AiToolInfo.ToolResult(Content(parm.urls.urlsToContent().leftOrElse { listOf(ContentNode.text(it)) }))
        }

    }
}
