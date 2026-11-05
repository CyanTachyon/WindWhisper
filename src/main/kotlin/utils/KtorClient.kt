package moe.tachyon.windwhisper.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import moe.tachyon.windwhisper.config.NetworkConfig
import moe.tachyon.windwhisper.config.Socks5Proxy
import moe.tachyon.windwhisper.config.networkConfig
import moe.tachyon.windwhisper.contentNegotiationJson
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal class NetworkClientEngineFactory(private val configuration: () -> NetworkConfig): HttpClientEngineFactory<OkHttpConfig>
{
    // All clients use one startup snapshot. Reloading config must not partially change routing.
    private val networkProxy: Proxy by lazy {
        val settings = configuration().proxy ?: return@lazy Proxy.NO_PROXY
        val address = InetSocketAddress(settings.host, settings.port)
        require(!address.isUnresolved) { "Cannot resolve SOCKS5 proxy host" }
        if (settings.username != null) installProxyAuthenticator(settings, address)
        Proxy(Proxy.Type.SOCKS, address)
    }

    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine = OkHttp.create {
        config {
            connectTimeout(5_000, TimeUnit.MILLISECONDS)
            readTimeout(0, TimeUnit.MILLISECONDS)
            writeTimeout(0, TimeUnit.MILLISECONDS)
        }
        block()
        // An explicit proxy (including NO_PROXY) prevents ambient settings or failed proxies
        // from silently changing the configured route. OkHttp sends destination names to SOCKS.
        proxy = networkProxy
    }
}

private fun installProxyAuthenticator(settings: Socks5Proxy, address: InetSocketAddress)
{
    synchronized(NetworkClientEngineFactory::class.java) {
        val previous = Authenticator.getDefault()
        if (previous is SocksProxyAuthenticator && previous.settings == settings) return
        Authenticator.setDefault(SocksProxyAuthenticator(settings, address, previous))
    }
}

private class SocksProxyAuthenticator(
    val settings: Socks5Proxy,
    private val address: InetSocketAddress,
    private val previous: Authenticator?,
): Authenticator()
{
    override fun getPasswordAuthentication(): PasswordAuthentication? =
        if (requestingProtocol.equals("SOCKS5", ignoreCase = true) && requestingPort == address.port &&
            (requestingSite == address.address || requestingHost.equals(settings.host, ignoreCase = true)))
            PasswordAuthentication(settings.username!!, settings.password!!.toCharArray())
        else previous?.requestPasswordAuthenticationInstance(
            requestingHost, requestingSite, requestingPort, requestingProtocol,
            requestingPrompt, requestingScheme, requestingURL, requestorType,
        )
}

val ktorClientEngineFactory: HttpClientEngineFactory<OkHttpConfig> = NetworkClientEngineFactory { networkConfig }

val httpClient: HttpClient by lazy {
    HttpClient(ktorClientEngineFactory)
    {
        engine()
        {
            dispatcher = Dispatchers.IO
            config { callTimeout(10_000, TimeUnit.MILLISECONDS) }
        }
        install(ContentNegotiation)
        {
            json(contentNegotiationJson)
        }
    }
}

// Media downloads previously used URLConnection without a total timeout. Keep large
// files independent of the forum client's short timeout while using the same proxy.
val downloadHttpClient: HttpClient by lazy {
    HttpClient(ktorClientEngineFactory) {
        engine {
            dispatcher = Dispatchers.IO
            config { callTimeout(0, TimeUnit.MILLISECONDS) }
        }
    }
}
