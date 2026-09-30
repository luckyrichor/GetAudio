package com.getaudio.app.data.repo

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerConnectionPolicyTest {
    @Test fun `old development defaults migrate while custom servers remain`() {
        assertEquals("https://43.142.37.199/", effectiveServerUrl("http://127.0.0.1:18800/"))
        assertEquals("https://43.142.37.199/", effectiveServerUrl("http://10.0.2.2:8800/"))
        assertEquals("https://43.142.37.199/", effectiveServerUrl(null))
        assertEquals("https://custom.example/", effectiveServerUrl("https://custom.example/"))
    }
    @Test fun `credentials only accompany intended HTTPS origin including downloads`() {
        val origin = "https://43.142.37.199/".toHttpUrl()
        assertTrue(isCredentialOrigin("https://43.142.37.199/audio/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("http://43.142.37.199/audio/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("https://43.142.37.199:8800/audio/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("https://other.example/audio/2".toHttpUrl(), origin))
    }
}
