package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-13: exercise the real gate transitions with a newly exposed recheck identity input.
 * The RED baseline exposes that input without enforcing it, preserving the original gate's
 * permission-only acceptance. This is domain behaviour, not an Android settings/Activity test
 * or evidence that the original API already accepted an account/walk/request identity.
 */
class PermissionRecoveryGateOwnerScopeTest {
    private val required = setOf(ObservedPermission.CAMERA, ObservedPermission.PRECISE_LOCATION)
    private val original = binding()

    @Test
    fun originalUnscopedRecheckAndCurrentPermissionAcknowledgementStillWork() {
        val awaiting = PermissionRecoveryGate()
            .blocked(required)
            .settingsPending()
            .recheckRequired()
            .completeFullRecheck(granted(), required, allPrerequisitesReady = true)

        assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, awaiting.state)
        assertTrue(awaiting.blocksAutomaticResourceStart)
        val acknowledged = awaiting.acknowledgeExplicitResume(granted(), required)
        assertEquals(PermissionRecoveryGateState.CLEAR, acknowledged.state)
        assertFalse(acknowledged.blocksAutomaticResourceStart)
    }

    @Test
    fun matchingOwnerWalkAndRequestNeedTheExplicitAcknowledgementToClear() {
        val awaiting = awaiting(original)

        assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, awaiting.state)
        assertEquals(original, awaiting.recheckBinding)
        assertTrue(awaiting.blocksAutomaticResourceStart)
        assertEquals(
            PermissionRecoveryGateState.CLEAR,
            awaiting.acknowledgeExplicitResume(granted(), required, original).state,
        )
    }

    @Test
    fun anotherActorCannotAcknowledgeThePreviousActorsRecheck() {
        assertScopeRejected(original.copy(actorBindingSha256 = "b".repeat(64)))
    }

    @Test
    fun anotherGatewaySessionGenerationCannotAcknowledgeTheOldRecheck() {
        assertScopeRejected(original.copy(gatewaySessionGeneration = 8L))
    }

    @Test
    fun anotherWalkCannotAcknowledgeThePreviousWalksRecheck() {
        assertScopeRejected(original.copy(walkEpoch = WalkRuntimeEpoch("walk-b", 2L)))
    }

    @Test
    fun anotherRecoveryGenerationCannotAcknowledgeTheOldRecheck() {
        assertScopeRejected(original.copy(walkEpoch = WalkRuntimeEpoch("walk-a", 3L)))
    }

    @Test
    fun anotherRecheckRequestCannotAcknowledgeTheOldResult() {
        assertScopeRejected(original.copy(recheckRequestId = "recheck-2"))
    }

    @Test
    fun anOldFullRecheckResultCannotReplaceANewerRequestsBinding() {
        val newer = original.copy(recheckRequestId = "recheck-2")
        val pending = awaiting(original).requestPending(required, newer)

        val late = pending.completeFullRecheck(
            observed = granted(),
            requiredPermissions = required,
            allPrerequisitesReady = true,
            resultBinding = original,
        )

        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, late.state)
        assertEquals(newer, late.recheckBinding)
        assertTrue(late.blocksAutomaticResourceStart)
        val currentResult = late.completeFullRecheck(granted(), required, true, newer)
        assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, currentResult.state)
        assertEquals(
            PermissionRecoveryGateState.CLEAR,
            currentResult.acknowledgeExplicitResume(granted(), required, newer).state,
        )
    }

    @Test
    fun aBoundGateCannotBeClearedThroughTheLegacyPermissionOnlyAcknowledgement() {
        val result = awaiting(original).acknowledgeExplicitResume(granted(), required)

        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, result.state)
        assertEquals(original, result.recheckBinding)
        assertTrue(result.blocksAutomaticResourceStart)
    }

    @Test
    fun aBoundGateCannotBeClearedThroughTheLegacyNoObservationAcknowledgement() {
        val result = awaiting(original).acknowledgeExplicitResume()

        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, result.state)
        assertEquals(original, result.recheckBinding)
        assertTrue(result.blocksAutomaticResourceStart)
    }

    @Test
    fun aLegacyFullRecheckCannotRemoveTheBindingBeforeALegacyAcknowledgement() {
        val replaced = awaiting(original).completeFullRecheck(granted(), required, true)
        val acknowledged = replaced.acknowledgeExplicitResume(granted(), required)

        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, acknowledged.state)
        assertEquals(original, acknowledged.recheckBinding)
        assertTrue(acknowledged.blocksAutomaticResourceStart)
    }

    @Test
    fun matchingScopeStillReobservesPermissionsAtTheExplicitTap() {
        val result = awaiting(original).acknowledgeExplicitResume(
            observed = granted().copy(cameraGranted = false),
            requiredPermissions = required,
            currentBinding = original,
        )

        assertEquals(PermissionRecoveryGateState.BLOCKED, result.state)
        assertEquals(setOf(ObservedPermission.CAMERA), result.affectedPermissions)
        assertEquals(original, result.recheckBinding)
    }

    @Test
    fun aClearGateIsNotForcedToRecheckBecauseTheWalkChanged() {
        val clear = PermissionRecoveryGate()
        val newer = original.copy(walkEpoch = WalkRuntimeEpoch("walk-b", 0L))

        assertEquals(clear, clear.acknowledgeExplicitResume(granted(), required, newer))
        assertFalse(clear.blocksAutomaticResourceStart)
    }

    @Test
    fun aResultWithoutACurrentMatchingRequestCannotCreateBoundApproval() {
        val restored = PermissionRecoveryGate.restoredBlocked(required)
        val unsolicited = restored.completeFullRecheck(granted(), required, true, original)

        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, unsolicited.state)
        assertEquals(null, unsolicited.recheckBinding)
        assertTrue(unsolicited.blocksAutomaticResourceStart)
        val current = unsolicited.recheckRequired(original)
            .completeFullRecheck(granted(), required, true, original)
        assertEquals(
            PermissionRecoveryGateState.CLEAR,
            current.acknowledgeExplicitResume(granted(), required, original).state,
        )
    }

    private fun assertScopeRejected(current: PermissionRecoveryBinding) {
        val result = awaiting(original).acknowledgeExplicitResume(granted(), required, current)
        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, result.state)
        assertEquals(original, result.recheckBinding)
        assertTrue(result.blocksAutomaticResourceStart)
    }

    private fun awaiting(binding: PermissionRecoveryBinding): PermissionRecoveryGate =
        PermissionRecoveryGate()
            .blocked(required, binding)
            .settingsPending()
            .recheckRequired()
            .completeFullRecheck(granted(), required, true, binding)

    private fun granted() = ObservedPermissionSnapshot(
        cameraGranted = true,
        preciseLocationGranted = true,
        microphoneGranted = true,
        activityRecognitionGranted = true,
    )

    private fun binding() = PermissionRecoveryBinding(
        actorBindingSha256 = "a".repeat(64),
        gatewaySessionGeneration = 7L,
        walkEpoch = WalkRuntimeEpoch("walk-a", 2L),
        recheckRequestId = "recheck-1",
    )
}
