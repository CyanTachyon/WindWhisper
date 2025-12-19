package moe.tachyon.windwhisper.console.command

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object Code: Command
{
    private const val PACKAGE = "moe.tachyon.windwhisper.console.command.code.tmp"
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        if (args.isEmpty()) return false
        val id = Uuid.random().toHexString()
        val name = "WindTempCodeI$id"
        val code = "package $PACKAGE;public class $name implements java.lang.Runnable{public void run(){${args.joinToString(";")};}}"
        val dir = Files.createTempDirectory("temp-code-$id").toFile()
        try
        {
            val file = File(dir, "${PACKAGE.replace('.', '/')}/$name.java")
            file.parentFile.mkdirs()
            file.createNewFile()
            file.writeText(code)
            val compiler = ToolProvider.getSystemJavaCompiler()
            val fileMgr = compiler.getStandardFileManager(null, null, null)
            val compilationUnits = fileMgr.getJavaFileObjects(file)
            val options = listOf("-d", dir.absolutePath)
            val task = compiler.getTask(null, fileMgr, null, options, null, compilationUnits)
            val success = task.call()
            if (success)
            {
                val classLoader = URLClassLoader.newInstance(arrayOf(dir.toURI().toURL()))
                try
                {
                    val className = "$PACKAGE.$name"
                    val clazz = classLoader.loadClass(className)
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    if (instance is Runnable)
                    {
                        instance.run()
                        sender.out("Code executed successfully")
                    }
                    else
                    {
                        sender.out("Class $className does not implement Runnable")
                    }
                }
                catch (e: Throwable)
                {
                    sender.out("Error executing code:")
                    e.stackTraceToString().split("\n").forEach()
                    { line ->
                        sender.out(line)
                    }
                }
                finally
                {
                    classLoader.close()
                }
            }
            else
            {
                sender.out("Compilation failed")
            }
            return true
        }
        finally
        {
            dir.deleteRecursively()
        }
    }
}