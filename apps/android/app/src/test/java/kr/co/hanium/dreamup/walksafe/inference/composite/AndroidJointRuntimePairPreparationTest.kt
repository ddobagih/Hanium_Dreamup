package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import sun.misc.Unsafe

class AndroidJointRuntimePairPreparationTest {
    @Test fun exactFinalPairRequiresTwoRealSamplesOnBothInstancesBeforeIssuingAOneUseProof() = Fixture().use { f ->
        val pair = f.prepare()
        assertEquals(1, f.primaryCreates)
        assertEquals(2, f.primarySamples)
        assertEquals(1, f.auxiliary.creates.get())
        assertEquals(2, f.auxiliary.invokes.get())
        assertEquals(listOf(VisionPurpose.WARMUP to f.deadline, VisionPurpose.BENCHMARK to f.deadline), f.primaryRequests)
        assertEquals(listOf(VisionPurpose.WARMUP, VisionPurpose.BENCHMARK), f.auxiliary.requests.map { it.purpose })
        assertTrue(f.auxiliary.requests.all { it.absoluteDeadlineElapsedNs == f.deadline * 1_000_000L && it.captureRawOutputs })
        assertTrue(pair.auxiliary.isReady)
        assertFalse(pair.auxiliary.stats().inflight)
        assertEquals(4, pair.evidence.size)
        assertTrue(pair.evidence.all { it.rawFinite && it.consumerDrained && !it.referenceVerifierApplied })
        assertEquals(f.choice.measurementId, pair.proof.measurementId)
        assertSame(f.primaryRef, pair.proof.primary)
        assertSame(f.auxiliaryRef, pair.proof.auxiliary)
        assertTrue(JointPairReadyProof.ownsProof(pair.proof))
        assertFalse(JointPairReadyProof.ownsQualifiedProof(pair.proof))
        assertFalse(pair.proof.isCurrent())
        assertFalse(pair.proof.consume())
        assertTrue(pair.proof.bindConsumerGateOnce { true })
        assertFalse(pair.proof.bindConsumerGateOnce { true })
        val forged = object : DeviceCheckPairProof {
            override val measurementId = f.choice.measurementId
            override val primary = f.primaryRef
            override val auxiliary = f.auxiliaryRef
            override fun isCurrent() = true
            override fun consume() = true
        }
        assertFalse(JointPairReadyProof.ownsProof(forged))
        assertTrue(pair.proof.consume())
        assertFalse(pair.proof.consume())
        assertTrue(pair.proof.isCurrent())
        f.nativeCurrent = false
        assertFalse(pair.proof.isCurrent())
    }

    @Test fun strictFinalQualificationPromotesOnlyTheSameUnconsumedPairAndRetainsItsWeakConsumerGate() = Fixture().use { f ->
        val base = f.prepare().proof
        base.bindConsumerGateOnce { true }
        assertTrue(base.isCurrent())
        assertFalse(JointPairReadyProof.ownsQualifiedProof(base))
        val qualification = f.qualification()
        val ready = base.attachFinalQualification(f.choice, qualification)
        assertFalse(base.isCurrent())
        assertSame(qualification, ready.qualification)
        assertEquals(4, ready.sampleEvidence.size)
        assertEquals(8, ready.qualification!!.strictEvidence.size)
        assertTrue(ready.isCurrent())
        assertTrue(JointPairReadyProof.ownsQualifiedProof(ready))
        assertFalse(ready.bindConsumerGateOnce { true })
        assertThrows(IllegalStateException::class.java) { base.attachFinalQualification(f.choice, qualification) }
        assertTrue(ready.consume())
        f.nativeCurrent = false
        assertFalse(JointPairReadyProof.ownsQualifiedProof(ready))
    }

    @Test fun qualificationCannotAttachToAnotherMeasurementFinalEvidenceOrActualModel() = Fixture().use { f ->
        val base = f.prepare().proof
        val qualification = f.qualification()
        assertThrows(IllegalStateException::class.java) {
            base.attachFinalQualification(f.choice.copy(measurementId = java.util.UUID.randomUUID().toString()), qualification)
        }
        assertThrows(IllegalStateException::class.java) {
            base.attachFinalQualification(f.choice.copy(evidence = f.choice.evidence.copy(primaryLongestGapMs = 220.0)), qualification)
        }
        val wrongSnapshot = f.snapshot().let { it.copy(auxiliaryHandle = it.auxiliaryHandle.copy(modelInstanceId = "different-native-model")) }
        assertThrows(IllegalStateException::class.java) { base.attachFinalQualification(f.choice, f.qualification(wrongSnapshot)) }
        val ready = base.attachFinalQualification(f.choice, qualification)
        assertFalse(ready.isCurrent()) // An unbound warm proof cannot borrow consumer readiness.
        assertTrue(ready.bindConsumerGateOnce { true })
        assertTrue(JointPairReadyProof.ownsQualifiedProof(ready))
    }

    @Test fun qualifiedTransferKeepsTheStrictEightAndOriginalSoakAndRebindsOnlyItsCurrentEpoch() = Fixture().use { f ->
        val base = f.prepare().proof
        val ready = base.attachFinalQualification(f.choice, f.qualification())
        ready.bindConsumerGateOnce { true }
        ready.consume()
        val witness = requireNotNull(ready.claimForTransfer())
        assertSame(ready.qualification, witness.qualification)
        val target = f.targetSnapshot()
        val primary = f.ref(target.primaryHandle, f.primaryRef.spec.modelSha256, target.lease)
        val auxiliary = f.ref(target.auxiliaryHandle, f.auxiliaryRef.spec.modelSha256, target.lease)
        assertThrows(IllegalStateException::class.java) {
            JointRuntimePairPreparation.reissueAfterTransfer(f.config,
                f.choice.copy(evidence = f.choice.evidence.copy(primaryLongestGapMs = 220.0)), witness,
                f.ownerBinding(), target, primary, auxiliary, { true })
        }
        val transferred = JointRuntimePairPreparation.reissueAfterTransfer(f.config, f.choice, witness,
            f.ownerBinding(), target, primary, auxiliary, { true })
        assertEquals(1L, transferred.qualification!!.qualifiedEpoch)
        assertEquals(2L, transferred.qualification!!.currentEpoch)
        assertEquals(ready.qualification!!.strictEvidence, transferred.qualification!!.strictEvidence)
        assertEquals(ready.qualification!!.soakEvidence, transferred.qualification!!.soakEvidence)
        assertFalse(JointPairReadyProof.ownsQualifiedProof(transferred))
        transferred.bindConsumerGateOnce { true }
        assertTrue(JointPairReadyProof.ownsQualifiedProof(transferred))
    }

    @Test fun aCommittedProofCanClaimTransferOnceAndReissueOnlyTheSameModelsAtTheNextEpoch() = Fixture().use { f ->
        val source = f.prepare().proof
        var sourceConsumerCurrent = true
        assertTrue(source.bindConsumerGateOnce { sourceConsumerCurrent })
        assertTrue(source.consume())
        val witness = requireNotNull(source.claimForTransfer())
        assertFalse(source.isCurrent())
        assertNull(source.claimForTransfer())
        sourceConsumerCurrent = false
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(witness.javaClass))
        val forbidden = listOf("Function", "CompletableFuture", "CompletionStage", "Activity", "FastSamRuntimeService", "TfliteAndroidFrameDetector")
        assertTrue(witness.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .none { field -> forbidden.any { field.type.name.contains(it) } })
        val target = f.targetSnapshot()
        val primary = f.ref(target.primaryHandle, f.primaryRef.spec.modelSha256, target.lease)
        val auxiliary = f.ref(target.auxiliaryHandle, f.auxiliaryRef.spec.modelSha256, target.lease)
        val reissued = JointRuntimePairPreparation.reissueAfterTransfer(f.config, f.choice, witness,
            f.ownerBinding(), target, primary, auxiliary, { true })
        assertTrue(JointPairReadyProof.ownsProof(reissued))
        assertFalse(reissued.isCurrent())
        assertTrue(reissued.bindConsumerGateOnce { true })
        assertTrue(reissued.isCurrent())
        assertTrue(reissued.consume())
        assertEquals(2, f.primarySamples)
        assertEquals(2, f.auxiliary.invokes.get())
        assertThrows(IllegalStateException::class.java) { JointRuntimePairPreparation.reissueAfterTransfer(f.config,
            f.choice, witness, f.ownerBinding(), target, primary, auxiliary, { true }) }
        Unit
    }

    @Test fun transferRejectsChangedBindingModelOrUnactivatedTargetWithoutConsumingTheWitness() = Fixture().use { f ->
        val source = f.prepare().proof
        source.bindConsumerGateOnce { true }
        val witness = requireNotNull(source.claimForTransfer())
        val target = f.targetSnapshot()
        val primary = f.ref(target.primaryHandle, f.primaryRef.spec.modelSha256, target.lease)
        val auxiliary = f.ref(target.auxiliaryHandle, f.auxiliaryRef.spec.modelSha256, target.lease)
        assertThrows(IllegalStateException::class.java) {
            JointRuntimePairPreparation.reissueAfterTransfer(f.config, f.choice, witness,
                f.ownerBinding().copy(actorBindingHash = "1".repeat(64)), target, primary, auxiliary, { true })
        }
        assertThrows(IllegalStateException::class.java) {
            JointRuntimePairPreparation.reissueAfterTransfer(f.config, f.choice, witness, f.ownerBinding(),
                target.copy(auxiliaryHandle = target.auxiliaryHandle.copy(modelInstanceId = "another-model")), primary, auxiliary, { true })
        }
        assertThrows(IllegalStateException::class.java) {
            JointRuntimePairPreparation.reissueAfterTransfer(f.config, f.choice, witness,
                f.ownerBinding(), target, primary, auxiliary, { false })
        }
        val valid = JointRuntimePairPreparation.reissueAfterTransfer(f.config, f.choice, witness,
            f.ownerBinding(), target, primary, auxiliary, { true })
        assertTrue(valid.bindConsumerGateOnce { true })
        assertTrue(valid.isCurrent())
    }

    @Test fun aReferenceVerifierFailureClosesBothModelsWithoutPublishingThePreparedPair() = Fixture().use { f ->
        val marker = IllegalStateException("strict reference/mask verifier rejected")
        val visited = mutableListOf<JointPairSampleEvidence>()
        val failure = runCatching { f.prepare { sample ->
            visited += sample.evidence
            if (sample.evidence.role == VisionRole.FASTSAM.name &&
                sample.evidence.phase == JointPairSamplePhase.VERIFICATION) throw marker
        } }.exceptionOrNull()
        assertSame(marker, failure)
        assertEquals(4, visited.size)
        assertTrue(visited.all { it.referenceVerifierApplied && JointRuntimeChoicePolicy.validHash(it.argbInputDigest) })
        assertEquals(1, f.primaryCloses)
        requireNotNull(f.actualAuxiliary).releasedFuture().get(3, TimeUnit.SECONDS)
        assertEquals(1, f.auxiliary.closes.get())
    }

    @Test fun rejectedRegistrationOrLateClockCannotStartAnotherAuxiliaryOrIssueReadiness() {
        Fixture().use { f ->
            f.acceptPrimary = false
            assertNotNull(runCatching { f.prepare() }.exceptionOrNull())
            assertEquals(1, f.primaryCloses)
            assertEquals(0, f.primarySamples)
            assertEquals(0, f.auxiliary.creates.get())
            assertEquals(1, f.creationFinished.size)
            assertNotNull(f.creationFinished.single())
            assertEquals(0, f.creationNotStarted)
        }
        Fixture().use { f ->
            f.onPrimarySample = { if (f.primarySamples == 2) f.now = f.deadline }
            assertTrue(runCatching { f.prepare() }.exceptionOrNull() is java.util.concurrent.TimeoutException)
            assertEquals(1, f.primaryCloses)
            requireNotNull(f.actualAuxiliary).releasedFuture().get(3, TimeUnit.SECONDS)
            assertEquals(1, f.auxiliary.closes.get())
        }
    }

    @Test fun auxiliaryStartupFailureCannotPassWithOnlyThePrimaryAndClosesItsActualInstance() = Fixture().use { f ->
        val marker = IllegalStateException("auxiliary OPEN failed")
        f.auxiliary.loadFailure = marker
        f.expectedAuxiliaryFailure = marker
        val failure = runCatching { f.prepare() }.exceptionOrNull()
        assertTrue(hasCause(failure, marker))
        assertEquals(1, f.primaryCloses)
        assertEquals(1, f.auxiliary.creates.get())
        assertTrue(f.primaryPhysical.isDone)
        assertTrue(requireNotNull(f.actualAuxiliary).releasedFuture().get(3, TimeUnit.SECONDS) == null)
    }

    @Test fun aPartialOrNonfinitePrimarySampleCannotMintAPairProof() {
        for (partial in listOf(false, true)) Fixture().use { f ->
            f.partial = partial
            f.primaryRaw = FloatArray(1800).also { if (!partial) it[1799] = Float.NaN }
            assertTrue(runCatching { f.prepare() }.exceptionOrNull() is IllegalStateException)
            assertEquals(1, f.primaryCloses)
        }
    }

    @Test fun differentActualCohortOrMissingOpaqueRefRejectsBothAllocatedModels() {
        for (missingRef in listOf(false, true)) Fixture().use { f ->
            if (missingRef) f.missingAuxiliaryRef = true else f.auxiliary.cohortId = "d".repeat(64)
            assertTrue(runCatching { f.prepare() }.exceptionOrNull() is IllegalStateException)
            assertEquals(1, f.primaryCloses)
            requireNotNull(f.actualAuxiliary).releasedFuture().get(3, TimeUnit.SECONDS)
            assertEquals(1, f.auxiliary.closes.get())
        }
    }

    @Test fun failureRetainsBothPhysicalReceiptsAndLateActualReleaseAloneReopensPreparation() = Fixture().use { f ->
        f.holdPrimaryRelease = true
        f.auxiliary.holdRelease = true
        val marker = IllegalStateException("auxiliary verification failed")
        f.auxiliary.invokeFailure = marker
        f.expectedAuxiliaryFailure = marker
        assertTrue(hasCause(runCatching { f.prepare() }.exceptionOrNull(), marker))
        assertTrue(runCatching { f.fence.ensureConfirmed() }.exceptionOrNull() is DetectorNativeCleanupFailure)
        assertEquals(1, f.primaryCloses)
        f.primaryPhysical.complete(Unit)
        assertTrue(runCatching { f.fence.ensureConfirmed() }.exceptionOrNull() is DetectorNativeCleanupFailure)
        f.auxiliary.physical.complete(null)
        requireNotNull(f.actualAuxiliary).releasedFuture().get(3, TimeUnit.SECONDS)
        val guardDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (runCatching { f.fence.ensureConfirmed() }.isFailure && System.nanoTime() < guardDeadline) Thread.yield()
        f.fence.ensureConfirmed()
    }

    @Test fun cancelledOrOverduePreparationCannotCreateNativeOrLeaveTheCreationGatePending() {
        for (overdue in listOf(false, true)) Fixture().use { f ->
            if (overdue) f.now = f.deadline else f.current = false
            assertNotNull(runCatching { f.prepare() }.exceptionOrNull())
            assertEquals(0, f.primaryCreates)
            assertEquals(0, f.auxiliary.creates.get())
            assertEquals(0, f.creationFinished.size)
            assertEquals(1, f.creationNotStarted) // Explicit preflight NO_CREATE, not an unknown thrown create.
        }
    }

    private class Fixture : JointPairPreparationPorts<Unit>, AutoCloseable {
        val config = JointRuntimeTestFixtures.config
        val choice = JointRuntimeTestFixtures.choice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        val detector = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        val fence = JointRuntimeReleaseFence()
        val auxiliary = JointPairTestAuxiliaryFactory()
        val primaryPhysical = CompletableFuture<Unit>()
        val lease = VisionCohortLease("c".repeat(64), "pair-final-owner", 1L)
        val primaryRuntime = AndroidDetectorRuntime("cpu", "cpu", 1, modelOwnerPid = 321,
            modelOwnerThreadId = 41, modelInstanceId = "primary-final", cohortId = lease.cohortId)
        val optionsJson = VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions("cpu", 1, false, false, true))
        fun json(thread: Long) = JSONObject().put("requestedDelegate", "cpu").put("activeDelegate", "cpu")
            .put("numThreads", 1).put("fallbackUsed", false).put("modelOwnerPid", 321)
            .put("modelOwnerThreadId", thread).put("loadMs", 0).put("gpuPrecisionLossRequested", false)
            .put("gpuPrecisionLossAllowed", false).put("gpuSerializationCacheRequested", true)
            .put("gpuDelegateAttached", false).toString()
        val primaryHandle = VisionModelHandle(lease.cohortId, "primary-final", 1, VisionRole.PRIMARY,
            config.primaryModelKey, json(41))
        val auxiliaryHandle = VisionModelHandle(lease.cohortId, "auxiliary-final", 1, VisionRole.FASTSAM,
            FastSamModelContract.MODEL_ID, json(42))
        fun ref(handle: VisionModelHandle, sha: String, actualLease: VisionCohortLease = lease) = DeviceCheckNativeModelRef(handle.modelInstanceId,
            DeviceCheckNativeLease(actualLease.cohortId, actualLease.ownerId, actualLease.ownerEpoch), DeviceCheckNativeSpec(handle.role.name,
                handle.modelKey, sha, "cpu", 1, VisionRuntimeCodec.optionsHash(optionsJson)),
            handle.modelInstanceId, VisionRuntimeCodec.runtimeHash(handle.runtimeJson), "fake-authority")
        val primaryRef = ref(primaryHandle, requireNotNull(config.unifiedWalksafe?.artifactSha256))
        val auxiliaryRef = ref(auxiliaryHandle, FastSamModelContract.SHA256)
        var now = 1_000L
        val deadline = 61_000L
        var current = true
        var nativeCurrent = true
        var primaryCreates = 0
        var primarySamples = 0
        val primaryRequests = mutableListOf<Pair<VisionPurpose, Long>>()
        var primaryCloses = 0
        var holdPrimaryRelease = false
        var missingAuxiliaryRef = false
        var partial = false
        var primaryRaw = FloatArray(1800)
        var actualAuxiliary: FastSamRuntimeService<Unit>? = null
        var expectedAuxiliaryFailure: Throwable? = null
        val creationFinished = mutableListOf<Throwable?>()
        var creationNotStarted = 0
        var acceptPrimary = true
        var onPrimarySample: () -> Unit = {}
        val fixture = JointPairFixture("pair-sample", "a".repeat(64), ArgbImage(2, 1, intArrayOf(1, 2)))
        override fun ownerBinding() = DeviceCheckAuthorityBinding("f".repeat(64), "e".repeat(64), choice.bindingHash, "b".repeat(64))
        fun targetSnapshot() = VisionPairSnapshot(lease.copy(ownerId = "camera-target", ownerEpoch = 2),
            primaryHandle.copy(ownerEpoch = 2), auxiliaryHandle.copy(ownerEpoch = 2), 321)
        fun snapshot() = VisionPairSnapshot(lease, primaryHandle, auxiliaryHandle, 321)
        fun qualification(snapshot: VisionPairSnapshot = snapshot()): JointRuntimeFinalQualification {
            val strict = JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS.flatMap { fixture -> VisionRole.entries.map { role ->
                JointRuntimeStrictSampleEvidence(role, fixture, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                    (if (role == VisionRole.PRIMARY) snapshot.primaryHandle else snapshot.auxiliaryHandle).modelInstanceId,
                    snapshot.lease.cohortId, if (role == VisionRole.PRIMARY) listOf(JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT)
                        else listOf(FastSamModelContract.DETECTION_FLOATS, FastSamModelContract.PROTOTYPE_FLOATS),
                    1000L, true, true, true, true)
            } }
            return JointRuntimeFinalQualification.issue(choice, snapshot, strict, 10_000L,
                10_000L + choice.evidence.durationMs, 8)
        }
        fun prepare(verify: ((JointPairSampleOutput) -> Unit)? = null) = JointRuntimePairPreparation.prepare(config, choice, choice.bindingHash, 8, true,
            7, object : FastSamRuntimeService.Listener<Unit> { override fun onResult(result: FastSamResult<Unit>) {} },
            deadline, { now }, { current }, listOf(fixture, fixture), this, fence,
            onPrimaryCreated = { acceptPrimary }, onPrimaryCreationFinished = { creationFinished += it },
            verifySample = verify,
            onPrimaryCreationNotStarted = { creationNotStarted++ })
        override fun createPrimary(config: TwoModelRuntimeConfig, options: ModelRuntimeOptions): AndroidDetectorLoadResult {
            primaryCreates++
            assertFalse(options.fallbackToCpu)
            assertFalse(options.gpuPrecisionLossAllowed)
            assertTrue(options.gpuSerializationCacheEnabled)
            assertNull(config.personSupplement)
            return AndroidDetectorLoadResult(detector, true, true, config.primaryModelKey, false, "created",
                actualReleasedFuture = primaryPhysical)
        }
        override fun primarySample(detector: TfliteAndroidFrameDetector, fixture: JointPairFixture,
            purpose: VisionPurpose, absoluteDeadlineMs: Long): JointPairPrimarySample {
            primaryRequests += purpose to absoluteDeadlineMs
            assertSame(this.detector, detector)
            primarySamples++
            onPrimarySample()
            return JointPairPrimarySample(AndroidCalibrationDetectionResult(AndroidDetectionResult(emptyList(),
                AndroidDetectorTiming(modelKey = config.primaryModelKey, completedModels = listOf(config.primaryModelKey),
                    modelRuntime = primaryRuntime, modelInferenceMs = 0, modelParseMs = 0), partial = partial), primaryRaw.copyOf()), "b".repeat(64))
        }
        override fun closePrimary(detector: TfliteAndroidFrameDetector) {
            assertSame(this.detector, detector); primaryCloses++
            if (!holdPrimaryRelease) primaryPhysical.complete(Unit)
        }
        override fun createAuxiliary(epoch: Long, options: FastSamRuntimeOptions,
            listener: FastSamRuntimeService.Listener<Unit>): FastSamRuntimeService<Unit> =
            auxiliary.create(epoch, options, listener, { now * 1_000_000L }).also { actualAuxiliary = it }
        override fun validatePair(primary: AndroidDetectorRuntime, auxiliary: FastSamRuntimeInfo) =
            VisionPairSnapshot(lease, primaryHandle, auxiliaryHandle, 321)
        override fun modelRef(cohortId: String, epoch: Long, modelId: String): DeviceCheckNativeModelRef? = when (modelId) {
            primaryRef.modelId -> primaryRef
            auxiliaryRef.modelId -> auxiliaryRef.takeUnless { missingAuxiliaryRef }
            else -> null
        }
        override fun currentValidator(primary: TfliteAndroidFrameDetector, auxiliary: FastSamRuntimeService<Unit>,
            snapshot: VisionPairSnapshot, primaryRef: DeviceCheckNativeModelRef,
            auxiliaryRef: DeviceCheckNativeModelRef): () -> Boolean = { nativeCurrent && auxiliary.isReady && !primaryPhysical.isDone }
        override fun close() {
            primaryPhysical.complete(Unit)
            val service = actualAuxiliary ?: return
            var semanticFailure: Throwable? = null
            try {
                service.closeAsync().get(3, TimeUnit.SECONDS)
            } catch (error: java.util.concurrent.ExecutionException) {
                semanticFailure = error.cause ?: error
            } finally {
                auxiliary.physical.complete(null)
                service.releasedFuture().get(3, TimeUnit.SECONDS)
                val stoppedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while ((service.stats().modelOwnerAlive || service.stats().cpuPostOwnerAlive) && System.nanoTime() < stoppedBy)
                    Thread.yield()
                assertFalse("Auxiliary model owner must terminate", service.stats().modelOwnerAlive)
                assertFalse("Auxiliary consumer owner must terminate", service.stats().cpuPostOwnerAlive)
            }
            val expected = expectedAuxiliaryFailure
            if (expected == null && semanticFailure != null) throw AssertionError("Unexpected auxiliary functional-close failure", semanticFailure)
            if (expected != null) assertTrue("The expected operation failure must remain the functional-close cause",
                hasCause(semanticFailure, expected))
        }
    }

    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        private fun hasCause(error: Throwable?, expected: Throwable): Boolean =
            error != null && (error === expected || hasCause(error.cause, expected))
    }
}
