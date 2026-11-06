package moe.tachyon.windwhisper.forum

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.mainConfig

@Serializable
data class ForumProfile(
    val id: Long,
    val username: String,
    val bio: String,
    val avatarTemplate: String,
    val uploadedAvatarId: Long? = null,
    val canEditAvatar: Boolean? = null,
    val canUploadAvatar: Boolean? = null,
)

private fun ownProfileUrl(): String =
    "${mainConfig.url.trimEnd('/')}/u/${mainConfig.username.encodeURLPathPart()}"

suspend fun LoginData.getOwnProfile(): ForumProfile
{
    val response = get("${ownProfileUrl()}.json").profileJson("读取个人资料")
    val user = response["user"] as? JsonObject ?: error("论坛未返回个人资料 user")
    val id = user["id"]?.jsonPrimitive?.longOrNull
    val username = user["username"]?.jsonPrimitive?.contentOrNull
    require(id != null && id > 0 && username != null && username.equals(mainConfig.username, ignoreCase = true)) {
        "论坛返回的个人资料与当前账号不一致"
    }
    // Discourse omits bio_raw when the biography is blank, including for its owner.
    return ForumProfile(
        id = id,
        username = username,
        bio = user["bio_raw"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        avatarTemplate = user["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        uploadedAvatarId = user["uploaded_avatar_id"]?.jsonPrimitive?.longOrNull,
        canEditAvatar = user["can_edit_avatar"]?.jsonPrimitive?.booleanOrNull,
        canUploadAvatar = user["can_upload_avatar"]?.jsonPrimitive?.booleanOrNull,
    )
}

suspend fun LoginData.updateOwnBio(bio: String): String
{
    val response = put("${ownProfileUrl()}.json") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("bio_raw", bio) })
    }.profileJson("修改简介", requireSuccess = true)
    val user = response["user"] as? JsonObject
    val actual = if (user != null && "bio_raw" in user)
        user["bio_raw"]?.jsonPrimitive?.contentOrNull.orEmpty()
    else getOwnProfile().bio
    // SSO can silently override bio updates even when the endpoint returns success: OK.
    check(actual == bio.ifBlank { "" }) { "论坛未应用简介修改：返回的实际简介与请求不一致" }
    return actual
}

suspend fun LoginData.updateOwnAvatar(image: GeneratedImageStore.Image): Long
{
    val profile = getOwnProfile()
    check(profile.canEditAvatar != false && profile.canUploadAvatar != false) { "论坛不允许当前账号上传或修改头像" }
    val upload = post("${mainConfig.url.trimEnd('/')}/uploads.json") {
        setBody(MultiPartFormDataContent(formData {
            append("upload_type", "avatar")
            append("synchronous", "true")
            append("file", image.bytes, Headers.build {
                append(HttpHeaders.ContentType, image.contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"${image.filename}\"")
            })
        }))
    }.profileJson("上传头像")
    val uploadId = upload["id"]?.jsonPrimitive?.longOrNull
    require(uploadId != null && uploadId > 0) { "论坛未返回有效的头像上传 ID" }
    put("${ownProfileUrl()}/preferences/avatar/pick.json") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("type", "uploaded"); put("upload_id", uploadId) })
    }.profileJson("设置头像", requireSuccess = true)
    return uploadId
}

private suspend fun HttpResponse.profileJson(action: String, requireSuccess: Boolean = false): JsonObject
{
    val body = bodyAsText()
    check(status.isSuccess()) { "$action 失败，HTTP 状态码 ${status.value}\n$body" }
    val result = Json.parseToJsonElement(body) as? JsonObject ?: error("$action 失败：论坛返回的 JSON 不是对象")
    val errors = result["errors"]
    val hasErrors = errors != null && errors != JsonNull && errors != JsonArray(emptyList())
    val failed = result["failed"]?.jsonPrimitive?.booleanOrNull == true
    val success = result["success"]?.jsonPrimitive
    check(!hasErrors && !failed && success?.booleanOrNull != false &&
        (!requireSuccess || success?.contentOrNull == "OK")) {
        "$action 失败，HTTP 状态码 ${status.value}\n$body"
    }
    return result
}
