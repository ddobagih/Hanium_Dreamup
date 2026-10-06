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
        val clear = function("private fun clearGatewayNavigationState()")
        assertTrue(clear.contains("reconcileRouteSnapshotAccount(GatewaySessionProcessCoordinator.snapshot())"))
        assertFalse(clear.contains("WalkSessionState.ACTIVE"))
        val session = function("private fun onGatewayProcessSessionChanged(")
        assertTrue(session.contains("reconcileRouteSnapshotAccount(snapshot)"))
        val actorBinding = function("private fun bindFirstRunVerifiedActorForTraining()")
        assertTrue(actorBinding.contains("clearGatewaySession(logoutRemote = true)"))
        val owner = function("private fun routeSnapshotOwnerOrNull(")
        assertTrue(owner.contains("session.backendAccountGeneration"))
        assertTrue(owner.contains("session.familyId"))
        assertFalse(owner.contains("snapshot().generation"))
        assertFalse(owner.contains("session.rotation"))
    }

    @Test
    fun deviceDeletionEvidenceIncludesSnapshotDeletionFailure() {
        val purge = function("private fun purgeAccountDeletionLocalDataOnCleanupThread()")
        assertTrue(purge.contains("AccountDeletionLocalStore.ROUTE_SNAPSHOT to {"))
        assertTrue(purge.contains("purgeEncryptedRouteSnapshotForAccount()"))
        assertTrue(purge.contains("return inventory.purge().isEmpty()"))
        assertTrue(source.contains("result = if (purgeSucceeded) \"DELETED\" else \"FAILED\""))
    }

    @Test
    fun finalResetHasAnActualSnapshotPurgeParticipantAndCannotCompleteAfterItsFailure() {
        // This function has an expression body; the first brace is a callback, not its body.
        val coordinator = source.substringAfter("private fun createAccountDeletionResetCoordinator(")
            .substringBefore("private fun clearAccountDeletionPlainResetArtifacts()")
        assertTrue(coordinator.contains("resetPlainPreferences = ::clearAccountDeletionPlainResetArtifacts"))
        val reset = function("private fun clearAccountDeletionPlainResetArtifacts()")
        assertTrue(reset.contains("val routeSnapshotPurged = purgeEncryptedRouteSnapshotForAccount()"))
        assertTrue(reset.contains("return routeSnapshotPurged && committed"))
        val accountPurge = function("private fun purgeEncryptedRouteSnapshotForAccount()")
        assertTrue(accountPurge.contains("routeSnapshotStore.bindAccount(null)"))
        assertTrue(accountPurge.contains("routeSnapshotPurgeFailed = !purged"))
    }

    @Test
    fun normalEndAndDestroyReleaseTheProcessWalkWhileExpiryDeletesOnlyItsCapturedScope() {
        val transition = function("private fun transitionWalkSession(")
        assertTrue(transition.contains("updateRouteSnapshotWalkOwnership(transition)"))
        val ownership = function("private fun updateRouteSnapshotWalkOwnership(")
        assertTrue(ownership.contains("WalkSessionState.ENDED, WalkSessionState.SAFE_STOP"))
        assertTrue(ownership.contains("routeSnapshotStore.endWalk()"))
        assertTrue(ownership.contains("routeSnapshotStore.beginWalk(scope)"))
        val expiry = function("private fun scheduleEncryptedRouteSnapshotExpiry(")
        assertTrue(expiry.contains("purgeEncryptedRouteSnapshot(scope)"))
        assertTrue(source.contains("if (::routeSnapshotStore.isInitialized) routeSnapshotStore.detach()"))
    }

    @Test
    fun startupReadsOnlyExpiryMetadataAndRequestsOnlySaveThroughAScopedLease() {
        val startup = source.substringAfter("val routeSnapshotNowEpochMs =")
            .substringBefore("accountDeletionIntentFence =")
        assertTrue(startup.contains("routeSnapshotStore.expiryMetadata(routeSnapshotNowEpochMs)"))
        assertFalse(startup.contains("routeSnapshotStore.load("))
        val request = function("private fun requestRoute(")
        assertTrue(request.contains("RouteSnapshotScope(owner, expectedWalkEpoch.walkSessionId)"))
        assertTrue(request.contains("routeSnapshotStore.load(scope)"))
        assertFalse(request.contains("replaceEndedWalk ="))
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
