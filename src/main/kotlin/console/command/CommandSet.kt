package moe.tachyon.windwhisper.console.command

import moe.tachyon.windwhisper.logger.WindWhisperLogger

/**
 * Command set.
 */
object CommandSet: TreeCommand(
    Config,
    Stop,
    Help,
    About,
    Clear,
    Logger,
    Color,
    Run,
    Code,
    Send,
    Context,
), CommandHandler
{
    private val logger = WindWhisperLogger.getLogger()

    override suspend fun handleCommandInvoke(sender: CommandSender, line: ParsedLine): Boolean
    {
        val words = line.words()
        if (words.isEmpty() || (words.size == 1 && words.first().isEmpty())) return true
        val command = getCommand(words[0])
        if (command != null && command.log) logger.info("${sender.name} is executing command: ${line.rawLine}")
        if (command == null)
            sender.err("Unknown command: ${words[0]}, use \"help\" to get help")
        else if (!command.execute(sender, words.subList(1, words.size)))
            sender.err("Command is illegal, use \"help ${words[0]}\" to get help")
        else return true
        return false
    }

    override suspend fun handleTabComplete(sender: CommandSender, line: ParsedLine) = tabComplete(line.words())
}
