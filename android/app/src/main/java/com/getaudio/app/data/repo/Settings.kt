package com.getaudio.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.getaudio.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore("settings")

/** 服务器地址和用户 id。用户 id 首次启动时生成一个，用来隔离各自的平台凭证。 */
class Settings(private val context: Context) {

    private val serverKey = stringPreferencesKey("server_url")
    private val userKey = stringPreferencesKey("user_id")

    val serverUrl: Flow<String> = context.dataStore.data.map {
        it[serverKey] ?: BuildConfig.DEFAULT_SERVER
    }

    suspend fun setServerUrl(url: String) {
        // 结尾必须有斜杠，否则 Retrofit 会把 baseUrl 的最后一段吃掉
        val normalized = url.trim().trimEnd('/') + "/"
        context.dataStore.edit { it[serverKey] = normalized }
    }

    suspend fun serverUrlNow(): String = serverUrl.first()

    suspend fun userId(): String {
        val existing = context.dataStore.data.map { it[userKey] }.first()
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        context.dataStore.edit { it[userKey] = fresh }
        return fresh
    }
}
