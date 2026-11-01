package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import org.junit.Assert.*
import org.junit.Test

class VisionParentReleaseTrackerTest {
    @Test fun noNativeProofCannotSkipAnAcceptedButNotYetEnteredBindRunnable() {
        val tracker = VisionParentReleaseTracker()
        val queued = requireNotNull(tracker.reserve())
        assertFalse(tracker.claimRelease(true))
        tracker.bindingFinished() // Retired before the SDK call: this really is NO_BIND.
        assertFalse(tracker.claimRelease(true))
        queued.finish()
        assertTrue(tracker.claimRelease(true))
        assertNull(tracker.reserve())
        assertFalse(tracker.claimRelease(true))
    }

    @Test fun everyAttemptedBindNeedsPositiveUnbindEvenWithoutNativeOpen() {
        // true, false, and SecurityException all enter the same SDK call boundary.
        for (outcome in listOf("true", "false", "SecurityException")) {
            val tracker = VisionParentReleaseTracker()
            tracker.bindingStarted(); tracker.bindingFinished()
            assertFalse(outcome, tracker.claimRelease(true))
            tracker.unbindingSucceeded()
            assertTrue(outcome, tracker.claimRelease(true))
        }
    }

    @Test fun unbindFailureOrTimeoutDoesNotStandInForPositiveSdkCleanup() {
        val tracker = VisionParentReleaseTracker()
        tracker.bindingStarted(); tracker.bindingFinished()
        val unbind = requireNotNull(tracker.reserve())
        unbind.finish() // A finally block after IllegalArgumentException is not a successful unbind.
        assertFalse(tracker.claimRelease(true))
    }

    @Test fun successfulUnbindStillWaitsForItsActualRunnableFinallyAndOtherAcceptedWork() {
        val tracker = VisionParentReleaseTracker()
        tracker.bindingStarted(); tracker.bindingFinished()
        val unbind = requireNotNull(tracker.reserve())
        val admission = requireNotNull(tracker.reserve())
        val hello = requireNotNull(tracker.reserve())
        val exit = requireNotNull(tracker.reserve())
        tracker.unbindingSucceeded()
        assertFalse(tracker.claimRelease(true))
        unbind.finish(); admission.finish(); hello.finish()
        assertFalse(tracker.claimRelease(true))
        exit.finish()
        assertTrue(tracker.claimRelease(true))
    }

    @Test fun rejectedEnqueueReleasesOnlyItsOwnNeverAcceptedReservation() {
        val tracker = VisionParentReleaseTracker()
        tracker.bindingFinished()
        val running = requireNotNull(tracker.reserve())
        val rejected = requireNotNull(tracker.reserve())
        rejected.finish(); rejected.finish()
        assertFalse(tracker.claimRelease(true))
        running.finish()
        assertFalse(tracker.claimRelease(false))
        assertTrue(tracker.claimRelease(true))
    }
}
