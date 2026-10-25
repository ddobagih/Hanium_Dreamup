package kr.co.hanium.dreamup.walksafe.session

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentController
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_PENDING_INTEGRATED_CONSENT_MUTATION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_REJECTED_CONSENT_CAS_JOURNAL
import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupInspectionController
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in current-Main state observation after a new live fixture reproduces the Settings lock.
 * No login, consent, preference, receipt, installation-key or controller state is supplied here.
 * Normal Main lifecycle may restore/revalidate the current account; this is not a historical
 * PID10380 snapshot or a Settings feature PASS. A recovered/offline process is not that live run.
 * Local VERIFIED/usable state does not verify service liveness; Root owns that new live receipt.
 * Session status reads use their existing authority guard. Across owners this is not an atomic
 * snapshot; ownerStableDuringRead records only the Gateway generation/session identity check.
 * The 20-second preparation budget leaves room inside JUnit's 30-second limit for reporting
 * and close, but launch/close are not bounded here. Native's external finally must confirm cleanup;
 * a JUnit timeout does not prove that ActivityScenario.close completed.
 * Current control/floor consistency is not the first failClosed cause or a past validation result.
 * Marker flags inspect only the already-loaded current store cache. The derived control flag is
 * not a persisted-store validation or the result of calling the controller's control predicate.
 */
@RunWith(AndroidJUnit4::class)
class IntegratedConsentFailClosedObservationDeviceTest {
    @Test(timeout = 30_000L)
    fun observeCurrentMainConsentLockWithoutChangingConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("allowConsentFailClosedObservation") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "kr.co.hanium.dreamup.walksafe.accountrestartfixture") {
            "Observation requires the owned Account fixture package"
        }
        check(Process.myUid() == 10179 && context.applicationInfo.uid == 10179) {
            "Observation requires the owned Account fixture UID"
        }
        check(BuildConfig.DEBUG && !BuildConfig.DEVELOPMENT_QUICK_START &&
            BuildConfig.WALKSAFE_GATEWAY_ORIGIN == "http://127.0.0.1:39873") {
            "Observation requires the existing Account fixture build configuration"
        }
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        val observerThread = Thread.currentThread()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val opened = ActivityScenario.launch(MainActivity::class.java)
            scenario = opened
            var preparation: Preparation? = null
            var observed: String? = null
            while (observed == null && !observerThread.isInterrupted &&
                SystemClock.elapsedRealtime() < deadline) {
                opened.onActivity { main ->
                    if (!observerThread.isInterrupted && SystemClock.elapsedRealtime() < deadline) {
                        val prepared = prepare(main)
                        preparation = prepared
                        // Read the consent controller only after normal inspection/current actor readiness.
                        if (prepared.ready && !observerThread.isInterrupted &&
                            SystemClock.elapsedRealtime() < deadline) {
                            observed = observe(main)
                        }
                    }
                }
                if (observed == null && !observerThread.isInterrupted &&
                    SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100L)
            }
            val interrupted = observerThread.isInterrupted
            val expired = SystemClock.elapsedRealtime() >= deadline
            val complete = observed != null && !interrupted && !expired
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "CONSENT_OBSERVATION preparationPresent=${preparation != null} " +
                    "preparation=${preparation ?: "NOT_OBSERVED"} deadlineExpired=$expired interrupted=$interrupted " +
                    "historicalLiveAttribution=false liveServiceObserved=false appFeatureTested=false\n")
            })
            if (complete) observed?.let { snapshot ->
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "CONSENT_OBSERVATION $snapshot " +
                        "historicalLiveAttribution=false liveServiceObserved=false appFeatureTested=false\n")
                })
            }
            check(complete) {
                "Read-only observation did not complete current verified actor/Main preparation within 20 seconds; " +
                    "offline/new-process state is not the earlier live Settings failure"
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "CONSENT_OBSERVATION result=OBSERVATION_COMPLETE " +
                    "appFeatureTested=false historicalLiveAttribution=false liveServiceObserved=false\n")
            })
        } finally {
            scenario?.close()
        }
    }

    private data class Preparation(
        val inspectionComplete: Boolean,
        val inspectionDestroyed: Boolean,
        val activityDestroyed: Boolean,
        val activityFinishing: Boolean,
        val foreground: Boolean,
        val currentActorAccessible: Boolean,
        val gatewayOperationInFlight: Boolean,
    ) {
        val ready: Boolean get() = inspectionComplete && !inspectionDestroyed &&
            !activityDestroyed && !activityFinishing && foreground &&
            currentActorAccessible && !gatewayOperationInFlight
    }

    private fun prepare(main: MainActivity): Preparation {
        val inspection = mainField(main, "privacyStartupInspectionController") as
            PrivacyStartupInspectionController
        val owner = GatewaySessionProcessCoordinator.snapshot()
        val reporter = mainField(main, "reporterUserId") as String?
        val permissions = mainField(main, "permissionSessionPolicy") as PermissionSessionPolicy
        return Preparation(inspection.isComplete, inspection.isDestroyed,
            main.isDestroyed, main.isFinishing, mainField(main, "isActivityForeground") == true,
            owner.session?.isUsableFor(reporter) == true &&
                permissions.isAuthenticatedFor(reporter) && !owner.storageBlocked &&
                !owner.deletionRecoveryOnly, owner.inFlightOperationId != null)
    }

    private fun observe(main: MainActivity): String {
        val owner = GatewaySessionProcessCoordinator.snapshot()
        val consent = checkNotNull(mainField(main, "integratedConsentSession") as? IntegratedConsentSession)
        val controller = MainActivityControllerTestFixture.controller(main,
            "integratedConsentController") as IntegratedConsentController
        val state = controller.screenState()
        val sessionLock = readField(consent, "lock") as Any
        var sessionPendingPresent = false
        val sessionObservation = synchronized(sessionLock) {
            val deletionBlocked = readField(consent, "accountDeletionBlocked") as Boolean
            val failedClosed = readField(consent, "failClosed") as Boolean
            val current = readField(consent, "current") as IntegratedConsentConfirmation?
            val floor = readField(consent, "revisionFloor") as Long
            val identityMatches = current != null && floor > 0L &&
                current.installationId == readField(consent, "revisionFloorInstallationId") &&
                current.policyVersion == readField(consent, "revisionFloorPolicyVersion")
            val revisionMatches = current != null && floor > 0L && current.revision == floor
            sessionPendingPresent = consent.pendingMutationOrNull() != null
            buildString {
                append("accountDeletionBlocked=$deletionBlocked failClosed=$failedClosed")
                append(" currentConfirmationInternalPresent=${current != null} revisionFloorPresent=${floor > 0L}")
                append(" currentMatchesFloorIdentity=$identityMatches currentMatchesFloorRevision=$revisionMatches")
                append(" sessionPendingPresent=$sessionPendingPresent")
                append(" localWithdrawalCount=${consent.localWithdrawalItems().size}")
            }
        }
        val statuses = IntegratedConsentItem.values().associateWith(consent::status)
        val cachedPresent = consent.cachedConfirmationOrNull() != null
        val deletion = mainField(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator
        val sensitive = mainField(main, "sensitivePrefs") as? AndroidSensitivePreferenceStore
        val privacy = checkNotNull(mainField(main, "privacyAuthority"))
        // This existing supplier is Main's direct { sensitivePrefs } read, not a store getter.
        val storeSupplier = readField(privacy, "sensitivePreferences") as Function0<*>
        val sameCurrentSensitiveStore = sensitive != null && storeSupplier.invoke() === sensitive
        check(sameCurrentSensitiveStore) { "Observed Main and consent persistence must use the same sensitive store" }
        // Preserve the original SDK's one isBlocked call; no extra reconciliation-capable getters.
        val sensitiveBlocked = sensitive?.isBlocked() == true
        val cachedMarkers = if (sensitive == null) Triple(false, false, false) else synchronized(sensitive) {
            // Match contains/commitEditor's instance-lock -> process-lock order, without calling them.
            synchronized(readField(sensitive, "PROCESS_LOCK") as Any) {
                val values = readField(sensitive, "values") as Map<*, *>
                Triple(values.containsKey(PREF_PENDING_INTEGRATED_CONSENT_MUTATION),
                    values.containsKey(PREF_REJECTED_CONSENT_CAS_JOURNAL),
                    values.containsKey(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED))
            }
        }
        val unsettledControlFromCachedMarkers = sensitiveBlocked || state.pendingMutation != null ||
            sessionPendingPresent || cachedMarkers.first || cachedMarkers.second || cachedMarkers.third ||
            statuses.values.any { it == PurposeConsentSyncState.WITHDRAWAL_PENDING ||
                it == PurposeConsentSyncState.WITHDRAWAL_RETRY }
        // The existing pure hash helper compares binding in memory; neither value is emitted.
        val actorMatches = owner.session?.let { current ->
            state.confirmedActorSha256 != null && state.confirmedActorSha256 ==
                actorHash(controller, current)
        } == true
        val after = GatewaySessionProcessCoordinator.snapshot()
        val stable = owner.generation == after.generation && owner.session === after.session
        return buildString {
            append(sessionObservation)
            statuses.forEach { (item, status) -> append(" ${item.name}=${status.name}") }
            append(" deletionProcessingBlocked=${deletion.stateMachine.processingBlocked()}")
            append(" sensitivePrefsPresent=${sensitive != null} sensitivePrefsBlocked=$sensitiveBlocked")
            append(" requestInFlight=${state.requestInFlight} bootstrapReady=${state.bootstrapReady}")
            append(" pendingPresent=${state.pendingMutation != null}")
            append(" unsettledControlFromCachedMarkers=$unsettledControlFromCachedMarkers")
            append(" sameCurrentSensitiveStore=$sameCurrentSensitiveStore markerCacheOnly=true")
            append(" cachedPendingMarkerPresent=${cachedMarkers.first} cachedCasJournalPresent=${cachedMarkers.second}")
            append(" cachedWithdrawalMarkerPresent=${cachedMarkers.third}")
            append(" confirmedActorPresent=${state.confirmedActorSha256 != null} actorMatches=$actorMatches")
            // This getter hides the cache while either latch is set; it is not raw-storage absence.
            append(" cachedConfirmationPresent=$cachedPresent cachedVisibilityGuarded=true")
            append(" ownerStableDuringRead=$stable atomicSnapshot=false")
        }
    }

    private fun actorHash(controller: IntegratedConsentController, session: GatewayFieldSession): String =
        controller.javaClass.getDeclaredMethod("integratedConsentActorSha256", GatewayFieldSession::class.java)
            .apply { isAccessible = true }.invoke(controller, session) as String

    private fun mainField(main: MainActivity, name: String): Any? =
        MainActivityControllerTestFixture.field(name).get(main)

    private fun readField(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
