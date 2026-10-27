package kr.co.hanium.dreamup.walksafe.report

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.security.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActorReportAttemptRepositoryTest {
    @Test fun attemptKeysPreserveActorClassExactCoordinatesAndPurpose() {
        val f = ActorReportPreferenceFixture()
        val key = f.repository.stateKey("actor-a", "damaged_tactile_block", 37.0, 127.0, ReportTransferPurpose.AUTOMATIC)
        assertEquals(sha256Hex("actor-a|damaged_tactile_block|37.0|127.0|AUTOMATIC".toByteArray()), key)
        assertNotEquals(key, f.repository.stateKey("actor-b", "damaged_tactile_block", 37.0, 127.0, ReportTransferPurpose.AUTOMATIC))
        assertNotEquals(key, f.repository.stateKey("actor-a", "damaged_tactile_block", 37.0, 127.00001, ReportTransferPurpose.AUTOMATIC))
        assertNotEquals(key, f.repository.stateKey("actor-a", "damaged_tactile_block", 37.0, 127.0, ReportTransferPurpose.EXPLICIT))
    }

    @Test fun transientBackoffAndFailureCountSurviveRepositoryRecreation() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        val key = f.key()
        assertEquals(2_000L, f.repository.persistTransientFailure(key, f.now))
        assertEquals("reportCandidate=backoff_persisted remainingMs=2000", f.repository.automaticBlockStatus(key, f.now))
        val restarted = f.newRepository()
        assertTrue(restarted.ensureActor("actor-a"))
        assertEquals(4_000L, restarted.persistTransientFailure(key, f.now))
        val persisted = JSONObject(f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null)!!)
        assertEquals("walksafe.report-attempt-state.v2", persisted.getString("schema"))
        assertEquals(sha256Hex("actor-a".toByteArray()), persisted.getString("actor_hash"))
        assertEquals(2, persisted.getJSONArray("entries").getJSONObject(0).getInt("failures"))
        assertNull(restarted.automaticBlockStatus(key, f.now + 4_000L))
    }

    @Test fun failureCountCapAndExistingHttpBackoffPolicyRemainBounded() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        val key = f.key()
        repeat(40) { f.repository.persistTransientFailure(key, f.now) }
        assertEquals(32, JSONObject(f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null)!!)
            .getJSONArray("entries").getJSONObject(0).getInt("failures"))
        assertEquals(30_000L, f.repository.persistTransientFailure(key, f.now, 401))
        assertEquals(90_000L, f.repository.persistTransientFailure(key, f.now, 429, 90_000L))
    }

    @Test fun terminalStatePreservesPriorFailuresAndExpiresAtTheOriginalDayBoundary() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        val key = f.key()
        f.repository.persistTransientFailure(key, f.now)
        assertTrue(f.repository.persistTerminalState(key, 422, f.now))
        assertEquals("reportCandidate=terminal_backoff status=422 remainingMs=86400000", f.repository.automaticBlockStatus(key, f.now))
        val entry = JSONObject(f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null)!!)
            .getJSONArray("entries").getJSONObject(0)
        assertEquals(1, entry.getInt("failures"))
        assertEquals(0L, entry.getLong("retry_not_before_ms"))
        assertNull(f.repository.automaticBlockStatus(key, f.now + 86_400_000L))
    }

    @Test fun actorSwitchRemovesThePreviousPayloadBeforePublishingTheNewActor() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        f.repository.persistTransientFailure(f.key(), f.now)
        assertTrue(f.repository.ensureActor("actor-b"))
        assertNull(f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null))
        assertNull(f.repository.automaticBlockStatus(f.key(), f.now))
        assertTrue(f.repository.persistTerminalState(f.key("actor-b"), null, f.now))
        val restored = f.newRepository()
        assertTrue(restored.ensureActor("actor-b"))
        assertTrue(restored.automaticBlockStatus(f.key("actor-b"), f.now)!!.contains("status=protocol"))
    }

    @Test fun commitFailureBlocksFurtherUseIncludingActorSwitch() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        f.repository.persistTransientFailure(f.key(), f.now)
        f.storage.commitsSucceed = false
        assertFalse(f.repository.ensureActor("actor-b"))
        assertEquals("reportCandidate=blocked:attempt_state_storage", f.repository.automaticBlockStatus(f.key(), f.now))
        assertFalse(f.repository.ensureActor("actor-a"))
        assertTrue(f.preferences.isBlocked())
    }

    @Test fun actorSwitchStillNeedsFreshClearAcknowledgementWhenTheOldPayloadIsAbsent() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        assertNull(f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null))
        f.storage.commitsSucceed = false
        assertFalse(f.repository.ensureActor("actor-b"))
        assertTrue(f.preferences.isBlocked())
        assertEquals("reportCandidate=blocked:attempt_state_storage", f.repository.automaticBlockStatus(f.key(), f.now))
    }

    @Test fun malformedOrWrongActorPayloadFailsClosedInsteadOfBecomingMissing() {
        for (raw in listOf("{bad-json", payload("actor-b", JSONArray()), payload("actor-a", JSONArray().put(entry("invalid-key", 1))),
            payload("actor-a", JSONArray().put(entry("a".repeat(64), 33))))) {
            val f = ActorReportPreferenceFixture()
            assertTrue(f.preferences.edit().putString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, raw).commit())
            assertFalse(f.repository.ensureActor("actor-a"))
            assertTrue(f.preferences.isBlocked())
            assertEquals("reportCandidate=blocked:attempt_state_storage", f.repository.automaticBlockStatus(f.key(), f.now))
        }
    }

    @Test fun entryCapacityRejectsANewKeyWithoutEvictingExistingState() {
        val f = ActorReportPreferenceFixture()
        val entries = JSONArray()
        repeat(64) { entries.put(entry(sha256Hex("entry-$it".toByteArray()), 1)) }
        assertTrue(f.preferences.edit().putString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, payload("actor-a", entries)).commit())
        assertTrue(f.repository.ensureActor("actor-a"))
        assertFalse(f.repository.persistTerminalState(f.key(), 422, f.now))
        assertEquals("reportCandidate=blocked:attempt_state_storage", f.repository.automaticBlockStatus(f.key(), f.now))
        assertEquals(64, JSONObject(f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null)!!).getJSONArray("entries").length())
    }

    @Test fun actorBlockIsScopedAndOnlyCommittedClearRemovesIt() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        f.repository.blockStorageForActor("actor-b")
        assertNull(f.repository.automaticBlockStatus(f.key(), f.now))
        f.repository.blockStorageForActor("actor-a")
        assertTrue(f.repository.ensureActor("actor-a"))
        assertEquals("reportCandidate=blocked:attempt_state_storage", f.repository.automaticBlockStatus(f.key(), f.now))
        assertTrue(f.repository.clearPersistedState(f.key()))
        assertNull(f.repository.automaticBlockStatus(f.key(), f.now))
        f.repository.blockStorageForActor("actor-a")
        f.storage.commitsSucceed = false
        assertFalse(f.repository.clearPersistedState(f.key()))
        assertEquals("reportCandidate=blocked:attempt_state_storage", f.repository.automaticBlockStatus(f.key(), f.now))
    }

    @Test fun accountDeletionMemoryResetCannotReopenBlockedSensitivePreferences() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        f.repository.persistTransientFailure(f.key(), f.now)
        val raw = f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null)
        f.repository.blockStorageForActor("actor-a")
        f.repository.clearForAccountDeletion()
        assertEquals(raw, f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null))
        assertNull(f.repository.automaticBlockStatus(f.key(), f.now))
        f.preferences.failClosed()
        f.repository.clearForAccountDeletion()
        assertFalse(f.repository.ensureActor("actor-a"))
        assertTrue(f.preferences.isBlocked())
    }

    @Test fun cooldownSuccessIsCommittedBeforeAnyInMemorySuccessIsPublished() {
        val f = ActorReportPreferenceFixture()
        val attempts = f.attemptStore()
        val scope = f.scope()
        val cooldown = AndroidReportSuccessfulCooldown(scope, f.now, f.now, 600_000L, 1)
        assertTrue(f.repository.persistCooldownForSuccess(attempts, cooldown))
        assertTrue(attempts.successfulCooldowns(f.now).isEmpty())
        val restartedAttempts = f.attemptStore()
        f.newRepository().restoreCooldowns(restartedAttempts)
        val blocked = restartedAttempts.acquire(scope, f.now, false) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, blocked.reason)
        assertEquals(600_000L, blocked.remainingMs)
        assertTrue(restartedAttempts.acquire(f.scope("actor-b"), f.now, false) is AndroidReportAttemptResult.Allowed)
    }

    @Test fun cooldownPersistenceReplacesTheExactScopeAndKeepsOtherScopes() {
        val f = ActorReportPreferenceFixture()
        val attempts = f.attemptStore()
        val original = listOf(AndroidReportSuccessfulCooldown(f.scope(), f.now - 1_000L),
            AndroidReportSuccessfulCooldown(f.scope("actor-b"), f.now - 2_000L))
        attempts.restoreSuccessfulCooldowns(original, f.now)
        assertTrue(f.repository.persistCooldownForSuccess(attempts, AndroidReportSuccessfulCooldown(f.scope(), f.now)))
        val payload = JSONArray(f.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_COOLDOWNS_KEY, null)!!)
        assertEquals(2, payload.length())
        assertEquals("actor-a", payload.getJSONObject(0).getString("actor_id"))
        assertEquals(f.now, payload.getJSONObject(0).getLong("last_uploaded_at_ms"))
        assertEquals("actor-b", payload.getJSONObject(1).getString("actor_id"))
    }

    @Test fun malformedCooldownBlocksTheSensitiveDomainAndAttemptAdmission() {
        val f = ActorReportPreferenceFixture()
        assertTrue(f.repository.ensureActor("actor-a"))
        assertTrue(f.preferences.edit().putString(ActorReportAttemptRepository.PREF_REPORT_COOLDOWNS_KEY, "[bad-json").commit())
        f.repository.restoreCooldowns(f.attemptStore())
        assertTrue(f.preferences.isBlocked())
        assertEquals("reportCandidate=blocked:attempt_state_storage", f.repository.automaticBlockStatus(f.key(), f.now))
        assertFalse(f.repository.persistCooldowns(f.attemptStore()))
    }

    private fun payload(actor: String, entries: JSONArray): String = JSONObject()
        .put("schema", "walksafe.report-attempt-state.v2")
        .put("actor_hash", sha256Hex(actor.toByteArray())).put("entries", entries).toString()

    private fun entry(key: String, failures: Int): JSONObject = JSONObject().put("key", key)
        .put("failures", failures).put("retry_not_before_ms", 0L)
        .put("terminal_status", JSONObject.NULL).put("terminal_until_ms", 0L)
}

/** The actual sensitive-store implementation with controlled disk commits and AES keys. */
internal class ActorReportPreferenceFixture {
    var now = 1_000_000L
    val storage = MemoryStorage()
    private val keyPolicy = AeadKeyPolicy("walksafe.test.report.aead.v", 1, setOf(1))
    private val spec = SensitivePreferenceSpec("report_envelope", "report_blocked", "report_reset",
        "walksafe|test|report".toByteArray(), keyPolicy, setOf(
            ActorReportAttemptRepository.PREF_REPORT_COOLDOWNS_KEY,
            ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2))
    val preferences = AndroidSensitivePreferenceStore(storage, spec, VersionedLocalAead(keyPolicy, MemoryKeys()))
    val repository = newRepository()

    fun newRepository() = ActorReportAttemptRepository({ preferences }, { now })
    fun key(actor: String = "actor-a") = repository.stateKey(actor, "damaged_tactile_block", 37.0, 127.0, ReportTransferPurpose.AUTOMATIC)
    fun attemptStore() = AndroidReportAttemptStore(monotonicNowMs = { now }, bootCountProvider = { 1 })
    fun scope(actor: String = "actor-a") = requireNotNull(AndroidReportCooldownPolicy.spatialScopeOrNull(actor,
        AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK, TrustedLocation(37.0, 127.0, 3f, now)))

    class MemoryStorage : SensitivePreferenceStorage {
        override val processIdentity: Any = this
        private val values = mutableMapOf<String, Any>()
        var commitsSucceed = true
        override fun contains(key: String) = key in values
        override fun keys(): Set<String> = values.keys.toSet()
        override fun getRaw(key: String): Any? = values[key]
        override fun getString(key: String): String? = values[key] as? String
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        override fun commit(strings: Map<String, String>, booleans: Map<String, Boolean>, removals: Set<String>): Boolean {
            if (!commitsSucceed) return false
            removals.forEach(values::remove)
            values.putAll(strings)
            values.putAll(booleans)
            return true
        }
    }

    private class MemoryKeys : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun getExisting(alias: String): SecretKey? = keys[alias]
        override fun delete(alias: String): Boolean { keys.remove(alias); return true }
        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey = getOrCreate(alias)
    }
}
