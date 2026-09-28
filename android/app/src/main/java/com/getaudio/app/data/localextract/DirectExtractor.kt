package com.getaudio.app.data.localextract

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.getaudio.app.data.local.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Public-link extraction on the phone. No GetAudio server is involved. */
class DirectExtractor(private val context: Context) {
    private val cookies = MemoryCookies()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun extract(text: String): TrackEntity = withContext(Dispatchers.IO) {
        val link = resolve(text)
        val id = localId(link.platform, link.key)
        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        val dest = File(audioDir, "$id.audio")
        val media = when (link.platform) {
            "douyin" -> douyin(link)
            else -> bilibili(link)
        }
        val work = File.createTempFile("getaudio-", ".source", context.cacheDir)
        val output = File.createTempFile("getaudio-", ".m4a", context.cacheDir)
        val staged = File(audioDir, "$id.audio.part")
        try {
            download(media.urls, work, media.referer)
            val format = audioOnly(work, output)
            val ready = if (output.length() > 1024) output else work
            val hash = sha256(ready)
            // Replace only after the complete audio has been downloaded and validated.
            ready.copyTo(staged, overwrite = true)
            if (!staged.renameTo(dest)) {
                staged.delete()
                error("无法保存音频")
            }
            TrackEntity(
                id = id, platform = link.platform, videoId = link.key,
                title = media.title, uploader = media.uploader,
                durationSec = duration(dest), audioBytes = dest.length(),
                audioFormat = format,
                sourceUrl = link.url, downloadUrl = "", updatedSeq = System.currentTimeMillis(),
                audioSha256 = hash, localPath = dest.absolutePath,
            )
        } finally {
            work.delete()
            output.delete()
            staged.delete()
        }
    }

    private data class Media(
        val title: String, val uploader: String?, val urls: List<String>,
        val referer: String,
    )

    private fun resolve(text: String): DirectLink {
        val raw = DirectLinkParser.find(text)
        val rawHost = raw.toHttpUrlOrNull()?.host ?: error("分享链接无效")
        val isShort = rawHost in setOf("b23.tv", "bili2233.cn", "v.douyin.com", "v.iesdouyin.com")
        val url = if (isShort) client.newCall(Request.Builder().url(raw)
            .header("User-Agent", BROWSER_UA).get().build()).execute().use {
            it.request.url.toString()
        } else raw
        return DirectLinkParser.parse(url)
    }

    private suspend fun douyin(link: DirectLink): Media {
        val ua = BROWSER_UA
        // The home page can be blocked even when the detail API is available.
        runCatching { get("https://www.douyin.com/", ua = ua, stage = "抖音首页") }
        val token = ByteArray(81).also(SecureRandom()::nextBytes)
        val msToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        cookies.put("douyin.com", "msToken", msToken)
        val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""
        runCatching {
            val request = Request.Builder().url("https://ttwid.bytedance.com/ttwid/union/register/")
                .header("User-Agent", ua).post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                Cookie.parseAll(response.request.url, response.headers)
                    .firstOrNull { it.name == "ttwid" }?.let { cookies.put("douyin.com", "ttwid", it.value) }
            }
        }
        val url = "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383" +
            "&channel=channel_pc_web&pc_client_type=1&version_code=170400&version_name=17.4.0" +
            "&cookie_enabled=true&platform=PC&downlink=10&aweme_id=${link.videoId}"
        val response = try {
            JSONObject(get(url, ua = ua, referer = link.url, stage = "抖音作品详情"))
        } catch (e: Exception) {
            if (e is PlatformHttpException && e.code != 403) throw e
            if (e !is PlatformHttpException && e !is org.json.JSONException) throw e
            val browserCookies = cookies.loadForRequest("https://www.douyin.com/".toHttpUrlOrNull()!!)
            val credentialHint = if (browserCookies.none { it.name == "ttwid" }) "；未取得抖音访客凭证" else ""
            runCatching { DouyinBrowserDetail.fetch(context, link.videoId, url, ua, browserCookies) }
                .getOrElse { browserError ->
                    error("抖音作品详情请求失败（${e.message}$credentialHint），浏览器重试也失败：${browserError.message}")
                }
        }
        val detail = response.optJSONObject("aweme_detail")
            ?: error("抖音没有返回作品信息；可能需要登录，或该作品不可见")
        val video = detail.optJSONObject("video")
        val music = detail.optJSONObject("music")
        val videoSeconds = detail.optDouble("duration", 0.0) / 1000.0
        val musicSeconds = music?.optDouble("duration", 0.0) ?: 0.0
        val musicUrls = urls(music?.optJSONObject("play_url"))
        val videoUrls = listOf("play_addr_h264", "play_addr_265", "play_addr", "download_addr")
            .flatMap { urls(video?.optJSONObject(it)) } +
            (video?.optJSONArray("bit_rate")?.let { arr ->
                (0 until arr.length()).flatMap { urls(arr.optJSONObject(it)?.optJSONObject("play_addr")) }
            } ?: emptyList())
        val useMusic = musicUrls.isNotEmpty() && (videoSeconds <= 0 || musicSeconds <= 0 ||
            musicSeconds >= videoSeconds * 0.9 || videoUrls.isEmpty())
        val selected = if (useMusic) musicUrls else videoUrls.ifEmpty { musicUrls }
        require(selected.isNotEmpty()) { "抖音没有返回可下载地址" }
        val title = music?.optString("title")?.takeIf(String::isNotBlank)
            ?: detail.optString("desc").take(60).ifBlank { "抖音 ${link.videoId}" }
        return Media(title, detail.optJSONObject("author")?.optString("nickname"),
            selected.distinct(), "https://www.douyin.com/")
    }

    private fun bilibili(link: DirectLink): Media {
        get("https://www.bilibili.com/", ua = BROWSER_UA)
        get("https://api.bilibili.com/x/frontend/finger/spi", ua = BROWSER_UA)
        val param = if (link.videoId.startsWith("av", true)) "aid=${link.videoId.drop(2)}" else "bvid=${link.videoId}"
        val view = JSONObject(get("https://api.bilibili.com/x/web-interface/view?$param", referer = link.url))
        require(view.optInt("code") == 0) { "B 站作品不可访问：${view.optString("message")}" }
        val item = view.getJSONObject("data")
        val pages = item.optJSONArray("pages")
        require(link.page == 1 || (pages != null && link.page <= pages.length())) { "作品没有 P${link.page}" }
        val cid = pages?.optJSONObject(link.page - 1)?.optLong("cid")?.takeIf { it > 0 }
            ?: item.optLong("cid")
        require(cid > 0) { "B 站没有返回视频分 P 编号" }
        val nav = JSONObject(get("https://api.bilibili.com/x/web-interface/nav", referer = link.url))
        val wbi = nav.getJSONObject("data").getJSONObject("wbi_img")
        val key = mixinKey(wbi.getString("img_url"), wbi.getString("sub_url"))
        val params = sortedMapOf(
            "bvid" to item.optString("bvid", link.videoId), "cid" to cid.toString(),
            "fnval" to "4048", "fnver" to "0", "fourk" to "1", "platform" to "pc",
            "wts" to (System.currentTimeMillis() / 1000).toString(),
        )
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = MessageDigest.getInstance("MD5").digest((query + key).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val play = JSONObject(get("https://api.bilibili.com/x/player/wbi/playurl?$query&w_rid=$signature", referer = link.url))
        require(play.optInt("code") == 0) { "B 站没有返回播放地址：${play.optString("message")}" }
        val audio = play.getJSONObject("data").getJSONObject("dash").getJSONArray("audio")
        val track = (0 until audio.length()).map { audio.getJSONObject(it) }
            .maxByOrNull { it.optLong("bandwidth") } ?: error("B 站没有可用音轨")
        val selected = listOfNotNull(track.optString("baseUrl").takeIf(String::isNotBlank)) +
            strings(track.optJSONArray("backupUrl"))
        val part = pages?.optJSONObject(link.page - 1)?.optString("part").orEmpty()
        val title = item.optString("title").ifBlank { "B 站 ${link.videoId}" } +
            if (link.page > 1) " - P${link.page} $part" else ""
        return Media(title, item.optJSONObject("owner")?.optString("name"), selected,
            "https://www.bilibili.com/")
    }

    private fun get(url: String, ua: String = BROWSER_UA, referer: String? = null, stage: String = "平台"): String {
        val builder = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (referer != null) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw PlatformHttpException(stage, response.code)
            return response.body?.string() ?: error("平台返回空内容")
        }
    }

    private class PlatformHttpException(stage: String, val code: Int) :
        IllegalStateException("$stage 请求失败：HTTP $code")

    private fun download(urls: List<String>, dest: File, referer: String) {
        var last: Exception? = null
        for (url in urls) {
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA)
                    .header("Referer", referer).build()).execute().use { response ->
                    require(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                    dest.outputStream().use { out ->
                        response.body?.byteStream()?.copyTo(out) ?: error("下载内容为空")
                    }
                }
                require(dest.length() > 1024) { "下载内容过小" }
                return
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("所有下载地址均失败：${last?.message}", last)
    }

    /** Remux AAC without re-encoding; preserve non-AAC audio-only sources. */
    private fun audioOnly(source: File, output: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val audio = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("文件中没有音轨")
            val hasVideo = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(audio)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (!hasVideo && mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                return when (mime) {
                    MediaFormat.MIMETYPE_AUDIO_MPEG -> "mp3"
                    MediaFormat.MIMETYPE_AUDIO_FLAC -> "flac"
                    else -> "audio"
                }
            }
            require(mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                "手机暂不支持抽取此视频的音频编码"
            }
            extractor.selectTrack(audio)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                started = true
                val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
            } finally {
                if (started) muxer.stop()
                muxer.release()
            }
            require(output.length() > 1024) { "抽取音轨失败" }
            return "m4a"
        } finally { extractor.release() }
    }

    private fun duration(file: File): Double? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble()?.div(1000)
        } finally { retriever.release() }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun urls(obj: JSONObject?): List<String> = strings(obj?.optJSONArray("url_list"))
    private fun strings(arr: org.json.JSONArray?): List<String> = if (arr == null) emptyList() else
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.startsWith("http") } }

    private fun mixinKey(img: String, sub: String): String {
        val raw = listOf(img, sub).joinToString("") { it.substringAfterLast('/').substringBefore('.') }
        return MIXIN_TABLE.map { raw[it] }.joinToString("").take(32)
    }

    private class MemoryCookies : CookieJar {
        private val jar = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { fresh ->
                jar.removeAll { it.name == fresh.name && it.domain == fresh.domain }
                jar.add(fresh)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = jar.filter { it.matches(url) }
        fun put(domain: String, name: String, value: String) {
            val cookie = Cookie.Builder().name(name).value(value).domain(domain).build()
            saveFromResponse("https://$domain".toHttpUrlOrNull()!!, listOf(cookie))
        }
    }

    companion object {
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val MIXIN_TABLE = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )

        private fun localId(platform: String, key: String): Long {
            val bytes = MessageDigest.getInstance("SHA-256").digest("$platform:$key".toByteArray())
            val positive = ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
            return -(positive.takeIf { it > 0 } ?: 1L)
        }
    }
}
