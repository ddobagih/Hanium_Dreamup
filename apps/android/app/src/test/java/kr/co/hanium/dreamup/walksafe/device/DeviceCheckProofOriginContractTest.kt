package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.composite.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Origin/authority glue with explicit fake I/O and strict/soak metadata; no native-device claim. */
class DeviceCheckProofOriginContractTest {
    @Test fun anIssuedRestorationProofCannotCommitANewProductionCheck() {
        val f = Fixture()
        assertTrue(JointPairReadyProof.ownsProof(f.proof))
        assertTrue(AndroidDeviceCheckAuthority.proofOriginVerifier(true)(f.proof))
        assertFalse(AndroidDeviceCheckAuthority.proofOriginVerifier(false)(f.proof))
        assertNull(f.core.freezeSuccess(f.attempt, 13_000, f.full, f.choice, f.proof))
        assertNull(requireNotNull(DeviceCheckAuthorityCodec.decode(f.io.text)).bundle)
        assertTrue("Origin rejection must occur before consuming the actual pair", f.proof.consume())
    }

    @Test fun issuedStrictAndSustainedQualificationCanCommitThroughTheProductionVerifier() {
        val f = Fixture()
        val qualified = f.qualified()
        assertFalse(f.proof.isCurrent()) // Promotion retires the unqualified source instance.
        assertTrue(AndroidDeviceCheckAuthority.proofOriginVerifier(false)(qualified))
        val frozen = requireNotNull(f.core.freezeSuccess(f.attempt, 13_000, f.full, f.choice, qualified))
        assertTrue(f.core.commitSuccess(id(), frozen) is DeviceCheckAuthorityOutcome.Committed)
        assertTrue(f.core.cachedSnapshot(f.binding) is DeviceCheckAuthorityRead.Ready)
    }

    @Test fun copyingQualifiedFieldsIntoAnotherImplementationNeverBorrowsItsIssuerOrigin() {
        val f = Fixture(); val actual = f.qualified()
        val copied = object : DeviceCheckPairProof {
            override val measurementId = actual.measurementId
            override val primary = actual.primary
            override val auxiliary = actual.auxiliary
            override fun isCurrent() = actual.isCurrent()
            override fun consume() = actual.consume()
        }
        assertFalse(AndroidDeviceCheckAuthority.proofOriginVerifier(false)(copied))
        assertFalse(AndroidDeviceCheckAuthority.proofOriginVerifier(true)(copied))
        assertNull(f.core.freezeSuccess(f.attempt, 13_000, f.full, f.choice, copied))
        assertNotNull(f.core.freezeSuccess(f.attempt, 13_000, f.full, f.choice, actual))
    }

    private class Fixture {
        val choice = JointRuntimeTestFixtures.choice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        val binding = DeviceCheckAuthorityBinding("b".repeat(64), "c".repeat(64), choice.bindingHash, "d".repeat(64))
        val io = DeviceCheckAuthorityCoreTest.MemoryIo()
        val core = DeviceCheckAuthorityCore(io, DeviceCheckProcessObserver { DeviceCheckProcessObservation.SAME_PROCESS },
            { 13_000 }, { 8 }, true, verifyProofOrigin = AndroidDeviceCheckAuthority.proofOriginVerifier(false))
        val full = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL, emptySet(), false, true,
            PostLoginMetricDepthState.SUPPORTED)
        val attempt: DeviceCheckAttemptRef
        val proof: JointPairReadyProof
        private val snapshot: VisionPairSnapshot
        init {
            assertTrue(core.admit(binding)); core.read(binding)
            attempt = value(core.beginAttempt(binding, id(), 1_000, 301_000))
            val owner = requireNotNull(core.registerOwner("origin-contract-owner", attempt, 1))
            val lease = VisionCohortLease("c".repeat(64), owner.ownerId, 1)
            val nativeLease = DeviceCheckNativeLease(lease.cohortId, lease.ownerId, lease.ownerEpoch)
            val identity = Any()
            val child = DeviceCheckNativeProcess(321, 1000, "app:vision_runtime", 100, "boot:1")
            fun runtime(thread: Long) = JSONObject().put("requestedDelegate", "cpu").put("activeDelegate", "cpu")
                .put("numThreads", 1).put("fallbackUsed", false).put("modelOwnerPid", child.pid)
                .put("modelOwnerThreadId", thread).put("loadMs", 0).put("gpuPrecisionLossRequested", false)
                .put("gpuPrecisionLossAllowed", false).put("gpuSerializationCacheRequested", true)
                .put("gpuDelegateAttached", false).toString()
            val p = VisionModelHandle(lease.cohortId, "origin-primary", 1, VisionRole.PRIMARY, "primary", runtime(41))
            val a = VisionModelHandle(lease.cohortId, "origin-auxiliary", 1, VisionRole.FASTSAM, "fastsam", runtime(42))
            snapshot = VisionPairSnapshot(lease, p, a, child.pid)
            fun opened(handle: VisionModelHandle): DeviceCheckNativeModelRef {
                val spec = DeviceCheckNativeSpec(handle.role.name, handle.modelKey, "e".repeat(64), "cpu", 1, "f".repeat(64))
                val permit = value(core.prepareNativeOpen(owner, nativeLease, identity, id(), spec, child, 20_000))
                assertTrue(core.consumeNativeOpen(permit, identity, 13_000))
                return value(core.recordModelOpened(permit, handle.modelInstanceId, VisionRuntimeCodec.runtimeHash(handle.runtimeJson)))
            }
            proof = JointPairReadyProof.issue(choice, binding, snapshot, opened(p), opened(a), emptyList()) { true }
            assertTrue(proof.bindConsumerGateOnce { true })
        }
        fun qualified(): JointPairReadyProof {
            val strict = JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS.flatMap { fixture -> VisionRole.entries.map { role ->
                val handle = if (role == VisionRole.PRIMARY) snapshot.primaryHandle else snapshot.auxiliaryHandle
                JointRuntimeStrictSampleEvidence(role, fixture, "a".repeat(64), "a".repeat(64), "a".repeat(64),
                    handle.modelInstanceId, snapshot.lease.cohortId,
                    if (role == VisionRole.PRIMARY) listOf(JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT)
                    else listOf(FastSamModelContract.DETECTION_FLOATS, FastSamModelContract.PROTOTYPE_FLOATS),
                    1_000, true, true, true, true)
            } }
            val qualification = JointRuntimeFinalQualification.issue(choice, snapshot, strict, 2_000, 12_000, 8)
            return proof.attachFinalQualification(choice, qualification).also { qualified ->
                assertTrue("Promotion must preserve the bound consumer gate", qualified.isCurrent())
            }
        }
    }
    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun <T> value(outcome: DeviceCheckAuthorityOutcome<T>) = (outcome as DeviceCheckAuthorityOutcome.Committed).value
    }
}
