package moe.tachyon.windwhisper.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable

@Serializable
data class NetworkConfig(
    @YamlComment("全局SOCKS5代理，null为直连；修改后需重启，config reload不会更新现有连接")
    val proxy: Socks5Proxy? = null,
)

@Serializable
data class Socks5Proxy(
    val host: String,
    val port: Int = 1080,
    val username: String? = null,
    val password: String? = null,
)
{
    init
    {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/\\@?#" }) {
            "SOCKS5 host must be a hostname or IP address without a URL scheme, path or credentials"
        }
        require(port in 1..65535) { "SOCKS5 port must be between 1 and 65535" }
        require((username == null) == (password == null)) { "SOCKS5 username and password must be configured together" }
        for (credential in listOfNotNull(username, password))
            require(credential.length in 1..255 && credential.all { it.code in 1..255 }) {
                "SOCKS5 credentials must contain 1 to 255 ISO-8859-1 characters"
            }
    }

    // ConfigLoader logs configuration changes; never expose proxy credentials there.
    override fun toString(): String =
        "Socks5Proxy(host=$host, port=$port, authenticated=${username != null})"
}

val networkConfig: NetworkConfig by config("network.yml", NetworkConfig())
