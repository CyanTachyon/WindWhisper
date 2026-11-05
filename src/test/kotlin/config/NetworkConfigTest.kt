package moe.tachyon.windwhisper.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NetworkConfigTest
{
    private val yaml = ConfigLoader.configSerializer

    @Test
    fun `forum write timeout defaults to two minutes for existing configurations`()
    {
        val config = yaml.decodeFromString<NetworkConfig>("{}")
        assertNull(config.proxy)
        assertEquals(120_000L, config.forumWriteTimeoutMillis)
    }

    @Test
    fun `forum write timeout round trips with proxy settings`()
    {
        val config = yaml.decodeFromString<NetworkConfig>("""
            proxy:
              host: localhost
              port: 7890
            forumWriteTimeoutMillis: 45000
        """.trimIndent())
        assertEquals(45_000L, config.forumWriteTimeoutMillis)
        assertEquals(Socks5Proxy("localhost", 7890), config.proxy)
        assertEquals(config, yaml.decodeFromString<NetworkConfig>(yaml.encodeToString(config)))
    }

    @Test
    fun `forum write timeout must fit a positive OkHttp millisecond duration`()
    {
        for (timeout in listOf(Long.MIN_VALUE, -1L, 0L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE))
            assertFailsWith<IllegalArgumentException>("timeout=$timeout") {
                NetworkConfig(forumWriteTimeoutMillis = timeout)
            }
        assertEquals(1L, NetworkConfig(forumWriteTimeoutMillis = 1).forumWriteTimeoutMillis)
        assertEquals(Int.MAX_VALUE.toLong(), NetworkConfig(forumWriteTimeoutMillis = Int.MAX_VALUE.toLong()).forumWriteTimeoutMillis)
    }
}
