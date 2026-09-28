package com.getaudio.app.data.repo

import com.getaudio.app.data.remote.ApiError
import com.getaudio.app.data.remote.GetAudioApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * 按服务器地址缓存 Retrofit 实例。
 *
 * 地址可以在设置里改，所以不能只建一次；但也不该每次调用都新建 —— 那会
 * 丢掉连接池。
 */
object ApiProvider {

    private val json = Json {
        ignoreUnknownKeys = true   // 服务端加字段不该让客户端崩
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        // 提取是同步阻塞的，服务器那边要下载加转码，给足时间
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, GetAudioApi>()

    @Synchronized
    fun api(baseUrl: String): GetAudioApi = cache.getOrPut(baseUrl) {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GetAudioApi::class.java)
    }

    fun okHttp(): OkHttpClient = client

    /**
     * 把 HTTP 错误翻成能直接显示的中文。
     *
     * 服务端的状态码是有含义的（401 = 要登录），别在这里丢掉。
     */
    fun humanMessage(t: Throwable): String = when (t) {
        is HttpException -> {
            val body = runCatching {
                t.response()?.errorBody()?.string()?.let { json.decodeFromString<ApiError>(it).detail }
            }.getOrNull()
            when (t.code()) {
                400 -> body ?: "链接解析失败"
                401 -> body ?: "需要先登录该平台"
                404 -> body ?: "没找到"
                501 -> body ?: "该平台暂不支持这种登录方式"
                502 -> body ?: "平台侧提取失败，稍后再试"
                else -> body ?: "服务器错误（${t.code()}）"
            }
        }
        is java.net.SocketTimeoutException -> "请求超时，请检查网络或稍后重试"
        is java.net.ConnectException -> "连接失败，请检查网络"
        else -> t.message ?: "未知错误"
    }
}
