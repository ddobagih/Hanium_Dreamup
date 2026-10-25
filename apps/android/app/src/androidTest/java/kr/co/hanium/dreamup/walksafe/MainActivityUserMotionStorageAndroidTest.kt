package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.TaskCompletionSource
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.fieldlog.*
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Main's existing storage consumers with real UUID files/preferences and native AEAD. Login,
 * readiness and step inputs are controlled typed inputs; Fused is a transport double. No sensor
 * registration success, account purge, RAW recovery, HTTP, microphone or physical walk is claimed.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUserMotionStorageAndroidTest {
    @Test
    fun ownedStepProfileAndLateMotionAreStoredBeforeEndRejectsTheOldInputs() = withFixture { f ->
        val captured = f.recordOwnedStepAndLateSnapshot()
        val expectedProfile = onMain(f.instrumentation) { f.restoreAndSubmitProfile() }
        f.awaitOwnedProfileCommit()
        onMain(f.instrumentation) {
            assertEquals(expectedProfile, f.profileStore.load(f.actorId))
            assertFalse(f.profilePreferences.all.containsKey(f.logicalProfileKey(f.actorId)))
            assertNotNull(f.profilePreferences.getString(f.profileSpec.storageKey, null))
            // End the controlled authority locally before Main's transition; no HTTP end is submitted.
            assertNotNull(f.authority.beginEnd(f.lifecycle.snapshot().epoch))
            val transition = f.invoke("transitionWalkSession", WalkSessionTransition::class.java,
                arrayOf(WalkSessionEvent::class.java), WalkSessionEvent.EndRequested) as WalkSessionTransition
            assertEquals(WalkSessionState.ENDED, transition.current.state)
            assertNull(f.lifecycle.currentRuntimeEpochOrNull())
            assertEquals(false, f.invoke("isWalkSessionRuntimeActive", java.lang.Boolean.TYPE))
            assertEquals(true, f.invoke("stopActivePositionFieldSession", java.lang.Boolean.TYPE))
            assertNull(field(f.positionController, "recording"))
            assertFalse(f.currentFieldAdmission())
            // GENERAL GPS may remain after END. Old walk inputs must still fail their motion/lease fences.
            val afterEnd = f.checkpoint()
            f.deliverStep(captured.event, captured.binding, captured.motionOwner, captured.receivedAtMs)
            f.apply(captured.snapshot, captured.binding)
            assertEquals("Retired walk input must not regenerate a trace, late record or motion state",
                afterEnd, f.checkpoint())
        }
        f.assertFreshAuthenticatedProfileReadback(f.actorId, expectedProfile)
    }

    @Test
    fun aRetiredGeneralOwnerCannotWriteAndAFreshBoundOwnerIsAccepted() = withFixture { f ->
        val old = f.recordOwnedStepAndLateSnapshot()
        val oldCallback = onMain(f.instrumentation) {
            assertTrue(f.bindingIsCurrent(old.binding))
            requireNotNull(field(f.main, "locationCallback")) as LocationCallback
        }
        onMain(f.instrumentation) {
            assertEquals(true, f.invoke("stopActivePositionFieldSession", java.lang.Boolean.TYPE))
            f.replaceGatewayOwner(NEW_ACTOR)
            f.startBindingAndRecorder()
            assertNotSame(old.binding, f.binding)
            assertFalse(f.bindingIsCurrent(old.binding))
            assertTrue(f.bindingIsCurrent(f.binding))
            assertNotEquals(old.motionOwner, f.motionOwner)
            val before = f.checkpoint()
            f.deliverStep(old.event, old.binding, old.motionOwner, old.receivedAtMs)
            f.apply(old.snapshot, old.binding)
            oldCallback.onLocationResult(LocationResult.create(listOf(Location("gps").apply {
                latitude = 37.0; longitude = 127.0; accuracy = 2f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })))
            assertEquals("Old captured step/snapshot/Fused callback must preserve the new owner and storage",
                before, f.checkpoint())
        }
        val accepted = f.recordOwnedStepAndLateSnapshot()
        onMain(f.instrumentation) {
            assertSame(accepted.event, field(f.main, "latestWalkingCalibrationStep"))
            assertTrue(f.bindingIsCurrent(accepted.binding))
            assertEquals(NEW_ACTOR, f.invoke("currentReporterUserId", String::class.java))
        }
        val expectedProfile = onMain(f.instrumentation) { f.restoreAndSubmitProfile() }
        f.awaitOwnedProfileCommit()
        f.assertFreshAuthenticatedProfileReadback(NEW_ACTOR, expectedProfile)
    }

    private data class CapturedInput(val event: StepEvent, val binding: Any,
        val motionOwner: UserMotionStateOwner, val receivedAtMs: Long, val snapshot: PositioningSnapshot)
    private data class Checkpoint(val binding: Any?, val callback: Any?, val locationOwner: Any?,
        val locationGeneration: Int, val motionOwner: UserMotionStateOwner?, val motionStepAt: Long?, val step: Any?,
        val stationary: Any?, val stationarySince: Any?, val trusted: Any?, val raw: Any?,
        val gnssHealth: Any?, val stepTracker: Any?, val motionTracker: Any?, val healthStatus: String,
        val profile: PedestrianMotionProfileSnapshot, val traceCounts: List<Pair<String, Long>>,
        val lateRecords: List<String>, val ownedFiles: List<Pair<String, String>>, val profileEnvelope: String?,
        val positionRecording: Any?, val positionScopeId: String?)

    /** Existing profile port delegates every read/commit to the actual encrypted preference store. */
    private class NativeProfileStorage(val native: AndroidSensitivePreferenceStore) : PedestrianProfileStorage {
        val successfulCommits = AtomicInteger()
        override fun isBlocked() = native.isBlocked()
        override fun contains(key: String) = native.contains(key)
        override fun getString(key: String) = native.getString(key, null)
        override fun putString(key: String, value: String): Boolean =
            native.edit().putString(key, value).commit().also { if (it) successfulCommits.incrementAndGet() }
        override fun remove(key: String) = native.edit().remove(key).commit()
        override fun failClosed() = native.failClosed()
        override fun resetAll(): Boolean = throw AssertionError("This fixture must not call account/profile reset")
    }

    /** Main creates the real request/callback/binding; a pending registration is not a location receipt. */
    private class FusedTransport {
        val callbacks = mutableListOf<LocationCallback>()
        val client = Proxy.newProxyInstance(FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java)) { proxy, method, arguments ->
            check(Looper.myLooper() == Looper.getMainLooper())
            when (method.name) {
                "requestLocationUpdates" -> {
                    check(arguments != null && arguments.size == 3 && arguments[0] is LocationRequest &&
                        arguments[1] is LocationCallback && arguments[2] === Looper.getMainLooper())
                    callbacks += arguments[1] as LocationCallback
                    TaskCompletionSource<Void>().task
                }
                "removeLocationUpdates" -> TaskCompletionSource<Void>().also { it.setResult(null) }.task
                "toString" -> "ControlledUserMotionFusedTransport"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw AssertionError("Unexpected Fused call: ${method.name}")
            }
        } as FusedLocationProviderClient
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir, "user-motion-storage-fixture-$id")
        val profilePrefsName = "user-motion-profile-fixture-$id"
        val stepPrefsName = "user-motion-step-fixture-$id"
        private val positionScopePrefsName = "user-motion-position-scope-fixture-$id"
        private val positionScopeContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(positionScopePrefsName, mode)
        }
        val main: MainActivity
        val transport = FusedTransport()
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val renewalHandler = Handler(Looper.getMainLooper())
        private var gatewayGeneration: Long? = null
        private var authorityEpoch: WalkRuntimeEpoch? = null
        var actorId = ACTOR
            private set
        lateinit var recorder: PositionFieldSessionRecorder
        lateinit var recorderBinding: RecorderBinding
        private var recorderBindings = mutableListOf<RecorderBinding>()
        lateinit var fieldLog: PersistentFieldSessionLog
        private var fieldSessionId: String? = null
        lateinit var profileSpec: SensitivePreferenceSpec
        lateinit var profileStorage: NativeProfileStorage
        lateinit var profileStore: AndroidPedestrianProfileStore
        private var readbackOwner: AndroidSensitivePreferenceStore? = null
        private val ownedAead = mutableListOf<LocalAead>()
        private val submittedActors = mutableSetOf<String>()
        private var expectedCommitCount = 0
        private var profileWritesDrained = false
        val profilePreferences get() = context.getSharedPreferences(profilePrefsName, 0)
        val positionController get() = (field(main, "positionFieldController\$delegate") as Lazy<*>).value
            as PositionFieldRecorderController
        val positionScopeStorage get() = field(positionController, "scopeStorage") as PositionFieldScopeStorage
        val coordinator get() = field(main, "positioningCoordinator") as PositioningCoordinator
        val authority get() = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
        val binding get() = requireNotNull(field(main, "positioningCollectionBinding"))
        val motionOwner get() = requireNotNull(coordinator.currentUserMotionOwner())
        private val bindingClass = kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding::class.java

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main user-motion storage probe",
                null, null, null) as MainActivity
        }

        fun prepare() {
            assertTrue(BuildConfig.DEBUG)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertEquals("No ACTIVITY_RECOGNITION grant or sensor success is supplied",
                PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION))
            assertTrue(requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            assertFalse(root.exists())
            assertTrue(root.mkdirs())
            profileSpec = AndroidPedestrianProfileStore::class.java.getDeclaredField("PROFILE_PREFERENCE_SPEC")
                .apply { isAccessible = true }.get(null) as SensitivePreferenceSpec
            profileSpec = profileSpec.copy(keyPolicy = keyPolicy("profile"))
            profileStorage = NativeProfileStorage(AndroidSensitivePreferenceStore(profilePreferences,
                profileSpec, ownAead(profileSpec.keyPolicy)))
            profileStore = AndroidPedestrianProfileStore(profileStorage)
            assertFalse(profileStorage.isBlocked())
            recorder = PositionFieldSessionRecorder(File(root, "no-backup"), ownAead(keyPolicy("position")),
                SystemClock::elapsedRealtimeNanos)
            fieldLog = PersistentFieldSessionLog(File(root, "field"),
                FieldSessionDeviceInfo("controlled_native_fixture", Build.VERSION.SDK_INT.toString(),
                    "fixture", "frozen-Main9f71"), aead = ownAead(keyPolicy("field")))
            fieldSessionId = requireNotNull(fieldLog.startAfterUserConfirmation())
            setField(main, "stepLengthPrefs", context.getSharedPreferences(stepPrefsName, 0))
            setField(main, "pedestrianProfileStore", profileStore)
            setOwnerField(positionController, "scopeStorage", AndroidPositionFieldScopeStorage(positionScopeContext))
            val recorderStore = PositionFieldSessionRecorderStore(recorder)
            setOwnerField(positionController, "recorderFactory", { recorderStore })
            positionController.initialize()
            assertFalse(positionController.storageBlocked)
            assertSame(recorderStore, field(positionController, "recorder"))
            assertSame(recorder, field(recorderStore, "recorder"))
            assertEquals(positionScopeStorage.localScopeId(), field(positionController, "localScopeId"))
            setField(main, "fieldSessionLog", fieldLog)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "gatewayWalkRenewalHandler", renewalHandler)
            setField(main, "statusText", TextView(context))
            setField(main, "detailText", TextView(context))
            setField(main, "fusedLocationClient", transport.client)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            publishGatewayOwner(actorId)
            activateOwnedWalk()
            assertEquals(true, invoke("isWalkSessionRuntimeActive", java.lang.Boolean.TYPE))
            assertEquals(actorId, invoke("currentReporterUserId", String::class.java))
            assertNull((field(main, "speechOutputCollection") as AtomicReference<*>).get())
            startBindingAndRecorder()
        }

        private fun keyPolicy(purpose: String) = AeadKeyPolicy(
            "walksafe.fixture.motion.${id.replace("-", "")}.$purpose.v", 1, setOf(1))
        private fun ownAead(policy: AeadKeyPolicy): LocalAead = AndroidKeyStoreAead(policy).also(ownedAead::add)

        private fun publishGatewayOwner(actor: String) {
            val onboarding = completedOnboarding(actor)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
                "walksafe_field_session=synthetic_motion_storage_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(actor, requireNotNull(gatewayGeneration)))
            setField(main, "reporterUserId", actor)
            setField(main, "priorityUserOnboardingActorId", actor)
            setField(main, "priorityUserOnboardingPolicy", readyPriorityPolicy())
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(actor)
        }

        private fun activateOwnedWalk() {
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val epoch = lifecycle.snapshot().epoch
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(epoch, it,
                    WalkSessionReadinessStatus.READY, "controlled typed motion-storage readiness")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            val activeEpoch = lifecycle.snapshot().epoch
            val start = requireNotNull(authority.beginStart(activeEpoch))
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            val wall = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "started", actorId, session.deviceId, activeEpoch.walkSessionId, "synthetic-motion-storage-lease",
                    1L, wall, wall + 120_000L, wall, elapsed + 120_000L))))
            authorityEpoch = activeEpoch
        }

        fun startBindingAndRecorder() {
            // The collector sees no field lease yet, and normal navigation prerequisites remain unavailable.
            invoke("startLocationUpdatesIfAllowed", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), false)
            assertTrue(bindingIsCurrent(binding))
            assertEquals(false, invoke("currentNavigationCollectionAllowsWork", java.lang.Boolean.TYPE))
            assertNull(field(main, "pedestrianMotionTracker"))
            val epoch = requireNotNull(lifecycle.currentRuntimeEpochOrNull())
            val scopeId = UUID.randomUUID().toString()
            // Same UUID/native scope fixture boundary, now durably owned by the current adapter.
            assertTrue(positionScopeStorage.storeScope(scopeId, clearPurgePending = false))
            assertEquals(scopeId, positionScopeStorage.localScopeId())
            setOwnerField(positionController, "localScopeId", scopeId)
            assertTrue(positionController.start(PositionFieldStartRequest(
                routeId = UUID.randomUUID().toString(), exactCoordinateExportConfirmed = true,
                chestCalibrationConfirmed = true, deviceModel = "controlled_native_fixture",
                androidApi = Build.VERSION.SDK_INT,
            )) { invoke("currentPositionFieldAuthority", FieldRecordAuthority::class.java) as FieldRecordAuthority })
            val recording = requireNotNull(field(positionController, "recording"))
            val lease = field(recording, "lease") as PositionFieldSessionLease
            recorderBinding = RecorderBinding(scopeId, epoch.recoveryGeneration)
            recorderBindings += recorderBinding
            assertEquals(epoch, field(recording, "walkEpoch"))
            assertEquals(GatewaySessionProcessCoordinator.snapshot().generation, field(recording, "gatewayGeneration"))
            assertEquals(scopeId, field(recording, "localScopeId"))
            assertTrue(positionController.explicitChestConfirmed)
            assertTrue(currentFieldAdmission())
            assertSame(lease, invoke("currentPositionFieldLeaseOrNull", PositionFieldSessionLease::class.java))
        }

        fun replaceGatewayOwner(nextActor: String) {
            assertNotNull(GatewaySessionProcessCoordinator.clear(requireNotNull(gatewayGeneration)))
            gatewayGeneration = null
            actorId = nextActor
            publishGatewayOwner(nextActor)
            // A fresh controlled server lease is bound through the real controller, not a Boolean field.
            authorityEpoch?.let(authority::invalidate)
            val epoch = lifecycle.snapshot().epoch
            val start = requireNotNull(authority.beginStart(epoch))
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            val wall = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "started", nextActor, session.deviceId, epoch.walkSessionId, "synthetic-motion-replacement-lease",
                    1L, wall, wall + 120_000L, wall, elapsed + 120_000L))))
            authorityEpoch = epoch
        }

        fun recordOwnedStepAndLateSnapshot(): CapturedInput {
            val capturedBinding = onMain(instrumentation) { assertTrue(bindingIsCurrent(binding)); binding }
            val capturedOwner = onMain(instrumentation) { motionOwner }
            val initializedAt = afterClock(onMain(instrumentation) {
                field(capturedBinding, "startedAtElapsedRealtimeMs") as Long
            })
            onMain(instrumentation) { apply(coordinator.observeGnss(
                GnssPositionObservation(37.0, 127.0, 2.0, initializedAt), coordinator.currentInputGeneration()), capturedBinding) }
            val measuredNs = afterClockNanos(initializedAt)
            val laterFixAt = afterClock(measuredNs / 1_000_000L)
            onMain(instrumentation) { apply(coordinator.observeGnss(
                GnssPositionObservation(37.0, 127.0, 2.0, laterFixAt), coordinator.currentInputGeneration()), capturedBinding) }
            val receivedNs = SystemClock.elapsedRealtimeNanos()
            val event = StepEvent(1, measuredNs / 1_000_000L, StepEventSource.STEP_DETECTOR,
                StepEventConfidence.HIGH, measuredNs, receivedNs)
            onMain(instrumentation) {
                assertTrue(currentFieldAdmission())
                val countBefore = traceCount(recorderBinding)
                val lateBefore = lateRecords().size
                deliverStep(event, capturedBinding, capturedOwner, receivedNs / 1_000_000L)
                assertSame(event, field(main, "latestWalkingCalibrationStep"))
                assertEquals(event.timestampMs, coordinator.peekUserMotion(receivedNs / 1_000_000L).stepObservedAtElapsedRealtimeMs)
                assertEquals(countBefore + 1L, traceCount(recorderBinding))
                assertEquals(lateBefore + 1, lateRecords().size)
                assertNull(event.producerRegistrationGeneration)
                assertEquals(measuredNs, event.measurementElapsedRealtimeNanos)
                assertEquals(receivedNs, event.receivedAtElapsedRealtimeNanos)
            }
            val nextMeasuredNs = afterClockNanos(laterFixAt)
            val nextFixAt = afterClock(nextMeasuredNs / 1_000_000L)
            return onMain(instrumentation) {
                apply(coordinator.observeGnss(GnssPositionObservation(37.0, 127.0, 2.0, nextFixAt),
                    coordinator.currentInputGeneration()), capturedBinding)
                val received = SystemClock.elapsedRealtimeNanos()
                val original = StepEvent(1, nextMeasuredNs / 1_000_000L, StepEventSource.STEP_DETECTOR,
                    StepEventConfidence.HIGH, nextMeasuredNs, received)
                val snapshot = coordinator.observeStep(1, PedestrianHeadingInput(original.timestampMs,
                    speed = null, gpsCourse = null, magneticTrueHeading = null, phoneForwardMounted = false), PdrStepQuality.HIGH,
                    receivedAtElapsedRealtimeMs = received / 1_000_000L, expectedGeneration = coordinator.currentInputGeneration(),
                    expectedMotionOwner = capturedOwner, stepEventSource = original.source,
                    stepEventConfidence = original.confidence, originalStepEvent = original)
                val fields = requireNotNull(snapshot.motionUpdate?.lateMotionEventFieldsOrNull())
                assertTrue((fields["measurement_lag_ms"] as Number).toLong() > 0L)
                assertNotEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, snapshot.motionUpdate?.disposition)
                assertEquals(capturedOwner, snapshot.userMotion?.owner)
                assertEquals(original.timestampMs, snapshot.userMotion?.stepObservedAtElapsedRealtimeMs)
                val before = lateRecords().size
                apply(snapshot, capturedBinding)
                val records = lateRecords()
                assertEquals(before + 1, records.size)
                val logged = JSONObject(records.last()).getJSONObject("fields")
                fields.forEach { (key, value) ->
                    if (value == null) assertFalse(logged.has(key))
                    else assertEquals(value.toString(), logged.get(key).toString())
                }
                CapturedInput(event, capturedBinding, capturedOwner, receivedNs / 1_000_000L, snapshot)
            }
        }

        fun deliverStep(event: StepEvent, capturedBinding: Any, owner: UserMotionStateOwner, receivedAt: Long) =
            invoke("handlePositioningStepEvent", Void.TYPE,
                arrayOf(StepEvent::class.java, bindingClass, java.lang.Long.TYPE, UserMotionStateOwner::class.java),
                event, capturedBinding, receivedAt, owner)
        fun apply(snapshot: PositioningSnapshot, capturedBinding: Any) = invoke("applyPositioningMotionSnapshot", Void.TYPE,
            arrayOf(PositioningSnapshot::class.java, bindingClass), snapshot, capturedBinding)
        fun bindingIsCurrent(value: Any) = invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE,
            arrayOf(bindingClass), value) as Boolean
        fun currentFieldAdmission() = invoke("currentPositionFieldCollectionAllowsWork", java.lang.Boolean.TYPE) as Boolean
        fun traceCount(value: RecorderBinding) = recorder.list(value).sumOf { it.recordCount }

        fun restoreAndSubmitProfile(): PersistedPedestrianMotionProfile {
            val restored = PersistedPedestrianMotionProfile(.70, 1.20, .16, 3L, calibrationConditionKnown = false)
            assertTrue(coordinator.restoreProfile(restored))
            val profile = requireNotNull(coordinator.snapshotProfileForPersistence())
            assertEquals(actorId, invoke("currentReporterUserId", String::class.java))
            assertEquals(actorId, field(main, "positioningProfileActorId"))
            expectedCommitCount = profileStorage.successfulCommits.get() + 1
            submittedActors += actorId
            profileWritesDrained = false
            invoke("persistPositioningProfileForCurrentActor", Void.TYPE)
            return profile
        }

        fun awaitOwnedProfileCommit() {
            await("Main's actual profile save commit and its own serial drain") {
                profileStorage.successfulCommits.get() >= expectedCommitCount &&
                    submittedActors.all { SerialPedestrianProfileWriter.shared.pendingProfileOrNull(it) == null }
            }
            profileWritesDrained = true
        }

        fun assertFreshAuthenticatedProfileReadback(actor: String, expected: PersistedPedestrianMotionProfile) {
            onMain(instrumentation) {
                val fresh = AndroidSensitivePreferenceStore(profilePreferences, profileSpec,
                    AndroidKeyStoreAead(profileSpec.keyPolicy))
                readbackOwner = fresh
                assertFalse(fresh.isBlocked())
                val reopened = AndroidPedestrianProfileStore(NativeProfileStorage(fresh))
                assertEquals(expected, reopened.load(actor))
                assertTrue("Fresh readback retires the old preference owner", profileStorage.native.isBlocked())
            }
            // No further Main/profile operation is performed after this owner transfer.
        }

        fun logicalProfileKey(actor: String): String = MethodHandles.privateLookupIn(
            AndroidPedestrianProfileStore::class.java, MethodHandles.lookup()).findVirtual(
            AndroidPedestrianProfileStore::class.java, "profileKey", MethodType.methodType(String::class.java, String::class.java))
            .bindTo(profileStore).invokeWithArguments(actor) as String

        fun lateRecords(): List<String> {
            val sessionId = requireNotNull(fieldSessionId)
            var ordinal = 0L
            return File(File(root, "field"), sessionId).listFiles().orEmpty()
                .filter { it.name.matches(Regex("records-[0-9]{4}\\.jsonl")) }.sortedBy { it.name }
                .flatMap { file ->
                    val segmentIndex = file.name.substring(8, 12).toInt()
                    file.readLines().filter(String::isNotBlank).map { envelope ->
                        val plain = MethodHandles.privateLookupIn(PersistentFieldSessionLog::class.java, MethodHandles.lookup())
                            .findVirtual(PersistentFieldSessionLog::class.java, "openRecord", MethodType.methodType(
                                String::class.java, String::class.java, String::class.java, java.lang.Integer.TYPE, java.lang.Long.TYPE))
                            .bindTo(fieldLog).invokeWithArguments(envelope, sessionId, segmentIndex, ordinal++) as String?
                        requireNotNull(plain) { "Actual native Field record must authenticate" }
                    }
                }.filter { JSONObject(it).optString("event_name") == "pdr_late_motion" }
        }

        fun checkpoint() = Checkpoint(field(main, "positioningCollectionBinding"), field(main, "locationCallback"),
            field(main, "locationCollectionOwner"), field(main, "locationCallbackGeneration") as Int,
            coordinator.currentUserMotionOwner(), coordinator.peekUserMotion(SystemClock.elapsedRealtime()).stepObservedAtElapsedRealtimeMs,
            field(main, "latestWalkingCalibrationStep"),
            field(main, "latestPositionStationaryState"), field(main, "latestPositionStationarySinceMs"),
            field(main, "latestTrustedLocation"), field(main, "latestRawRouteLocation"),
            field(main, "latestGnssQualitySnapshot"), field(main, "stepTracker"), field(main, "pedestrianMotionTracker"),
            invoke("currentMotionEvidenceStatusMessage", String::class.java) as String, coordinator.currentProfile(),
            recorderBindings.map { it.localAccountScopeId to traceCount(it) }, lateRecords(),
            root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).path }
                .map { it.relativeTo(root).path to sha256(it.readBytes()) }.toList(),
            profilePreferences.getString(profileSpec.storageKey, null),
            field(positionController, "recording"), positionScopeStorage.localScopeId())

        fun invoke(name: String, result: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, types.toList()))
                .bindTo(main).invokeWithArguments(args.toList())

        fun drainOwnWritesForCleanup() {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            await("Only submitted fixture actors finish; the process writer is never closed/reset") {
                submittedActors.all { SerialPedestrianProfileWriter.shared.pendingProfileOrNull(it) == null }
            }
            profileWritesDrained = true
        }

        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    if (first == null) first = problem else first!!.addSuppressed(problem)
                }
            }
            cleanup { assertEquals(true, invoke("stopLocationUpdates", java.lang.Boolean.TYPE, arrayOf(java.lang.Boolean.TYPE), true)) }
            cleanup { renewalHandler.removeCallbacksAndMessages(null) }
            cleanup { authorityEpoch?.let(authority::invalidate) }
            cleanup { setField(main, "isActivityForeground", false); lifecycle.handle(WalkSessionEvent.EnteredBackground) }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
            cleanup { if (::fieldLog.isInitialized) fieldLog.close() }
            cleanup { if (::fieldLog.isInitialized) assertTrue(fieldLog.purgeAll()) }
            cleanup { if (::recorder.isInitialized) assertTrue(recorder.purge()) }
            // If the own serial drain failed, retain profile material/keys rather than racing a queued write.
            cleanup {
                if (::profileStorage.isInitialized && profileWritesDrained) {
                    assertTrue((readbackOwner ?: profileStorage.native).edit().clear().commit())
                    assertTrue(context.deleteSharedPreferences(profilePrefsName))
                }
            }
            cleanup { context.deleteSharedPreferences(stepPrefsName) }
            cleanup { assertTrue(context.deleteSharedPreferences(positionScopePrefsName)) }
            if (profileWritesDrained) ownedAead.forEach { owned -> cleanup { assertTrue(owned.destroyKnownVersions()) } }
            cleanup { if (profileWritesDrained) assertTrue(root.deleteRecursively()) }
            first?.let { throw it }
        }
    }

    private companion object {
        const val ACTOR = "77777777-7777-4777-8777-777777777777"
        const val NEW_ACTOR = "88888888-8888-4888-8888-888888888888"
        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U kr.co.hanium.dreamup.walksafe")
            val userUids = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:kr\\.co\\.hanium\\.dreamup\\.walksafe\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            }
            assertEquals(1, userUids.size); assertNotEquals(userUids.single(), Process.myUid())
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            var first: Throwable? = null
            try {
                onMain(instrumentation) { f.prepare() }; test(f)
                assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION))
            }
            catch (failure: Throwable) { first = failure; throw failure }
            finally {
                var cleanupFailure: Throwable? = null
                try { f.drainOwnWritesForCleanup() } catch (problem: Throwable) { cleanupFailure = problem }
                try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
                catch (problem: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = problem else cleanupFailure.addSuppressed(problem)
                }
                cleanupFailure?.let { if (first == null) throw it else first.addSuppressed(it) }
            }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val value = AtomicReference<T>(); val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { value.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }; return value.get()
        }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10L)
            assertTrue(description, condition())
        }
        fun afterClock(previous: Long): Long {
            await("Distinct real elapsed input after $previous") { SystemClock.elapsedRealtime() > previous }
            return SystemClock.elapsedRealtime()
        }
        fun afterClockNanos(previousMs: Long): Long { afterClock(previousMs); return SystemClock.elapsedRealtimeNanos() }
        fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(target)
        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun setOwnerField(owner: Any, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(owner, value)
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
        fun completedOnboarding(actor: String): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(actor), receipt(1)),
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
        fun completedDeviceCheck(actor: String, generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), actor, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, actor, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
        fun readyPriorityPolicy() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true); reviewPracticeNecessity(true); acceptEducationConsent()
            acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
            confirmSafePracticePlace()
        }
    }
}
