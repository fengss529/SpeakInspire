package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.service.Prompts
import com.hatio.speakinspire.service.ThinMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限边界断言。
 *
 * 这是整个测试策略存在的核心理由：
 * **核心设计边界不能只靠 prompt 里的措辞自觉。**
 *
 * 如果这些测试被删除或注释掉，说明产品核心价值被动摇了。
 */
class PromptBoundaryTest {

    // ---------- 追问阶段：AI 只能问，不能答 ----------

    @Test
    fun `追问 prompt 明确禁止给结论和建议`() {
        val p = Prompts.PROBE_SYSTEM_PROMPT
        assertTrue("必须写明不给结论", p.contains("不给结论"))
        assertTrue("必须写明不给建议", p.contains("不给建议"))
        assertTrue("必须写明不给替代方案", p.contains("不给替代方案"))
    }

    @Test
    fun `追问 prompt 的建议句式只出现在禁止清单里`() {
        val p = Prompts.PROBE_SYSTEM_PROMPT
        val forbidden = listOf(
            "我建议你",
            "你应该",
            "建议你",
            "更好的做法是",
            "最佳实践",
            "首先你",
        )

        // 关键：不能简单断言 contains==false。
        // 这些词必须出现在「绝对禁止」段里——正是为了禁止它们。
        // 所以要验证的是：它们只出现在禁止语境，不出现在「角色」或「输出」段。
        val sections = p.split(Regex("【[^】]+】"))
        // sections[0] 是标题，其后每段以【xx】开头，内容在下一个元素
        val bodyLines = sections.drop(1).filter { it.isNotBlank() }

        val bannedMarker = "不说"
        bodyLines.forEach { section ->
            if (section.contains(bannedMarker)) return@forEach
            // 非禁止段里出现任一建议句式 = 真问题
            forbidden.forEach { phrase ->
                assertFalse(
                    "非禁止段落出现了建议句式「$phrase」：${section.take(40)}",
                    section.contains(phrase),
                )
            }
        }
    }

    @Test
    fun `追问 prompt 的禁止清单确实列出了这些句式`() {
        val p = Prompts.PROBE_SYSTEM_PROMPT
        // 反向确认：禁止清单本身要完整，否则上面那个测试就是空转
        assertTrue(p.contains("不说"))
        assertTrue(p.contains("我建议你"))
        assertTrue(p.contains("你应该"))
    }

    @Test
    fun `追问 prompt 禁止总结用户观点`() {
        val p = Prompts.PROBE_SYSTEM_PROMPT
        assertTrue("必须明确写「不总结」", p.contains("不总结用户的观点"))

        // 这句话出现在禁止清单里是对的——正是为了禁止它。
        // 但要确认它只以「不说」的形式出现，而不是在示范话术里。
        val forbiddenExample = "根据你的回答，我理解你"
        if (p.contains(forbiddenExample)) {
            val idx = p.indexOf(forbiddenExample)
            val before = p.substring(maxOf(0, idx - 30), idx)
            assertTrue(
                "总结句式只能作为反例出现，前面必须有禁止标记，实际：…${before}",
                before.contains("不说") || before.contains("不"),
            )
        }
    }

    @Test
    fun `追问 prompt 要求只输出开放式问题`() {
        val p = Prompts.PROBE_SYSTEM_PROMPT
        assertTrue(p.contains("只输出一个开放式问题"))
    }

    /**
     * 真机反馈：「AI 追问的问题有点水，没有结合记录和之后的回答，
     * 只是针对当前问题的一个追问」。
     *
     * 光在 prompt 里写「要结合上下文」没用——模型会照旧问
     * 「你能具体说说吗」。必须给它一个**可判断的标准**
     * 和一组能对照的反面例子。
     */
    @Test
    fun `追问 prompt 要求贴着用户的原话问`() {
        val p = Prompts.PROBE_SYSTEM_PROMPT
        assertTrue("必须给出可判断的标准", p.contains("把他那句原话遮住"))
        assertTrue("必须列出「白问」的反面例子", p.contains("换成任何话题都能问"))
        assertTrue("必须明确要带上最初那句记录", p.contains("最初随手记"))
        assertTrue("必须提示前后矛盾处才是好问题", p.contains("讲得不一样"))
    }

    // ---------- 成文阶段：允许补位，但守住立场 ----------

    @Test
    fun `成文 prompt 允许补充论证`() {
        val p = Prompts.DRAFT_SYSTEM_PROMPT
        assertTrue("决策 Q1 选 (c)：允许补充论证", p.contains("补充论证"))
        assertTrue(p.contains("反例"))
    }

    @Test
    fun `成文 prompt 禁止改变用户立场`() {
        val p = Prompts.DRAFT_SYSTEM_PROMPT
        assertTrue("必须禁止改变立场", p.contains("改变用户的观点立场"))
        assertTrue("必须禁止编造事实", p.contains("编造"))
    }

    @Test
    fun `成文 prompt 强制段落级来源标注`() {
        val p = Prompts.DRAFT_SYSTEM_PROMPT
        assertTrue(
            "「来源」视图是 (c) 方案的必要配套，标注必须在 prompt 里强制",
            p.contains("isMine"),
        )
    }

    @Test
    fun `成文 prompt 规定素材不足必须拒稿`() {
        val p = Prompts.DRAFT_SYSTEM_PROMPT
        // 门槛数字直接引用 ThinMaterial.THRESHOLD，不写死——
        // 两处写死过一次，prompt 里还留着 300 而代码已改成 60，
        // 模型和后端各按各的数走
        assertTrue(
            "必须写明字数下限 ${ThinMaterial.THRESHOLD}",
            p.contains(ThinMaterial.THRESHOLD.toString()),
        )
        assertTrue("必须写明拒绝", p.contains("拒绝成稿"))
        assertTrue("必须禁止注水", p.contains("注水"))
    }

    /**
     * 300 字的统一门槛已经去掉。
     *
     * 真机反馈：「短一些的感悟，也可以成短文」。
     * 这条测试守着那个决策不被悄悄改回去——
     * 一旦有人重新写死一个大数字，短感悟就又写不出来了。
     */
    @Test
    fun `成文 prompt 不再设 300 字统一门槛`() {
        val p = Prompts.DRAFT_SYSTEM_PROMPT
        assertFalse("不能重新写死 300 字门槛", p.contains("300"))
        assertTrue("必须说明长度跟着素材走", p.contains("跟着素材走"))
    }

    // ---------- 转选题阶段：绝不给逐字稿 ----------

    @Test
    fun `转选题 prompt 禁止写逐字稿`() {
        assertTrue(Prompts.ANGLE_SYSTEM_PROMPT.contains("不写逐字稿"))
        assertTrue(Prompts.OUTLINE_SYSTEM_PROMPT.contains("不写逐字稿"))
    }

    @Test
    fun `转选题 prompt 要求意群短语而非孤立词`() {
        val p = Prompts.OUTLINE_SYSTEM_PROMPT
        assertTrue("必须要求意群短语", p.contains("意群短语"))
        assertTrue("必须给出正反例", p.contains("我明明准备过"))
        assertTrue("必须标出孤立词是错的", p.contains("孤立词"))
    }

    @Test
    fun `转选题 prompt 禁止建议用户改成别的观点`() {
        val p = Prompts.OUTLINE_SYSTEM_PROMPT
        assertTrue(p.contains("不改变用户文章的观点"))
    }

    // ---------- 三套 prompt 不得互相污染 ----------

    @Test
    fun `追问 prompt 不含成文允许的内容`() {
        // 最危险的污染方向：把成文的「补位」能力写进追问 prompt
        val probe = Prompts.PROBE_SYSTEM_PROMPT
        assertFalse(
            "追问 prompt 不得出现补位式措辞",
            probe.contains("补充论证"),
        )
        assertFalse(
            "追问 prompt 不得出现成文相关的『文章』表述",
            probe.contains("写成一篇"),
        )
    }

    @Test
    fun `追问 prompt 不得包含轮次倒计时`() {
        // 倒计时诱发敷衍作答——用户会为了快速结束而草草回答，
        // 而追问的全部价值依赖于用户真的在想。
        val p = Prompts.PROBE_SYSTEM_PROMPT
        assertFalse(p.contains("还剩"))
        assertFalse(p.contains("剩余"))
    }

    @Test
    fun `追问 prompt 允许提前收尾`() {
        // 追问不是硬凑 5 轮，用户想通了就该收
        val p = Prompts.PROBE_SYSTEM_PROMPT
        assertTrue(p.contains("不要硬凑"))
        assertTrue(p.contains("提前") || p.contains("想通"))
    }

    // ---------- 契约层：上下文结构 ----------

    @Test
    fun `追问上下文正确计算已答轮数`() {
        val ctx = DeepeningContext(
            noteId = "n1",
            originalNote = "我在会上说不出话",
            rounds = listOf(
                ChatMessage.ai("当时具体发生了什么？"),
                ChatMessage.user("讲到第二页就卡住了"),
            ),
            roundIndex = 1,
            maxRounds = 5,
        )
        assertEquals(1, ctx.answeredRounds)
        assertFalse(ctx.isLastRound)
    }

    @Test
    fun `最后一轮标记正确`() {
        val ctx = DeepeningContext(
            noteId = "n1",
            originalNote = "x",
            rounds = emptyList(),
            roundIndex = 4,
            maxRounds = 5,
        )
        assertTrue(ctx.isLastRound)
    }
}