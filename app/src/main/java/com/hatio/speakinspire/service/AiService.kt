package com.hatio.speakinspire.service

import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.model.ScriptOutline
import com.hatio.speakinspire.data.model.SproutSection
import kotlinx.coroutines.flow.Flow

/**
 * AI 能力的唯一入口，也是本App 唯一的测试主缝隙。
 *
 * ## 为什么三个方法必须分开
 *
 * 追问、成文、转选题的 AI 权力边界完全不同：
 *
 * | 阶段       | AI 能做                | AI 不能做                    |
 * |------------|------------------------|------------------------------|
 * | 追问       | 只提问、攻击观点| 不给结论/建议/替代方案 |
 * | 成文       | 重组/润色/补论证       | 不改变用户观点立场           |
 * | 转选题     | 拆解成意群短语骨架     | 不写逐字稿                   |
 *
 * 如果把它们合并成一个 `chat(prompt)` 加参数区分的方法，
 * 那么「追问不给答案」这条规则就只能靠 prompt 里的措辞保证，
 * **没有任何结构上的保障**。分成不同方法后，
 * [streamQuestion] 在类型上就不可能返回一段总结。
 *
 * 这个约束由单元测试强制：
 * - `AiServiceContractTest` 断言三个方法的签名差异
 * - `PromptBoundaryTest` 断言追问 prompt 中不含给答案的措辞
 */
interface AiService {

    /**
     * 追问阶段的流式提问。
     *
     * 契约：
     * - 每次调用**最多返回一个开放式问题**
     * - **绝不**返回结论、建议或替代方案
     * - 流式输出，用于打字机效果
     * - 实现方可通过 [ComposeResult.wrappedUp] 表达「聊透了」提前收尾
     *
     * @param context 当前追问上下文（含历史轮次与原始记录）
     * @return 问题文本的字符流。空流表示无法生成问题（应降级处理）
     */
    fun streamQuestion(context: DeepeningContext): Flow<String>

    /**
     * 成文阶段的一次性调用。
     *
     * 契约：
     * - 观点必须全部来自用户回答
     * - **允许**补充论证、反例、数据
     * - **不允许**改变用户观点立场
     * - 素材不足时**必须**返回 [ComposeResult.rejected] 而不是注水文章
     */
    suspend fun composeArticle(context: DeepeningContext): ComposeResult

    /**
     * 转选题阶段：把文章拆解成多个拍摄角度。
     *
     * 契约：
     * - 骨架用意群短语，**不写逐字稿**
     * - 每条选题给独特切入角度
     * - 每段标建议时长，总时长 30-60 秒
     */
    suspend fun deriveAngles(article: ArticleDraft): List<Angle>

    /**
     * 生成某个角度的完整拍摄骨架。
     *
     * 与 [deriveAngles] 分开：拆角度是快速的列表操作，
     * 生成骨架是较慢的结构化输出，两者不应挤在同一次调用里。
     */
    suspend fun buildOutline(article: ArticleDraft, angle: Angle): ScriptOutline

    /**
     * 发芽：从一条记录里长出新的连接。
     *
     * ## 为什么它和 [streamQuestion] 是两种东西
     *
     * 追问只问不答，是为了让用户自己想到。发芽**必须给内容**——
     * 它的价值就在于「把你说的话放到你没想到的地方去」。
     * 这不是自相矛盾：追问的产物是**问题**（要用户继续想），
     * 发芽的产物是**参照物**（用户可以看，也可以不看）。
     *
     * 所以这里的契约是：
     * - 每一节都要从他的原话出发（`seed` 必须是他的句子）
     * - 引用别人的话必须给出处，给不出就留空，**不许编**
     * - 不许替他下结论、不许出现「你应该」
     *
     * 这三条由 `SproutBoundaryTest` 守着。
     */
    suspend fun sprout(context: SproutContext): SproutResult

    /**
     * 连接测试，用于设置页的「测试连接」按钮。
     *
     * 不消耗对话配额，只发最短请求验证配置可用。
     */
    suspend fun testConnection(config: ProviderConfig): ConnectionResult
}

/**
 * 连接测试结果。
 *
 * 错误必须**按类型区分**而非返回错误字符串——
 * 调用方需要按类型决定降级行为（重试/引导修改/直接放弃）。
 */
sealed interface ConnectionResult {
    data class Success(
        val latencyMs: Long,
        val model: String,
        val contextWindow: Long?,
    ) : ConnectionResult

    /** 已识别的可归因错误。[reason] 用于给用户看的人话。 */
    data class Failure(
        val kind: ErrorKind,
        val reason: String,
    ) : ConnectionResult

    data object Unknown : ConnectionResult
}

/**
 * 可归因的错误类型。
 *
 * 这些都是实际查证中遇到过的坑，每一类都需要给用户不同的引导。
 */
enum class ErrorKind {
    /** 401：Key 无效或过期 */
    INVALID_API_KEY,

    /** 404：模型名不存在 / 不再提供 */
    MODEL_NOT_FOUND,

    /** 429：请求过于频繁 */
    RATE_LIMITED,

    /** 余额不足 */
    INSUFFICIENT_BALANCE,

    /** Token Plan 的key 走了按量端点（MiMo 的 sk- 与 tp- 端点不同） */
    WRONG_ENDPOINT_FOR_KEY,

    /** 网络不可达 / 超时 */
    NETWORK_UNAVAILABLE,

    /** SSE 解析失败 */
    MALFORMED_STREAM,

    /**
     * 400 / 422：请求本身被拒。
     *
     * **这一类必须能说出服务商的原话。**
     * 曾经请求体漏了一个逗号，服务商稳定返回 400，
     * 界面却只说「出了点问题，具体原因看不出来」——
     * 用户唯一能做的只有反复重试和放弃。
     */
    BAD_REQUEST,

    /**
     * 连上了、也返回了，但正文是空的——只收到思考过程。
     *
     * 思考模式下模型先把思维链说完才说正文；`max_tokens` 一旦被思考吃光，
     * 正文就永远不来。表现是「点了没反应」，而真实原因非常具体。
     */
    THINKING_ONLY,

    /** 连上了、返回了，但一个字都没解析出来 */
    NO_CONTENT,

    /** 服务商在流中间发了一帧 error（额度、审核、参数不支持） */
    STREAM_ERROR,

    /** 未分类 */
    UNKNOWN,
}

/** 追问上下文。传给 AI 时会完整携带，保证多轮对话的连续性。 */
data class DeepeningContext(
    val noteId: String,
    /** 用户最初随手记的内容 */
    val originalNote: String,
    /** 已完成的轮次，按时间顺序 */
    val rounds: List<ChatMessage>,
    /** 当前轮次序号，从 0 开始 */
    val roundIndex: Int,
    /** 硬性上限 */
    val maxRounds: Int,
    /** 标签，作为轻量上下文，不参与决策 */
    val tags: List<String> = emptyList(),
    /**
     * 这一轮用哪种追问方法。
     *
     * 放进上下文而不是由服务实现自己读配置，是为了让
     * 「ViewModel 选了哪种方法 → 实际发出去的是哪种」
     * 能被链路测试直接断言，不依赖 Android 存储。
     */
    val probeMethod: ProbeMethod = ProbeMethod.DEFAULT,
    /**
     * 用户改过的问法。null 表示用 [ProbeMethod.instruction]。
     *
     * 和 [probeMethod] 一样放进上下文，理由也一样：
     * 让「设置里改了什么 → 实际发出去的是什么」能被链路测试直接断言。
     * 服务实现自己读存储的话，这条链就断了。
     */
    val probeInstruction: String? = null,
) {
    /** 已经答完的轮数 */
    val answeredRounds: Int get() = rounds.count { it.isUser }

    /**
     * 用户实际说过的字数（去空白）。成文阶段判断素材是否足够。
     *
     * **必须把 [originalNote] 算进来。**
     *
     * 之前只数 `rounds` 里的回答。于是「随手记了 500 字、只答了两轮」
     * 这种最有内容的记录反而被判成素材不足——用户明明写了一大段，
     * App 告诉他「你聊得太少了」。这条规则错得很隐蔽：
     * 它惩罚的正是认真记录的人。
     */
    val materialSize: Int
        get() = originalNote.count { c -> !c.isWhitespace() } +
            rounds.filter { it.isUser }
                .sumOf { it.content.count { c -> !c.isWhitespace() } }

    /** 是否为最后一轮 */
    val isLastRound: Boolean get() = roundIndex >= maxRounds - 1
}

/**
 * 发芽的输入。
 *
 * [answers] 也参与——追问过的记录往往比原始那一条更值得发芽：
 * 用户随手写下的是种子，追问里那些磕磕绊绊的解释才是根。
 */
data class SproutContext(
    val noteId: String,
    val noteTitle: String,
    /** 他最初记下的那句话 */
    val noteBody: String,
    /** 追问里他说过的句子，按时间顺序 */
    val answers: List<String> = emptyList(),
    /**
     * 已经长出来的芽（标题）。
     *
     * 传进去是为了**避免重复**：同一条记录第二次发芽，
     * 如果又给一遍「爱因斯坦也说过类似的话」，用户会觉得这功能是死的。
     */
    val previousTitles: List<String> = emptyList(),
)

/**
 * 发芽结果。
 *
 * 失败的 [detail] 必须有——发芽是一次较长的请求，
 * 失败时若只显示「没长出来」，用户无从判断该重试还是该改配置。
 */
sealed interface SproutResult {
    data class Success(
        val title: String,
        val sections: List<SproutSection>,
    ) : SproutResult

    data class Failed(val detail: String? = null) : SproutResult
}