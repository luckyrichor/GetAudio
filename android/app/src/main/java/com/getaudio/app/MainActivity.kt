package com.getaudio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.getaudio.app.ui.screen.ExtractScreen
import com.getaudio.app.ui.screen.LibraryScreen
import com.getaudio.app.ui.screen.LoginScreen
import com.getaudio.app.ui.screen.SettingsScreen
import com.getaudio.app.ui.screen.AgentScreen
import com.getaudio.app.ui.theme.GetAudioTheme
import com.getaudio.app.data.repo.Settings

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("音频库", Icons.Default.LibraryMusic),
    EXTRACT("提取", Icons.Default.Link),
    AGENT("助手", Icons.Default.Chat),
    LOGIN("云端登录", Icons.Default.Person),
    SETTINGS("设置", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GetAudioTheme {
                val serverUrl by remember { Settings(applicationContext).serverUrl }.collectAsState(initial = "")
                // 从 B站/抖音「分享到 GetAudio」进来时直接落在提取页
                var shared by remember { mutableStateOf(sharedText(intent)) }
                var tab by remember { mutableStateOf(if (shared != null) Tab.EXTRACT else Tab.LIBRARY) }

                LaunchedEffect(serverUrl) {
                    if (serverUrl.isBlank() && tab == Tab.LOGIN) tab = Tab.LIBRARY
                }

                DisposableEffect(Unit) {
                    val listener = { newIntent: Intent ->
                        sharedText(newIntent)?.let { shared = it; tab = Tab.EXTRACT }
                        Unit
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.filter { it != Tab.LOGIN || serverUrl.isNotBlank() }.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    }
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding)) {
                        when (tab) {
                            Tab.LIBRARY -> LibraryScreen()
                            Tab.EXTRACT -> ExtractScreen(prefill = shared, onSharedConsumed = { shared = null })
                            Tab.AGENT -> AgentScreen()
                            Tab.LOGIN -> LoginScreen()
                            Tab.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

    private fun sharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        } else null
}
