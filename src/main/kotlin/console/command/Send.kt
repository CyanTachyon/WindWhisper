package moe.tachyon.windwhisper.console.command

import moe.tachyon.windwhisper.ConversationRuntime

object Send: Command
{
    override val description = "Send a user message to WindWhisper's current Responses conversation."
    override val args = "<message>"
    override val log = false

    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        val message = args.joinToString(" ")
        if (message.isBlank()) return false
        val runtime = ConversationRuntime.current
        if (runtime == null)
        {
            sender.err("会话尚未启动或正在停止。")
            return true
        }
        val id = runtime.enqueue(message)
        if (id == null) sender.err("消息未加入队列：队列已满或会话正在停止，请稍后发送。")
        else sender.out("消息已加入当前会话队列：$id；当前任务结束后处理，结果会显示在日志中。")
        return true
    }
}
