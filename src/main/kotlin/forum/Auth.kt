package moe.tachyon.windwhisper.forum

import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.tachyon.windwhisper.mainConfig
import moe.tachyon.windwhisper.utils.httpClient

@Serializable
data class LoginData(
    var cookie: String,
    var csrfToken: String,
)
{
    suspend fun get(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = sendRequest(url, block, HttpClient::get)
    suspend fun post(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = sendRequest(url, block, HttpClient::post)
    suspend fun put(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = sendRequest(url, block, HttpClient::put)

    private suspend fun sendRequest(url: String, block: HttpRequestBuilder.() -> Unit, method: suspend HttpClient.(String, HttpRequestBuilder.() -> Unit) -> HttpResponse): HttpResponse
    {
        repeat(mainConfig.retry)
        { time ->
            runCatching()
            {
                val res = httpClient.method(url)
                {
                    header("Cookie", cookie)
                    header("X-Csrf-Token", csrfToken)
                    mainConfig.defaultHeaders.forEach { (key, value) -> header(key, value) }
                    mapOf(
                        "discourse-logged-in" to "true",
                        "discourse-present" to "true",
                        "dnt" to "1",
                        "priority" to "u=1, i",
                        "sec-ch-ua" to "Edge\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"",
                        "user-agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
                        "x-requested-with" to "XMLHttpRequest",
                        "x-silence-logger" to "true"
                    ).forEach { (key, value) -> header(key, value) }
                    block()
                }
                if (res.headers.contains("Set-Cookie"))
                {
                    // 合并新旧 Cookie
                    val newCookies = res.setCookie().map { renderCookieHeader(it) }
                    val oldCookies = cookie.split("; ").associate { val (k, v) = it.split("=", limit = 2); k to v }
                    val mergedCookies = oldCookies.toMutableMap()
                    for (newCookie in newCookies)
                    {
                        val (k, v) = newCookie.split("=", limit = 2)
                        mergedCookies[k] = v
                    }
                    cookie = mergedCookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
                }
                if (time == mainConfig.retry - 1 || res.status.value !in (listOf(401, 403, 429) + (500..599)))
                    return res
                if (res.status.value in listOf(401, 403) && "BAD CSRF" in res.bodyAsText())
                {
                    val newData = login()
                    cookie = newData.cookie
                    csrfToken = newData.csrfToken
                }
                else return res
            }.onFailure()
            {
                if (time == mainConfig.retry - 1)
                    throw it
            }
        }
        error("Unreachable")
    }
}

suspend fun login(
    username: String = mainConfig.username,
    password: String = mainConfig.password,
): LoginData
{
    println("Logging in as $username...")
    var cookie = httpClient.get("${mainConfig.url}/session/passkey/challenge.json").setCookie().joinToString("; ", transform = ::renderCookieHeader)
    val csrfToken = httpClient.get("${mainConfig.url}/session/csrf")
    {
        header("Cookie", cookie)
    }.also {
        cookie = it.setCookie().joinToString("; ", transform = ::renderCookieHeader)
    }.body<JsonObject>()["csrf"]!!.jsonPrimitive.content
    val data = LoginData(cookie, csrfToken)
    val login = data.post("${mainConfig.url}/session")
    {
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("login", username)
                    append("password", password)
                    append("second_factor_method", "1")
                    append("timezone", "Asia/Shanghai")
                }
            )
        )
    }
    require(login.status.isSuccess()) { "Login failed: ${login.bodyAsText()}" }
    return data
}