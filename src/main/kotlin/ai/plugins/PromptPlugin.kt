package moe.tachyon.windwhisper.ai.plugins

import moe.tachyon.windwhisper.ai.ChatMessage
import moe.tachyon.windwhisper.ai.ChatMessages
import moe.tachyon.windwhisper.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.Role
import moe.tachyon.windwhisper.ai.internal.llm.BeforeLlmRequest
import moe.tachyon.windwhisper.ai.internal.llm.LlmLoopPlugin
import moe.tachyon.windwhisper.ai.internal.llm.PluginScope

class PromptPlugin(private val prompt: suspend PromptScope.() -> Unit): BeforeLlmRequest
{
    inner class PromptScope
    {
        private val messages = mutableListOf<ChatMessage>()
        fun addMessage(message: ChatMessage) = messages.add(message)
        fun addMessages(vararg msgs: ChatMessage) = messages.addAll(msgs)
        fun addMessages(msgs: List<ChatMessage>) = messages.addAll(msgs)
        fun addMessage(role: Role, content: String) = messages.add(ChatMessage(role, content))
        fun addUserMessage(content: String) = messages.add(ChatMessage(Role.USER, content))
        fun addAssistantMessage(content: String) = messages.add(ChatMessage(Role.ASSISTANT, content))
        fun addSystemMessage(content: String) = messages.add(ChatMessage(Role.SYSTEM, content))
        fun addUserMessage(content: Content) = messages.add(ChatMessage(Role.USER, content))
        fun addAssistantMessage(content: Content) = messages.add(ChatMessage(Role.ASSISTANT, content))
        fun addSystemMessage(content: Content) = messages.add(ChatMessage(Role.SYSTEM, content))

        internal suspend fun result(): ChatMessages
        {
            prompt()
            return messages.toChatMessages()
        }
    }

    context(_: LlmLoopPlugin.Context, _: BeforeLlmRequest.BeforeRequestContext)
    override suspend fun PluginScope.beforeRequest()
    {
        requestMessage = PromptScope().result() + requestMessage
    }
}