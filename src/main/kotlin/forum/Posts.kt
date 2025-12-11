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
    val category: Category?,
    val highestPostNumber: Int,
)

suspend fun LoginData.getTopic(topic: Int): Topic? = logger.warning("Failed to get topic $topic")
{
    val url = "${mainConfig.url}/t/${topic}.json?forceLoad=true"
    val res = get(url)
    if (!res.status.isSuccess())
    {
        if (res.status.value == 404) return null
        error("Failed to get topic $topic: ${res.status}\n${res.bodyAsText()}")
    }
    val body = res.body<JsonObject>()
    val title = body["title"]!!.jsonPrimitive.content
    val category = getCategory(body["category_id"]!!.jsonPrimitive.int)
    val highestPostNumber = body["highest_post_number"]!!.jsonPrimitive.int
    return Topic(title, topic, category, highestPostNumber)
}.getOrThrow()

@Serializable
data class Category(
    val id: Int,
    val name: String,
    val color: String,
    val textColor: String,
)

suspend fun LoginData.getCategory(category: Int): Category? = logger.warning("Failed to get category $category")
{
    val url = "${mainConfig.url}/c/${category}/show.json"
    val res = get(url)
    if (!res.status.isSuccess())
    {
        if (res.status.value == 404) return null
        error("Failed to get category $category: ${res.status}\n${res.bodyAsText()}")
    }
    val body = res.body<JsonObject>()
    val categoryObj = body["category"]!!.jsonObject
    val name = categoryObj["name"]!!.jsonPrimitive.content
    val color = categoryObj["color"]!!.jsonPrimitive.content
    val textColor = categoryObj["text_color"]!!.jsonPrimitive.content
    return Category(category, name, color, textColor)
}.getOrThrow()

@Serializable
data class PostData(
    val id: Int,
    val topicId: Int,
    val username: String,
    val cooked: String,
    val replyTo: Int,
    val postNumber: Int,
    val myReaction: String?,
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
        val myReaction = (json["current_user_reaction"] as? JsonObject)?.let { it["id"]?.jsonPrimitive?.content ?: "like" }
        PostData(id, topicId, username, cooked, replyTo, postNumber, myReaction)
    } ?: emptyList()
}.getOrThrow()

suspend fun LoginData.getPosts(topic: Int, postNumber: Int): List<PostData> = logger.warning("Failed to get posts from number $postNumber in topic $topic")
{
    val url = "${mainConfig.url}/t/$topic/${postNumber}.json?include_suggested=false"
    val res = get(url)
    val body = res.body<JsonObject>()["post_stream"]?.jsonObject ?: return emptyList()
    val tmp = body["posts"]?.jsonArray?.associate { it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject }
    tmp?.mapNotNull { (id, json) ->
        val topicId = json["topic_id"]!!.jsonPrimitive.int
        val username = json["username"]!!.jsonPrimitive.content
        val cooked = json["cooked"]!!.jsonPrimitive.content
        val replyTo = json["reply_to_post_number"]?.jsonPrimitive?.intOrNull ?: 0
        val postNumber = json["post_number"]?.jsonPrimitive?.intOrNull!!
        val myReaction = (json["current_user_reaction"] as? JsonObject)?.let { it["id"]?.jsonPrimitive?.content ?: "like" }
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

suspend fun LoginData.toggleLike(post: Int, action: String): Boolean = logger.warning("Failed to toggle like for post $post")
{
    val url = "${mainConfig.url}/discourse-reactions/posts/${post}/custom-reactions/${action}/toggle.json"
    val res = put(url)
    if (!res.status.isSuccess())
        error("Failed to toggle like for post $post: ${res.status}\n${res.bodyAsText()}")
    return res.status.isSuccess()
}.getOrThrow()