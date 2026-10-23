package com.hatio.speakinspire.data.export

import com.hatio.speakinspire.domain.IdeaView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把一条记录导出成 Markdown。
 *
 * ## 为什么是 Markdown
 *
 * 这是唯一一个「十年后还打得开」的格式：纯文本、无私有结构、
 * 任何编辑器都认。用户把想法记在这个 App 里，最怕的是
 * 「哪天这个 App 不做了，我说过的话就没了」。
 * 能导成 md，这份担心才真正解除。
 *
 * 所以导出必须是**完整**的，不是摘要：
 * 原始记录、每一轮问答、文章全文、发芽报告、选题骨架，
 * 一个都不能少。少一样，用户就得回头来 App 里找——那这次导出就没意义。
 *
 * ## 为什么不做 YAML front matter
 *
 * Obsidian 之类会读它，但对其他工具就是噪音。
 * 标题用一级 `#`、时间用引用行，是所有人都能看懂的最小公约数。
 */
object MarkdownExporter {

    /** 单条记录 → 一个 md 文档 */
    fun note(view: IdeaView): String = buildString {
        val n = view.note
        appendLine("# ${n.title.ifBlank { "（无标题）" }}")
        appendLine()
        appendLine("> ${formatTime(n.createdAt)} · ${n.source.label}")
        appendLine()

        if (n.body.isNotBlank()) {
            appendLine(n.body.trim())
            appendLine()
        }

        appendRounds(this, view)
        appendArticle(this, view)
        appendSprouts(this, view)
        appendScripts(this, view)
    }

    /** 「导出全部」：一份文档里按时间倒序排所有记录 */
    fun all(views: List<IdeaView>): String = buildString {
        appendLine("# 随手记 · 全部分享")
        appendLine()
        appendLine("> 导出于 ${formatTime(System.currentTimeMillis())} · 共 ${views.size} 条")
        appendLine()
        appendLine("---")
        appendLine()
        for (v in views) {
            appendLine(note(v).trimEnd())
            appendLine()
            appendLine("---")
            appendLine()
        }
    }

    // ---------- 分段 ----------

    private fun appendRounds(sb: StringBuilder, view: IdeaView) {
        val rounds = view.deepening?.rounds?.filter { it.answered }.orEmpty()
        if (rounds.isEmpty()) return
        sb.appendLine("## 追问")
        sb.appendLine()
        rounds.forEachIndexed { i, r ->
            sb.appendLine("**${i + 1}. ${r.question.trim()}**")
            sb.appendLine()
            sb.appendLine(r.answer?.trim().orEmpty())
            sb.appendLine()
        }
    }

    private fun appendArticle(sb: StringBuilder, view: IdeaView) {
        val a = view.article ?: return
        sb.appendLine("## 文章：${a.title}")
        sb.appendLine()
        a.paragraphs.forEach { p ->
            sb.appendLine(p.text.trim())
            sb.appendLine()
        }
        // 正文保持干净（用户会拿它二次编辑），
        // 但「哪几段是 AI 补的」这条信息不能丢——单独列在最后一行。
        val aiParagraphs = a.paragraphs
            .withIndex()
            .filter { !it.value.mine }
            .map { it.index + 1 }
        if (aiParagraphs.isNotEmpty()) {
            sb.appendLine("> 来源标注：第 ${aiParagraphs.joinToString("、")} 段为 AI 补充的论证，其余为用户原话。")
            sb.appendLine()
        }
    }

    private fun appendSprouts(sb: StringBuilder, view: IdeaView) {
        if (view.sprouts.isEmpty()) return
        // 按时间正序导出：读起来是一条「这颗种子怎么长」的线
        view.sprouts.sortedBy { it.createdAt }.forEach { sp ->
            sb.appendLine("## 发芽 · ${formatTime(sp.createdAt)}：${sp.title}")
            sb.appendLine()
            sp.sections.forEach { sec ->
                if (sec.seed.isNotBlank()) {
                    sb.appendLine("> 你说的：${sec.seed.trim()}")
                    sb.appendLine()
                }
                sb.appendLine(sec.insight.trim())
                sb.appendLine()
                if (sec.hasSource) {
                    // 有出处才写。没出处就空着，不在导出时替它补一个
                    sb.appendLine("—— ${sec.source.trim()}")
                    sb.appendLine()
                }
            }
        }
    }

    private fun appendScripts(sb: StringBuilder, view: IdeaView) {
        val live = view.scripts.filterNot { it.parked }
        if (live.isEmpty()) return
        sb.appendLine("## 选题")
        sb.appendLine()
        live.forEach { script ->
            val shot = if (script.shotAt != null) "（已拍）" else ""
            sb.appendLine("### ${script.angle}$shot")
            sb.appendLine()
            script.sections.forEach { s ->
                sb.appendLine("- **${s.title}** · ${s.seconds} 秒")
                s.phrases.forEach { p -> sb.appendLine("  - $p") }
                s.cues.forEach { c -> sb.appendLine("  - _${c.icon}：${c.text}_") }
            }
            if (script.note.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("备注：${script.note.trim()}")
            }
            sb.appendLine()
        }
    }

    // ---------- 工具 ----------

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)

    fun formatTime(ms: Long): String = timeFmt.format(Date(ms))

    /**
     * 文件名。
     *
     * 去掉路径分隔符和控制字符——标题是用户随手写的，
     * 里面完全可能有 `/` 或换行，直接当文件名会写失败。
     * 全角的 `：？` 一并换掉：它们本身合法，但留在文件名里
     * 会被各家文件管理器、网盘当成残缺字符处理，看着像坏了。
     */
    fun fileName(title: String, stamp: Long = System.currentTimeMillis()): String {
        val safe = title.trim()
            .replace(Regex("""[\\/:*?"<>|\n\r\t：？＊｜]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
            // 末尾的点和空格要去掉：Windows 上以点结尾的文件名非法，
            // 用户把导出的 .md 拷回电脑时会失败
            .trimEnd('.', ' ')
            .ifBlank { "随手记" }
        return "$safe-${fileFmt.format(Date(stamp))}.md"
    }
}
