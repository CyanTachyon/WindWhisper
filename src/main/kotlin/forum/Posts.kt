package moe.tachyon.windwhisper.forum

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.mainConfig

val logger = WindWhisperLogger.getLogger()

@Serializable
data class Topic(
    val title: String,
    val id: Int,
    val category: Int,
    val posts: List<Int>,
)

suspend fun LoginData.getTopic(topic: Int): Topic? = logger.warning("Failed to get topic $topic")
{
    val url = "${mainConfig.url}/t/${topic}.json?track_visit=true&forceLoad=true"
    val res = get(url)
    if (!res.status.isSuccess())
    {
        if (res.status.value == 404) return null
        error("Failed to get topic $topic: ${res.status}\n${res.bodyAsText()}")
    }
    val body = res.body<JsonObject>()
    val posts = body["post_stream"]?.jsonObject?.get("stream")?.jsonArray?.map { it.jsonPrimitive.int } ?: return null
    val title = body["title"]!!.jsonPrimitive.content
    val category = body["category_id"]!!.jsonPrimitive.int
    return Topic(title, topic, category, posts)
}.getOrThrow()

@Serializable
data class PostData(
    val id: Int,
    val topicId: Int,
    val username: String,
    val cooked: String,
    val replyTo: Int,
    val postNumber: Int,
    val myReaction: String,
)

suspend fun LoginData.getPosts(topic: Int, posts: List<Int>): List<PostData> = logger.warning("Failed to get posts $posts in topic $topic")
{
    val url = "${mainConfig.url}/t/$topic/posts.json?${posts.joinToString("&") { "post_ids%5B%5D=$it" }}&include_suggested=false"
    val res = get(url)
    val body = res.body<JsonObject>()["post_stream"]?.jsonObject ?: return emptyList()
    val tmp = body["posts"]?.jsonArray?.associate { it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject }
    tmp?.mapNotNull { (id, json) ->
        val topicId = json["topic_id"]!!.jsonPrimitive.int
        val username = json["username"]!!.jsonPrimitive.content
        val cooked = json["cooked"]!!.jsonPrimitive.content
        val replyTo = json["reply_to_post_number"]?.jsonPrimitive?.intOrNull ?: 0
        val postNumber = json["post_number"]?.jsonPrimitive?.intOrNull!!
        val myReaction = (json["current_user_reaction"] as? JsonObject)?.get("reaction_value")?.jsonPrimitive?.content ?: "null"
        PostData(id, topicId, username, cooked, replyTo, postNumber, myReaction)
    } ?: emptyList()
}.getOrThrow()

suspend fun LoginData.sendPosts(topic: Int, content: String, replyTo: Int): Boolean = logger.warning("Failed to send post to topic $topic")
{
    val url = "${mainConfig.url}/posts.json"
    val res = post(url)
    {
        setBody(
            buildJsonObject {
                put("raw", content)
                put("topic_id", topic)
                if (replyTo != 0)
                    put("reply_to_post_number", replyTo)
            }
        )
        contentType(ContentType.Application.Json)
    }
    if (!res.status.isSuccess())
        error("Failed to send post to topic $topic: ${res.status}\n${res.bodyAsText()}")
    return res.status.isSuccess()
}.getOrThrow()

suspend fun LoginData.toggleLike(post: Int): Boolean = logger.warning("Failed to toggle like for post $post")
{
    val url = "${mainConfig.url}/discourse-reactions/posts/${post}/custom-reactions/heart/toggle.json"
    val res = put(url)
    if (!res.status.isSuccess())
        error("Failed to toggle like for post $post: ${res.status}\n${res.bodyAsText()}")
    return res.status.isSuccess()
}.getOrThrow()