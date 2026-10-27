package com.getaudio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.getaudio.app.player.PlayerHolder
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.getaudio.app.ui.components.BackgroundLayer
import com.getaudio.app.ui.components.LocalBackgroundEnabled
import com.getaudio.app.ui.screen.ExtractScreen
import com.getaudio.app.ui.screen.LibraryScreenV2
import com.getaudio.app.ui.screen.MyScreen
import com.getaudio.app.ui.screen.QueueBar
import com.getaudio.app.ui.theme.GetAudioTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("音频库", Icons.Default.LibraryMusic),
    EXTRACT("提取", Icons.Default.Link),
    MY("我的", Icons.Default.Person),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.getaudio.app.data.repo.BackgroundStore.init(this)
        setContent {
            GetAudioTheme {
                val background by com.getaudio.app.data.repo.BackgroundStore.state.collectAsState()
                // 从 B站/抖音「分享到 GetAudio」进来时直接落在提取页
                var shared by remember { mutableStateOf(sharedText(intent)) }
                var tab by remember { mutableStateOf(if (shared != null) Tab.EXTRACT else Tab.LIBRARY) }
                var openCategories by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    val listener = { newIntent: Intent ->
                        sharedText(newIntent)?.let { shared = it; tab = Tab.EXTRACT }
                        Unit
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                // 背景图铺在最底层，页面自己让出底色，卡片保持不透明以保证可读性。
                CompositionLocalProvider(LocalBackgroundEnabled provides background.enabled) {
                    Box(Modifier.fillMaxSize()) {
                        BackgroundLayer()
                        Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        Column {
                            if (tab == Tab.LIBRARY) QueueBar()
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                            NavigationBar(
                                containerColor = if (background.enabled) {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                                } else {
                                    NavigationBarDefaults.containerColor
                                },
                            ) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t; if (t == Tab.LIBRARY) openCategories = false },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                            }
                        }
                    }
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding), color = Color.Transparent) {
                        Box(Modifier.fillMaxSize()) {
                            when (tab) {
                                Tab.LIBRARY -> LibraryScreenV2(openCategories)
                                Tab.EXTRACT -> ExtractScreen(prefill = shared, onSharedConsumed = { shared = null })
                                Tab.MY -> MyScreen()
                            }
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                                MaterialTheme.colorScheme.surface,
                                            )
                                        )
                                    )
                            )
                        }
                    }
                }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            try { PlayerHolder.restore(applicationContext) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                com.getaudio.app.data.repo.DiagnosticsStore.event("恢复播放", "播放状态恢复失败，可手动选择音频", false)
            }
        }
    }

    override fun onStop() {
        PlayerHolder.savePlayback(applicationContext, synchronous = true)
        super.onStop()
    }

    private fun sharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        } else null
}
