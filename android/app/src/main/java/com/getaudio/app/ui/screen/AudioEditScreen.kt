package com.getaudio.app.ui.screen

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.getaudio.app.audioedit.*
import com.getaudio.app.data.local.TrackEntity
import com.getaudio.app.data.repo.TrackRepository
import com.getaudio.app.data.repo.WaveformCache
import com.getaudio.app.player.PlayerHolder
import com.getaudio.app.ui.components.uiAccentContainer
import com.getaudio.app.ui.components.uiCardColor
import com.getaudio.app.ui.components.uiHairline
import com.getaudio.app.ui.components.uiMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEditScreen(track: TrackEntity, onClose: () -> Unit, onSaved: (TrackEntity) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { TrackRepository(context) }
    val player = remember { ExoPlayer.Builder(context).build().apply { setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true) } }
    var file by remember { mutableStateOf<File?>(null) }
    var duration by remember { mutableLongStateOf(0) }
    var peaks by remember { mutableStateOf(FloatArray(0)) }
    var segments by remember { mutableStateOf(listOf<AudioSegment>()) }
    var active by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var previewSegments by remember { mutableStateOf(listOf<AudioSegment>()) }
    var loading by remember { mutableStateOf(true) }
    var waveformLoading by remember { mutableStateOf(true) }
    var waveformProgress by remember { mutableFloatStateOf(0f) }
    var coarseShown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(track.title + "（剪辑）") }
    var overwrite by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    val selected = segments.getOrNull(active)
    val valid = remember(segments, duration) { runCatching { validatedSegments(segments, duration) }.getOrNull() }
    LaunchedEffect(selected) { startText = selected?.let { editTime(it.startMs) } ?: ""; endText = selected?.let { editTime(it.endMs) } ?: "" }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(file) { val snapshot = file; onDispose { snapshot?.delete() } }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(track.id) {
        try {
            PlayerHolder.get(context).pause()
            val source = repo.editSource(track.id)
            file = source
            duration = withContext(Dispatchers.IO) { AudioEditor.duration(source) }
            require(duration > 0) { "音频时长无效" }
            segments = listOf(AudioSegment(0, duration))
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source))); player.prepare()
        } catch (e: Exception) { error = e.message ?: "无法打开音频" }
        finally { loading = false }
    }
    LaunchedEffect(file, duration) {
        val source = file ?: return@LaunchedEffect
        if (duration <= 0) return@LaunchedEffect
        waveformLoading = true
        waveformProgress = 0f
        // 之前算过就直接用缓存，不用再解一遍整条音轨。
        val cached = withContext(Dispatchers.IO) { WaveformCache.read(context, track.id, AudioEditor.WAVEFORM_BUCKETS) }
        if (cached != null) {
            peaks = cached
            waveformProgress = 1f
            waveformLoading = false
            return@LaunchedEffect
        }
        try {
            // 先用跳采样把整条形状铺出来（软件解码器解整条要十秒，这一步两秒内），
            // 用户一进页面就能看到完整轮廓。
            val coarse = AudioEditor.coarseWaveform(source, duration)
            val hasCoarse = coarse.any { it > 0f }
            if (hasCoarse) { peaks = coarse; coarseShown = true }
            // 再跑精确解码：已经解出来的部分用精确值，还没解到的继续显示粗波形，不会突然变空。
            peaks = AudioEditor.waveform(source, duration) { partial, progress ->
                peaks = if (hasCoarse) FloatArray(partial.size) { i -> if (partial[i] > 0f) partial[i] else coarse[i] } else partial
                waveformProgress = progress
            }
            waveformProgress = 1f
            withContext(Dispatchers.IO) { WaveformCache.write(context, track.id, peaks) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "波形读取失败：${e.message}；仍可播放和选择时间" }
        finally { waveformLoading = false }
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            position = if (preview) previewSegments.getOrNull(player.currentMediaItemIndex)?.let { it.startMs + player.currentPosition } ?: 0 else player.currentPosition
            delay(100)
        }
    }
    fun originalAt(ms: Long, play: Boolean = false) {
        val source = file ?: return
        if (preview) { player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source))); player.prepare(); preview = false }
        player.seekTo(ms.coerceIn(0, duration)); position = ms
        if (play) player.play()
    }
    fun update(segment: AudioSegment) {
        if (preview) { player.pause(); originalAt(position) }
        segments = segments.mapIndexed { index, value -> if (index == active) segment else value }
    }
    fun save() {
        val source = file ?: return
        val clips = valid ?: return
        val name = title.trim()
        if (name.isBlank()) { error = "名称不能为空"; return }
        busy = true; player.pause(); error = null
        scope.launch {
            val output = File(context.cacheDir, "edit-${java.util.UUID.randomUUID()}.m4a")
            try {
                AudioEditor.export(context, source, clips, duration, output)
                val saved = repo.saveEdit(track.id, output, name, overwrite)
                // 覆盖了本地文件：内容变了，旧波形作废，下次进剪辑页重新算。
                if (overwrite) WaveformCache.forget(context, track.id)
                onSaved(saved)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = "保存失败：${e.message}" }
            finally { output.delete(); busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("剪辑音频", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            track.title,
                            fontSize = 12.sp,
                            color = uiMuted(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(uiAccentContainer())
                            .clickable(enabled = !busy, onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, "关闭", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备音频…", fontSize = 12.sp, color = uiMuted()) }
                error?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
                if (file != null && duration > 0) {
                    Text("选中的是保留部分 · 点击波形定位，拖动两端调整范围", fontSize = 12.sp, color = uiMuted())
                    val window = (duration / zoom).toLong().coerceAtLeast(1)
                    val viewStart = ((duration - window) * offset).toLong()
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = uiCardColor(),
                        border = BorderStroke(1.dp, uiHairline()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 波形生成中：进度条叠在波形底部，不额外占高度，版面不会跳。
                        Box {
                            WaveformEditor(peaks, duration, viewStart, window, segments, active, position, !busy,
                                onSeek = { originalAt(it, playing) }, onRange = { update(it) })
                            if (waveformLoading) {
                                LinearProgressIndicator(
                                    progress = { waveformProgress.coerceIn(0.02f, 1f) },
                                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                                )
                            }
                        }
                    }
                    // 这行一直留着：生成中显示进度，生成完显示提示，避免版面跳动。
                    Text(
                        when {
                            !waveformLoading -> "波形已就绪，拖动两端调整保留范围"
                            coarseShown -> "已显示整体轮廓，正在细化 ${(waveformProgress.coerceIn(0f, 1f) * 100).toInt()}%"
                            else -> "正在生成波形 ${(waveformProgress.coerceIn(0f, 1f) * 100).toInt()}%"
                        },
                        fontSize = 12.sp,
                        color = if (waveformLoading) uiMuted() else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SheetPill("缩小", Icons.Default.Remove, { zoom = (zoom / 2).coerceAtLeast(1f) }, enabled = !busy)
                        SheetPill("放大", Icons.Default.Add, { zoom = (zoom * 2).coerceAtMost(64f) }, enabled = !busy)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${editTime(viewStart)} — ${editTime((viewStart + window).coerceAtMost(duration))}",
                            fontSize = 11.sp, color = uiMuted(),
                        )
                    }
                    // 一直保留这一行：缩小到 1 倍时滑杆不可拖动，但版面不跳动。
                    Text("移动波形视窗", fontSize = 11.sp, color = uiMuted())
                    Slider(offset, { offset = it }, enabled = !busy && zoom > 1f)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = uiAccentContainer().copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayCircleOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(editTime(position), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Text(" / ${editTime(duration)}", fontSize = 13.sp, color = uiMuted())
                        }
                    }
                    // 三个控制分开：
                    // 「播放原音频」从原内容的最开头开始播；「播放剪辑片段」从保留片段的头部开始播。
                    // 暂停/继续是一个纯图标按钮，管的是「当前正在播的那个」——
                    // 不管正在放原音频还是剪辑片段，都由它暂停和续播，不再互相顶掉文案。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { originalAt(0, true) },
                            enabled = !busy,
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("播放原音频") }
                        OutlinedButton(
                            onClick = {
                                val clips = valid ?: return@OutlinedButton
                                previewSegments = clips; preview = true
                                player.setMediaItems(clips.map { AudioEditor.clippedItem(file!!, it) }); player.prepare(); player.play()
                            },
                            enabled = valid != null && !busy,
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("播放剪辑片段") }
                        FilledIconButton(onClick = { if (playing) player.pause() else player.play() }, enabled = !busy) {
                            Icon(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "暂停" else "继续播放",
                            )
                        }
                    }
                    Text(if (preview) "正在播放保留片段，按原时间顺序连续播放；用暂停/续播按钮可停在当前位置" else "边听边选择起点和终点；点波形可跳到该位置", fontSize = 12.sp, color = uiMuted())
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = uiCardColor(),
                        border = BorderStroke(1.dp, uiHairline()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            segments.forEachIndexed { index, segment ->
                                val isActive = active == index
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else Color.Transparent)
                                        .clickable(enabled = !busy) { active = index }
                                        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(8.dp).clip(CircleShape)
                                            .background(if (isActive) MaterialTheme.colorScheme.primary else uiHairline()),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "片段 ${index + 1}",
                                        fontSize = 13.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${editTime(segment.startMs)} — ${editTime(segment.endMs)}",
                                        Modifier.weight(1f),
                                        fontSize = 11.sp,
                                        color = uiMuted(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(
                                        Modifier.size(30.dp).clip(CircleShape).clickable(enabled = !busy) {
                                            player.pause(); originalAt(position)
                                            segments = segments.filterIndexed { i, _ -> i != index }
                                            active = active.coerceAtMost((segments.size - 1).coerceAtLeast(0))
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Close, "移除片段 ${index + 1}", Modifier.size(15.dp), tint = uiMuted())
                                    }
                                }
                                if (index != segments.lastIndex) SheetDivider()
                            }
                        }
                    }
                    if (selected != null) {
                        RangeSlider(value = selected.startMs.toFloat()..selected.endMs.toFloat(), onValueChange = { update(AudioSegment(it.start.toLong(), it.endInclusive.toLong())) }, valueRange = 0f..duration.toFloat(), enabled = !busy)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                startText, { startText = it },
                                label = { Text("开始 分:秒", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f), enabled = !busy, singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    unfocusedBorderColor = uiHairline(),
                                    focusedContainerColor = uiCardColor(),
                                    unfocusedContainerColor = uiCardColor(),
                                ),
                            )
                            OutlinedTextField(
                                endText, { endText = it },
                                label = { Text("结束 分:秒", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f), enabled = !busy, singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    unfocusedBorderColor = uiHairline(),
                                    focusedContainerColor = uiCardColor(),
                                    unfocusedContainerColor = uiCardColor(),
                                ),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // 三个按钮同款外观；「应用时间」不再单独给背景色。
                            SheetPill("应用时间", null, {
                                val start = parseEditTime(startText); val end = parseEditTime(endText)
                                if (start == null || end == null || start >= end || end > duration) error = "请填写有效的开始和结束时间"
                                else { update(AudioSegment(start, end)); error = null }
                            }, enabled = !busy)
                            SheetPill("当前位置为起点", null, { update(selected.copy(startMs = position.coerceIn(0, selected.endMs - 1))) }, enabled = !busy && selected.endMs > 0)
                            SheetPill("当前位置为终点", null, { update(selected.copy(endMs = position.coerceIn(selected.startMs + 1, duration))) }, enabled = !busy && selected.startMs < duration)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val sorted = segments.sortedBy { it.startMs }
                            val boundaries = listOf(AudioSegment(0, 0)) + sorted + AudioSegment(duration, duration)
                            val gap = boundaries.zipWithNext().firstOrNull { (a, b) -> b.startMs - a.endMs >= 1000 }
                            if (gap == null) error = "没有可添加的空白区间，请先缩短或移除已有片段"
                            else { player.pause(); originalAt(position); segments = segments + AudioSegment(gap.first.endMs, minOf(gap.second.startMs, gap.first.endMs + 30000)); active = segments.lastIndex; error = null }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("添加片段") }
                    Text(
                        if (valid == null) "请检查片段：至少一段，时间有效且不能重叠"
                        else "最终时长 ${editTime(valid.sumOf { it.endMs - it.startMs })} · 多段按原顺序拼接",
                        fontSize = 12.sp,
                        color = if (valid == null) MaterialTheme.colorScheme.error else uiMuted(),
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = uiCardColor(),
                        border = BorderStroke(1.dp, uiHairline()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(!overwrite, { overwrite = false; title = track.title + "（剪辑）" }, enabled = !busy)
                                Text("另存为新音频", fontSize = 14.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(overwrite, { overwrite = true; title = track.title }, enabled = !busy)
                                Text("覆盖本地音频", fontSize = 14.sp)
                            }
                            OutlinedTextField(
                                title, { title = it },
                                label = { Text("音频名称（允许同名）", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                                singleLine = true, enabled = !busy,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    unfocusedBorderColor = uiHairline(),
                                    focusedContainerColor = uiCardColor(),
                                    unfocusedContainerColor = uiCardColor(),
                                ),
                            )
                        }
                    }
                    Text("剪辑在手机本地完成，结果保存为 M4A；服务器原文件保持原样。", fontSize = 12.sp, color = uiMuted())
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在生成并保存剪辑结果…", fontSize = 12.sp, color = uiMuted())
                    }
                    Button(
                        onClick = { if (overwrite) confirmOverwrite = true else save() },
                        enabled = !busy && valid != null && title.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("保存剪辑", fontSize = 15.sp) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (confirmOverwrite) AlertDialog(onDismissRequest = { confirmOverwrite = false }, title = { Text("覆盖本地音频？") },
        text = { Text("原时长 ${editTime(duration)}，剪辑后 ${editTime(valid?.sumOf { it.endMs - it.startMs } ?: 0)}。收藏、类别和播放列表保留。手机上的文件将替换，服务器原版不受影响。") },
        confirmButton = { TextButton(onClick = { confirmOverwrite = false; save() }) { Text("确认覆盖") } },
        dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("取消") } })
}

@Composable
private fun WaveformEditor(peaks: FloatArray, duration: Long, viewStart: Long, window: Long, segments: List<AudioSegment>, active: Int, position: Long, enabled: Boolean, onSeek: (Long) -> Unit, onRange: (AudioSegment) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val selected = segments.getOrNull(active)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentRange by rememberUpdatedState(onRange)
    val currentSelected by rememberUpdatedState(selected)
    Canvas(Modifier.fillMaxWidth().height(160.dp)
        .pointerInput(viewStart, window, enabled) { if (enabled) detectTapGestures { currentSeek((viewStart + it.x / size.width * window).toLong().coerceIn(0, duration)) } }
        .pointerInput(viewStart, window, enabled, active) { if (enabled) {
            var dragStart = true
            detectDragGestures(onDragStart = { point ->
                currentSelected?.let { segment ->
                    val time = (viewStart + point.x / size.width * window).toLong()
                    dragStart = abs(time - segment.startMs) <= abs(time - segment.endMs)
                }
            }, onDrag = { change, _ ->
            val segment = currentSelected
            if (segment != null) {
                val time = (viewStart + change.position.x / size.width * window).toLong().coerceIn(0, duration)
                if (dragStart && segment.endMs > 0) currentRange(segment.copy(startMs = time.coerceAtMost(segment.endMs - 1)))
                else if (!dragStart && segment.startMs < duration) currentRange(segment.copy(endMs = time.coerceAtLeast(segment.startMs + 1)))
                change.consume()
            }
        }) } }) {
        fun x(ms: Long) = (ms - viewStart).toFloat() / window * size.width
        segments.forEachIndexed { index, segment ->
            val left = x(segment.startMs).coerceIn(0f, size.width); val right = x(segment.endMs).coerceIn(0f, size.width)
            if (right > left) drawRect(accent.copy(alpha = if (index == active) 0.22f else 0.09f), Offset(left, 0f), androidx.compose.ui.geometry.Size(right - left, size.height))
        }
        if (peaks.isEmpty()) {
            // 还没解出数据：先画一条中轴骨架，让这块区域看着是「正在加载」而不是「坏了」。
            drawLine(accent.copy(alpha = 0.28f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
        }
        val columns = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
        for (column in 0 until columns) {
            val a = ((viewStart + column.toDouble() / columns * window) / duration * peaks.size).toInt().coerceIn(0, peaks.size)
            val b = ((viewStart + (column + 1.0) / columns * window) / duration * peaks.size).toInt().coerceIn(a, peaks.size)
            var peak = 0f
            for (i in a until maxOf(b, (a + 1).coerceAtMost(peaks.size))) peak = maxOf(peak, peaks[i])
            val height = maxOf(2f, peak * size.height * 0.85f)
            val at = column.toFloat() / columns * size.width
            drawLine(accent, Offset(at, (size.height - height) / 2), Offset(at, (size.height + height) / 2), 2.dp.toPx())
        }
        selected?.let { segment -> listOf(segment.startMs, segment.endMs).forEach { time ->
            val at = x(time)
            if (at in 0f..size.width) { drawLine(accent, Offset(at, 0f), Offset(at, size.height), 3.dp.toPx()); drawCircle(accent, 7.dp.toPx(), Offset(at, size.height / 2)) }
        } }
        val cursor = x(position)
        if (cursor in 0f..size.width) drawLine(Color.Red, Offset(cursor, 0f), Offset(cursor, size.height), 2.dp.toPx())
    }
}
