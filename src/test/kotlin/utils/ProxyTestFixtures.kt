package moe.tachyon.windwhisper.utils

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A loopback SOCKS5 server; destination hostnames are resolved by this fixture, never by the client. */
internal class LocalSocksProxy(
    private val username: String? = null,
    private val password: String? = null,
    private val rejectConnections: Boolean = false,
): AutoCloseable
{
    data class Destination(val host: String, val port: Int, val addressType: Int)

    val destinations = CopyOnWriteArrayList<Destination>()
    val authentications = CopyOnWriteArrayList<Pair<String, String>>()
    val requiresAuthentication: Boolean get() = username != null
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "socks5-test-proxy").apply { isDaemon = true }
    }
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int get() = server.localPort

    init
    {
        workers.execute {
            while (!server.isClosed)
            {
                try
                {
                    val connection = server.accept()
                    sockets += connection
                    workers.execute { handle(connection) }
                }
                catch (error: IOException)
                {
                    if (!server.isClosed) failures += error
                    break
                }
            }
        }
    }

    private fun handle(client: Socket)
    {
        var upstream: Socket? = null
        var relaying = false
        try
        {
            client.soTimeout = 5_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            assertEquals(5, input.readUnsignedByte(), "SOCKS protocol version")
            val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val method = if (requiresAuthentication) 2 else 0
            assertTrue(method.toByte() in methods, "Client must offer the required authentication method")
            output.write(byteArrayOf(5, method.toByte()))
            output.flush()
            if (requiresAuthentication)
            {
                assertEquals(1, input.readUnsignedByte(), "SOCKS username/password authentication version")
                fun readCredential(): String = ByteArray(input.readUnsignedByte()).also(input::readFully)
                    .toString(Charsets.ISO_8859_1)
                val credentials = readCredential() to readCredential()
                authentications += credentials
                val accepted = credentials == (username to password)
                output.write(byteArrayOf(1, if (accepted) 0 else 1))
                output.flush()
                if (!accepted) return
            }
            assertEquals(5, input.readUnsignedByte())
            assertEquals(1, input.readUnsignedByte(), "Only SOCKS CONNECT is expected")
            assertEquals(0, input.readUnsignedByte())
            val addressType = input.readUnsignedByte()
            val host = when (addressType)
            {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                else -> error("Unexpected SOCKS address type: $addressType")
            }
            val destinationPort = input.readUnsignedShort()
            destinations += Destination(host, destinationPort, addressType)
            if (rejectConnections)
            {
                output.write(byteArrayOf(5, 5, 0, 1, 127, 0, 0, 1, 0, 0))
                output.flush()
                return
            }
            check(host in setOf("fixture.invalid", "wrong.invalid", "127.0.0.1", "localhost")) {
                "Fixture cannot forward to external host: $host"
            }
            val target = Socket().apply {
                sockets += this
                connect(InetSocketAddress("127.0.0.1", destinationPort), 2_000)
                soTimeout = 5_000
            }
            upstream = target
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            output.flush()
            relaying = true
            workers.execute {
                try
                {
                    client.getInputStream().copyTo(target.getOutputStream())
                    runCatching { target.shutdownOutput() }
                }
                catch (_: IOException) { /* Closing either side ends this forwarding direction. */ }
            }
            target.getInputStream().copyTo(output)
        }
        catch (error: Throwable)
        {
            // TLS rejection and client cancellation close the tunnel without an HTTP request.
            if (!relaying && !server.isClosed) failures += error
        }
        finally
        {
            runCatching { client.close() }
            sockets -= client
            upstream?.let {
                runCatching { it.close() }
                sockets -= it
            }
        }
    }

    fun verify()
    {
        failures.firstOrNull()?.let { throw AssertionError("SOCKS5 fixture failed", it) }
    }

    override fun close()
    {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "SOCKS fixture workers must terminate")
    }
}

internal class LocalProxyHttpTarget(tls: ProxyTestTls? = null): AutoCloseable
{
    data class Request(val path: String, val headers: Map<String, List<String>>)
    val requests = CopyOnWriteArrayList<Request>()
    private val server = if (tls == null) HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    else HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(tls.serverContext)
    }
    val port: Int get() = server.address.port

    init
    {
        server.createContext("/") { exchange ->
            try
            {
                requests += Request(exchange.requestURI.path, exchange.requestHeaders.entries
                    .associate { it.key.lowercase() to it.value.toList() })
                val streaming = exchange.requestURI.path == "/stream"
                val body = if (streaming) "event: tick\ndata: first\n\nevent: tick\ndata: second\n\n" else "proxy response"
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", if (streaming) "text/event-stream" else "text/plain")
                exchange.responseHeaders.set("Connection", "close")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            finally
            {
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

/** Fixed, public test-only certificate and key, valid 2020–2040 for fixture.invalid only. */
internal class ProxyTestTls
{
    val trustManager: X509TrustManager
    val serverContext: SSLContext
    val clientContext: SSLContext

    init
    {
        fun decode(pem: String) = Base64.getMimeDecoder().decode(pem.lineSequence()
            .filterNot { it.startsWith("-----") }.joinToString("\n"))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(decode(CERTIFICATE).inputStream())
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decode(PRIVATE_KEY)))
        val password = "test-fixture".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("fixture", key, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("fixture", certificate) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        trustManager = trustManagers.trustManagers.filterIsInstance<X509TrustManager>().single()
        serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    companion object
    {
        private val CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIC9DCCAdygAwIBAgIJAIJynEVHPfB0MA0GCSqGSIb3DQEBCwUAMBoxGDAWBgNV
            BAMTD2ZpeHR1cmUuaW52YWxpZDAeFw0yMDAxMDEwNzQxNDdaFw00MDAxMDEwNzQx
            NDdaMBoxGDAWBgNVBAMTD2ZpeHR1cmUuaW52YWxpZDCCASIwDQYJKoZIhvcNAQEB
            BQADggEPADCCAQoCggEBAKofItxdWxRIzDbLRYV1eZsUFCN0uORdMpdtW0fhNQ8q
            VTJ6+w83ggGcTLCMEH2u/+OLfMrtXJ21tuXQHcSMDsuQoKMmmH5XydZCm3SU5tnq
            PlG3YNwjoidt8vzauURfAngs0IQpLJgRcqNULmnw+xO1/jwxh6KmKNbTawLt0TYO
            7hgyWsJqt9A/A3YuxLbZHswsQps7+1W2j/6UxKcZHVGCN1fG9FpjNoj5McOzBhl2
            1ApYZryxx1k97vdk8w850fpvObACh0nIPFTynVbMXrpt7AGGIAuTw0maDO+sTFlM
            10f639LAoHoO4BBX7syqo5SkPcxjkRD+OI9vj7kzlU8CAwEAAaM9MDswHQYDVR0O
            BBYEFJkIfKXTnHh7rJlYIAMKFeyDY60GMBoGA1UdEQQTMBGCD2ZpeHR1cmUuaW52
            YWxpZDANBgkqhkiG9w0BAQsFAAOCAQEAZBE1dAIKp7x21nn8dFzz3EdmN9mpuZHI
            aep97h8KX67cCstZ3zQGvKlqJ9ciZ1bKy6QzmYNd2pk4DSOXX2igOr+a7y5Eu3pd
            QIwIMOQSAlFirNwEpJ/Jui8ctNSkSnkyrczrS2y+J+kZO0f9+dsNZ+hlpcU8pWuv
            2kT1OX3C0rUjxGRQ9kgd/uhnLgdLcE2hzCFqml+EQdTBsUxK7z01C5cKJ6jUw93f
            DypyuFYFQJiymJQsqH4+u26fODhAoQwwGbFZ4Y9jLYUSxnXffI8hsFOAd2Cz5bY/
            HF4Uzvbq1RTr2wgbq2V/P6+6eD5wuLlB32B64OPt+7t5Z5kmmsdXyg==
            -----END CERTIFICATE-----
        """.trimIndent()
        private val PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCqHyLcXVsUSMw2
            y0WFdXmbFBQjdLjkXTKXbVtH4TUPKlUyevsPN4IBnEywjBB9rv/ji3zK7Vydtbbl
            0B3EjA7LkKCjJph+V8nWQpt0lObZ6j5Rt2DcI6InbfL82rlEXwJ4LNCEKSyYEXKj
            VC5p8PsTtf48MYeipijW02sC7dE2Du4YMlrCarfQPwN2LsS22R7MLEKbO/tVto/+
            lMSnGR1RgjdXxvRaYzaI+THDswYZdtQKWGa8scdZPe73ZPMPOdH6bzmwAodJyDxU
            8p1WzF66bewBhiALk8NJmgzvrExZTNdH+t/SwKB6DuAQV+7MqqOUpD3MY5EQ/jiP
            b4+5M5VPAgMBAAECggEAECEKl5T8TSSHxAwqp7gXEvZo4GGxI+mYIdY66Mo30xvC
            3xnc0rkDCO8OMFDfQWNFnIe9ianOdZ9kW06Le/aBcphgOoeuvo/JMZi4D2oMpw13
            4hx+QPDZ714Twxhjp1FQtR8el+ElerdwxH53NhEkZN6E36UchHW3eoKp6ymsA7Fo
            GyvK8yBivuvk2366WGEzdKj+RzdQk8FdKg1qbInUja/vzU3Az0Chfk0214L5r+lj
            x9lBWH7B5UrUQ1Jp5Ky/Z+8UANp6P7XsEqMrOitKeI+e/K8yt7a1moXOkN7OzOfu
            cVV2losMoI+o95nAqpPEgkcVqamZ6MS7pnWFoDD26QKBgQDTjdVSM2fpMy9uoPeb
            blIY9vB99r4cGd4eb6sDJS+Rn2drbkiQDUolCv47yhMlH5IJVcmucqNNn66u2ay5
            63LRtcU1EExXwx3g4gKdtoSDRohioOMQf9HcZSRQfVZj/KLYYjz5JBToBXKBR6dQ
            cbS1HIeRdqnoGEWlHEN6+u61NwKBgQDN3Om6geU1r06WEs6POtOKAp44M55ojXIa
            xcn9vqGq3P6yuoXS52rSINu+1uEcnmJNMstxCwk00JTZ+TqtExsb2DRE8Ntn+9U8
            QfXqmZByavkQUUg3qIoADnJvuUnJo1rQeVjMSwV9WUMrdTVbH3g+2YbpUnrolzbB
            li+EjYusqQKBgGV7bk9mVXxqj2BUWzL2cu+C1c63JG86gXC8RJ7XocALZYMFfk2L
            8ntekKKBxzlRsa5EtuC2IVxFM3iw9sihB41N0vInhVe8Q4LzKCobuel3VklpwELT
            7gn5grgRx/Vi5YNDbNhe1KCQrGMTylEumm8e5nGDKPIrYo1Xx48g/I11AoGASAok
            qKXIJi9A2ved3TWCzWr5H0XBC2lkIsZQV0GcpYRMhRMMva9KDFp/S5s6j59FWyqJ
            Uve/swHC9TxoMiTbebUBJZb70Lmp9zpF6mDu0XtpyTIIiaGrGTX1Ce/XSH6nywiL
            8IHYW/MetFiB4W9zztqkgYXhiP9RpBEAc+xsknECgYEAszRPCqJN4DTl9mm3x9Au
            Pum7bW0z0rk+LM/KnhBjW2de2nAApsa7oHf3ltrIQdNybpsh+IhcRsmT9M8pQvbP
            cGKUJ9fHJBpAqoVdB6mxC2fWnL6Is+QuKIa4vJNGoFmqCkwIT87ad8i63tbngDbO
            ITRyjaG51FoqkFKSyaiFT3w=
            -----END PRIVATE KEY-----
        """.trimIndent()
    }
}
