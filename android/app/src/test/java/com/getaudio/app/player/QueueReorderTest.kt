package com.getaudio.app.player

import org.junit.Assert.*
import org.junit.Test

class QueueReorderTest {
    private fun apply(current: List<Long>, desired: List<Long>): List<Long> {
        val result = current.toMutableList()
        queueReorderMoves(current, desired)!!.forEach { (from, to) -> result.add(to, result.removeAt(from)) }
        return result
    }
    @Test fun moveFirstToLastPreservesEverySong() {
        assertEquals(listOf(2L, 3L, 1L), apply(listOf(1, 2, 3), listOf(2, 3, 1)))
    }
    @Test fun moveLastToFirstWithNegativeLocalIds() {
        assertEquals(listOf(-3L, -1L, -2L), apply(listOf(-1, -2, -3), listOf(-3, -1, -2)))
    }
    @Test fun reorderAllEighteenSongsWithoutLosingCurrentIdentity() {
        val original = (1L..18L).toList()
        val desired = original.reversed()
        assertEquals(desired, apply(original, desired))
        assertEquals(13, desired.indexOf(5L))
    }
    @Test fun unchangedOrEmptyQueueNeedsNoPlayerOperations() {
        assertTrue(queueReorderMoves(listOf(1L), listOf(1L))!!.isEmpty())
        assertTrue(queueReorderMoves(emptyList(), emptyList())!!.isEmpty())
    }
    @Test fun changedMembershipOrDuplicateIsRejectedRatherThanResurrected() {
        assertNull(queueReorderMoves(listOf(1L, 2L), listOf(1L)))
        assertNull(queueReorderMoves(listOf(1L), listOf(1L, 2L)))
        assertNull(queueReorderMoves(listOf(1L, 2L), listOf(1L, 1L)))
        assertNull(queueReorderMoves(listOf(1L, 1L), listOf(1L, 1L)))
    }
}
