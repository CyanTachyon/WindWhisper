package moe.tachyon.windwhisper.utils

import io.ktor.server.application.*
import io.ktor.utils.io.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.tachyon.windwhisper.console.AnsiStyle.Companion.RESET
import moe.tachyon.windwhisper.console.SimpleAnsiColor.Companion.CYAN
import moe.tachyon.windwhisper.console.SimpleAnsiColor.Companion.PURPLE
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.mainJob
import moe.tachyon.windwhisper.server
import java.io.File
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.system.exitProcess

@Suppress("unused")
@OptIn(ExperimentalAtomicApi::class)
object Power
{
    @JvmField
    val logger = WindWhisperLogger.getLogger()
    private var isShutdown = AtomicBoolean(false)
    private val alreadyShutdown = AtomicBoolean(false)

    @JvmStatic
    fun shutdown(code: Int, cause: String = "unknown"): Nothing
    {
        if (!isShutdown.compareAndSet(expectedValue = false, newValue = true))
        {
            while (!alreadyShutdown.load())
                Thread.sleep(1)
            exitProcess(code)
        }
        logger.warning("${PURPLE}Server is shutting down: ${CYAN}$cause${RESET}")
        val mainJob = mainJob
        if (mainJob != null)
        {
            logger.info("Waiting for MainJob to complete...")
            logger.warning("Failed to stop MainJob")
            {
                runBlocking { mainJob.cancelAndJoin() }
            }.onSuccess()
            {
                logger.info("MainJob is stopped.")
            }
        }
        val server = server
        if (server != null) logger.warning("Failed to stop Ktor: ")
        {
            server.monitor.raise(ApplicationStopPreparing, server.environment)
            server.engine.stop()
            @OptIn(InternalAPI::class)
            runBlocking { server.disposeAndJoin() }
            logger.info("Ktor is stopped.")
        }
        startShutdownHook(code)
        alreadyShutdown.store(true)
        exitProcess(code)
    }

    @JvmStatic
    private fun startShutdownHook(code: Int)
    {
        val hook = Thread()
        {
            try
            {
                Thread.sleep(3000)
                logger.severe("检测到程序未退出，尝试强制终止")
                Runtime.getRuntime().halt(code)
            }
            catch (e: InterruptedException)
            {
                Thread.currentThread().interrupt()
            }
        }
        hook.isDaemon = true
        hook.start()
    }

    @JvmStatic
    fun init() = runCatching()
    {
        val javaVersion = System.getProperty("java.specification.version")
        if (javaVersion != "17")
        {
            logger.severe("Java version is $javaVersion, but WindWhisper requires Java 17.")
            shutdown(1, "Java version is $javaVersion, but WindWhisper requires Java 17.")
        }

        val file = File(this.javaClass.protectionDomain.codeSource.location.toURI())
        val lst = file.lastModified()
        val thread = Thread()
        {
            try
            {
                while (true)
                {
                    Thread.sleep(1000)
                    val newLst = file.lastModified()
                    if (newLst != lst)
                    {
                        logger.warning("检测到文件 ${file.name} 已被修改，程序将自动关闭")
                        shutdown(0, "File ${file.name} modified")
                    }
                }
            }
            catch (e: InterruptedException)
            {
                Thread.currentThread().interrupt()
            }
        }
        thread.isDaemon = true
        thread.start()
    }.onFailure()
    {
        logger.severe("启动监视器失败", it)
        shutdown(1, "Failed to start monitoring")
    }
}