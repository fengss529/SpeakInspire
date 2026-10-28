package com.hatio.speakinspire

import androidx.compose.ui.text.TextRange
import com.hatio.speakinspire.ui.text.pickedText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 从选区里切文字。
 *
 * ## 为什么这个纯函数值得单独测
 *
 * 它直接吃用户拖出来的区间，而区间是**不可信输入**：
 * 可能拖反方向、可能根本没选、可能在拖动过程中这段文字被换掉了
 * （换了记录、文章重新生成）。任何一种抛出异常，
 * 界面会在用户松手那一刻崩，而且看起来毫无原因。
 *
 * 所以三种情况全落在「返回空串」：空串就是「什么都没选中」，
 * 那个「收进回答」的按钮据此不显示。
 * **不显示按钮，好过显示一个点了没反应的按钮。**
 */
class PickedTextTest {

    private val text = "我提前背了稿子，上台全忘了"

    @Test
    fun `正着选`() {
        assertEquals("背了稿子", pickedText(text, TextRange(3, 7)))
    }

    @Test
    fun `反着拖也是同一段`() {
        // 用户从右往左拖，selection 的 start 会大于 end。
        // 不处理的话 substring 直接抛 StringIndexOutOfBounds
        assertEquals("背了稿子", pickedText(text, TextRange(7, 3)))
    }

    @Test
    fun `没选返回空串`() {
        assertEquals("", pickedText(text, TextRange(5)))
        assertEquals("", pickedText(text, TextRange(5, 5)))
    }

    @Test
    fun `区间越界不崩`() {
        // 拖动过程中文字被换短了，旧的 end 会指到字符串外面。
        // 负数那头不用管：TextRange 的构造函数自己就拒绝负值
        // （试过 TextRange(-5, 999)，直接抛 IllegalArgumentException），
        // 所以这里只需要守「超出上界」
        assertEquals("上台全忘了", pickedText(text, TextRange(8, 999)))
    }

    @Test
    fun `空文本返回空串`() {
        assertEquals("", pickedText("", TextRange(0, 0)))
        assertEquals("", pickedText("", TextRange(0, 10)))
    }

    @Test
    fun `全选返回全文`() {
        assertEquals(text, pickedText(text, TextRange(0, text.length)))
    }

    @Test
    fun `只有空白也算选了，但不该被当成一句话`() {
        // 切出来是空格——长度大于 0，界面会显示按钮。
        // 这里只把事实记下来：过滤空白交给调用方，
        // 因为"什么算空"是业务判断，不该埋进切词函数
        assertEquals("   ", pickedText("a   b", TextRange(1, 4)))
    }
}
