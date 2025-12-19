package moe.tachyon.windwhisper.ai.tools

import kotlinx.serialization.Serializable
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getPosts
import moe.tachyon.windwhisper.forum.getTopic
import moe.tachyon.windwhisper.forum.sendPosts
import moe.tachyon.windwhisper.forum.toggleLike
import moe.tachyon.windwhisper.mainConfig
import moe.tachyon.windwhisper.utils.JsonSchema

class Forum(
    private val user: LoginData,
    private val blackList: Set<Int>,
): AiToolSet.ToolProvider<Any?>
{
    override val name: String = "Forum"

    @Serializable
    data class GetTopicParams(
        @JsonSchema.Description("话题ID")
        val topicIds: List<Int>,
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
        val actions: List<Action>,
    )
    {
        @Serializable
        data class Action(
            @JsonSchema.Description("目标帖子ID")
            val postId: Int,
            @JsonSchema.Description("要进行的具体点赞操作")
            val action: String,
        )
    }

    override suspend fun <S> AiToolSet<S>.registerTools()
    {
        registerTool<GetTopicParams>(
            "get_topic",
            null,
            "获得一个话题的相关信息",
        )
        {
            val sb = StringBuilder()
            for (topicId in parm.topicIds) runCatching()
            {
                val topic = user.getTopic(topicId)

                if (topic == null)
                    sb.append("- 话题ID $topicId 不存在。")
                else
                {
                    sb.append("- 话题标题: ${topic.title}\n")
                    sb.append("话题ID: ${topic.id}\n")
                    sb.append("话题类型: ")
                    when (topic.archetype)
                    {
                        "regular" -> sb.append("普通话题\n")
                        "private_message" -> sb.append("该话题是对方与你的私信\n")
                        else -> sb.append("未知类型(${topic.archetype})\n")
                    }
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
                    if (topic.id in blackList)
                        sb.append("注意：你被禁止在该话题中发言和点赞等操作。但你仍然可以阅读内容。\n")
                }
            }.getOrElse()
            {
                sb.append("- 获取话题${topicId}信息时出错: ${it.stackTraceToString()}")
            }
            AiToolInfo.ToolResult(
                content = Content(sb.toString())
            )
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
                    sb.append("点赞统计:\n")
                    if (post.reactions.isEmpty()) sb.append(" 无任何点赞\n")
                    else post.reactions.forEach { (reaction, count) ->
                        if (count > 0 && reaction in mainConfig.reactions.keys)
                            sb.append("  `${reaction}`: ${count}\n")
                    }
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
            if (parm.topicId in blackList)
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("你被禁止访问话题ID ${parm.topicId}，无法发送帖子。"),
                )
            }
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
            mainConfig.reactions.toList().joinToString("\n") { "`${it.first}`: ${it.second   }" },
        )
        {
            if (parm.topicId in blackList)
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("你被禁止访问话题ID ${parm.topicId}，无法进行点赞操作。"),
                )

            parm.actions.map { it.action }.filter { it !in mainConfig.reactions.keys }.takeUnless(List<String>::isEmpty)?.let()
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content(
                        "无效的action: ${it.joinToString()}。可用的action有：\n" +
                        mainConfig.reactions.toList().joinToString("\n") { "`${it.first}`: ${it.second}" }
                    ),
                )
            }

            val sb = StringBuilder()

            val posts = user.getPosts(parm.topicId, parm.actions.map { it.postId })
            val alreadyLiked = posts.filter { it.myReaction != null }.map { it.id }

            for (action in parm.actions) runCatching()
            {
                if (action.postId in alreadyLiked)
                {
                    sb.append("- 帖子ID ${action.postId} 已经被你点赞过了，不能重复点赞。\n")
                    return@runCatching
                }

                if (posts.none { it.id == action.postId })
                {
                    sb.append("- 帖子ID ${action.postId} 不存在，无法进行点赞操作。\n")
                    return@runCatching
                }

                val success = user.toggleLike(action.postId, action.action)
                if (success)
                    sb.append("- 点赞帖子ID ${action.postId} 成功。\n")
                else
                    sb.append("- 点赞帖子ID ${action.postId} 失败。\n")
            }.getOrElse()
            {
                sb.append("- 点赞帖子ID ${action.postId} 时出错: ${it.stackTraceToString()}\n")
            }

            AiToolInfo.ToolResult(
                content = Content(sb.toString())
            )
        }
    }
}