package moe.tachyon.windwhisper.forum

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.mainConfig
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

val logger = WindWhisperLogger.getLogger()

@Serializable
data class Topic(
    val title: String,
    val id: Int,
    val category: Category?,
    val highestPostNumber: Int,
    val archetype: String,
)

private val bufferMutex = Mutex()
private val topicBuffer = LinkedHashMap<Int, Pair<String?, Instant>>()
private const val BUFFER_TIME_MINUTES = 10L

suspend fun LoginData.getTopicTitle(topic: Int): String?
{
    val now = Clock.System.now()
    bufferMutex.withLock()
    {
        val it = topicBuffer.iterator()
        while (it.hasNext())
        {
            val entry = it.next()
            if (now - entry.value.second > BUFFER_TIME_MINUTES.minutes)
                it.remove()
            else
                break
        }
        val cached = topicBuffer[topic]
        if (cached != null)
            return cached.first
    }
    return getTopic(topic)?.title
}
suspend fun LoginData.getTopic(topic: Int): Topic? = logger.warning("Failed to get topic $topic")
{
    val url = "${mainConfig.url}/t/${topic}.json?track_visit=true&forceLoad=true"
    val res = get(url)
    if (!res.status.isSuccess())
    {
        if (res.status.value == 404)
        {
            bufferMutex.withLock()
            {
                topicBuffer[topic] = Pair(null, Clock.System.now())
            }
            return null
        }
        error("Failed to get topic $topic: ${res.status}\n${res.bodyAsText()}")
    }
    val body = res.body<JsonObject>()
    val title = body["title"]!!.jsonPrimitive.content
    val categoryId = body["category_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
    val category = categoryId?.let { getCategory(it) }
    val highestPostNumber = body["highest_post_number"]!!.jsonPrimitive.int
    val archetype = body["archetype"]!!.jsonPrimitive.content
    val result = Topic(title, topic, category, highestPostNumber, archetype)
    bufferMutex.withLock()
    {
        topicBuffer[topic] = Pair(result.title, Clock.System.now())
    }
    return result
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
    val reactions: Map<String, Int>,
)

suspend fun LoginData.getPosts(topic: Int, posts: List<Int>): List<PostData> = logger.warning("Failed to get posts $posts in topic $topic")
{
    val url = "${mainConfig.url}/t/$topic/posts.json?${posts.joinToString("&") { "post_ids%5B%5D=$it" }}&include_suggested=false"
    val res = get(url)
    if (res.status.value == 404) return emptyList()
    check(res.status.isSuccess()) { "Failed to get posts in topic $topic: HTTP ${res.status.value}\n${res.bodyAsText()}" }
    val body = res.body<JsonObject>()["post_stream"]?.jsonObject ?: error("Forum response is missing post_stream")
    val tmp = body["posts"]?.jsonArray?.associate { it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject }
        ?: error("Forum post_stream is missing posts")
    tmp.mapNotNull { (id, json) ->
        val topicId = json["topic_id"]!!.jsonPrimitive.int
        val username = json["username"]!!.jsonPrimitive.content
        val cooked = json["cooked"]!!.jsonPrimitive.content
        val replyTo = json["reply_to_post_number"]?.jsonPrimitive?.intOrNull ?: 0
        val postNumber = json["post_number"]?.jsonPrimitive?.intOrNull!!
        val myReaction = (json["current_user_reaction"] as? JsonObject)?.let { it["id"]?.jsonPrimitive?.content ?: "like" }
        val reactions = (json["reactions"] as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull()
        {
            val reactionId = it["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val count = it["count"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            reactionId to count
        }?.toMap() ?: emptyMap()
        PostData(id, topicId, username, cooked, replyTo, postNumber, myReaction, reactions)
    }
}.getOrThrow()

suspend fun LoginData.getPosts(topic: Int, postNumber: Int): List<PostData> = logger.warning("Failed to get posts from number $postNumber in topic $topic")
{
    val url = "${mainConfig.url}/t/$topic/${postNumber}.json?include_suggested=false"
    val res = get(url)
    if (res.status.value == 404) return emptyList()
    check(res.status.isSuccess()) { "Failed to get posts in topic $topic: HTTP ${res.status.value}\n${res.bodyAsText()}" }
    val body = res.body<JsonObject>()["post_stream"]?.jsonObject ?: error("Forum response is missing post_stream")
    val tmp = body["posts"]?.jsonArray?.associate { it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject }
        ?: error("Forum post_stream is missing posts")
    tmp.mapNotNull { (id, json) ->
        val topicId = json["topic_id"]!!.jsonPrimitive.int
        val username = json["username"]!!.jsonPrimitive.content
        val cooked = json["cooked"]!!.jsonPrimitive.content
        val replyTo = json["reply_to_post_number"]?.jsonPrimitive?.intOrNull ?: 0
        val postNumber = json["post_number"]?.jsonPrimitive?.intOrNull!!
        val myReaction = (json["current_user_reaction"] as? JsonObject)?.let { it["id"]?.jsonPrimitive?.content ?: "like" }
        val reactions = (json["reactions"] as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull()
        {
            val reactionId = it["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val count = it["count"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            reactionId to count
        }?.toMap() ?: emptyMap()
        PostData(id, topicId, username, cooked, replyTo, postNumber, myReaction, reactions)
    }
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
