package kr.co.hanium.dreamup.walksafe.inference.composite

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil

/** Real bundled CPU1 models and production proxies/host/gates. No production result is written. */
@RunWith(AndroidJUnit4::class)
class JointRuntimeSampleHostAndroidTest {
    @Test(timeout = 180_000L)
    fun realCpuSampleReferenceAndQualifiedFinalPairKeepTheirInstancesUntilActualExit() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("allowJointSampleHostTests") == "true")
        assertTrue("The isolated production runtime requires API 31+", Build.VERSION.SDK_INT >= 31)
        val started = SystemClock.elapsedRealtime()
        val workDeadline = started + 160_000L
        val cleanupDeadline = started + 175_000L
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val config = TwoModelRuntimeConfig.load(app).copy(fallbackModelKey = null, customTactile = null,
            cocoGeneral = null, personSupplement = null)
        val model = requireNotNull(config.unifiedWalksafe)
        assertEquals(768, model.inputSize)
        assertArrayEquals(intArrayOf(1, 300, 6), model.outputTensorShape())
        assertFalse(model.outputFormat.isRaw)
        val manifest = RuntimeCalibrationFixtures(app).loadManifest()
        assertEquals(RuntimeCalibrationFixtures.VERSION, manifest.version)
        assertEquals(RuntimeCalibrationFixtures.MANIFEST_SHA256, manifest.sha256)
        assertEquals(JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS, manifest.fixtures.map { it.id }.toSet())
        assertTrue(manifest.fixtures.all { it.required })
        assertTrue("NEAR_THRESHOLD must have actual required input coverage", manifest.fixtures.any {
            RuntimeCalibrationFixtureRole.NEAR_THRESHOLD in it.roles })

        val scope = VisionDiagnosticScope(app, "sample_host_" + UUID.randomUUID())
        val client = RemoteVisionClient.forMainProcess(app)
        val registry = VisionConsumerRegistry.process
        assertNull("A previous Java consumer still owns the runtime", registry.pendingRelease())
        val memoryBefore = ParentDirectBufferArena.process().memoryLedger.snapshot()
        val fixtures = JointRuntimeSampleFixtures.load(app) // Decoded exactly once for all three cohorts.
        val cpu1 = RuntimeCandidate(RuntimeBackend.CPU, 1)
        val candidate = JointRuntimeCandidate(cpu1, cpu1, 400L, 600L)
        val policy = JointRuntimeSearchPolicy(started, started + 300_000L, Runtime.getRuntime().availableProcessors())
        val phases = mutableListOf<Phase>()
        val diagnostic = mutableListOf<String>()
        var stage = "initializing"
        var references: JointRuntimeReferenceSet? = null
        var finalPair: JointRuntimeFinalQualifiedPair<Unit>? = null
        var consumerClaim: VisionConsumerRegistry.Claim? = null
        var consumerReleased: CompletionStage<Unit>? = null
        val consumerDrained = CompletableFuture<Unit>()
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        var failed: Throwable? = null
        fun current() = alive.get() && SystemClock.elapsedRealtime() < workDeadline && !Thread.currentThread().isInterrupted
        fun budget(phase: JointRuntimeSearchPhase, maximumMs: Long): JointRuntimePhaseBudget {
            val at = SystemClock.elapsedRealtime()
            return JointRuntimePhaseBudget(phase, at, minOf(workDeadline, at + maximumMs))
        }
        fun host(label: String, epoch: Long): Phase {
            val id = "$label-${UUID.randomUUID()}"
            val context = scope.context(id) // Immutable owner captured before the new host starts.
            val owner = VisionRuntimeContext.ownerRef(context)
            return Phase(label, owner, JointRuntimeSampleHost.create(context, config, scope.authority,
                fixtures, epoch, FastSamRuntimeService.Listener<Unit> {})).also(phases::add)
        }
        try {
            stage = "provisional"
            val trial = host(stage, 1L)
            val measured = trial.host.measureProvisional(candidate, budget(JointRuntimeSearchPhase.PROVISIONAL, 35_000L), ::current)
            val assessment = policy.assessProvisional(measured)
            diagnostic += "provisional=${assessment.failure}; p95=${p95(measured.primaryTurnaroundMs)}/${p95(measured.auxiliaryTurnaroundMs)}; " +
                "counts=${measured.primaryTurnaroundMs.size}/${measured.primarySubmittedFrames},${measured.auxiliaryTurnaroundMs.size}/${measured.auxiliarySubmittedFrames}"
            verifyReleased(trial, client, cleanupDeadline)

            stage = "reference_cpu1_four_fixtures"
            val reference = host(stage, 2L)
            references = JointRuntimeReferenceSet.capture(reference.host, budget(JointRuntimeSearchPhase.REFERENCE, 70_000L), ::current)
            verifyReleased(reference, client, cleanupDeadline)
            diagnostic += "references=actual_CPU1_required_four_raw_roles_and_masks"

            stage = "final_strict_eight_and_same_instance_soak"
            val final = host(stage, 3L)
            val retained = JointRuntimeFinalPairPreparation.prepare(final.host, candidate, requireNotNull(references),
                budget(JointRuntimeSearchPhase.FINAL, 90_000L), ::current)
            finalPair = retained
            val prepared = retained.prepared
            val proof = prepared.proof
            val snapshot = proof.snapshot
            val qualification = retained.qualification
            diagnostic += "final=${prepared.choice.measurementId}; cohort=${snapshot.lease.cohortId}; pid=${snapshot.childPid}; " +
                "ids=${snapshot.primaryHandle.modelInstanceId}/${snapshot.auxiliaryHandle.modelInstanceId}; " +
                "soak=${qualification.soakEvidence.durationMs}; p95=${p95(qualification.soakEvidence.primaryTurnaroundMs)}/${p95(qualification.soakEvidence.auxiliaryTurnaroundMs)}"
            assertEquals(candidate, prepared.choice.candidate)
            assertTrue(snapshot.childPid > 0 && snapshot.childPid != Process.myPid())
            assertEquals(10_000L, JointRuntimeChoicePolicy.MINIMUM_DURATION_MS)
            assertTrue(qualification.soakEvidence.durationMs >= JointRuntimeChoicePolicy.MINIMUM_DURATION_MS)
            assertTrue(p95(qualification.soakEvidence.primaryTurnaroundMs) <= JointRuntimeChoicePolicy.MAXIMUM_SAMPLE_P95_MS)
            assertTrue(p95(qualification.soakEvidence.auxiliaryTurnaroundMs) <= JointRuntimeChoicePolicy.MAXIMUM_SAMPLE_P95_MS)
            assertNull(JointRuntimeChoicePolicy.rejectionReason(candidate, qualification.soakEvidence, Runtime.getRuntime().availableProcessors()))
            assertSame(qualification, proof.qualification)
            val auxiliaryRuntime = requireNotNull(prepared.auxiliary.runtimeIfReady())
            assertEquals(snapshot.auxiliaryHandle.modelInstanceId, auxiliaryRuntime.modelInstanceId)
            assertEquals(snapshot.childPid.toLong(), auxiliaryRuntime.modelOwnerProcessId)
            assertEquals("CPU", auxiliaryRuntime.requestedBackend.name)
            assertEquals("CPU", auxiliaryRuntime.actualBackend.name)
            assertEquals(1, auxiliaryRuntime.numThreads)
            assertFalse(auxiliaryRuntime.fallbackUsed)
            assertEquals(model.artifactSha256, proof.primary.spec.modelSha256)
            assertEquals(FastSamModelContract.SHA256, proof.auxiliary.spec.modelSha256)
            assertTrue(qualification.matches(prepared.choice, snapshot))
            assertEquals(4, prepared.evidence.size)
            assertEquals(8, qualification.strictEvidence.size)
            assertEquals(manifest.fixtures.flatMap { f -> VisionRole.entries.map { f.id to it } }.toSet(),
                qualification.strictEvidence.map { it.fixtureId to it.role }.toSet())
            qualification.strictEvidence.forEach { evidence ->
                val fixture = manifest.fixtures.single { it.id == evidence.fixtureId }
                assertEquals(fixture.sha256, evidence.fixtureSha256)
                assertEquals(snapshot.lease.cohortId, evidence.cohortId)
                assertEquals((if (evidence.role == VisionRole.PRIMARY) snapshot.primaryHandle else snapshot.auxiliaryHandle).modelInstanceId, evidence.modelInstanceId)
                assertTrue(evidence.rawFinite && evidence.rawEquivalent && evidence.fixtureRolesMatched && evidence.masksEquivalent)
                assertEquals(if (evidence.role == VisionRole.PRIMARY) listOf(JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT)
                    else listOf(FastSamModelContract.DETECTION_FLOATS, FastSamModelContract.PROTOTYPE_FLOATS), evidence.rawValueCounts)
            }
            assertTrue(prepared.evidence.all { it.cohortId == snapshot.lease.cohortId && it.rawFinite && it.consumerDrained })
            assertFalse("Selected native models must remain alive after final qualification", final.host.actualReleasedFuture.toCompletableFuture().isDone)
            assertFalse(requireNotNull(prepared.load.actualReleasedFuture).toCompletableFuture().isDone)
            assertFalse(prepared.auxiliary.releasedFuture().isDone)
            assertSame(snapshot.lease, client.peekLease(final.owner.ownerId))
            assertSame(snapshot.primaryHandle, client.validatePair(snapshot.lease, snapshot.primaryHandle.modelInstanceId,
                snapshot.auxiliaryHandle.modelInstanceId).primaryHandle)
            val oldIds = phases.dropLast(1).flatMap { it.host.warmEvidence }.map { it.modelInstanceId }.toSet()
            assertFalse(snapshot.primaryHandle.modelInstanceId in oldIds)
            assertFalse(snapshot.auxiliaryHandle.modelInstanceId in oldIds)

            stage = "kept_primary_actual_raw_fixture_audit"
            auditActualFixtureRoles(retained, fixtures, model, workDeadline, diagnostic)
            // A fixed-candidate integration probe continues to references to expose accuracy failures,
            // but it cannot report engineering success after a rejected provisional measurement.
            assertNull("Provisional gate was rejected: ${diagnostic.joinToString("; ")}", assessment.failure)

            stage = "qualified_origin_with_metadata_consumer_claim"
            assertFalse("An unbound proof cannot authorize production publication", JointPairReadyProof.ownsQualifiedProof(proof))
            val claim = requireNotNull(registry.acquire(VisionConsumerRegistry.Owner(final.owner.ownerId,
                "diagnostic:${prepared.choice.measurementId}:${prepared.choice.bindingHash}"), snapshot.lease,
                listOf(snapshot.primaryHandle, snapshot.auxiliaryHandle)))
            consumerClaim = claim
            consumerReleased = requireNotNull(registry.pendingRelease())
            assertTrue(proof.bindConsumerGateOnce { registry.permits(claim) })
            assertTrue(JointPairReadyProof.ownsQualifiedProof(proof))
            assertTrue("Use the exact production-origin predicate without publishing production PASS",
                AndroidDeviceCheckAuthority.proofOriginVerifier(false)(proof))
            assertTrue(proof.consume())
            assertFalse(proof.consume())
            assertTrue(proof.isCurrent())
            Log.i(TAG, diagnostic.joinToString("; "))
        } catch (error: Throwable) {
            val observedFailure = generateSequence(error) { it.cause }.filterIsInstance<JointRuntimeSampleFailure>().firstOrNull()?.failure
            val ownedModels = phases.joinToString { phase ->
                "${phase.name}=" + phase.host.warmEvidence.joinToString { "${it.role}:${it.modelInstanceId}@${it.cohortId}" }
            }
            val detail = "stage=$stage; failure=$observedFailure; candidate=$candidate; p95Limit=${JointRuntimeChoicePolicy.MAXIMUM_SAMPLE_P95_MS}; " +
                "${diagnostic.joinToString("; ")}; models=$ownedModels; memory=${ParentDirectBufferArena.process().memoryLedger.snapshot()}"
            failed = AssertionError(detail, error)
            throw failed!!
        } finally {
            alive.set(false)
            val cleanupErrors = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { cleanupErrors += error } }
            finalPair?.prepared?.proof?.revoke()
            val finalSnapshot = finalPair?.prepared?.proof?.snapshot
            consumerClaim?.let { claim -> finalSnapshot?.let { snapshot ->
                cleanup { assertTrue(registry.retire(claim, consumerDrained, client.actualReleasedFuture(snapshot.lease.cohortId))) }
            } }
            phases.asReversed().forEach { phase ->
                cleanup {
                    val captured = client.retirementSnapshot(phase.owner)
                    phase.host.auxiliary?.closeAsync()
                    val primaryClose = runCatching { phase.host.primary?.close() }.exceptionOrNull()
                    val auxiliaryClose = phase.host.auxiliary?.closeAsync()
                    auxiliaryClose?.handle { _, _ -> Unit }?.let { await(it, cleanupDeadline) }
                    phase.host.cancel("sample_host_android_test_finished")
                    phase.host.finish(false)
                    await(phase.host.samplingDrainedFuture, cleanupDeadline)
                    consumerDrained.complete(Unit)
                    captured?.let { client.requestExit(it.lease, "sample_host_android_test_exact_cohort_exit") }
                    await(phase.host.actualReleasedFuture, cleanupDeadline)
                    captured?.let { observed ->
                        val receipt = await(observed.actualReleased, cleanupDeadline)
                        assertEquals(observed.lease.cohortId, receipt.cohortId)
                    }
                    verifyReleased(phase, client, cleanupDeadline)
                    if (primaryClose != null && primaryClose !is DetectorCohortRetired && primaryClose !is DetectorNativeCleanupFailure)
                        throw primaryClose
                }
            }
            cleanup { consumerReleased?.let { await(it, cleanupDeadline) } }
            cleanup { references?.close() }
            cleanup { fixtures.close() }
            cleanup {
                val memoryAfter = ParentDirectBufferArena.process().memoryLedger.snapshot()
                assertEquals("Managed ownership must return after fixture/reference/consumer drain", memoryBefore.managedBytes, memoryAfter.managedBytes)
                assertEquals(memoryBefore.pendingManagedBytes, memoryAfter.pendingManagedBytes)
                assertEquals(memoryBefore.sharedBytes, memoryAfter.sharedBytes)
                assertEquals(memoryBefore.activeReservations, memoryAfter.activeReservations)
                // Started direct backing remains charged and pooled; neither null nor GC is release proof.
                assertTrue(memoryAfter.directBytes >= memoryBefore.directBytes)
                assertTrue(memoryAfter.directBytes <= JointRuntimeMemoryLedger.MAXIMUM_DIRECT_BYTES)
            }
            if (cleanupErrors.isNotEmpty()) {
                val prior = failed
                if (prior != null) cleanupErrors.forEach(prior::addSuppressed)
                else throw AssertionError("Actual sample-host cleanup was not confirmed", cleanupErrors.first()).also { error ->
                    cleanupErrors.drop(1).forEach(error::addSuppressed) }
            }
        }
    }

    /** Additional real raw observations on the already-selected instance; no replacement OPEN. */
    private fun auditActualFixtureRoles(pair: JointRuntimeFinalQualifiedPair<Unit>, fixtures: JointRuntimeSampleFixtures,
        model: ModelRuntimeConfig, deadlineMs: Long, detail: MutableList<String>) {
        val primary = requireNotNull(pair.prepared.load.detector)
        val snapshot = pair.prepared.proof.snapshot
        val lease = ParentDirectBufferArena.process().acquire("joint-sample-primary-input-768", 768 * 768 * 3 * 4, intArrayOf())
        try {
            val preprocessor = YuvImagePreprocessor { size -> check(size == 768); lease.inputView() }
            var prior: Pair<FloatArray, FloatArray>? = null
            fixtures.borrow().use { borrowed -> borrowed.values.forEach { fixture ->
                assertTrue(SystemClock.elapsedRealtime() < deadlineMs)
                val input = preprocessor.preprocessBilinear(fixture.image, 768)
                val digest = jointPairTensorDigest(input.inputBuffer)
                val actual = primary.inferPreparedUnifiedForCalibration(input, 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
                    copyRawOutput = true, purpose = VisionPurpose.BENCHMARK, absoluteDeadlineMs = deadlineMs)
                prior?.let { (owned, saved) -> assertArrayEquals("Raw outputs must remain owned after the next invoke", saved, owned, 0f) }
                val raw = requireNotNull(actual.rawOutput)
                assertEquals(JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT, raw.size)
                assertTrue(raw.all(Float::isFinite))
                val runtime = requireNotNull(actual.result.timing.modelRuntime)
                assertEquals(snapshot.primaryHandle.modelInstanceId, runtime.modelInstanceId)
                assertEquals(snapshot.childPid, runtime.modelOwnerPid)
                assertEquals("cpu", runtime.activeDelegate)
                assertEquals(1, runtime.numThreads)
                assertFalse(runtime.fallbackUsed)
                assertTrue(JointRuntimeReferenceSet.roles(fixture.definition, actual.result.detections, actual.result.detections, raw, raw, model))
                val recorded = pair.qualification.strictEvidence.single { it.role == VisionRole.PRIMARY && it.fixtureId == fixture.definition.id }
                assertEquals(recorded.modelInputDigest, digest)
                if (RuntimeCalibrationFixtureRole.NEAR_THRESHOLD in fixture.definition.roles) {
                    val near = RuntimeCalibrationOutputGate.hasNearThresholdCandidate(raw, model)
                    val closest = YoloEndToEndOutputParser(model.inputSize, model::classNameForId, model::thresholdForClass,
                        model::isAllowedClass, model::thresholdForClassId,
                        pixelCoordinates = model.outputFormat == YoloOutputFormat.END_TO_END_XYXY_PIXELS).parseRaw(raw)
                        .filter { row -> model.classNameForId(row.classId)?.let(model::isAllowedClass) == true }
                        .minByOrNull { abs(it.score - model.thresholdForClassId(it.classId)) }
                    val observed = "near=${fixture.definition.id}:$near,class=${closest?.classId},score=${closest?.score},threshold=${closest?.let { model.thresholdForClassId(it.classId) }}"
                    detail += observed
                    assertTrue("Manifest NEAR_THRESHOLD must be observed in the actual raw output: $observed", near)
                }
                prior = raw to raw.copyOf()
            } }
        } finally { assertTrue("Audit input lease must drain", lease.releaseAfterHostDrain()) }
    }

    private class Phase(val name: String, val owner: DeviceCheckOwnerRef, val host: JointRuntimeSampleHost<Unit>)
    private fun verifyReleased(phase: Phase, client: RemoteVisionClient, deadlineMs: Long) {
        await(phase.host.actualReleasedFuture, deadlineMs)
        phase.host.auxiliary?.let { await(it.releasedFuture(), deadlineMs) }
        runCatching { phase.host.loadedPrimary().actualReleasedFuture }.getOrNull()?.let { await(it, deadlineMs) }
        client.retirementSnapshot(phase.owner)?.let { await(it.allocationAvailable, deadlineMs) }
        phase.host.warmEvidence.map { it.cohortId }.distinct().forEach { cohort ->
            val receipt = await(client.actualReleasedFuture(cohort), deadlineMs)
            assertEquals(cohort, receipt.cohortId)
            assertEquals(VisionReleaseProof.CHILD_DEATH, receipt.releaseProof)
            assertTrue("${phase.name} must observe an actual private-child death", receipt.childPid > 0 && receipt.childPid != Process.myPid())
            await(client.whenAvailable(cohort), deadlineMs)
        }
        assertNull("${phase.name} owner still has a native cohort", client.peekLease(phase.owner.ownerId))
    }
    private fun <T> await(stage: CompletionStage<T>, deadlineMs: Long): T {
        val remaining = deadlineMs - SystemClock.elapsedRealtime()
        check(remaining > 0) { "Actual cleanup deadline expired" }
        return stage.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)
    }
    private fun p95(values: List<Double>): Double = values.sorted()[(ceil(values.size * .95).toInt() - 1).coerceAtLeast(0)]
    companion object { private const val TAG = "JointSampleHostAndroidTest" }
}
