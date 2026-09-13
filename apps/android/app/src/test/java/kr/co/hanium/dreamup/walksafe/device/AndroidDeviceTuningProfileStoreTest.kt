package kr.co.hanium.dreamup.walksafe.device

import android.content.SharedPreferences
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AndroidDeviceTuningProfileStoreTest {
    private var now = DeviceTuningClockReading(1_800_000_000_000, 80_000, 3)
    private var processors = 12
    private var mainThread = false
    private val binding = "a".repeat(64)
    private val nextBinding = "b".repeat(64)
    private val interval = DeviceTuningRetryPolicy.RETRY_INTERVAL_MS

    @Test fun singleAtomicBlobRoundTripPreservesProfileAcrossPartialAttempt() {
        val prefs = Prefs()
        val store = store(prefs)
        val confirmed = profile()
        assertTrue(store.save(confirmed))
        assertTrue(store.markAttemptStarted(binding))
        assertTrue(store.markAttemptFinished(binding, DeviceTuningRecheckReason.INCOMPLETE_COMPARISON, queue()))
        assertEquals(confirmed, store(prefs).load(binding))
        assertEquals(queue(), store(prefs).loadAttempt(binding)?.cursor)
        assertEquals(setOf(AndroidDeviceTuningProfileStore.RECORD_KEY), prefs.values.keys)
        assertEquals(3, prefs.commits)
        assertEquals(0, prefs.applies)
    }

    @Test fun partialAndInconclusiveDecisionsCannotProduceAProfile() {
        listOf(RuntimeSelectionStatus.PARTIAL, RuntimeSelectionStatus.INCONCLUSIVE).forEach {
            assertNull(RuntimeTuningProfile.fromConfirmed(RuntimeSelectionDecision(
                it, RuntimeCandidate(RuntimeBackend.GPU, 5), "pending", null,
            ), now.epochMs))
        }
    }

    @Test fun malformedOrUnconfirmedEvidenceNeverOverwritesPreviousPreference() {
        val prefs = Prefs()
        val store = store(prefs)
        val valid = profile()
        assertTrue(store.save(valid))
        val invalid = listOf(
            valid.copy(bindingHash = "invalid"), valid.copy(measuredAtEpochMs = 0),
            valid.copy(candidate = RuntimeCandidate(RuntimeBackend.GPU, 13)),
            valid.copy(candidate = RuntimeCandidate(RuntimeBackend.CPU, 5)),
            valid.copy(policyVersion = "old-policy"), valid.copy(fixtureHash = "x".repeat(64)),
            valid.copy(measurement = valid.measurement.copy(comparedPairCount = 7)),
            valid.copy(measurement = valid.measurement.copy(confirmedPairCount = 3)),
            valid.copy(measurement = valid.measurement.copy(liveValidCompletions = 0)),
            valid.copy(measurement = valid.measurement.copy(selectedMedianMs = Double.NaN)),
            valid.copy(measurement = valid.measurement.copy(selectedMedianMs = Double.POSITIVE_INFINITY)),
            valid.copy(measurement = valid.measurement.copy(uncertaintyMs = -1.0)),
        )
        invalid.forEach { assertFalse(store.save(it)); assertEquals(valid, store.load(binding)) }
        assertEquals(1, prefs.commits)
    }

    @Test fun canonicalSchemaRejectsTruncationCoercionExtraKeysDuplicateKeysAndOverflow() {
        val valid = DeviceTuningRecordCodec.encode(DeviceTuningRecord(profile()))!!
        val malformed = listOf(
            valid.dropLast(1), valid + "junk", "[]", "{\"schema\":1}",
            valid.replaceFirst("\"schema\":1", "\"schema\":2"),
            valid.replaceFirst("\"schema\":1", "\"schema\":1,\"schema\":1"),
            valid.replaceFirst("\"schema\":1", "\"schema\":1,\"unknown\":true"),
            valid.replaceFirst("\"numThreads\":5", "\"numThreads\":\"5\""),
            valid.replaceFirst("\"numThreads\":5", "\"numThreads\":5.1"),
            valid.replaceFirst("\"numThreads\":5", "\"numThreads\":0"),
            valid.replaceFirst("\"numThreads\":5", "\"numThreads\":2147483648"),
            valid.replaceFirst("\"measuredAtEpochMs\":${now.epochMs}", "\"measuredAtEpochMs\":9223372036854775808"),
            valid.replaceFirst("\"selectedMedianMs\":100.0", "\"selectedMedianMs\":1e999"),
            valid.replaceFirst("\"selectedMedianMs\":100.0", "\"selectedMedianMs\":\"100\""),
            valid.replaceFirst("{", "{/*comment*/"), valid.replaceFirst("\"schema\"", "'schema'"),
        )
        malformed.forEach { assertNull(it.take(70), DeviceTuningRecordCodec.decode(it)) }
        assertEquals(profile(), DeviceTuningRecordCodec.decode(valid)?.profile)
    }

    @Test fun failedCommitPreservesOldProfileEvenAcrossStoreRecreationAndSuccessfulRetry() {
        val prefs = Prefs()
        val store = store(prefs)
        val original = profile()
        val replacement = original.copy(measuredAtEpochMs = now.epochMs + 1)
        assertTrue(store.save(original))
        prefs.commitResult = false
        assertFalse(store.save(replacement))
        assertEquals(original, store.load(binding))
        assertEquals(original, store(prefs).load(binding))
        prefs.commitResult = true
        assertTrue(store.save(replacement))
        assertEquals(replacement, store(prefs).load(binding))
    }

    @Test fun readsDuringCommitSeePreviousAcceptedRecordWithoutWaitingForDisk() {
        val prefs = Prefs()
        val store = store(prefs)
        assertTrue(store.save(profile()))
        prefs.duringCommit = { assertEquals(profile(), store(prefs).load(binding)) }
        assertTrue(store.save(profile().copy(measuredAtEpochMs = now.epochMs + 1)))
    }

    @Test fun workerContractRejectsMainThreadBeforeAnyDiskEdit() {
        val prefs = Prefs()
        mainThread = true
        val store = store(prefs)
        assertFalse(store.save(profile()))
        assertFalse(store.markAttemptStarted(binding))
        assertEquals(0, prefs.commits)
    }

    @Test fun exactBindingAndCurrentProcessorRangeAreRequiredWithoutDeletingStoredProfile() {
        val prefs = Prefs()
        val store = store(prefs)
        assertTrue(store.save(profile()))
        assertNull(store.load(nextBinding))
        assertTrue(store.needsRecheck(nextBinding))
        processors = 3
        assertNull(store.load(binding))
        processors = 12
        assertEquals(profile(), store.load(binding))
        assertEquals(1, prefs.commits)
    }

    @Test fun healthyConfirmedProfileDoesNotBecomeDueJustBecauseADayPassed() {
        val store = store(Prefs())
        assertTrue(store.markAttemptStarted(binding))
        assertTrue(store.save(profile()))
        assertTrue(store.markAttemptFinished(binding, null))
        advance(interval * 8)
        assertFalse(store.needsRecheck(binding))
        assertTrue(store.needsRecheck(binding, manual = true))
    }

    @Test fun cancellationAndPartialConsume24HoursFromStartNotFinish() {
        val store = store(Prefs())
        assertTrue(store.markAttemptStarted(binding))
        advance(200_000)
        assertTrue(store.markAttemptFinished(binding, DeviceTuningRecheckReason.ENVIRONMENT_INTERRUPTED))
        advance(interval - 200_001)
        assertFalse(store.needsRecheck(binding))
        advance(1)
        assertTrue(store.needsRecheck(binding))
    }

    @Test fun bindingChangeManualRequestAndExecutionRecoveryBypassRetryCooldown() {
        val store = store(Prefs())
        assertTrue(store.markAttemptStarted(binding))
        assertFalse(store.needsRecheck(binding))
        assertTrue(store.needsRecheck(nextBinding))
        assertTrue(store.needsRecheck(binding, manual = true))
        assertTrue(store.markAttemptFinished(binding, DeviceTuningRecheckReason.EXECUTION_RECOVERY))
        assertTrue(store.needsRecheck(binding))
    }

    @Test fun sustainedDegradationSchedulesFromLastAttemptWithoutErasingProfile() {
        val store = store(Prefs())
        assertTrue(store.markAttemptStarted(binding))
        assertTrue(store.save(profile()))
        assertTrue(store.markAttemptFinished(binding, null))
        assertFalse(store.needsRecheck(binding))
        assertTrue(store.markAttemptFinished(binding, DeviceTuningRecheckReason.PERSISTENT_DEGRADATION))
        assertFalse(store.needsRecheck(binding))
        advance(interval)
        assertTrue(store.needsRecheck(binding))
        assertEquals(profile().copy(measuredAtEpochMs = now.epochMs - interval), store.load(binding))
    }

    @Test fun wallClockJumpCannotBypassOrPermanentlyBlockSameBootCooldown() {
        val store = store(Prefs())
        assertTrue(store.markAttemptStarted(binding))
        now = now.copy(epochMs = now.epochMs + interval * 20)
        assertFalse(store.needsRecheck(binding))
        now = now.copy(epochMs = now.epochMs - interval * 40, elapsedMs = now.elapsedMs + interval)
        assertTrue(store.needsRecheck(binding))
    }

    @Test fun ordinaryRebootDoesNotResetCooldownAndWallRollbackHasBoundedRecovery() {
        val store = store(Prefs())
        assertTrue(store.markAttemptStarted(binding))
        now = now.copy(bootCount = 4, elapsedMs = 1_000, epochMs = now.epochMs + 10_000)
        assertFalse(store.needsRecheck(binding))
        now = now.copy(epochMs = now.epochMs + interval)
        assertTrue(store.needsRecheck(binding))
        now = now.copy(epochMs = now.epochMs - interval * 4)
        assertFalse(store.needsRecheck(binding))
        now = now.copy(elapsedMs = interval)
        assertTrue(store.needsRecheck(binding))
    }

    @Test fun pendingRequiresAnExplicitReasonAndQueueFieldsStayInProcessorRange() {
        val store = store(Prefs())
        assertTrue(store.markAttemptStarted(binding))
        assertFalse(store.markAttemptFinished(binding, null, queue()))
        listOf(queue().copy(cpuCursor = 0), queue().copy(gpuCursor = 13),
            queue().copy(cpuAnchor = 13), queue().copy(outcomes = queue().outcomes + queue().outcomes),
        ).forEach { assertFalse(store.markAttemptFinished(binding, DeviceTuningRecheckReason.INCOMPLETE_COMPARISON, it)) }
        assertTrue(store.markAttemptFinished(binding, null))
        assertNull(store.loadAttempt(binding)?.cursor)
    }

    @Test fun configurationDigestCoversInferenceInputClassesThresholdsPrecisionAndEnvironment() {
        val config = config()
        val original = digest(config)!!
        val m = config.unifiedWalksafe!!
        val changed = listOf(
            config.copy(bundleVersion = "bundle-2"),
            config.copy(unifiedWalksafe = m.copy(artifactSha256 = nextBinding)),
            config.copy(unifiedWalksafe = m.copy(classes = m.classes.reversed())),
            config.copy(unifiedWalksafe = m.copy(allowlist = setOf("person"))),
            config.copy(unifiedWalksafe = m.copy(thresholds = mapOf("default" to .6f))),
            config.copy(unifiedWalksafe = m.copy(runtime = m.runtime.copy(numThreads = 7))),
            config.copy(unifiedWalksafe = m.copy(runtime = m.runtime.copy(delegate = "cpu"))),
            config.copy(unifiedWalksafe = m.copy(runtime = m.runtime.copy(fallbackToCpu = true))),
        )
        changed.forEach { assertNotEquals(original, digest(it)) }
        assertNotEquals(original, digest(config, scope = RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH))
        assertNotEquals(original, digest(config, os = "os-2"))
        assertNotEquals(original, digest(config, abis = listOf("x86_64")))
        assertNotEquals(original, digest(config, runtime = "runtime-2"))
        assertNotEquals(original, digest(config, input = "input-2"))
        assertNotEquals(original, digest(config, gpu = "actual-driver"))
        assertNotEquals(original, digest(config, policy = "policy-2"))
        assertNull(digest(config.copy(unifiedWalksafe = m.copy(inputSize = 640))))
        assertNull(digest(config.copy(unifiedWalksafe = m.copy(runtime = m.runtime.copy(gpuPrecisionLossAllowed = true)))))
        assertNull(digest(config.copy(unifiedWalksafe = m.copy(enabled = false))))
        assertNull(digest(config.copy(unifiedWalksafe = m.copy(artifactSha256 = null))))
    }

    @Test fun changedFixtureVersionOrManifestInvalidatesTheBindingAndOldProfile() {
        val current = digest(config())!!
        val oldBindings = listOf(
            digest(config(), fixtureVersion = "previous-fixture-version")!!,
            digest(config(), fixtureHash = "d".repeat(64))!!,
        )
        oldBindings.forEach { previous ->
            assertNotEquals(previous, current)
            val original = profile()
            val oldProfile = original.copy(bindingHash = previous,
                measurement = original.measurement.copy(bindingHash = previous))
            val store = store(Prefs())
            assertTrue(store.save(oldProfile))
            assertNull(store.load(current))
            assertTrue(store.needsRecheck(current))
        }
    }

    @Test fun currentBindingCannotRestoreEvidenceFromAnOldFixture() {
        val current = profile()
        val old = current.copy(fixtureHash = "d".repeat(64),
            measurement = current.measurement.copy(fixtureHash = "d".repeat(64)))
        val prefs = Prefs()
        prefs.values[AndroidDeviceTuningProfileStore.RECORD_KEY] =
            DeviceTuningRecordCodec.encode(DeviceTuningRecord(old))!!
        val store = store(prefs)
        assertNull(store.load(binding))
        assertFalse(store.save(old))
        assertTrue(store.needsRecheck(binding))
    }

    private fun store(prefs: Prefs) = AndroidDeviceTuningProfileStore(prefs.shared, { now }, { processors }, { mainThread })
    private fun advance(ms: Long) { now = now.copy(epochMs = now.epochMs + ms, elapsedMs = now.elapsedMs + ms) }
    private fun profile(): RuntimeTuningProfile {
        val m = RuntimeConfirmedMeasurement(binding, RuntimeFeatureScope.CAMERA_TRACKING, RuntimeBackend.GPU,
            8, 4, 150.0, 100.0, 3.0, RuntimeCalibrationFixtures.VERSION,
            RuntimeCalibrationFixtures.MANIFEST_SHA256, 10, 20_000)
        return RuntimeTuningProfile(binding, RuntimeCandidate(RuntimeBackend.GPU, 5), m.featureScope,
            m.fixtureVersion, m.fixtureHash, m.policyVersion, now.epochMs, m)
    }
    private fun queue() = RuntimeCandidateQueueSnapshot(12, RuntimeCandidate(RuntimeBackend.CPU, 4), true,
        5, RuntimeBackend.GPU, 4, 5, 1, 6, true, true, RuntimeBackend.GPU,
        listOf(RuntimeCandidateOutcome(RuntimeCandidate(RuntimeBackend.GPU, 6), RuntimeSelectionStatus.PARTIAL)))
    private fun config() = TwoModelRuntimeConfig("bundle-1", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
        null, ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "models/test.tflite", 768,
            listOf("person", "bicycle"), setOf("person", "bicycle"), mapOf("default" to .5f),
            artifactSha256 = binding, runtime = ModelRuntimeOptions("gpu", 4, false)), null, null)
    private fun digest(config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope = RuntimeFeatureScope.CAMERA_TRACKING,
        os: String = "os-1", abis: List<String> = listOf("arm64-v8a"), gpu: String? = null,
        runtime: String = "runtime-1", input: String = "input-1", policy: String = "policy-1",
        fixtureVersion: String = RuntimeCalibrationFixtures.VERSION,
        fixtureHash: String = RuntimeCalibrationFixtures.MANIFEST_SHA256,
    ) = DeviceTuningBinding.create(config, scope, abis, os, gpu, runtime, input, policy, fixtureVersion, fixtureHash)

    private class Prefs {
        val values = mutableMapOf<String, String?>()
        var commits = 0
        var applies = 0
        var commitResult = true
        var duringCommit: (() -> Unit)? = null
        val shared: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args -> when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> editor()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "TuningTestPreferences"
                else -> error("Unexpected preference call: ${method.name}")
            } } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val update = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args -> when (method.name) {
                    "putString" -> { update[args!![0] as String] = args[1] as String?; proxy }
                    "commit" -> { commits++; values.putAll(update); duringCommit?.invoke(); commitResult }
                    "apply" -> { applies++; error("Asynchronous apply must not be used") }
                    else -> error("Unexpected editor call: ${method.name}")
                } } as SharedPreferences.Editor
        }
    }
}
