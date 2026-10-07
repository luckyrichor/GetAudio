package com.getaudio.app.data.repo

import android.content.Context
import java.io.File
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    @Volatile private var serverAuthorization: String? = null
    private val credentialOrigin = TX_SERVER_URL.toHttpUrl()

    /** Provisioned privately on the device, never bundled in the APK or diagnostics. */
    fun initialize(context: Context) {
        AccountStore.initialize(context)
        serverAuthorization = accessStore(context).authorization()
    }

    private fun accessStore(context: Context) = ServerAccessStore(File(context.filesDir, "server-access.json"))
    fun serverUsername(context: Context): String = accessStore(context).username().ifBlank { "lck80" }
    fun hasServerAccess(context: Context): Boolean = accessStore(context).authorization() != null

    /** Candidate credentials are sent only to the configured production HTTPS origin. */
    fun testServerAccess(context: Context, address: String, username: String, password: String, save: Boolean): String {
        val origin = address.trim().trimEnd('/').toHttpUrl()
        val production = isCredentialOrigin(origin, credentialOrigin)
        require(origin.encodedPath == "/" && origin.query == null && origin.fragment == null) { "服务器地址请填写根地址" }
        val testClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).build()
        fun verify(auth: String?) {
            val request = okhttp3.Request.Builder().url(origin.newBuilder().addPathSegment("sync").addQueryParameter("limit", "1").build())
            auth?.let { request.header("Authorization", it) }
            testClient.newCall(request.build()).execute().use { response ->
                require(response.code != 401) { "服务器授权失败，请检查用户名和密码" }
                require(response.isSuccessful) { "服务器检查失败：HTTP ${response.code}" }
                require(JSONObject(response.body?.string().orEmpty()).has("items")) { "服务器未返回有效同步结果" }
            }
        }
        if (production) {
            val store = accessStore(context)
            if (save) {
                store.saveValidated(username, password) { verify(it) }
                serverAuthorization = store.authorization()
            } else verify(store.candidate(username, password))
            return "服务器连接及访问授权正常"
        }
        require(password.isEmpty()) { "服务器访问凭据仅用于 tx 的 HTTPS 地址" }
        verify(null)
        return "服务器连接正常（未发送 tx 访问凭据）"
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // Never record URL paths, query strings, headers, bodies or account identifiers.
            try {
                chain.proceed(chain.request()).also { response ->
                    DiagnosticsStore.event("网络请求", "${chain.request().method} · HTTP ${response.code}", response.isSuccessful)
                }
            } catch (error: java.io.IOException) {
                DiagnosticsStore.event("网络请求", error.javaClass.simpleName, false)
                throw error
            }
        }
        // 提取是同步阻塞的，服务器那边要下载加转码，给足时间
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            // Evaluate every hop, including redirects and audio downloads.
            if (isCredentialOrigin(request.url, credentialOrigin)) {
                serverAuthorization?.let { builder.header("Authorization", it) }
            } else {
                builder.removeHeader("Authorization")
            }
            builder.removeHeader("X-GetAudio-Session")
            AccountStore.sessionFor(request.url)?.let { builder.header("X-GetAudio-Session", it) }
            chain.proceed(builder.build())
        }
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
                401 -> body ?: "服务器访问未授权，请检查访问配置"
                404 -> body ?: "没找到"
                501 -> body ?: "该平台暂不支持这种登录方式"
                502 -> body ?: "平台侧提取失败，稍后再试"
                else -> body ?: "服务器错误（${t.code()}）"
            }
        }
        is java.net.SocketTimeoutException -> "请求超时，请检查网络或稍后重试"
        is java.net.ConnectException -> if (t.message.orEmpty().contains("127.0.0.1"))
            "tx 调试通道未连接，请连接 Mac 并恢复 USB 转发，或在服务器设置中填写手机可访问的地址"
        else "无法连接服务器，请检查服务器地址和服务状态"
        else -> t.message ?: "未知错误"
    }
}
