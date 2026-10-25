package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.DestinationCoordinateCandidate
import kr.co.hanium.dreamup.walksafe.navigation.DestinationCoordinateEvidence
import kr.co.hanium.dreamup.walksafe.navigation.DestinationCoordinateKind
import kr.co.hanium.dreamup.walksafe.navigation.PendingExplicitRouteStart
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four source checks verify Main's wiring, not Activity/network execution. The remaining
 * checks use the route owner's real context value and production PendingExplicitRouteStart API.
 */
class MainActivityDestinationCoordinateEvidenceIntegrationTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    private val acquisition = owner("routeAcquisitionController", "navigation/session/RouteAcquisitionController.kt")
    private val archive = owner("routeArchiveController", "navigation/session/RouteArchiveController.kt")

    @Test
    fun sourceSelectionPassesTheApprovedResultPointAndEvidenceTogether() {
        val confirmation = functionBlock("fun openNativeDestinationConfirmation(")
        val entry = owner("nativeGuidanceEntryController", "navigation/start/NativeGuidanceEntryController.kt")
        val start = MainActivityRefactorSource.member(entry, "start")
        val handoff = MainActivityRefactorSource.member(source, "handOffNativeDestinationStart")
        val workflow = owner("nativeWorkflowController", "ui/workflow/NativeWorkflowController.kt")
        val workflowHandoff = MainActivityRefactorSource.member(workflow, "handOffNativeDestinationStart")
        val selection = functionBlock("fun onDestinationSelected(")

        assertTrue(confirmation.contains("pendingUiDestination = result"))
        assertTrue(MainActivityRefactorSource.member(source, "startNativeDestinationGuidance")
            .contains("nativeGuidanceEntryController.start()"))
        assertTrue(start.contains("val destination = workflow.pendingUiDestination ?: return"))
        assertTrue(start.contains("actions.handOffDestinationStart(destination)"))
        assertTrue(MainActivityRefactorSource.composition(source, "nativeGuidanceEntryController")
            .contains("override fun handOffDestinationStart(destination: DestinationSearchResult) { handOffNativeDestinationStart(destination) }"))
        assertTrue(handoff.contains("onDestinationSelected(it, retainExplicitStartUntilTrustedLocation = true)"))
        assertTrue(handoff.contains("nativeWorkflowController.handOffNativeDestinationStart(destination, prepare, select, refreshUi, navigationAdmitted)"))
        assertTrue(workflowHandoff.contains("val started = select(destination)"))
        val request = selection.substringAfter("requestRoute(")
        assertTrue(request.contains("result.point,"))
        assertTrue(request.contains("destinationCoordinateEvidence = result.coordinateEvidence"))
        assertFalse(selection.contains("destinationCoordinateEvidenceFor("))
    }

    @Test
    fun sourceGpsWaitTimeoutAndConsumptionCarryTheSamePointEvidenceContext() {
        val contextFactory = MainActivityRefactorSource.member(acquisition, "explicitRouteStartContextOrNull")
        val ownedContext = MainActivityRefactorSource.member(acquisition, "ownedExplicitRouteStartContextOrNull")
        val retain = MainActivityRefactorSource.member(acquisition, "retainPendingExplicitRouteStart", occurrence = 1)
        val continuation = MainActivityRefactorSource.member(acquisition, "continuePendingExplicitRouteStartIfReady")
        val resume = MainActivityRefactorSource.member(acquisition, "continueAdmittedExplicitRouteStartIfReady")
        val request = MainActivityRefactorSource.member(acquisition, "requestRoute", occurrence = 1)

        val contextDeclaration = acquisition.substringAfter("internal data class ExplicitRouteStartContext(")
            .substringBefore("\n    )")
        assertTrue(contextDeclaration.contains("val walkEpoch: WalkRuntimeEpoch"))
        assertTrue(contextDeclaration.contains("val destination: RoutePoint"))
        assertTrue(contextDeclaration.contains("val destinationCoordinateEvidence: DestinationCoordinateEvidence? = null"))
        assertTrue(contextFactory.contains("return ownedExplicitRouteStartContextOrNull(destination, destinationCoordinateEvidence)"))
        assertTrue(ownedContext.contains("if (currentDestination != destination) return null"))
        assertTrue(ownedContext.contains("destinationCoordinateEvidence = destinationCoordinateEvidence"))
        assertTrue(retain.contains("explicitRouteStartContextOrNull(destination, destinationCoordinateEvidence)"))
        assertTrue(retain.contains("context.destination,"))
        assertTrue(retain.contains("context.destinationCoordinateEvidence,"))
        assertTrue(retain.contains("expireIfCurrent("))
        assertTrue(continuation.contains("continueAdmittedExplicitRouteStartIfReady()"))
        assertTrue(resume.contains("request.context.destination,"))
        assertTrue(resume.contains("request.context.destinationCoordinateEvidence,"))
        assertTrue(resume.contains("DestinationCoordinateEvidence?) -> Unit = ::requestRoute"))
        assertTrue(resume.indexOf("consumeIfCurrent(") in 0 until resume.indexOf("requestNavigation("))
        assertTrue(resume.contains("retained.destination,"))
        assertTrue(resume.contains("retained.destinationCoordinateEvidence,"))
        assertTrue(request.contains("retainPendingExplicitRouteStart(destination, requestedDestinationCoordinateEvidence)"))
        assertTrue(request.indexOf("it.copy(candidates = it.candidates.toList())") in
            0 until request.indexOf("val origin = facts.currentLocation"))
    }

    @Test
    fun sourceRerouteUsesOnlyInstalledDestinationEvidenceAndManualInputStaysUnknown() {
        val commands = owner("voiceInteractionControllers.commands", "voice/interaction/VoiceCommandInteractionController.kt")
        val reroute = MainActivityRefactorSource.member(commands, "requestRerouteFromVoice", occurrence = 1)
        val manual = functionBlock("private fun onRouteButtonClicked()")
        val request = MainActivityRefactorSource.member(acquisition, "requestRoute")

        assertTrue(reroute.contains("destinationCoordinateEvidence = routeRun().navigator.destinationCoordinateEvidenceFor(destination)"))
        assertTrue(reroute.indexOf("if (!decision.shouldReroute)") in 0 until reroute.indexOf("route.requestRoute("))
        assertFalse(reroute.contains("storedRouteSnapshot"))
        assertTrue(manual.contains("val destination = currentDestination ?: parseDestinationInput()"))
        assertTrue(manual.contains("routeAcquisitionController.requestUserDestination(destination)"))
        MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        val userDestination = MainActivityRefactorSource.member(acquisition, "requestUserDestination")
        assertTrue(userDestination.contains("requestRoute(destination, reason = \"user_destination\")"))
        assertFalse(userDestination.contains("destinationCoordinateEvidence"))
        assertFalse(manual.contains("destinationCoordinateEvidence"))
        assertTrue(request.contains("destinationCoordinateEvidence: DestinationCoordinateEvidence? = null"))
    }

    @Test
    fun sourceCurrentLeaseSuccessStoresAndInstallsTheRequestCopyWithoutArchiveSubstitution() {
        val request = MainActivityRefactorSource.member(acquisition, "requestRoute", occurrence = 1)
        val storeIndex = request.indexOf("archive.submitRouteSnapshotResponseStorage(")
        val installIndex = request.indexOf("installRequestedRoute(route, destination, origin,")
        val store = MainActivityRefactorSource.member(archive, "submitRouteSnapshotResponseStorage")
        val save = MainActivityRefactorSource.member(archive, "routeSnapshotResponseArchive")
        val install = MainActivityRefactorSource.member(acquisition, "installRequestedRoute", occurrence = 1)

        assertTrue(request.indexOf("routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)") in 0 until storeIndex)
        assertTrue(request.indexOf("if (!isCurrentRouteRequest() || !isRouteActive) {") in 0 until storeIndex)
        assertTrue(request.indexOf("if (installLocationStatus != RouteResponseInstallLocationStatus.CONNECTED &&") in 0 until storeIndex)
        assertTrue(request.indexOf("installLocationStatus != RouteResponseInstallLocationStatus.APPROACH_REQUIRED)") in 0 until storeIndex)
        assertTrue(storeIndex in 0 until installIndex)
        val queuedStore = request.substring(storeIndex, request.indexOf(".thenAccept", storeIndex))
        assertTrue(queuedStore.contains("::storageAdmissionCurrent, scope, route, destination,"))
        assertTrue(queuedStore.contains("requestedDestinationCoordinateEvidence"))
        assertTrue(store.contains("val provenance = evidence?.copy(candidates = evidence.candidates.toList())"))
        assertTrue(store.contains("lane.submit<StoredRouteSnapshot?>(lease, null, current)"))
        assertTrue(store.contains("routeSnapshotResponseArchive(store, scope, captured, destination, provenance)"))
        assertTrue(save.contains("store.saveFirstRoute(scope = scope, route = route, destination = destination,"))
        assertTrue(save.contains("destinationCoordinateEvidence = destinationCoordinateEvidence"))
        val completion = request.substring(request.indexOf(".thenAccept", storeIndex), installIndex)
        assertTrue(completion.contains("if (!storageAdmissionCurrent() ||"))
        assertTrue(completion.contains("!routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)"))
        assertTrue(completion.contains("if (completedLocationStatus != RouteResponseInstallLocationStatus.CONNECTED &&"))
        assertTrue(completion.contains("completedLocationStatus != RouteResponseInstallLocationStatus.APPROACH_REQUIRED)"))
        assertTrue(request.substring(installIndex).contains("requestedDestinationCoordinateEvidence, reason,"))
        assertTrue(install.contains("destination = destination"))
        assertTrue(install.contains("destinationCoordinateEvidence = coordinateEvidence"))
        assertFalse(request.contains("storedRouteSnapshot.destinationCoordinateEvidence"))
        assertFalse(request.contains("destinationCoordinateEvidenceFor("))
        assertEquals(1, Regex("routeNavigator\\.setRoute\\(").findAll(install).count())
    }

    @Test
    fun actualRouteOwnerContextEqualityDistinguishesAllProviderKindsAtTheSamePoint() {
        val contexts = DestinationCoordinateKind.entries.map { kind ->
            val evidence = evidence(kind)
            context(evidence).also { original ->
                assertEquals(original, context(evidence.copy(candidates = evidence.candidates.toList())))
                assertEquals(evidence, contextEvidence(original))
                assertNotEquals(original, context(null))
                assertNotEquals(original, context(evidence, destination = destination.copy(name = "다른 이름")))
            }
        }

        assertEquals(4, contexts.toSet().size)
        assertEquals(context(null), context(null))
    }

    @Test
    fun actualPendingConsumesEachOriginalEvidencePacketOnceIncludingLegacyUnknown() {
        val packets = DestinationCoordinateKind.entries.map(::evidence) + listOf(null)
        packets.forEach { evidence ->
            val pending = PendingExplicitRouteStart<Any>()
            val retained = context(evidence)
            val request = pending.replace(retained, nowElapsedRealtimeMs = 1_000L)

            val consumed = pending.consumeIfCurrent(request, context(evidence), nowElapsedRealtimeMs = 2_000L)
            assertSame(retained, consumed)
            assertEquals(evidence, contextEvidence(requireNotNull(consumed)))
            assertNull(pending.consumeIfCurrent(request, context(evidence), nowElapsedRealtimeMs = 2_001L))
        }
    }

    @Test
    fun actualPendingOldRequestCannotConsumeOrExpireSamePointWithReplacementEvidence() {
        val pending = PendingExplicitRouteStart<Any>()
        val oldContext = context(evidence(DestinationCoordinateKind.NOOR))
        val newContext = context(evidence(DestinationCoordinateKind.FRONT))
        val oldRequest = pending.replace(oldContext, nowElapsedRealtimeMs = 1_000L)
        val newRequest = pending.replace(newContext, nowElapsedRealtimeMs = 2_000L)

        assertNull(pending.consumeIfCurrent(oldRequest, oldContext, nowElapsedRealtimeMs = 3_000L))
        assertFalse(pending.expireIfCurrent(oldRequest, oldContext, nowElapsedRealtimeMs = 16_000L))
        assertSame(newRequest, pending.currentOrNull())
        assertSame(newContext, pending.consumeIfCurrent(newRequest, context(evidence(DestinationCoordinateKind.FRONT)), 3_000L))
    }

    @Test
    fun actualPendingTimeoutRejectsChangedEvidenceOrOwnerAndCancellationClearsThePacket() {
        val originalEvidence = evidence(DestinationCoordinateKind.LAT)
        val original = context(originalEvidence)
        listOf(
            context(evidence(DestinationCoordinateKind.NEW)),
            context(originalEvidence, walkSessionId = "new-walk"),
            context(originalEvidence, recoveryGeneration = 1L),
            context(originalEvidence, gatewayGeneration = 8L),
        ).forEach { changed ->
            val pending = PendingExplicitRouteStart<Any>()
            val request = pending.replace(original, 1_000L)
            assertFalse(pending.expireIfCurrent(request, changed, 16_000L))
            assertNull(pending.currentOrNull())
        }
        val pending = PendingExplicitRouteStart<Any>()
        val request = pending.replace(original, 1_000L)
        assertNull(pending.consumeIfCurrent(request, context(originalEvidence), 16_000L))
        assertSame(request, pending.currentOrNull())
        assertTrue(pending.expireIfCurrent(request, context(originalEvidence), 16_000L))
        pending.replace(original, 17_000L)
        pending.cancel()
        assertNull(pending.currentOrNull())
    }

    private val destination = RoutePoint(36.123, 128.456, "선택 목적지")

    private fun evidence(kind: DestinationCoordinateKind) = DestinationCoordinateEvidence(
        selectedKind = kind,
        candidates = listOf(DestinationCoordinateCandidate(kind, destination)),
    )

    private fun context(
        evidence: DestinationCoordinateEvidence?,
        walkSessionId: String = "walk",
        recoveryGeneration: Long = 0L,
        gatewayGeneration: Long = 7L,
        destination: RoutePoint = this.destination,
    ): RouteAcquisitionController.ExplicitRouteStartContext = RouteAcquisitionController.ExplicitRouteStartContext(
        walkEpoch = WalkRuntimeEpoch(walkSessionId, recoveryGeneration = recoveryGeneration),
        gatewayGeneration = gatewayGeneration,
        destination = destination,
        destinationCoordinateEvidence = evidence,
    )

    private fun contextEvidence(context: Any): DestinationCoordinateEvidence? =
        (context as RouteAcquisitionController.ExplicitRouteStartContext).destinationCoordinateEvidence

    private fun owner(property: String, path: String): String {
        MainActivityRefactorSource.composition(source, property)
        return File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()
    }

    private fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)
}
