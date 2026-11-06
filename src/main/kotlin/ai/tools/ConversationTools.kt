package moe.tachyon.windwhisper.ai.tools

import moe.tachyon.windwhisper.ai.GeneratedAudioStore
import moe.tachyon.windwhisper.ai.GeneratedImageStore
import moe.tachyon.windwhisper.blackList
import moe.tachyon.windwhisper.config.AiConfig
import moe.tachyon.windwhisper.config.aiConfig
import moe.tachyon.windwhisper.dataDir
import moe.tachyon.windwhisper.forum.LoginData
import java.io.File

/** Use the same tool schemas for generation and the console's input-token count. */
internal suspend fun conversationTools(user: LoginData, images: GeneratedImageStore, model: AiConfig.LlmModel) =
    AiToolSet(
        Forum(user, blackList, images),
        Profile(user, images),
        ReadImage(),
        Speech(aiConfig.speech, GeneratedAudioStore(File(dataDir, "generated-audio")), user),
    ).getTools(null, model)
