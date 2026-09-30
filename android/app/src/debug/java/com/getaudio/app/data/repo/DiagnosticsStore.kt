package com.getaudio.app.data.repo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock

/** Recent extraction steps only. Never store cookies or complete media URLs here. */
object DiagnosticsStore {
    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    fun replace(value: List<String>) { lines = value.takeLast(60) }
    fun beginLocal(version: String) { replace(listOf("GetAudio $version · 手机直提取（未使用 GetAudio 服务器）")) }
    fun beginServer(version: String) { replace(listOf("GetAudio $version · 手动服务器提取")) }
    fun record(startedAt: Long, message: String) {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        lines = (lines + "[+${seconds}s] $message").takeLast(60)
    }
}
