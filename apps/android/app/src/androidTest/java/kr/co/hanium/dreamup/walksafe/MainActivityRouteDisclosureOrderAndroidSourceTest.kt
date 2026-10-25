package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupInspectionController
import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotIoLane
import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import java.lang.reflect.InvocationTargetException
import java.security.KeyStore
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.HomePanel
import kr.co.hanium.dreamup.walksafe.ui.NativeHomeUiPresenter
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOURCE ONLY; not compiled or executed. Intended selector is the single method below.
 * Attached Android Main, its original requestRoute/response/UI closures and process I/O lane are
 * exercised without onCreate/onDestroy. Existing typed software readiness inputs are fixtures,
 * not physical capability findings. Walking/Gateway transport and speech engine are doubles.
 * Real Android clocks, Location consumer, Handler/View, SharedPreferences commit and own UUID
 * AndroidKeyStore AES-GCM are retained. No Fused subscription, real audio or provider HTTP occurs.
 * Real registered Tracker/current listener accepts declared raw RV software inputs; its policy
 * must produce VALID. The dedicated Tracker sensor queue alone is temporarily isolated so native
 * callbacks do not mix with declared input. SensorEvent reflective construction/registration is
 * an unexecuted SDK precondition. No heading, cue, guard or completion output is assigned.
 * A failure before original native write/qualified direction entry is preparation/ENV,
 * never a product ASSERT_RED. Physical sensor delivery and physical TTS are NOT_RUN.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityRouteDisclosureOrderAndroidSourceTest {
    @Test(timeout = 90_000L)
    fun actualRequestCompletesInstalledStartAnnouncementBeforeTheFirstDirectionalNavigationDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(Looper.myLooper() != Looper.getMainLooper())
        var fixture: Fixture? = null
        var primary: Throwable? = null
        try {
            val f = onMain(instrumentation) { Fixture(instrumentation) }.also { fixture = it }
            f.prepareOwnershipAndLocation()
            f.prepareDeclaredRotationCallbacks()
            assertEquals(DISCLOSURE, routeScopeDisclosureKo("STAIR_AVOID"))
            val requestOutputStart = onMain(instrumentation) {
                f.assertQualifiedFacing()
                val before = f.runtime.spoken.size
                f.requestThroughOriginalMain()
                before
            }
            awaitMain(instrumentation, "Original request/Store/UI completion and installed start announcement engine delivery") {
                f.throwFirstUpstreamFailure()
                !f.inFlight() && f.navigator.hasRoute() && f.storage.commitSucceeded.get() &&
                    f.runtime.spoken.any { it.message == START_ANNOUNCEMENT }
            }
            val owner = onMain(instrumentation) {
                assertEquals(1, f.walkingPosts.get())
                assertEquals(1, f.gatewayGets.get())
                assertTrue(f.storage.writeThreadWasOffMain.get())
                assertTrue(f.storage.envelopeChars.get() > 0)
                assertEquals(f.destination, field(f.main, "currentDestination"))
                assertEquals("길안내: 목적지까지의 경로가 설정되었습니다.", f.homeStatus.text.toString())
                assertEquals(START_ANNOUNCEMENT, f.runtime.spoken.drop(requestOutputStart).first().message)
                assertFalse("Installed start must not repeat the stair scope explanation",
                    f.runtime.spoken.drop(requestOutputStart).first().message.contains("계단"))
                assertEquals(1, f.runtime.spoken.count { it.message == START_ANNOUNCEMENT })
                assertEquals(0, f.navigationDeliveries().size)
                assertTrue(f.actuator.isAppSpeechActive())
                f.assertQualifiedFacing()
                f.ownerIdentity()
            }
            val startAnnouncementId = onMain(instrumentation) { f.runtime.spoken.single { it.message == START_ANNOUNCEMENT }.id }
            val interactionOutcomesBefore = onMain(instrumentation) {
                f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED } to
                    f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE }
            }
            onMain(instrumentation) { f.runtime.start(startAnnouncementId) }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                f.throwFirstUpstreamFailure()
                assertEquals(interactionOutcomesBefore.first + 1, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED })
                assertTrue(f.actuator.isAppSpeechActive())
                assertEquals(0, f.navigationDeliveries().size)
                assertEquals(owner, f.ownerIdentity())
                f.assertQualifiedFacing()
                // Production command completion schedules production navigation retry. No manual dispatch.
                f.runtime.done(startAnnouncementId)
            }
            awaitMain(instrumentation, "Original Main retry must deliver an actual directional NAV cue") {
                f.throwFirstUpstreamFailure()
                f.navigationDeliveries().isNotEmpty()
            }
            val (direction, cue) = onMain(instrumentation) {
                assertEquals(interactionOutcomesBefore.second + 1, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE })
                assertEquals(1, f.runtime.spoken.count { it.message == START_ANNOUNCEMENT })
                assertEquals(1, f.navigationDeliveries().size)
                assertEquals(owner, f.ownerIdentity())
                f.assertQualifiedFacing()
                val delivered = f.navigationDeliveries().single()
                val pending = requireNotNull(f.pendingCue())
                assertTrue("Overview/endpoint notice is not the required first direction", pending.requiresFacingEvidence)
                assertEquals(f.navigator.currentRouteId(), pending.routeId)
                assertEquals(pending, f.navigatorField("offeredSpeechCue"))
                assertTrue(delivered.message.contains(requireNotNull(f.navigatorField("offeredFacingMessage") as String?).trim()))
                assertTrue("Actual offered facing lease remains current", requireNotNull(pending.facingValidUntilElapsedRealtimeMs) >= SystemClock.elapsedRealtime())
                f.runtime.start(delivered.id)
                delivered to pending
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                f.throwFirstUpstreamFailure()
                assertEquals(owner, f.ownerIdentity())
                assertEquals(cue, f.pendingCue())
                assertEquals(1, f.events.count { it.kind == SpeechOutputKind.NAVIGATION && it.outcome == SpeechOutputOutcome.STARTED })
                f.runtime.done(direction.id)
            }
            awaitMain(instrumentation, "Actual Main terminal callback must ACK the same directional cue") {
                f.throwFirstUpstreamFailure()
                f.navigatorField("completedSpeechCue") == cue && f.pendingCue() == null
            }
            onMain(instrumentation) {
                assertEquals(owner, f.ownerIdentity())
                assertEquals(1, f.events.count { it.kind == SpeechOutputKind.NAVIGATION && it.outcome == SpeechOutputOutcome.DONE })
                assertEquals(1, f.runtime.spoken.count { it.message == direction.message })
                f.runtime.done(direction.id) // Duplicate terminal callback must not replay this direction.
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                f.throwFirstUpstreamFailure()
                assertEquals(owner, f.ownerIdentity())
                assertEquals(cue, f.navigatorField("completedSpeechCue"))
                assertEquals(1, f.runtime.spoken.count { it.message == direction.message })
                assertEquals(1, f.runtime.spoken.count { it.message == START_ANNOUNCEMENT })
            }
            val loaded = requireNotNull(f.lane.submit(f.lease, null as StoredRouteSnapshot?, { true }) {
                it.load(f.scope)
            }.get(WAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(f.destination, loaded.destination)
            assertEquals(f.scope, loaded.scope)
            assertTrue(f.preferencesFile.isFile)
            Log.i(TAG, "SOURCE_ONLY_EXECUTION_WINDOW installedStartBeforeFirstDirection=true noRepeatedScopeExplanation=true " +
                "directionCueRequiresFacing=true actualRequestAndNativeArchive=true " +
                "rawRvCallbacks=${f.rawCallbacks.get()} heading=original_policy_output " +
                "transport=DOUBLE speechEngine=DOUBLE physicalSensors=NOT_RUN physicalTts=NOT_RUN")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try { fixture?.close(primary) } catch (cleanup: Throwable) {
                if (primary != null) { if (cleanup !== primary) primary.addSuppressed(cleanup) } else throw cleanup
            }
        }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val preferencesName = "walksafe_route_disclosure_source_$id"
        val preferences: SharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val preferencesFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml")
        val keyPolicy = AeadKeyPolicy("s07.12.disclosure.$id.v", 1, setOf(1))
        val storage = NativePreferences(preferences)
        val main: MainActivity
        val status: TextView
        val homeStatus: TextView
        val runtime = RecordingSpeechRuntime()
        val events = mutableListOf<SpeechOutputEvent>()
        val actuator: AndroidFeedbackActuator
        val navigator: RouteNavigator
        val lane: RouteSnapshotIoLane
        lateinit var lease: RouteSnapshotIoLane.Lease
        lateinit var scope: RouteSnapshotScope
        val destination = point(1_000.0).copy(name = "source-only-destination")
        val walkingPosts = AtomicInteger()
        val gatewayGets = AtomicInteger()
        val responseBytes = AtomicInteger()
        private val firstTransportFailure = AtomicReference<Throwable?>()
        val rawCallbacks = AtomicInteger()
        private val firstRawFailure = AtomicReference<Throwable?>()
        private val rawHandler = Handler(Looper.getMainLooper())
        private var rawPulse: Runnable? = null
        private val sensorQueueEntered = CountDownLatch(1)
        private val sensorQueueRelease = CountDownLatch(1)
        private val sensorQueueExited = CountDownLatch(1)
        private var sensorQueuePosted = false
        private var ownedSensorThread: HandlerThread? = null
        private val tracker = AndroidEarthOrientationTracker(context)
        private val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        private var ownedGatewayGeneration: Long? = null
        private var deletionLease: AccountDeletionActivityLease? = null
        private var resources: AndroidWalkSessionResourceProbe? = null
        private val ownerClass = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner")
        private val bindingClass = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding")
        private var locationOwner: Any? = null
        private var binding: Any? = null
        private var locationGeneration = 0

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "S07-12 Disclosure Source SDK1",
                null, null, null) as MainActivity
            status = TextView(main)
            val homePanel = HomePanel(main,
                MainActivityControllerTestFixture.controller(main, "viewStyle") as WalkSafeViewStyle,
                HomePanel.Props(showCameraTest = BuildConfig.DEBUG, initialRouteStatus = ""),
                HomePanel.Actions(onCamera = {}, onVoice = {}, onDestinationSearch = {}, onSettings = {}))
            val homePresenter = field(main, "nativeHomeUiPresenter") as NativeHomeUiPresenter
            homePresenter.publishRouteTarget(homePanel)
            homeStatus = homePresenter.routeTarget()
            assertSame(homePanel.nativeHomeRouteStatusText, homeStatus)
            navigator = field(main, "routeNavigator") as RouteNavigator
            actuator = AndroidFeedbackActuator(runtime = runtime,
                speechAllowed = { call("authorizeActuatorSpeechForHomeWakeInput") as Boolean })
            actuator.speechOutputListener = { events += it }
            lane = MainActivityControllerTestFixture.field("ROUTE_SNAPSHOT_IO_LANE")
                .apply { isAccessible = true }.get(null) as RouteSnapshotIoLane
        }

        fun prepareOwnershipAndLocation() {
            // Real OS preconditions are asserted, not granted or replaced by fixture flags.
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertTrue(BuildConfig.WALKSAFE_POST_LOGIN_DEVICE_CHECK_REQUIRED)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue(requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertFalse(preferencesFile.exists())
            assertFalse(keyStore().containsAlias(keyPolicy.alias(1)))
            assertFalse(keyStore().containsAlias(keyPolicy.alias(1) + ".generation"))
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val latest = lane.javaClass.getDeclaredField("latest").apply { isAccessible = true }.get(lane) as AtomicReference<*>
            assertNull("PREPARATION: original process I/O lane must have no other active Activity", latest.get())

            onMain(instrumentation) { prepareTypedMain() }
            val attached = lane.attach {
                EncryptedRouteSnapshotStore(storage, AndroidKeyStoreAead(keyPolicy), Unit,
                    elapsedRealtimeMs = SystemClock::elapsedRealtime,
                    bootCount = { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT) })
                    .also { setField(main, "routeSnapshotStore", it) }
            }
            lease = attached.first
            assertTrue("PREPARATION: original attach/purge", attached.second.get(WAIT_SECONDS, TimeUnit.SECONDS)?.purged == true)
            onMain(instrumentation) {
                setField(main, "routeSnapshotIoLease", lease)
                call("submitRouteSnapshotOwnership", GatewaySessionProcessCoordinator.snapshot(), lifecycle.snapshot(), null)
            }
            awaitMain(instrumentation, "PREPARATION: original ownership UI completion") {
                field(main, "routeSnapshotPreparationPending") == false &&
                    field(main, "routeSnapshotAccountBindingEstablished") == true &&
                    field(main, "routeSnapshotWalkScope") == scope &&
                    field(main, "routeSnapshotPurgeFailed") == false
            }
            onMain(instrumentation) {
                assertEquals(scope.owner, lease.boundOwner)
                assertEquals(scope, lease.walkScope)
                assertTrue(lease.bound)
                prepareLocationBinding()
                deliverSdkLocation()
            }
            // Distinct SDK measurement time; orchestration timeout is not a latency criterion.
            instrumentation.waitForIdleSync()
            awaitNextSdkMillisecond(onMain(instrumentation) { latestFixTime() })
            onMain(instrumentation) {
                deliverSdkLocation()
                assertNotNull("PREPARATION: actual SDK consumer must produce a fresh trusted fix", call("currentRouteLocationOrNull"))
                assertEquals(true, call("currentNavigationCollectionAllowsWork"))
                assertEquals(false, call("currentPositionFieldCollectionAllowsWork"))
                assertEquals(true, call("routeSnapshotPurgeFenceAllowsRoute"))
            }
        }

        private fun prepareTypedMain() {
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            ownedGatewayGeneration = operation.generation
            val expires = System.currentTimeMillis() + 120_000L
            val account = BackendAccountDeviceCookieBinding(ACTOR, 1L, 1L, "source-sdk-device-$id", "source-sdk-session-$id", expires)
            val session = GatewayFieldSession.backendAccountDeviceSession("https://gateway.example.test", account,
                "walksafe_field_session=source_only_cookie_$id", expires)
            assertEquals(1L, session.backendAccountGeneration)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            val generation = GatewaySessionProcessCoordinator.snapshot().generation
            ownedGatewayGeneration = generation
            val device = completedDeviceCheck(generation)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val now = SystemClock.elapsedRealtime()
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val collector = WalkSessionReadinessCollector(epoch, plan)
            plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY,
                    "existing SDK typed software fixture input"))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val start = requireNotNull(authority.beginStart(epoch))
            val wall = System.currentTimeMillis()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(start,
                GatewayWalkStartResult.Granted(GatewayWalkLease("started", ACTOR, session.deviceId,
                    epoch.walkSessionId, "source-only-lease-$id", 1L, wall, wall + 60_000L, wall, now + 60_000L))))
            val confirmation = OfficialEnvironmentUserConfirmation(epoch, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS)
            val capability = WalkSafeStartupCapabilityResolver.resolve(WalkSafeStartupCapabilityInput(
                true, true, true, true, true, true, true, true, true, "existing SDK prepared-capability fixture"))
            assertTrue(capability.mayConfirmAndStart)
            assertEquals(WalkSafeStartupCapabilityTier.FULL, capability.tier)
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            val accountPolicy = completedPriorityEducation()
            assertNull(accountPolicy.accountBlockReason())
            assertTrue(accountPolicy.snapshot().nativeEducationComplete)
            setField(main, "priorityUserOnboardingPolicy", accountPolicy)
            setField(main, "priorityUserOnboardingActorId", ACTOR)
            setField(main, "reporterUserId", ACTOR)
            setField(main, "postLoginDeviceCheckSnapshot", device)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", true)
            setField(main, "startupCapabilityDecision", capability)
            setField(main, "confirmedStartupCapabilityDecision", capability)
            setField(main, "feedbackActuator", actuator)
            setField(main, "earthOrientationTracker", tracker)
            setField(main, "walkLastResultText", TextView(main))
            setField(main, "navigationStatusText", status)
            setField(main, "nativeHomeRouteStatusText", homeStatus)
            setField(main, "statusText", TextView(main))
            setField(main, "detailText", TextView(main))
            setField(main, "networkStateProbe", AndroidNetworkStateProbe(context))
            resources = AndroidWalkSessionResourceProbe(context).also { setField(main, "walkSessionResourceProbe", it) }
            assertEquals("PREPARATION: real Android resource observation", WalkSessionReadinessStatus.READY, resources!!.snapshot().readinessStatus)
            assertTrue((field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(ACTOR).mayUseProtectedServerFeature)
            val process = field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator
            assertFalse(process.stateMachine.processingBlocked())
            deletionLease = process.attach().also { setField(main, "accountDeletionActivityLease", it) }
            scope = RouteSnapshotScope(RouteSnapshotOwner(ACTOR, "account:1"), epoch.walkSessionId)
            val transport = object : WalkingRouteTransport {
                override fun post(url: String, body: String, session: GatewayFieldSession): String = captureTransportFailure {
                    assertTrue(url.endsWith("/api/navigation/walking"))
                    assertEquals(1L, session.backendAccountGeneration)
                    assertEquals(destination.latitude, JSONObject(body).getJSONObject("destination").getDouble("latitude"), 0.0)
                    walkingPosts.incrementAndGet()
                    longRouteResponse().also { responseBytes.set(it.toByteArray(Charsets.UTF_8).size) }
                }
                override fun get(url: String, session: GatewayFieldSession): String = captureTransportFailure { error("unexpected destination HTTP") }
            }
            setField(main, "walkingRouteClient", BackendWalkingRouteClient(transport))
            setField(main, "gatewaySessionClient", GatewayFieldSessionClient(object : GatewaySessionTransport {
                override fun postJson(url: String, body: String): GatewayHttpResponse = captureTransportFailure { error("unexpected Gateway POST") }
                override fun postJson(url: String, headers: Map<String, String>, body: String): GatewayHttpResponse = captureTransportFailure {
                    require(headers.isEmpty()) { "This GatewaySessionTransport does not support authenticated JSON POST" }
                    error("unexpected Gateway POST")
                }
                override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = captureTransportFailure {
                    assertTrue(url.endsWith("/api/field-session")); gatewayGets.incrementAndGet()
                    val observed = Instant.now()
                    GatewayHttpResponse(200, JSONObject().put("required", true).put("authenticated", true)
                        .put("actor_id", ACTOR).put("session_scope", "general")
                        .put("capacity", JSONObject().put("version", 1).put("observed_at", observed.toString())
                            .put("expires_at", observed.plusSeconds(60).toString()).put("level", "NORMAL")
                            .put("reason", "STORAGE_UTILIZATION")).toString())
                }
                override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = captureTransportFailure { error("unexpected Gateway DELETE") }
                override fun deleteJson(url: String, body: String): GatewayHttpResponse = captureTransportFailure { error("unexpected Gateway DELETE JSON") }
            }))
            runtime.ready()
            // Declared software inputs enter Main's original policy/record/activation path.
            // DEBUG's existing profile is asserted through the actual Main assessment, not replaced.
            assertTrue("PREPARATION: existing testCandidate selection requires DEBUG", BuildConfig.DEBUG)
            setField(main, "officialEnvironmentUserConfirmation", confirmation)
            recordDeclaredCameraInput()
            call("recordOfficialEnvironmentGpsObservation", epoch, SystemClock.elapsedRealtime(), true, 2.0)
            assertActualEnvironmentInputs()
            assertEquals(true, call("activateOfficialEnvironmentRuntime"))
            assertActualEnvironmentAdmission()
            assertEquals(true, call("isWalkSessionRuntimeActive"))
            assertEquals(ACTOR, call("currentReporterUserId"))
            assertEquals(session, call("gatewaySessionOrNull", "source-sdk-preparation", false))
            assertEquals(true, call("currentNavigationCollectionAllowsWork"))
            assertEquals(true, call("isGatewayNetworkAllowed", "walking_route", false))
        }

        private fun prepareLocationBinding() {
            val coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
            locationOwner = requireNotNull(call("currentLocationCollectionOwnerOrNull"))
            locationGeneration = field(main, "locationCallbackGeneration") as Int
            binding = bindingClass.getDeclaredConstructor(PositioningCoordinator::class.java, java.lang.Long.TYPE,
                ownerClass, Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(coordinator, coordinator.currentInputGeneration(), locationOwner, locationGeneration, SystemClock.elapsedRealtime())
            setField(main, "locationCollectionOwner", locationOwner)
            setField(main, "positioningCollectionBinding", binding)
            setField(main, "locationCallback", object : LocationCallback() {}) // Unregistered transport only.
            assertEquals(true, call("isPositioningCollectionCurrent", binding))
        }

        fun deliverSdkLocation() {
            // Refresh the declared camera input with the Android clock; real camera is NOT_RUN.
            recordDeclaredCameraInput()
            val location = Location("gps").apply {
                latitude = 37.0; longitude = 127.0; accuracy = 2f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                assertTrue(hasAccuracy()); assertFalse(isMock)
            }
            call("handleLocationUpdate", location, locationOwner, locationGeneration, binding, SystemClock.elapsedRealtime())
            assertActualEnvironmentAdmission()
            val gps = field(main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence
            assertEquals(TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos), gps.observedAtElapsedRealtimeMs)
        }
        private fun recordDeclaredCameraInput() {
            val profile = WalkSafeEnvironmentProfiles.testCandidate.cameraFrameQuality
            val observation = CameraFrameQualityObservation(
                epoch = lifecycle.snapshot().epoch,
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(), frameAvailable = true,
                normalizedBrightness = profile.minimumNormalizedBrightness,
                occludedFraction = profile.maximumOccludedFraction,
                angularShakeDegreesPerSecond = profile.maximumAngularShakeDegreesPerSecond,
                mountPitchDegrees = (profile.minimumMountPitchDegrees + profile.maximumMountPitchDegrees) / 2.0,
            )
            assertNotNull(call("observeOfficialEnvironmentCameraFrame", observation, null, null))
        }
        private fun assertActualEnvironmentInputs() {
            val assessed = call("currentOfficialEnvironmentAssessment", lifecycle.snapshot().epoch,
                SystemClock.elapsedRealtime()) as OfficialEnvironmentAssessment
            assertEquals(WalkSafeEnvironmentProfiles.testCandidate.officialEnvironment.profileId, assessed.profileId)
            assertTrue("PREPARATION: Main must consume completed education", assessed.usageLimitsAcknowledged)
            assertEquals(EnvironmentEvidenceStatus.PASS, assessed.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
            assertEquals(EnvironmentEvidenceStatus.PASS, assessed.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
            assertTrue("PREPARATION: Main's actual assessment", assessed.canStartWalk)
        }
        private fun assertActualEnvironmentAdmission() {
            assertActualEnvironmentInputs()
            val decision = (field(main, "officialEnvironmentRuntimeGuard") as OfficialEnvironmentRuntimeGuard).decision()
            assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
            assertTrue(decision.navigationOutputsAllowed)
            assertEquals(true, call("navigationEnvironmentOutputsAllowed"))
            assertEquals(true, call("currentNavigationCollectionAllowsWork"))
        }
        fun latestFixTime(): Long = (field(main, "latestTrustedLocation") as TrustedLocation).elapsedRealtimeMs
        fun inFlight(): Boolean = (field(main, "routeRequestInFlight") as AtomicBoolean).get()
        fun navigationDeliveries() = runtime.spoken.filter { it.id.startsWith("nav-") }
        fun navigatorField(name: String): Any? = RouteNavigator::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(navigator)
        fun pendingCue() = navigatorField("pendingSpeechCue") as RouteSpeechCueToken?
        fun ownerIdentity(): List<Any?> = listOf(lifecycle.snapshot().epoch,
            GatewaySessionProcessCoordinator.snapshot().generation,
            field(main, "routeRequestGeneration"), field(main, "feedbackLifecycleGeneration"),
            field(main, "locationCollectionOwner"), field(main, "positioningCollectionBinding"),
            navigator.currentRouteId(), scope, lease.boundOwner, lease.walkScope)
        fun throwFirstUpstreamFailure() {
            firstTransportFailure.get()?.let { throw it }
            firstRawFailure.get()?.let { throw it }
        }
        fun assertQualifiedFacing() {
            throwFirstUpstreamFailure()
            val result = call("currentRouteCompassHeadingResult", SystemClock.elapsedRealtime()) as RouteCompassHeadingResult
            assertEquals("PREPARATION: original raw-input policy must produce VALID", RouteCompassHeadingReason.VALID, result.reason)
            assertEquals(RouteFacingSource.ROTATION_VECTOR, requireNotNull(result.observation).source)
        }
        fun prepareDeclaredRotationCallbacks() {
            check(Looper.myLooper() != Looper.getMainLooper())
            // Main's original Location consumer has already started this real registered component.
            val (thread, listener, sensor, generation) = onMain(instrumentation) {
                synchronized(tracker) {
                    fun inputField(name: String): Any? = AndroidEarthOrientationTracker::class.java
                        .getDeclaredField(name).apply { isAccessible = true }.get(tracker)
                    assertEquals(true, inputField("started"))
                    assertTrue((inputField("registeredRouteStreams") as Set<*>).contains(RouteCompassStream.ROTATION_VECTOR))
                    arrayOf(requireNotNull(inputField("sensorThread")), requireNotNull(inputField("currentListener")),
                        requireNotNull(inputField("rotationVectorSensor")), inputField("registrationGeneration"))
                }
            }
            val sensorThread = thread as HandlerThread
            val currentListener = listener as SensorEventListener
            val rotationSensor = sensor as Sensor
            ownedSensorThread = sensorThread
            // Dedicated Tracker sensor queue only: Main/Actor/UI/FIFO lanes remain live.
            assertNotEquals(Looper.getMainLooper(), sensorThread.looper)
            assertTrue(Handler(sensorThread.looper).post {
                sensorQueueEntered.countDown()
                try {
                    // This wait owns fixture lifetime, not a sensor or speech latency budget.
                    // The test finally always releases it; entry/exit/join remain bounded separately.
                    sensorQueueRelease.await()
                } catch (failure: Throwable) {
                    if (!firstRawFailure.compareAndSet(null, failure)) firstRawFailure.get()?.addSuppressed(failure)
                } finally { sensorQueueExited.countDown() }
            })
            sensorQueuePosted = true
            assertTrue("PREPARATION: own sensor queue must be isolated before declared callbacks", sensorQueueEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            onMain(instrumentation) {
                val constructor = SensorEvent::class.java.getDeclaredConstructor(Integer.TYPE).apply { isAccessible = true }
                currentListener.onAccuracyChanged(rotationSensor, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
                val pulse = object : Runnable {
                    override fun run() {
                        if (rawPulse !== this) return
                        try {
                            synchronized(tracker) {
                                fun inputField(name: String): Any? = AndroidEarthOrientationTracker::class.java
                                    .getDeclaredField(name).apply { isAccessible = true }.get(tracker)
                                check(inputField("currentListener") === currentListener && inputField("registrationGeneration") == generation) {
                                    "PREPARATION: original registration retired; never substitute a listener/heading"
                                }
                                // SensorManager's quaternion conversion yields the north rear-axis matrix
                                // from RouteCompassSensorLifecycleTest.matrix(0.0), with its existing 5° error.
                                // Raw software input, not a claim of native SensorManager callback delivery.
                                val event = constructor.newInstance(5)
                                event.sensor = rotationSensor
                                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                                val halfRoot = kotlin.math.sqrt(0.5).toFloat()
                                event.values[0] = halfRoot; event.values[1] = 0f
                                event.values[2] = 0f; event.values[3] = halfRoot
                                event.values[4] = Math.toRadians(5.0).toFloat()
                                event.timestamp = SystemClock.elapsedRealtimeNanos()
                                currentListener.onSensorChanged(event)
                                rawCallbacks.incrementAndGet()
                            }
                            if (rawPulse === this) check(rawHandler.postDelayed(this, 100L))
                        } catch (failure: Throwable) {
                            if (!firstRawFailure.compareAndSet(null, failure)) firstRawFailure.get()?.addSuppressed(failure)
                        }
                    }
                }
                rawPulse = pulse
                pulse.run()
            }
            awaitMain(instrumentation, "PREPARATION: registered raw callback must produce actual Main facing") {
                throwFirstUpstreamFailure()
                rawCallbacks.get() >= 3 &&
                    (call("currentRouteCompassHeadingResult", SystemClock.elapsedRealtime()) as RouteCompassHeadingResult).reason == RouteCompassHeadingReason.VALID
            }
        }
        fun requestThroughOriginalMain() = call("requestRoute", destination, "source-sdk-first-route", false, null)
        private fun <T> captureTransportFailure(action: () -> T): T = try {
            action()
        } catch (failure: Throwable) {
            if (!firstTransportFailure.compareAndSet(null, failure)) {
                firstTransportFailure.get()?.takeIf { it !== failure }?.addSuppressed(failure)
            }
            // Main catches Exception; an assertion Error must not escape its worker uncaught.
            throw java.io.IOException("Source transport double failed", failure)
        }
        fun call(name: String, vararg values: Any?): Any? {
            val methods = MainActivity::class.java.declaredMethods.filter { it.name == name && it.parameterCount == values.size }
            check(methods.size == 1) { "SOURCE signature changed: $name/${values.size}" }
            return try { methods.single().apply { isAccessible = true }.invoke(main, *values) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun close(observedPrimary: Throwable?) {
            var first: Throwable? = null
            var archiveDrained = !::lease.isInitialized
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (first == null) first = failure else first!!.addSuppressed(failure)
                }
            }
            // Retire only this fixture's raw pump/owner; release the dedicated sensor queue on all paths.
            // The Main/UI/Actor/FIFO queues were never blocked.
            try { cleanup { onMain(instrumentation) {
                    rawPulse?.let(rawHandler::removeCallbacks)
                    rawPulse = null
                    setField(main, "isActivityForeground", false)
                    (MainActivityControllerTestFixture.field("privacyStartupInspectionController").apply { isAccessible = true }
                        .get(main) as PrivacyStartupInspectionController).destroy()
                    if (::lease.isInitialized) lane.invalidate(lease)
                    call("cancelNavigationSpeechRetry")
                    call("cancelEncryptedRouteSnapshotExpirySchedule")
                    (field(main, "navigationRequests") as AndroidNavigationRequestCoordinator)
                        .handle(AndroidNavigationRequestEvent.ACTIVITY_DESTROYED)
                    setField(main, "locationCallback", null)
                    setField(main, "positioningCollectionBinding", null)
                    setField(main, "locationCollectionOwner", null)
                    actuator.close()
                } }
                cleanup { onMain(instrumentation) { call("invalidateOfficialEnvironmentEvidence", "source-fixture-cleanup") } }
            } finally { sensorQueueRelease.countDown() }
            cleanup { onMain(instrumentation) { tracker.stop() } }
            if (sensorQueuePosted) cleanup {
                check(sensorQueueExited.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "own sensor queue did not settle" }
            }
            cleanup {
                ownedSensorThread?.let { thread ->
                    thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
                    check(!thread.isAlive) { "own sensor thread did not settle" }
                }
            }
            cleanup { firstRawFailure.get()?.takeUnless { it === observedPrimary }?.let { throw it } }
            cleanup { firstTransportFailure.get()?.takeUnless { it === observedPrimary }?.let { throw it } }
            if (::lease.isInitialized) cleanup {
                // All prior response/storage actions settle before destroying our key namespace.
                lane.submit(lease, false, { true }) { it.endWalk() }.get(WAIT_SECONDS, TimeUnit.SECONDS)
                lane.detach(lease)
                lane.submit(lease, false, { false }) { true }.get(WAIT_SECONDS, TimeUnit.SECONDS)
                archiveDrained = true
            }
            cleanup {
                (field(main, "routeExecutor") as java.util.concurrent.ExecutorService).apply {
                    shutdown()
                    check(awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) { "own Main route executor did not settle" }
                }
            }
            cleanup { resources?.close() }
            cleanup { deletionLease?.let { (field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator).detach(it) } }
            cleanup { ownedGatewayGeneration?.let { check(GatewaySessionProcessCoordinator.clear(it) != null) } }
            if (archiveDrained) {
                cleanup { check(preferences.edit().clear().commit()) }
                cleanup { check(context.deleteSharedPreferences(preferencesName)) }
                cleanup { val keys = keyStore(); listOf(keyPolicy.alias(1), keyPolicy.alias(1) + ".generation").forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) } }
            } else cleanup { error("own archive tail is unsettled; retain own UUID namespace rather than race I/O") }
            first?.let { throw it }
        }
    }

    private class NativePreferences(private val preferences: SharedPreferences) : RouteSnapshotStorage {
        val commitSucceeded = AtomicBoolean()
        val writeThreadWasOffMain = AtomicBoolean()
        val envelopeChars = AtomicInteger()
        override fun read(): String? = preferences.getString(SNAPSHOT_KEY, null)
        override fun clear(): Boolean = preferences.edit().remove(SNAPSHOT_KEY).commit()
        override fun write(envelope: String): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper())
            writeThreadWasOffMain.set(true)
            envelopeChars.set(envelope.length)
            check(JSONObject(envelope).getString("ciphertext").isNotEmpty())
            return preferences.edit().putString(SNAPSHOT_KEY, envelope).commit().also(commitSucceeded::set)
        }
    }

    private class RecordingSpeechRuntime : FeedbackSpeechRuntime {
        data class Spoken(val message: String, val id: String)
        private val handler = Handler(Looper.getMainLooper())
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        val spoken = mutableListOf<Spoken>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int { spoken += Spoken(message, utteranceId); return TextToSpeech.SUCCESS }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("no progress tone in SOURCE SDK1")
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS) }
        fun start(id: String) { requireNotNull(listener).onStart(id) }
        fun done(id: String) { requireNotNull(listener).onDone(id) }
    }

    companion object {
        private const val TAG = "RouteDisclosureSource1"
        private const val ACTOR = "11111111-1111-4111-8111-111111111111"
        private const val DISCLOSURE = "계단을 피하는 경로입니다. 보도 상태나 공사 같은 다른 접근성 정보는 확인되지 않았습니다."
        private const val START_ANNOUNCEMENT = "source-only-destination까지 안내를 시작합니다. 경로 거리는 약 1000미터입니다."
        private const val SNAPSHOT_KEY = "encrypted_route_snapshot_v1"
        private const val POINT_COUNT = 1_001
        private const val WAIT_SECONDS = 10L // Test orchestration bound, not a product latency criterion.
        private fun point(northM: Double) = RoutePoint(37.0 + northM / 111_194.92664455874, 127.0)
        private fun pointJson(value: RoutePoint) = JSONObject().put("latitude", value.latitude).put("longitude", value.longitude)
        private fun longRouteResponse(): String {
            val points = JSONArray().apply { repeat(POINT_COUNT) { put(pointJson(point(it.toDouble()))) } }
            return JSONObject().put("schema_version", "walksafe.walking_route.v1")
                .put("provider", "tmap_pedestrian").put("provider_result_code", 0)
                .put("priority", "STAIR_AVOID").put("provider_route_id", "source-only-long-route")
                .put("distance_source", "provider_total_geometry_scaled")
                .put("summary", JSONObject().put("distance_m", 1_000).put("duration_s", 1_000))
                .put("polyline", points)
                .put("steps", JSONArray().put(JSONObject().put("index", 0).put("distance_m", 1_000)
                    .put("duration_s", 1_000).put("points", points).put("road_name", "source-only-road")
                    .put("instruction", "직진").put("road_type", 16)))
                .put("guide_points", JSONArray()
                    .put(JSONObject().put("index", 0).put("point", pointJson(point(0.0))).put("instruction", "출발")
                        .put("distance_from_start_m", 0).put("remaining_distance_m", 1_000).put("turn_type", 200).put("point_type", "SP"))
                    .put(JSONObject().put("index", 1).put("point", pointJson(point(1_000.0))).put("instruction", "도착")
                        .put("distance_from_start_m", 1_000).put("remaining_distance_m", 0).put("turn_type", 201).put("point_type", "EP")))
                .toString()
        }
        private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        private fun setField(main: MainActivity, name: String, value: Any?) = when (name) {
            "feedbackActuator" -> MainActivityControllerTestFixture.replaceActuator(main, value as kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator?)
            "postLoginDeviceCheckSnapshot" -> MainActivityControllerTestFixture.publishDeviceSnapshot(main, value as kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot)
            else -> MainActivityControllerTestFixture.field(name).set(main, value)
        }
        private fun <T> onMain(instrumentation: Instrumentation, block: () -> T): T {
            val task = FutureTask(Callable(block)); instrumentation.runOnMainSync(task)
            return try { task.get() } catch (failure: java.util.concurrent.ExecutionException) { throw requireNotNull(failure.cause) }
        }
        private fun awaitMain(instrumentation: Instrumentation, label: String, condition: () -> Boolean) {
            check(Looper.myLooper() != Looper.getMainLooper())
            val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS)
            while (!onMain(instrumentation, condition)) {
                check(SystemClock.elapsedRealtime() < deadline) { label }
                SystemClock.sleep(10L)
            }
        }
        private fun awaitNextSdkMillisecond(previous: Long) {
            check(Looper.myLooper() != Looper.getMainLooper())
            while (SystemClock.elapsedRealtime() <= previous) SystemClock.sleep(1L)
        }
        private fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
        private fun completedPriorityEducation() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true)
            reviewPracticeNecessity(true)
            acceptEducationConsent()
            acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
            confirmSafePracticePlace()
            PriorityUserPractice.entries.forEach { practice ->
                val token = requireNotNull(beginPractice(practice))
                PriorityUserPracticeDeliverySignal.entries.forEach { recordPracticeDelivery(token, it) }
                if (practice == PriorityUserPractice.HAZARD_ALERT) confirmHazardResponse(token)
            }
            acceptAppUsageEducation()
            check(snapshot().nativeEducationComplete)
        }
        private fun completedOnboarding(): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            check(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            check(device.accepted && device.current.mayEnterWalk)
            return device.current
        }
        private fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
    }
}
