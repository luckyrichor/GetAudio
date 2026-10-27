package com.getaudio.app.data.localextract

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Image notes can put the actual soundtrack in play_addr.uri, wrapped by a broken video URL. */
internal fun douyinNoteAudioUrls(detail: JSONObject): List<String> {
    if (detail.optInt("aweme_type") != 2) return emptyList()
    val uri = detail.optJSONObject("video")?.optJSONObject("play_addr")?.optString("uri")
        ?: return emptyList()
    val url = uri.toHttpUrlOrNull() ?: return emptyList()
    if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty())
        return emptyList()
    if (url.host != "douyinstatic.com" && !url.host.endsWith(".douyinstatic.com")) return emptyList()
    val supportedBucket = listOf("/obj/ies-music/", "/obj/ies-music-hj/").any(url.encodedPath::startsWith)
    if (!supportedBucket || !url.encodedPath.endsWith(".mp3")) return emptyList()
    return listOf(url.toString())
}
