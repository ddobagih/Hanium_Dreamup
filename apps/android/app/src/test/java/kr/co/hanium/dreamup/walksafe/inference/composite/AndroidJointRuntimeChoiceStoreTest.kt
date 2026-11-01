package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AndroidJointRuntimeChoiceStoreTest {
    private val f = JointRuntimeTestFixtures
    private var processors = 8
    private var mainThread = false
    private var thermalSupported = false

    @Test fun atomicRecordRoundTripRequiresExactModelAndProcessorIdentity() {
        val prefs = Prefs()
        val store = store(prefs)
        val choice = f.choice()
        assertTrue(store.save(f.config, choice))
        assertEquals(choice, store(prefs).load(f.config))
        assertNull(store.load(f.config.copy(primaryModelKey = "another-model")))
        processors = 1
        assertNull(store.load(f.config))
        processors = 8
        assertEquals(choice, store.load(f.config))
        assertEquals(setOf("choice"), prefs.values.keys)
        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
    }

    @Test fun codecPreservesAllMeasuredEvidenceAndRejectsMalformedNoncanonicalOrCoercedJson() {
        val choice = f.choice()
        val text = requireNotNull(JointRuntimeChoiceCodec.encode(choice))
        assertEquals(choice, JointRuntimeChoiceCodec.decode(text))
        listOf(text.dropLast(1), text + "junk", "[]", "{\"schema\":1}",
            text.replaceFirst("\"schema\":2", "\"schema\":1"),
            text.replaceFirst("\"schema\":2", "\"schema\":2,\"schema\":2"),
            text.replaceFirst("{", "{\"unknown\":true,"),
            text.replaceFirst("\"threads\":2", "\"threads\":\"2\""),
            text.replaceFirst("\"threads\":2", "\"threads\":2.1"),
            text.replaceFirst("\"threads\":2", "\"threads\":0"),
            text.replaceFirst("\"durationMs\":10000", "\"durationMs\":9223372036854775808"),
            text.replaceFirst("\"fallbackUsed\":false", "\"fallbackUsed\":\"false\""),
            text.replaceFirst("{", "{/*comment*/"),
            text.replaceFirst("\"schema\"", "'schema'"),
        ).forEach { assertNull(it.take(60), JointRuntimeChoiceCodec.decode(it)) }
    }

    @Test fun stalePolicyIncompleteEvidenceAndTamperedCandidateNeverRestoreOrOverwrite() {
        val original = f.choice()
        val invalid = listOf(
            original.copy(policyVersion = "old-policy"), original.copy(bindingHash = "b".repeat(64)),
            original.copy(candidate = original.candidate.copy(primary = original.candidate.primary.copy(numThreads = 1))),
            original.copy(evidence = original.evidence.copy(fallbackUsed = true)),
            original.copy(evidence = original.evidence.copy(fixtureQuality = original.evidence.fixtureQuality.dropLast(1))),
            original.copy(evidence = original.evidence.copy(auxiliarySubmittedFrames = 32)),
        )
        for (choice in invalid) {
            val prefs = Prefs()
            val store = store(prefs)
            assertTrue(store.save(f.config, original))
            assertFalse(store.save(f.config, choice))
            assertEquals(original, store.load(f.config))
            assertEquals(1, prefs.commits)
            val corruptPrefs = Prefs()
            corruptPrefs.values["choice"] = JointRuntimeChoiceCodec.encode(choice)
            assertNull(store(corruptPrefs).load(f.config))
        }
        val missingEvidence = JSONObject(requireNotNull(JointRuntimeChoiceCodec.encode(original)))
        missingEvidence.getJSONObject("evidence").remove("actualAuxiliary")
        val prefs = Prefs()
        prefs.values["choice"] = missingEvidence.toString()
        assertNull(store(prefs).load(f.config))
    }

    @Test fun oldLiveRecordOrMissingMeasurementKindCannotBeReclassifiedAsSampleEvidence() {
        val valid = requireNotNull(JointRuntimeChoiceCodec.encode(f.choice()))
        val oldLive = JSONObject(valid).put("schema", 1).put("policy", "joint-camera-runtime-768-v1")
            .put("scope", "CAMERA_TRACKING_METRIC_DEPTH")
        oldLive.remove("measurementKind")
        val missingKind = JSONObject(valid).also { it.remove("measurementKind") }
        val inventedKind = JSONObject(valid).put("measurementKind", "LIVE_CAMERA")
        for (record in listOf(oldLive, missingKind, inventedKind)) {
            val prefs = Prefs()
            prefs.values["choice"] = record.toString(); prefs.values["unrelated"] = "preserve"
            assertNull(store(prefs).load(f.config))
            assertEquals(0, prefs.commits)
            assertEquals("preserve", prefs.values["unrelated"])
            assertNotNull(prefs.values["choice"]) // Fail closed, without an implicit destructive migration.
        }
    }

    @Test fun workerEvidenceCodecIsBoundedAndContainsOnlySampleModelMeasurements() {
        val measured = f.evidence()
        val text = requireNotNull(JointRuntimeChoiceCodec.encodeEvidence(measured))
        assertEquals(measured, JointRuntimeChoiceCodec.decodeEvidence(text))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= JointRuntimeChoiceCodec.MAX_EVIDENCE_JSON_BYTES)
        val json = JSONObject(text)
        for (unsupported in listOf("scope", "cameraWidth", "cameraFps", "displayRefreshHz", "sourceQualified",
            "uiDelayMs", "cameraIntervalMs", "primaryAgesMs", "freshAuxiliaryFrames")) {
            assertFalse(json.has(unsupported))
        }
        assertNull(JointRuntimeChoiceCodec.decodeEvidence(text + "junk"))
        assertNull(JointRuntimeChoiceCodec.decodeEvidence(" ".repeat(JointRuntimeChoiceCodec.MAX_EVIDENCE_JSON_BYTES + 1)))
        assertNull(JointRuntimeChoiceCodec.decodeEvidence(JSONObject(text).put("sourceQualified", true).toString()))
    }

    @Test fun unavailableThermalReadingIsPreservedOnlyOnAPlatformWithoutTheApi() {
        val prefs = Prefs()
        val store = store(prefs)
        val unknown = f.choice(evidence = f.evidence().copy(thermalStatus = -1))
        assertTrue(store.save(f.config, unknown))
        assertEquals(-1, store.load(f.config)!!.evidence.thermalStatus)
        thermalSupported = true
        assertNull(store.load(f.config))
        assertFalse(store.save(f.config, unknown))
        assertEquals(1, prefs.commits)
        assertTrue(store.save(f.config, f.choice()))
        assertEquals(0, store.load(f.config)!!.evidence.thermalStatus)
    }

    @Test fun failedCommitKeepsThePreviousPersistedChoiceEvenAfterStoreRecreation() {
        val prefs = Prefs()
        val store = store(prefs)
        val original = f.choice()
        val replacement = f.choice(evidence = f.evidence().copy(durationMs = 12_000))
        assertTrue(store.save(f.config, original))
        prefs.commitResult = false
        assertFalse(store.save(f.config, replacement))
        assertNotEquals(JointRuntimeChoiceCodec.encode(original), prefs.values["choice"])
        assertEquals(original, store.load(f.config))
        assertEquals(original, store(prefs).load(f.config))
        prefs.commitResult = true
        assertTrue(store.save(f.config, replacement))
        assertEquals(replacement, store(prefs).load(f.config))
    }

    @Test fun noPriorRecordCannotBecomeAcceptedFromAFailedCommit() {
        val prefs = Prefs()
        prefs.commitResult = false
        assertFalse(store(prefs).save(f.config, f.choice()))
        assertNotNull(prefs.values["choice"])
        assertNull(store(prefs).load(f.config))
    }

    @Test fun invalidationRetiresOnlyThisRecordAndFailedRemovalStaysUnusableInTheProcess() {
        val prefs = Prefs()
        prefs.values["unrelated"] = "preserve"
        val store = store(prefs)
        assertTrue(store.save(f.config, f.choice()))
        prefs.commitResult = false
        assertFalse(store.invalidate())
        assertNull(store(prefs).load(f.config))
        assertEquals("preserve", prefs.values["unrelated"])
        // An explicit new successful, fully validated measurement can replace the retired record.
        prefs.commitResult = true
        assertTrue(store.save(f.config, f.choice()))
        assertNotNull(store(prefs).load(f.config))
        assertTrue(store.invalidate())
        assertNull(store(prefs).load(f.config))
        assertEquals(mapOf("unrelated" to "preserve"), prefs.values)
    }

    @Test fun lateInvalidationFromAnOldChoicePreservesTheNewerSuccessfullySavedChoice() {
        val prefs = Prefs()
        val oldOwner = store(prefs)
        val original = f.choice()
        assertTrue(oldOwner.save(f.config, original))
        val captured = requireNotNull(oldOwner.load(f.config))
        // Binding and requested runtime can stay identical after a new, independently measured run.
        val newer = f.choice(evidence = f.evidence().copy(durationMs = 12_000))
        assertEquals(captured.bindingHash, newer.bindingHash)
        assertEquals(captured.candidate, newer.candidate)
        assertTrue(store(prefs).save(f.config, newer))

        assertFalse(oldOwner.invalidateIfMatches(captured))
        assertEquals(newer, oldOwner.load(f.config))
        assertEquals(newer, store(prefs).load(f.config))
        assertEquals(2, prefs.commits)
    }

    @Test fun conditionalInvalidationRemovesOnlyTheExactlyMatchingRecord() {
        val prefs = Prefs()
        prefs.values["unrelated"] = "preserve"
        val store = store(prefs)
        val original = f.choice()
        assertTrue(store.save(f.config, original))
        val differentEvidence = f.choice(evidence = f.evidence().copy(durationMs = 12_000))
        assertFalse(store.invalidateIfMatches(differentEvidence))
        assertEquals(original, store.load(f.config))
        assertEquals(1, prefs.commits)
        val reconstructed = requireNotNull(JointRuntimeChoiceCodec.decode(JointRuntimeChoiceCodec.encode(original)))
        assertNotSame(original, reconstructed)
        assertTrue(store(prefs).invalidateIfMatches(reconstructed))
        assertNull(store.load(f.config))
        assertEquals(mapOf("unrelated" to "preserve"), prefs.values)
        assertFalse(store.invalidateIfMatches(original))
        assertEquals(2, prefs.commits)
    }

    @Test fun identicalPayloadFromANewMeasurementCannotBeInvalidatedByThePreviousOwner() {
        val prefs = Prefs(); val oldOwner = store(prefs)
        val old = f.choice(); val newer = f.choice()
        assertEquals(old.evidence, newer.evidence); assertEquals(old.candidate, newer.candidate)
        assertNotEquals(old.measurementId, newer.measurementId)
        assertTrue(oldOwner.save(f.config, old))
        assertTrue(store(prefs).save(f.config, newer))
        assertFalse(oldOwner.invalidateIfMatches(old))
        assertEquals(newer, store(prefs).load(f.config))
        assertEquals(2, prefs.commits)
        assertTrue(store(prefs).invalidateIfMatches(newer))
        assertNull(oldOwner.load(f.config))
    }

    @Test fun retryOfSameMeasurementIsIdempotentButCannotChangeItsEvidence() {
        val prefs = Prefs(); val store = store(prefs); val original = f.choice()
        assertTrue(store.save(f.config, original))
        val reconstructed = requireNotNull(JointRuntimeChoiceCodec.decode(JointRuntimeChoiceCodec.encode(original)))
        assertEquals(original.measurementId, reconstructed.measurementId)
        assertTrue(store(prefs).save(f.config, reconstructed))
        assertEquals(1, prefs.commits)
        val changedEvidence = original.copy(evidence = original.evidence.copy(durationMs = 12_000))
        assertTrue(changedEvidence.isValid(f.binding, 8))
        assertFalse(store.save(f.config, changedEvidence))
        assertEquals(original, store.load(f.config)); assertEquals(1, prefs.commits)
        val missingId = JSONObject(requireNotNull(JointRuntimeChoiceCodec.encode(original))).also { it.remove("measurementId") }
        assertNull(JointRuntimeChoiceCodec.decode(missingId.toString()))
        val invalidId = JSONObject(requireNotNull(JointRuntimeChoiceCodec.encode(original))).put("measurementId", "invalid")
        assertNull(JointRuntimeChoiceCodec.decode(invalidId.toString()))
    }

    @Test fun mainThreadCannotCommitSaveOrInvalidation() {
        val prefs = Prefs()
        val store = store(prefs)
        val original = f.choice()
        assertTrue(store.save(f.config, original))
        mainThread = true
        assertFalse(store.save(f.config, original))
        assertFalse(store.invalidate())
        assertFalse(store.invalidateIfMatches(original))
        assertNotNull(store.load(f.config))
        assertEquals(1, prefs.commits)
    }

    @Test fun readersDuringSaveSeeThePreviousAcceptedChoice() {
        val prefs = Prefs()
        val store = store(prefs)
        val original = f.choice()
        assertTrue(store.save(f.config, original))
        prefs.duringCommit = { assertEquals(original, store(prefs).load(f.config)) }
        assertTrue(store.save(f.config, f.choice(evidence = f.evidence().copy(durationMs = 12_000))))
    }

    private fun store(prefs: Prefs) = AndroidJointRuntimeChoiceStore(prefs.shared,
        { config -> f.binding.takeIf { config == f.config } }, { processors }, { mainThread }, { thermalSupported })

    /** commit(false) still changes process memory, matching SharedPreferences' difficult case. */
    private class Prefs {
        val values = mutableMapOf<String, String?>()
        var commits = 0
        var applies = 0
        var commitResult = true
        var duringCommit: (() -> Unit)? = null
        val shared: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args -> when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "JointRuntimeTestPreferences"
                else -> error("Unexpected preference call: ${method.name}")
            } } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val update = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args -> when (method.name) {
                    "putString" -> { update[args!![0] as String] = args[1] as String?; proxy }
                    "remove" -> { update[args!![0] as String] = null; proxy }
                    "commit" -> {
                        commits++
                        update.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        duringCommit?.invoke()
                        commitResult
                    }
                    "apply" -> { applies++; error("Asynchronous apply must not be used") }
                    else -> error("Unexpected editor call: ${method.name}")
                } } as SharedPreferences.Editor
        }
    }
}
