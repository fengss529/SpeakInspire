package com.hatio.speakinspire.data.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 把导出文件写进「手机的下载目录」。
 *
 * ## 为什么不再走分享面板
 *
 * 分享面板的语义是「把这一份发给别人」。可用户真正的需求是
 * **「留一份在我自己手里」**——换手机、重装、清数据之前先存出来。
 * 走分享的话，他得先想清楚发给谁，而「发给文件管理器」这件事
 * 在很多机型上根本没有入口。
 *
 * 写进 `下载/随手记/` 之后，手机自带的文件管理器一打开就看得见，
 * 插上电脑也是一个普通文件夹。这是最不需要解释的位置。
 *
 * ## 两条路：MediaStore 与直接写文件
 *
 * Android 10（API 29）起有了分区存储，往公共目录写文件**不用权限**，
 * 走 MediaStore 就行。API 26–28 还得申请 `WRITE_EXTERNAL_STORAGE`，
 * 并且要自己建目录。
 *
 * 分叉是必须的：只有 API 29+ 能用 MediaStore，也只有 29+ 才能不用权限。
 * 写成一条路的话，要么在旧机上崩，要么在新机上白要一个权限。
 *
 * ## 重名不覆盖
 *
 * 同名文件在的话 MediaStore 会自动改成 `data (1).json`。
 * 这是**要的行为**：用户可能是想再存一份，覆盖掉上一次的备份
 * 属于无声地毁掉他唯一的退路。
 */
object DownloadStore {

    /** 下载目录下的子文件夹名 */
    const val ALBUM = "随手记"

    sealed interface Result {
        /** 写成功了。[folder] 是给用户看的路径，[names] 是实际文件名 */
        data class Ok(val folder: String, val names: List<String>) : Result

        /** API 28 及以下、用户还没给存储权限 */
        data object NeedPermission : Result

        /** 写失败了。[detail] 要能说出口，不能只说「导出失败」 */
        data class Failed(val detail: String) : Result
    }

    fun write(context: Context, files: List<Pair<String, String>>): Result {
        if (files.isEmpty()) return Result.Failed("没有可导出的内容")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, files)
        } else {
            writeViaFile(context, files)
        }
    }

    /** 旧系统才需要问权限。新系统上永远返回 true */
    fun needsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    }

    // ---------- API 29+ ----------

    private fun writeViaMediaStore(
        context: Context,
        files: List<Pair<String, String>>,
    ): Result {
        val resolver = context.contentResolver
        val names = mutableListOf<String>()
        return try {
            files.forEach { (name, content) ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM",
                    )
                    // 先占位、写完再放出来。不然别的 App 可能读到写了一半的文件
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.Failed("系统不允许在下载目录里新建文件")

                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: return Result.Failed("文件建好了但打不开写入通道")

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                names += name
            }
            Result.Ok("下载/$ALBUM", names)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- API 26–28 ----------

    private fun writeViaFile(
        context: Context,
        files: List<Pair<String, String>>,
    ): Result {
        if (needsPermission(context)) return Result.NeedPermission
        return try {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(root, ALBUM).apply { if (!exists()) mkdirs() }
            if (!dir.isDirectory) return Result.Failed("建不出 ${dir.absolutePath}")

            val names = files.map { (name, content) ->
                val target = uniqueName(dir, name)
                File(dir, target).writeText(content, Charsets.UTF_8)
                target
            }
            Result.Ok("下载/$ALBUM", names)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 旧系统上同名会直接覆盖，这里手动往后排号 */
    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (File(dir, "$stem ($i)$ext").exists()) i++
        return "$stem ($i)$ext"
    }

    /** 扩展名决定 MIME。给错了系统相册/文档列表里会显示成「未知文件」 */
    private fun mimeOf(name: String): String = when {
        name.endsWith(".json", ignoreCase = true) -> "application/json"
        name.endsWith(".md", ignoreCase = true) -> "text/markdown"
        else -> "application/octet-stream"
    }
}
