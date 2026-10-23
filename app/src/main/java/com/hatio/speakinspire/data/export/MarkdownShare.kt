package com.hatio.speakinspire.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把 Markdown 交给系统分享。
 *
 * ## 为什么要走分享，而不是「保存到某个固定目录」
 *
 * 应用私有目录里的文件，用户在文件管理器里根本看不见——
 * 「导出成功」之后他找不到东西，等于没导出。
 * 交给系统分享面板，他可以发到微信、邮件、存进网盘、传到电脑，
 * 去哪个山头由他决定，而不是由我们规定一个他打不开的路径。
 *
 * 文件用 [FileProvider] 授权，不申请存储权限——
 * 为了导出一个 md 去要「访问所有文件」，是把用户的犹豫换成自己的方便。
 */
object MarkdownShare {

    /**
     * 写文件并拉起分享面板。
     *
     * @return 写入失败时返回 false，由调用方决定怎么提示
     */
    fun share(context: Context, fileName: String, content: String): Boolean = runCatching {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            // 用 text/plain 而不是 text/markdown：
            // 后者很多接收方不认，分享面板里会一片空白。
            // 文件名带 .md，接收方按扩展名处理，内容不受影响
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "导出 Markdown").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)

    /** 导出文件放在缓存里。系统空间紧张时可被清理，用户也不需要它长期存在 */
    private const val DIR = "exports"
}
