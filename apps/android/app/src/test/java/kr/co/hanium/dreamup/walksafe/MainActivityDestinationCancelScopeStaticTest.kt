package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-39: cancelling a search or a candidate choice, deleting a destination and ending guidance are
 * separate effects. "목적지 취소" never ends a running or requested route; "길안내 종료" does, and says so.
 */
class MainActivityDestinationCancelScopeStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val cancel = source.substringAfter("private fun cancelDestinationFromVoice() {")
        .substringBefore("private fun selectVoiceDestinationCandidate(")

    @Test fun anActiveOrRequestedRouteIsKeptAndTheEffectIsSaid() {
        // S06-39 review: "running" means an installed route, not merely a request in flight.
        assertTrue(cancel.contains("val routeRunning = isRouteActive && routeNavigator.hasRoute()"))
        assertFalse(cancel.contains("if (isRouteActive || routeRequestInFlight.get()) {"))
        val guard = cancel.indexOf("if (routeRunning) {")
        assertTrue(guard >= 0)
        assertTrue(guard < cancel.indexOf("resetRouteState()"))
        val kept = cancel.substring(guard).substringBefore("            return\n        }")
        assertFalse(kept.contains("resetRouteState("))
        // Only a confirmed replacement is cancelled, and the running route is preserved.
        assertFalse(kept.contains("cancelActiveRouteRequest(preserveExistingRoute = false)"))
        assertFalse(kept.contains("currentDestination = null"))
        assertTrue(kept.contains("if (destinationSearchInFlight || searchLocationAcquiring) cancelDestinationSearch()"))
        assertTrue(kept.contains("destinationSearchVoiceState = null"))
        assertTrue(kept.contains("clearHomeDestinationVoiceDialog()"))
        assertTrue(kept.contains("walkingDestinationChange.clear()"))
        assertTrue(kept.contains("진행 중인 길안내는 계속됩니다. 길안내를 끝내려면 길안내 종료라고 말해 주세요."))
    }

    @Test fun locationAcquisitionIsCancelledBeforeItsLateCallbackCanRestartSearch() {
        assertTrue(cancel.contains("val searchLocationAcquiring = destinationSearchLocationController?.isAcquiring == true"))
        val kept = cancel.substringAfter("if (routeRunning) {").substringBefore("            return\n        }")
        assertTrue(kept.contains("val hadSearch = destinationSearchInFlight || searchLocationAcquiring ||"))
        assertTrue(kept.contains("if (destinationSearchInFlight || searchLocationAcquiring) cancelDestinationSearch()"))
        val noRoute = cancel.substringAfter("            return\n        }")
        assertTrue(noRoute.contains("if (destinationSearchInFlight || searchLocationAcquiring) cancelDestinationSearch()"))
        val helper = source.substringAfter("private fun cancelDestinationSearch() {")
            .substringBefore("\n    }\n")
        assertTrue(helper.contains("cancelDestinationSearchLocation()"))
        // The controller's cancellation generation excludes an already queued fix callback.
        val location = source.substringAfter("private fun cancelDestinationSearchLocation() {")
            .substringBefore("\n    }\n")
        assertTrue(location.contains("destinationSearchLocationController?.cancel()"))
    }

    /** S06-39 review case 2: a confirmed replacement in flight is a destination choice and is cancelled. */
    @Test fun aConfirmedReplacementRequestInFlightIsCancelledAndTheRunningRouteKept() {
        val kept = cancel.substring(cancel.indexOf("if (routeRunning) {")).substringBefore("            return\n        }")
        assertTrue(
            kept.contains(
                "val replacementPending = routeRequestInFlight.get() &&\n" +
                    "                activeRouteRequestReason == \"user_destination\"",
            ),
        )
        assertTrue(kept.contains("if (replacementPending) cancelActiveRouteRequest(preserveExistingRoute = true)"))
        assertTrue(kept.contains("walkingDestinationChange.hasPending() ||\n                replacementPending"))
        assertTrue(kept.contains("목적지 검색과 후보 선택을 취소했습니다. "))
        // The reason matches what a confirmed walking change sends, and is recorded when the
        // request is admitted; an off-route reroute ("off_route") is never cancelled here.
        val select = source.substringAfter("private fun onDestinationSelected(")
            .substringBefore("private fun explainNavigationFeatureUnavailable()")
        assertTrue(select.contains("reason = \"user_destination\","))
        val request = source.substringAfter("private fun requestRoute(").substringBefore("private fun ")
        val admitted = request.indexOf("!routeRequestInFlight.compareAndSet(false, true)")
        val recorded = request.indexOf("activeRouteRequestReason = reason")
        assertTrue(admitted in 0 until recorded)
        val cancelHelper = source.substringAfter("private fun cancelActiveRouteRequest(preserveExistingRoute: Boolean = true) {")
            .substringBefore("private fun clearActiveRouteRequestState(")
        assertTrue(cancelHelper.contains("navigationRequests.cancelRoute()"))
        assertTrue(cancelHelper.contains("preserveExistingRoute = preserveExistingRoute"))
    }

    /** S06-39 review case 1: a first request with no route yet belongs to the destination being removed. */
    @Test fun aFirstRouteRequestInFlightIsCancelledWithTheDestinationAndSaidTruthfully() {
        val removal = cancel.substringAfter("            return\n        }")
        val first = removal.indexOf("val firstRequestCancelled = routeRequestInFlight.get()")
        assertTrue(first >= 0)
        // The call itself (the comment above the flag also names resetRouteState()).
        assertTrue(first < removal.indexOf("\n        resetRouteState()\n"))
        assertTrue(
            removal.contains("firstRequestCancelled -> \"목적지와 경로 요청을 취소했습니다. 길안내는 시작하지 않습니다.\""),
        )
        assertFalse(removal.contains("진행 중인 길안내는 계속됩니다"))
        val reset = source.substringAfter("private fun resetRouteState(").substringBefore("private fun ")
        assertTrue(reset.contains("navigationRequests.cancelRoute()"))
        assertTrue(reset.contains("routeRequestGeneration += 1"))
        assertTrue(reset.contains("routeRequestInFlight.set(false)"))
    }

    @Test fun withoutARouteOnlyTheDestinationIsRemovedAndNoRouteIsClaimed() {
        val removal = cancel.substringAfter("            return\n        }")
        assertTrue(removal.contains("resetRouteState()"))
        assertTrue(removal.contains("\"목적지를 취소했습니다.\""))
        assertFalse(cancel.contains("목적지와 진행 중인 경로를 취소했습니다."))
    }

    @Test fun endingGuidanceStaysTheExplicitSeparateCommand() {
        val stop = source.substringAfter("private fun stopNavigationFromVoice(expectedToken: RouteNavigatorDecisionToken?) {")
            .substringBefore("private fun updateVoiceCommandButton(")
        assertTrue(stop.contains("resetRouteState()"))
        assertTrue(stop.contains("길안내를 중지했습니다."))
    }
}
