package moe.tachyon.windwhisper.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO

/** A private, persistent store addressed only by generated image IDs, never by model-supplied paths. */
class GeneratedImageStore(private val directory: File)
{
    data class Image(val bytes: ByteArray, val filename: String, val contentType: String)

    @Synchronized
    fun save(id: String, base64: String, format: String)
    {
        val hash = hashId(id)
        val extension = normalizeFormat(format)
        require(base64.length <= ((MAX_BYTES + 2) / 3) * 4) { "生成图片超过 20 MiB 限制" }
        val bytes = try { Base64.getDecoder().decode(base64) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("生成图片不是有效的 Base64") }
        validateImage(bytes, extension)
        Files.createDirectories(directory.toPath())
        val existing = imageFiles(hash)
        if (existing.isNotEmpty())
        {
            require(existing.size == 1 && existing.single().extension == extension && read(id).bytes.contentEquals(bytes)) {
                "生成图片 ID 已存在且内容不同"
            }
            return
        }
        val temporary = Files.createTempFile(directory.toPath(), ".generated-image-", ".tmp")
        try
        {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.move(temporary, File(directory, "$hash.$extension").toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        finally { Files.deleteIfExists(temporary) }
    }

    @Synchronized
    fun contains(id: String): Boolean = imageFiles(hashId(id)).isNotEmpty()

    @Synchronized
    fun read(id: String): Image
    {
        val hash = hashId(id)
        val files = imageFiles(hash)
        require(files.size == 1) { "未找到生成图片 ID" }
        val file = files.single()
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "生成图片存储文件无效" }
        require(file.length() in 1..MAX_BYTES.toLong()) { "生成图片文件大小无效" }
        val bytes = file.readBytes()
        validateImage(bytes, file.extension)
        return Image(bytes, "generated-$hash.${file.extension}", "image/${file.extension}")
    }

    private fun imageFiles(hash: String): List<File> = FORMATS.map { File(directory, "$hash.$it") }
        .filter { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    private fun hashId(id: String): String
    {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "生成图片 ID 无效" }
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun normalizeFormat(format: String): String
    {
        val normalized = if (format.lowercase() == "jpg") "jpeg" else format.lowercase()
        require(normalized in FORMATS) { "生成图片仅支持 png、jpeg 或 webp" }
        return normalized
    }

    private fun validateImage(bytes: ByteArray, format: String)
    {
        require(bytes.size in 1..MAX_BYTES) { "生成图片文件大小无效" }
        val signatureMatches = when (format)
        {
            "png" -> bytes.size >= 33 && bytes.take(8).map { it.toInt() and 0xff } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
            "jpeg" -> bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte() && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
            "webp" -> validWebp(bytes)
            else -> false
        }
        require(signatureMatches) { "生成图片内容与声明格式不符，或文件已损坏" }
        if (format != "webp")
        {
            val valid = runCatching {
                ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) return@use false
                    val reader = readers.next()
                    try
                    {
                        reader.input = input
                        val width = reader.getWidth(0)
                        val height = reader.getHeight(0)
                        width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS && reader.read(0) != null
                    }
                    finally { reader.dispose() }
                }
            }.getOrDefault(false)
            require(valid) { "生成图片无法解码或尺寸超过限制" }
        }
    }

    // The JDK does not ship a WebP decoder. Validate its RIFF container and static frame headers.
    private fun validWebp(bytes: ByteArray): Boolean
    {
        fun ascii(offset: Int, size: Int) = String(bytes, offset, size, Charsets.US_ASCII)
        fun uint(offset: Int, count: Int): Long = (0 until count).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }
        if (bytes.size < 30 || ascii(0, 4) != "RIFF" || ascii(8, 4) != "WEBP" || uint(4, 4) != bytes.size - 8L) return false
        var offset = 12
        var hasFrame = false
        while (offset + 8 <= bytes.size)
        {
            val type = ascii(offset, 4)
            val length = uint(offset + 4, 4)
            val end = offset + 8L + length
            if (end > bytes.size) return false
            val payload = offset + 8
            if (type == "VP8 ")
            {
                if (length < 11 || (bytes[payload].toInt() and 1) != 0 ||
                    uint(payload + 3, 3) != 0x2a019dL) return false
                val width = uint(payload + 6, 2) and 0x3fff
                val height = uint(payload + 8, 2) and 0x3fff
                if (width == 0L || height == 0L || width * height > MAX_PIXELS) return false
                hasFrame = true
            }
            else if (type == "VP8L")
            {
                if (length < 6 || bytes[payload] != 0x2f.toByte()) return false
                val dimensions = uint(payload + 1, 4)
                val width = (dimensions and 0x3fff) + 1
                val height = ((dimensions shr 14) and 0x3fff) + 1
                if ((dimensions shr 29) != 0L || width * height > MAX_PIXELS) return false
                hasFrame = true
            }
            offset = (end + length % 2).toInt()
        }
        return offset == bytes.size && hasFrame
    }

    companion object
    {
        private const val MAX_BYTES = 20 * 1024 * 1024
        private const val MAX_PIXELS = 40_000_000L
        private val FORMATS = listOf("png", "jpeg", "webp")
    }
}
