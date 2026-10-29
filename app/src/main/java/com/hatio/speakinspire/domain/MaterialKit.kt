package com.hatio.speakinspire.domain

import com.hatio.speakinspire.data.model.Material
import com.hatio.speakinspire.data.model.MaterialKind

/**
 * 素材的准入规则。
 *
 * ## 为什么要有这一层，而不是「prompt 里说过了」
 *
 * prompt 里写着「没有出处就不要给」，但模型会照给——
 * 它编一个「某项研究显示」太容易了，而且看起来很像真的。
 * 成文那边早就有过同一个教训：让模型自己拒稿，它把拒稿理由
 * 写成了一个正常文章的形状，App 于是把它当正文渲染。
 *
 * 判断「这条有没有出处」是我们自己数得清的事，不该交给模型的自觉。
 *
 * ## 唯一的例外是例子
 *
 * 生活里的例子（「我上次跟客户吃饭也是这样」）本来就没有出处可引。
 * 硬要求出处，等于把这一整类素材全砍掉，剩下的全是名人名言——
 * 那不像人在说话，像语录集。
 *
 * 所以规则按种类分：**需要出处的那三种，缺了整条丢弃，不降级、不改写。**
 * 把「金句」降级成「例子」留着用，是最坏的处理——用户会以为
 * 这句话是他自己想出来的，然后原样讲出去。
 */
object MaterialKit {

    /** 一次最多留几条。太多了就不叫「手边有料」，叫第二篇稿子 */
    const val MAX = 8

    /**
     * 过滤。
     *
     * @param raw 模型给的原样素材，可能带空条目、可能缺出处
     * @return 可以展示、可以进拍摄卡片的那些
     */
    fun sanitize(raw: List<Material>): List<Material> {
        val seen = mutableSetOf<String>()
        return raw.asSequence()
            .map { m ->
                Material(
                    kind = m.kind,
                    text = m.text.trim(),
                    source = m.source.trim(),
                )
            }
            .filter { it.text.isNotEmpty() }
            // 没有出处的那三类，整条丢掉。宁缺毋滥
            .filter { !it.kind.needsSource || it.hasRealSource }
            // 同一条素材重复出现，用户会以为模型很笃定
            .filter { seen.add("${it.kind.name}|${it.text}") }
            .take(MAX)
            .toList()
    }

    /**
     * 出处是不是**真出处**。
     *
     * ## 为什么 [Material.hasSource] 不够
     *
     * 那个只问「填没填」。真机上模型给过这么一条：
     *
     *     引用：临场组织语言时，大脑优先处理立场安全……
     *     —— 用户原文，社会心理学关于表达优先级的说法
     *
     * 字面上有出处，实际上指向的是用户自己 + 一个学科名。
     * 用户看到「引用 —— 用户原文」会以为这话有外部依据，
     * 然后原样对着镜头讲出去。这比空着更坏：空着他知道没有。
     *
     * 所以这里再问一层：出处得是**别人能查到的形状**。
     * 判据只有两条，故意保守——宁可放过一条含糊的，
     * 也不要把「《反脆弱》塔勒布」这种正常出处误杀：
     *
     * 1. 不能指向用户自己（「用户原文」「他自己说过的」）
     * 2. 不能是「某项研究」「心理学认为」这种没有具体对象的说法
     *
     * @see hasRealSourceText 判定逻辑本身，测试直接打它
     */
    val Material.hasRealSource: Boolean
        get() = hasSource && hasRealSourceText(source)

    /** 见 [Material.hasRealSource] */
    fun hasRealSourceText(source: String): Boolean {
        val s = source.trim()
        // 一个字算不上出处
        if (s.length < MIN_SOURCE_LENGTH) return false
        // 带书名号或四位年份的，形态上就是别人查得到的，直接放行。
        // 这条白名单必须在前——不然「《社会心理学》戴维·迈尔斯」
        // 会因为里面有个学科名被下面的词表误杀
        if (s.contains("《") || s.contains("》") || YEAR.containsMatchIn(s)) return true
        return PSEUDO_SOURCE_MARKERS.none { s.contains(it) }
    }

    private val YEAR = Regex("(19|20)\\d{2}")

    /**
     * 出处至少两个字。
     *
     * 只用来挡住一个字这种明显没写完的东西。**不能定得更长**：
     * 「海明威」三个字就是一条完整、可核查的出处，
     * 按字数卡会把它误杀——出处的好坏靠词表判，不靠长度。
     */
    const val MIN_SOURCE_LENGTH = 2

    /**
     * 这些词出现在出处里，说明它指向的不是一个来源。
     *
     * 每一条都对应真机上见过或 prompt 里明令禁止的写法，
     * 不是想当然列出来的。
     */
    private val PSEUDO_SOURCE_MARKERS = listOf(
        // 指向用户自己
        "用户原文", "用户所说", "用户说的", "他自己说", "本人的话",
        "我的记录", "这条记录", "本条记录", "记录里",
        // 没有具体对象的研究
        "某项研究", "有研究", "研究表明", "心理学家认为", "专家表示",
        "心理学认为", "心理学中", "心理学里",
        // 学科名当出处——学科不是来源。带书名号的那种在上面已经放行
        "社会心理学", "传播学",
        // 名称当出处
        "不详", "未知", "暂无", "原文",
    )

    /** 编辑之后的收尾：清空的行、超量的部分 */
    fun tidy(raw: List<Material>): List<Material> = sanitize(raw)

    /** 按种类分组，交付界面按「例子 / 数据 / 金句 / 引用」排 */
    fun grouped(list: List<Material>): List<Pair<MaterialKind, List<Material>>> =
        MaterialKind.entries.mapNotNull { kind ->
            list.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it }
        }

    /**
     * 界面上「这条为什么被丢了」的说明。设置页不放这条——
     * 它不可覆盖，不是一个选项。
     */
    const val RULE_HINT: String =
        "数据、金句、引用没有出处的一律不收，出处得能查到（书名、作者、机构＋年份）；" +
            "「用户原文」这种不算出处。例子可以没有。"
}
