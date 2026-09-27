package com.getaudio.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.getaudio.app.data.local.TrackEntity
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 连接到 [PlaybackService] 的控制器。
 *
 * 整个 App 共用一个 —— 多个控制器会各自持有播放状态，界面之间就对不上了。
 */
object PlayerHolder {

    @Volatile
    private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.let { if (it.isConnected) return it }
        return connect(context).also { controller = it }
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
        val item = MediaItem.Builder()
            .setUri(localPath)
            .setMediaId(track.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.uploader ?: track.platform)
                    .build()
            )
            .build()

        get(context).run {
            setMediaItem(item)
            prepare()
            play()
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
