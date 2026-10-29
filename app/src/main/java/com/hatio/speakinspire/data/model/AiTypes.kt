package com.hatio.speakinspire.data.model

/** 追问对话中的一条消息。用于流式追问与成文两种场景。 */
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
) {
    companion object {
        fun ai(text: String) = ChatMessage(text, isUser = false)
        fun user(text: String) = ChatMessage(text, isUser = true)
    }
}

/** 成文阶段的输入。 */
data class ArticleDraft(
    val noteId: String,
    val title: String,
    val originalNote: String,
    val dialogue: List<ChatMessage>,
) {
    /** 用户实际说过的字数（去空白）。低于此值 AI 应拒稿。 */
    val materialSize: Int
        get() = dialogue.filter { it.isUser }
            .sumOf { it.content.count { c -> !c.isWhitespace() } }
}

/**
 * 成文结果。
 *
 * 注意 [rejected] 的存在——素材不足时必须**明确拒稿**而不是硬凑。
 * 注水会毁掉整个思考库的可信度。
 */
sealed interface ComposeResult {
    data class Success(
        val title: String,
        val paragraphs: List<DraftParagraph>,
    ) : ComposeResult

    /** 素材不足，原样返回给用户看——让用户自己决定要不要再聊聊 */
    data class Rejected(
        val actualChars: Int,
        val requiredChars: Int,
    ) : ComposeResult

    /**
     * 失败。
     *
     * [detail] 不能省。这个类曾经只有 [kind] 一个字段，
     * 于是成文失败能说出口的只有「出了点问题」——
     * 服务商返回的原文、解析拿到的响应体，全在中间层被扔掉了。
     * 追问那边早就带上了 detail，成文这边漏了整整一条链路。
     */
    data class Failed(
        val kind: ErrorKindLite,
        val detail: String? = null,
    ) : ComposeResult
}

enum class ErrorKindLite { NETWORK, UNAUTHORIZED, RATE_LIMITED, UNKNOWN }

data class DraftParagraph(
    val text: String,
    /** true = 用户在追问里说过的；false = 成文阶段 AI 补充的论证 */
    val isMine: Boolean,
    /**
     * 这段用到了「发芽」里的连接 / 例子 / 引用。
     *
     * 一旦为 true，[isMine] 必须是 false —— 别人说的话不能标成你说的。
     * 这条不靠模型自觉：解析时在这里强制一次（见 `RealAiService.parseCompose`）。
     */
    val fromSprout: Boolean = false,
)

/** 一个拍摄角度。同一篇文章可派生多个。 */
data class Angle(
    val title: String,
    /** 为什么这个角度适合讲——帮用户判断，不是给他答案 */
    val rationale: String,
    val kind: String,
)

/** 完整拍摄骨架。意群短语，绝不写逐字稿。 */
data class ScriptOutline(
    val angle: String,
    val sections: List<OutlineSection>,
    /**
     * 手边的料。**和骨架同一次请求产出**——
     * 少一次等待、少一次点击，而且模型手里已经有文章全文，
     * 这时候找例子比单开一次请求更准。
     */
    val materials: List<Material> = emptyList(),
) {
    val totalSeconds: Int get() = sections.sumOf { it.seconds }
}

data class OutlineSection(
    val title: String,
    val seconds: Int,
    val goal: String,
    /** 意群短语，如「我明明准备过」——而非孤立词「稿子」 */
    val phrases: List<String>,
    val cues: List<ShootingCue> = emptyList(),
)

data class ShootingCue(
    val icon: String,
    val text: String,
)

/** 用户自带的服务商配置。key 存EncryptedSharedPreferences。 */
data class ProviderConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** MiMo 的 thinking 默认开启，需显式 disabled；DeepSeek 则是换模型名控制 */
    val thinkingDisabled: Boolean = true,
) {
    companion object {
        const val DEEPSEEK = "deepseek"
        const val MIMO = "mimo"
        const val CUSTOM = "custom"
    }
}