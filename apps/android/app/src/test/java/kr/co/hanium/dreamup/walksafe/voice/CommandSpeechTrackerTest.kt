package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.*
import org.junit.Test

class CommandSpeechTrackerTest {
    @Test fun unrelatedAndStaleCallbacksNeverCompleteANewCommand() {
        val t = CommandSpeechTracker()
        val oldOwner = t.begin(1); val old = t.start(oldOwner)
        val owner = t.begin(2); val active = t.start(owner)
        assertNull(t.start(null)); assertNull(t.start(oldOwner))
        t.finish(old, CommandSpeechState.COMPLETED)
        assertEquals(CommandSpeechState.PENDING, t.progress(2).state)
        t.finish(active, CommandSpeechState.CANCELLED)
        t.finish(active, CommandSpeechState.COMPLETED)
        assertEquals(CommandSpeechState.CANCELLED, t.progress(2).state)
    }

    @Test fun resultReplacesAcknowledgementAndPartsAdvanceWithoutAQuietGap() {
        val t = CommandSpeechTracker(); val owner = t.begin(1)
        val acknowledgement = t.start(owner)
        t.finish(acknowledgement, CommandSpeechState.COMPLETED)
        val result = t.start(owner); val revision = t.progress(1).revision
        t.finish(acknowledgement, CommandSpeechState.FAILED)
        t.partCompleted(result)
        assertEquals(CommandSpeechState.PENDING, t.progress(1).state)
        assertTrue(t.progress(1).revision > revision)
        t.finish(result, CommandSpeechState.FAILED)
        t.finish(result, CommandSpeechState.COMPLETED)
        assertEquals(CommandSpeechState.FAILED, t.progress(1).state)
    }
}
