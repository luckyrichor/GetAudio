package com.getaudio.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getaudio.app.data.local.AppDatabase
import com.getaudio.app.data.local.CategoryEntity
import com.getaudio.app.data.local.TrackEntity
import com.getaudio.app.data.repo.CategoryRepository
import com.getaudio.app.data.repo.PlaylistRepository
import com.getaudio.app.data.repo.TrackRepository
import com.getaudio.app.player.PlayerHolder
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

private sealed interface LibraryTarget {
    data class Audio(val track: TrackEntity) : LibraryTarget
    data class Category(val category: CategoryEntity) : LibraryTarget
}

@Composable
fun LibraryScreenV2(openCategories: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(context) }
    val trackRepo = remember { TrackRepository(context) }
    val categoryRepo = remember { CategoryRepository(context) }
    val playlistRepo = remember { PlaylistRepository(context) }
    val allCategories by categoryRepo.observeAll().collectAsState(initial = emptyList())
    val frequent by categoryRepo.frequent().collectAsState(initial = emptyList())
    val playlists by playlistRepo.observeAll().collectAsState(initial = emptyList())
    var section by remember { mutableIntStateOf(0) }
    LaunchedEffect(openCategories) { if (openCategories) section = 1 }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var dateDialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<LibraryTarget?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var selectedParent by remember { mutableStateOf<Long?>(null) }
    var exportTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var playlistId by remember { mutableStateOf<Long?>(null) }
    var playlistSearch by remember { mutableStateOf("") }
    var playlistPickMode by remember { mutableIntStateOf(0) }
    var selectedTracks by remember { mutableStateOf(setOf<Long>()) }
    var selectedCategories by remember { mutableStateOf(setOf<Long>()) }
    val startMs = remember(startDate) { parseDate(startDate) }
    val endMs = remember(endDate) { parseDate(endDate)?.plus(86_399_999L) }
    val allTracks by db.trackDao().allWithTimeRange(query, startMs, endMs).collectAsState(initial = emptyList())
    val libraryTracks by db.trackDao().observeAll().collectAsState(initial = emptyList())
    val categoryTracks by db.trackDao().inCategory(categoryId ?: -1L).collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri: Uri? ->
        val track = exportTrack
        if (uri != null && track != null) scope.launch {
            trackRepo.download(track).onSuccess { file ->
                runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法打开文件") }
                    .onSuccess { notice = "已导出" }.onFailure { notice = it.message ?: "导出失败" }
            }.onFailure { notice = it.message ?: "下载失败" }
        }
        exportTrack = null
    }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); notice = null } }

    fun operate(block: suspend () -> Unit) { scope.launch { runCatching { block() }.onFailure { notice = it.message ?: "操作失败" } } }
    fun queueItems(tracks: List<TrackEntity>, play: Boolean) = operate {
        val ready = mutableListOf<PlayerHolder.QueueItem>()
        for (track in tracks) {
            val path = track.localPath?.takeIf { File(it).exists() }
                ?: trackRepo.download(track).getOrThrow().absolutePath
            ready += PlayerHolder.QueueItem(track, path)
        }
        if (ready.isEmpty()) notice = "类别内没有音频"
        else if (play) PlayerHolder.playAll(context, ready)
        else PlayerHolder.append(context, ready)
    }
    fun targetTracks(item: LibraryTarget) = operate {
        val tracks = when (item) {
            is LibraryTarget.Audio -> listOf(item.track)
            is LibraryTarget.Category -> categoryRepo.recursiveTracks(item.category.id).also { categoryRepo.recordAccess(item.category.id) }
        }
        queueItems(tracks, true)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("全部音频") }, modifier = Modifier.weight(1f))
                Tab(selected = section == 1, onClick = { section = 1; categoryId = null }, text = { Text("类别") }, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
            if (section == 0) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索音频名称") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { dateDialog = true }) { Text("日期") }
                }
                if (startDate.isNotBlank() || endDate.isNotBlank()) {
                    TextButton(onClick = { startDate = ""; endDate = "" }) { Text("${startDate.ifBlank { "不限" }} ~ ${endDate.ifBlank { "不限" }} ×") }
                }
                if (allTracks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无音频") }
                else LazyColumn { items(allTracks, key = { it.id }) { track ->
                    LibraryAudioRow(track, onPlay = { queueItems(listOf(track), true) }, onMore = { target = LibraryTarget.Audio(track) })
                    HorizontalDivider()
                } }
            } else {
                val parent = allCategories.firstOrNull { it.id == categoryId }
                if (parent != null) {
                    TextButton(onClick = { categoryId = parent.parentId }) { Text("‹ ${parent.name} / 返回") }
                } else if (frequent.any { it.accessCount > 0 }) {
                    Text("常用类别", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        frequent.filter { it.accessCount > 0 }.forEach { cat ->
                            AssistChip(onClick = { categoryId = cat.id; operate { categoryRepo.recordAccess(cat.id) } },
                                label = { Text(cat.name) }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                val children = allCategories.filter { it.parentId == categoryId }
                if (categoryId == null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { dialog = "newCategory"; editText = ""; selectedParent = null }) { Text("＋ 新建类别") }
                        TextButton(onClick = { dialog = "playlists" }) { Text("播放列表") }
                    }
                } else if (parent?.kind != "LEAF") {
                    TextButton(onClick = { dialog = "newCategory"; editText = ""; selectedParent = categoryId }) { Text("＋ 新建子类别") }
                }
                if (children.isEmpty() && categoryTracks.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("这里还没有内容") }
                LazyColumn {
                    items(children, key = { "c${it.id}" }) { cat ->
                        val count = if (cat.kind == "CONTAINER") allCategories.count { it.parentId == cat.id }
                            else libraryTracks.count { it.localCategoryId == cat.id }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { targetTracks(LibraryTarget.Category(cat)) }) { Icon(Icons.Default.PlayArrow, "播放类别") }
                            Icon(Icons.Default.Folder, null)
                            Column(Modifier.weight(1f).clickable { categoryId = cat.id; operate { categoryRepo.recordAccess(cat.id) } }.padding(12.dp)) {
                                Text(cat.name)
                                Text(if (cat.kind == "CONTAINER") "$count 个类别" else "$count 首音频", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { target = LibraryTarget.Category(cat) }) { Icon(Icons.Default.MoreVert, "更多") }
                        }
                        HorizontalDivider()
                    }
                    items(categoryTracks, key = { "t${it.id}" }) { track ->
                        LibraryAudioRow(track, onPlay = { queueItems(listOf(track), true) }, onMore = { target = LibraryTarget.Audio(track) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (dateDialog) AlertDialog(onDismissRequest = { dateDialog = false }, title = { Text("更新时间范围") },
        text = { Column { OutlinedTextField(startDate, { startDate = it }, label = { Text("开始 YYYY-MM-DD") }); OutlinedTextField(endDate, { endDate = it }, label = { Text("截止 YYYY-MM-DD") }) } },
        confirmButton = { TextButton(onClick = { if ((startDate.isBlank() || startMs != null) && (endDate.isBlank() || endMs != null)) dateDialog = false else notice = "日期格式应为 YYYY-MM-DD" }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { dateDialog = false }) { Text("取消") } })

    if (dialog == null) target?.let { item ->
        AlertDialog(onDismissRequest = { target = null }, title = { Text(if (item is LibraryTarget.Audio) item.track.title else (item as LibraryTarget.Category).category.name) },
            text = { Column {
                TextButton(onClick = { target = null; operate { val tracks = if (item is LibraryTarget.Audio) listOf(item.track) else categoryRepo.recursiveTracks((item as LibraryTarget.Category).category.id); queueItems(tracks, false) } }) { Text("加入当前列表") }
                TextButton(onClick = { target = null; dialog = "playlists" }) { Text("播放列表") }
                TextButton(onClick = { target = null; dialog = "mode" }) { Text("播放方式") }
                TextButton(onClick = { target = null; dialog = "timer" }) { Text("定时播放") }
                HorizontalDivider()
                if (item !is LibraryTarget.Category || item.category.id != 1L) {
                    TextButton(onClick = { editText = if (item is LibraryTarget.Audio) item.track.title else (item as LibraryTarget.Category).category.name; dialog = "rename" }) { Text("重命名") }
                    TextButton(onClick = { selectedParent = null; dialog = "move" }) { Text("移动到…") }
                    TextButton(onClick = { dialog = "delete" }) { Text("删除") }
                }
                if (item is LibraryTarget.Audio) TextButton(onClick = { exportTrack = item.track; target = null; exportLauncher.launch("${item.track.title.take(60)}.${item.track.audioFormat}") }) { Text("导出") }
                if (item is LibraryTarget.Category && item.category.kind != "LEAF") TextButton(onClick = { selectedParent = item.category.id; editText = ""; target = null; dialog = "newCategory" }) { Text("新建子类别") }
            } }, confirmButton = { TextButton(onClick = { target = null }) { Text("关闭") } })
    }
    when (dialog) {
        "newCategory", "rename", "newPlaylist", "renamePlaylist" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(when (dialog) { "newCategory" -> "新建类别"; "newPlaylist" -> "新建播放列表"; else -> "重命名" }) },
            text = { OutlinedTextField(editText, { editText = it }, label = { Text("名称") }) },
            confirmButton = { TextButton(onClick = {
                val action = dialog; val item = target; val name = editText.trim(); dialog = null; target = null
                operate { when (action) {
                    "newCategory" -> categoryRepo.create(name, selectedParent)
                    "rename" -> when (item) { is LibraryTarget.Audio -> trackRepo.rename(item.track.id, name); is LibraryTarget.Category -> categoryRepo.rename(item.category.id, name); else -> Unit }
                    "newPlaylist" -> playlistRepo.create(name)
                    "renamePlaylist" -> playlistId?.let { playlistRepo.rename(it, name) }
                } }
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { dialog = null; target = null }) { Text("取消") } })
        "move" -> AlertDialog(onDismissRequest = { dialog = null; target = null }, title = { Text("移动到类别") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (target is LibraryTarget.Category) TextButton(onClick = { selectedParent = null }) { Text("根目录 ${if (selectedParent == null) "✓" else ""}") }
                allCategories.filter { cat -> cat.id != (target as? LibraryTarget.Category)?.category?.id && (target is LibraryTarget.Category && cat.kind != "LEAF" || target is LibraryTarget.Audio && cat.kind != "CONTAINER") }.forEach { cat ->
                    TextButton(onClick = { selectedParent = cat.id }) { Text("${categoryPath(cat, allCategories)} ${if (selectedParent == cat.id) "✓" else ""}") }
                }
            } }, confirmButton = { TextButton(onClick = { val item = target; val dest = selectedParent; dialog = null; target = null; operate { when (item) {
                is LibraryTarget.Audio -> categoryRepo.moveTrack(item.track.id, dest ?: error("请选择目标类别"))
                is LibraryTarget.Category -> categoryRepo.moveCategory(item.category.id, dest)
                else -> Unit
            } } }) { Text("移动") } }, dismissButton = { TextButton(onClick = { dialog = null; target = null }) { Text("取消") } })
        "delete" -> {
            val item = target
            var count by remember(item) { mutableIntStateOf(0) }
            LaunchedEffect(item) { if (item is LibraryTarget.Category) count = categoryRepo.recursiveTracks(item.category.id).size }
            AlertDialog(onDismissRequest = { dialog = null; target = null }, title = { Text("确认删除") },
                text = { Text(if (item is LibraryTarget.Category) "删除该类别将同时删除其中的 $count 首音频，是否继续？" else "删除这首音频？") },
                confirmButton = { TextButton(onClick = { dialog = null; target = null; operate { when (item) { is LibraryTarget.Audio -> trackRepo.remove(item.track.id); is LibraryTarget.Category -> categoryRepo.delete(item.category.id); else -> Unit } } }) { Text("删除") } },
                dismissButton = { TextButton(onClick = { dialog = null; target = null }) { Text("取消") } })
        }
        "mode" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("播放方式") }, text = { Column { PlayerHolder.Mode.entries.forEach { mode -> TextButton(onClick = { operate { PlayerHolder.setMode(context, mode) }; dialog = null }) { Text(mode.label) } } } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } })
        "timer" -> TimerDialog(onClose = { dialog = null })
        "playlists", "playlistEdit", "playlistPick" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("播放列表") },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (dialog == "playlists") {
                    TextButton(onClick = { editText = ""; dialog = "newPlaylist" }) { Text("＋ 新建列表") }
                    playlists.forEach { list -> Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { playlistId = list.id; dialog = "playlistEdit" }, modifier = Modifier.weight(1f)) { Text(list.name) }
                        TextButton(onClick = { playlistId = list.id; editText = list.name; dialog = "renamePlaylist" }) { Text("改名") }
                        TextButton(onClick = { operate { playlistRepo.delete(list.id) } }) { Text("删除") }
                    } }
                } else if (dialog == "playlistEdit") {
                    TextButton(onClick = { selectedTracks = emptySet(); selectedCategories = emptySet(); dialog = "playlistPick" }) { Text("＋ 添加音频或类别") }
                    val entries by playlistRepo.items(playlistId ?: -1L).collectAsState(initial = emptyList())
                    TextButton(onClick = { queueItems(entries.mapNotNull { entry -> libraryTracks.firstOrNull { it.id == entry.trackId } }, true); dialog = null }, enabled = entries.isNotEmpty()) { Text("播放此列表") }
                    entries.forEach { entry -> val track = libraryTracks.firstOrNull { it.id == entry.trackId }; if (track != null) Text(track.title, Modifier.padding(8.dp)) }
                } else {
                    Row {
                        FilterChip(playlistPickMode == 0, { playlistPickMode = 0 }, { Text("按类别选择") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(playlistPickMode == 1, { playlistPickMode = 1 }, { Text("按文件选择") })
                    }
                    if (playlistPickMode == 0) {
                        allCategories.forEach { cat -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = cat.id in selectedCategories, onCheckedChange = { selectedCategories = if (it) selectedCategories + cat.id else selectedCategories - cat.id })
                            Text(categoryPath(cat, allCategories))
                        } }
                    } else {
                        OutlinedTextField(playlistSearch, { playlistSearch = it }, label = { Text("搜索文件名") })
                        libraryTracks.filter { it.title.contains(playlistSearch, ignoreCase = true) }.forEach { track -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = track.id in selectedTracks, onCheckedChange = { selectedTracks = if (it) selectedTracks + track.id else selectedTracks - track.id })
                            Text(track.title)
                        } }
                    }
                    Button(onClick = { val id = playlistId; operate { if (id != null) { playlistRepo.addTracks(id, selectedTracks.toList()); selectedCategories.forEach { playlistRepo.addCategory(id, it) } } }; dialog = "playlistEdit" }) { Text("加入列表") }
                }
            } }, confirmButton = { TextButton(onClick = { dialog = if (dialog == "playlists") null else "playlists" }) { Text(if (dialog == "playlists") "关闭" else "返回") } })
    }
}

private fun parseDate(text: String): Long? = runCatching {
    if (text.isBlank()) null else LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

private fun categoryPath(category: CategoryEntity, all: List<CategoryEntity>): String {
    val names = mutableListOf(category.name)
    var parentId = category.parentId
    while (parentId != null) {
        val parent = all.firstOrNull { it.id == parentId } ?: break
        names.add(parent.name)
        parentId = parent.parentId
    }
    return names.asReversed().joinToString(" / ")
}

@Composable
private fun LibraryAudioRow(track: TrackEntity, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "播放音频") }
        Column(Modifier.weight(1f).padding(8.dp)) {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.uploader ?: track.platform, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "更多") }
    }
}

@Composable
private fun TimerDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var minutes by remember { mutableStateOf("15") }
    var finish by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onClose, title = { Text("定时播放") }, text = { Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(15L, 30L, 60L).forEach { value -> TextButton(onClick = { minutes = value.toString() }) { Text(if (value == 60L) "1小时" else "${value}分钟") } } }
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, label = { Text("自定义分钟") })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = finish, onCheckedChange = { finish = it }); Text("播完再停") }
        TextButton(onClick = { PlayerHolder.cancelTimer(); onClose() }) { Text("取消定时") }
    } }, confirmButton = { TextButton(onClick = { val duration = minutes.toLongOrNull(); if (duration != null && duration > 0) { PlayerHolder.setTimer(context, duration, finish); onClose() } }) { Text("开始计时") } }, dismissButton = { TextButton(onClick = onClose) { Text("关闭") } })
}
