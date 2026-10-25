package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks complement executable encrypted-storage/lease tests; no Android UI execution claim. */
class MainActivityRouteSnapshotOwnershipStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun accountClearPurgesIndependentlyOfTheWalkStateAndAcceptedLoginRebindsTheOwner() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val acquisition = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        val clear = MainActivityRefactorSource.member(acquisition, "clearGatewayNavigationState")
        assertTrue(clear.contains("archive.reconcileRouteSnapshotAccount(facts.gateway)"))
        assertFalse(clear.contains("WalkSessionState.ACTIVE"))
        val lifecycle = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt")
        val session = MainActivityRefactorSource.member(lifecycle, "onGatewayProcessSessionChanged")
        assertTrue(session.contains("routes.reconcileAccount(snapshot)"))
        val onboarding = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/onboarding/FirstRunOnboardingController.kt")
        val actorBinding = MainActivityRefactorSource.member(onboarding, "bindFirstRunVerifiedActorForTraining")
        assertTrue(actorBinding.contains("gateway.clearSession()"))
        val owner = method("routeSnapshotOwnerOrNull")
        assertTrue(owner.contains("session.backendAccountGeneration"))
        assertTrue(owner.contains("session.familyId"))
        assertFalse(owner.contains("snapshot().generation"))
        assertFalse(owner.contains("session.rotation"))
        assertTrue(MainActivityRefactorSource.member(source, "clearGatewayNavigationState").contains("routeAcquisitionController.clearGatewayNavigationState()"))
        val actorWire = MainActivityRefactorSource.property(source, "actorSessionLifecycleCoordinator")
        assertTrue(actorWire.contains("override fun reconcileAccount(snapshot: GatewaySessionProcessSnapshot) { this@MainActivity.routeArchiveController.reconcileRouteSnapshotAccount(snapshot) }"))
        val firstRunWire = MainActivityRefactorSource.property(source, "firstRunOnboardingController")
        assertTrue(firstRunWire.contains("override fun clearSession() { this@MainActivity.clearGatewaySession(logoutRemote = true) }"))
        val archiveWire = MainActivityRefactorSource.composition(source, "routeArchiveController")
        assertTrue(archiveWire.contains("val lane = resources.lane()"))
        assertTrue(archiveWire.contains("RouteArchiveController(lane,"))
        assertTrue(archiveWire.contains("val activity = archiveFacts()"))
        assertTrue(archiveWire.contains("override fun gatewaySnapshot(): GatewaySessionProcessSnapshot = activity.gatewaySnapshot()"))
        val archivePorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(archivePorts.contains("lane = { ROUTE_SNAPSHOT_IO_LANE }"))
        val archiveFacts = ReportStaticSourceInspector.blockAfter(archivePorts, "archiveFacts =")
        assertTrue(archiveFacts.contains("object : ArchiveActivityFacts"))
        assertTrue(Regex("""(?m)^\s*override fun gatewaySnapshot\(\) = GatewaySessionProcessCoordinator\.snapshot\(\)\s*$""").containsMatchIn(archiveFacts))
        val reconcile = method("reconcileRouteSnapshotAccount")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(reconcile,
            "if (facts.deletionBlocked())", "invalidateRouteSnapshotResponseLease()",
            "routeSnapshotOwnerOrNull(snapshot.session)", "submitRouteSnapshotOwnership(snapshot,",
        ))
    }

    @Test
    fun deviceDeletionEvidenceIncludesSnapshotDeletionFailure() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionLocalDataPurger.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val purge = method("purgeLocalDataOnWorker")
        assertTrue(purge.contains("val routeSnapshotPurged = retry {"))
        assertTrue(purge.contains("participants.purgeRouteSnapshot()"))
        assertTrue(purge.contains("return routeSnapshotPurged && legacyQueuePurged"))
        val progress = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionProgressController.kt")
        assertTrue(progress.contains("result = if (purgeSucceeded) \"DELETED\" else \"FAILED\""))
        val assembly = MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
        assertTrue(assembly.contains("participants = participants({ speechOutputStorageCoordinator }, routeArchive)"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition").contains("routeArchive = { routeArchiveController }"))
        val wire = MainActivityRefactorSource.factory(source, "createPrivacyLocalPurge")
        assertTrue(wire.contains("override fun purgeRouteSnapshot() = route().purgeEncryptedRouteSnapshotForAccount()"))
        assertTrue(MainActivityRefactorSource.member(source, "purgeEncryptedRouteSnapshotForAccount").contains("routeArchiveController.purgeEncryptedRouteSnapshotForAccount()"))
    }

    @Test
    fun finalResetHasAnActualSnapshotPurgeParticipantAndCannotCompleteAfterItsFailure() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val resetSource = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/reset/AccountDeletionResetStorage.kt")
        val coordinator = MainActivityRefactorSource.member(resetSource, "createCoordinator")
        assertTrue(coordinator.contains("resetPlainPreferences = ::clearPlainResetArtifacts"))
        val reset = MainActivityRefactorSource.member(resetSource, "clearPlainResetArtifacts")
        assertTrue(reset.contains("val routeSnapshotPurged = routePurgeVerified()"))
        assertTrue(reset.contains("return routeSnapshotPurged && committed"))
        assertTrue(reset.indexOf("if (!routeSnapshotPurged) return false") in 0 until reset.indexOf("val committed ="))
        val resetWire = MainActivityRefactorSource.composition(source, "accountDeletionResetStorage")
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition").contains("routeArchive = { routeArchiveController }"))
        assertTrue(resetWire.contains("val preparation = routeArchive().resetParticipant"))
        assertTrue(resetWire.contains("preparation != null && preparation.verified.get() && routeArchive().routeSnapshotResetPreparationIsCurrent(preparation)"))
        val accountPurge = method("submitRouteSnapshotAccountPurgeStorage")
        assertTrue(accountPurge.contains("store.bindAccount(null)"))
        assertTrue(accountPurge.contains("lease.purgeFailed = !purged"))
        val ack = method("awaitRouteSnapshotAccountPurgeOnWorker")
        assertTrue(ack.contains("preparation.verified.set(purged)"))
        assertTrue(ack.contains("return committed && purged"))
        assertTrue(method("publishRouteSnapshotAccountPurgeResult").contains("routeSnapshotPurgeFailed = !purged"))
        assertTrue(method("routeSnapshotResetPreparationIsCurrent").contains("lane.isCurrent(preparation.lease, preparation.operation)"))
        assertTrue(MainActivityRefactorSource.member(source, "createAccountDeletionResetCoordinator").contains("accountDeletionResetStorage.createCoordinator(noBackupRoot)"))
    }

    @Test
    fun normalEndAndDestroyReleaseTheProcessWalkWhileExpiryDeletesOnlyItsCapturedScope() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val transitionSource = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionTransitionController.kt")
        val transition = MainActivityRefactorSource.member(transitionSource, "transitionWalkSession")
        assertTrue(transition.contains("effects.updateRouteSnapshotWalkOwnership(transition)"))
        val ownership = method("updateRouteSnapshotWalkOwnership")
        assertTrue(ownership.contains("WalkSessionState.ENDED, WalkSessionState.SAFE_STOP"))
        assertTrue(ownership.contains("submitRouteSnapshotOwnership(gateway, transition.current, endingWalk)"))
        val storage = method("submitRouteSnapshotOwnershipStorage")
        assertTrue(storage.contains("store.endWalk()"))
        assertTrue(storage.contains("store.beginWalk(scope)"))
        assertTrue(storage.contains("lease.walkScope?.walkSessionId == endingWalk"))
        assertTrue(storage.indexOf("store.endWalk()") < storage.indexOf("store.beginWalk(scope)"))
        val expiry = method("scheduleEncryptedRouteSnapshotExpiry")
        assertTrue(expiry.contains("onEncryptedRouteSnapshotExpired(scope, lease, operation)"))
        assertTrue(method("onEncryptedRouteSnapshotExpired").contains("operation, scope, expiryClaimIsCurrent)"))
        assertTrue(method("submitRouteSnapshotScopePurge").contains("store.clearWalk(scope)"))
        assertTrue(MainActivityRefactorSource.member(source, "onDestroy").contains("routeArchiveController.detach()"))
        assertTrue(method("detach").contains("lane.detach(routeSnapshotIoLease)"))
        val transitionWire = MainActivityRefactorSource.composition(source, "walkSessionTransitionController")
        assertTrue(transitionWire.contains("override fun updateRouteSnapshotWalkOwnership(transition: WalkSessionTransition) = this@MainActivity.routeArchiveController.updateRouteSnapshotWalkOwnership(transition)"))
        assertTrue(transitionWire.contains("lifecycle = { walkSessionLifecycle }"))
        val archiveWire = MainActivityRefactorSource.composition(source, "routeArchiveController")
        assertTrue(archiveWire.contains("val lane = resources.lane()"))
        assertTrue(archiveWire.contains("RouteArchiveController(lane,"))
        assertTrue(archiveWire.contains("val activity = archiveFacts()"))
        assertTrue(archiveWire.contains("override fun gatewaySnapshot(): GatewaySessionProcessSnapshot = activity.gatewaySnapshot()"))
        val archivePorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(archivePorts.contains("lane = { ROUTE_SNAPSHOT_IO_LANE }"))
        val archiveFacts = ReportStaticSourceInspector.blockAfter(archivePorts, "archiveFacts =")
        assertTrue(archiveFacts.contains("object : ArchiveActivityFacts"))
        assertTrue(Regex("""(?m)^\s*override fun gatewaySnapshot\(\) = GatewaySessionProcessCoordinator\.snapshot\(\)\s*$""").containsMatchIn(archiveFacts))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(transition,
            "lifecycle().handle(event, effects::beforePublication)", "effects.updateRouteSnapshotWalkOwnership(transition)",
        ))
    }

    @Test
    fun startupReadsOnlyExpiryMetadataAndRequestsOnlySaveThroughAScopedLease() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val lane = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteSnapshotIoLane.kt")
        val attach = MainActivityRefactorSource.member(lane, "attach")
        assertTrue(attach.contains("store.expiryMetadata(nowEpochMs)"))
        assertFalse(attach.contains("store.load("))
        assertTrue(method("initialize").contains("lane.attach { factory().also { routeSnapshotStore = it } }"))
        val acquisition = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        val request = MainActivityRefactorSource.member(acquisition, "requestRoute", 1)
        assertTrue(request.contains("RouteSnapshotScope(owner, expectedWalkEpoch.walkSessionId)"))
        val archiveBody = method("routeSnapshotResponseArchive")
        assertTrue(archiveBody.contains("if (!saved) null else store.load(scope, nowEpochMs = nowEpochMs)"))
        assertFalse(request.contains("replaceEndedWalk ="))
        assertFalse(archiveBody.contains("replaceEndedWalk ="))
        assertTrue(method("submitRouteSnapshotResponseStorage").contains("routeSnapshotResponseArchive(store, scope, captured, destination, provenance)"))
        assertTrue(MainActivityRefactorSource.member(source, "initializeRouteSnapshotOnIoWorker").contains("routeArchiveController.initialize {"))
        val archive = MainActivityRefactorSource.composition(source, "routeArchiveController")
        assertTrue(archive.contains("val lane = resources.lane()"))
        assertTrue(archive.contains("RouteArchiveController(lane,"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains("lane = { ROUTE_SNAPSHOT_IO_LANE }"))
    }

    private fun function(marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing $marker" }
        val opening = source.indexOf('{', start + marker.length)
        var depth = 0
        for (i in opening until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, i + 1)
            }
        }
        error("Unclosed $marker")
    }
}
