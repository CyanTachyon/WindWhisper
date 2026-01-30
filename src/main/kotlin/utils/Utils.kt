@file:Suppress("unused")

package moe.tachyon.windwhisper.utils

import com.charleskorn.kaml.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import javax.imageio.ImageIO
import kotlin.collections.iterator
import kotlin.coroutines.CoroutineContext
import kotlin.text.iterator

open class LineOutputStream(private val line: (String) -> Unit): OutputStream()
{
    private val arrayOutputStream = ByteArrayOutputStream()
    override fun write(b: Int)
    {
        if (b == '\n'.code)
        {
            val str: String
            synchronized(arrayOutputStream)
            {
                str = arrayOutputStream.toString()
                arrayOutputStream.reset()
            }
            runCatching { line(str) }
        }
        else
        {
            arrayOutputStream.write(b)
        }
    }
}

open class LinePrintStream(private val line: (String) -> Unit): PrintStream(LineOutputStream(line))
{
    override fun println(x: Any?) = x.toString().split('\n').forEach(line)

    override fun println() = println("" as Any?)
    override fun println(x: Boolean) = println(x as Any?)
    override fun println(x: Char) = println(x as Any?)
    override fun println(x: Int) = println(x as Any?)
    override fun println(x: Long) = println(x as Any?)
    override fun println(x: Float) = println(x as Any?)
    override fun println(x: Double) = println(x as Any?)
    override fun println(x: CharArray) = println(x.joinToString("") as Any?)
    override fun println(x: String?) = println(x as Any?)
}

fun richTextToString(richText: JsonElement): String
{
    return when (richText)
    {
        is JsonArray   -> richText.joinToString("", transform = ::richTextToString)
        is JsonPrimitive -> richText.content
        is JsonObject  ->
        {
            val text = richText["text"]
            if (text != null) return richTextToString(text)
            val content = richText["content"]
            if (content != null) return richTextToString(content)
            val children = richText["children"]
            if (children != null) return richTextToString(children)
            ""
        }
    }
}

/**
 * 判断文本内容是否不超过x个中文字符或2x个英文字符
 */
fun isWithinChineseCharLimit(text: String, limit: Int): Boolean
{
    var count = 0
    for (ch in text)
    {
        count += if (ch.code in 0..127) 1 else 2
        if (count > limit * 2) return false
    }
    return true
}

fun YamlNode.toJsonElement(): JsonElement
{
    return when (this)
    {
        is YamlMap ->
        {
            val map = mutableMapOf<String, JsonElement>()
            for ((key, value) in this.entries)
            {
                val keyStr = key.toJsonElement().jsonPrimitive.content
                map[keyStr] = value.toJsonElement()
            }
            JsonObject(map)
        }
        is YamlList -> JsonArray(this.items.map { it.toJsonElement() })
        is YamlNull -> JsonNull
        is YamlScalar ->
        {
            runCatching { return JsonPrimitive(this.toBoolean()) }
            runCatching { return JsonPrimitive(this.toLong()) }
            runCatching { return JsonPrimitive(this.toDouble()) }
            JsonPrimitive(this.content)
        }
        is YamlTaggedNode -> this.innerNode.toJsonElement()
    }
}

fun JsonElement.toYamlNode(): YamlNode =
    Yaml.default.parseToYamlNode(this.toString())

fun ByteArray.toJpegBytes(): ByteArray =
    ByteArrayOutputStream().also()
    {
        ImageIO.write(ImageIO.read(this.inputStream()).withoutAlpha(), "jpeg", it)
    }.toByteArray()

fun BufferedImage.toJpegBytes(): ByteArray =
    ByteArrayOutputStream().also()
    {
        ImageIO.write(this.withoutAlpha(), "jpeg", it)
    }.toByteArray()

fun BufferedImage.withoutAlpha(): BufferedImage
{
    val rImg = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = rImg.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, rImg.width, rImg.height)
    graphics.drawImage(this, 0, 0, null)
    graphics.dispose()
    return rImg
}

fun BufferedImage.resize(width: Int, height: Int): BufferedImage
{
    val rImg = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = rImg.createGraphics()
    graphics.drawImage(this, 0, 0, width, height, null)
    graphics.dispose()
    return rImg
}

sealed class Either<out L, out R>
{
    data class Left<out L>(val value: L): Either<L, Nothing>()
    data class Right<out R>(val value: R): Either<Nothing, R>()

    val leftOrNull get() = (this as? Left<L>)?.value
    val rightOrNull get() = (this as? Right<R>)?.value
    val right get() = if (this is Right<R>) this.value else error("Either is Left")
    val left get() = if (this is Left<L>) this.value else error("Either is Right")
    val isLeft get() = this is Left<L>
    val isRight get() = this is Right<R>

    override fun toString(): String = when (this)
    {
        is Left  -> "Left($value)"
        is Right -> "Right($value)"
    }
}

inline fun <L, R> Either<L, R>.leftOrElse(block: (R) -> L): L = leftOrNull ?: block(right)
inline fun <L, R> Either<L, R>.rightOrElse(block: (L) -> R): R = rightOrNull ?: block(left)


@Suppress("UNCHECKED_CAST")
fun <T> suspendLazy(initializer: suspend () -> T): suspend () -> T
{
    val mutex = Mutex()
    var value: Any? = mutex
    return suspend {
        if (value !== mutex) value as T
        else mutex.withLock()
        {
            if (value !== mutex) value as T
            else
            {
                value = initializer()
                value
            }
        }
    }
}

/**
 * 当context被取消，即使内部的程序捕捉取消事件并继续执行，withContext也会抛出CancellationException，
 * 该函数在这种情况下不会抛出异常，而是返回内部的结果。
 */
suspend fun <T> safeWithContext(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T
): T
{
    var res: Result<T>? = null
    val res1 = runCatching()
    {
        withContext(context)
        {
            res = runCatching { block() }
            return@withContext res
        }
    }
    if (res != null) return res.getOrThrow()
    return res1.getOrThrow().getOrThrow()
}