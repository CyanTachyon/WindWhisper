package moe.tachyon.windwhisper

import moe.tachyon.windwhisper.logger.WindWhisperLogger
import java.nio.file.Files
import java.util.logging.Logger

/** Initialize only the globals needed by serializers and the HTTP client, without starting the app. */
internal fun initializeTestEnvironment()
{
    TestEnvironment.initialized
}

private object TestEnvironment
{
    val initialized: Unit = run {
        val directory = Files.createTempDirectory("windwhisper-api-tests-").toFile()
        val parse = Class.forName("moe.tachyon.windwhisper.MainKt")
            .getDeclaredMethod("parseCommandLineArgs", Array<String>::class.java)
        parse.isAccessible = true
        parse.invoke(null, arrayOf("-workDir=${directory.absolutePath}", "-debug=false") as Any)

        // The application's logger installs an interactive console and replaces stdout/stderr.
        // Initialize it once, then keep Gradle's original streams/handlers for headless tests.
        val stdout = System.out
        val stderr = System.err
        val root = Logger.getLogger("")
        val handlers = root.handlers.toList()
        val useParentHandlers = root.useParentHandlers
        try
        {
            WindWhisperLogger.globalLogger
        }
        finally
        {
            System.setOut(stdout)
            System.setErr(stderr)
            root.handlers.forEach(root::removeHandler)
            handlers.forEach(root::addHandler)
            root.useParentHandlers = useParentHandlers
        }
    }
}
