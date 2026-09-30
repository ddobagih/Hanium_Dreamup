package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks; controlled async execution is covered by the source-extracted Main harness. */
class MainActivityRouteReplacementStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun function(name: String): String = source.substringAfter("private fun $name(")
        .substringBefore("\n    private ")

    @Test fun selectingAReplacementRetainsTheInstalledRouteAndItsDestination() {
        val selection = function("onDestinationSelected")
        assertTrue(selection.contains("val preserveExistingRoute = isRouteActive && routeNavigator.hasRoute()"))
        assertTrue(selection.contains("preserveExistingRoute = preserveExistingRoute"))
        assertFalse(selection.contains("resetRouteState()"))
        val pending = selection.substringAfter("if (!preserveExistingRoute) {")
            .substringBefore("updateRouteButtonText()")
        assertTrue(pending.contains("currentDestination = result.point"))
        assertTrue(pending.contains("destinationLatInput.setText"))
        assertTrue(pending.contains("destinationLngInput.setText"))
    }

    @Test fun routeAndDestinationCommitOnlyAfterTheCurrentValidatedResponse() {
        val request = function("requestRoute")
        val owner = request.substringAfter("fun isCurrentRouteRequest(): Boolean")
            .substringBefore("updateNavigationStatus(")
        assertTrue(owner.contains("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(owner.contains("currentSession.generation == expectedGatewaySessionGeneration"))
        assertTrue(owner.contains("currentSession.session === gatewaySession"))
        assertTrue(owner.contains("currentReporterUserId() == gatewaySession.actorId"))
        assertTrue(request.contains("if (!preserveExistingRoute) currentDestination = destination"))
        val install = request.indexOf("routeNavigator.setRoute(")
        assertTrue(install > request.indexOf("routeCall.execute()"))
        assertTrue(install > request.indexOf("routeSnapshotStore.saveFirstRoute("))
        assertTrue(request.indexOf("                        currentDestination = destination", install) > install)
        assertFalse(request.contains("purgeEncryptedRouteSnapshot()"))
    }

    @Test fun requestCancellationAndNavigationEndHaveDifferentRouteOwnership() {
        val cancel = function("cancelActiveRouteRequest")
        assertTrue(cancel.contains("preserveExistingRoute: Boolean = true"))
        assertTrue(cancel.contains("preserveExistingRoute = preserveExistingRoute"))
        val stop = source.substringAfter("private fun stopNavigationFromVoice(expectedToken:")
            .substringBefore("\n    private ")
        assertTrue(stop.contains("cancelActiveRouteRequest(preserveExistingRoute = false)"))
        val request = function("requestRoute")
        assertTrue(request.contains("if (reason == \"off_route\") {\n                retainRouteAfterRerouteFailure(message)"))
        assertTrue(request.contains("enterWalkSessionSafetyStopAndCancelOutputs(\"route_consecutive_failures\")"))
        assertTrue(request.contains("if (navigationFailure.isUserSessionAuthentication)"))
        assertTrue(request.contains("expectedAuthenticationFailure ="))
        assertTrue(request.contains("navigationFailure.isUserSessionAuthentication,"))
    }
}
