package moe.tachyon.windwhisper.console.command

import kotlinx.coroutines.CancellationException
import moe.tachyon.windwhisper.ConversationRuntime
import moe.tachyon.windwhisper.ai.internal.llm.countResponsesInputTokens
import moe.tachyon.windwhisper.ai.tools.conversationTools
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.currentSessionScope

object Context: Command
{
    override val description = "Show current Responses context usage and automatic compaction threshold."

    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        if (args.isNotEmpty()) return false
        val runtime = ConversationRuntime.current
        if (runtime == null)
        {
            sender.err("会话尚未启动或正在停止。")
            return true
        }
        val model = aiConfig.model
        if (currentSessionScope(model) != runtime.startupScope)
        {
            sender.err("论坛身份或 Responses 地址/密钥已改变，请恢复匹配配置并重启后查询。")
            return true
        }
        val snapshot = runtime.session.state
        val pending = snapshot.turn
        sender.out("模型：${model.model}")
        sender.out("待处理轮次：${pending?.id ?: "无"}；排队消息：${runtime.queuedMessages}")
        sender.out("会话续接响应：${snapshot.previousResponseId ?: "尚未创建"}")
        pending?.responseId?.let { sender.out("当前轮次响应：$it") }
        sender.out("自动压缩阈值：${model.compactThreshold} tokens（不是模型最大窗口）")
        if (pending != null) sender.out("正在处理任务；以下计数针对已保存的请求输入，不包含仍在生成的输出。")
        try
        {
            val tools = conversationTools(runtime.user, runtime.images, model)
            val tokens = countResponsesInputTokens(
                model = model,
                input = pending?.input ?: snapshot.deferredInput,
                instructions = runtime.instructions,
                previousResponseId = snapshot.previousResponseId,
                tools = tools,
            )
            sender.out("当前输入上下文：$tokens tokens（API 实际计数，包含提示词、工具定义及待发送内容）")
            val remaining = model.compactThreshold.toLong() - tokens
            sender.out(if (remaining >= 0) "距自动压缩阈值：$remaining tokens"
                else "已超过自动压缩阈值 ${-remaining} tokens；压缩由服务端在生成响应时处理。")
            if (runtime.session.state !== snapshot)
                sender.out("查询期间会话状态已更新；以上是查询开始时的快照。")
        }
        catch (error: CancellationException) { throw error }
        catch (error: Exception)
        {
            sender.err("上下文计数失败：${error.message ?: error::class.simpleName}")
        }
        return true
    }
}
