package com.getaudio.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.getaudio.app.data.local.TrackEntity
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 连接到 [PlaybackService] 的控制器。
 *
 * 整个 App 共用一个 —— 多个控制器会各自持有播放状态，界面之间就对不上了。
 */
object PlayerHolder {

    enum class Mode(val label: String) {
        LOOP("列表循环"), SINGLE("单次播放"), ORDERED("顺序列表"), SHUFFLE("列表随机")
    }

    data class QueueItem(val track: TrackEntity, val path: String)
    private val queueState = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue = queueState.asStateFlow()
    private val indexState = MutableStateFlow(0)
    val currentIndex = indexState.asStateFlow()
    private val playingState = MutableStateFlow(false)
    val isPlaying = playingState.asStateFlow()
    private val modeState = MutableStateFlow(Mode.ORDERED)
    val mode = modeState.asStateFlow()
    private val timerDeadlineState = MutableStateFlow<Long?>(null)
    val timerDeadline = timerDeadlineState.asStateFlow()
    private var stopAfterCurrent = false
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null

    @Volatile
    private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.let { if (it.isConnected) return it }
        return connect(context).also { connected ->
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playingState.value = isPlaying
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val previousIndex = indexState.value
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                        (stopAfterCurrent || modeState.value == Mode.SINGLE)) {
                        connected.pause()
                        if (previousIndex in queueState.value.indices) connected.seekTo(previousIndex, 0)
                        stopAfterCurrent = false
                    }
                    indexState.value = connected.currentMediaItemIndex.coerceAtLeast(0)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && stopAfterCurrent) {
                        connected.pause()
                        stopAfterCurrent = false
                    }
                }
            })
        }
    }

    private suspend fun connect(context: Context): MediaController =
        suspendCancellableCoroutine { cont ->
            val token = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, PlaybackService::class.java),
            )
            val future = MediaController.Builder(context.applicationContext, token).buildAsync()
            future.addListener(
                { cont.resume(future.get()) },
                MoreExecutors.directExecutor(),
            )
        }

    suspend fun play(context: Context, track: TrackEntity, localPath: String) {
        playAll(context, listOf(QueueItem(track, localPath)))
    }

    private fun mediaItem(item: QueueItem) = MediaItem.Builder()
        .setUri(item.path)
        .setMediaId(item.track.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.track.title)
            .setArtist(item.track.uploader ?: item.track.platform).build())
        .build()

    suspend fun playAll(context: Context, items: List<QueueItem>) {
        if (items.isEmpty()) return
        queueState.value = items
        indexState.value = 0
        get(context).run {
            setMediaItems(items.map(::mediaItem))
            prepare()
            play()
        }
        applyMode(context)
    }

    suspend fun append(context: Context, items: List<QueueItem>) {
        if (items.isEmpty()) return
        val wasEmpty = queueState.value.isEmpty()
        queueState.value = queueState.value + items
        get(context).run {
            addMediaItems(items.map(::mediaItem))
            if (wasEmpty) prepare() // 加入当前列表时保持暂停
        }
        if (wasEmpty) indexState.value = 0
    }

    suspend fun jump(context: Context, index: Int) {
        if (index !in queueState.value.indices) return
        get(context).run { seekTo(index, 0); play() }
        indexState.value = index
    }

    suspend fun remove(context: Context, index: Int) {
        if (index !in queueState.value.indices) return
        get(context).removeMediaItem(index)
        queueState.value = queueState.value.toMutableList().also { it.removeAt(index) }
        indexState.value = get(context).currentMediaItemIndex.coerceAtLeast(0)
    }

    suspend fun toggle(context: Context) {
        get(context).run { if (isPlaying) pause() else play() }
    }

    suspend fun setMode(context: Context, value: Mode) {
        modeState.value = value
        applyMode(context)
    }

    private suspend fun applyMode(context: Context) {
        get(context).run {
            shuffleModeEnabled = modeState.value == Mode.SHUFFLE
            repeatMode = when (modeState.value) {
                Mode.LOOP -> Player.REPEAT_MODE_ALL
                Mode.SINGLE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setTimer(context: Context, minutes: Long, finishTrack: Boolean) {
        timerJob?.cancel()
        val deadline = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        timerDeadlineState.value = deadline
        stopAfterCurrent = false
        timerJob = timerScope.launch {
            delay(minutes * 60_000L)
            if (timerDeadlineState.value == deadline) {
                timerDeadlineState.value = null
                if (finishTrack && get(context).isPlaying) stopAfterCurrent = true
                else get(context).pause()
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerDeadlineState.value = null
        stopAfterCurrent = false
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
