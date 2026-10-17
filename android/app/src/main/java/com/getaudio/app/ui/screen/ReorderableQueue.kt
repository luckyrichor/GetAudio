package com.getaudio.app.ui.screen

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.getaudio.app.player.PlayerHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Drag a local preview; commit once on release, cancel safely on dismiss or a live queue change. */
@Composable
internal fun ReorderableQueue(
    queue: List<PlayerHolder.QueueItem>, currentId: Long?, playing: Boolean,
    onReorder: suspend (List<Long>) -> Boolean,
    onPlay: (Long) -> Unit, onRemove: (Long) -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }
    var draft by remember { mutableStateOf(queue) }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var center by remember { mutableFloatStateOf(0f) }
    var finger by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestQueue by rememberUpdatedState(queue)
    val latestReorder by rememberUpdatedState(onReorder)

    LaunchedEffect(queue) { draggedId = null; draft = queue }

    fun moveAtCenter() {
        val id = draggedId ?: return
        val target = state.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != id && center >= it.offset && center < it.offset + it.size
        } ?: return
        val from = draft.indexOfFirst { it.track.id == id }
        val to = draft.indexOfFirst { it.track.id == target.key }
        if (from >= 0 && to >= 0 && from != to) draft = draft.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun saveOrder(items: List<PlayerHolder.QueueItem>) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (!latestReorder(items.map { it.track.id })) error = "列表已更新，请重新排序"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "排序未保存，请重试"
            } finally { saving = false; draft = latestQueue }
        }
    }
    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            withFrameNanos { }
            val layout = state.layoutInfo
            val speed = when {
                finger < layout.viewportStartOffset + edge ->
                    -((layout.viewportStartOffset + edge - finger) / edge).coerceIn(0f, 1f) * 14f
                finger > layout.viewportEndOffset - edge ->
                    ((finger - layout.viewportEndOffset + edge) / edge).coerceIn(0f, 1f) * 14f
                else -> 0f
            }
            if (speed != 0f) { state.scrollBy(speed); moveAtCenter() }
        }
    }
    Column {
        Text(if (saving) "正在保存顺序…" else "长按歌曲上下拖动排序", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 420.dp).pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    if (!saving) {
                        state.layoutInfo.visibleItemsInfo.firstOrNull {
                            offset.y >= it.offset && offset.y < it.offset + it.size
                        }?.let { item ->
                            draggedId = item.key as Long
                            center = item.offset + item.size / 2f
                            finger = offset.y
                            error = null
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
                onDrag = { change, amount ->
                    if (draggedId != null) {
                        change.consume(); center += amount.y; finger += amount.y; moveAtCenter()
                    }
                },
                onDragEnd = {
                    val moved = draggedId != null && draft.map { it.track.id } != latestQueue.map { it.track.id }
                    draggedId = null
                    if (moved) saveOrder(draft)
                },
                onDragCancel = { draggedId = null; draft = latestQueue },
            )
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(draft, key = { _, item -> item.track.id }) { i, item ->
                val id = item.track.id
                val active = draggedId == id
                val current = id == currentId
                val location = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                Row(Modifier.fillMaxWidth().zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active && location != null) center - location.offset - location.size / 2f else 0f }
                    .regionFrame(current || active).semantics {
                        customActions = listOfNotNull(
                            if (i > 0) CustomAccessibilityAction("上移") {
                                if (!saving && draggedId == null) saveOrder(draft.toMutableList().apply { add(i - 1, removeAt(i)) })
                                true
                            } else null,
                            if (i < draft.lastIndex) CustomAccessibilityAction("下移") {
                                if (!saving && draggedId == null) saveOrder(draft.toMutableList().apply { add(i + 1, removeAt(i)) })
                                true
                            } else null,
                        )
                    }.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DragHandle, "拖动排序：${item.track.title}", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${i + 1}", Modifier.widthIn(min = 20.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.track.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    IconButton(enabled = !saving && draggedId == null, onClick = { onPlay(id) }) {
                        Icon(if (current && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (current && playing) "暂停第${i + 1}个音频" else "播放第${i + 1}个音频",
                            tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    AreaButton(enabled = !saving && draggedId == null, onClick = { onRemove(id) }, tone = ActionTone.DESTRUCTIVE) { Text("移除") }
                }
            }
        }
    }
}
