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
    data class Deepening(val noteId: String) : Route
    data object Settings : Route
    data class Compose(val noteId: String) : Route
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

    when (val r = route) {
        Route.Home -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val current = tab ?: MainTab.RECORD
                when (current) {
                    MainTab.RECORD -> RecordScreen(
                        ideas = state.record,
                        onOpen = { id -> route = Route.Deepening(id) },
                        onSettings = { route = Route.Settings },
                    )
                    MainTab.THINK -> ThinkScreen(
                        ideas = state.think,
                        onOpen = { id -> route = Route.Deepening(id) },
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
                recordBadge = state.recordBadge,
                topicBadge = state.topicBadge,
            )
        }

Route.Settings -> {
            val svm: SettingsViewModel = hiltViewModel()
            val sstate by svm.state.collectAsStateWithLifecycle()
            SettingsScreen(
                state = sstate,
                onBack = { route = Route.Home },
                onSelectProvider = { svm.selectProvider(it) },
                onBaseUrl = { svm.updateBaseUrl(it) },
                onModel = { svm.updateModel(it) },
                onApiKey = { svm.updateKey(it) },
                onThinking = { svm.updateThinking(it) },
                onTest = { svm.testConnection() },
                onSave = { svm.save() },
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
                onBack = { route = Route.Home },
                onRetry = { cvm.compose() },
                onToggleSource = { cvm.toggleSourceMode() },
                onSave = { cvm.save() },
                onGoBackToDeepening = { route = Route.Deepening(r.noteId) },
            )
        }

        is Route.Deepening -> {
            val dvm: DeepeningViewModel = hiltViewModel(key = "deepening_${r.noteId}")
            val dstate by dvm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.noteId) { dvm.load(r.noteId) }
            DeepeningScreen(
                state = dstate,
                onBack = { route = Route.Home },
                onSend = { text -> dvm.send(text) },
                onSkip = { dvm.skipTyping() },
                onFinishEarly = { dvm.finishEarly() },
                onCompose = { route = Route.Compose(r.noteId) },
            )
        }

        Route.NewNote -> NewNoteScreen(
            onSave = { title, body, done ->
                vm.createNote(title, body) { done() }
            },
            onBack = { route = Route.Home },
        )
    }
}