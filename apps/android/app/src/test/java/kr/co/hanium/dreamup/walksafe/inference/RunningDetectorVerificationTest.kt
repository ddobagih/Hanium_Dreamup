package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-33: verification is bound to the running instance, its exact bytes, delegate and generation. */
class RunningDetectorVerificationTest {
    private val configSha = "c".repeat(64)
    private val passed = RunningDetectorLoadEvidence(
        assetIntegrityVerified = true,
        interpreterLoaded = true,
        sampleInferencePassed = true,
    )

    @Test
    fun sameBundleVersionWithDifferentModelBytesDoesNotReuseTheVerification() {
        val gate = RunningDetectorVerificationGate()
        val instance = Any()
        val verified = identity(config(modelSha = "a".repeat(64)), generation = 3L)
        val swapped = identity(config(modelSha = "b".repeat(64)), generation = 3L)
        assertEquals(verified.bundleVersion, swapped.bundleVersion)

        assertNotNull(gate.verify(instance, verified, passed))

        assertTrue(gate.allowsOutput(instance, verified))
        assertFalse(gate.allowsOutput(instance, swapped))
        assertNotEquals(verified.modelSetSha256, swapped.modelSetSha256)
    }

    @Test
    fun aNewGenerationOrInstanceRejectsTheEarlierToken() {
        val gate = RunningDetectorVerificationGate()
        val first = Any()
        gate.verify(first, identity(config(), generation = 7L), passed)

        assertTrue(gate.allowsOutput(first, 7L, configSha))
        assertFalse(gate.allowsOutput(first, 8L, configSha))
        assertFalse(gate.allowsOutput(Any(), 7L, configSha))
        assertFalse(gate.allowsOutput(first, 7L, "d".repeat(64)))
        assertFalse(gate.allowsOutput(first, 7L, null))
    }

    @Test
    fun delegateChangeIsADifferentIdentity() {
        val cpu = identity(config(), generation = 1L, delegate = "cpu")
        val gpu = identity(config(), generation = 1L, delegate = "gpu")
        val gate = RunningDetectorVerificationGate()
        val instance = Any()
        gate.verify(instance, cpu, passed)

        assertFalse(gate.allowsOutput(instance, gpu))
    }

    @Test
    fun everyLoadStageIsRequiredAndAFailedStageClosesAnEarlierToken() {
        val gate = RunningDetectorVerificationGate()
        val instance = Any()
        val id = identity(config(), generation = 2L)
        gate.verify(instance, id, passed)
        assertTrue(gate.allowsOutput(instance, 2L, configSha))

        listOf(
            passed.copy(assetIntegrityVerified = false),
            passed.copy(interpreterLoaded = false),
            passed.copy(sampleInferencePassed = false),
        ).forEach { evidence ->
            gate.verify(instance, id, passed)
            assertNull(gate.verify(instance, id, evidence))
            assertFalse(gate.allowsOutput(instance, 2L, configSha))
        }
        gate.verify(instance, id, passed)
        assertNull(gate.verify(instance, null, passed))
        assertFalse(gate.allowsOutput(instance, 2L, configSha))

        gate.verify(instance, id, passed)
        gate.invalidate()
        assertFalse(gate.allowsOutput(instance, 2L, configSha))
    }

    @Test
    fun identityFailsClosedWhenTheRunningModelsCannotBeNamedExactly() {
        assertNull(RunningDetectorIdentity.of(null, configSha, UNIFIED, "cpu", false, 1L))
        assertNull(RunningDetectorIdentity.of(config(), null, UNIFIED, "cpu", false, 1L))
        assertNull(RunningDetectorIdentity.of(config(), "not-a-hash", UNIFIED, "cpu", false, 1L))
        assertNull(RunningDetectorIdentity.of(config(), configSha, null, "cpu", false, 1L))
        assertNull(RunningDetectorIdentity.of(config(), configSha, "other", "cpu", false, 1L))
        assertNull(RunningDetectorIdentity.of(config(), configSha, UNIFIED, null, false, 1L))
        assertNull(RunningDetectorIdentity.of(config(), configSha, UNIFIED, "xnnpack", false, 1L))
        assertNull(RunningDetectorIdentity.of(config(modelSha = null), configSha, UNIFIED, "cpu", false, 1L))
        // Legacy key without the pair of pinned legacy models is not nameable either.
        assertNull(RunningDetectorIdentity.of(config(), configSha, TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY, "cpu", true, 1L))
    }

    @Test
    fun classListHashIsOrderSensitive() {
        val ordered = model(classes = listOf("person", "car"))
        val reordered = model(classes = listOf("car", "person"))

        assertNotEquals(
            RunningDetectorIdentity.classListSha256(listOf(ordered)),
            RunningDetectorIdentity.classListSha256(listOf(reordered)),
        )
    }

    private fun identity(
        config: TwoModelRuntimeConfig,
        generation: Long,
        delegate: String = "cpu",
    ): RunningDetectorIdentity = requireNotNull(
        RunningDetectorIdentity.of(config, configSha, UNIFIED, delegate, false, generation),
    )

    private fun config(modelSha: String? = "a".repeat(64)) = TwoModelRuntimeConfig(
        bundleVersion = "bundle-1",
        runtimeSourceModel = null,
        primaryModelKey = UNIFIED,
        fallbackModelKey = null,
        unifiedWalksafe = model(artifactSha256 = modelSha),
        customTactile = null,
        cocoGeneral = null,
    )

    private fun model(
        classes: List<String> = listOf("person", "car"),
        artifactSha256: String? = "a".repeat(64),
    ) = ModelRuntimeConfig(
        key = UNIFIED,
        asset = "models/unified.tflite",
        inputSize = 768,
        classes = classes,
        allowlist = setOf("person"),
        thresholds = mapOf("person" to 0.5f, "car" to 0.5f),
        artifactSha256 = artifactSha256,
    )

    private companion object {
        const val UNIFIED = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY
    }
}
