package com.getaudio.app.data.repo

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val TX_SERVER_URL = "https://43.142.37.199/getaudio/"

/** Only this verified production service has an address alias; no host-wide trust. */
internal fun migratedServerUrl(saved: String): String {
    val url = saved.toHttpUrlOrNull() ?: return saved
    return if (url.isHttps && url.host == "43.142.37.199" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        url.encodedPath in listOf("/", "/getaudio", "/getaudio/")) TX_SERVER_URL else saved
}

/** Preserve the existing Room scope/mapping/cursor identity across the transport move. */
internal fun serverIdentity(saved: String): String =
    if (migratedServerUrl(saved) == TX_SERVER_URL) "https://43.142.37.199/" else saved

internal fun checkedServerBase(input: String): String {
    val url = effectiveServerUrl(input).toHttpUrl()
    require(url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) {
        "服务器地址不能包含用户名、密码、查询参数或片段"
    }
    return url.toString()
}

internal fun effectiveServerUrl(saved: String?): String = when (saved?.trim()?.trimEnd('/')) {
    null, "", "http://127.0.0.1:18800", "http://127.0.0.1:8800", "http://10.0.2.2:8800" -> TX_SERVER_URL
    else -> migratedServerUrl(saved.trim().trimEnd('/') + "/")
}

internal fun isCredentialOrigin(request: HttpUrl, origin: HttpUrl): Boolean =
    request.scheme == "https" && origin.scheme == "https" &&
        request.host == origin.host && request.port == origin.port &&
        request.encodedPath.startsWith(origin.encodedPath.trimEnd('/') + "/") &&
        !Regex("(?i)%2f|%5c").containsMatchIn(request.encodedPath)

/** Resolve cached legacy paths and new server paths, rejecting namespace escapes. */
internal fun serverResourceUrl(baseUrl: String, resource: String): String {
    val base = migratedServerUrl(baseUrl).toHttpUrl()
    val link = resource.trim()
    require(link.isNotEmpty() && !link.startsWith("//") && !link.contains('\\')) { "资源地址无效" }
    val url = if (link.startsWith("https://") || link.startsWith("http://")) link.toHttpUrl() else {
        val prefix = base.encodedPath.trimEnd('/') + "/"
        if (link.startsWith(prefix)) base.resolve(link) else {
            val relative = link.removePrefix("/")
            require(relative.substringBefore('/') in setOf("v2", "audio", "login", "account", "agent", "health")) { "资源路径不属于 GetAudio" }
            base.resolve(relative)
        }
    } ?: error("资源地址无效")
    require(isCredentialOrigin(url, base) && url.username.isEmpty() && url.password.isEmpty()) { "资源地址超出服务器范围" }
    return url.toString()
}
