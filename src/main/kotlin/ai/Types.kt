@file:Suppress("unused")

package moe.tachyon.windwhisper.ai

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.plugins.ContextCompressor
import moe.tachyon.windwhisper.ai.tools.AiToolInfo
import moe.tachyon.windwhisper.ai.tools.AiToolSet
import moe.tachyon.windwhisper.dataJson
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable(Role.Serializer::class)
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
sealed interface Role
{
    val role get() = when (this)
    {
        ASSISTANT       -> "assistant"
        is MARKING      -> "marking"
        is SHOWING_DATA -> "showing_data"
        SYSTEM          -> "system"
        is TOOL         -> "tool"
        USER            -> "user"
    }
    @Serializable(with = USER.Serializer::class) data object USER: Role
    {
        class Serializer : KSerializer<USER>
        {
            override val descriptor = buildSerialDescriptor("Role.User", SerialKind.ENUM)
            {
                element("user", buildSerialDescriptor("user", StructureKind.OBJECT))
            }
            override fun serialize(encoder: Encoder, value: USER) = encoder.encodeString("user")
            override fun deserialize(decoder: Decoder): USER = USER
        }
    }
    @Serializable(with = SYSTEM.Serializer::class) data object SYSTEM: Role
    {
        class Serializer : KSerializer<SYSTEM>
        {
            override val descriptor = buildSerialDescriptor("Role.System", SerialKind.ENUM)
            {
                element("system", buildSerialDescriptor("system", StructureKind.OBJECT))
            }
            override fun serialize(encoder: Encoder, value: SYSTEM) = encoder.encodeString("system")
            override fun deserialize(decoder: Decoder): SYSTEM = SYSTEM
        }
    }
    @Serializable(with = ASSISTANT.Serializer::class) data object ASSISTANT: Role
    {
        class Serializer : KSerializer<ASSISTANT>
        {
            override val descriptor = buildSerialDescriptor("Role.Assistant", SerialKind.ENUM)
            {
                element("assistant", buildSerialDescriptor("assistant", StructureKind.OBJECT))
            }
            override fun serialize(encoder: Encoder, value: ASSISTANT) = encoder.encodeString("assistant")
            override fun deserialize(decoder: Decoder): ASSISTANT = ASSISTANT
        }
    }
    @Serializable data class TOOL(val id: String, val name: String): Role
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val role = "tool"
        companion object
        {
            val role get() = "tool"
        }
    }
    @Serializable data class SHOWING_DATA(val type: AiToolSet.ToolData.Type): Role
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val role = "showing_data"
        companion object
        {
            val role get() = "showing_data"
        }

    }
    @Serializable data class MARKING(val type: String, val data: JsonElement): Role
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val role = "marking"
        inline fun <reified T> get(): T = dataJson.decodeFromJsonElement(data)
        companion object
        {
            val role get() = "marking"
        }
    }

    class Serializer: KSerializer<Role>
    {
        override val descriptor: SerialDescriptor = buildSerialDescriptor("Role", PolymorphicKind.SEALED)
        {
            element<USER>("user")
            element<SYSTEM>("system")
            element<ASSISTANT>("assistant")
            element<TOOL>("tool")
            element<SHOWING_DATA>("showing_data")
            element<MARKING>("marking")
        }
        override fun serialize(encoder: Encoder, value: Role) = when(value)
        {
            is ASSISTANT    -> ASSISTANT.serializer().serialize(encoder, value)
            is MARKING      -> MARKING.serializer().serialize(encoder, value)
            is SHOWING_DATA -> SHOWING_DATA.serializer().serialize(encoder, value)
            is SYSTEM       -> SYSTEM.serializer().serialize(encoder, value)
            is TOOL   -> TOOL.serializer().serialize(encoder, value)
            is USER   -> USER.serializer().serialize(encoder, value)
        }
        override fun deserialize(decoder: Decoder): Role
        {
            if (decoder !is JsonDecoder) error("Unsupported decoder type: ${decoder::class}")
            val ele = JsonElement.serializer().deserialize(decoder)
            return when(val role = ((ele as? JsonObject)?.get("role") as? JsonPrimitive)?.content ?: (ele as? JsonPrimitive)?.content)
            {
                "user"         -> USER
                "system"       -> SYSTEM
                "assistant"    -> ASSISTANT
                "tool"         -> decoder.json.decodeFromJsonElement<TOOL>(ele)
                "showing_data" -> decoder.json.decodeFromJsonElement<SHOWING_DATA>(ele)
                "marking"      -> decoder.json.decodeFromJsonElement<MARKING>(ele)
                else           -> error("Unsupported role: $ele")
            }
        }
    }
}


@Serializable
data class TokenUsage(
    @SerialName("completion_tokens")
    val completionTokens: Long = 0,
    @SerialName("prompt_tokens")
    val promptTokens: Long = 0,
    @SerialName("total_tokens")
    val totalTokens: Long = 0,
)
{
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        completionTokens + other.completionTokens,
        promptTokens + other.promptTokens,
        totalTokens + other.totalTokens,
    )
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
sealed interface ContentNode
{
    val type: String

    companion object
    {
        @JvmStatic
        operator fun invoke(content: String): ContentNode = Text(content)
        @JvmStatic
        fun text(content: String): ContentNode = Text(content)
        @JvmStatic
        fun image(image: String): ContentNode = Image(Image.Image(image))
        @JvmStatic
        fun file(filename: String, url: String): ContentNode = File(File.File(filename, url))
    }

    @Serializable
    @SerialName("text")
    data class Text(val text: String): ContentNode
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: String = "text"
    }

    @Serializable
    @SerialName("image_url")
    data class Image(@SerialName("image_url") val image: Image): ContentNode
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: String = "image_url"
        @Serializable data class Image(val url: String)
    }

    @Serializable
    @SerialName("file")
    data class File(@SerialName("file") val file: File): ContentNode
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: String = "file"
        @Serializable data class File(@SerialName("filename") val filename: String, @SerialName("file_data") val url: String)
    }
}

@JvmInline
@Serializable
@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
value class Content(@Serializable(ContentSerializer::class) val content: List<ContentNode>): List<ContentNode> by content
{
    constructor(vararg content: ContentNode): this(content.toList())
    constructor(content: String): this(listOf(ContentNode.Text(content)))

    operator fun plus(other: Content): Content = Content(content + other.content).optimize()

    operator fun plus(other: ContentNode): Content = Content(content + other).optimize()

    override fun toString(): String = content.joinToString(separator = "")

    fun optimize(): Content
    {
        val optimizedContent = mutableListOf<ContentNode>()
        for (node in content)
        {
            if (node is ContentNode.Text && optimizedContent.lastOrNull() is ContentNode.Text)
            {
                val lastTextNode = optimizedContent.last() as ContentNode.Text
                optimizedContent[optimizedContent.size - 1] = ContentNode.Text(lastTextNode.text + node.text)
            }
            else
            {
                optimizedContent.add(node)
            }
        }
        return Content(optimizedContent)
    }

    fun toText(): String
    {
        return content.joinToString(separator = "") { (it as? ContentNode.Text)?.text ?: "" }
    }

    override fun isEmpty(): Boolean = content.isEmpty() || content.all { it is ContentNode.Text && it.text.isBlank() }

    private class ContentSerializer: KSerializer<List<ContentNode>>
    {
        private val serializer = ListSerializer(ContentNode.serializer())
        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("AiRequest.Message.Content", PolymorphicKind.SEALED)
        {
            element("simple", String.serializer().descriptor)
            element("complex", serializer.descriptor)
        }

        override fun serialize(encoder: Encoder, value: List<ContentNode>)
        {
            if (value.all { it is ContentNode.Text })
                encoder.encodeString(value.joinToString(separator = "") { (it as ContentNode.Text).text })
            else
                serializer.serialize(encoder, value)
        }

        override fun deserialize(decoder: Decoder): List<ContentNode> =
            if (decoder is JsonDecoder)
            {
                val json = decoder.json
                val ele = JsonElement.serializer().deserialize(decoder)
                if (ele is JsonPrimitive) listOf(ContentNode.Text(ele.content))
                else json.decodeFromJsonElement(serializer, ele)
            }
            else if (decoder is YamlInput)
            {
                val ele = decoder.node
                if (ele is YamlScalar) listOf(ContentNode.Text(ele.content))
                else decoder.yaml.decodeFromYamlNode(serializer, ele)
            }
            else error("Unsupported decoder type: ${decoder::class}")
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable(ChatMessage.Serializer::class)
@KeepGeneratedSerializer
data class ChatMessage(
    val role: Role,
    val content: Content,
    @SerialName("reasoning_content")
    val reasoningContent: Content = Content(),
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall> = emptyList(),
)
{
    constructor(role: Role, content: String, reasoningContent: String = ""):
            this(role, Content(content), Content(reasoningContent), emptyList())
    init
    {
        if (toolCalls.isNotEmpty() && role != Role.ASSISTANT)
        {
            error("Only ASSISTANT role messages can have tool calls")
        }
    }

    infix fun canPlus(other: ChatMessage): Boolean =
        this.role == other.role && (this.role is Role.ASSISTANT || this.role is Role.TOOL)
    operator fun plus(other: ChatMessage): ChatMessage
    {
        if (!(this canPlus other)) error("Cannot combine messages with different roles, tool call IDs, or showing types")
        return ChatMessage(
            role = role,
            content = content + other.content,
            reasoningContent = reasoningContent + other.reasoningContent,
            toolCalls = toolCalls + other.toolCalls,
        )
    }

    fun isBlank(): Boolean = content.isEmpty() && reasoningContent.isEmpty() && toolCalls.isEmpty()

    @Serializable
    data class ToolCall(
        val id: String,
        val function: Function
    )
    {
        constructor(id: String, name: String, arguments: String): this(id, Function(name, arguments))
        val type = "function"
        val name: String get() = function.name
        val arguments: String get() = function.arguments
        @Serializable
        data class Function(
            val name: String,
            val arguments: String,
        )
    }

    class Serializer: KSerializer<ChatMessage>
    {
        private val gen = generatedSerializer()
        override val descriptor: SerialDescriptor = gen.descriptor
        override fun serialize(encoder: Encoder, value: ChatMessage) = gen.serialize(encoder, value)
        @OptIn(ExperimentalUuidApi::class)
        override fun deserialize(decoder: Decoder): ChatMessage
        {
            require(decoder is JsonDecoder) { "This class can be loaded only by Json" }
            val ele = decoder.decodeJsonElement()
            if (ele !is JsonObject) error("Expected JsonObject, found ${ele::class}")
            if ("toolCallId" in ele || "showingType" in ele || (ele["role"] as? JsonPrimitive)?.content in listOf("CONTEXT_COMPRESSION", "tool"))
            {
                /// legacy format
                val role = when((ele["role"] as? JsonPrimitive)?.content)
                {
                    "CONTEXT_COMPRESSION" -> Role.MARKING(ContextCompressor.MARKING_TYPE, JsonNull)
                    "tool"                -> Role.TOOL(Uuid.random().toHexString(), "unknown")
                    else                  -> decoder.json.decodeFromJsonElement<Role>(ele["role"] ?: error("Missing role field"))
                }
                val toolCallId = ele["tool_call_id"]?.let<JsonElement, String>(decoder.json::decodeFromJsonElement) ?: ""
                val showingType = ele["showingType"]?.let<JsonElement, AiToolSet.ToolData.Type>(decoder.json::decodeFromJsonElement)

                val content = decoder.json.decodeFromJsonElement<Content>(ele["content"] ?: error("Missing content field"))
                val reasoningContent = ele["content"]?.let<JsonElement, Content>(decoder.json::decodeFromJsonElement) ?: Content()
                val toolCalls = mutableListOf<ToolCall>()
                return ChatMessage(
                    role =
                        if (toolCallId.isNotBlank()) Role.TOOL(toolCallId, "unknown")
                        else if (showingType != null) Role.SHOWING_DATA(showingType)
                        else role,
                    content = content,
                    reasoningContent = reasoningContent,
                    toolCalls = toolCalls,
                )
            }
            return decoder.json.decodeFromJsonElement(gen, ele)
        }
    }
}

@Serializable
@JvmInline
@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
value class ChatMessages(private val messages: List<ChatMessage>): List<ChatMessage> by messages
{
    override fun subList(fromIndex: Int, toIndex: Int): ChatMessages =
        ChatMessages(messages.subList(fromIndex, toIndex))
    constructor(vararg messages: ChatMessage): this(messages.toList())
    constructor(role: Role, content: Content, reasoningContent: Content = Content()):
            this(ChatMessage(role, content, reasoningContent))
    constructor(role: Role, content: Content, reasoningContent: String):
        this(ChatMessage(role, content, Content(reasoningContent)))
    constructor(role: Role, content: String, reasoningContent: String = ""):
            this(role, Content(content), reasoningContent)
    constructor(vararg messages: Pair<Role, Content>):
        this(messages.map { ChatMessage(it.first, it.second) })

    fun optimize(): ChatMessages
    {
        if (isEmpty()) return empty()
        val optimizedMessages = mutableListOf<ChatMessage>()
        var lastMessage: ChatMessage = first()
        for (message in drop(1))
        {
            if (lastMessage canPlus message)
                lastMessage += message
            else
            {
                optimizedMessages.add(lastMessage)
                lastMessage = message
            }
        }
        optimizedMessages.add(lastMessage)
        return optimizedMessages.toChatMessages()
    }

    operator fun plus(other: ChatMessage): ChatMessages =
        ChatMessages(this.messages + other).optimize()
    operator fun plus(other: Collection<ChatMessage>): ChatMessages =
        ChatMessages(this.messages + other).optimize()

    fun isBlank(): Boolean = all { it.isBlank() }

    companion object
    {
        @JvmStatic
        fun empty(): ChatMessages = ChatMessages(emptyList())
        @JvmStatic
        fun of(vararg messages: ChatMessage): ChatMessages = ChatMessages(messages.toList())
        fun Iterable<ChatMessage>.toChatMessages(): ChatMessages = this as? ChatMessages ?: ChatMessages(this.toList())
        operator fun Iterable<ChatMessage>.plus(other: ChatMessages): ChatMessages =
            if (this is ChatMessages) this + other
            else this.toChatMessages() + other
    }
}

sealed interface StreamAiResponseSlice
{
    @Serializable
    data class Message(
        val content: String,
        @SerialName("reasoning_content")
        val reasoningContent: String = "",
    ): StreamAiResponseSlice

    data class ToolCall(
        val id: String,
        val tool: AiToolInfo<*>,
        val parms: String,
    ): StreamAiResponseSlice

    @Serializable
    data class ToolMessage(
        val id: String,
        @SerialName("reasoning_content")
        val reasoningContent: Content = Content(),
    ): StreamAiResponseSlice

    @Serializable
    data class ShowingTool(
        val content: String,
        val showingType: AiToolSet.ToolData.Type,
    ): StreamAiResponseSlice
}