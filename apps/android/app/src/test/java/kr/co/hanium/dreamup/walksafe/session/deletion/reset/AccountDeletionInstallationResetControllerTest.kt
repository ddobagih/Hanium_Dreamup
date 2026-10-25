package kr.co.hanium.dreamup.walksafe.session.deletion.reset

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionInstallationResetControllerTest {
    @Test fun constructionDoesNotInitializeTheJournalOrPerformParticipantIo() {
        val f = ResetFixture(initialize = false)
        assertTrue(f.events.isEmpty())
        assertEquals(AccountDeletionResetJournalState.Absent, f.controller.journalStateAtStartup)
        f.controller.initialize(File("unused-test-root"))
        assertEquals(listOf("create-coordinator"), f.events)
    }

    @Test fun resetRequiresTerminalCleanupAndBothOriginalParticipantAcknowledgements() {
        val f = ResetFixture()
        f.restoreCompleted(cleanup = false)
        f.controller.resetAfterConfirmedDeletion()
        assertEquals(listOf("ui"), f.events)
        assertEquals(0, f.worker.size)
        f.enableCleanup()
        f.events.clear()
        f.speechAck = false
        f.controller.resetAfterConfirmedDeletion()
        assertEquals(AccountDeletionPhase.RESET_PENDING, f.machine.phase())
        f.worker.runNext()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.machine.phase())
        assertTrue(f.events.indexOf("speech-ack") < f.events.indexOf("route-ack"))
        assertFalse(f.events.contains("gateway-reset"))
        assertFalse(f.events.contains("prepare-fresh"))
        assertTrue(f.sensitive.getBoolean("account_deletion_fail_closed_v1", false))
    }

    @Test fun verifiedResetCommitsBeforeNewProviderAndUiPublicationInOriginalOrder() {
        val f = ResetFixture()
        f.restoreCompleted()
        f.controller.resetAfterConfirmedDeletion()
        f.worker.runNext()
        assertEquals(AccountDeletionPhase.IDLE, f.machine.phase())
        assertFalse(f.events.contains("runtime-reset"))
        f.runUi()
        assertTrue(ReportStaticSourceInspector.appearsInOrder(f.events.joinToString("|"),
            "speech-ack", "route-ack", "gateway-reset", "sensitive-reset", "plain-reset", "field-reset",
            "intent-clear", "prepare-fresh", "report-reset", "runtime-reset", "publish-fresh"))
        assertNull(f.machine.snapshotOrNull())
    }

    @Test fun routePreparationExceptionRollsBackWithoutAwaitingTheAlreadyPreparedSpeech() {
        val f = ResetFixture()
        f.restoreCompleted()
        f.throwRoutePreparation = true
        f.controller.resetAfterConfirmedDeletion()
        f.worker.runNext()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.machine.phase())
        assertTrue(f.events.contains("capture-speech"))
        assertFalse(f.events.contains("speech-ack"))
        assertFalse(f.events.contains("gateway-reset"))
    }

    @Test fun retiredParticipantOwnerPreventsAllDestructiveCoordinatorStages() {
        val f = ResetFixture()
        f.restoreCompleted()
        f.ownerCurrent = false
        f.controller.resetAfterConfirmedDeletion()
        f.worker.runNext()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.machine.phase())
        assertTrue(f.events.contains("route-ack"))
        assertFalse(f.events.contains("gateway-reset"))
    }

    @Test fun rejectedCleanupExecutorRollsBackReservationAndRetainsPrivacyFence() {
        val f = ResetFixture()
        f.restoreCompleted()
        f.worker.reject = true
        f.controller.resetAfterConfirmedDeletion()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.machine.phase())
        assertTrue(f.events.contains("keep-fence"))
        assertFalse(f.events.contains("capture-speech"))
    }

    @Test fun lateLeaseOrRejectedUiClosesOnlyTheNewCapturedProvider() {
        for (rejected in listOf(false, true)) {
            val f = ResetFixture()
            f.restoreCompleted()
            f.acceptUi = !rejected
            f.controller.resetAfterConfirmedDeletion()
            f.worker.runNext()
            if (!rejected) { f.machine.attachActivity(); f.runUi() }
            assertEquals(1, f.events.count { it == "close-fresh" })
            assertFalse(f.events.contains("runtime-reset"))
            assertFalse(f.events.contains("publish-fresh"))
        }
    }

    @Test fun missingFreshProviderOrFailedStartupPublicationCannotPretendTheUiWasReset() {
        val confirmed = ResetFixture()
        confirmed.restoreCompleted()
        confirmed.freshAvailable = false
        confirmed.controller.resetAfterConfirmedDeletion()
        confirmed.worker.runNext()
        assertFalse(confirmed.events.contains("report-reset"))
        assertFalse(confirmed.events.contains("runtime-reset"))
        val startup = ResetFixture()
        startup.publicationSucceeded = false
        startup.plain.values["account_deletion_reset_required_v1"] = true
        assertEquals(InstallationResetStartupResult.FINISH, startup.controller.inspectStartupReset(true))
        assertTrue(startup.events.contains("publish-fresh"))
        assertFalse(startup.events.contains("runtime-reset"))
    }

    @Test fun terminalPersistenceDropsAnObsoleteJournalIdentityAndReason() {
        val f = ResetFixture()
        f.restoreCompleted()
        assertTrue(f.machine.failClosed(f.lease, "first_terminal_reason"))
        val first = f.machine.snapshotOrNull()
        f.controller.scheduleTerminalPersistence("first_terminal_reason", first)
        assertTrue(f.machine.failClosed(f.lease, "new_terminal_reason"))
        // Repeated failure preserves the first durable reason and the same journal identity.
        assertSame(first, f.machine.snapshotOrNull())
        assertEquals("first_terminal_reason", f.machine.failureReasonOrNull())
        // A separately persisted authority replaces that snapshot with its exact durable reason.
        assertTrue(f.machine.enforceFailClosedAuthority(f.lease, "new_terminal_reason"))
        assertNotSame(first, f.machine.snapshotOrNull())
        assertEquals("new_terminal_reason", f.machine.failureReasonOrNull())
        f.worker.runNext()
        assertFalse(f.sensitive.contains("account_deletion_fail_closed_v1"))
        assertFalse(f.events.any { it.startsWith("terminal-failure:") })
    }

    @Test fun anotherInstallationResetCompletionMakesTheOldControllerFinishWithoutPublication() {
        val first = ResetFixture()
        val superseded = ResetFixture()
        first.plain.values["account_deletion_reset_required_v1"] = true
        assertEquals(AccountDeletionResetResult.COMPLETED, first.controller.reconcileResetAtStartup(true))
        assertEquals(InstallationResetStartupResult.FINISH, superseded.controller.inspectStartupReset(null))
        assertFalse(superseded.events.contains("prepare-fresh"))
        assertFalse(superseded.events.contains("report-reset"))
    }

    @Test fun storageParticipantFailureCannotCreateAnUnblockedReplacement() {
        val f = ResetFixture()
        f.restoreCompleted()
        f.failFieldReset = true
        f.controller.resetAfterConfirmedDeletion()
        f.worker.runNext()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.machine.phase())
        assertFalse(f.events.contains("prepare-fresh"))
        assertFalse(f.events.contains("report-reset"))
        assertTrue(f.sensitive.getBoolean("account_deletion_fail_closed_v1", false))
    }

    @Test fun evidenceKeyCleanupRunsOnlyAfterAcknowledgedCompletionAndCannotIgnoreFailure() {
        val f = ResetFixture()
        f.controller.resetAfterConfirmedDeletion()
        assertEquals(0, f.keyPurgeCalls)
        val completed = ResetFixture(); completed.restoreCompleted(); completed.keyPurgeSuccess = false
        completed.controller.resetAfterConfirmedDeletion(); completed.worker.runNext()
        assertEquals(1, completed.keyPurgeCalls)
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, completed.machine.phase())
        assertFalse(completed.events.contains("gateway-reset"))
        assertFalse(completed.events.contains("prepare-fresh"))
        assertFalse(completed.events.contains("publish-fresh"))
    }

    @Test fun startupResetRequiresAnOriginalVerifiedPreparationAndStrictLegacyMarker() {
        val f = ResetFixture()
        assertEquals(AccountDeletionResetResult.NO_PENDING, f.controller.reconcileResetAtStartup(null))
        f.plain.values["account_deletion_reset_required_v1"] = true
        assertEquals(AccountDeletionResetResult.BLOCKED, f.controller.reconcileResetAtStartup(null))
        assertFalse(f.events.contains("gateway-reset"))
        assertEquals(AccountDeletionResetResult.BLOCKED, f.controller.reconcileResetAtStartup(false))
        assertEquals(AccountDeletionResetResult.COMPLETED, f.controller.reconcileResetAtStartup(true))
        assertTrue(f.events.contains("gateway-reset"))
        val malformed = ResetFixture()
        malformed.plain.values["account_deletion_reset_required_v1"] = "true"
        assertEquals(LegacyResetIntentState.CORRUPT, malformed.controller.legacyIntentState())
        assertEquals(AccountDeletionResetResult.BLOCKED, malformed.controller.reconcileResetAtStartup(true))
        assertFalse(malformed.events.contains("gateway-reset"))
    }

    @Test fun completedStartupResetPublishesSameProviderOnlyAfterAbsentAuthorityAndFreshEnrollment() {
        val f = ResetFixture()
        f.plain.values["account_deletion_reset_required_v1"] = true
        assertEquals(InstallationResetStartupResult.CONTINUE, f.controller.inspectStartupReset(true))
        assertFalse(f.handoff.pending)
        assertTrue(ReportStaticSourceInspector.appearsInOrder(f.events.joinToString("|"),
            "intent-clear", "observe-authority:ABSENT", "report-reset", "prepare-fresh", "publish-fresh"))
        assertEquals(AccountDeletionPhase.IDLE, f.machine.phase())
    }

    @Test fun staleStartupHandoffNeverClearsTheProcessFlagOrResetsReportPrivacy() {
        val f = ResetFixture()
        f.handoff.pending = true
        f.machine.attachActivity()
        assertEquals(InstallationResetStartupResult.FINISH, f.controller.restoreStartupHandoffAfterResourcesPrepared())
        assertTrue(f.handoff.pending)
        assertFalse(f.events.contains("report-reset"))
    }

    @Test fun plainResetUsesExactAllowlistRequiresVerifiedRouteAndChecksReadback() {
        val f = ResetFixture()
        val keys = listOf("raw_source_field_log_blocked_v1", "account_deletion_reset_required_v1", "reporter_user_id",
            "account_deletion_completion_receipt_v1", "device_check_location_result_v1", "device_check_location_reason_v1",
            "device_check_location_accuracy_bucket_v1", "device_check_location_age_bucket_v1")
        keys.forEach { f.plain.values[it] = "old" }
        f.plain.values["unrelated_user_setting"] = "preserve"
        f.routeVerified = false
        assertFalse(f.storage.clearPlainResetArtifacts())
        assertTrue(keys.all(f.plain.values::containsKey))
        f.routeVerified = true
        assertTrue(f.storage.clearPlainResetArtifacts())
        assertTrue(keys.none(f.plain.values::containsKey))
        assertEquals("preserve", f.plain.values["unrelated_user_setting"])
        f.plain.values[keys.first()] = true
        f.plain.afterCommit = { f.plain.values[keys.first()] = true }
        assertFalse(f.storage.clearPlainResetArtifacts())
    }

    @Test fun strictTerminalPersistenceRequiresBothDurableAuthorityAndExactSensitiveReadback() {
        val f = ResetFixture()
        f.restoreCompleted()
        f.machine.failClosed(f.lease, "controlled_terminal_failure")
        val journal = f.machine.snapshotOrNull()
        assertTrue(f.controller.persistTerminalState(journal, "fallback"))
        assertEquals("controlled_terminal_failure", f.sensitive.getString("account_deletion_fail_closed_reason_v1", null))
        assertEquals(AccountDeletionJournalCodec.encode(journal!!), f.sensitive.getString("account_deletion_journal_v2", null))
        f.sensitiveDisk.commitsSucceed = false
        assertFalse(f.controller.persistTerminalStateOrHandle(journal, "fallback"))
        assertTrue(f.events.contains("terminal-failure:controlled_terminal_failure"))
        assertTrue(f.sensitive.isBlocked())
    }

    @Test fun blockingResetFailureRespectsCallbackLeaseAndPreservesFenceOrder() {
        val f = ResetFixture()
        f.callbackAllowed = false
        assertFalse(f.controller.blockForResetFailure())
        assertEquals(AccountDeletionPhase.IDLE, f.machine.phase())
        assertFalse(f.sensitive.isBlocked())
        f.callbackAllowed = true
        assertTrue(f.controller.blockForResetFailure())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.machine.phase())
        assertTrue(f.sensitive.isBlocked())
        assertTrue(f.events.contains("block-reset-privacy"))
        assertEquals(true, f.plain.values["raw_source_field_log_blocked_v1"])
    }
}

internal class ResetFixture(initialize: Boolean = true) {
    val events = mutableListOf<String>()
    val machine = AccountDeletionStateMachine()
    val lease = machine.attachActivity()
    val worker = QueuedExecutor()
    private val ui = ArrayDeque<() -> Unit>()
    val plain = PlainPreferences()
    val sensitiveDisk = SensitiveDisk()
    private val policy = AeadKeyPolicy("walksafe.test.reset.aead.v", 1, setOf(1))
    val sensitive = AndroidSensitivePreferenceStore(sensitiveDisk,
        SensitivePreferenceSpec("envelope", "blocked", "reset_pending", "test|reset".toByteArray(), policy,
            setOf("account_deletion_journal_v2", "account_deletion_fail_closed_v1", "account_deletion_fail_closed_reason_v1")),
        VersionedLocalAead(policy, Keys()))
    var authorityState: AccountDeletionIntentAuthorityState = AccountDeletionIntentAuthorityState.Absent
    private var intent = false
    val dual = AccountDeletionDualAuthority(AccountDeletionIntentFence(read = { intent }, store = { intent = true; true }, clear = { intent = false; true }),
        object : AccountDeletionIntentAuthority {
            override fun read() = authorityState
            override fun confirm(): Boolean { authorityState = AccountDeletionIntentAuthorityState.Confirmed; return true }
            override fun failClosed(reason: String): Boolean { authorityState = AccountDeletionIntentAuthorityState.FailClosed(reason); return true }
            override fun clear(): Boolean { authorityState = AccountDeletionIntentAuthorityState.Absent; return true }
        })
    var routeVerified = true
    var speechAck = true
    var routeAck = true
    var ownerCurrent = true
    var throwRoutePreparation = false
    var failFieldReset = false
    var acceptUi = true
    var freshAvailable = true
    var publicationSucceeded = true
    var destroyed = false
    var callbackAllowed = true
    var keyPurgeSuccess = true
    var keyPurgeCalls = 0
    val handoff = object : InstallationResetStartupHandoff { override var pending = false }
    val storage = AccountDeletionResetStorage({ plain.instance }, { sensitive }, { null }, { dual }, { routeVerified }, { !failFieldReset })
    private val journal = object : AccountDeletionResetJournal {
        var state: AccountDeletionResetJournalState = AccountDeletionResetJournalState.Absent
        override fun read() = state
        override fun write(phase: AccountDeletionResetPhase): Boolean { state = AccountDeletionResetJournalState.Pending(phase); return true }
        override fun clear(): Boolean { state = AccountDeletionResetJournalState.Absent; return true }
    }
    private val coordinator = AccountDeletionResetCoordinator(journal,
        storeLegacyIntent = { plain.values["account_deletion_reset_required_v1"] = true; true },
        resetGateway = { events += "gateway-reset"; true },
        resetSensitivePreferences = { events += "sensitive-reset"; true },
        resetPlainPreferences = { events += "plain-reset"; storage.clearPlainResetArtifacts() },
        resetFieldStorage = { events += "field-reset"; !failFieldReset },
        clearDeletionIntentFence = { events += "intent-clear"; storage.clearIntentFence() })
    val controller = AccountDeletionInstallationResetController({ machine }, { lease }, { worker }, {
        if (acceptUi) ui.addLast(it)
        acceptUi
    }, { it() }, storage, handoff, object : InstallationResetActions {
        override fun isDestroyed() = destroyed
        override fun activityCallbackAllowed() = callbackAllowed
        override fun updateUi() { events += "ui" }
        override fun keepPrivacyFenceClosed() { events += "keep-fence" }
        override fun invalidateGatewaySession() { events += "invalidate-gateway" }
        override fun captureParticipants() = InstallationResetParticipants.capture<Any, Any>(
            prepareSpeech = { events += "capture-speech"; Any() },
            prepareRoute = { if (throwRoutePreparation) error("controlled route preparation failure"); events += "capture-route"; Any() },
            awaitSpeech = { events += "speech-ack"; speechAck }, awaitRoute = { events += "route-ack"; routeAck },
            speechIsCurrent = { ownerCurrent }, routeIsCurrent = { ownerCurrent })
        override fun prepareFreshSpeechCollection(): PreparedResetSpeechCollection? {
            check(machine.phase() == AccountDeletionPhase.IDLE)
            events += "prepare-fresh"
            if (!freshAvailable) return null
            return object : PreparedResetSpeechCollection {
                override fun publish(): Boolean { events += "publish-fresh"; return publicationSucceeded }
                override fun closeAndDrain() { events += "close-fresh" }
            }
        }
        override fun resetReportPrivacyForNewEnrollment() { events += "report-reset" }
        override fun resetRuntimeForNewEnrollment() { events += "runtime-reset" }
        override fun finishActivity() { events += "finish" }
        override fun observeStartupAuthority(snapshot: AccountDeletionAuthoritySnapshot) { events += "observe-authority:${snapshot.aggregateState}" }
        override fun blockPrivacyForResetFailure() { events += "block-reset-privacy" }
        override fun applyTerminalPersistenceFailure(reason: String) { events += "terminal-failure:$reason" }
        override fun showTerminalPersistenceFailure(reason: String) { events += "terminal-ui:$reason" }
    }, { events += "create-coordinator"; coordinator }, { keyPurgeCalls++; keyPurgeSuccess })
    init { if (initialize) { controller.initialize(File("unused-reset-root")); events.clear() } }
    fun runUi() { ui.removeFirst().invoke() }
    fun restoreCompleted(cleanup: Boolean = true) {
        val pending = AccountDeletionJournal.pending("https://gateway.example.test", "installation_0001", "account_delete_request_0001", "2026-10-06T00:00:00Z")
        val completed = pending.copy(phase = AccountDeletionPhase.COMPLETED, serverRevision = 1L,
            acceptedAt = "2026-10-06T00:01:00Z", accountGeneration = 1L, tombstoneId = "tombstone_0001",
            requestReceiptSha256 = "a".repeat(64), receiptSha256 = "b".repeat(64), deviceEvidenceId = "device_evidence_0001",
            deviceEvidenceSha256 = "c".repeat(64), deviceEvidenceExpectedStatusRevision = 1L, deviceEvidenceResult = "DELETED",
            deviceEvidenceCompletedAt = "2026-10-06T00:02:00Z", deviceEvidenceAcknowledged = true,
            items = pending.items.mapValues { (_, value) -> value.copy(state = DeletionItemState.COMPLETED, evidenceSha256 = "d".repeat(64), terminalAt = "2026-10-06T00:02:00Z") })
        check(machine.restore(completed, lease))
        if (cleanup) enableCleanup()
    }
    fun enableCleanup() {
        val completed = requireNotNull(machine.snapshotOrNull())
        val attempt = requireNotNull(controller.terminalCleanup.begin(completed))
        check(controller.terminalCleanup.complete(attempt, completed, AccountDeletionTerminalMarkerCleanupResult.ALREADY_ABSENT, true) == true)
    }
    class QueuedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        var reject = false
        val size get() = queue.size
        override fun execute(command: Runnable) { if (reject) throw RejectedExecutionException(); queue.addLast(command) }
        fun runNext() { queue.removeFirst().run() }
    }
    class SensitiveDisk : SensitivePreferenceStorage {
        override val processIdentity: Any = this
        private val values = mutableMapOf<String, Any>()
        var commitsSucceed = true
        override fun contains(key: String) = key in values
        override fun keys() = values.keys.toSet()
        override fun getRaw(key: String) = values[key]
        override fun getString(key: String) = values[key] as? String
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun commit(strings: Map<String, String>, booleans: Map<String, Boolean>, removals: Set<String>): Boolean {
            if (!commitsSucceed) return false
            removals.forEach(values::remove); values.putAll(strings); values.putAll(booleans); return true
        }
    }
    private class Keys : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun getExisting(alias: String) = keys[alias]
        override fun delete(alias: String): Boolean { keys.remove(alias); return true }
        override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
    }
    class PlainPreferences {
        val values = mutableMapOf<String, Any>()
        var afterCommit: () -> Unit = {}
        val instance: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, arguments ->
            val args = arguments ?: emptyArray()
            when (method.name) {
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0] as String)
                "getString", "getStringSet", "getInt", "getLong", "getFloat", "getBoolean" -> values[args[0] as String] ?: args[1]
                "edit" -> editor()
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ControlledResetPreferences"
                else -> error("Unexpected preference method: ${method.name}")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val writes = mutableMapOf<String, Any?>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, arguments ->
                val args = arguments ?: emptyArray()
                when (method.name) {
                    "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> { writes[args[0] as String] = args[1]; editor }
                    "remove" -> { writes[args[0] as String] = null; editor }
                    "clear" -> { clear = true; editor }
                    "commit", "apply" -> {
                        if (clear) values.clear()
                        writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        afterCommit()
                        if (method.name == "commit") true else null
                    }
                    "equals" -> proxy === args[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "ControlledResetEditor"
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
