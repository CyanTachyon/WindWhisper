package moe.tachyon.windwhisper.ai.tools

import io.ktor.util.*
import kotlinx.serialization.Serializable
import moe.tachyon.windwhisper.ai.*
import moe.tachyon.windwhisper.ai.internal.llm.sendAiRequest
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.utils.*
import java.net.URL
import kotlin.math.sqrt
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ReadImage(private val vlm: AiConfig.LlmModel?): AiToolSet.ToolProvider<Any?>
{
    override val name: String get() = "查看图片"

    @Serializable
    private data class VlmParm(
        @JsonSchema.Description("一个数组，其中的每一项是一个图片URL，特别的，该工具还支持使用 `uuid:` 前缀来引用系统中的文件。")
        val imageUrls: List<String>,
        @JsonSchema.Description("提示词，将直接传递给模型")
        val prompt: String,
    )

    @Serializable
    private data class SimpleParm(val urls: List<String>)

    private fun List<String>.urlsToContent(): Either<List<ContentNode>, String> = runCatching()
    {
        asSequence()
            .flatMap()
            {
                val bytes =
                    if (it.startsWith("data:")) it.substringAfter(",").decodeBase64Bytes()
                    else URL(it).readBytes()
                DocumentConversion.documentToImages(bytes) ?: error("不支持的图片/PDF格式，URL：$it")
            }.map()
            { img ->
                val totalPixels = img.width.toLong() * img.height.toLong()
                if (totalPixels > 10_000_000)
                {
                    val scale = sqrt(10_000_000.0 / totalPixels.toDouble())
                    val newWidth = (img.width * scale).toInt().coerceAtLeast(1)
                    val newHeight = (img.height * scale).toInt().coerceAtLeast(1)
                    img.resize(newWidth, newHeight)
                }
                else img
            }.map()
            {
                "data:image/jpeg;base64," + it.toJpegBytes().encodeBase64()
            }.map(ContentNode::image)
            .toList()
            .let { Either.Left(it) }
    }.getOrElse { Either.Right(it.message ?: "未知错误") }

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

        registerTool<VlmParm>(
            name = "read_image",
            displayName = "查看图片",
            description = """
                **如果**你需要知道一个/一组图片/PDF中的内容，但是你无法直接查看图片/PDF内容，你可以使用该工具来获取图片中的内容。
                注意！，如果你已经能得知图片内容，则不应使用该工具。
                该工具会将全部图片（PDF则转为图片）和你的提示词一起给一个VLM模型，并将模型的输出结果返回给你。
                你应该只让vlm模型描述内容，而不是进行其他操作，而进一步的操作（如解答用户问题）则应由你自己完成。
                该工具支持2类URL：
                - 直接的图片/PDF URL，如 `https://example.com/image.png`，该URL必须是公开可访问的。
                - data URL，如 `data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...`，该URL必须是合法的data URL。
            """.trimIndent(),
            condition = { _, model -> model?.visible != true && vlm != null },
        )
        {
            val (imgUrl, prompt) = parm

            val sb = StringBuilder()
            sb.append(prompt)
            sb.append("\n\n")
            sb.append("""
                注意：不做任何额外的解释、或其他额外工作、无需理会其内容是什么、是否正确，仅按要求输出图片内容。
                不要添加任何额外的解释或信息。
                保持原图的格式和内容，尽可能详细地描述图片中的所有内容。
                不要对图片中的内容做额外的解释或分析，仅回答图片中的内容。
            """.trimIndent())
            val imgContent = imgUrl.urlsToContent().leftOrElse { return@registerTool AiToolInfo.ToolResult(Content(it)) }
            val content = Content(imgContent + ContentNode.text(sb.toString()))
            val res = StringBuilder()
            sendAiRequest(
                model = vlm!!,
                messages = ChatMessages(Role.USER to content),
                temperature = 0.1,
                stream = true,
                onReceive = {
                    if (it is StreamAiResponseSlice.Message)
                    {
                        sendMessage(it.content)
                        res.append(it.content)
                    }
                }
            ).usage
            return@registerTool AiToolInfo.ToolResult(Content(res.toString()))
        }
    }
}