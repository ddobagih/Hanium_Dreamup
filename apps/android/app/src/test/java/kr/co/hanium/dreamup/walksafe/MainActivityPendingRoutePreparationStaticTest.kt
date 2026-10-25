package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-function start wiring; executable token/ownership tests cover the underlying gates. */
class MainActivityPendingRoutePreparationStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun anExplicitStartDuringStoragePreparationKeepsItsRequestAndCoordinateEvidence() {
        MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val body = method("requestRoute", 1).substringBefore("val expectedWalkEpoch")
        assertTrue(body.contains("archive.preparationPending && retainExplicitStartUntilTrustedLocation"))
        assertTrue(body.contains("!preserveExistingRoute && facts.locationPermissionReady"))
        assertTrue(body.contains("currentDestination = destination"))
        assertTrue(body.contains("it.copy(candidates = it.candidates.toList())"))
        assertTrue(body.contains("retainPendingExplicitRouteStart(destination, evidence, waitForNewFixAfterMs = null,"))
        assertTrue(MainActivityRefactorSource.member(source, "requestRoute", 1).contains("routeAcquisitionController.requestRoute("))
        assertTrue(ReportStaticSourceInspector.blockAfter(MainActivityRefactorSource.factory(source, "createNavigationSessions"), "facts = { object : AcquisitionActivityFacts").contains("override val locationPermissionReady get() = isRouteLocationPermissionReady()"))
    }

    @Test
    fun storageAndActualGpsAreCheckedBeforeConsumingTheGuardedStart() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val body = method("continueAdmittedExplicitRouteStartIfReady")
        val context = body.indexOf("if (current == null)")
        val storage = body.indexOf("if (!archive.routeSnapshotPurgeFenceAllowsRoute()) return")
        val gps = body.indexOf("val location = trustedLocation() ?: return")
        val consume = body.indexOf("pendingExplicitRouteStart.consumeIfCurrent(")
        assertTrue(context >= 0 && storage > context && gps > storage && consume > gps)
        assertTrue(body.contains("trustedLocation: () -> TrustedLocation? = { facts.currentLocation }"))
        val pending = method("continuePendingExplicitRouteStartIfReady")
        assertTrue(pending.contains("if (!explicitStartOwnerCurrent(request.context))"))
        val owner = method("explicitStartOwnerCurrent")
        assertTrue(owner.contains("facts.foreground && !facts.activityDestroyed && facts.locationPermissionReady"))
        assertTrue(pending.contains("if (!facts.navigationAllowed)"))
        assertTrue(pending.contains("if (!sameInitialOwner) clearPendingExplicitRouteStart()"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeAcquisitionController").contains("override val currentLocation get() = positioning.value.currentRouteLocationOrNull()"))
    }

    @Test
    fun onlyCommittedSuccessfulStoragePreparationContinuesTheExistingStart() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val body = method("submitRouteSnapshotOwnership")
        val guard = body.indexOf("if (!committed) return@post")
        val continuation = body.indexOf("if (result?.ready == true) onPrepared()")
        assertTrue(guard >= 0 && continuation > guard)
        assertTrue(body.contains("lane.commit("))
        assertTrue(MainActivityRefactorSource.composition(source, "routeArchiveController").contains("{ acquisition.value.continuePendingExplicitRouteStartIfReady() }"))
    }

    @Test
    fun automaticStartKeepsTheGuidanceDestinationAndScreen() {
        MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val body = method("continueAdmittedExplicitRouteStartIfReady")
        assertTrue(body.contains("if (facts.guidancePage) {\n                effects.renderMain()\n            } else {"))
        val input = MainActivityRefactorSource.member(source, "currentRouteGuidanceDisplayInput")
        assertTrue(input.contains("routeAcquisitionController.requestPending || routeAcquisitionController.explicitStart != null"))
        val wire = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(ReportStaticSourceInspector.blockAfter(wire, "facts = { object : AcquisitionActivityFacts").contains("override val guidancePage get() = nativeUiPage == NativeUiPage.GUIDANCE"))
        assertTrue(ReportStaticSourceInspector.blockAfter(wire, "effects = { object : AcquisitionActivityEffects").contains("override fun renderMain() = renderMainUi()"))
    }

    @Test
    fun pauseAnnouncementsRequireAnExistingRouteWhileThePositionSafetyStateStillUpdates() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val body = method("applyPositionConfidenceDecision")
        val state = body.indexOf("positionGuidancePaused = decision.guidancePaused")
        val announcement = body.indexOf("decision.announcementRequest?.takeIf")
        assertTrue(state >= 0 && announcement > state)
        assertTrue(body.contains("emitAnnouncements && decision.guidancePaused && isRouteActive && routeNavigator.hasRoute()"))
        assertTrue(body.contains("confidenceLease.delivered("))
        val wire = MainActivityRefactorSource.composition(source, "routeGuidanceController")
        assertTrue(wire.contains("binding.coordinator.commitAnnouncementDelivered("))
        assertTrue(MainActivityRefactorSource.member(source, "applyPositionConfidenceDecision").contains("routeGuidanceController.applyPositionConfidenceDecision(snapshot, emitAnnouncements)"))
    }
}
