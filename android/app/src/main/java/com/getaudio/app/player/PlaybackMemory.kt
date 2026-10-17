package com.getaudio.app.player

import android.content.Context
import androidx.media3.common.Player

/** App-private, small playback bookmark. The playlist itself remains authoritative in Room. */
internal class PlaybackMemory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback-memory", Context.MODE_PRIVATE)
    fun read(): PlaybackSnapshot? = PlaybackSnapshot.decode(preferences.getString("last", null))
    fun save(snapshot: PlaybackSnapshot?, synchronous: Boolean = false) {
        val edit = preferences.edit()
        if (snapshot == null) edit.remove("last") else edit.putString("last", snapshot.encode())
        if (synchronous) edit.commit() else edit.apply()
    }
}

internal fun playbackSnapshot(player: Player): PlaybackSnapshot? {
    val list = PlayerHolder.sourcePlaylistId.value ?: return null
    val track = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return null
    return PlaybackSnapshot(list, track, player.currentPosition.coerceAtLeast(0), PlayerHolder.mode.value.name)
}
