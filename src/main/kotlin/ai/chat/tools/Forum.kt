package moe.tachyon.windwhisper.ai.chat.tools

import kotlinx.serialization.Serializable
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getPosts
import moe.tachyon.windwhisper.forum.getTopic
import moe.tachyon.windwhisper.forum.sendPosts
import moe.tachyon.windwhisper.forum.toggleLike
import moe.tachyon.windwhisper.mainConfig
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
        @JsonSchema.Description("帖子楼层(post_number)")
        val postNumber: Int,
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
        @JsonSchema.Description("目标帖子所属的话题ID")
        val topicId: Int,
        @JsonSchema.Description("目标帖子ID")
        val postId: Int,
        @JsonSchema.Description("要进行的具体点赞操作")
        val action: String,
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
                    sb.append("话题的类别信息:\n")
                    if (topic.category != null)
                    {
                        sb.append(" ID: ${topic.category.id}\n")
                        sb.append(" 名称: ${topic.category.name}\n")
                        sb.append(" 颜色: ${topic.category.color}\n")
                        sb.append(" 文字颜色: ${topic.category.textColor}\n")
                    }
                    else
                    {
                        sb.append(" 无类别信息（可能是已删除的类别）\n")
                    }
                    sb.append("最高楼层（highest_post_number）: ${topic.highestPostNumber}\n")
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
            "指定楼层（post_number）获得其前、后各10楼的帖子信息，这个工具非常适合当你需要通过楼层翻阅帖子内容时使用",
        )
        {
            runCatching()
            {
                val posts = user.getPosts(parm.topicId, parm.postNumber)
                val sb = StringBuilder()
                posts.forEach { post ->
                    sb.append("帖子ID: ${post.id}\n")
                    sb.append("楼层（post_number）: ${post.postNumber}\n")
                    sb.append("作者: ${post.username}\n")
                    sb.append("回复至楼层: ${post.replyTo}\n")
                    sb.append("我的点赞状态: ${post.myReaction}\n")
                    sb.append("<!--post-content-start-->\n")
                    sb.append(post.cooked)
                    sb.append("\n<!--post-content-end-->\n")
                    sb.append("\n---\n")
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
            "点赞指定帖子，注意如果“我的点赞状态”不是null，说明已经点赞过了，不要重复点赞！\n" +
            "所有的action总共只能点一次，只要点了任意一个action都算点过赞了\n" +
            "以下是可用的action和说明：\n" +
            mainConfig.likes.toList().joinToString("\n") { "`${it.first}`: ${it.second   }" },
        )
        {
            if (parm.action !in mainConfig.likes.keys)
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("无效的action: ${parm.action}。可用的action有：\n" +
                        mainConfig.likes.toList().joinToString("\n") { "`${it.first}`: ${it.second}" }
                    ),
                )
            }
            runCatching()
            {
                user.getPosts(parm.topicId, listOf(parm.postId)).firstOrNull()?.let()
                { post ->
                    if (post.myReaction != null)
                        return@registerTool AiToolInfo.ToolResult(
                            content = Content("帖子ID ${parm.postId} 已经被你点赞过了，当前点赞状态为 `${post.myReaction}`，请不要重复点赞！"),
                        )
                } ?: return@registerTool AiToolInfo.ToolResult(
                    content = Content("未能在话题ID ${parm.topicId} 中找到帖子ID ${parm.postId}，无法进行点赞操作。"),
                )

                val success = user.toggleLike(parm.postId, parm.action)
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