package moe.tachyon.windwhisper.utils

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import moe.tachyon.windwhisper.config.ConfigLoader
import moe.tachyon.windwhisper.config.NetworkConfig
import moe.tachyon.windwhisper.config.Socks5Proxy
import moe.tachyon.windwhisper.initializeTestEnvironment
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SocksProxyTest
{
    private var previousAuthenticator: Authenticator? = null

    @BeforeTest
    fun setUp()
    {
        initializeTestEnvironment()
        previousAuthenticator = Authenticator.getDefault()
        Authenticator.setDefault(null)
    }

    @AfterTest
    fun tearDown() = Authenticator.setDefault(previousAuthenticator)

    private fun client(proxy: Socks5Proxy? = null, tls: ProxyTestTls? = null) =
        HttpClient(NetworkClientEngineFactory { NetworkConfig(proxy) }) {
            install(SSE)
            engine {
                config {
                    connectTimeout(2, TimeUnit.SECONDS)
                    if (tls != null) sslSocketFactory(tls.clientContext.socketFactory, tls.trustManager)
                }
            }
        }

    @Test
    fun `HTTP and SSE use SOCKS5 with proxy-side hostname resolution`() = runBlocking {
        LocalProxyHttpTarget().use { target ->
            LocalSocksProxy().use { proxy ->
                client(Socks5Proxy("127.0.0.1", proxy.port)).use { http ->
                    withTimeout(10_000) {
                        assertEquals("proxy response", http.get("http://fixture.invalid:${target.port}/body").bodyAsText())
                        val events = mutableListOf<String>()
                        http.sse("http://fixture.invalid:${target.port}/stream") {
                            incoming.take(2).collect { event -> event.data?.let(events::add) }
                        }
                        assertEquals(listOf("first", "second"), events)
                    }
                }
                proxy.verify()
                assertEquals(listOf("/body", "/stream"), target.requests.map { it.path })
                assertTrue(proxy.destinations.isNotEmpty())
                assertTrue(proxy.destinations.all { it.host == "fixture.invalid" && it.port == target.port && it.addressType == 3 })
                assertTrue(proxy.authentications.isEmpty())
            }
        }
    }

    @Test
    fun `HTTPS keeps TLS verification while tunneling through SOCKS5`() = runBlocking {
        val tls = ProxyTestTls()
        LocalProxyHttpTarget(tls).use { target ->
            LocalSocksProxy().use { proxy ->
                client(Socks5Proxy("127.0.0.1", proxy.port), tls).use { http ->
                    withTimeout(10_000) {
                        assertEquals("proxy response", http.get("https://fixture.invalid:${target.port}/body").bodyAsText())
                        // The trusted fixture certificate has only fixture.invalid in its SAN.
                        assertFailsWith<IOException> { http.get("https://wrong.invalid:${target.port}/body").bodyAsText() }
                    }
                }
                proxy.verify()
                assertEquals(1, target.requests.size)
                assertTrue(proxy.destinations.any { it.host == "fixture.invalid" && it.port == target.port })
                assertTrue(proxy.destinations.any { it.host == "wrong.invalid" && it.port == target.port })
            }
        }
    }

    @Test
    fun `SOCKS credentials authenticate the proxy without entering HTTP headers`() = runBlocking {
        LocalProxyHttpTarget().use { target ->
            LocalSocksProxy("proxy-user", "proxy-secret").use { proxy ->
                client(Socks5Proxy("127.0.0.1", proxy.port, "proxy-user", "proxy-secret")).use { http ->
                    withTimeout(10_000) {
                        assertEquals("proxy response", http.get("http://fixture.invalid:${target.port}/body") {
                            header("Authorization", "Bearer application-token")
                        }.bodyAsText())
                    }
                }
                proxy.verify()
                assertEquals(listOf("proxy-user" to "proxy-secret"), proxy.authentications.toList())
                val headers = target.requests.single().headers
                assertEquals(listOf("Bearer application-token"), headers["authorization"])
                assertNull(headers["proxy-authorization"])
                assertTrue(headers.values.flatten().none { "proxy-user" in it || "proxy-secret" in it })

                val address = InetAddress.getByName("127.0.0.1")
                assertNull(Authenticator.requestPasswordAuthentication("127.0.0.1", address, proxy.port,
                    "http", "origin", "basic"))
                assertNull(Authenticator.requestPasswordAuthentication("127.0.0.1", address, proxy.port + 1,
                    "SOCKS5", "proxy", null))
                assertNull(Authenticator.requestPasswordAuthentication("unrelated.invalid", InetAddress.getByName("127.0.0.2"), proxy.port,
                    "SOCKS5", "proxy", null))
            }
        }
    }

    @Test
    fun `proxy refusal and bad credentials never fall back to a direct connection`() = runBlocking {
        LocalProxyHttpTarget().use { target ->
            val fixtures = listOf(
                { LocalSocksProxy(rejectConnections = true) },
                { LocalSocksProxy("proxy-user", "expected-password") },
            )
            fixtures.forEach { factory ->
                factory().use { proxy ->
                    val settings = if (proxy.requiresAuthentication)
                        Socks5Proxy("127.0.0.1", proxy.port, "proxy-user", "wrong-password")
                    else Socks5Proxy("127.0.0.1", proxy.port)
                    client(settings).use { http ->
                        withTimeout(10_000) {
                            assertFailsWith<IOException> { http.get("http://127.0.0.1:${target.port}/body").bodyAsText() }
                        }
                    }
                    proxy.verify()
                }
            }
            assertTrue(target.requests.isEmpty())
        }
    }

    @Test
    fun `disabled proxy connects directly`() = runBlocking {
        LocalProxyHttpTarget().use { target ->
            client().use { http ->
                withTimeout(10_000) {
                    assertEquals("proxy response", http.get("http://127.0.0.1:${target.port}/body").bodyAsText())
                }
            }
            assertEquals(listOf("/body"), target.requests.map { it.path })
        }
    }

    @Test
    fun `network YAML defaults to direct and decodes optional credentials`()
    {
        val yaml = ConfigLoader.configSerializer
        assertNull(yaml.decodeFromString<NetworkConfig>("{}").proxy)
        assertNull(yaml.decodeFromString<NetworkConfig>("proxy: null").proxy)
        assertEquals(Socks5Proxy("localhost", 1080), yaml.decodeFromString<NetworkConfig>("""
            proxy:
              host: localhost
        """.trimIndent()).proxy)
        val config = yaml.decodeFromString<NetworkConfig>("""
            proxy:
              host: localhost
              port: 7890
              username: proxy-user
              password: proxy-secret
        """.trimIndent())
        assertEquals(Socks5Proxy("localhost", 7890, "proxy-user", "proxy-secret"), config.proxy)
        assertFalse("proxy-secret" in config.toString())
        assertFalse("proxy-user" in config.toString())
    }

    @Test
    fun `invalid SOCKS endpoint and credential configurations are rejected`()
    {
        for (host in listOf("", " ", "socks5://localhost", "localhost/path", "host name"))
            assertFailsWith<IllegalArgumentException>(host) { Socks5Proxy(host) }
        for (port in listOf(0, -1, 65_536))
            assertFailsWith<IllegalArgumentException>("port=$port") { Socks5Proxy("localhost", port) }
        assertFailsWith<IllegalArgumentException> { Socks5Proxy("localhost", username = "user") }
        assertFailsWith<IllegalArgumentException> { Socks5Proxy("localhost", password = "secret") }
        assertFailsWith<IllegalArgumentException> { Socks5Proxy("localhost", username = "u".repeat(256), password = "secret") }
        assertFailsWith<IllegalArgumentException> { Socks5Proxy("localhost", username = "user", password = "p".repeat(256)) }
        assertFailsWith<IllegalArgumentException> { Socks5Proxy("localhost", username = "用户名", password = "secret") }
    }
}
