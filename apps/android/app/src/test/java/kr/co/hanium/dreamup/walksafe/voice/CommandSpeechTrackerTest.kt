package kr.co.hanium.dreamup.walksafe.voice
import org.junit.Assert.*
import org.junit.Test
class CommandSpeechTrackerTest {
    @Test fun everyNativePartMustReachTerminalAndDuplicateDoneCannotAdvanceTheNextPart() {
        val tracker = CommandSpeechTracker(); tracker.begin(1)
        val response = tracker.submit("response", 3)
        tracker.terminalPart(response, 0, true); tracker.terminalPart(response, 0, true)
        assertEquals(CommandSpeechProgress.PENDING, tracker.progress(1))
        tracker.terminalPart(response, 1, true); assertEquals(CommandSpeechProgress.PENDING, tracker.progress(1))
        tracker.terminalPart(response, 2, true); assertEquals(CommandSpeechProgress.COMPLETED, tracker.progress(1))
    }
    @Test fun failureAndRetirementReleaseAllPartsAndOldCommandCannotCompleteNewOwner() {
        val tracker = CommandSpeechTracker(); tracker.begin(1)
        val old = tracker.submit("old", 3); tracker.terminal(old, false)
        assertEquals(CommandSpeechProgress.FAILED, tracker.progress(1))
        tracker.begin(2); val fresh = tracker.submit("new", 2); tracker.terminal(old, true)
        assertEquals(CommandSpeechProgress.RETIRED, tracker.progress(1)); assertEquals(CommandSpeechProgress.PENDING, tracker.progress(2))
        tracker.retirePending(); tracker.terminal(fresh, true)
        assertEquals(CommandSpeechProgress.FAILED, tracker.progress(2))
    }
    @Test fun dispatchWithoutOutputIsDifferentFromAnUncompletedUtterance() {
        val tracker = CommandSpeechTracker(); assertEquals(CommandSpeechProgress.NOT_TRACKED, tracker.progress(1))
        tracker.begin(1); assertEquals(CommandSpeechProgress.NO_OUTPUT, tracker.progress(1))
        tracker.submit("reserved", 1); assertEquals(CommandSpeechProgress.PENDING, tracker.progress(1))
    }
}
