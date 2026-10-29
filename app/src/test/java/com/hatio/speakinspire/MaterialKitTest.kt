package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.Material
import com.hatio.speakinspire.data.model.MaterialKind
import com.hatio.speakinspire.domain.MaterialKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 素材准入规则。
 *
 * ## 这条规则为什么必须在代码里，而不是只在 prompt 里
 *
 * 「没有出处就不要给」写进了 prompt，但模型编一句
 * 「某项研究显示 67% 的人……」太容易了，而且看起来很像真的。
 * 用户会把它原样讲出去，被追问时说不出出处。
 *
 * 判断「这条有没有出处」是我们自己数得清的事。
 * 本文件守的就是这个数——它一旦被放宽，
 * App 里就会出现没有来路的数据和金句。
 */
class MaterialKitTest {

    private fun data(source: String = "") =
        Material(MaterialKind.DATA, "67% 的人在会上不敢开口", source)

    private fun quote(source: String = "") =
        Material(MaterialKind.QUOTE, "勇气是压力下的优雅", source)

    private fun citation(source: String = "") =
        Material(MaterialKind.CITATION, "《被讨厌的勇气》里讲课题分离", source)

    private fun example(source: String = "") =
        Material(MaterialKind.EXAMPLE, "上次跟客户吃饭，我也是一开口就快", source)

    // ---------- 无出处即丢弃 ----------

    @Test
    fun `没出处的数据被丢掉`() {
        assertTrue(MaterialKit.sanitize(listOf(data())).isEmpty())
    }

    @Test
    fun `没出处的金句被丢掉`() {
        assertTrue(MaterialKit.sanitize(listOf(quote())).isEmpty())
    }

    @Test
    fun `没出处的引用被丢掉`() {
        assertTrue(MaterialKit.sanitize(listOf(citation())).isEmpty())
    }

    @Test
    fun `有出处就留下`() {
        val kept = MaterialKit.sanitize(
            listOf(
                data("皮尤研究中心，2019"),
                quote("海明威"),
                citation("岸见一郎《被讨厌的勇气》"),
            )
        )
        assertEquals("三条都该留下", 3, kept.size)
    }

    @Test
    fun `出处是空白等于没有出处`() {
        // 模型常常返回 "source": "   " 这种占位。按「有出处」放过去，
        // 界面上就会出现一句孤零零的「—— 」
        assertTrue(MaterialKit.sanitize(listOf(data("   "))).isEmpty())
    }

    @Test
    fun `例子不需要出处`() {
        // 生活里的例子本来就没有出处可引。硬要求出处会把这一整类砍掉，
        // 剩下的全是名人名言——那不像人在说话，像语录集
        val kept = MaterialKit.sanitize(listOf(example()))
        assertEquals(1, kept.size)
        assertEquals(MaterialKind.EXAMPLE, kept[0].kind)
    }

    @Test
    fun `不要降级成例子来保住它`() {
        // 最坏的处理是把一条没出处的「金句」当成「例子」留下来——
        // 用户会以为这句话是他自己想出来的，然后原样讲出去
        val kept = MaterialKit.sanitize(listOf(quote("")))
        assertTrue("没出处的金句不许换个身份活下来", kept.isEmpty())
    }

    // ---------- 清理 ----------

    @Test
    fun `空文本丢掉`() {
        assertTrue(MaterialKit.sanitize(listOf(example(""), data("某研究"))).none { it.text.isBlank() })
    }

    @Test
    fun `文本和出处都去空白`() {
        val kept = MaterialKit.sanitize(listOf(data("  某研究，2020  "))).single()
        assertEquals("某研究，2020", kept.source)
    }

    @Test
    fun `重复的素材只留一条`() {
        val kept = MaterialKit.sanitize(listOf(example(), example()))
        assertEquals("同一条重复出现会让用户以为模型很笃定", 1, kept.size)
    }

    @Test
    fun `种类不同不算重复`() {
        val kept = MaterialKit.sanitize(
            listOf(
                Material(MaterialKind.EXAMPLE, "同一句话", ""),
                Material(MaterialKind.QUOTE, "同一句话", "某人"),
            )
        )
        assertEquals(2, kept.size)
    }

    @Test
    fun `超过上限只保留前面的`() {
        // 太多了就不叫「手边有料」，叫第二篇稿子
        val many = (1..20).map { Material(MaterialKind.EXAMPLE, "第 $it 条", "") }
        assertEquals(MaterialKit.MAX, MaterialKit.sanitize(many).size)
    }

    // ---------- 种类识别 ----------

    @Test
    fun `认得中文的种类名`() {
        assertEquals(MaterialKind.EXAMPLE, MaterialKind.of("例子"))
        assertEquals(MaterialKind.DATA, MaterialKind.of("数据"))
        assertEquals(MaterialKind.QUOTE, MaterialKind.of("金句"))
        assertEquals(MaterialKind.CITATION, MaterialKind.of("引用"))
    }

    @Test
    fun `认不出来当例子`() {
        // 一个错词不该把本来能用的素材整条丢掉。
        // 例子对出处没有要求，是最安全的兜底
        assertEquals(MaterialKind.EXAMPLE, MaterialKind.of("案例"))
        assertEquals(MaterialKind.EXAMPLE, MaterialKind.of(null))
        assertEquals(MaterialKind.EXAMPLE, MaterialKind.of(""))
    }

    @Test
    fun `数字和名言也算认得`() {
        assertEquals(MaterialKind.DATA, MaterialKind.of("数字"))
        assertEquals(MaterialKind.QUOTE, MaterialKind.of("名言"))
    }

    // ---------- 分组 ----------

    @Test
    fun `分组按固定顺序且跳过空组`() {
        val groups = MaterialKit.grouped(listOf(quote("某人"), example()))
        assertEquals(2, groups.size)
        assertEquals("例子的顺序在前", MaterialKind.EXAMPLE, groups[0].first)
        assertEquals(MaterialKind.QUOTE, groups[1].first)
    }

    // ---------- 规则要在界面上说出来 ----------

    /**
     * 编辑态必须把准入规则写出来。
     *
     * 这条守的是一个**已经真实发生过**的坑：`RULE_HINT` 早就写好了，
     * 但没有任何界面引用它——一个死常量，规则只活在代码里。
     *
     * 后果是可预见的：用户在编辑态把某条金句的出处删空，
     * 点「存好」，那条就消失了。`sanitize` 是按设计丢的，
     * 但**用户没有任何线索知道为什么**，也没有撤销。
     *
     * 所以这条只能读源码：Compose 的文案没法在 JVM 单元测试里断言。
     */
    @Test
    fun `编辑素材的界面要写明准入规则`() {
        val src = File(sourceRoot(), "app/src/main/java/com/hatio/speakinspire/" +
            "ui/screens/ConvertTopicScreen.kt").readText()
        assertTrue(
            "编辑态不说明「没有出处的一律不收」，用户会以为自己写的被吞了",
            "MaterialKit.RULE_HINT" in src,
        )
    }

    // ---------- 出处得像出处 ----------

    /**
     * 真机上模型真给过这么一条，原文照抄：
     *
     *     引用：临场组织语言时，大脑优先处理立场安全，表达精度被排到后面。
     *     —— 用户原文，社会心理学关于表达优先级的说法
     *
     * 「出处」字段非空，所以旧的 `hasSource` 放它过去了。
     * 可它指向的是用户自己 + 一个学科名，一个字都查不到。
     * 用户看到「引用 —— 用户原文」，会以为这句话有外部依据，
     * 然后原样对着镜头讲出去，被追问时说不出出处。
     */
    @Test
    fun `指向用户自己的出处不算出处`() {
        val kept = MaterialKit.sanitize(
            listOf(citation("用户原文，社会心理学关于表达优先级的说法")),
        )
        assertTrue("「用户原文」不是出处——引用自己说过的话不叫引用", kept.isEmpty())
    }

    @Test
    fun `没有具体对象的研究不算出处`() {
        listOf("某项研究", "有研究表明", "心理学家认为").forEach { fake ->
            assertTrue("「$fake」查不到任何人", MaterialKit.sanitize(listOf(quote(fake))).isEmpty())
        }
    }

    @Test
    fun `学科名不算出处`() {
        assertTrue(
            MaterialKit.sanitize(listOf(quote("社会心理学关于沉默的说法"))).isEmpty(),
        )
    }

    @Test
    fun `书名号或年份的出处放行`() {
        // 白名单必须在前，否则《社会心理学》会因为带学科名被误杀
        val kept = MaterialKit.sanitize(
            listOf(
                citation("《社会心理学》戴维·迈尔斯"),
                data("皮尤研究中心，2019"),
                quote("海明威"),
            ),
        )
        assertEquals("正常出处一条都不能丢", 3, kept.size)
    }

    @Test
    fun `例子仍然不要求出处`() {
        assertEquals(1, MaterialKit.sanitize(listOf(example())).size)
    }

    @Test
    fun `选题提示里点名禁止了这几种伪出处`() {
        // 代码里那层过滤是兜底，prompt 里不说清楚，每一条都要靠兜底拦——
        // 用户看到的素材会莫名其妙变少
        val p = com.hatio.speakinspire.service.Prompts.OUTLINE_SYSTEM_PROMPT
        listOf("用户原文", "某项研究", "学科名").forEach {
            assertTrue("提示里没点明「$it」，模型会继续这么写出处", p.contains(it))
        }
    }

    /** 从测试进程所在目录往上找仓库根 */
    private fun sourceRoot(): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }
}
