package kr.co.hanium.dreamup.walksafe.session.privacy

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.report.ReportPrivacyConsentSession
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.consent.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.*
import kr.co.hanium.dreamup.walksafe.session.deletion.runtime.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PrivacyAuthorityCompositionTest {
    @After fun resetReportProcess() { ReportPrivacyConsentSession.resetForNewEnrollment() }

    @Test fun constructionAndEveryOriginalAnchorKeepForeignResourcesLate() = Fixture().use { f ->
        assertTrue(f.queries.isEmpty())
        val filesBefore = f.base.root.walkTopDown().map { it.relativeTo(f.base.root).path }.toList()
        f.anchors()
        assertEquals(listOf("deletion-client"), f.queries)
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty() && f.events.isEmpty())
        assertEquals(filesBefore, f.base.root.walkTopDown().map { it.relativeTo(f.base.root).path }.toList())
        assertEquals(0, f.base.markerFactoryCalls)
        assertNull(f.speech.collectionOrNull())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.lazies().none { it.isInitialized() })
        assertTrue(f.lazies().dropLast(1).all { it.javaClass.name == "kotlin.SynchronizedLazyImpl" })
        assertEquals("kotlin.UnsafeLazyImpl", f.restore.javaClass.name)
    }

    @Test fun actualMainAliasesRetainTheSameEagerOwnersAndActualLazyInstances() {
        val main = MainActivity()
        val graph = field(main, "privacyAuthority")
        for (name in listOf("accountDeletionResetStorage", "accountDeletionInstallationResetController", "speechOutputStorageCoordinator",
            "reportPrivacyConsentSession", "integratedConsentSession", "accountDeletionWorkerStages", "accountDeletionRecoveryStore",
            "accountDeletionOperationAuthority", "accountDeletionRecoveryActorController", "accountDeletionPreparationController",
            "legacyPendingReportQueuePurgeController")) {
            assertSame(name, field(main, name), field(graph, name))
        }
        for (name in listOf("integratedConsentController", "accountDeletionNetworkController", "accountDeletionLocalDataPurger",
            "accountDeletionRuntimeFenceController", "accountDeletionEvidenceController", "accountDeletionProgressController", "privacyStartupRestoreCoordinator")) {
            assertSame(name, field(main, name + "\$delegate"), field(graph, name))
            assertFalse(name, (field(graph, name) as Lazy<*>).isInitialized())
        }
    }

    @Test fun forcingProgressUsesTheOriginalEvidencePeerAndDoesNotForceItsNetworkCycle() = Fixture().use { f ->
        f.anchors(); f.queries.clear()
        val progress = f.progress.value
        assertTrue(f.evidence.isInitialized())
        assertSame(f.evidence.value, field(progress, "evidenceController"))
        assertSame(f.operation, field(progress, "operation"))
        assertSame(f.store, field(progress, "store"))
        assertSame(f.actor, field(progress, "actor"))
        assertSame(f.stages, field(progress, "stages"))
        assertFalse(f.network.isInitialized())
        assertFalse(f.purger.isInitialized())
        assertFalse(f.fence.isInitialized())
        assertEquals(listOf("deletion-client"), f.queries)
        val network = f.network.value
        assertSame(f.operation, field(network, "operation"))
        assertSame(f.store, field(network, "store"))
        assertSame(f.actor, field(network, "actor"))
        assertSame(f.stages, field(network, "stages"))
        assertNull((field(network, "queries") as AccountDeletionNetworkQueries).localPurgeRequestId())
        assertEquals(listOf("deletion-client", "deletion-client"), f.queries)
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
    }

    @Test fun originalConsentAndPurgeParticipantsAreCapturedAtFirstForceOnly() = Fixture().use { f ->
        f.anchors(); f.queries.clear()
        assertFalse(f.consent.isInitialized()); assertFalse(f.purger.isInitialized())
        f.consent.value
        assertEquals(listOf("consent-ports"), f.queries)
        assertSame(f.consentSession, field(f.consent.value, "integratedConsentSession"))
        assertSame(f.consent.value, f.consent.value)
        f.purger.value
        assertEquals(listOf("consent-ports", "purge-ports"), f.queries)
        assertSame(f.legacy, field(f.purger.value, "legacyQueue"))
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
    }

    @Test fun acceptedConsentPreparationUsesExactIdentityAndRejectedEvaluationsHaveNoEffects() = Fixture().use { f ->
        val first = confirmation(raw = true)
        for (result in IntegratedConsentApplyResult.entries) {
            val calls = mutableListOf<String>()
            val accepted = f.graph.prepareSpeechOutputStorageConsentApply(first, result, first,
                { calls += "read"; first }, { calls += "withdraw" }, { calls += "invalidate" })
            when (result) {
                IntegratedConsentApplyResult.APPLIED -> { assertTrue(accepted); assertEquals(listOf("invalidate"), calls) }
                IntegratedConsentApplyResult.DUPLICATE -> { assertTrue(accepted); assertEquals(listOf("read"), calls) }
                else -> { assertFalse(accepted); assertTrue(calls.isEmpty()) }
            }
        }
        val copied = first.copy()
        val calls = mutableListOf<String>()
        assertTrue(f.graph.prepareSpeechOutputStorageConsentApply(copied, IntegratedConsentApplyResult.DUPLICATE, first,
            { calls += "read"; copied }, { calls += "withdraw" }, { calls += "invalidate" }))
        assertEquals(listOf("read", "invalidate"), calls)
        calls.clear()
        assertTrue(f.graph.prepareSpeechOutputStorageConsentApply(confirmation(raw = false), IntegratedConsentApplyResult.DUPLICATE, first,
            { error("withdrawal must not query raw authority") }, { calls += "withdraw" }, { calls += "invalidate" }))
        assertEquals(listOf("withdraw"), calls)
    }

    @Test fun queuedRefreshRechecksDestroyedAndExactLeaseAtExecution() = Fixture().use { f ->
        f.graph.postAccountDeletionUiRefresh(); assertTrue(f.events.isEmpty()); f.destroyed = true
        f.runUi(); assertTrue(f.events.isEmpty())
        f.destroyed = false; f.graph.postAccountDeletionUiRefresh(); f.base.machine.attachActivity()
        f.runUi(); assertTrue(f.events.isEmpty())
    }

    @Test fun currentQueuedRefreshRunsOnceOnlyAfterTheSameUiBoundary() = Fixture().use { f ->
        f.graph.postAccountDeletionUiRefresh(); assertEquals(1, f.ui.size); assertTrue(f.events.isEmpty())
        f.runUi(); assertEquals(listOf("ui"), f.events)
    }

    @Test fun fenceRechecksCurrentBlockedStateBeforeForcingAnyParticipant() = Fixture().use { f ->
        f.anchors(); f.graph.keepAccountDeletionPrivacyFenceClosed(); f.runUi()
        assertFalse(f.fence.isInitialized()); assertTrue(f.events.isEmpty())
        assertTrue(f.base.machine.failClosed(f.base.lease, "test_failure"))
        f.graph.keepAccountDeletionPrivacyFenceClosed(); f.destroyed = true; f.runUi()
        assertFalse(f.fence.isInitialized()); assertTrue(f.events.isEmpty())
        f.destroyed = false; f.graph.keepAccountDeletionPrivacyFenceClosed(); f.base.machine.attachActivity(); f.runUi()
        assertFalse(f.fence.isInitialized()); assertTrue(f.events.isEmpty())
    }

    @Test fun currentFencePreservesSpeechConsentAndForeignEffectOrderIncludingFailedPosition() = Fixture().use { f ->
        f.anchors(); f.positionPurge = false
        val token = f.speech.accountDeletionIntentToken()
        assertTrue(f.base.machine.failClosed(f.base.lease, "test_failure"))
        f.graph.keepAccountDeletionPrivacyFenceClosed(); f.runUi()
        assertNotSame(token, f.speech.accountDeletionIntentToken())
        assertTrue(ReportPrivacyConsentSession.isAccountDeletionBlocked())
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.consentSession.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(listOf("position", "report-queue", "raw", "user-requests", "field", "upload-authority", "permissions",
            "metadata", "originals", "frames", "attempt", "walk-stop", "position-failed", "ui"), f.events)
    }

    @Test fun actualPreparationConfirmationAndCancelUseTheOriginalStateMachineWithoutAWorker() = Fixture().use { f ->
        f.anchors(); f.preparation.onPrimaryClicked()
        assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, f.base.machine.phase())
        f.preparation.onCancelClicked()
        assertEquals(AccountDeletionPhase.IDLE, f.base.machine.phase())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.worker.isEmpty())
        assertFalse(f.network.isInitialized())
    }

    @Test fun mismatchedActorIsRejectedBeforeTheActualFenceAndNetworkNode() = Fixture().use { f ->
        f.anchors(); f.preparation.onPrimaryClicked(); f.reporter = "another_actor"
        f.preparation.onConfirmClicked()
        assertFalse(f.fence.isInitialized()); assertFalse(f.network.isInitialized())
        assertTrue(f.worker.isEmpty()); assertFalse(f.operation.requestInFlight)
    }

    @Test fun resetFailureUsesTheSameStorageConsentReportAndRemoteResumeAuthorities() = Fixture().use { f ->
        f.anchors(); val actions = field(f.reset, "actions") as InstallationResetActions
        actions.applyTerminalPersistenceFailure("failed")
        assertTrue(f.store.remoteResumeBlocked)
        assertTrue(ReportPrivacyConsentSession.isAccountDeletionBlocked())
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.consentSession.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertNull(f.speech.authorityOrNull())
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
    }

    @Test fun freshResetCollectionRequiresTheActualVerifiedSpeechPreparation() = Fixture().use { f ->
        f.anchors(); f.queries.clear()
        assertNull(f.graph.captureFreshInstallationResetSpeechCollection())
        assertNull(f.speech.collectionOrNull())
        assertTrue(f.queries.isEmpty()); assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
    }

    @Test fun staleStartupRestoreCannotTouchPersistenceOrConsent() = Fixture().use { f ->
        f.anchors(); f.queries.clear(); f.callbackAllowed = false
        assertFalse(f.restore.value.restore())
        assertEquals(listOf("callback"), f.queries)
        assertFalse(f.consent.isInitialized()); assertFalse(f.network.isInitialized())
        assertTrue(f.events.isEmpty())
    }

    @Test fun startupSensitiveFailurePersistsTheSameRawFenceAndClosesActualAuthorities() = Fixture().use { f ->
        f.anchors(); f.bindRecovery(); f.base.preferences.failClosed()
        assertTrue(f.restore.value.restore())
        assertTrue(f.base.machine.processingBlocked())
        assertEquals("sensitive_storage_blocked", f.base.machine.failureReasonOrNull())
        assertTrue(ReportPrivacyConsentSession.isAccountDeletionBlocked())
        assertEquals(true, f.plainValues["raw_source_field_log_blocked_v1"])
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.consentSession.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(f.consent.isInitialized()); assertFalse(f.network.isInitialized())
    }

    @Test fun startupResumeVerifiesTheDurableMarkerBeforeSchedulingOneAcknowledgedStatusCall() = DeletionProgressFixture().use { durable ->
        acknowledgeDeviceEvidence(durable)
        val f = Fixture(durable.base)
        f.anchors(); f.bindRecovery()
        assertTrue(f.store.prepareIntentAuthority().confirmed)

        f.graph.resumePrivacyControlOperations()
        assertEquals(1, f.worker.size)
        assertFalse(f.network.isInitialized())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.networkWork.isEmpty())

        f.worker.removeFirst().run()
        assertEquals(1, f.worker.size)
        assertTrue(f.network.isInitialized())
        assertTrue(f.networkWork.isEmpty())
        f.worker.removeFirst().run()
        assertTrue(f.worker.isEmpty())
        assertTrue(f.operation.requestInFlight)
        assertEquals(1, f.networkWork.size)
        assertFalse(f.purger.isInitialized())
        assertEquals(AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED,
            (f.base.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record.phase)
        f.operation.destroy()
    }

    @Test fun corruptStartupMarkerFailsClosedBeforeTheNetworkOwnerCanStart() = DeletionProgressFixture().use { durable ->
        acknowledgeDeviceEvidence(durable)
        val f = Fixture(durable.base)
        f.anchors(); f.bindRecovery()
        assertTrue(f.store.prepareIntentAuthority().confirmed)
        File(f.base.root, AndroidAccountDeletionFallbackMarker.MARKER_FILE_NAME).writeText("corrupt")

        f.graph.resumePrivacyControlOperations()
        assertEquals(1, f.worker.size)
        assertFalse(f.network.isInitialized())
        f.worker.removeFirst().run()
        assertFalse(f.network.isInitialized())
        assertTrue(f.networkWork.isEmpty())
        assertEquals(1, f.worker.size)
        f.worker.removeFirst().run()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.base.machine.phase())
        assertEquals("fallback_marker_unavailable", f.base.machine.failureReasonOrNull())
        assertTrue(f.store.remoteResumeBlocked)
        assertTrue(f.worker.isEmpty() && f.networkWork.isEmpty())
        assertFalse(f.operation.requestInFlight)
    }

    @Test fun deniedStartupRuntimeFenceDoesNotScheduleAnIndependentStatusRefresh() = DeletionProgressFixture().use { durable ->
        acknowledgeDeviceEvidence(durable)
        val f = Fixture(durable.base)
        f.anchors(); f.bindRecovery()
        assertTrue(f.store.prepareIntentAuthority().confirmed)
        f.positionPurge = false

        f.graph.resumePrivacyControlOperations()
        assertTrue(f.base.machine.processingBlocked())
        assertTrue(f.events.contains("status:accountDeletion=blocked:position_field_purge"))
        assertFalse(f.network.isInitialized())
        assertTrue(f.worker.isEmpty() && f.networkWork.isEmpty())
        assertFalse(f.operation.requestInFlight)
    }

    private class Fixture(val base: DeletionRecoveryFixture = DeletionRecoveryFixture()) : AutoCloseable {
        val queries = mutableListOf<String>()
        val events = mutableListOf<String>()
        val worker = ArrayDeque<Runnable>()
        val networkWork = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        var destroyed = false
        var callbackAllowed = true
        var positionPurge = true
        var reporter: String? = base.actor
        val plainValues = mutableMapOf<String, Any?>()
        private val plain = preferences(plainValues)
        private val fieldLog = object : FieldSessionLog by NoopFieldSessionLog() {
            override fun blockForAccountDeletion(): Boolean { events += "field-block"; return true }
        }
        val graph = PrivacyAuthorityComposition(
            plainPreferences = { queries += "plain"; plain }, sensitivePreferences = { queries += "sensitive"; base.preferences },
            sensitivePreferencesOrNull = { queries += "sensitive-if-ready"; base.preferences }, gatewayStoreOrNull = { error("Gateway store query") },
            installationId = { queries += "installation"; "installation_0001" },
            fallbackMarkerFactory = { queries += "marker"; base.marker }, configuredGatewayOriginOrNull = { queries += "origin"; base.origin },
            stateMachine = { queries += "machine"; base.machine }, activityLease = { queries += "lease"; base.lease },
            cleanupExecutor = { queries += "cleanup-executor"; Executor { worker += it } },
            networkExecutor = { queries += "network-executor"; Executor { networkWork += it } },
            deletionClient = { queries += "deletion-client"; AndroidPrivacyDeletionClient() },
            consentClient = { error("Consent network client query") },
            callbacks = object : AccountDeletionProgressCallbacks {
                override val activityToken get() = "privacy_test_activity"
                override val destroyed get() = this@Fixture.destroyed
                override val startupInspectionDestroyed get() = this@Fixture.destroyed
                override fun post(callback: () -> Unit): Boolean { ui += callback; return true }
            }, runOnUi = { ui += it },
            activity = object : PrivacyAuthorityActivity {
                override fun callbackAllowed(): Boolean { queries += "callback"; return callbackAllowed }
                override fun updateUi() { events += "ui" }
                override fun status(value: String) { events += "status:$value" }
                override fun speak(value: String) { events += "speak:$value" }
                override fun invalidateGatewaySession() { events += "invalidate-gateway" }
                override fun resetRuntimeForNewEnrollment() { events += "reset-runtime" }
                override fun finishActivity() { events += "finish" }
                override fun schedulePrivacyOperations() { events += "schedule-privacy" }
            }, fieldLog = { fieldLog }, fieldLogOrNull = { fieldLog },
            routeArchive = { error("Route owner queried before a reset participant stage") },
            purgeGuidanceDiagnosticsOnWorker = { true },
            validFailClosedReason = Regex("[a-z0-9](?:[a-z0-9_:-]{0,95})")::matches,
        )
        lateinit var reset: AccountDeletionInstallationResetController
        lateinit var speech: SpeechOutputStorageCoordinator
        lateinit var consentSession: IntegratedConsentSession
        lateinit var stages: AccountDeletionWorkerStages
        lateinit var store: AccountDeletionRecoveryStore
        lateinit var operation: AccountDeletionOperationAuthority
        lateinit var actor: AccountDeletionRecoveryActorController
        lateinit var preparation: AccountDeletionPreparationController
        lateinit var legacy: LegacyPendingReportQueuePurgeController
        lateinit var consent: Lazy<IntegratedConsentController>
        lateinit var network: Lazy<AccountDeletionNetworkController>
        lateinit var purger: Lazy<AccountDeletionLocalDataPurger>
        lateinit var fence: Lazy<AccountDeletionRuntimeFenceController>
        lateinit var evidence: Lazy<AccountDeletionEvidenceController>
        lateinit var progress: Lazy<AccountDeletionProgressController>
        lateinit var restore: Lazy<kr.co.hanium.dreamup.walksafe.session.gateway.PrivacyStartupRestoreCoordinator>
        fun anchors() {
            graph.createAccountDeletionResetStorage()
            reset = graph.createAccountDeletionInstallationResetController()
            speech = graph.createSpeechOutputStorageCoordinator({ error("Permissions early") }, { error("Lifecycle early") }) { accountBlocked ->
                object : SpeechStorageQueries {
                    override fun startupDestroyed() = destroyed
                    override fun foreground(): Boolean = error("Foreground early")
                    override fun lifecycleInitialized(): Boolean = error("Lifecycle early")
                    override fun currentActuator(): AndroidFeedbackActuator? = error("Actuator early")
                    override fun feedbackGeneration(): Int = error("Generation early")
                    override fun observedPermissions(): ObservedPermissionSnapshot = error("Permissions early")
                    override fun deviceCheck(): PostLoginDeviceCheckSnapshot = error("Device early")
                    override fun fieldParticipantOrNull(): FieldSessionLog? = fieldLog
                    override fun filesDirectory(): File = error("Files early")
                    override fun accountBlockedAtStartup() = accountBlocked()
                    override fun rawBlockedAtStartup(): Boolean = error("Raw early")
                    override fun requiredObservedPermissions(): Set<ObservedPermission> = error("Required permissions early")
                }
            }
            graph.createReportPrivacyConsentSession(); consentSession = graph.createIntegratedConsentSession()
            stages = graph.createAccountDeletionWorkerStages(); store = graph.createAccountDeletionRecoveryStore()
            consent = graph.createIntegratedConsentController { reports, legacy, storage ->
                queries += "consent-ports"
                assertSame(ReportPrivacyConsentSession, reports()); assertSame(this.legacy, legacy()); assertSame(speech, storage())
                PrivacyConsentParticipants(failPort(), { error("Consent permissions early") }, failPort(), failPort(), failPort(), failPort(), failPort(), Executor { worker += it })
            }
            operation = graph.createAccountDeletionOperationAuthority()
            actor = graph.createAccountDeletionRecoveryActorController({ true }, { reporter }, object : AccountDeletionEnrollmentActorReader {
                override fun onboardingInitialized() = false
                override fun onboardingComplete(): Boolean = error("Unexpected onboarding")
                override fun onboardingActorId(): String? = error("Unexpected onboarding")
                override fun priorityPolicyInitialized() = false
                override fun reporterActorId() = reporter
                override fun priorityActorId(): String? = null
                override fun priorityAccountUnblocked() = true
            }, { _, _ -> events += "clear-session" })
            preparation = graph.createAccountDeletionPreparationController(object : AccountDeletionPreparationQueries {
                override fun generalSession() = base.session
                override fun trustedOrigin() = base.origin
                override fun currentReporterActor() = reporter
                override fun sensitiveStorageBlocked() = base.preferences.isBlocked()
            }, { events += "reapply-consent" })
            network = graph.createAccountDeletionNetworkController()
            purger = graph.createAccountDeletionLocalDataPurger { storage, _ ->
                queries += "purge-ports"; assertSame(speech, storage()); failPort()
            }
            fence = graph.createAccountDeletionRuntimeFenceController {
                PrivacyFenceParticipants(
                    { events += "position"; positionPurge }, object : AccountDeletionReportFenceActions {
                        override fun cancelDrainAndFenceQueue() { events += "report-queue" }
                        override fun clearUserRequests() { events += "user-requests" }
                        override fun advanceUploadAuthority() { events += "upload-authority" }
                        override fun cancelOriginalUploads() { events += "originals" }
                        override fun clearAttemptState() { events += "attempt" }
                    }, { events += "raw" }, { events += "field" }, { events += "permissions" }, { events += "metadata" },
                    { events += "frames" }, { events += "walk-stop" }, { events += "position-failed" },
                )
            }
            legacy = graph.createLegacyPendingReportQueuePurgeController(false, { Executor { worker += it } }, { events += "legacy-purge"; true })
            evidence = graph.createAccountDeletionEvidenceController(); progress = graph.createAccountDeletionProgressController()
            restore = graph.createPrivacyStartupRestoreCoordinator()
        }
        fun bindRecovery() {
            store.bindIntentFence(base.store.intentFence)
            store.bindFileAuthority(FileAccountDeletionIntentAuthority(File(base.root, "r12-authority")))
            store.observeAuthority(store.dualAuthority.startupState())
        }
        fun lazies(): List<Lazy<*>> = listOf(consent, network, purger, fence, evidence, progress, restore)
        fun runUi() = ui.removeFirst().invoke()
        override fun close() { base.close() }
    }

    companion object {
        private fun acknowledgeDeviceEvidence(f: DeletionProgressFixture) {
            f.installEvidence()
            assertEquals(AccountDeletionApplyResult.APPLIED,
                f.base.machine.apply(requireNotNull(f.base.stages.begin(f.journal)), f.status(2L),
                    acknowledgesDeviceEvidence = true))
            assertTrue(f.base.store.persistJournal(f.journal))
            f.progress.scheduleAfterStatus(f.journal)
            f.runWorker(); f.runUi()
            assertEquals(AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED,
                (f.base.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record.phase)
        }

        private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        private inline fun <reified T> failPort(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unexpected ${T::class.java.simpleName}.${method.name}")
        } as T
        private fun confirmation(raw: Boolean) = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, INTEGRATED_CONSENT_POLICY_VERSION,
            "501e3ad4-e74f-4433-820f-72ac2fdd42ad", "privacy_graph_consent_0001", IntegratedConsentItemVersions(), 1L, 1L,
            IntegratedConsentSelections(rawSourceCollection = raw), "2026-07-25T12:00:00.000Z", "9".repeat(64), "a".repeat(64), "b".repeat(64))
        private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putString", "putLong" -> { values[args!![0] as String] = args[1]; editor }
                    "remove" -> { values.remove(args!![0] as String); editor }
                    "commit" -> true
                    else -> error("Unexpected Editor.${method.name}")
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "edit" -> editor
                    "contains" -> values.containsKey(args!![0])
                    "getAll" -> values.toMap()
                    "getBoolean", "getString", "getLong" -> values[args!![0]] ?: args[1]
                    else -> error("Unexpected SharedPreferences.${method.name}")
                }
            } as SharedPreferences
        }
    }
}
