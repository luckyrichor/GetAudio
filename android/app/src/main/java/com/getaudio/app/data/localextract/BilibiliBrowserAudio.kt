package com.getaudio.app.data.localextract

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

internal fun shouldUseBilibiliBrowser(status: Int): Boolean = status == 412

internal fun isBilibiliBrowserPage(value: String?): Boolean {
    val url = value?.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.host == "www.bilibili.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty()
}

/** One browser-context retry of the same signed, public audio request. */
internal object BilibiliBrowserAudio {
    suspend fun fetch(context: Context, pageUrl: String, apiUrl: String, userAgent: String,
                      report: suspend (String) -> Unit): JSONObject = withContext(Dispatchers.Main) {
        require(isBilibiliBrowserPage(pageUrl))
        val api = apiUrl.toHttpUrl()
        require(api.isHttps && api.port == 443 && api.host == "api.bilibili.com" && api.encodedPath == "/x/player/wbi/playurl")
        val view = WebView(context)
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.userAgentString = userAgent
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            var loaded = false
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(webView: WebView?, request: WebResourceRequest?): Boolean =
                    request?.isForMainFrame == true && !isBilibiliBrowserPage(request.url.toString())
                override fun onPageStarted(webView: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loaded = false
                }
                override fun onPageFinished(webView: WebView?, url: String?) {
                    loaded = isBilibiliBrowserPage(url) && isBilibiliBrowserPage(webView?.url)
                }
            }
            withTimeout(30_000) {
                view.loadUrl(pageUrl)
                while (!loaded) delay(250)
                delay(1_500)
                view.eval("""
                    window.__getaudio_audio = null;
                    fetch(${JSONObject.quote(apiUrl)}, {credentials: 'include', redirect: 'error', headers: {Accept: 'application/json, text/plain, */*'}})
                      .then(async r => { window.__getaudio_audio = {status: r.status, body: await r.text()}; })
                      .catch(() => { window.__getaudio_audio = {status: 0, body: ''}; });
                """.trimIndent())
                var result: JSONObject? = null
                while (result == null) {
                    delay(250)
                    val raw = view.eval("JSON.stringify(window.__getaudio_audio)")
                    if (raw == "null" || raw == "undefined") continue
                    val text = JSONArray("[$raw]").getString(0)
                    if (text != "null") result = JSONObject(text)
                }
                val response = requireNotNull(result)
                val status = response.optInt("status")
                report("B 站浏览器播放地址：HTTP $status")
                check(status == 200) { "B 站播放地址请求仍受限制：HTTP $status，请稍后重试或使用服务器提取" }
                JSONObject(response.getString("body"))
            }
        } finally { view.stopLoading(); view.destroy() }
    }
    private suspend fun WebView.eval(script: String): String {
        check(isBilibiliBrowserPage(url)) { "B 站页面已跳转，请重新提取" }
        return suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
        }
    }
}
