package com.hatio.speakinspire.service

import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.data.model.DraftParagraph
import com.hatio.speakinspire.data.model.ErrorKindLite
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.model.ScriptOutline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 假 AI 服务。这是本项目**唯一的主测试缝隙**。
 *
 * 六项能力，每项对应一类真实场景：
 *
 * | 能力 | 开关 | 对应真实场景 |
 * |---|---|---|
 * | 脚本化返回 | [questionScript] / [draftText] | 断言上层逻辑的确定性行为 |
 * | 流速控制 | [charDelayMs] | 打字机效果、可跳过 |
 * | 失败模拟 | [failProbe] / [failDraft] | 错误归因与降级 |
 * | 素材不足 | [forceThinMaterial] | AI 拒稿而非注水 |
 * | 提前收尾 | [wrapUpAfter] | 追问不是硬凑 5 轮 |
 * | **请求捕获** | [captured] | **把权限边界变成可自动验证的断言** |
 *
 * 最后一项最重要：它让「追问 prompt 里不能出现给答案的措辞」
 * 这条核心设计规则变成一条 assert，而不是靠后来人的自觉。
 */
class FakeAiService : AiService {

    /** 捕获到的全部请求。断言 prompt 内容靠它。 */
    val captured = mutableListOf<Captured>()

    data class Captured(
        val stage: Stage,
        val systemPrompt: String,
        val context: DeepeningContext?,
    )

    enum class Stage { PROBE, DRAFT, ANGLES, OUTLINE, PING }

    // ---------- 脚本化返回 ----------
    var questionScript: List<String> = emptyList()
    var draftText: String = ""
    var draftTitle: String = ""
    var draftParagraphs: List<com.hatio.speakinspire.data.model.DraftParagraph> = emptyList()
    var angles: List<Angle> = emptyList()
    var outline: ScriptOutline? = null

    // ---------- 行为开关 ----------
    var failProbe: Boolean = false
    var failDraft: Boolean = false
    var failPing: ConnectionResult.Failure? = null

    /** 强制 AI 以素材不足拒稿 */
    var forceThinMaterial: Boolean = false

    /** 在第 N 轮后提前收尾（不硬凑 5 轮） */
    var wrapUpAfter: Int? = null

    /** 每个字符延迟，模拟流式。0 = 一次性返回 */
    var charDelayMs: Long = 0

    /** 追问游标，支持同一 Fake 连续跑多轮 */
    var probeCursor: Int = 0

    /** 列表页显示的「预估追问轮数」 */
    var estimatedRounds: Int = 5

    /** 是否已表达「聊透了」 */
    var wrappedUp: Boolean = false

    // ---------- AiService 实现 ----------

    override fun streamQuestion(context: DeepeningContext): Flow<String> {
        captured += Captured(Stage.PROBE, PROBE_SYSTEM_PROMPT, context)
        if (failProbe) return flow { throw IllegalStateException("模拟的追问失败") }

        val limit = wrapUpAfter?.coerceAtMost(questionScript.size) ?: questionScript.size
        if (probeCursor >= limit) {
            // 达到收尾上限——模拟 AI 判断用户已想通，主动结束
            wrappedUp = true
            return flow { }
        }

        val q = questionScript[probeCursor]
        // 游标在 collect 时推进，而不是在构建时——
        // 这样「调用了但没收集」不会误消耗一轮，
        // 轮次与实际产出严格对应。
        return flow {
            probeCursor++
            if (charDelayMs <= 0L) {
                emit(q)
            } else {
                for (ch in q) {
                    emit(ch.toString())
                    delay(charDelayMs)
                }
            }
        }
    }

    override suspend fun composeArticle(context: DeepeningContext): ComposeResult {
        captured += Captured(Stage.DRAFT, DRAFT_SYSTEM_PROMPT, context)

        if (failDraft) return ComposeResult.Failed(
            com.hatio.speakinspire.data.model.ErrorKindLite.NETWORK
        )

        if (forceThinMaterial) {
            return ComposeResult.Rejected(actualChars = 180, requiredChars = THIN_MATERIAL)
        }

        val paragraphs = draftParagraphs.ifEmpty {
            listOf(com.hatio.speakinspire.data.model.DraftParagraph(draftText, isMine = true))
        }
        return ComposeResult.Success(draftTitle.ifEmpty { "未命名" }, paragraphs)
    }

    override suspend fun deriveAngles(article: ArticleDraft): List<Angle> {
        captured += Captured(Stage.ANGLES, ANGLE_SYSTEM_PROMPT, null)
        return angles
    }

    override suspend fun buildOutline(
        article: ArticleDraft,
        angle: Angle,
    ): ScriptOutline {
        captured += Captured(Stage.OUTLINE, OUTLINE_SYSTEM_PROMPT, null)
        return outline ?: ScriptOutline(angle.title, emptyList())
    }

    override suspend fun testConnection(config: ProviderConfig): ConnectionResult {
        captured += Captured(Stage.PING, "", null)
        return failPing ?: ConnectionResult.Success(
            latencyMs = 240L,
            model = config.model,
            contextWindow = 1_000_000L,
        )
    }

    companion object {
        /** 成文素材下限：低于此字数应拒稿 */
        const val THIN_MATERIAL = 300
    }
}