package com.hatio.speakinspire.data.repository

import android.content.Context
import com.hatio.speakinspire.service.DraftStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 成文风格的选择，以及叠加在预设之上的「补充写作要求」。
 *
 * ## 和追问方法为什么长一个样
 *
 * 同一类东西：一个预设 + 一段可改的文字 + 一条不可改的底线。
 * 用户在设置页里不用学两套交互——看到同样的「选一个 + 改一段 +
 * 下面灰字不许动」，他就知道怎么用了。
 *
 * ## 为什么补充要求是「叠加」而不是「替换」
 *
 * 追问那边用户改的是**手法**，改掉了就是他自己那套，预设不再生效。
 * 风格这边不一样：预设里写的是结构（观点文要有主张、清单要可执行），
 * 用户想改的通常只是语气（「再口语一点」「别用感叹号」）。
 *
 * 所以这里存的是**叠加在预设之后的一段附加要求**，预设那套结构仍然生效。
 * 如果他真的想换结构，那就该去选另一种风格，而不是把预设改没。
 *
 * 底线（[com.hatio.speakinspire.service.Prompts.DRAFT_FORBIDDEN]）
 * 不在这段里，改不了。
 */
interface DraftStyleStore {

    /** 当前选中的默认风格 */
    fun current(): DraftStyle

    fun select(style: DraftStyle)

    /**
     * 当前风格的补充写作要求。没写过时返回空串。
     *
     * 这里**返回空串而不是预设内容**——和追问那边的 `instructionOf`
     * 不同：那边用户改的就是手法本身，所以预填；这边预设写在
     * [DraftStyle.instruction] 里、始终生效，输入框是「额外再加点什么」，
     * 预填会把预设复制一遍，用户改一处就变成两处打架。
     */
    fun noteOf(): String

    /** 保存补充要求。传空白表示清掉 */
    fun saveNote(text: String)
}

@Singleton
class SharedPrefsDraftStyleStore @Inject constructor(
    @ApplicationContext context: Context,
) : DraftStyleStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun current(): DraftStyle = DraftStyle.byId(prefs.getString(KEY_STYLE, null))

    override fun select(style: DraftStyle) {
        prefs.edit().putString(KEY_STYLE, style.id).apply()
    }

    override fun noteOf(): String = prefs.getString(KEY_NOTE, null).orEmpty()

    override fun saveNote(text: String) {
        prefs.edit().apply {
            if (text.isBlank()) remove(KEY_NOTE) else putString(KEY_NOTE, text.trim())
        }.apply()
    }

    private companion object {
        const val FILE = "draft_settings"
        const val KEY_STYLE = "draft_style"
        const val KEY_NOTE = "draft_style_note"
    }
}

/** 内存实现。测试与降级用 */
class InMemoryDraftStyleStore(
    private var style: DraftStyle = DraftStyle.DEFAULT,
    private var note: String = "",
) : DraftStyleStore {

    override fun current(): DraftStyle = style

    override fun select(style: DraftStyle) {
        this.style = style
    }

    override fun noteOf(): String = note

    override fun saveNote(text: String) {
        note = if (text.isBlank()) "" else text.trim()
    }
}
