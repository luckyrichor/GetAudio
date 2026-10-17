package com.getaudio.app.player

import android.content.Intent
import androidx.media3.common.Player
import kotlinx.coroutines.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放。
 *
 * 用 MediaSessionService 而不是自己管 Notification：通知栏控制、
 * 蓝牙耳机按键、锁屏控件都由框架接管。
 */
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hadQueue = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { save() }
    }
    private fun save(synchronous: Boolean = false) {
        val current = player ?: return
        val snapshot = playbackSnapshot(current)
        if (snapshot != null) hadQueue = true
        if (snapshot != null || hadQueue) PlaybackMemory(this).save(snapshot, synchronous)
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        session = MediaSession.Builder(this, exo).build()
        exo.addListener(listener)
        scope.launch { while (isActive) { delay(1000); save() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        save(synchronous = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        save(synchronous = true)
        player?.removeListener(listener)
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}
