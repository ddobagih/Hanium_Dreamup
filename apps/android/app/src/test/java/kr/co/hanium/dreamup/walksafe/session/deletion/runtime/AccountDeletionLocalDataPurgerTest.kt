package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.io.IOException
import java.util.concurrent.Executor
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionLocalDataPurgerTest {
    @Test fun constructorDoesNotReadPreferencesOrInvokeAnyPurgeParticipant() {
        val f = Fixture()
        assertEquals(0, f.preferenceReads)
        assertTrue(f.events.isEmpty())
        assertTrue(f.delays.isEmpty())
    }

    @Test fun successRequiresEveryObservedPurgeAndPreservesDeletionAuthorityKeys() {
        val f = Fixture()
        for (key in f.profileKeys) assertTrue(f.preferences.edit().putString(key, "profile").commit())
        for (key in f.authorityKeys) assertTrue(f.preferences.edit().putString(key, "retained-authority").commit())
        assertTrue(f.purger.purgeLocalDataOnWorker())
        for (key in f.profileKeys) assertFalse(key, f.preferences.contains(key))
        for (key in f.authorityKeys) assertEquals(key, "retained-authority", f.preferences.getString(key, null))
        assertEquals(listOf("speech", "route", "legacy", "report", "raw-ready", "raw", "field-ready:after-ack",
            "field-block:after-ack", "field-purge:after-ack", "outdoor", "profiles", "diagnostics", "runtime-diagnostics"), f.events)
    }

    @Test fun eachFailedParticipantPreventsDeletedSuccess() {
        for (participant in listOf("speech", "route", "legacy", "report", "raw", "field", "outdoor", "profiles", "diagnostics", "runtime-diagnostics")) {
            val f = Fixture(); f.results[participant] = false
            assertFalse(participant, f.purger.purgeLocalDataOnWorker())
        }
    }

    @Test fun fieldAndCollectionProvidersAreReadOnlyAfterTheCapturedSpeechAck() {
        val f = Fixture()
        assertEquals("before-ack", f.fieldVersion)
        assertTrue(f.purger.purgeLocalDataOnWorker())
        assertEquals("after-ack", f.fieldVersion)
        assertTrue(f.events.contains("field-ready:after-ack"))
        assertTrue(f.events.contains("field-purge:after-ack"))
        val fallback = Fixture(); fallback.persistentField = false
        assertTrue(fallback.purger.purgeLocalDataOnWorker())
        assertTrue(fallback.events.contains("collection-provider:after-ack"))
        assertFalse(fallback.events.any { it.startsWith("field-block:") })
    }

    @Test fun failedSpeechAckCannotUseAnotherProviderToClaimFieldPurgeSuccess() {
        val f = Fixture(); f.results["speech"] = false
        assertFalse(f.purger.purgeLocalDataOnWorker())
        assertFalse(f.events.any { it.startsWith("field-") || it.startsWith("collection-provider:") })
        assertTrue(f.events.contains("profiles"))
    }

    @Test fun missingRawCollectionsCannotBeTreatedAsSuccessfulDeviceUnsentDeletion() {
        val f = Fixture(); f.rawReady = false
        assertFalse(f.purger.purgeLocalDataOnWorker())
        assertTrue(f.events.contains("raw-ready"))
        assertFalse(f.events.contains("raw"))
    }

    @Test fun missingCollectionProviderCannotReplaceAnUninitializedPersistentFieldLoggerWithSuccess() {
        val f = Fixture(); f.persistentField = false; f.collectionResult = null
        assertFalse(f.purger.purgeLocalDataOnWorker())
        assertTrue(f.events.contains("collection-provider:after-ack"))
    }

    @Test fun failedProfilePreferenceCommitCannotBeReportedAsDeleted() {
        val f = Fixture()
        val key = f.profileKeys.first()
        assertTrue(f.preferences.edit().putString(key, "persisted-profile").commit())
        val persisted = f.disk.values.toMap()
        f.disk.commitsSucceed = false
        assertFalse(f.purger.purgeLocalDataOnWorker())
        assertTrue(f.events.contains("profiles"))
        assertTrue(f.preferences.isBlocked())
        assertEquals(persisted, f.disk.values)
    }

    @Test fun genericRetryKeepsThreeAttemptsAndOriginalDelaysForCheckedFailures() {
        val f = Fixture()
        var calls = 0
        assertTrue(f.purger.retry { calls++; if (calls < 3) throw IOException("controlled transient IO"); true })
        assertEquals(3, calls)
        assertEquals(listOf(250L, 500L), f.delays)
    }

    @Test fun interruptedGenericBackoffRestoresInterruptAndStopsFurtherPurge() {
        val f = Fixture(); f.interrupted = true
        var calls = 0
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            assertFalse(f.purger.retry { calls++; false })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, calls)
        } finally { Thread.interrupted() }
    }

    @Test fun throwingParticipantStillRunsRemainingPurgesAndCannotClaimSuccess() {
        val f = Fixture(); f.throwingParticipant = "outdoor"
        assertFalse(f.purger.purgeLocalDataOnWorker())
        assertTrue(f.events.contains("profiles"))
        assertTrue(f.events.contains("diagnostics"))
        assertTrue(f.events.contains("runtime-diagnostics"))
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        val results = mutableMapOf<String, Boolean>()
        var preferenceReads = 0
        var rawReady = true
        var persistentField = true
        var collectionResult: Boolean? = true
        var fieldVersion = "before-ack"
        var interrupted = false
        var throwingParticipant: String? = null
        val profileKeys = setOf("report_successful_cooldowns_v1", "report_attempt_state_v2", "reporter_user_id",
            "priority_user_snapshot_v2_actor", "priority_user_snapshot_invalid_v2_actor")
        val authorityKeys = setOf("account_deletion_journal_v2", "account_deletion_actor_hash_v2", "account_deletion_fail_closed_v1")
        val disk = DeletionRecoveryFixture.Disk()
        private val policy = AeadKeyPolicy("walksafe.test.local-cleanup.v", 1, setOf(1))
        val preferences = AndroidSensitivePreferenceStore(disk,
            SensitivePreferenceSpec("envelope", "blocked", "reset-pending", "test|local-cleanup".toByteArray(), policy, profileKeys + authorityKeys),
            VersionedLocalAead(policy, Keys()))
        private fun result(name: String): Boolean { events += name; if (name == throwingParticipant) throw IOException("controlled $name failure"); return results[name] ?: true }
        private val legacy = LegacyPendingReportQueuePurgeController("local_cleanup_0001", false,
            { Executor { error("No asynchronous queue work expected in local purge") } },
            LegacyQueuePurgeCallbacks { error("No queue callback expected in local purge") }, { result("legacy") }, { delays += it })
        val purger = AccountDeletionLocalDataPurger({ preferenceReads++; preferences }, object : AccountDeletionLocalPurgeParticipants {
            override fun awaitSpeechPurgeOnWorker(): Boolean { val success = result("speech"); fieldVersion = "after-ack"; return success }
            override fun purgeRouteSnapshot() = result("route")
            override fun purgePersistentReportQueue() = result("report")
            override fun rawCollectionsInitialized(): Boolean { events += "raw-ready"; return rawReady }
            override fun purgeRawCollections() = result("raw")
            override fun persistentFieldLoggerReady(): Boolean { events += "field-ready:$fieldVersion"; return persistentField }
            override fun blockPersistentFieldLogger() { events += "field-block:$fieldVersion" }
            override fun purgePersistentFieldLogger(): Boolean { events += "field-purge:$fieldVersion"; return results["field"] ?: true }
            override fun purgeCollectionFieldProvider(): Boolean? { events += "collection-provider:$fieldVersion"; return collectionResult }
            override fun purgeOutdoorPositionArtifacts() = result("outdoor")
            override fun purgeSerialPedestrianProfiles() = result("profiles")
            override fun purgeGuidanceDiagnostics() = result("diagnostics")
            override fun purgeRuntimeDiagnostics() = result("runtime-diagnostics")
        }, legacy, { delay -> delays += delay; if (interrupted) throw InterruptedException() })
    }
    private class Keys : LocalAeadKeyProvider {
        private val values = mutableMapOf<String, SecretKey>()
        override fun getOrCreate(alias: String): SecretKey = values.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun getExisting(alias: String) = values[alias]
        override fun delete(alias: String): Boolean { values.remove(alias); return true }
        override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
    }
}
