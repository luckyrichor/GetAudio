package com.getaudio.app.player

import org.json.JSONObject

internal data class ResumePoint(val index: Int, val positionMs: Long)

/** Store identities, never stale list offsets or file paths. */
internal data class PlaybackSnapshot(
    val playlistId: Long, val trackId: Long, val positionMs: Long, val mode: String,
) {
    fun encode(): String = JSONObject().put("playlistId", playlistId).put("trackId", trackId)
        .put("positionMs", positionMs.coerceAtLeast(0)).put("mode", mode).toString()

    fun resumePoint(availableIds: List<Long>, durationMs: Long? = null): ResumePoint? {
        if (availableIds.isEmpty()) return null
        val index = availableIds.indexOf(trackId)
        if (index < 0) return ResumePoint(0, 0)
        val position = positionMs.coerceAtLeast(0).let {
            if (durationMs != null && durationMs > 0) it.coerceAtMost(durationMs - 1) else it
        }
        return ResumePoint(index, position)
    }

    companion object {
        fun decode(text: String?): PlaybackSnapshot? = runCatching {
            if (text.isNullOrBlank()) return null
            val json = JSONObject(text)
            val list = json.getLong("playlistId")
            require(list > 0)
            PlaybackSnapshot(list, json.getLong("trackId"), json.optLong("positionMs", 0).coerceAtLeast(0),
                json.optString("mode", "ORDERED"))
        }.getOrNull()
    }
}
