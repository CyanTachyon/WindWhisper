package moe.tachyon.windwhisper

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import moe.tachyon.windwhisper.config.ConfigLoader
import moe.tachyon.windwhisper.config.loggerConfig
import moe.tachyon.windwhisper.console.Console
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getTopicTitle
import moe.tachyon.windwhisper.forum.login
import moe.tachyon.windwhisper.logger.ToConsoleHandler
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.utils.Power
import java.io.File
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.properties.Delegates

lateinit var version: String
    private set
lateinit var workDir: File
    private set
lateinit var dataDir: File
    private set
lateinit var mainConfig: MainConfig
var debug by Delegates.notNull<Boolean>()
    private set
var server: Application? = null
    private set
var mainJob: Job? = null
    private set
val loginUser: LoginData by lazy()
{
    runBlocking { login() }
}

@Serializable
data class BlacklistItem(val id: Int, val title: String)

/**
 * 解析命令行, 返回的是处理后的命令行, 和从命令行中读取的配置文件(默认是config.yaml, 可通过-config=xxxx.yaml更改)
 */
private fun parseCommandLineArgs(args: Array<String>): Pair<Array<String>, File>
{
    val argsMap = args.mapNotNull()
    {
        when (val idx = it.indexOf("="))
        {
            -1   -> null
            else -> Pair(it.take(idx), it.drop(idx + 1))
        }
    }.toMap()

    // 从命令行中加载信息

    // 工作目录
    workDir = File(argsMap["-workDir"] ?: ".")
    workDir.mkdirs()

    // 配置文件目录
    dataDir = argsMap["-dataDir"]?.let { File(it) } ?: File(workDir, "data")
    dataDir.mkdirs()

    // 是否开启debug模式
    debug = argsMap["-debug"].toBoolean()
    System.setProperty("io.ktor.development", "$debug")

    // 去除命令行中的-config参数, 因为ktor会解析此参数进而不加载打包的application.yaml
    // 其余参数还原为字符串数组
    val resArgs = argsMap.entries
        .filterNot { it.key == "-config" || it.key == "-workDir" || it.key == "-debug" || it.key == "-dataDir" }
        .map { (k, v) -> "$k=$v" }
        .toTypedArray()
    // 命令行中输入的自定义配置文件
    val configFile = argsMap["-config"]?.let { File(it) } ?: File(workDir, "config.json")

    return resArgs to configFile
}

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking()
{
    val (_, configFile) = runCatching { parseCommandLineArgs(args) }.getOrElse { return@runBlocking }
    ConfigLoader.init()
    Power.init()
    Console.startConsoleCommandHandler()
    if (!configFile.exists())
    {
        val default = MainConfig(
            url = "https://xxx.com",
            username = "your_username",
            password = "your_password",
            retry = 3,
            defaultHeaders = mapOf(),
            reactions = mapOf("heart" to "爱心，同时也是默认的点赞行为"),
        )
        configFile.writeText(showJson.encodeToString(MainConfig.serializer(), default))
        println("配置文件不存在, 已生成默认配置文件于 ${configFile.absolutePath}, 请修改后重新运行")
        return@runBlocking
    }
    val promptFile = File(workDir, "prompt.txt")
    if (!promptFile.exists())
    {
        Loader.getResource("/prompt.default.md")?.use()
        { input ->
            promptFile.outputStream().use()
            { output ->
                input.copyTo(output)
            }
        }
    }
    version = Yaml.default.parseToYamlNode(Loader.getResource("application.yaml")!!.readBytes().decodeToString())
        .let { it as YamlMap }
        .get<YamlScalar>("version")!!
        .content
    mainConfig = dataJson.decodeFromString(configFile.readText())
    WindWhisperLogger.getLogger().info("Config loaded from ${configFile.absolutePath}")
    mainJob = launch()
    {
        workMain(loginUser, promptFile.readText())
    }
    var server: EmbeddedServer<*, *>? = null
    if (mainConfig.webServer.enabled)
    {
        server = EngineMain.createServer(
            arrayOf(
                "-port=${mainConfig.webServer.port}",
                "-host=${mainConfig.webServer.host}",
                "-P:ktor.deployment.rootPath=${mainConfig.webServer.rootPath}",
            )
        )
    }
    server?.start(wait = false)
    mainJob!!.join()
}.also()
{
    Power.shutdown(0, "Main function exited")
}

@Suppress("unused")
fun Application.init()
{
    server = this
    WindWhisperLogger.getLogger().info("Starting web server...")
    install(AutoHeadResponse)
    install(ContentNegotiation)
    {
        json(contentNegotiationJson)
    }
    install(SSE)

    val loggerFlow = MutableSharedFlow<String>(
        replay = 1024,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sharedFlow = loggerFlow.asSharedFlow()

    WindWhisperLogger.globalLogger.logger.addHandler(object: Handler()
    {
        init
        {
            this.formatter = ToConsoleHandler.formatter
        }

        override fun publish(record: LogRecord)
        {
            if (!loggerConfig.check(record)) return
            if (record.loggerName.startsWith("io.ktor.websocket")) return
            loggerFlow.tryEmit(formatter.format(record))
        }

        override fun flush() = Unit
        override fun close() = Unit
    })

    routing()
    {
        get("/")
        {
            val fileContent = Loader.getResource("index.html")?.readBytes() ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondBytes(
                fileContent.decodeToString().replace($$"$rootPath", mainConfig.webServer.rootPath.removeSuffix("/")).encodeToByteArray(),
                ContentType.Text.Html
            )
        }

        staticResources("/js", "js")

        route("/logs/sse", HttpMethod.Get)
        {
            plugin(SSE)
            handle()
            {
                call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(HttpHeaders.Connection, "keep-alive")
                call.response.header("X-Accel-Buffering", "no")
                val sse = SSEServerContent(call)
                {
                    heartbeat()
                    sharedFlow.collect()
                    {
                        send(
                            data = it,
                            event = "log_message",
                            id = System.currentTimeMillis().toString(),
                        )
                        delay(3L)
                    }
                }
                return@handle call.respond(HttpStatusCode.OK, sse)
            }
        }

        route("/blacklist")
        {
            get()
            {
                val items = blackList.map()
                { id ->
                    val topic = runCatching { loginUser.getTopicTitle(id) }.getOrNull()
                    BlacklistItem(id, topic ?: "未知标题")
                }
                call.respond(items)
            }

            post()
            {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                if (id == null)
                {
                    call.respond(HttpStatusCode.BadRequest, "Missing or invalid id")
                    return@post
                }
                blackList = blackList + id
                call.respond(HttpStatusCode.OK)
            }

            delete("/{id}")
            {
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null)
                {
                    call.respond(HttpStatusCode.BadRequest, "Missing or invalid id")
                    return@delete
                }
                blackList = blackList - id
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}