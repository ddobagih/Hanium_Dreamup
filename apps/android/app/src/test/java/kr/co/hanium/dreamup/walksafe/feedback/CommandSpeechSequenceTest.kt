package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.*
import org.junit.Test

class CommandSpeechSequenceTest {
    @Test fun partsAdvanceOnlyOnceAndCompletionWaitsForTheLastPart() {
        val f = Fixture()
        assertTrue(f.sequence.start())
        assertFalse(f.sequence.start())
        assertEquals(listOf("first"), f.spoken)
        f.callbacks[0].first()
        f.callbacks[0].first(); f.callbacks[0].second()
        assertEquals(listOf("first", "second"), f.spoken)
        assertEquals(listOf(0), f.finishedParts)
        assertEquals(0, f.completed)
        f.callbacks[1].first(); f.callbacks[1].first()
        assertEquals(listOf(0, 1), f.finishedParts)
        assertEquals(1, f.completed)
        assertEquals(0, f.failed)
    }

    @Test fun failureOrOwnerChangePreventsNextCandidateAndLateSuccess() {
        for (ownerChanged in listOf(false, true)) {
            val f = Fixture(); f.sequence.start()
            if (ownerChanged) { f.current = false; f.callbacks[0].first() }
            else f.callbacks[0].second()
            f.callbacks[0].first()
            assertEquals(listOf("first"), f.spoken)
            assertTrue(f.finishedParts.isEmpty())
            assertEquals(0, f.completed); assertEquals(1, f.failed)
        }
    }

    @Test fun rejectedOrEmptyOutputFailsExactlyOnce() {
        val f = Fixture(); f.accept = false
        assertFalse(f.sequence.start())
        f.callbacks[0].second(); f.callbacks[0].first()
        assertEquals(1, f.failed); assertEquals(0, f.completed)
        var failed = 0
        assertFalse(CommandSpeechSequence(emptyList(), { true }, { _, _, _ -> error("empty") }, {}, {}, { failed++ }).start())
        assertEquals(1, failed)
    }

    private class Fixture {
        val spoken = mutableListOf<String>()
        val callbacks = mutableListOf<Pair<() -> Unit, () -> Unit>>()
        val finishedParts = mutableListOf<Int>()
        var current = true; var accept = true; var completed = 0; var failed = 0
        val sequence = CommandSpeechSequence(listOf("first", "second"), { current }, { text, done, fail ->
            spoken += text; callbacks += done to fail; accept
        }, { finishedParts += it }, { completed++ }, { failed++ })
    }
}
