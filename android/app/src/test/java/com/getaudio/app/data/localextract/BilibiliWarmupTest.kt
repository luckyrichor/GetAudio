package com.getaudio.app.data.localextract

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class BilibiliWarmupTest {
    @Test fun rejectedOptionalPageDoesNotBlockRequiredApi() = runBlocking {
        val failures = mutableListOf<String>()
        val result = withOptionalBilibiliWarmup(
            warmup = { throw IOException("HTTP 412") },
            onFailure = { failures.add(it.message.orEmpty()) },
            required = { "valid audio metadata" },
        )
        assertEquals("valid audio metadata", result)
        assertEquals(listOf("HTTP 412"), failures)
    }
    @Test fun requiredApiFailureRemainsFailure() = runBlocking {
        try {
            withOptionalBilibiliWarmup(warmup = {}, onFailure = {}, required = { throw IOException("metadata HTTP 412") })
            fail("Required API must remain authoritative")
        } catch (e: IOException) { assertEquals("metadata HTTP 412", e.message) }
    }
    @Test fun cancellationStopsAllRequests() = runBlocking {
        var reached = false
        try {
            withOptionalBilibiliWarmup(warmup = { throw CancellationException() }, onFailure = {}, required = { reached = true })
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertFalse(reached) }
    }
}

class BilibiliFallbackTest {
    @Test fun browserNavigationStaysOnAllowedHttpsOrigin() {
        assertTrue(isBilibiliBrowserPage("https://www.bilibili.com/video/BV1KG411w7r2/?p=3"))
        for (url in listOf(null, "about:blank", "http://www.bilibili.com/", "https://passport.bilibili.com/",
            "https://www.bilibili.com.attacker.invalid/", "https://www.bilibili.com:444/", "https://user@www.bilibili.com/")) {
            assertFalse(url, isBilibiliBrowserPage(url))
        }
    }
    @Test fun browserRetryIsLimitedToRiskControlResponse() {
        assertTrue(shouldUseBilibiliBrowser(412))
        assertFalse(shouldUseBilibiliBrowser(401))
        assertFalse(shouldUseBilibiliBrowser(404))
        assertFalse(shouldUseBilibiliBrowser(200))
    }
}
