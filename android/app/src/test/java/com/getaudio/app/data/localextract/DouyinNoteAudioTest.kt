package com.getaudio.app.data.localextract

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinNoteAudioTest {
    private fun item(type: Int, uri: String) = JSONObject().put("aweme_type", type)
        .put("video", JSONObject().put("play_addr", JSONObject().put("uri", uri)))
    private val audio = "https://sf6-cdn-tos.douyinstatic.com/obj/ies-music/123.mp3"

    @Test fun imageNoteReadsDirectAudioInsteadOfBrokenVideoWrapper() {
        val detail = item(2, audio)
        detail.getJSONObject("video").getJSONObject("play_addr").put("url_list",
            org.json.JSONArray().put("https://aweme.snssdk.com/aweme/v1/playwm/?video_id=$audio"))
        assertEquals(listOf(audio), douyinNoteAudioUrls(detail))
    }
    @Test fun imageNoteAcceptsVerifiedHjMusicBucket() {
        val uri = "https://lf9-music-east.douyinstatic.com/obj/ies-music-hj/7523193955769453370.mp3"
        assertEquals(listOf(uri), douyinNoteAudioUrls(item(2, uri)))
    }
    @Test fun unrelatedBucketsRemainRejected() {
        assertTrue(douyinNoteAudioUrls(item(2,
            "https://lf9-music-east.douyinstatic.com/obj/other-bucket/123.mp3")).isEmpty())
    }
    @Test fun ordinaryVideoUriIsNotUsedAsAnAudioAddress() {
        assertTrue(douyinNoteAudioUrls(item(0, audio)).isEmpty())
    }
    @Test fun opaqueIdsAndNonPlatformUrlsAreNotTreatedAsAudio() {
        for (uri in listOf("v0200example", "http://sf6-cdn-tos.douyinstatic.com/a.mp3",
            "https://douyinstatic.com.attacker.invalid/a.mp3", "https://127.0.0.1/a.mp3")) {
            assertTrue(douyinNoteAudioUrls(item(2, uri)).isEmpty())
        }
    }
    @Test fun missingAudioLeavesExistingSelectionAvailable() {
        assertTrue(douyinNoteAudioUrls(JSONObject().put("aweme_type", 2)).isEmpty())
    }
}
