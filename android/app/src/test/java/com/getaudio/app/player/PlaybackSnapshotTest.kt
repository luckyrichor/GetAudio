package com.getaudio.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotTest {
    private val saved = PlaybackSnapshot(7, -22, 42000, "SHUFFLE")
    @Test fun selectedSongFollowsIdentityAfterReordering() {
        assertEquals(ResumePoint(2, 42000), saved.resumePoint(listOf(-33, -11, -22)))
    }
    @Test fun deletedSongFallsBackToFirstAtStart() {
        assertEquals(ResumePoint(0, 0), saved.resumePoint(listOf(-11, -33)))
    }
    @Test fun noAvailableAudioProducesNoResumePoint() {
        assertNull(saved.resumePoint(emptyList()))
    }
    @Test fun positionsAreNonNegativeAndClampedToKnownDuration() {
        assertEquals(ResumePoint(0, 0), saved.copy(positionMs = -1).resumePoint(listOf(-22)))
        assertEquals(ResumePoint(0, 999), saved.resumePoint(listOf(-22), 1000))
    }
    @Test fun snapshotRoundTripIncludesListSongProgressAndMode() {
        assertEquals(saved, PlaybackSnapshot.decode(saved.encode()))
    }
    @Test fun missingOrCorruptSnapshotIsIgnored() {
        for (text in listOf(null, "", "{", "{}", "{\"playlistId\":-1,\"trackId\":2}")) {
            assertNull(PlaybackSnapshot.decode(text))
        }
    }
}
