package com.getaudio.app.data.repo

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PublicPathMigrationTest {
    @Test fun httpsRequestsNeverFollowProtocolDowngrade() {
        assertFalse(ApiProvider.okHttp().followSslRedirects)
        val account = AccountStore.Account("alice", "", "test-token", "https://43.142.37.199/getaudio/")
        assertFalse(ApiProvider.okHttp(account).followSslRedirects)
    }
    @Test fun connectionSettingsAcceptProjectBaseAndRejectEmbeddedCredentials() {
        assertEquals("https://43.142.37.199/getaudio/", checkedServerBase("https://43.142.37.199/"))
        assertEquals("https://custom.example/project/", checkedServerBase("https://custom.example/project"))
        for (value in listOf("https://name:password@example.com/", "https://example.com/?token=x", "https://example.com/#fragment")) {
            assertTrue(runCatching { checkedServerBase(value) }.isFailure)
        }
    }
    @Test fun productionSettingsMoveButOtherAppsAndServersDoNot() {
        assertEquals("https://43.142.37.199/getaudio/", effectiveServerUrl("https://43.142.37.199/"))
        assertEquals("https://43.142.37.199/getaudio/", effectiveServerUrl("https://43.142.37.199:443/"))
        assertEquals("https://43.142.37.199/getaudio/", effectiveServerUrl(null))
        assertEquals("https://43.142.37.199:8443/", effectiveServerUrl("https://43.142.37.199:8443/"))
        assertEquals("https://43.142.37.199/other/", effectiveServerUrl("https://43.142.37.199/other/"))
    }

    @Test fun oldScopeAndReportOwnershipSurviveWithoutRewritingMediaIds() {
        assertEquals(cloudScopeKey("https://43.142.37.199/", "alice"), cloudScopeKey("https://43.142.37.199/getaudio/", "alice"))
        assertNotEquals(cloudScopeKey("https://43.142.37.199/getaudio/", "alice"), cloudScopeKey("https://43.142.37.199/getaudio/", "bob"))
        val dir = Files.createTempDirectory("path-reports").toFile()
        try {
            val box = FailureOutbox(dir)
            val id = box.enqueue("https://43.142.37.199/", "alice", "share", "0.9.10", listOf("failed"))
            assertEquals(id, box.eligible("https://43.142.37.199/getaudio/", "alice").single().getJSONObject("report").getString("id"))
            assertTrue(box.eligible("https://43.142.37.199/getaudio/", "bob").isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun credentialBoundaryIncludesPathAndRejectsOtherProjects() {
        val base = "https://43.142.37.199/getaudio/".toHttpUrl()
        assertTrue(isCredentialOrigin("https://43.142.37.199/getaudio/v2/audio/1".toHttpUrl(), base))
        for (path in listOf("/v2/audio/1", "/getvideo/v2/audio/1", "/getaudio-other/v2/audio/1", "/getaudio/../account/me")) {
            assertFalse(path, isCredentialOrigin("https://43.142.37.199$path".toHttpUrl(), base))
        }
    }

    @Test fun resourceLinksKeepPrefixExactlyOnceAndRejectEscape() {
        val base = "https://43.142.37.199/getaudio/"
        for (link in listOf("/v2/audio/1", "v2/audio/1", "/getaudio/v2/audio/1", base + "v2/audio/1")) {
            assertEquals(base + "v2/audio/1", serverResourceUrl(base, link))
        }
        for (link in listOf("https://other.invalid/file", "//other.invalid/file", "../getvideo/file", "/getvideo/file")) {
            assertTrue(link, runCatching { serverResourceUrl(base, link) }.isFailure)
        }
    }
}
