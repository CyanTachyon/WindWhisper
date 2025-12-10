package moe.tachyon.windwhisper.ai.chat.tools

import kotlinx.serialization.Serializable
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getPosts
import moe.tachyon.windwhisper.forum.getTopic
import moe.tachyon.windwhisper.forum.sendPosts
import moe.tachyon.windwhisper.forum.toggleLike
import moe.tachyon.windwhisper.utils.JsonSchema

class Forum(private val user: LoginData): AiToolSet.ToolProvider<Any?>
{
    override val name: String = "Forum"

    @Serializable
    data class GetTopicParams(
        @JsonSchema.Description("话题ID")
        val topicId: Int,
    )

    @Serializable
    data class GetPostParams(
        @JsonSchema.Description("话题ID")
        val topicId: Int,
        @JsonSchema.Description("帖子ID")
        val postIds: List<Int>,
    )

    @Serializable
    data class SendPostParams(
        @JsonSchema.Description("话题ID")
        val topicId: Int,
        @JsonSchema.Description("回复内容")
        val content: String,
        @JsonSchema.Description("回复至的帖子的post_number，如果是作为新帖发送则传入0")
        val replyTo: Int,
    )

    @Serializable
    data class ToggleLikeParams(
        @JsonSchema.Description("帖子ID")
        val postId: Int
    )

    override suspend fun <S> AiToolSet<S>.registerTools()
    {
        registerTool<GetTopicParams>(
            "get_topic",
            null,
            "获得一个话题的相关信息",
        )
        {
            runCatching()
            {
                val topic = user.getTopic(parm.topicId)
                val sb = StringBuilder()
                if (topic == null)
                {
                    sb.append("话题ID ${parm.topicId} 不存在。")
                }
                else
                {
                    sb.append("话题标题: ${topic.title}\n")
                    sb.append("话题ID: ${topic.id}\n")
                    sb.append("话题类别: ${topic.category}\n")
                    sb.append("该话题下的帖子的id如果你想要读取`post_number`和其他具体的信息请进一步获取。请留意某些工具需要传入`post_number`，某些则需要传入ID。\n")
                    sb.append("其中，第一个即为话题首帖（楼主开启话题时发的帖子）。\n")
                    topic.posts.forEach { postId -> sb.append(" ${postId}\n") }
                }
                AiToolInfo.ToolResult(
                    content = Content(sb.toString())
                )
            }.getOrElse()
            {
                AiToolInfo.ToolResult(
                    content = Content("获取话题信息时出错: ${it.stackTraceToString()}"),
                )
            }
        }

        registerTool<GetPostParams>(
            "get_posts",
            null,
            "获得一个或多个帖子的信息",
        )
        {
            runCatching()
            {
                val posts = user.getPosts(parm.topicId, parm.postIds)
                val sb = StringBuilder()
                if (posts.isEmpty())
                {
                    sb.append("未能在话题ID ${parm.topicId} 中找到指定的帖子ID: ${parm.postIds.joinToString(", ")}。")
                }
                else
                {
                    posts.forEach { post ->
                        sb.append("帖子ID: ${post.id}\n")
                        sb.append("post_number: ${post.postNumber}\n")
                        sb.append("作者: ${post.username}\n")
                        sb.append("内容: ${post.cooked}\n")
                        sb.append("回复至楼层: ${post.replyTo}\n")
                        sb.append("我的点赞状态: ${post.myReaction}\n")
                        sb.append("-----\n")
                    }
                }
                AiToolInfo.ToolResult(
                    content = Content(sb.toString())
                )
            }.getOrElse()
            {
                AiToolInfo.ToolResult(
                    content = Content("获取帖子信息时出错: ${it.stackTraceToString()}"),
                )
            }
        }

        registerTool<SendPostParams>(
            "send_post",
            null,
            "发送一个帖子到指定话题",
        )
        {
            runCatching()
            {
                val success = user.sendPosts(parm.topicId, parm.content, parm.replyTo)
                if (success)
                {
                    AiToolInfo.ToolResult(
                        content = Content("成功发送帖子到话题ID ${parm.topicId}。"),
                    )
                }
                else
                {
                    AiToolInfo.ToolResult(
                        content = Content("发送帖子到话题ID ${parm.topicId} 失败。"),
                    )
                }
            }.getOrElse()
            {
                AiToolInfo.ToolResult(
                    content = Content("发送帖子时出错: ${it.stackTraceToString()}"),
                )
            }
        }

        registerTool<ToggleLikeParams>(
            "toggle_like",
            null,
            "点赞指定帖子，注意如果“我的点赞状态”不是null，说明已经点赞过了，不要重复点赞",
        )
        {
            runCatching()
            {
                val success = user.toggleLike(parm.postId)
                if (success)
                {
                    AiToolInfo.ToolResult(
                        content = Content("成功点赞帖子ID ${parm.postId}。"),
                    )
                }
                else
                {
                    AiToolInfo.ToolResult(
                        content = Content("点赞帖子ID ${parm.postId} 失败。"),
                    )
                }
            }.getOrElse()
            {
                AiToolInfo.ToolResult(
                    content = Content("点赞帖子时出错: ${it.stackTraceToString()}"),
                )
            }
        }
    }
}