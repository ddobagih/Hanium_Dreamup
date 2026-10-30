package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.SharedPreferences
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AndroidJointRuntimeChoiceStoreTest {
    private val f = JointRuntimeTestFixtures
    private var processors = 8
    private var mainThread = false

    @Test fun atomicRecordRoundTripRequiresExactModelScopeCameraAndProcessorIdentity() {
        val prefs = Prefs()
        val store = store(prefs)
        val choice = f.choice()
        assertTrue(store.save(f.config, choice))
        assertEquals(choice, store(prefs).load(f.config, f.scope, f.mode))
        assertEquals(choice, store(prefs).load(f.config)) // Initial model preparation only.
        assertNull(store.load(f.config.copy(primaryModelKey = "another-model"), f.scope, f.mode))
        assertNull(store.load(f.config, RuntimeFeatureScope.CAMERA_TRACKING, f.mode))
        assertNull(store.load(f.config, f.scope, f.mode.copy(width = 1280)))
        assertNull(store.load(f.config, f.scope, f.mode.copy(fps = 60)))
        assertNull(store.load(f.config, f.scope, f.mode.copy(displayRefreshHz = 59.94)))
        processors = 1
        assertNull(store.load(f.config, f.scope, f.mode))
        processors = 8
        assertEquals(choice, store.load(f.config, f.scope, f.mode))
        assertEquals(setOf("choice"), prefs.values.keys)
        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
    }

    @Test fun codecPreservesAllMeasuredEvidenceAndRejectsMalformedNoncanonicalOrCoercedJson() {
        val choice = f.choice()
        val text = requireNotNull(JointRuntimeChoiceCodec.encode(choice))
        assertEquals(choice, JointRuntimeChoiceCodec.decode(text))
        listOf(text.dropLast(1), text + "junk", "[]", "{\"schema\":1}",
            text.replaceFirst("\"schema\":1", "\"schema\":2"),
            text.replaceFirst("\"schema\":1", "\"schema\":1,\"schema\":1"),
            text.replaceFirst("{", "{\"unknown\":true,"),
            text.replaceFirst("\"threads\":2", "\"threads\":\"2\""),
            text.replaceFirst("\"threads\":2", "\"threads\":2.1"),
            text.replaceFirst("\"threads\":2", "\"threads\":0"),
            text.replaceFirst("\"durationMs\":10000", "\"durationMs\":9223372036854775808"),
            text.replaceFirst("\"sourceQualified\":true", "\"sourceQualified\":\"true\""),
            text.replaceFirst("{", "{/*comment*/"),
            text.replaceFirst("\"schema\"", "'schema'"),
        ).forEach { assertNull(it.take(60), JointRuntimeChoiceCodec.decode(it)) }
    }

    @Test fun stalePolicyIncompleteEvidenceAndTamperedCandidateNeverRestoreOrOverwrite() {
        val original = f.choice()
        val invalid = listOf(
            original.copy(policyVersion = "old-policy"), original.copy(bindingHash = "b".repeat(64)),
            original.copy(candidate = original.candidate.copy(primary = original.candidate.primary.copy(numThreads = 1))),
            original.copy(evidence = original.evidence.copy(fixtureEquivalent = false)),
            original.copy(evidence = original.evidence.copy(sourceQualified = false)),
            original.copy(evidence = original.evidence.copy(freshAuxiliaryFrames = 24)),
        )
        for (choice in invalid) {
            val prefs = Prefs()
            val store = store(prefs)
            assertTrue(store.save(f.config, original))
            assertFalse(store.save(f.config, choice))
            assertEquals(original, store.load(f.config, f.scope, f.mode))
            assertEquals(1, prefs.commits)
            val corruptPrefs = Prefs()
            corruptPrefs.values["choice"] = JointRuntimeChoiceCodec.encode(choice)
            assertNull(store(corruptPrefs).load(f.config, f.scope, f.mode))
        }
        val missingEvidence = JSONObject(requireNotNull(JointRuntimeChoiceCodec.encode(original)))
        missingEvidence.getJSONObject("evidence").remove("actualAuxiliary")
        val prefs = Prefs()
        prefs.values["choice"] = missingEvidence.toString()
        assertNull(store(prefs).load(f.config, f.scope, f.mode))
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
        assertEquals(original, store.load(f.config, f.scope, f.mode))
        assertEquals(original, store(prefs).load(f.config, f.scope, f.mode))
        prefs.commitResult = true
        assertTrue(store.save(f.config, replacement))
        assertEquals(replacement, store(prefs).load(f.config, f.scope, f.mode))
    }

    @Test fun noPriorRecordCannotBecomeAcceptedFromAFailedCommit() {
        val prefs = Prefs()
        prefs.commitResult = false
        assertFalse(store(prefs).save(f.config, f.choice()))
        assertNotNull(prefs.values["choice"])
        assertNull(store(prefs).load(f.config, f.scope, f.mode))
    }

    @Test fun invalidationRetiresOnlyThisRecordAndFailedRemovalStaysUnusableInTheProcess() {
        val prefs = Prefs()
        prefs.values["unrelated"] = "preserve"
        val store = store(prefs)
        assertTrue(store.save(f.config, f.choice()))
        prefs.commitResult = false
        assertFalse(store.invalidate())
        assertNull(store(prefs).load(f.config, f.scope, f.mode))
        assertEquals("preserve", prefs.values["unrelated"])
        // An explicit new successful, fully validated measurement can replace the retired record.
        prefs.commitResult = true
        assertTrue(store.save(f.config, f.choice()))
        assertNotNull(store(prefs).load(f.config, f.scope, f.mode))
        assertTrue(store.invalidate())
        assertNull(store(prefs).load(f.config, f.scope, f.mode))
        assertEquals(mapOf("unrelated" to "preserve"), prefs.values)
    }

    @Test fun lateInvalidationFromAnOldChoicePreservesTheNewerSuccessfullySavedChoice() {
        val prefs = Prefs()
        val oldOwner = store(prefs)
        val original = f.choice()
        assertTrue(oldOwner.save(f.config, original))
        val captured = requireNotNull(oldOwner.load(f.config, f.scope, f.mode))
        // Binding and requested runtime can stay identical after a new, independently measured run.
        val newer = f.choice(evidence = f.evidence().copy(durationMs = 12_000))
        assertEquals(captured.bindingHash, newer.bindingHash)
        assertEquals(captured.candidate, newer.candidate)
        assertTrue(store(prefs).save(f.config, newer))

        assertFalse(oldOwner.invalidateIfMatches(captured))
        assertEquals(newer, oldOwner.load(f.config, f.scope, f.mode))
        assertEquals(newer, store(prefs).load(f.config, f.scope, f.mode))
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
        assertEquals(original, store.load(f.config, f.scope, f.mode))
        assertEquals(1, prefs.commits)
        val reconstructed = requireNotNull(JointRuntimeChoiceCodec.decode(JointRuntimeChoiceCodec.encode(original)))
        assertNotSame(original, reconstructed)
        assertTrue(store(prefs).invalidateIfMatches(reconstructed))
        assertNull(store.load(f.config, f.scope, f.mode))
        assertEquals(mapOf("unrelated" to "preserve"), prefs.values)
        assertFalse(store.invalidateIfMatches(original))
        assertEquals(2, prefs.commits)
    }

    @Test fun mainThreadCannotCommitSaveOrInvalidation() {
        val prefs = Prefs()
        val store = store(prefs)
        assertTrue(store.save(f.config, f.choice()))
        mainThread = true
        assertFalse(store.save(f.config, f.choice()))
        assertFalse(store.invalidate())
        assertFalse(store.invalidateIfMatches(f.choice()))
        assertNotNull(store.load(f.config, f.scope, f.mode))
        assertEquals(1, prefs.commits)
    }

    @Test fun readersDuringSaveSeeThePreviousAcceptedChoice() {
        val prefs = Prefs()
        val store = store(prefs)
        val original = f.choice()
        assertTrue(store.save(f.config, original))
        prefs.duringCommit = { assertEquals(original, store(prefs).load(f.config, f.scope, f.mode)) }
        assertTrue(store.save(f.config, f.choice(evidence = f.evidence().copy(durationMs = 12_000))))
    }

    private fun store(prefs: Prefs) = AndroidJointRuntimeChoiceStore(prefs.shared,
        { config, scope, mode ->
            f.binding.takeIf { config == f.config && scope == f.scope && mode == f.mode }
        }, { processors }, { mainThread })

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
