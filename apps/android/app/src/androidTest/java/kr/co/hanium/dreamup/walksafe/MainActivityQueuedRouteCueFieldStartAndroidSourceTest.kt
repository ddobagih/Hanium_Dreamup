package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.system.Os
import android.util.Log
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionDeviceInfo
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.HomePanel
import kr.co.hanium.dreamup.walksafe.ui.NativeHomeUiPresenter
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Source-only SDK candidate. Actual attached Main/off-Main posting/Actor callback/Field writer;
 * declared policy/account/education/camera/GPS inputs and a controlled speech engine.
 * Field diagnostic opt-in is separate from RAW speech-output collection (not consented).
 * No onCreate, native TTS, USER data, shared/default alias purge, or physical-start assertion.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityQueuedRouteCueFieldStartAndroidSourceTest {
    @Test(timeout = 90_000L)
    fun queuedCurrentNavigationCueRecordsOnlyItsOwnGenerationAndActualStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        assertEquals(FIXTURE_PACKAGE, context.packageName)
        assertEquals(context.applicationInfo.uid, Process.myUid())
        @Suppress("DEPRECATION")
        val sharedUid = context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId
        assertNull(sharedUid)
        // Context.filesDir may create a missing leaf. Fail before that getter in this fixture.
        val existingFiles = File(context.applicationInfo.dataDir, "files")
        assertTrue("PREPARATION: existing fixture files leaf", Files.isDirectory(existingFiles.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(Process.myUid(), Os.stat(existingFiles.path).st_uid)
        val files = context.applicationContext.filesDir
        assertTrue(Files.isSameFile(existingFiles.toPath(), files.toPath()))
        val f = Fixture(instrumentation, context, files)
        var first: Throwable? = null
        try {
            f.prepare()
            assertFalse(f.log.isActive())
            // Explicit non-opted-in Field control enters actual Main, not a synthetic record.
            val nonOptedInFiles = f.fileState()
            val unconsented = f.newCue(180.0)
            f.dispatchQueued(unconsented)
            assertEquals(unconsented.update.instruction, f.runtime.spoken.last().message)
            assertFalse(f.log.isActive())
            assertNull(f.log.activeSessionId())
            assertEquals(nonOptedInFiles, f.fileState())
            f.cancelPending()

            f.sessionId = requireNotNull(f.log.startAfterUserConfirmation())
            assertTrue(f.log.isActive())
            val cue = f.newCue(200.0)
            f.dispatchQueued(cue)
            val submitted = f.runtime.spoken.last()
            assertEquals(cue.update.instruction, submitted.message)
            val generated = f.events(cue, "route_guidance_generated").single()
            f.assertGenerated(cue, generated)
            assertTrue(f.events(cue, "route_guidance_started").isEmpty())
            // Submission validators have run, but a real Actor onStart callback has not.
            val callbackAt = SystemClock.elapsedRealtime()
            onMain(instrumentation) { f.runtime.start(submitted.id) }
            instrumentation.waitForIdleSync()
            val started = f.events(cue, "route_guidance_started").single().getJSONObject("fields")
            assertEquals(f.cueId(cue), started.getString("cue_id"))
            assertEquals("started", started.getString("result"))
            assertTrue(started.getLong("elapsed_realtime_ms") >= callbackAt)
            assertTrue(started.getLong("elapsed_realtime_ms") >= generated.getJSONObject("fields").getLong("elapsed_realtime_ms"))
            onMain(instrumentation) {
                assertTrue(f.actor.isAppSpeechActive())
                assertFalse(f.navigator.reserveInstruction(cue.update))
                f.call("tryDispatchRouteInstruction", cue.update, f.lifecycle.snapshot().epoch, cue.compass)
                f.runtime.start(submitted.id) // Duplicate engine callback, not another Main cue.
            }
            instrumentation.waitForIdleSync()
            assertEquals(1, f.events(cue, "route_guidance_generated").size)
            assertEquals(1, f.events(cue, "route_guidance_started").size)
            assertEquals(1, f.runtime.spoken.count { it.id == submitted.id })
            onMain(instrumentation) { f.runtime.done(submitted.id) }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                f.call("cancelNavigationSpeechRetry")
                assertFalse(f.actor.isAppSpeechActive())
                assertFalse("Original Main completion ACK consumed this cue", f.navigator.reserveInstruction(cue.update))
            }

            // A queued old cue is invalidated via Navigator's public route install, not private flags.
            val stale = f.newCue(230.0)
            val spokenBeforeStale = f.runtime.spoken.size
            f.dispatchQueued(stale, invalidateBeforeUiDelivery = true)
            assertEquals(1, f.events(stale, "route_guidance_generated").size)
            assertTrue(f.events(stale, "route_guidance_started").isEmpty())
            assertEquals(spokenBeforeStale, f.runtime.spoken.size)
            f.cancelPending()

            // The admitted old sentence receives an actual late Actor START after route retirement.
            val late = f.newCue(260.0)
            f.dispatchQueued(late)
            val lateSpoken = f.runtime.spoken.last()
            onMain(instrumentation) { f.navigator.setRoute(f.route(260.0)); f.runtime.start(lateSpoken.id) }
            instrumentation.waitForIdleSync()
            assertEquals(1, f.events(late, "route_guidance_generated").size)
            assertEquals("revoked", f.events(late, "route_guidance_started").single().getJSONObject("fields").getString("result"))
            onMain(instrumentation) { f.call("cancelNavigationSpeechRetry"); assertFalse(f.actor.isAppSpeechActive()) }

            // A real Field account fence makes later Main record attempts inert. No archive purge.
            f.log.blockNewProcessingForAccountDeletion()
            val fencedBefore = f.fileState()
            val blocked = f.newCue(290.0)
            f.dispatchQueued(blocked)
            assertEquals(blocked.update.instruction, f.runtime.spoken.last().message)
            f.cancelPending()
            assertEquals(fencedBefore, f.fileState())
            f.assertOrdinaryFactoryStillReadOnly()
            Log.i(TAG, "SOURCE_SDK1 sameCueGenerated=1 sameCueStartCheck=1 duplicateAdditional=0 " +
                "queuedOldCueSubmitted=0 lateOldCueResult=revoked nonOptedInRecords=0 fencedWrites=0 " +
                "fieldAead=AndroidKeyStore ownNamespace=true ordinaryRawConsent=false ordinaryRawWrites=0 " +
                "engineCallbacks=controlled physicalStart=NOT_RUN mainOnCreate=NOT_RUN")
        } catch (failure: Throwable) {
            first = failure
            throw failure
        } finally {
            try { f.close() } catch (failure: Throwable) {
                if (first == null) throw failure else first.addSuppressed(failure)
            }
        }
    }

    private data class Cue(val update: RouteNavigatorUpdate, val compass: RouteCompassHeadingResult, val createdAtMs: Long)

    private class Fixture(val instrumentation: Instrumentation, val context: Context, val files: File) {
        val id = UUID.randomUUID().toString()
        val root = File(files, "route-cue-field-$id")
        val privacyRoot = File(context.noBackupFilesDir, "route-cue-privacy-$id")
        val preferencesName = "route_cue_field_$id"
        val keyPolicy = AeadKeyPolicy("route.cue.$id.v", 1, setOf(1))
        val aead = AndroidKeyStoreAead(keyPolicy)
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val runtime = RecordingRuntime()
        lateinit var main: MainActivity
        lateinit var navigator: RouteNavigator
        lateinit var actor: AndroidFeedbackActuator
        lateinit var log: PersistentFieldSessionLog
        lateinit var preferences: SharedPreferences
        var sessionId: String? = null
        private var gatewayGeneration: Long? = null
        private var ownedPositioningBinding: PositioningCollectionBinding? = null
        private var ownedLocationCallback: LocationCallback? = null
        private var activityLease: AccountDeletionActivityLease? = null
        private var collection: Any? = null
        private val workers = mutableListOf<Thread>()
        private var originalListener: ((SpeechOutputEvent) -> Unit)? = null

        fun prepare() {
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertTrue(BuildConfig.DEBUG)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue(requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertFalse(root.exists()); assertFalse(privacyRoot.exists())
            assertFalse(keys().containsAlias(keyPolicy.alias(1)))
            assertFalse(keys().containsAlias(keyPolicy.alias(1) + ".generation"))
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            assertTrue(preferences.all.isEmpty()) // This UUID preference namespace only.
            log = PersistentFieldSessionLog(root, FieldSessionDeviceInfo("fixture", "fixture", "fixture", "source-only"), aead = aead)
            onMain(instrumentation) {
                val component = ComponentName(context, MainActivity::class.java)
                @Suppress("DEPRECATION")
                main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                    context.applicationContext as Application, Intent().setComponent(component),
                    context.packageManager.getActivityInfo(component, 0), "Queued route Field Source1", null, null, null) as MainActivity
                navigator = field(main, "routeNavigator") as RouteNavigator
                preparePrivacyOwners()
                prepareTypedWalk()
                actor = AndroidFeedbackActuator(runtime = runtime,
                    speechAllowed = { call("authorizeActuatorSpeechForHomeWakeInput") as Boolean })
                MainActivityControllerTestFixture.replaceActuator(main, actor)
                originalListener = actor.speechOutputListener
                runtime.ready()
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) { assertEquals("tts=ready", actor.statusText()) }
            privacyWorker { call("initializeSpeechOutputCollectionOnPrivacyWorker") }
            collection = (field(main, "speechOutputCollection") as AtomicReference<*>).get()
            assertNotNull("Original Main worker factory reached", collection)
            assertTrue(Files.isSameFile((field(requireNotNull(collection), "filesDirectory") as File).toPath(), files.toPath()))
            onMain(instrumentation) { assertSame(actor, call("ensureFeedbackActuator")) }
            assertOrdinaryFactoryStillReadOnly()
        }

        private fun preparePrivacyOwners() {
            val coordinator = field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator
            assertFalse(coordinator.stateMachine.processingBlocked())
            activityLease = coordinator.attach()
            set(main, "accountDeletionActivityLease", requireNotNull(activityLease))
            set(main, "stepLengthPrefs", preferences)
            set(main, "fieldSessionLog", log)
            val fence = AccountDeletionIntentFence(
                read = { val values = preferences.all
                    if (!values.containsKey("account_deletion_intent_fence_v1")) false
                    else (values["account_deletion_intent_fence_v1"] as? Boolean)?.takeIf { it } },
                store = { preferences.edit().putBoolean("account_deletion_intent_fence_v1", true).commit() },
                clear = { preferences.edit().remove("account_deletion_intent_fence_v1").commit() })
            val fileOwner = FileAccountDeletionIntentAuthority(File(privacyRoot, "account_deletion_intent_authority"))
            val dual = AccountDeletionDualAuthority(fence, fileOwner)
            set(main, "accountDeletionIntentFence", fence)
            set(main, "accountDeletionPreparedConfirmationRecovery", fileOwner)
            set(main, "accountDeletionDualAuthority", dual)
            val reset = call("createAccountDeletionResetCoordinator", privacyRoot) as AccountDeletionResetCoordinator
            set(main, "accountDeletionResetCoordinator", reset)
            val startup = dual.startupState()
            val journal = reset.journalState()
            set(main, "accountDeletionAuthorityAtStartup", startup)
            set(main, "accountDeletionResetJournalStateAtStartup", journal)
            assertFalse(startup.blocksPrivacy)
            assertTrue(journal is AccountDeletionResetJournalState.Absent)
            val legacy = call("legacyAccountDeletionResetIntentState") as Enum<*>
            assertEquals("ABSENT", legacy.name)
            val spec = MainActivityControllerTestFixture.field("SENSITIVE_PREF_SPEC").apply { isAccessible = true }.get(null) as SensitivePreferenceSpec
            val sensitive = AndroidSensitivePreferenceStore(preferences, spec,
                initiallyBlockedForExternalReset = journal !is AccountDeletionResetJournalState.Absent || legacy.name != "ABSENT")
            set(main, "sensitivePrefs", sensitive)
            assertFalse(sensitive.isBlocked())
            call("readAccountDeletionFallbackMarkerAtStartup")
            assertFalse("Actual fixture fallback marker observation", field(main, "accountDeletionFallbackPresentAtStartup") as Boolean)
        }

        private fun prepareTypedWalk() {
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val wall = System.currentTimeMillis()
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=source_cue_$id", wall + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            val device = completedDeviceCheck(requireNotNull(gatewayGeneration))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val epoch = lifecycle.snapshot().epoch
            val collector = WalkSessionReadinessCollector(epoch, plan)
            plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(epoch, it,
                WalkSessionReadinessStatus.READY, "declared upstream SDK consumer fixture")) }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(SystemClock.elapsedRealtime())))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val start = requireNotNull(authority.beginStart(lifecycle.snapshot().epoch))
            val now = SystemClock.elapsedRealtime()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(start,
                GatewayWalkStartResult.Granted(GatewayWalkLease("started", ACTOR, session.deviceId,
                    lifecycle.snapshot().epoch.walkSessionId, "source-cue-lease-$id", 1L, wall, wall + 120_000L, wall, now + 120_000L))))
            set(main, "firstRunOnboardingSnapshot", onboarding)
            set(main, "postLoginDeviceCheckSnapshot", device)
            set(main, "walkSessionLifecycle", lifecycle)
            set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            set(main, "reporterUserId", ACTOR)
            set(main, "priorityUserOnboardingPolicy", completedEducation())
            set(main, "priorityUserOnboardingActorId", ACTOR)
            val capability = WalkSafeStartupCapabilityResolver.resolve(WalkSafeStartupCapabilityInput(
                true, true, true, true, true, true, true, true, true, "declared upstream SDK capability input"))
            assertTrue(capability.mayConfirmAndStart)
            set(main, "startupCapabilityDecision", capability)
            set(main, "confirmedStartupCapabilityDecision", capability)
            val homePanel = HomePanel(main,
                MainActivityControllerTestFixture.controller(main, "viewStyle") as WalkSafeViewStyle,
                HomePanel.Props(showCameraTest = BuildConfig.DEBUG, initialRouteStatus = ""),
                HomePanel.Actions(onCamera = {}, onVoice = {}, onDestinationSearch = {}, onSettings = {}))
            val homePresenter = field(main, "nativeHomeUiPresenter") as NativeHomeUiPresenter
            homePresenter.publishRouteTarget(homePanel)
            assertSame(homePanel.nativeHomeRouteStatusText, homePresenter.routeTarget())
            for (name in listOf("walkLastResultText", "navigationStatusText", "nativeHomeRouteStatusText", "statusText", "detailText")) {
                set(main, name, if (name == "nativeHomeRouteStatusText") homePresenter.routeTarget() else TextView(main))
            }
            val permissions = field(main, "permissionSessionPolicy") as PermissionSessionPolicy
            assertTrue(permissions.authenticated(ACTOR).mayUseProtectedServerFeature)
            assertFalse("RAW collection was not consented", permissions.snapshot().rawCollectionConsentGranted)
            assertNull((field(main, "integratedConsentSession") as IntegratedConsentSession).currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
            set(main, "officialEnvironmentUserConfirmation", OfficialEnvironmentUserConfirmation(lifecycle.snapshot().epoch,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS))
            refreshDeclaredEnvironment()
            assertEquals(true, call("activateOfficialEnvironmentRuntime"))
            assertEquals(true, call("navigationEnvironmentOutputsAllowed"))
            assertEquals(true, call("isWalkSessionRuntimeActive"))
            val owner = requireNotNull(call("currentLocationCollectionOwnerOrNull") as? LocationCollectionOwner)
            assertEquals(ACTOR, owner.actorId)
            assertSame(session, owner.gatewaySession)
            assertEquals(requireNotNull(gatewayGeneration), owner.gatewayGeneration)
            val positioning = MainActivityControllerTestFixture.controller(main, "positioningSessionController") as PositioningSessionController
            assertNull(positioning.binding); assertNull(positioning.collectionOwner); assertNull(positioning.callback)
            val binding = PositioningCollectionBinding(positioning.coordinator,
                positioning.coordinator.currentInputGeneration(), owner, positioning.callbackGeneration, SystemClock.elapsedRealtime())
            val callback = object : LocationCallback() {} // Controlled upstream transport; never registered with Fused.
            ownedPositioningBinding = binding
            ownedLocationCallback = callback
            set(main, "locationCollectionOwner", owner)
            set(main, "positioningCollectionBinding", binding)
            set(main, "locationCallback", callback)
            assertSame(binding, positioning.binding)
            assertTrue("Actual route-speech collection must be current", positioning.isPositioningCollectionCurrent(binding))
            assertTrue(lifecycle.isRuntimeEpochCurrent(epoch))
        }

        private fun refreshDeclaredEnvironment() {
            val profile = WalkSafeEnvironmentProfiles.testCandidate.cameraFrameQuality
            val epoch = lifecycle.snapshot().epoch
            val camera = CameraFrameQualityObservation(epoch, SystemClock.elapsedRealtime(), true,
                profile.minimumNormalizedBrightness, profile.maximumOccludedFraction, profile.maximumAngularShakeDegreesPerSecond,
                (profile.minimumMountPitchDegrees + profile.maximumMountPitchDegrees) / 2.0)
            assertNotNull(call("observeOfficialEnvironmentCameraFrame", camera, null, null))
            call("recordOfficialEnvironmentGpsObservation", epoch, SystemClock.elapsedRealtime(), true, 2.0)
            val assessment = call("currentOfficialEnvironmentAssessment", epoch, SystemClock.elapsedRealtime()) as OfficialEnvironmentAssessment
            assertTrue(assessment.usageLimitsAcknowledged)
            assertEquals(EnvironmentEvidenceStatus.PASS, assessment.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
            assertEquals(EnvironmentEvidenceStatus.PASS, assessment.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
            assertTrue(assessment.canStartWalk)
        }

        fun route(guide: Double) = WalkingRoute("SOURCE_CUE", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)), listOf(WalkingRouteGuidePoint(0, point(guide), "안내", guide.toInt(), (400 - guide).toInt(), turnType = 13)))

        fun newCue(guide: Double): Cue {
            assertNotEquals("Grace waiting must stay off the UI thread", Looper.getMainLooper(), Looper.myLooper())
            fun observeCurrentInput(): Cue {
                refreshDeclaredEnvironment()
                val binding = requireNotNull(ownedPositioningBinding)
                val positioning = MainActivityControllerTestFixture.controller(main, "positioningSessionController") as PositioningSessionController
                assertSame(binding, positioning.binding)
                assertTrue(positioning.isPositioningCollectionCurrent(binding))
                assertEquals(true, call("navigationEnvironmentOutputsAllowed"))
                assertEquals(true, call("isWalkSessionRuntimeActive"))
                val now = SystemClock.elapsedRealtime()
                // Preserve the original rejected sensor inputs and UNKNOWN facing on every fresh observation.
                val compass = RouteCompassHeading.evaluateFallback(
                    RouteCompassVectorSample(0.0, 9.80665, 0.0, now, EarthOrientationAccuracy.HIGH), null,
                    RouteCompassVectorSample(0.0, 40.0, -30.0, now, EarthOrientationAccuracy.HIGH),
                    ChestMountedGeomagneticReference(0.0, 53.13, 50.0), now)
                assertNull(compass.observation)
                assertNotNull(compass.checkEvidence)
                val location = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, now) }
                val update = navigator.update(location, now, false,
                    facingObservation = compass.observation, facingUnavailableReason = compass.reason)
                assertFalse(update.userDecisionRequired)
                return Cue(update, compass, now)
            }
            val (onset, deadline, spokenBefore) = onMain(instrumentation) {
                navigator.setRoute(route(guide))
                set(main, "isRouteActive", navigator.hasRoute()) // Upstream route installation, not a cue/token assignment.
                val first = observeCurrentInput()
                assertEquals("guidance_facing_recheck_wait", first.update.reason)
                assertNull(first.update.instruction); assertNull(first.update.speechCueToken)
                assertFalse(navigator.hasPendingInstruction())
                val grace = requireNotNull(navigator.currentDirectionGraceSnapshot())
                val onset = requireNotNull(grace.onsetElapsedRealtimeMs)
                val deadline = requireNotNull(grace.deadlineElapsedRealtimeMs)
                assertEquals(first.createdAtMs, onset)
                assertEquals(GuidanceRecoveryTiming.DIRECTION_GRACE_MS, deadline - onset)
                Triple(onset, deadline, runtime.spoken.size)
            }
            val bound = deadline + 750L
            var current: Cue? = null
            while (current == null && SystemClock.elapsedRealtime() < bound) {
                Thread.sleep(250L)
                current = onMain(instrumentation) {
                    val observed = observeCurrentInput()
                    assertTrue("Fresh cue observation must stay within the actual grace bound", observed.createdAtMs <= bound)
                    val grace = requireNotNull(navigator.currentDirectionGraceSnapshot())
                    assertEquals(onset, grace.onsetElapsedRealtimeMs)
                    assertEquals(deadline, grace.deadlineElapsedRealtimeMs)
                    assertEquals("No unrequested cue may reach the Actor while preparing an input", spokenBefore, runtime.spoken.size)
                    assertFalse(navigator.hasPendingInstruction())
                    if (observed.createdAtMs < deadline) {
                        assertEquals("guidance_facing_recheck_wait", observed.update.reason)
                        assertNull(observed.update.instruction); assertNull(observed.update.speechCueToken)
                        null
                    } else {
                        val update = observed.update
                        assertNotNull(update.instruction); assertNotNull(update.speechCueToken)
                        assertFalse(update.userDecisionRequired)
                        assertFalse(requireNotNull(update.speechCueToken).requiresFacingEvidence)
                        assertNull("No departure guide: no separate INFO submission", update.infoUtterance)
                        assertTrue(navigator.isInstructionCurrent(update, SystemClock.elapsedRealtime(), null))
                        observed
                    }
                }
            }
            return requireNotNull(current) { "Actual UNKNOWN grace produced no current cue within its bounded deadline" }
        }

        fun dispatchQueued(cue: Cue, invalidateBeforeUiDelivery: Boolean = false) {
            val returned = CountDownLatch(1)
            val problem = AtomicReference<Throwable?>()
            val epoch = onMain(instrumentation) { lifecycle.snapshot().epoch }
            val worker = Thread({
                try {
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    call("tryDispatchRouteInstruction", cue.update, epoch, cue.compass)
                } catch (failure: Throwable) { problem.set(failure) }
                finally { returned.countDown() }
            }, "route-cue-$id")
            workers += worker
            onMain(instrumentation) {
                val spokenBefore = runtime.spoken.size
                worker.start()
                // Briefly hold Main until the off-Main call returns. Its real runOnUiThread post
                // cannot run inline; no replacement scheduler or clock is installed.
                assertTrue("Actual off-Main Main call returned while UI delivery is queued", returned.await(5L, TimeUnit.SECONDS))
                problem.get()?.let { throw it }
                assertEquals("Main queued branch has not submitted yet", spokenBefore, runtime.spoken.size)
                if (invalidateBeforeUiDelivery) navigator.setRoute(route(230.0))
            }
            worker.join(5_000L)
            assertFalse("Own dispatch worker settled", worker.isAlive)
            problem.get()?.let { throw it }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) { call("cancelNavigationSpeechRetry"); assertSame(originalListener, actor.speechOutputListener) }
        }

        fun cueId(cue: Cue): String = requireNotNull(cue.update.speechCueToken).let { "${it.routeRevision}:${it.cueRevision}" }

        fun records(): List<JSONObject> {
            val directory = sessionId?.let { File(root, it) } ?: return emptyList()
            if (!directory.exists()) return emptyList()
            val limits = PersistentFieldSessionLog::class.java.getDeclaredField("RECORD_LIMITS").apply { isAccessible = true }.get(null) as AeadLimits
            val aadMethod = PersistentFieldSessionLog::class.java.getDeclaredMethod("recordAad", String::class.java,
                Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
            var ordinal = 0L
            return requireNotNull(directory.listFiles()).filter { it.name.matches(Regex("records-[0-9]{4}\\.jsonl")) }
                .sortedBy(File::getName).flatMap { file ->
                    assertFalse(Files.isSymbolicLink(file.toPath()))
                    val segment = file.name.removePrefix("records-").removeSuffix(".jsonl").toInt()
                    file.readLines().map { envelope ->
                        assertFalse(envelope.contains("route_guidance_generated"))
                        val aad = aadMethod.invoke(log, requireNotNull(sessionId), segment, ordinal++) as ByteArray
                        val opened = aead.open(envelope, aad, limits)
                        assertTrue("Actual AndroidKeyStore ciphertext opens: $opened", opened is AeadOpenResult.Opened)
                        val plaintext = (opened as AeadOpenResult.Opened).plaintext
                        try { JSONObject(String(plaintext, Charsets.UTF_8)) } finally { plaintext.fill(0) }
                    }
                }
        }

        fun events(cue: Cue, event: String) = records().filter { row ->
            row.optString("event_name") == event && row.getJSONObject("fields").optString("cue_id") == cueId(cue)
        }

        fun assertGenerated(cue: Cue, row: JSONObject) {
            val stored = row.getJSONObject("fields")
            val time = stored.getLong("elapsed_realtime_ms")
            assertTrue(time >= cue.createdAtMs)
            // Previously accepted mapper is the reference. Only Main dispatch/real Field
            // consumption is under this new SDK condition; no expected row is injected.
            val expected = RouteGuidanceDiagnostics.guidanceGenerated(cue.update, time, cue.compass).filterValues { it != null }
            assertEquals(expected.keys, stored.keys().asSequence().toSet())
            expected.forEach { (key, value) -> assertEquals(key, value.toString(), stored.get(key).toString()) }
            assertEquals(cue.compass.reason.name, stored.getString("compass_reason"))
            for (key in listOf("latitude", "longitude", "instruction", "voice_text", "sensor_vector")) assertFalse(stored.has(key))
        }

        fun assertOrdinaryFactoryStillReadOnly() {
            val provider = field(requireNotNull(collection), "provider") as FieldSpeechOutputStorageProvider
            val lazyAccess = field(provider, "access\$delegate") as Lazy<*>
            assertFalse("Ordinary RAW storage/key factory was never resolved", lazyAccess.isInitialized())
            assertSame(originalListener, actor.speechOutputListener)
            assertNull("No RAW receipt was published", (field(main, "integratedConsentSession") as IntegratedConsentSession)
                .currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
        }

        fun fileState(): Map<String, String> = root.walkTopDown().filter(File::isFile).associate {
            it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        }

        fun cancelPending() = onMain(instrumentation) { actor.cancelNavigationSpeech(); call("cancelNavigationSpeechRetry") }

        private fun privacyWorker(action: () -> Unit) {
            val coordinator = field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator
            val task = FutureTask(Callable { action() })
            coordinator.executor.execute(task)
            try { task.get(15L, TimeUnit.SECONDS) } catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
        }

        fun call(name: String, vararg values: Any?): Any? {
            val method = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == values.size }
            return try { method.apply { isAccessible = true }.invoke(main, *values) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }

        fun close() {
            var first: Throwable? = null
            var ownedTailsSettled = true
            fun cleanup(critical: Boolean = false, action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (critical) ownedTailsSettled = false
                if (first == null) first = failure else first!!.addSuppressed(failure)
            } }
            cleanup(critical = true) {
                workers.forEach { if (it.state != Thread.State.NEW) it.join(5_000L) }
                check(workers.none(Thread::isAlive)) { "Own dispatch still running: retain own files/keys" }
            }
            if (::main.isInitialized) {
                cleanup(critical = true) { onMain(instrumentation) {
                    call("cancelNavigationSpeechRetry")
                    if (::actor.isInitialized) actor.close()
                    ownedPositioningBinding?.let { binding ->
                        val positioning = MainActivityControllerTestFixture.controller(main, "positioningSessionController") as PositioningSessionController
                        assertTrue("Retire only this fixture's collection", positioning.binding == null || positioning.binding === binding)
                        if (positioning.binding === binding) {
                            assertSame(ownedLocationCallback, positioning.callback)
                            set(main, "locationCallback", null)
                            set(main, "positioningCollectionBinding", null)
                            set(main, "locationCollectionOwner", null)
                        }
                    }
                    ownedPositioningBinding = null
                    ownedLocationCallback = null
                    call("invalidateOfficialEnvironmentEvidence", "source_fixture_cleanup")
                    lifecycle.handle(WalkSessionEvent.EnteredBackground)
                    call("setActivityForeground", lifecycle.snapshot().isForeground)
                    runtime.close()
                } }
                cleanup(critical = true) {
                    instrumentation.waitForIdleSync()
                    privacyWorker { Unit }
                    // The original factory may have completed after a preparation timeout.
                    collection = (field(main, "speechOutputCollection") as AtomicReference<*>).get()
                }
                cleanup(critical = true) { collection?.let { check((field(it, "store") as ConsentBoundSpeechOutputStore)
                    .closeAndDrain().get(15L, TimeUnit.SECONDS)) { "Own privacy store did not drain" } } }
                cleanup { activityLease?.let { check((field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator).detach(it)) } }
                cleanup { gatewayGeneration?.let { check(GatewaySessionProcessCoordinator.clear(it) != null) } }
            }
            if (ownedTailsSettled) {
                cleanup(critical = true) { if (::log.isInitialized) { log.stop(); log.close() } }
            }
            if (ownedTailsSettled) {
                cleanup { if (::preferences.isInitialized) { check(preferences.edit().clear().commit()); check(context.deleteSharedPreferences(preferencesName)) } }
                cleanup { for (alias in listOf(keyPolicy.alias(1), keyPolicy.alias(1) + ".generation")) {
                    val store = keys(); if (store.containsAlias(alias)) store.deleteEntry(alias); check(!store.containsAlias(alias))
                } }
                cleanup { for (owned in listOf(root, privacyRoot)) { check(owned.name.endsWith(id)); if (owned.exists()) check(owned.deleteRecursively()) } }
            } else {
                cleanup { error("Cleanup failed: own UUID namespace retained; no shared/default purge") }
            }
            first?.let { throw it }
        }
    }

    /** Only engine effects/START/DONE are controlled; MainLooper/clock/listener validation are real. */
    private class RecordingRuntime : FeedbackSpeechRuntime {
        data class Spoken(val message: String, val id: String)
        private val handler = Handler(Looper.getMainLooper())
        val spoken = mutableListOf<Spoken>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val engineInitialized get() = initialized != null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            check(Looper.myLooper() == Looper.getMainLooper()); spoken += Spoken(message, utteranceId); return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone in NAV Field condition")
        fun ready() = requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
        fun start(id: String) = requireNotNull(listener).onStart(id)
        fun done(id: String) = requireNotNull(listener).onDone(id)
        fun close() { handler.removeCallbacksAndMessages(null) }
    }

    private companion object {
        const val TAG = "QueuedRouteFieldSource"
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val ACTOR = "33333333-3333-4333-8333-333333333333"
        fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
        fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun field(owner: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(owner)
        fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val task = FutureTask(Callable(action)); instrumentation.runOnMainSync(task)
            return try { task.get() } catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
        }
        fun receipt(n: Int) = FirstRunReceiptHash.fromSha256Hex(n.toString(16).padStart(64, '0'))
        fun completedOnboarding(): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            check(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            check(device.accepted && device.current.mayEnterWalk); return device.current
        }
        fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
        fun completedEducation() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS); reviewSafetyEducation(true); reviewPracticeNecessity(true)
            acceptEducationConsent(); acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
            confirmSafePracticePlace()
            PriorityUserPractice.entries.forEach { practice ->
                val token = requireNotNull(beginPractice(practice)); PriorityUserPracticeDeliverySignal.entries.forEach { recordPracticeDelivery(token, it) }
                if (practice == PriorityUserPractice.HAZARD_ALERT) confirmHazardResponse(token)
            }
            acceptAppUsageEducation(); check(snapshot().nativeEducationComplete)
        }
    }
}
