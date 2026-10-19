package com.hatio.speakinspire

import com.hatio.speakinspire.service.ProbeMethod
import com.hatio.speakinspire.service.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追问方法学的断言。
 *
 * 这里守的是两件事：
 *
 * 1. **四种方法真的不一样**——否则设置页里那四个选项就是装饰。
 * 2. **四种方法又真的守同一条边界**——换问法不等于放宽 AI 的权力，
 *    这条线一旦被某种方法打穿，产品就退化成「聊天拿建议」了。
 */
class ProbeMethodTest {

    private val all = ProbeMethod.entries

    @Test
    fun `方法 id 唯一且非空`() {
        val ids = all.map { it.id }
        assertEquals("id 必须唯一，否则存档读回来会串", all.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun `未知 id 回落到默认方法`() {
        assertEquals(ProbeMethod.DEFAULT, ProbeMethod.byId(null))
        assertEquals(ProbeMethod.DEFAULT, ProbeMethod.byId(""))
        assertEquals(ProbeMethod.DEFAULT, ProbeMethod.byId("不存在的id"))
    }

    @Test
    fun `按 id 能取回同一个方法`() {
        all.forEach { m ->
            assertEquals(m, ProbeMethod.byId(m.id))
        }
    }

    // ---------- 四种方法必须真的不同 ----------

    @Test
    fun `四种方法的提示词互不相同`() {
        val prompts = all.map { Prompts.probeSystem(it) }
        assertEquals(
            "每种方法必须产出不同的提示词，否则设置里选什么都没区别",
            all.size,
            prompts.toSet().size,
        )
    }

    @Test
    fun `每种方法都带自己的提问手法`() {
        all.forEach { m ->
            val p = Prompts.probeSystem(m)
            assertTrue(
                "${m.id} 的提示词里找不到自己的手法",
                p.contains(m.instruction.trim()),
            )
        }
    }

    @Test
    fun `方法之间有实质区分而不是换个说法`() {
        // 抓每种方法的标志性动作
        assertTrue(Prompts.probeSystem(ProbeMethod.SOCRATIC).contains("具体指什么"))
        assertTrue(Prompts.probeSystem(ProbeMethod.FIVE_WHYS).contains("为什么"))
        assertTrue(Prompts.probeSystem(ProbeMethod.DEVILS_ADVOCATE).contains("反例"))
        assertTrue(Prompts.probeSystem(ProbeMethod.STAR).contains("最近一次"))
    }

    // ---------- 但四种方法必须守同一条边界 ----------

    @Test
    fun `每种方法都禁止给结论建议和替代方案`() {
        all.forEach { m ->
            val p = Prompts.probeSystem(m)
            assertTrue("${m.id} 必须禁止给结论", p.contains("不给结论"))
            assertTrue("${m.id} 必须禁止给建议", p.contains("不给建议"))
            assertTrue("${m.id} 必须禁止给替代方案", p.contains("不给替代方案"))
        }
    }

    @Test
    fun `禁区段落不因方法而变`() {
        // 这是这个测试类里最重要的一条：
        // 换方法只换提问手法，禁区必须是同一段文字。
        // 如果有人给某种方法写了「可以适当给点提示」，
        // 这条会立刻红。
        fun forbiddenBlock(p: String): String =
            p.substringAfter("【绝对禁止").substringBefore("【你的输出】")

        val blocks = all.map { forbiddenBlock(Prompts.probeSystem(it)) }
        blocks.forEach { b ->
            assertTrue("禁区段被切空了，断言会空转", b.length > 20)
        }
        assertEquals("四种方法的禁区必须完全一致", 1, blocks.toSet().size)
    }

    @Test
    fun `每种方法都只输出一个问题`() {
        all.forEach { m ->
            assertTrue(
                "${m.id} 必须限定输出形状",
                Prompts.probeSystem(m).contains("只输出一个开放式问题"),
            )
        }
    }

    @Test
    fun `追问提示词不得越界到成文`() {
        all.forEach { m ->
            val p = Prompts.probeSystem(m)
            assertFalse("${m.id} 不得出现成文阶段的补位能力", p.contains("补充论证"))
            assertFalse("${m.id} 不得出现成文阶段的表述", p.contains("写成一篇"))
        }
    }

    // ---------- 轮次与收尾 ----------

    @Test
    fun `提示词带上当前轮次`() {
        val p = Prompts.probeSystem(ProbeMethod.SOCRATIC, roundIndex = 2, maxRounds = 5)
        assertTrue("必须告诉模型这是第几轮", p.contains("第 3 轮"))
        assertTrue(p.contains("最多 5 轮"))
    }

    @Test
    fun `不得出现轮次倒计时`() {
        // 倒计时诱发敷衍作答——用户会为了快点结束而草草回答
        all.forEach { m ->
            val p = Prompts.probeSystem(m)
            assertFalse("${m.id} 出现了倒计时", p.contains("还剩"))
            assertFalse("${m.id} 出现了倒计时", p.contains("剩余"))
        }
    }

    @Test
    fun `不让模型输出收尾语`() {
        // 收尾语会被当成「又一个问题」追加进轮次，
        // 界面停在等你回答，而用户面对一句评价无从下口。
        val p = Prompts.probeSystem()
        assertTrue("必须明确禁止收尾语", p.contains("不要输出"))
        assertTrue("必须说明结束权归用户", p.contains("结束追问是用户的权利"))
        assertTrue("必须给出收拢式问法", p.contains("那你自己现在怎么看"))
    }

    @Test
    fun `默认方法下与兼容常量一致`() {
        // PROBE_SYSTEM_PROMPT 是给既有断言用的稳定样本，
        // 不能和真正发出去的提示词漂移
        assertEquals(Prompts.probeSystem(), Prompts.PROBE_SYSTEM_PROMPT)
    }

    @Test
    fun `每种方法都给出了适配场景`() {
        all.forEach { m ->
            assertTrue("${m.id} 缺少一句话说明", m.blurb.isNotBlank())
            assertTrue("${m.id} 缺少适用场景，" +
                "用户会不知道什么时候该选它", m.bestFor.isNotBlank())
            assertNotEquals("${m.id} 的说明不能是占位符", "TODO", m.blurb)
        }
    }
}
