package com.getaudio.app.data.repo

/** Release build deliberately has no extraction trace collection. */
object DiagnosticsStore {
    val lines: List<String> get() = emptyList()
    fun replace(value: List<String>) = Unit
    fun beginLocal(version: String) = Unit
    fun beginServer(version: String) = Unit
    fun record(startedAt: Long, message: String) = Unit
}
