package moe.tachyon.windwhisper.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Stores generated mono PCM as WAV, addressed by opaque IDs rather than supplied paths. */
class GeneratedAudioStore(private val directory: File)
{
    data class Audio(val bytes: ByteArray, val filename: String, val contentType: String)

    @Synchronized
    fun save(id: String, pcm: ByteArray, sampleRate: Int = 24_000)
    {
        val hash = hashId(id)
        require(pcm.isNotEmpty() && pcm.size <= MAX_BYTES - HEADER_BYTES && pcm.size % 2 == 0) {
            "生成语音必须是完整的 PCM16 音频，且 WAV 文件不得超过 20 MiB"
        }
        require(sampleRate in SAMPLE_RATES) { "生成语音采样率无效" }
        val bytes = ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2) // Block alignment
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }.array()
        Files.createDirectories(directory.toPath())
        val file = File(directory, "$hash.wav")
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        {
            require(read(id).bytes.contentEquals(bytes)) { "生成语音 ID 已存在且内容不同" }
            return
        }
        val temporary = Files.createTempFile(directory.toPath(), ".generated-audio-", ".tmp")
        try
        {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        finally { Files.deleteIfExists(temporary) }
    }

    @Synchronized
    fun contains(id: String): Boolean = Files.exists(file(id).toPath(), LinkOption.NOFOLLOW_LINKS)

    @Synchronized
    fun read(id: String): Audio
    {
        val file = file(id)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "未找到生成语音 ID 或存储文件无效" }
        require(file.length() in (HEADER_BYTES + 2).toLong()..MAX_BYTES.toLong()) { "生成语音文件大小无效" }
        val bytes = file.readBytes()
        validateWav(bytes)
        return Audio(bytes, "generated-audio-${file.name}", "audio/wav")
    }

    private fun file(id: String): File = File(directory, "${hashId(id)}.wav")

    private fun hashId(id: String): String
    {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "生成语音 ID 无效" }
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object
    {
        private const val HEADER_BYTES = 44
        private const val MAX_BYTES = 20 * 1024 * 1024
        private val SAMPLE_RATES = setOf(8_000, 16_000, 24_000, 48_000)

        /** Accept only the complete PCM WAV layout written by this store. */
        internal fun validateWav(bytes: ByteArray)
        {
            require(bytes.size in (HEADER_BYTES + 2)..MAX_BYTES && (bytes.size - HEADER_BYTES) % 2 == 0) {
                "生成语音 WAV 文件大小无效"
            }
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun ascii(offset: Int, size: Int) = String(bytes, offset, size, Charsets.US_ASCII)
            val sampleRate = data.getInt(24)
            require(ascii(0, 4) == "RIFF" && data.getInt(4) == bytes.size - 8 &&
                ascii(8, 8) == "WAVEfmt " && data.getInt(16) == 16 &&
                data.getShort(20).toInt() == 1 && data.getShort(22).toInt() == 1 &&
                sampleRate in SAMPLE_RATES && data.getInt(28) == sampleRate * 2 &&
                data.getShort(32).toInt() == 2 && data.getShort(34).toInt() == 16 &&
                ascii(36, 4) == "data" && data.getInt(40) == bytes.size - HEADER_BYTES) {
                "生成语音不是完整的单声道 PCM16 WAV 文件"
            }
        }
    }
}
