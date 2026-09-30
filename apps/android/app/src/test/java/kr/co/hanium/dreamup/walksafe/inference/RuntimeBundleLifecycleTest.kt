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

    @Test
    fun activationRejectsSameVersionEvidenceForEveryDifferentDescriptorSurface() {
        sameVersionContentMismatches().forEach { (surface, target) ->
            assertEquals(CANDIDATE.bundleVersion, target.bundleVersion)
            assertActivationEvidenceBinding(surface, target, validation(CANDIDATE), matches = false)
        }
    }

    @Test
    fun rollbackRejectsSameVersionEvidenceForEveryDifferentDescriptorSurface() {
        sameVersionContentMismatches().forEach { (surface, target) ->
            assertEquals(CANDIDATE.bundleVersion, target.bundleVersion)
            assertRollbackEvidenceBinding(surface, target, validation(CANDIDATE), matches = false)
        }
    }

    @Test
    fun activationEvidenceKeepsAllOuterMapsDetached() {
        outerDescriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val evidence = validation(source.descriptor)
            mutate(source)

            assertActivationEvidenceBinding(surface, source.descriptor, evidence, matches = false)
            assertActivationEvidenceBinding("$surface original", CANDIDATE, evidence, matches = true)
            assertEquals(CANDIDATE.bundleVersion, evidence.bundleVersion)
        }
    }

    @Test
    fun rollbackEvidenceKeepsAllOuterMapsDetached() {
        outerDescriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val evidence = validation(source.descriptor)
            mutate(source)

            assertRollbackEvidenceBinding(surface, source.descriptor, evidence, matches = false)
            assertRollbackEvidenceBinding("$surface original", CANDIDATE, evidence, matches = true)
            assertEquals(CANDIDATE.bundleVersion, evidence.bundleVersion)
        }
    }

    @Test
    fun activationEvidenceKeepsNestedCollectionsDetached() {
        nestedDescriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val evidence = validation(source.descriptor)
            mutate(source)

            assertActivationEvidenceBinding(surface, source.descriptor, evidence, matches = false)
            assertActivationEvidenceBinding("$surface original", CANDIDATE, evidence, matches = true)
        }
    }

    @Test
    fun rollbackEvidenceKeepsNestedCollectionsDetached() {
        nestedDescriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val evidence = validation(source.descriptor)
            mutate(source)

            assertRollbackEvidenceBinding(surface, source.descriptor, evidence, matches = false)
            assertRollbackEvidenceBinding("$surface original", CANDIDATE, evidence, matches = true)
        }
    }

    @Test
    fun activationAcceptsEquivalentMapAndSetOrderWithSeparateEvidence() {
        val validated = orderedDescriptor(reverseEntries = false)
        val target = orderedDescriptor(reverseEntries = true)

        assertEquals(validated, target)
        assertActivationEvidenceBinding("collection order", target, validation(validated), matches = true)
    }

    @Test
    fun rollbackAcceptsEquivalentMapAndSetOrderWithSeparateEvidence() {
        val validated = orderedDescriptor(reverseEntries = false)
        val target = orderedDescriptor(reverseEntries = true)

        assertEquals(validated, target)
        assertRollbackEvidenceBinding("collection order", target, validation(validated), matches = true)
    }

    @Test
    fun constructorCurrentIsDetachedFromAllCallerCollections() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val lifecycle = RuntimeBundleLifecycle(source.descriptor, CURRENT)
            val expected = RuntimeBundleLifecycleSnapshot(CANDIDATE, CURRENT)
            val earlier = lifecycle.snapshot()

            mutate(source)

            assertEquals(surface, expected, earlier)
            assertEquals(surface, expected, lifecycle.snapshot())
        }
    }

    @Test
    fun constructorPreviousKnownGoodIsDetachedFromAllCallerCollections() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val lifecycle = RuntimeBundleLifecycle(CURRENT, source.descriptor)
            val expected = RuntimeBundleLifecycleSnapshot(CURRENT, CANDIDATE)
            val earlier = lifecycle.snapshot()

            mutate(source)

            assertEquals(surface, expected, earlier)
            assertEquals(surface, expected, lifecycle.snapshot())
        }
    }

    @Test
    fun activationDetachesCommittedCandidateFromAllCallerCollections() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val lifecycle = RuntimeBundleLifecycle(
                initialCurrent = CURRENT,
                approvalProfile = approvalProfile(CANDIDATE),
            )

            val result = lifecycle.activateForNextWalk(activationRequest(source.descriptor))
            assertEquals(surface, RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK, result.status)
            mutate(source)

            val expected = RuntimeBundleLifecycleSnapshot(CANDIDATE, CURRENT)
            assertEquals(surface, expected, result.snapshot)
            assertEquals(surface, expected, lifecycle.snapshot())
        }
    }

    @Test
    fun rollbackDetachesCommittedTargetFromAllCallerCollections() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val lifecycle = RuntimeBundleLifecycle(
                initialCurrent = CURRENT,
                initialPreviousKnownGood = CANDIDATE,
                approvalProfile = approvalProfile(CANDIDATE),
            )

            val result = lifecycle.rollbackForNextWalk(rollbackRequest(source.descriptor))
            assertEquals(surface, RuntimeBundleChangeStatus.ROLLED_BACK_FOR_NEXT_WALK, result.status)
            mutate(source)

            val expected = RuntimeBundleLifecycleSnapshot(CANDIDATE, CURRENT)
            assertEquals(surface, expected, result.snapshot)
            assertEquals(surface, expected, lifecycle.snapshot())
        }
    }

    @Test
    fun activationKeepsPreviousKnownGoodDetachedFromOriginalCurrent() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val next = descriptor(bundleVersion = "next-v1")
            val lifecycle = RuntimeBundleLifecycle(
                initialCurrent = source.descriptor,
                approvalProfile = approvalProfile(next),
            )

            val result = lifecycle.activateForNextWalk(activationRequest(next))
            assertEquals(surface, RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK, result.status)
            mutate(source)

            val expected = RuntimeBundleLifecycleSnapshot(next, CANDIDATE)
            assertEquals(surface, expected, result.snapshot)
            assertEquals(surface, expected, lifecycle.snapshot())
        }
    }

    @Test
    fun rollbackKeepsPreviousKnownGoodDetachedFromOriginalCurrent() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val lifecycle = RuntimeBundleLifecycle(
                initialCurrent = source.descriptor,
                initialPreviousKnownGood = CURRENT,
                approvalProfile = approvalProfile(CURRENT),
            )

            val result = lifecycle.rollbackForNextWalk(rollbackRequest(CURRENT))
            assertEquals(surface, RuntimeBundleChangeStatus.ROLLED_BACK_FOR_NEXT_WALK, result.status)
            mutate(source)

            val expected = RuntimeBundleLifecycleSnapshot(CURRENT, CANDIDATE)
            assertEquals(surface, expected, result.snapshot)
            assertEquals(surface, expected, lifecycle.snapshot())
        }
    }

    @Test
    fun approvalBindingsDoNotExpandWhenCallerAddsBinding() {
        val added = descriptor(bundleVersion = "added-v1")
        val bindings = linkedSetOf(approvalProfile(CANDIDATE).bindings.single())
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CURRENT,
            approvalProfile = RuntimeBundleApprovalProfile(bindings),
        )
        val before = lifecycle.snapshot()

        bindings += approvalProfile(added).bindings.single()
        val rejected = lifecycle.activateForNextWalk(activationRequest(added))

        assertEquals(RuntimeBundleChangeStatus.APPROVAL_MISMATCH, rejected.status)
        assertEquals(before, rejected.snapshot)
        assertEquals(before, lifecycle.snapshot())
        val accepted = lifecycle.activateForNextWalk(activationRequest(CANDIDATE))
        assertEquals(RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK, accepted.status)
        assertEquals(RuntimeBundleLifecycleSnapshot(CANDIDATE, CURRENT), accepted.snapshot)
        assertEquals(accepted.snapshot, lifecycle.snapshot())
    }

    @Test
    fun approvalBindingsDoNotShrinkWhenCallerRemovesBinding() {
        val original = approvalProfile(CANDIDATE).bindings.single()
        val other = approvalProfile(descriptor(bundleVersion = "other-v1")).bindings.single()
        val bindings = linkedSetOf(original, other)
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CURRENT,
            approvalProfile = RuntimeBundleApprovalProfile(bindings),
        )

        bindings.remove(original)
        val result = lifecycle.activateForNextWalk(activationRequest(CANDIDATE))

        assertEquals(RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK, result.status)
        assertEquals(RuntimeBundleLifecycleSnapshot(CANDIDATE, CURRENT), result.snapshot)
        assertEquals(result.snapshot, lifecycle.snapshot())
    }

    @Test
    fun activationApprovalDescriptorIsDetachedFromAllCallerCollections() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val lifecycle = RuntimeBundleLifecycle(
                initialCurrent = CURRENT,
                approvalProfile = approvalProfile(source.descriptor),
            )
            val before = lifecycle.snapshot()

            mutate(source)
            val rejected = lifecycle.activateForNextWalk(activationRequest(source.descriptor))

            assertEquals(surface, RuntimeBundleChangeStatus.APPROVAL_MISMATCH, rejected.status)
            assertEquals(surface, before, rejected.snapshot)
            assertEquals(surface, before, lifecycle.snapshot())
            val accepted = lifecycle.activateForNextWalk(activationRequest(CANDIDATE))
            assertEquals(surface, RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK, accepted.status)
            assertEquals(surface, RuntimeBundleLifecycleSnapshot(CANDIDATE, CURRENT), accepted.snapshot)
            assertEquals(surface, accepted.snapshot, lifecycle.snapshot())
        }
    }

    @Test
    fun rollbackApprovalDescriptorIsDetachedFromAllCallerCollections() {
        descriptorMutations().forEach { (surface, mutate) ->
            val source = MutableDescriptorFixture()
            val lifecycle = RuntimeBundleLifecycle(
                initialCurrent = CURRENT,
                initialPreviousKnownGood = CANDIDATE,
                approvalProfile = approvalProfile(source.descriptor),
            )
            val before = lifecycle.snapshot()

            mutate(source)
            val rejected = lifecycle.rollbackForNextWalk(rollbackRequest(source.descriptor))

            assertEquals(surface, RuntimeBundleChangeStatus.APPROVAL_MISMATCH, rejected.status)
            assertEquals(surface, before, rejected.snapshot)
            assertEquals(surface, before, lifecycle.snapshot())
            val accepted = lifecycle.rollbackForNextWalk(rollbackRequest(CANDIDATE))
            assertEquals(surface, RuntimeBundleChangeStatus.ROLLED_BACK_FOR_NEXT_WALK, accepted.status)
            assertEquals(surface, RuntimeBundleLifecycleSnapshot(CANDIDATE, CURRENT), accepted.snapshot)
            assertEquals(surface, accepted.snapshot, lifecycle.snapshot())
        }
    }

    @Test
    fun snapshotsDetachCurrentAndPreviousKnownGoodCollections() {
        assertExportedSnapshotsAreDetached(changesBundle = false) { lifecycle, _ ->
            lifecycle.snapshot()
        }
    }

    @Test
    fun activationResultsDetachCurrentAndPreviousKnownGoodCollections() {
        assertExportedSnapshotsAreDetached(changesBundle = true) { lifecycle, target ->
            val result = lifecycle.activateForNextWalk(activationRequest(target))
            assertEquals(RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK, result.status)
            result.snapshot
        }
    }

    @Test
    fun rollbackResultsDetachCurrentAndPreviousKnownGoodCollections() {
        assertExportedSnapshotsAreDetached(changesBundle = true) { lifecycle, target ->
            val result = lifecycle.rollbackForNextWalk(rollbackRequest(target))
            assertEquals(RuntimeBundleChangeStatus.ROLLED_BACK_FOR_NEXT_WALK, result.status)
            result.snapshot
        }
    }

    @Test
    fun failedActivationResultsDetachCurrentAndPreviousKnownGoodCollections() {
        assertExportedSnapshotsAreDetached(changesBundle = false) { lifecycle, target ->
            val result = lifecycle.activateForNextWalk(
                activationRequest(target).copy(activeWalk = true),
            )
            assertEquals(RuntimeBundleChangeStatus.ACTIVE_WALK_IN_PROGRESS, result.status)
            result.snapshot
        }
    }

    @Test
    fun failedRollbackResultsDetachCurrentAndPreviousKnownGoodCollections() {
        assertExportedSnapshotsAreDetached(changesBundle = false) { lifecycle, target ->
            val result = lifecycle.rollbackForNextWalk(
                rollbackRequest(target).copy(activeWalk = true),
            )
            assertEquals(RuntimeBundleChangeStatus.ACTIVE_WALK_IN_PROGRESS, result.status)
            result.snapshot
        }
    }

    @Test
    fun unconfiguredResultsDetachCurrentAndPreviousKnownGoodCollections() {
        assertExportedSnapshotsAreDetached(changesBundle = false, configured = false) { lifecycle, target ->
            val result = lifecycle.activateForNextWalk(activationRequest(target))
            assertEquals(RuntimeBundleChangeStatus.NOT_CONFIGURED, result.status)
            result.snapshot
        }
        assertExportedSnapshotsAreDetached(changesBundle = false, configured = false) { lifecycle, target ->
            val result = lifecycle.rollbackForNextWalk(rollbackRequest(target))
            assertEquals(RuntimeBundleChangeStatus.NOT_CONFIGURED, result.status)
            result.snapshot
        }
    }

    private fun descriptorMutations() = outerDescriptorMutations() + nestedDescriptorMutations()

    private fun assertExportedSnapshotsAreDetached(
        changesBundle: Boolean,
        configured: Boolean = true,
        operation: (RuntimeBundleLifecycle, RuntimeBundleDescriptor) -> RuntimeBundleLifecycleSnapshot,
    ) {
        exportedDescriptorMutations().forEach { (surface, mutate) ->
            val current = ownershipDescriptor("owned-current-v1")
            val previous = ownershipDescriptor("owned-previous-v1")
            val lifecycle = RuntimeBundleLifecycle(
                initialCurrent = current,
                initialPreviousKnownGood = previous,
                approvalProfile = if (configured) approvalProfile(current, previous) else null,
            )
            val expected = if (changesBundle) {
                RuntimeBundleLifecycleSnapshot(
                    ownershipDescriptor("owned-previous-v1"),
                    ownershipDescriptor("owned-current-v1"),
                )
            } else {
                RuntimeBundleLifecycleSnapshot(
                    ownershipDescriptor("owned-current-v1"),
                    ownershipDescriptor("owned-previous-v1"),
                )
            }

            val exported = operation(lifecycle, previous)
            assertEquals(surface, expected, exported)
            val independent = lifecycle.snapshot()
            mutate(exported.current)
            mutate(checkNotNull(exported.previousKnownGood))

            assertEquals(surface, expected, independent)
            assertEquals(surface, expected, lifecycle.snapshot())
        }
    }

    private fun ownershipDescriptor(version: String) =
        orderedDescriptor(reverseEntries = false).copy(bundleVersion = version)

    private fun exportedDescriptorMutations(): List<Pair<String, (RuntimeBundleDescriptor) -> Unit>> =
        listOf(
            "modelArtifacts outer" to { value: RuntimeBundleDescriptor ->
                val artifacts = value.modelArtifacts as MutableMap<String, String>
                artifacts["first"] = HASH_D
            },
            "modelClasses outer" to { value: RuntimeBundleDescriptor ->
                val classes = value.modelClasses as MutableMap<String, List<String>>
                classes["first"] = listOf("car", "person")
            },
            "guidanceAllowlist outer" to { value: RuntimeBundleDescriptor ->
                val allowlist = value.guidanceAllowlist as MutableMap<String, Set<String>>
                allowlist["first"] = setOf("car")
            },
            "classThresholds outer" to { value: RuntimeBundleDescriptor ->
                val thresholds = value.classThresholds as MutableMap<String, Map<String, Float>>
                thresholds["first"] = mapOf("person" to 0.7f, "car" to 0.4f)
            },
            "modelClasses nested list" to { value: RuntimeBundleDescriptor ->
                val classes = value.modelClasses.getValue("first") as MutableList<String>
                classes.reverse()
            },
            "guidanceAllowlist nested set" to { value: RuntimeBundleDescriptor ->
                val classes = value.guidanceAllowlist.getValue("first") as MutableSet<String>
                classes.clear()
            },
            "classThresholds nested map" to { value: RuntimeBundleDescriptor ->
                val thresholds = value.classThresholds.getValue("first") as MutableMap<String, Float>
                thresholds["person"] = 0.7f
            },
        )

    private fun sameVersionContentMismatches() = listOf(
        "modelArtifacts" to CANDIDATE.copy(modelArtifacts = mapOf("model" to HASH_A)),
        "modelClasses list order" to CANDIDATE.copy(
            modelClasses = mapOf("model" to listOf("car", "person")),
        ),
        "guidanceAllowlist" to CANDIDATE.copy(
            guidanceAllowlist = mapOf("model" to setOf("person", "car")),
        ),
        "classThresholds" to CANDIDATE.copy(
            classThresholds = mapOf("model" to mapOf("person" to 0.7f, "car" to 0.4f)),
        ),
        "appArtifactSha256" to CANDIDATE.copy(appArtifactSha256 = HASH_D),
        "configSha256" to CANDIDATE.copy(configSha256 = HASH_D),
    )

    private fun assertActivationEvidenceBinding(
        label: String,
        target: RuntimeBundleDescriptor,
        evidence: RuntimeBundleValidationEvidence,
        matches: Boolean,
    ) {
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CURRENT,
            initialPreviousKnownGood = descriptor(bundleVersion = "older-v1"),
            approvalProfile = approvalProfile(target),
        )
        val before = lifecycle.snapshot()

        val result = lifecycle.activateForNextWalk(
            activationRequest(target).copy(validation = evidence),
        )

        assertEquals(
            label,
            if (matches) RuntimeBundleChangeStatus.ACTIVATED_FOR_NEXT_WALK
            else RuntimeBundleChangeStatus.VALIDATION_BINDING_MISMATCH,
            result.status,
        )
        val expected = if (matches) RuntimeBundleLifecycleSnapshot(target, CURRENT) else before
        assertEquals(label, expected, result.snapshot)
        assertEquals(label, expected, lifecycle.snapshot())
    }

    private fun assertRollbackEvidenceBinding(
        label: String,
        target: RuntimeBundleDescriptor,
        evidence: RuntimeBundleValidationEvidence,
        matches: Boolean,
    ) {
        val lifecycle = RuntimeBundleLifecycle(
            initialCurrent = CURRENT,
            initialPreviousKnownGood = target,
            approvalProfile = approvalProfile(target),
        )
        val before = lifecycle.snapshot()

        val result = lifecycle.rollbackForNextWalk(
            rollbackRequest(target).copy(validation = evidence),
        )

        assertEquals(
            label,
            if (matches) RuntimeBundleChangeStatus.ROLLED_BACK_FOR_NEXT_WALK
            else RuntimeBundleChangeStatus.VALIDATION_BINDING_MISMATCH,
            result.status,
        )
        val expected = if (matches) RuntimeBundleLifecycleSnapshot(target, CURRENT) else before
        assertEquals(label, expected, result.snapshot)
        assertEquals(label, expected, lifecycle.snapshot())
    }

    private fun outerDescriptorMutations(): List<Pair<String, (MutableDescriptorFixture) -> Unit>> =
        listOf(
            "modelArtifacts outer" to { source: MutableDescriptorFixture ->
                source.modelArtifacts["model"] = HASH_A
            },
            "modelClasses outer" to { source: MutableDescriptorFixture ->
                source.modelClasses["model"] = listOf("car", "person")
            },
            "guidanceAllowlist outer" to { source: MutableDescriptorFixture ->
                source.guidanceAllowlist["model"] = setOf("person", "car")
            },
            "classThresholds outer" to { source: MutableDescriptorFixture ->
                source.classThresholds["model"] = mapOf("person" to 0.7f, "car" to 0.4f)
            },
        )

    private fun nestedDescriptorMutations(): List<Pair<String, (MutableDescriptorFixture) -> Unit>> =
        listOf(
            "modelClasses nested list" to { source: MutableDescriptorFixture ->
                source.classNames.reverse()
            },
            "guidanceAllowlist nested set" to { source: MutableDescriptorFixture ->
                source.guidanceClasses += "car"
            },
            "classThresholds nested map" to { source: MutableDescriptorFixture ->
                source.thresholds["person"] = 0.7f
            },
        )

    private fun orderedDescriptor(reverseEntries: Boolean): RuntimeBundleDescriptor {
        val modelKeys = if (reverseEntries) listOf("second", "first") else listOf("first", "second")
        return descriptor(
            bundleVersion = CANDIDATE.bundleVersion,
            modelArtifacts = modelKeys.associateWith { if (it == "first") HASH_A else HASH_D },
            modelClasses = modelKeys.associateWith { listOf("person", "car") },
            guidanceAllowlist = modelKeys.associateWith {
                if (reverseEntries) linkedSetOf("car", "person") else linkedSetOf("person", "car")
            },
            classThresholds = modelKeys.associateWith {
                if (reverseEntries) linkedMapOf("car" to 0.4f, "person" to 0.3f)
                else linkedMapOf("person" to 0.3f, "car" to 0.4f)
            },
        )
    }

    private class MutableDescriptorFixture {
        val modelArtifacts = linkedMapOf("model" to HASH_D)
        val classNames = mutableListOf("person", "car")
        val modelClasses = linkedMapOf<String, List<String>>("model" to classNames)
        val guidanceClasses = linkedSetOf("person")
        val guidanceAllowlist = linkedMapOf<String, Set<String>>("model" to guidanceClasses)
        val thresholds = linkedMapOf("person" to 0.3f, "car" to 0.4f)
        val classThresholds = linkedMapOf<String, Map<String, Float>>("model" to thresholds)
        val descriptor = RuntimeBundleDescriptor(
            bundleVersion = "candidate-v1",
            modelArtifacts = modelArtifacts,
            modelClasses = modelClasses,
            guidanceAllowlist = guidanceAllowlist,
            classThresholds = classThresholds,
            appArtifactSha256 = HASH_B,
            configSha256 = HASH_C,
        )
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
        descriptor = target,
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
