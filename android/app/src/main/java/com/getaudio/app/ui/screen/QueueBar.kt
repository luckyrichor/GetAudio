package com.getaudio.app.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getaudio.app.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QueueBar() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by PlayerHolder.queue.collectAsState()
    val index by PlayerHolder.currentIndex.collectAsState()
    val playing by PlayerHolder.isPlaying.collectAsState()
    val deadline by PlayerHolder.timerDeadline.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (deadline != null) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    if (queue.isNotEmpty()) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(queue.getOrNull(index)?.track?.title ?: queue.first().track.title,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { scope.launch { PlayerHolder.toggle(context) } }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "继续")
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.List, "当前列表") }
            }
            if (deadline != null) Text("定时停止：还剩 ${(deadline!! - now).coerceAtLeast(0) / 60_000} 分钟", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("当前列表") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(queue) { i, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { PlayerHolder.jump(context, i) } }, modifier = Modifier.weight(1f)) {
                        Text("${if (i == index) "▶ " else ""}${item.track.title}", maxLines = 2)
                    }
                    TextButton(onClick = { scope.launch { PlayerHolder.remove(context, i) } }) { Text("移除") }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("关闭") } })
}
