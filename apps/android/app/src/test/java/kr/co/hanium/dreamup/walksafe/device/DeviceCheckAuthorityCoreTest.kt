package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeTestFixtures
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DeviceCheckAuthorityCoreTest {
    @Test fun reconciledPendingBeginCannotHideAnUnreleasedForeignNativeCohort() {
        val foreign = Fixture().also { it.pair() }
        val f = Fixture()
        val command = java.util.UUID.randomUUID().toString()
        f.io.publication = DeviceCheckPublication.INDETERMINATE
        val pending = f.core.beginAttempt(f.binding, command, 1000L, 301000L)
        assertTrue(pending is DeviceCheckAuthorityOutcome.Indeterminate)
        f.io.text = requireNotNull(foreign.io.text)
        f.io.publication = DeviceCheckPublication.COMMITTED
        assertEquals(DeviceCheckAuthorityRead.Blocked("native_recovery_required"), f.core.reconcile(command))
        assertEquals(DeviceCheckAuthorityRead.Blocked("native_recovery_required"), f.core.cachedSnapshot(f.binding))
    }

    @Test fun beginMustPublishBeforeItCanIssueAnyNativeOwner() {
        val f = Fixture(); f.io.publication = DeviceCheckPublication.NOT_PUBLISHED
        assertTrue(f.core.beginAttempt(f.binding, id(), 1000, 301000) is DeviceCheckAuthorityOutcome.NotPublished)
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
        assertNull(f.core.ownerRef("main-test"))
        assertEquals(1, f.io.writes)
    }
    @Test fun exactLeaseIdentityOneUseAndAttemptCancellationGuardEveryOpen() {
        val f = Fixture(); val attempt = f.begin(); val owner = f.owner(attempt)
        val lease = DeviceCheckNativeLease(id(), owner.ownerId, 1); val actual = Any()
        val permit = ok(f.core.prepareNativeOpen(owner, lease, actual, id(), f.primarySpec, f.child, 20000))
        assertFalse(f.core.consumeNativeOpen(permit, Any(), 1001))
        assertTrue(f.core.consumeNativeOpen(permit, actual, 1001))
        assertFalse(f.core.consumeNativeOpen(permit, actual, 1001))
        f.core.reserveCancellation(attempt)
        assertTrue(f.core.prepareNativeOpen(owner, lease, actual, id(), f.auxSpec, f.child, 20000) is DeviceCheckAuthorityOutcome.Rejected)
    }
    @Test fun oldFactoryCannotBorrowANewAttemptWithTheSameActivityOwnerId() {
        val f = Fixture(); val old = f.begin(); val oldOwner = f.owner(old)
        assertTrue(f.core.reserveCancellation(old)); ok(f.core.cancelAttempt(old, id(), "user_cancelled"))
        val fresh = f.begin(); val freshOwner = f.owner(fresh)
        assertNotSame(oldOwner, freshOwner)
        val lease = DeviceCheckNativeLease(id(), oldOwner.ownerId, 1)
        assertTrue(f.core.prepareNativeOpen(oldOwner, lease, Any(), id(), f.primarySpec, f.child, 20000) is DeviceCheckAuthorityOutcome.Rejected)
        assertTrue(f.core.prepareNativeOpen(freshOwner, lease, Any(), id(), f.primarySpec, f.child, 20000) is DeviceCheckAuthorityOutcome.Committed)
    }
    @Test fun primaryCloseOrCommitAckCannotRetireTheStillLiveAuxiliaryCohort() {
        val f = Fixture(); val pair = f.pair(); val frozen = requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        ok(f.core.commitSuccess(id(), frozen))
        ok(f.core.recordModelCloseAck(pair.primary, f.child.pid, false))
        val stored = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        val cohort = stored.cohorts.single()
        assertNull(cohort.release)
        assertTrue(cohort.loads.single { it.spec.role == "PRIMARY" }.closed)
        assertFalse(cohort.loads.single { it.spec.role == "FASTSAM" }.closed)
        val restarted = f.restart(DeviceCheckProcessObservation.SAME_PROCESS)
        assertTrue(restarted.read(f.binding) is DeviceCheckAuthorityRead.Blocked)
    }
    @Test fun fullAndChoiceUseTheSameAttemptAndTwoOpaqueModelRefsInOnePublication() {
        val f = Fixture(); val pair = f.pair()
        val before = f.io.writes
        val frozen = requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        val bundle = ok(f.core.commitSuccess(id(), frozen))
        assertEquals(before + 1, f.io.writes)
        val s = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        assertEquals(bundle.attemptId, s.latest!!.id)
        assertEquals(bundle.attemptId, s.bundle!!.attemptId)
        assertEquals(f.choice.measurementId, s.bundle.choice.measurementId)
        assertEquals(setOf(pair.primary.loadId, pair.aux.loadId), s.bundle.pairLoadIds.toSet())
        assertTrue(s.cohorts.single().loads.all { it.verifiedAt == 1100L })
        assertSame(pair.primary, f.core.modelRef(pair.primary.lease.cohortId, 1, pair.primary.modelId))
        assertSame(pair.owner, f.core.ownerRef(pair.owner.ownerId)) // Atomic source promotion preserves the actual pair.
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.Ready)
    }
    @Test fun frozenDecisionCanSettleHistoricallyAfterScreenExitButClosedStateIsNotReopened() {
        val f = Fixture(); val pair = f.pair()
        val frozen = requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        f.core.retireOwner(pair.owner); pair.proof.current = false
        ok(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        ok(f.core.commitSuccess(id(), frozen))
        val cohort = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).cohorts.single()
        assertEquals("CHILD_DEATH", cohort.release)
        assertTrue(cohort.loads.all { it.closed && it.verifiedAt == 1100L })
        assertFalse(pair.proof.isCurrent())
    }
    @Test fun cancellationWinsBeforeProofConsumptionAndCannotBeOverwrittenByReadyCallbacks() {
        val f = Fixture(); val pair = f.pair()
        assertTrue(f.core.reserveCancellation(pair.attempt))
        assertNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        assertEquals(0, pair.proof.consumptions)
        ok(f.core.cancelAttempt(pair.attempt, id(), "cancelled"))
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
    }
    @Test fun wrongModelRefMeasurementOrDeadlineCannotBecomeAStoredPass() {
        for (kind in listOf("measurement", "foreignref", "deadline", "notcurrent")) {
            val f = Fixture(); val pair = f.pair()
            val proof = when (kind) {
                "measurement" -> Proof(id(), pair.primary, pair.aux)
                "foreignref" -> Proof(f.choice.measurementId, DeviceCheckNativeModelRef(pair.primary.loadId,
                    pair.primary.lease, pair.primary.spec, pair.primary.modelId, pair.primary.actualRuntimeHash, "forged"), pair.aux)
                else -> pair.proof
            }
            if (kind == "notcurrent") proof.current = false
            assertNull(f.core.freezeSuccess(pair.attempt, if (kind == "deadline") 301000 else 1100, f.full, f.choice, proof))
            assertNull(requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).bundle)
        }
    }
    @Test fun dirtyProcessUnknownBlocksButPositiveAbsenceAllowsAnExplicitNewCheckWithoutAutoPass() {
        val f = Fixture(); val pair = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        val unknown = f.restart(DeviceCheckProcessObservation.UNKNOWN)
        assertTrue(unknown.read(f.binding) is DeviceCheckAuthorityRead.Blocked)
        assertTrue(unknown.beginAttempt(f.binding, id(), 1000, 301000) is DeviceCheckAuthorityOutcome.Rejected)
        val absent = f.restart(DeviceCheckProcessObservation.ABSENT)
        assertTrue(absent.read(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
        assertTrue(absent.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
        val retried = ok(absent.beginAttempt(f.binding, id(), 1000, 301000))
        assertTrue(absent.checkAttempt(retried, 1001))
        assertTrue(absent.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
    }
    @Test fun interruptedNewCheckNeverPairsThePreviousFullWithANewChoice() {
        val f = Fixture(); val pair = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        ok(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        f.begin()
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).read(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
    }
    @Test fun unknownPublicationBlocksEveryPermitUntilFreshReadbackAndActualCloseRepair() {
        val f = Fixture(); val pair = f.pair()
        val operation = id()
        f.core.publicationUnconfirmed(operation)
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.Blocked)
        assertNull(f.core.registerOwner("other", pair.attempt, 1))
        assertTrue(f.core.noteCohortRelease(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        assertTrue(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid,
            DeviceCheckNativeReleaseProof.CHILD_DEATH) is DeviceCheckAuthorityOutcome.Indeterminate)
        f.core.reconcile(operation)
        val snapshot = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        assertEquals("CHILD_DEATH", snapshot.cohorts.single().release)
        assertNull(f.core.unsettledOperation())
    }
    @Test fun bundleResultRecoveryPreservesChoiceAndJournalAndUsesItsOwnRevisionCas() {
        val f = Fixture(); val pair = f.pair()
        val bundle = ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        val before = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        val limited = f.full.copy(state = PostLoginDeviceCheckState.LIMITED,
            disabledFeatures = setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE), metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)
        val changed = ok(f.core.updateResult(bundle, id(), f.full, limited))
        val after = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        assertEquals(before.cohorts, after.cohorts); assertEquals(before.bundle!!.choice, after.bundle!!.choice)
        assertTrue(f.core.updateResult(bundle, id(), f.full, limited) is DeviceCheckAuthorityOutcome.Rejected)
        assertTrue(f.core.updateResult(changed, id(), limited, limited.copy(disabledFeatures = setOf(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)))
            is DeviceCheckAuthorityOutcome.Rejected)
        ok(f.core.updateResult(changed, id(), limited, f.full))
    }
    @Test fun malformedOrOlderSchemaAndNewActorOrBuildNeverRestorePass() {
        val f = Fixture(); val pair = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        ok(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        val otherActor = f.binding.copy(actorBindingHash = "f".repeat(64))
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).also { it.admit(otherActor) }.read(otherActor) is DeviceCheckAuthorityRead.NeedsCheck)
        val otherBuild = f.binding.copy(runtimeBuildId = "f".repeat(64))
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).also { it.admit(otherBuild) }.read(otherBuild) is DeviceCheckAuthorityRead.NeedsCheck)
        f.io.text = requireNotNull(f.io.text).replaceFirst("\"schema\":1", "\"schema\":0")
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).read(f.binding) is DeviceCheckAuthorityRead.Blocked)
    }
    @Test fun anUnregisteredProofImplementationCannotConsumeEvenRealOpaqueModelRefs() {
        val f = Fixture(trustProof = false); val pair = f.pair()
        assertNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        assertEquals(0, pair.proof.consumptions)
    }
    @Test fun aNewAttemptCannotReuseTheOldMeasurementIdEvenWithIdenticalPayload() {
        val f = Fixture(); val first = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(first.attempt, 1100, f.full, f.choice, first.proof))))
        ok(f.core.recordCohortReleased(first.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        val second = f.pair()
        assertNull(f.core.freezeSuccess(second.attempt, 1100, f.full, f.choice, second.proof))
    }
    @Test fun transferKeepsPhysicalCohortDirtyAndReissuesOnlyTargetEpochModelRefs() {
        val f = Fixture(); val pair = f.pair()
        val bundle = ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        val targetOwner = requireNotNull(f.core.registerOwner("camera-test", bundle, 1))
        val target = pair.primary.lease.copy(ownerId = targetOwner.ownerId, ownerEpoch = 2)
        val refs = ok(f.core.recordOwnerTransfer(pair.primary.lease, target, targetOwner, Any(), id(),
            mapOf(pair.primary.modelId to "b".repeat(64), pair.aux.modelId to "b".repeat(64))))
        assertNull(f.core.modelRef(pair.primary.lease.cohortId, 1, pair.primary.modelId))
        assertSame(refs.first { it.modelId == pair.primary.modelId }, f.core.modelRef(target.cohortId, 2, pair.primary.modelId))
        assertNull(requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).cohorts.single().release)
    }
    @Test fun hardFaultRevocationSurvivesFailedRetirementThenSuccessfulDeathPublicationAndRestart() {
        val f = Fixture(); val pair = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        val retirement = requireNotNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "invoke_hard_timeout"))
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
        assertNull(f.core.modelRef(pair.primary.lease.cohortId, 1, pair.primary.modelId))
        f.io.publication = DeviceCheckPublication.NOT_PUBLISHED
        assertTrue(f.core.retireRuntime(retirement, id()) is DeviceCheckAuthorityOutcome.NotPublished)
        f.io.publication = DeviceCheckPublication.COMMITTED
        ok(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        val state = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        assertEquals("INTERRUPTED", state.latest!!.phase)
        assertEquals("CHILD_DEATH", state.cohorts.single().release)
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).read(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
    }
    @Test fun uncertainRetirementRecoveryCombinesPhysicalDeathAndPendingRevocationBeforeOpeningTheFence() {
        val f = Fixture(); val pair = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        val retirement = requireNotNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "native_error"))
        val operation = id(); f.io.publication = DeviceCheckPublication.INDETERMINATE
        assertTrue(f.core.retireRuntime(retirement, operation) is DeviceCheckAuthorityOutcome.Indeterminate)
        assertTrue(f.core.noteCohortRelease(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        f.io.publication = DeviceCheckPublication.COMMITTED
        assertTrue(f.core.reconcile(operation) is DeviceCheckAuthorityRead.NeedsCheck)
        val state = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        assertEquals("INTERRUPTED", state.latest!!.phase)
        assertEquals("CHILD_DEATH", state.cohorts.single().release)
        assertNull(f.core.unsettledOperation())
    }
    @Test fun failedRetirementAndDeathWritesLeaveDirtyOldProcessWhichNeverAutoRestoresPass() {
        val f = Fixture(); val pair = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        val retirement = requireNotNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "native_error"))
        f.io.publication = DeviceCheckPublication.NOT_PUBLISHED
        assertTrue(f.core.retireRuntime(retirement, id()) is DeviceCheckAuthorityOutcome.NotPublished)
        assertTrue(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH)
            is DeviceCheckAuthorityOutcome.NotPublished)
        assertTrue(f.restart(DeviceCheckProcessObservation.SAME_PROCESS).read(f.binding) is DeviceCheckAuthorityRead.Blocked)
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).read(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
    }
    @Test fun failedUnselectedCandidateRetiresOnlyItsCapabilitiesAndAllowsTheNextCandidate() {
        val f = Fixture(); val pair = f.pair()
        val retirement = requireNotNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "open_hard_timeout"))
        assertTrue(f.core.checkAttempt(pair.attempt, 1100))
        assertNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        ok(f.core.retireRuntime(retirement, id()))
        ok(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        val freshOwner = f.owner(pair.attempt)
        assertNotSame(pair.owner, freshOwner)
        val permit = ok(f.core.prepareNativeOpen(freshOwner, DeviceCheckNativeLease(id(), freshOwner.ownerId, 1), Any(), id(),
            f.primarySpec.copy(delegate = "cpu"), f.child, 20000))
        assertEquals("cpu", permit.spec.delegate)
        assertEquals("CHECKING", requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).latest!!.phase)
    }
    @Test fun aFrozenFinalPairHardFaultCannotBeCommittedAfterItsCapabilitiesAreRevoked() {
        val f = Fixture(); val pair = f.pair()
        val frozen = requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        val retirement = requireNotNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "invoke_hard_timeout"))
        assertTrue(f.core.commitSuccess(id(), frozen) is DeviceCheckAuthorityOutcome.Rejected)
        ok(f.core.retireRuntime(retirement, id()))
        assertEquals("INTERRUPTED", requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).latest!!.phase)
    }
    @Test fun frozenSuccessKeepsTheExistingPairOwnerCurrentWhileRejectingNewNativeAdmission() {
        val f = Fixture(); val pair = f.pair()
        val frozen = requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        assertSame(pair.owner, f.core.ownerRef(pair.owner.ownerId))
        assertNull(f.core.registerOwner("late-camera", pair.attempt, 1))
        assertTrue(f.core.prepareNativeOpen(pair.owner, pair.primary.lease, pair.identity, id(), f.primarySpec,
            f.child, 20000) is DeviceCheckAuthorityOutcome.Rejected)
        ok(f.core.commitSuccess(id(), frozen))
        assertSame(pair.owner, f.core.ownerRef(pair.owner.ownerId))
    }
    @Test fun runtimeRetirementRequiresTheExactCapturedOwnerEpochAndActualLease() {
        val f = Fixture(); val pair = f.pair()
        assertNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, Any(), "native_error"))
        val other = requireNotNull(f.core.registerOwner("camera-test", pair.attempt, 1))
        val target = pair.primary.lease.copy(ownerId = other.ownerId, ownerEpoch = 2)
        val targetIdentity = Any()
        ok(f.core.recordOwnerTransfer(pair.primary.lease, target, other, targetIdentity, id(),
            mapOf(pair.primary.modelId to "b".repeat(64), pair.aux.modelId to "b".repeat(64))))
        assertNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "native_error"))
        assertNotNull(f.core.reserveRuntimeRetirement(other, target.cohortId, targetIdentity, "native_error"))
    }
    @Test fun revocationDuringACommittedWriteIsRepairedBeforeTheCommandCanReturnSuccess() {
        val f = Fixture(); val pair = f.pair()
        val frozen = requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        f.io.afterPublish = {
            f.io.afterPublish = null
            assertNotNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "native_error"))
        }
        ok(f.core.commitSuccess(id(), frozen))
        assertEquals("INTERRUPTED", requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).latest!!.phase)
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
    }
    @Test fun failedRepairAfterAnAlreadyPublishedSuccessIsUncertainRatherThanNotPublished() {
        val f = Fixture(); val pair = f.pair()
        val frozen = requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))
        f.io.afterPublish = {
            f.io.afterPublish = null
            assertNotNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "native_error"))
            f.io.publication = DeviceCheckPublication.NOT_PUBLISHED
        }
        val operation = id()
        assertTrue(f.core.commitSuccess(operation, frozen) is DeviceCheckAuthorityOutcome.Indeterminate)
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.Blocked)
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).read(f.binding) is DeviceCheckAuthorityRead.NeedsCheck)
        assertTrue(f.core.noteCohortRelease(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        f.io.publication = DeviceCheckPublication.COMMITTED
        assertTrue(f.core.reconcile(operation) is DeviceCheckAuthorityRead.NeedsCheck)
        val state = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
        assertEquals("INTERRUPTED", state.latest!!.phase)
        assertEquals("CHILD_DEATH", state.cohorts.single().release)
    }
    @Test fun normalRetirementDoesNotRevokeTheMeasuredChoiceAndStaleChoiceCannotInterruptANewCheck() {
        val f = Fixture(); val pair = f.pair()
        ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        f.core.retireOwner(pair.owner)
        assertNull(f.core.reserveRuntimeRetirement(pair.owner, pair.primary.lease.cohortId, pair.identity, "native_error"))
        ok(f.core.recordCohortReleased(pair.primary.lease.cohortId, f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH))
        assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).read(f.binding) is DeviceCheckAuthorityRead.Ready)
        val fresh = f.begin()
        assertFalse(f.core.reserveChoiceRetirement(f.choice))
        ok(f.core.retireChoice(f.choice, id()))
        assertTrue(f.core.checkAttempt(fresh, 1100))
        assertEquals("CHECKING", requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).latest!!.phase)
    }
    @Test fun observedSessionRejectsLateOwnerRegistrationEvenWithTheSameActorBundle() {
        val f = Fixture(); val pair = f.pair()
        val bundle = ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        f.core.retireOwnersOutsideSession(2)
        assertNull(f.core.registerOwner("late-old-camera", bundle, 1))
        assertNotNull(f.core.registerOwner("new-camera", bundle, 2))
        f.core.retireOwnersOutsideSession(null)
        assertNull(f.core.registerOwner("late-logout-camera", bundle, 2))
    }

    @Test fun inconsistentDepthAndFeatureRecordsCannotCommitOrRestoreThroughTheCanonicalCodec() {
        val original = Fixture().full
        val invalid = listOf(PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED, PostLoginMetricDepthState.UNKNOWN,
            PostLoginMetricDepthState.TIMED_OUT).map { original.copy(metricDepthState = it) } +
            original.copy(state = PostLoginDeviceCheckState.LIMITED,
                disabledFeatures = setOf(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION))
        for (result in invalid) {
            val f = Fixture(); val pair = f.pair()
            assertFalse(DeviceCheckAuthorityValidation.result(result))
            assertNull(f.core.freezeSuccess(pair.attempt, 1100, result, f.choice, pair.proof))
            ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
            val stored = requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text))
            val corrupted = stored.copy(bundle = requireNotNull(stored.bundle).copy(result = result))
            f.io.text = requireNotNull(DeviceCheckAuthorityCodec.encode(corrupted))
            assertNull(DeviceCheckAuthorityCodec.decode(f.io.text))
            assertTrue(f.restart(DeviceCheckProcessObservation.ABSENT).read(f.binding) is DeviceCheckAuthorityRead.Blocked)
        }
    }

    @Test fun metricLimitedSupportedAndLegacyDeferredPendingResultsRemainLegal() {
        val f = Fixture(); val metric = PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE
        val limited = f.full.copy(state = PostLoginDeviceCheckState.LIMITED, disabledFeatures = setOf(metric))
        val legitimate = listOf(f.full, limited,
            limited.copy(metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED),
            limited.copy(metricDepthState = PostLoginMetricDepthState.UNKNOWN),
            limited.copy(metricDepthState = PostLoginMetricDepthState.TIMED_OUT),
            limited.copy(disabledFeatures = setOf(metric, PostLoginDeviceCheckFeature.OBSTACLE_DETECTION)),
            f.full.copy(cameraDependentChecksDeferred = true, metricDepthProbePolicyCurrent = false,
                metricDepthState = PostLoginMetricDepthState.PENDING),
            limited.copy(cameraDependentChecksDeferred = true, metricDepthProbePolicyCurrent = false,
                metricDepthState = PostLoginMetricDepthState.PENDING))
        legitimate.forEach { assertTrue(it.toString(), DeviceCheckAuthorityValidation.result(it)) }
    }

    @Test fun aContradictoryRecoveryUpdateCannotReplaceTheExistingBundleChoiceOrJournal() {
        val f = Fixture(); val pair = f.pair()
        val limited = f.full.copy(state = PostLoginDeviceCheckState.LIMITED,
            disabledFeatures = setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),
            metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)
        val bundle = ok(f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof))))
        val before = f.io.text; val writes = f.io.writes
        val contradictory = f.full.copy(metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)
        assertTrue(f.core.updateResult(bundle, id(), f.full, contradictory) is DeviceCheckAuthorityOutcome.Rejected)
        assertEquals(before, f.io.text); assertEquals(writes, f.io.writes)
        val restored = f.core.cachedSnapshot(f.binding) as DeviceCheckAuthorityRead.Ready
        assertEquals(f.full, restored.result); assertEquals(f.choice, restored.choice)
        val changed = ok(f.core.updateResult(bundle, id(), f.full, limited))
        ok(f.core.updateResult(changed, id(), limited, f.full)) // The explicit supported recovery remains legal.
    }

    internal class MemoryIo : DeviceCheckAuthorityPersistence {
        var text: String? = null; var writes = 0; var publication = DeviceCheckPublication.COMMITTED
        var afterPublish: (() -> Unit)? = null
        override fun read() = text
        override fun publish(canonicalState: String): DeviceCheckPublication {
            val outcome = publication
            writes++
            if (outcome != DeviceCheckPublication.NOT_PUBLISHED) text = canonicalState
            afterPublish?.invoke()
            return outcome
        }
    }
    internal class Proof(override val measurementId: String, override val primary: DeviceCheckNativeModelRef,
        override val auxiliary: DeviceCheckNativeModelRef) : DeviceCheckPairProof {
        var current = true; var consumptions = 0
        override fun isCurrent() = current
        override fun consume(): Boolean { if (!current || consumptions != 0) return false; consumptions++; return true }
    }
    internal data class PairFixture(val attempt: DeviceCheckAttemptRef, val owner: DeviceCheckOwnerRef,
        val primary: DeviceCheckNativeModelRef, val aux: DeviceCheckNativeModelRef, val proof: Proof, val identity: Any)
    internal class Fixture(private val trustProof: Boolean = true) {
        val io = MemoryIo(); val choice = JointRuntimeTestFixtures.choice()
        val binding = DeviceCheckAuthorityBinding("b".repeat(64), "c".repeat(64), choice.bindingHash, "d".repeat(64))
        val full = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL, emptySet(), false, true, PostLoginMetricDepthState.SUPPORTED)
        val child = DeviceCheckNativeProcess(200, 1000, "app:vision_runtime", 100, "boot:1")
        val primarySpec = DeviceCheckNativeSpec("PRIMARY", "primary", "e".repeat(64), "gpu", 2, "f".repeat(64))
        val auxSpec = DeviceCheckNativeSpec("FASTSAM", "fastsam", "e".repeat(64), "cpu", 2, "f".repeat(64))
        val core = newCore(DeviceCheckProcessObservation.SAME_PROCESS).also { it.read(binding) }
        fun begin() = ok(core.beginAttempt(binding, id(), 1000, 301000))
        fun owner(ref: DeviceCheckAuthoritySource) = requireNotNull(core.registerOwner("main-test", ref, 1))
        fun restart(observation: DeviceCheckProcessObservation) = newCore(observation)
        private fun newCore(observation: DeviceCheckProcessObservation) = DeviceCheckAuthorityCore(io, DeviceCheckProcessObserver { observation }, { 1100 }, { 8 }, true,
            verifyProofOrigin = { trustProof }).also { it.admit(binding) } // Explicit test issuer/admission; production uses the actual pair registry.
        fun pair(): PairFixture {
            val attempt = begin(); val owner = owner(attempt); val lease = DeviceCheckNativeLease(id(), owner.ownerId, 1); val identity = Any()
            fun open(spec: DeviceCheckNativeSpec): DeviceCheckNativeModelRef {
                val p = ok(core.prepareNativeOpen(owner, lease, identity, id(), spec, child, 20000))
                assertTrue(core.consumeNativeOpen(p, identity, 1001))
                return ok(core.recordModelOpened(p, id(), "a".repeat(64)))
            }
            val primary = open(primarySpec); val aux = open(auxSpec)
            return PairFixture(attempt, owner, primary, aux, Proof(choice.measurementId, primary, aux), identity)
        }
    }
    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun <T> ok(value: DeviceCheckAuthorityOutcome<T>): T {
            assertTrue("Expected durable commit: $value", value is DeviceCheckAuthorityOutcome.Committed)
            return (value as DeviceCheckAuthorityOutcome.Committed).value
        }
    }
}
