package moe.tachyon.windwhisper.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import kotlin.test.*

class GeneratedAudioStoreTest
{
    private val directories = mutableListOf<File>()

    @AfterTest
    fun tearDown() { directories.forEach { it.deleteRecursively() } }

    private fun directory(): File = Files.createTempDirectory("windwhisper-generated-audio-").toFile().also(directories::add)

    @Test
    fun `saved audio is decodable mono PCM WAV and survives reopening by opaque id`()
    {
        val directory = directory()
        val store = GeneratedAudioStore(directory)
        val pcm = byteArrayOf(0, 0, 1, 2, -1, 127, 0, -128)
        assertFalse(store.contains("speech_example"))
        store.save("speech_example", pcm)
        store.save("speech_example", pcm)
        assertEquals(1, directory.listFiles()!!.size)
        val restored = GeneratedAudioStore(directory).read("speech_example")
        assertTrue(restored.filename.matches(Regex("generated-audio-[0-9a-f]{64}\\.wav")))
        assertEquals("audio/wav", restored.contentType)
        assertTrue(store.contains("speech_example"))
        AudioSystem.getAudioInputStream(restored.bytes.inputStream()).use { audio ->
            assertEquals(24_000f, audio.format.sampleRate)
            assertEquals(1, audio.format.channels)
            assertEquals(16, audio.format.sampleSizeInBits)
            assertFalse(audio.format.isBigEndian)
            assertEquals(4L, audio.frameLength)
            assertContentEquals(pcm, audio.readBytes())
        }
        assertFailsWith<IllegalArgumentException> { store.save("speech_example", byteArrayOf(0, 1)) }
        assertContentEquals(restored.bytes, store.read("speech_example").bytes)
    }

    @Test
    fun `save rejects unsafe ids incomplete samples excessive data and unsupported sample rates`()
    {
        val directory = directory()
        val store = GeneratedAudioStore(directory)
        val pcm = byteArrayOf(1, 2)
        listOf("", "../outside", "/tmp/outside", "speech:id").forEach { id ->
            assertFailsWith<IllegalArgumentException> { store.save(id, pcm) }
            assertFailsWith<IllegalArgumentException> { store.contains(id) }
            assertFailsWith<IllegalArgumentException> { store.read(id) }
        }
        assertFailsWith<IllegalArgumentException> { store.save("empty", byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { store.save("odd", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { store.save("large", ByteArray(20 * 1024 * 1024)) }
        assertFailsWith<IllegalArgumentException> { store.save("rate", pcm, 0) }
        assertFailsWith<IllegalArgumentException> { store.save("rate", pcm, Int.MAX_VALUE) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun `read rejects corrupt lengths formats truncated content and symlinks`()
    {
        val directory = directory()
        val store = GeneratedAudioStore(directory)
        store.save("speech_example", byteArrayOf(1, 2, 3, 4))
        val file = directory.listFiles()!!.single()
        val valid = file.readBytes()
        val corruptions = listOf(
            valid.copyOf(44),
            valid.copyOf().also { it[0] = 0 },
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 123) },
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(22, 2) },
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 2) },
        )
        corruptions.forEach { bytes ->
            file.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> { store.read("speech_example") }
        }
        file.delete()
        val external = File(directory(), "external.wav").apply { writeBytes(valid) }
        Files.createSymbolicLink(file.toPath(), external.toPath())
        assertFailsWith<IllegalArgumentException> { store.read("speech_example") }
        assertFailsWith<IllegalArgumentException> { store.save("speech_example", byteArrayOf(1, 2, 3, 4)) }
        assertContentEquals(valid, external.readBytes())
    }
}
