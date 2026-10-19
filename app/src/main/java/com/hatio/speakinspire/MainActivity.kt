package com.hatio.speakinspire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.activity.compose.BackHandler
import com.hatio.speakinspire.ui.detail.NoteDetailViewModel
import com.hatio.speakinspire.ui.screens.NoteDetailScreen
import com.hatio.speakinspire.ui.detail.EditNoteViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hatio.speakinspire.domain.IdeaState
import com.hatio.speakinspire.domain.IdeaStateDeriver
import com.hatio.speakinspire.ui.MainViewModel
import com.hatio.speakinspire.ui.deepening.DeepeningViewModel
import com.hatio.speakinspire.ui.screens.DeepeningScreen
import com.hatio.speakinspire.ui.screens.SettingsScreen
import com.hatio.speakinspire.ui.settings.SettingsViewModel
import com.hatio.speakinspire.ui.screens.ComposeScreen
import com.hatio.speakinspire.ui.compose.ComposeViewModel
import com.hatio.speakinspire.ui.screens.ConvertTopicScreen
import com.hatio.speakinspire.ui.topic.TopicViewModel
import com.hatio.speakinspire.ui.navigation.MainBottomBar
import com.hatio.speakinspire.ui.navigation.MainTab
import com.hatio.speakinspire.ui.screens.NewNoteScreen
import com.hatio.speakinspire.ui.screens.RecordScreen
import com.hatio.speakinspire.ui.screens.StatScreen
import com.hatio.speakinspire.ui.screens.ThinkScreen
import com.hatio.speakinspire.ui.screens.TopicScreen
import com.hatio.speakinspire.ui.theme.SpeakInspireTheme
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

/** 全局路由。追问对话页与设置页在下一阶段接入 */
private sealed interface Route {
    data object Home : Route
    data object NewNote : Route
    data object Settings : Route

    /** 记录条详情——点开记录条先进这里 */
    data class Detail(val noteId: String) : Route

    /** 编辑记录。复用新建页的布局，带 noteId 表示改已有 */
    data class Edit(val noteId: String) : Route

    data class Deepening(val noteId: String) : Route
    data class Compose(val noteId: String) : Route
    data class Convert(val noteId: String) : Route

    /**
     * 从哪来。
     *
     * Android 的返回手势（边缘左滑）走的是「上一层」语义，
     * 所以每个页面必须知道该退回哪——不能一律退回首页，
     * 那会让用户从成文页一路左滑到首页，而不是回到追问页。
     */
    val parent: Route?
        get() = when (this) {
            Home, NewNote -> null
            Settings -> Home
            // 每个带 noteId 的分支里 this 就是该实例，直接取 noteId
            is Detail -> Home
            is Edit -> Detail(noteId)
            is Deepening -> Detail(noteId)
            is Compose -> Deepening(noteId)
            is Convert -> Compose(noteId)
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
                        ideas = state.record,
                        // 先看详情再决定做什么。
                        // 之前直接进追问页，用户看不到自己记了什么，
                        // 也不知道该输入还是该等 AI 问。
                        onOpen = { id -> route = Route.Detail(id) },
                        onSettings = { route = Route.Settings },
                    )
                    MainTab.THINK -> ThinkScreen(
                        ideas = state.think,
                        // 思考库里的条目已经有文章了，直接进成文/来源视图
                        onOpen = { id -> route = Route.Compose(id) },
                    )
                    MainTab.TOPIC -> TopicScreen(
                        ideas = state.topic,
                        onOpen = { id -> route = Route.Deepening(id) },
                        onMarkShot = markShotHandler,
                        onPark = { vm.park(it) },
                        onUndoShot = { vm.undoShot(it) },
                    )
                    MainTab.STAT -> StatScreen(
                        stats = state.stats,
                        pendingCount = state.allIdeas.count {
                            IdeaStateDeriver.derive(it) == IdeaState.DEEPENED
                        },
                    )
                }
            }

            MainBottomBar(
                current = tab,
                onSelect = { tab = it },
                onCreate = { route = Route.NewNote },
            )
        }

        Route.Settings -> {
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
                onTest = { svm.testConnection() },
                onSave = { svm.save() },
                onSavedConsumed = { svm.consumeSavedFlag() },
                onAddModel = { svm.addModel() },
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
                onBack = { route = r.parent ?: Route.Home },
                onEdit = { route = Route.Edit(r.noteId) },
                onDeepen = { route = Route.Deepening(r.noteId) },
                onViewArticle = { route = Route.Compose(r.noteId) },
            )
        }

        is Route.Edit -> {
            val evm: EditNoteViewModel = hiltViewModel(key = "edit_${r.noteId}")
            val estate by evm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) { evm.load(r.noteId) }
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
            val tvm: TopicViewModel = hiltViewModel(key = "topic_${r.noteId}")
            val tstate by tvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) { tvm.load(r.noteId) }
            ConvertTopicScreen(
                state = tstate,
                onBack = { route = r.parent ?: Route.Home },
                onDerive = { tvm.deriveAngles(tstate.articleTitle, emptyList()) },
                onChooseAngle = { tvm.chooseAngle(it) },
                onBackToAngles = { tvm.back() },
                onSave = { tvm.saveScript() },
                onMarkShot = { tvm.markShot() },
                onUndoShot = { tvm.undoShot() },
                onPark = { tvm.park() },
            )
        }

        is Route.Compose -> {
            val cvm: ComposeViewModel = hiltViewModel(key = "compose_${r.noteId}")
            val cstate by cvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) {
                cvm.load(r.noteId)
                if (!cstate.hasContent && !cstate.alreadyComposed) cvm.compose()
            }
            ComposeScreen(
                state = cstate,
                onBack = { route = r.parent ?: Route.Home },
                onRetry = { cvm.compose() },
                onToggleSource = { cvm.toggleSourceMode() },
                onSave = {
                    cvm.save()
                    route = Route.Convert(r.noteId)
                },
                onGoBackToDeepening = { route = Route.Deepening(r.noteId) },
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
                onRetry = { dvm.retryFirstQuestion() },
            )
        }

        Route.NewNote -> NewNoteScreen(
            onSave = { title, body, done ->
                vm.createNote(title, body) { done() }
            },
            onBack = { route = r.parent ?: Route.Home },
        )
    }
}