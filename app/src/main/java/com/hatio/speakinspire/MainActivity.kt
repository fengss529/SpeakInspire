package com.hatio.speakinspire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hatio.speakinspire.ui.MainViewModel
import com.hatio.speakinspire.ui.compose.ComposeViewModel
import com.hatio.speakinspire.ui.deepening.DeepeningViewModel
import com.hatio.speakinspire.ui.detail.EditNoteViewModel
import com.hatio.speakinspire.ui.detail.NoteDetailViewModel
import com.hatio.speakinspire.ui.navigation.MainBottomBar
import com.hatio.speakinspire.ui.navigation.MainTab
import com.hatio.speakinspire.ui.screens.ComposeScreen
import com.hatio.speakinspire.ui.screens.ConvertTopicScreen
import com.hatio.speakinspire.ui.screens.DeepeningScreen
import com.hatio.speakinspire.ui.screens.NewNoteScreen
import com.hatio.speakinspire.ui.screens.NoteDetailScreen
import com.hatio.speakinspire.ui.screens.RecordScreen
import com.hatio.speakinspire.ui.screens.SettingsScreen
import com.hatio.speakinspire.ui.screens.SproutScreen
import com.hatio.speakinspire.ui.screens.StatScreen
import com.hatio.speakinspire.ui.screens.ThinkScreen
import com.hatio.speakinspire.ui.screens.TopicScreen
import com.hatio.speakinspire.ui.screens.TrashScreen
import com.hatio.speakinspire.ui.trash.TrashViewModel
import com.hatio.speakinspire.ui.settings.SettingsViewModel
import com.hatio.speakinspire.ui.sprout.SproutViewModel
import com.hatio.speakinspire.ui.theme.SpeakInspireTheme
import com.hatio.speakinspire.ui.topic.TopicViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpeakInspireTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

/**
 * 全局路由。
 *
 * ## 每个页面都要知道「该退回哪」
 *
 * Android 的返回手势（边缘左滑）走的是「上一层」语义。
 * 这里没有用 Navigation 库——路由只有七八个目标、没有深链、
 * 没有跨进程恢复的需求，一个 sealed class 足够，
 * 而且每个分支的 [parent] 是显式写出来的，比图里绕一圈好读。
 *
 * ## 设置页带 [Settings.from]
 *
 * 设置现在从任何页面都能进（顶栏的齿轮）。若 parent 固定成 Home，
 * 用户从追问页进去、按返回会掉回首页，还得再点两下才回到刚才那页。
 * 所以把来源带在身上。
 */
private sealed interface Route {
    data object Home : Route

    /**
     * 新建记录。
     *
     * [parentId] 非空表示这条是从某条记录「长」出来的
     * （发芽报告里点「接着这句记一条」），会写进 note.parentId。
     * [returnTo] 是取消时该退回哪——从发芽页来的，退回发芽页。
     */
    data class NewNote(
        val returnTo: Route? = null,
        val parentId: String? = null,
        val prefillTitle: String = "",
        val prefillBody: String = "",
    ) : Route

    /** [from] 决定返回键回哪一页 */
    data class Settings(val from: Route? = null) : Route

    /** 回收站。从设置页进，返回也回设置页 */
    data object Trash : Route

    /** 记录条详情——点开记录条先进这里 */
    data class Detail(val noteId: String) : Route

    /** 编辑记录。复用新建页的布局，带 noteId 表示改已有 */
    data class Edit(val noteId: String) : Route

    data class Deepening(val noteId: String) : Route

    /**
     * 文章视图。
     *
     * [fromHome] 区分入口：从思考 tab 直接点开的是**已经存过的文章**，
     * 返回该回列表；从追问页走上来的，返回该回追问页。
     */
    data class Compose(val noteId: String, val fromHome: Boolean = false) : Route

    /**
     * 直接按文章 id 打开一篇已存在的文章。从「思考」tab 进来走这条。
     *
     * 文章独立之后它可能没有来源记录，用 noteId 反查会打不开。
     */
    data class Article(val articleId: String) : Route

    /** 发芽报告 */
    data class Sprout(val noteId: String) : Route

    /**
     * 拍摄骨架页。
     *
     * [articleId] 是文章 id，不是记录 id——选题挂在文章上。
     * [fromHome] 区分入口：从选题 tab 直接点进来的，返回该回选题列表；
     * 从文章页一路走下来的，返回该回文章页。
     */
    data class Convert(val articleId: String) : Route

    /** 单独打开一个已有选题的骨架。从选题 tab 点卡片进来走这条 */
    data class Topic(val scriptId: String) : Route

    /** 每一页的「上一层」 */
    val parent: Route?
        get() = when (this) {
            Home -> null
            is NewNote -> returnTo
            is Settings -> from
            Trash -> Settings(Home)
            is Detail -> Home
            is Edit -> Detail(noteId)
            is Deepening -> Detail(noteId)
            is Compose -> if (fromHome) Home else Deepening(noteId)
            is Article -> Home
            is Sprout -> Detail(noteId)
            is Convert -> Home
            is Topic -> Home
        }
}

@Composable
private fun AppRoot(vm: MainViewModel = hiltViewModel()) {
    var tab by remember { mutableStateOf<MainTab?>(MainTab.RECORD) }
    var route by remember { mutableStateOf<Route>(Route.Home) }

    val state by vm.uiState.collectAsStateWithLifecycle()

    // 显式声明类型：嵌套 lambda 会让推断失败
    val markShotHandler: (String, () -> Unit) -> Unit = { id, onOk ->
        vm.markShot(id) { onOk() }
    }

    // 从任意页进设置。传当前 route 当来源，返回才能回到原处
    val openSettings: () -> Unit = { route = Route.Settings(route) }

    // Android 的边缘左滑与系统返回键走同一条路径。
    // 不声明的话，系统会认为「这是根页面」，
    // 左滑直接把 Activity 关掉——用户说「左滑退出应用」就是这个。
    BackHandler(enabled = route != Route.Home) {
        route = route.parent ?: Route.Home
    }

    when (val r = route) {
        Route.Home -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val current = tab ?: MainTab.RECORD
                when (current) {
                    MainTab.RECORD -> RecordScreen(
                        notes = state.record,
                        // 先看详情再决定做什么。
                        // 之前直接进追问页，用户看不到自己记了什么，
                        // 也不知道该输入还是该等 AI 问。
                        onOpen = { id -> route = Route.Detail(id) },
                        onSettings = openSettings,
                        onDelete = { vm.moveNoteToTrash(it) },
                    )
                    MainTab.THINK -> ThinkScreen(
                        articles = state.think,
                        // 文章是独立资源，按自己的 id 打开——
                        // 来源记录被删掉的文章也能进得去
                        onOpen = { id -> route = Route.Article(id) },
                        onSettings = openSettings,
                        onDelete = { vm.moveArticleToTrash(it) },
                    )
                    MainTab.TOPIC -> TopicScreen(
                        topics = state.topic,
                        // 点开的是这个选题自己的骨架，不是再跟 AI 聊一轮
                        onOpen = { id -> route = Route.Topic(id) },
                        onMarkShot = markShotHandler,
                        onPark = { vm.park(it) },
                        onUndoShot = { vm.undoShot(it) },
                        onUnpark = { vm.unpark(it) },
                        onSettings = openSettings,
                        onDelete = { vm.moveTopicToTrash(it) },
                    )
                    MainTab.STAT -> StatScreen(
                        stats = state.stats,
                        pendingCount = state.pendingCount,
                        onSettings = openSettings,
                    )
                }
            }

            MainBottomBar(
                current = tab,
                onSelect = { tab = it },
                onCreate = { route = Route.NewNote() },
            )
        }

        is Route.Settings -> {
            val svm: SettingsViewModel = hiltViewModel()
            val sstate by svm.state.collectAsStateWithLifecycle()
            SettingsScreen(
                state = sstate,
                onBack = { route = r.parent ?: Route.Home },
                onSelectProvider = { svm.selectProvider(it) },
                onSelectModel = { svm.selectModel(it) },
                onBaseUrl = { svm.updateBaseUrl(it) },
                onModel = { svm.updateModel(it) },
                onApiKey = { svm.updateKey(it) },
                onThinking = { svm.updateThinking(it) },
                onSelectProbeMethod = { svm.selectProbeMethod(it) },
                onSaveProbeInstruction = { svm.saveProbeInstruction(it) },
                onTest = { svm.testConnection() },
                onSave = { svm.save() },
                onSavedConsumed = { svm.consumeSavedFlag() },
                onAddModel = { svm.addModel() },
                onCommitModel = { svm.commitNewModel(it) },
                onCancelAddModel = { svm.cancelAddModel() },
                onExportAll = { svm.exportAll() },
                onExportMessageConsumed = { svm.consumeExportMessage() },
                onExportDoneConsumed = { svm.consumeExportDone() },
                onOpenTrash = { route = Route.Trash },
            )
        }

        Route.Trash -> {
            val tvm: TrashViewModel = hiltViewModel()
            val items by tvm.items.collectAsStateWithLifecycle()
            TrashScreen(
                items = items,
                onBack = { route = r.parent ?: Route.Home },
                onRestore = { tvm.restore(it) },
                onPurge = { tvm.purge(it) },
            )
        }

        is Route.Detail -> {
            val nvm: NoteDetailViewModel = hiltViewModel(key = "detail_${r.noteId}")
            val nstate by nvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) { nvm.load(r.noteId) }
            NoteDetailScreen(
                title = nstate.title,
                body = nstate.body,
                createdLabel = nstate.createdLabel,
                rounds = nstate.rounds,
                hasArticle = nstate.hasArticle,
                hasDeepened = nstate.hasDeepened,
                sproutLabel = nstate.sproutLabel,
                latestSproutTitle = nstate.latestSproutTitle,
                exportMessage = nstate.exportMessage,
                onBack = { route = r.parent ?: Route.Home },
                onEdit = { route = Route.Edit(r.noteId) },
                onDeepen = { route = Route.Deepening(r.noteId) },
                onSprout = { route = Route.Sprout(r.noteId) },
                // 文章 id 来自详情页状态，不在这里拼——
                // 拼错一个字符就是一个打不开的页面
                onViewArticle = {
                    nstate.articleId?.let { route = Route.Article(it) }
                },
                onSettings = openSettings,
                onShare = { nvm.shareNote() },
                onDismissMessage = { nvm.consumeExportMessage() },
            )
        }

        is Route.Edit -> {
            // 用 route 里的一次性 id 做 key，
            // 而不是固定的 "edit_$noteId"——
            // 固定 key 会让 hiltViewModel 复用同一个实例，
            // 保存后返回详情页、再进编辑页时拿到的还是旧状态，
            // load() 又因 noteId 相同直接 return，于是显示旧内容。
            //
            // 每次进入编辑都是新的编辑会话，用递增的 sessionId 隔离。
            val sessionId = remember(r.noteId) { System.nanoTime() }
            val evm: EditNoteViewModel = hiltViewModel(key = "edit_$sessionId")
            val estate by evm.state.collectAsStateWithLifecycle()

            LaunchedEffect(sessionId) { evm.load(r.noteId) }

            NewNoteScreen(
                initialTitle = estate.title,
                initialBody = estate.body,
                saving = estate.saving,
                onSave = { title, body, done ->
                    evm.save(title, body) {
                        done()
                        route = Route.Detail(r.noteId)
                    }
                },
                onBack = { route = r.parent ?: Route.Home },
            )
        }

        is Route.Convert -> {
            val tvm: TopicViewModel = hiltViewModel(key = "topic_${r.articleId}")
            val tstate by tvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.articleId) { tvm.load(r.articleId) }
            ConvertTopicScreen(
                state = tstate,
                onBack = { route = r.parent ?: Route.Home },
                // 重试要重新读正文——只拿标题去拆角度，拆出来的东西没有依据
                onDerive = { tvm.rederive() },
                onChooseAngle = { tvm.chooseAngle(it) },
                onBackToAngles = { tvm.back() },
                onSave = { tvm.saveScript() },
                onMarkShot = { tvm.markShot() },
                onUndoShot = { tvm.undoShot() },
                onPark = { tvm.park() },
            )
        }

        is Route.Article -> {
            val cvm: ComposeViewModel = hiltViewModel(key = "article_${r.articleId}")
            val cstate by cvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.articleId) { cvm.loadArticle(r.articleId) }
            ComposeScreen(
                state = cstate,
                onBack = { route = r.parent ?: Route.Home },
                onRetry = { cvm.compose() },
                onToggleSource = { cvm.toggleSourceMode() },
                onSave = { cvm.save() },
                // 来源记录可能已被删掉，这时「回去再聊」无处可去
                canReopenDeepening = cstate.noteId.isNotBlank(),
                onGoBackToDeepening = {
                    if (cstate.noteId.isNotBlank()) route = Route.Deepening(cstate.noteId)
                },
                onConvert = { route = Route.Convert(cstate.articleId) },
                onShare = { cvm.shareArticle() },
                onSettings = openSettings,
            )
        }

        is Route.Compose -> {
            val cvm: ComposeViewModel = hiltViewModel(key = "compose_${r.noteId}")
            val cstate by cvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) {
                // 自动成文的时机必须交给 ViewModel。
                //
                // 这里曾经是 `load(id)` 然后立刻判断 `cvm.state.value.hasContent`——
                // 但 load 内部是 viewModelScope.launch，调用返回时状态还没写进去，
                // 于是必然判断成「没有文章」，于是 compose() 在 noteId 还是空串时发出，
                // findIdea("") 返回 null，静默退出。
                // 表现：成文页停在「还没开始」，要用户再点一次。
                cvm.load(r.noteId, autoCompose = true)
            }
            ComposeScreen(
                state = cstate,
                onBack = { route = r.parent ?: Route.Home },
                onRetry = { cvm.compose() },
                onToggleSource = { cvm.toggleSourceMode() },
                // 存完**不跳走**：留在文章页，把「接下来干什么」交给他
                onSave = { cvm.save() },
                canReopenDeepening = true,
                onGoBackToDeepening = { route = Route.Deepening(cstate.noteId) },
                // 转选题要按**文章** id 走：选题挂在文章上，不挂在记录上
                onConvert = { route = Route.Convert(cstate.articleId) },
                onShare = { cvm.shareArticle() },
                onSettings = openSettings,
            )
        }

        is Route.Topic -> {
            val tvm: TopicViewModel = hiltViewModel(key = "topic_script_${r.scriptId}")
            val tstate by tvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.scriptId) { tvm.loadTopic(r.scriptId) }
            ConvertTopicScreen(
                state = tstate,
                onBack = { route = r.parent ?: Route.Home },
                // 已经有一条选题了，重拆角度的意义不大，
                // 但保留入口：用户可能想换个角度再存一条
                onDerive = { tvm.rederive() },
                onChooseAngle = { tvm.chooseAngle(it) },
                onBackToAngles = { tvm.back() },
                onSave = { tvm.saveScript() },
                onMarkShot = { tvm.markShot() },
                onUndoShot = { tvm.undoShot() },
                onPark = { tvm.park() },
            )
        }

        is Route.Deepening -> {
            val dvm: DeepeningViewModel = hiltViewModel(key = "deepening_${r.noteId}")
            val dstate by dvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) { dvm.load(r.noteId) }
            DeepeningScreen(
                state = dstate,
                onBack = { route = r.parent ?: Route.Home },
                onSend = { text -> dvm.send(text) },
                onSkip = { dvm.skipTyping() },
                onFinishEarly = { dvm.finishEarly() },
                onCompose = { route = Route.Compose(r.noteId) },
                // 「先留着」= 收尾 + 回详情，不写文章
                onKeep = {
                    dvm.finishEarly()
                    route = Route.Detail(r.noteId)
                },
                onRetry = { dvm.retryFirstQuestion() },
                onReopen = { dvm.reopen() },
                // 改一句自己说过的话。下标指的是它在 rounds 里的位置
                onUpdateAnswer = { index, text -> dvm.updateAnswer(index, text) },
                // 改最初那条记录。追问页里直接给入口，
                // 不必退出去详情页找——用户此刻正好知道该改哪句
                onEditOrigin = { route = Route.Edit(r.noteId) },
                onSettings = openSettings,
            )
        }

        is Route.Sprout -> {
            val spvm: SproutViewModel = hiltViewModel(key = "sprout_${r.noteId}")
            val spstate by spvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) { spvm.load(r.noteId) }
            SproutScreen(
                state = spstate,
                onBack = { route = r.parent ?: Route.Home },
                onSprout = { spvm.sprout() },
                onSettings = openSettings,
                // 顺着某一节再记一条：新记录挂在原记录下（parentId），
                // 「记录 → 发芽 → 新记录 → 再发芽」这条生长的线才连得起来
                onSeedNewNote = { insight ->
                    route = Route.NewNote(
                        returnTo = Route.Sprout(r.noteId),
                        parentId = r.noteId,
                        prefillBody = insight,
                    )
                },
                onReview = { spvm.review(it) },
                onDismissError = { spvm.dismissError() },
            )
        }

        is Route.NewNote -> NewNoteScreen(
            initialTitle = r.prefillTitle,
            initialBody = r.prefillBody,
            onSave = { title, body, done ->
                vm.createNote(title, body, r.parentId) { newId ->
                    done()
                    // 不管从哪来，新建完直接进新记录的详情页——
                    // 用户刚写完，最想看的是「它长什么样、能做什么」
                    route = if (newId.isNotBlank()) Route.Detail(newId) else Route.Home
                }
            },
            onBack = { route = r.parent ?: Route.Home },
        )
    }
}
