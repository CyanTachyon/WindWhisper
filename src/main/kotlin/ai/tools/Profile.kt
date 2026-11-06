package moe.tachyon.windwhisper.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.tachyon.windwhisper.ai.Content
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.forum.LoginData
import moe.tachyon.windwhisper.forum.getOwnProfile
import moe.tachyon.windwhisper.forum.updateOwnAvatar
import moe.tachyon.windwhisper.forum.updateOwnBio
import moe.tachyon.windwhisper.utils.JsonSchema

class Profile(
    private val user: LoginData,
    private val images: GeneratedImageStore? = null,
): AiToolSet.ToolProvider<Any?>
{
    override val name = "Profile"

    @Serializable
    data class UpdateBioParams(
        @JsonSchema.Description("自己的完整新简介；替换现有简介，传空字符串可清空。")
        val bio: String,
    )

    @Serializable
    data class UpdateAvatarParams(
        @JsonSchema.Description("已由内置图片生成工具生成并保存的 imageId，将这张图片设为自己的头像。")
        val imageId: String,
    )

    override suspend fun <S> AiToolSet<S>.registerTools()
    {
        registerTool<AiToolSet.EmptyToolParm>(
            "get_my_profile", "查看自己的资料",
            "查看当前登录账号自己的简介、头像及头像修改权限。",
        ) {
            AiToolInfo.ToolResult(Content(Json.encodeToString(user.getOwnProfile())))
        }
        registerTool<UpdateBioParams>(
            "update_my_bio", "修改自己的简介",
            "修改当前登录账号自己的个人简介。只修改简介，并核对实际返回内容。",
        ) {
            val actualBio = user.updateOwnBio(parm.bio)
            AiToolInfo.ToolResult(Content(buildJsonObject {
                put("success", true)
                put("bio", actualBio)
            }.toString()))
        }
        if (images != null) registerTool<UpdateAvatarParams>(
            "update_my_avatar", "修改自己的头像",
            "将已生成并保存的图片设为当前登录账号自己的头像。传入 imageId；程序上传图片并设置头像，论坛会处理头像尺寸。",
        ) {
            val image = images.read(parm.imageId)
            val uploadId = user.updateOwnAvatar(image)
            AiToolInfo.ToolResult(Content(buildJsonObject {
                put("success", true)
                put("imageId", parm.imageId)
                put("uploadId", uploadId)
            }.toString()))
        }
    }
}
