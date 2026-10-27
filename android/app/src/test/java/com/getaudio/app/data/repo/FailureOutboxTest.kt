package com.getaudio.app.data.repo

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FailureOutboxTest {
    @Test fun persistsShareAndRedactedLogsAcrossReopen() {
        val dir = Files.createTempDirectory("reports").toFile()
        try {
            val box = FailureOutbox(dir)
            val id = box.enqueue("https://server.invalid/", "alice", "分享 https://v.douyin.com/abc/ password=secret", "0.8.7",
                listOf("HTTP 404", "Cookie: secret", "GET https://media.invalid/private"))
            val record = FailureOutbox(dir).pending().single()
            assertEquals(id, record.getJSONObject("report").getString("id"))
            assertEquals("alice", record.getString("owner"))
            val text = record.toString()
            assertTrue(text.contains("v.douyin.com")); assertTrue(text.contains("HTTP 404"))
            assertFalse(text.contains("password=secret")); assertFalse(text.contains("Cookie: secret"))
            assertFalse(text.contains("media.invalid/private"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun otherAccountBacklogCannotStarveCurrentReports() {
        val dir = Files.createTempDirectory("reports").toFile()
        try {
            val box = FailureOutbox(dir)
            repeat(10) { box.enqueue("https://server.invalid/", "bob", "old", "0.8.7", listOf("404")) }
            box.enqueue("https://server.invalid/", "alice", "new", "0.8.7", listOf("404"))
            assertEquals("alice", box.eligible("https://server.invalid/", "alice").take(10).single().getString("owner"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun guestReportPinsFirstUploaderBeforeAnyNetworkAttempt() {
        val dir = Files.createTempDirectory("reports").toFile()
        try {
            val box = FailureOutbox(dir)
            val id = box.enqueue("https://server.invalid/", "", "share", "0.8.7", listOf("HTTP 404"))
            assertTrue(box.bindOwner(id, "alice"))
            assertFalse(FailureOutbox(dir).bindOwner(id, "bob"))
            assertEquals("alice", box.pending().single().getString("owner"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun acknowledgingOneRecordRetainsOtherPendingFailures() {
        val dir = Files.createTempDirectory("reports").toFile()
        try {
            val box = FailureOutbox(dir)
            val first = box.enqueue("https://server.invalid/", "alice", "one", "0.8.7", listOf("HTTP 404"))
            box.enqueue("https://server.invalid/", "bob", "two", "0.8.7", listOf("HTTP 403"))
            box.acknowledge(first)
            assertEquals("bob", box.pending().single().getString("owner"))
        } finally { dir.deleteRecursively() }
    }
}
