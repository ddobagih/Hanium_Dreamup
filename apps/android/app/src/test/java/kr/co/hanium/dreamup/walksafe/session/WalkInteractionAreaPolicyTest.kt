package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkInteractionAreaPolicyTest {
    private fun area(state: WalkSessionState, selected: Boolean = false, preparing: Boolean = false, ending: Boolean = false) =
        WalkInteractionAreaPolicy.resolve(state, selected, preparing, ending)
    @Test fun searchingIsMainButSelectingADestinationStartsWalkOwnership() {
        assertEquals(WalkInteractionAreaPolicy.Area.MAIN, area(WalkSessionState.READY))
        assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(WalkSessionState.READY, selected = true))
        assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(WalkSessionState.READY, preparing = true))
    }
    @Test fun pausingOrSafetyStoppingNeverReleasesOwnership() {
        for (state in listOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED, WalkSessionState.SAFE_STOP)) {
            assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(state))
        }
    }
    @Test fun terminalStateAloneCannotReactivateHomeBeforeCleanup() {
        assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(WalkSessionState.ENDED, ending = true))
        assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(WalkSessionState.ENDED, selected = true))
        assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(WalkSessionState.ENDED, preparing = true))
        assertEquals(WalkInteractionAreaPolicy.Area.MAIN, area(WalkSessionState.ENDED))
    }
    @Test fun aNewDestinationAfterEndingBelongsToANewPreparation() {
        assertEquals(WalkInteractionAreaPolicy.Area.MAIN, area(WalkSessionState.ENDED))
        assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(WalkSessionState.ENDED, selected = true))
        assertEquals(WalkInteractionAreaPolicy.Area.WALK, area(WalkSessionState.READY, selected = true))
    }
}
