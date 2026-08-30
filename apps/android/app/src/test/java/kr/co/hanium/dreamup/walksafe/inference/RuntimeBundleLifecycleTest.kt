package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeBundleLifecycleTest {
    @Test
    fun productionApprovalProfileIsNullAndChangesDefaultOff() {
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CANDIDATE,
            initialPreviousKnownGood = CURRENT,
        )

        assertNull(RuntimeBundleLifecycle.productionApprovalProfile)
        assertEquals(
            RuntimeBundleChangeStatus.NOT_CONFIGURED,
            lifecycle.activateForNextWalk(activationRequest(CURRENT)).status,
        )
        assertEquals(
            RuntimeBundleChangeStatus.NOT_CONFIGURED,
            lifecycle.rollbackForNextWalk(rollbackRequest(CURRENT)).status,
        )
        assertEquals(CANDIDATE, lifecycle.snapshot().current)
        assertEquals(CURRENT, lifecycle.snapshot().previousKnownGood)
    }

    @Test
    fun descriptorRequiresEveryPinnedBundleSurface() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(bundleVersion = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(modelArtifacts = emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(modelClasses = emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(guidanceAllowlist = emptyMap(), classThresholds = emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(classThresholds = emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(appArtifactSha256 = "not-a-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(configSha256 = "not-a-hash")
        }
    }

    @Test
    fun activationRequiresExactApprovedDescriptorAndBindings() {
        val mismatches = listOf(
            CANDIDATE.copy(bundleVersion = "candidate-v2"),
            CANDIDATE.copy(modelArtifacts = mapOf("model" to HASH_A)),
            CANDIDATE.copy(modelClasses = mapOf("model" to listOf("car", "person"))),
            CANDIDATE.copy(
                guidanceAllowlist = mapOf("model" to setOf("person", "car")),
            ),
            CANDIDATE.copy(
                classThresholds = mapOf(
                    "model" to mapOf("person" to 0.4f, "car" to 0.4f),
                ),
            ),
            CANDIDATE.copy(appArtifactSha256 = HASH_D),
            CANDIDATE.copy(configSha256 = HASH_D),
        )
        mismatches.forEach { mismatch ->
            val lifecycle = configuredLifecycle()
            assertEquals(
                mismatch.toString(),
                RuntimeBundleChangeStatus.APPROVAL_MISMATCH,
                lifecycle.activateForNextWalk(activationRequest(mismatch)).status,
            )
            assertEquals(CURRENT, lifecycle.snapshot().current)
        }

        val lifecycle = configuredLifecycle()
        assertEquals(
            RuntimeBundleChangeStatus.APPROVAL_MISMATCH,
            lifecycle.activateForNextWalk(
                activationRequest(CANDIDATE).copy(approvalId = "wrong-approval"),
            ).status,
        )
        assertEquals(
            RuntimeBundleChangeStatus.APPROVAL_MISMATCH,
            lifecycle.activateForNextWalk(
                activationRequest(CANDIDATE).copy(signatureFingerprint = "wrong-fingerprint"),
            ).status,
        )
    }

    @Test
    fun activationWaitsForNoActiveWalkCompatibilityAndSelfTest() {
        val lifecycle = configuredLifecycle()

        assertEquals(
            RuntimeBundleChangeStatus.ACTIVE_WALK_IN_PROGRESS,
            lifecycle.activateForNextWalk(
                activationRequest(CANDIDATE).copy(activeWalk = true),
            ).status,
        )
        assertEquals(
            RuntimeBundleChangeStatus.COMPATIBILITY_FAILED,
            lifecycle.activateForNextWalk(
                activationRequest(CANDIDATE).copy(
                    validation = validation(CANDIDATE, compatibilityPassed = false),
                ),
            ).status,
        )
        assertEquals(
            RuntimeBundleChangeStatus.SELF_TEST_FAILED,
            lifecycle.activateForNextWalk(
                activationRequest(CANDIDATE).copy(
                    validation = validation(CANDIDATE, selfTestPassed = false),
                ),
            ).status,
        )
        assertEquals(
            RuntimeBundleChangeStatus.VALIDATION_BINDING_MISMATCH,
            lifecycle.activateForNextWalk(
                activationRequest(CANDIDATE).copy(
                    validation = validation(CURRENT),
                ),
            ).status,
        )
        assertEquals(CURRENT, lifecycle.snapshot().current)
    }

    @Test
    fun successfulActivationRetainsCurrentAsPreviousKnownGood() {
        val lifecycle = configuredLifecycle()

        val result = lifecycle.activateForNextWalk(activationRequest(CANDIDATE))

        assertEquals(RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK, result.status)
        assertEquals(CANDIDATE, result.snapshot.current)
        assertEquals(CURRENT, result.snapshot.previousKnownGood)
        assertEquals(result.snapshot, lifecycle.snapshot())
    }

    @Test
    fun rollbackRequiresPreviousApprovedTargetAndEveryDataGate() {
        val approvalProfile = approvalProfile(CURRENT, CANDIDATE)
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CANDIDATE,
            initialPreviousKnownGood = CURRENT,
            approvalProfile = approvalProfile,
        )

        val failures = listOf(
            rollbackRequest(CURRENT).copy(activeWalk = true) to RuntimeBundleChangeStatus.ACTIVE_WALK_IN_PROGRESS,
            rollbackRequest(CURRENT).copy(databaseBackwardCompatible = false) to
                RuntimeBundleChangeStatus.DATABASE_NOT_BACKWARD_COMPATIBLE,
            rollbackRequest(CURRENT).copy(dataBackwardCompatible = false) to
                RuntimeBundleChangeStatus.DATA_NOT_BACKWARD_COMPATIBLE,
            rollbackRequest(CURRENT).copy(noDataLoss = false) to RuntimeBundleChangeStatus.DATA_LOSS_RISK,
            rollbackRequest(CURRENT).copy(explicitApprovalId = null) to
                RuntimeBundleChangeStatus.EXPLICIT_APPROVAL_MISSING,
            rollbackRequest(CURRENT).copy(explicitApprovalId = "different") to
                RuntimeBundleChangeStatus.EXPLICIT_APPROVAL_MISSING,
            rollbackRequest(CURRENT).copy(auditBinding = null) to RuntimeBundleChangeStatus.AUDIT_BINDING_MISSING,
            rollbackRequest(CURRENT).copy(
                auditBinding = rollbackRequest(CURRENT).auditBinding?.copy(
                    approvalId = CANDIDATE.approvalId(),
                ),
            ) to RuntimeBundleChangeStatus.AUDIT_BINDING_MISSING,
            rollbackRequest(CURRENT).copy(
                auditBinding = rollbackRequest(CURRENT).auditBinding?.copy(
                    targetBundleVersion = CANDIDATE.bundleVersion,
                ),
            ) to RuntimeBundleChangeStatus.AUDIT_BINDING_MISSING,
        )
        failures.forEach { (request, expected) ->
            assertEquals(expected, lifecycle.rollbackForNextWalk(request).status)
            assertEquals(CANDIDATE, lifecycle.snapshot().current)
            assertEquals(CURRENT, lifecycle.snapshot().previousKnownGood)
        }
        assertEquals(
            RuntimeBundleChangeStatus.TARGET_NOT_PREVIOUS_KNOWN_GOOD,
            lifecycle.rollbackForNextWalk(rollbackRequest(CANDIDATE)).status,
        )
    }

    @Test
    fun rollbackAlsoRequiresExactApprovalCompatibilityAndSelfTest() {
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CANDIDATE,
            initialPreviousKnownGood = CURRENT,
            approvalProfile = approvalProfile(CURRENT, CANDIDATE),
        )

        assertEquals(
            RuntimeBundleChangeStatus.APPROVAL_MISMATCH,
            lifecycle.rollbackForNextWalk(
                rollbackRequest(CURRENT).copy(signatureFingerprint = "wrong"),
            ).status,
        )
        assertEquals(
            RuntimeBundleChangeStatus.COMPATIBILITY_FAILED,
            lifecycle.rollbackForNextWalk(
                rollbackRequest(CURRENT).copy(
                    validation = validation(CURRENT, compatibilityPassed = false),
                ),
            ).status,
        )
        assertEquals(
            RuntimeBundleChangeStatus.SELF_TEST_FAILED,
            lifecycle.rollbackForNextWalk(
                rollbackRequest(CURRENT).copy(
                    validation = validation(CURRENT, selfTestPassed = false),
                ),
            ).status,
        )
    }

    @Test
    fun successfulRollbackSwapsKnownGoodBundlesForTheNextWalk() {
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CANDIDATE,
            initialPreviousKnownGood = CURRENT,
            approvalProfile = approvalProfile(CURRENT, CANDIDATE),
        )

        val result = lifecycle.rollbackForNextWalk(rollbackRequest(CURRENT))

        assertEquals(RuntimeBundleChangeStatus.ROLLED_BACK_FOR_NEXT_WALK, result.status)
        assertEquals(CURRENT, result.snapshot.current)
        assertEquals(CANDIDATE, result.snapshot.previousKnownGood)
    }

    private fun configuredLifecycle() = RuntimeBundleLifecycle(
        initialCurrent = CURRENT,
        approvalProfile = approvalProfile(CANDIDATE),
    )

    private fun activationRequest(target: RuntimeBundleDescriptor) = RuntimeBundleActivationRequest(
        candidate = target,
        approvalId = target.approvalId(),
        signatureFingerprint = target.signatureFingerprint(),
        validation = validation(target),
        activeWalk = false,
    )

    private fun rollbackRequest(target: RuntimeBundleDescriptor) = RuntimeBundleRollbackRequest(
        target = target,
        approvalId = target.approvalId(),
        signatureFingerprint = target.signatureFingerprint(),
        validation = validation(target),
        activeWalk = false,
        databaseBackwardCompatible = true,
        dataBackwardCompatible = true,
        noDataLoss = true,
        explicitApprovalId = target.approvalId(),
        auditBinding = RuntimeBundleRollbackAuditBinding(
            auditId = "audit-${target.bundleVersion}",
            approvalId = target.approvalId(),
            targetBundleVersion = target.bundleVersion,
        ),
    )

    private fun validation(
        target: RuntimeBundleDescriptor,
        compatibilityPassed: Boolean = true,
        selfTestPassed: Boolean = true,
    ) = RuntimeBundleValidationEvidence(
        bundleVersion = target.bundleVersion,
        compatibilityPassed = compatibilityPassed,
        selfTestPassed = selfTestPassed,
    )

    private fun approvalProfile(vararg descriptors: RuntimeBundleDescriptor) =
        RuntimeBundleApprovalProfile(
            bindings = descriptors.map { descriptor ->
                RuntimeBundleApprovalBinding(
                    approvalId = descriptor.approvalId(),
                    signatureFingerprint = descriptor.signatureFingerprint(),
                    descriptor = descriptor,
                )
            }.toSet(),
        )

    private fun RuntimeBundleDescriptor.approvalId() = "approval-$bundleVersion"

    private fun RuntimeBundleDescriptor.signatureFingerprint() = "fingerprint-$bundleVersion"

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        val CURRENT = descriptor(bundleVersion = "current-v1")
        val CANDIDATE = descriptor(
            bundleVersion = "candidate-v1",
            modelArtifacts = mapOf("model" to HASH_D),
        )

        fun descriptor(
            bundleVersion: String = "bundle-v1",
            modelArtifacts: Map<String, String> = mapOf("model" to HASH_A),
            modelClasses: Map<String, List<String>> =
                mapOf("model" to listOf("person", "car")),
            guidanceAllowlist: Map<String, Set<String>> =
                mapOf("model" to setOf("person")),
            classThresholds: Map<String, Map<String, Float>> =
                mapOf("model" to mapOf("person" to 0.3f, "car" to 0.4f)),
            appArtifactSha256: String = HASH_B,
            configSha256: String = HASH_C,
        ) = RuntimeBundleDescriptor(
            bundleVersion = bundleVersion,
            modelArtifacts = modelArtifacts,
            modelClasses = modelClasses,
            guidanceAllowlist = guidanceAllowlist,
            classThresholds = classThresholds,
            appArtifactSha256 = appArtifactSha256,
            configSha256 = configSha256,
        )
    }
}
