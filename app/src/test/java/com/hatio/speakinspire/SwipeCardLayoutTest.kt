package com.hatio.speakinspire

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动卡片的位移守卫：**位移绝不能用 `Modifier.padding` 表达**。
 *
 * ## 这个 bug 为什么只能靠读源码守
 *
 * 它不在数据层，也不在任何纯函数里——
 * `GestureJudge.judge(-600f, 0f)` 返回 `PARK` 是完全正确的，
 * 所有单测都会绿。炸的是 Compose 的布局校验：
 *
 * ```
 * Modifier.padding(start = dragX.dp)   // dragX 左滑时为负
 * → IllegalArgumentException: Padding must be non-negative
 * → FATAL EXCEPTION: main → App 闪退
 * ```
 *
 * 实测：左滑任意一张选题卡片，**必崩**。
 * 也就是说「搁置」这个功能从加上去那天起就没成功过一次，
 * 而右滑（正数）一直是好的——所以只测右滑的用例发现不了。
 *
 * 正确写法是 `Modifier.offset { IntOffset(dx, 0) }`：
 * 它吃 IntOffset，负值合法，而且只影响绘制阶段，不触发布局。
 *
 * ## 为什么不写 Compose 测试
 *
 * 要跑起来得加 `androidx.compose.ui:ui-test-junit4` + Robolectric 组合，
 * 为一条断言引一整套依赖不划算。这里沿用 [DaoWriteStrategyTest] 的做法：
 * 直接读源码断言「没这么写」。**宁可粗糙，也不要留一个必崩的入口。**
 */
class SwipeCardLayoutTest {

    /** 从测试工作目录往上找 TopicScreen.kt（Gradle 的 cwd 可能是根目录或 app/） */
    private fun sourcePath(): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidates = listOf(
                File(dir, "app/src/main/java/com/hatio/speakinspire/ui/screens/TopicScreen.kt"),
                File(dir, "src/main/java/com/hatio/speakinspire/ui/screens/TopicScreen.kt"),
            )
            candidates.firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile ?: dir
        }
        error("找不到 TopicScreen.kt，起始目录 ${System.getProperty("user.dir")}")
    }

    /**
     * 剥掉注释再扫。
     *
     * 不剥的话，连**解释这个坑的那段注释本身**都会被匹配到——
     * 第一次跑这条测试就是这么红的，因为我在注释里原样抄了错误写法。
     */
    private fun code(): String = sourcePath().readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `拖拽位移不能用 padding 表达`() {
        assertFalse(
            "padding 不能接受 dragX——左滑时它是负数，Compose 会直接抛" +
                "「Padding must be non-negative」闪退。" +
                "位移请用 Modifier.offset { IntOffset(...) }",
            Regex("""padding\s*\([^)]*dragX""").containsMatchIn(code()),
        )
    }

    @Test
    fun `拖拽位移必须走 offset`() {
        assertTrue(
            "选题卡片的横向位移应该用 Modifier.offset { IntOffset(...) }",
            Regex("""offset\s*\{\s*IntOffset""").containsMatchIn(code()),
        )
    }
}
