package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.location.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Real binding/coordinator/fused callback wrapper. SDK registration, device factories and UI queue are ports. */
class PositioningSessionControllerTest {
    @Test fun constructionDoesNotReadAdmissionOrCreateSources() {
        val f = Fixture()
        assertEquals(0, f.reads)
        assertEquals(0, f.registrations)
        assertNull(f.controller.binding)
        assertNull(f.controller.collectionOwner)
    }
    @Test fun sameOwnerKeepsItsExactCallbackBindingAndInputHistory() {
        val f = Fixture(); f.start()
        val binding = requireNotNull(f.controller.binding)
        val callback = f.controller.callback
        val original = f.controller.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 3.0, 1_000L))
        assertEquals(GnssObservationDisposition.INITIALIZED, original.gnssUpdate?.disposition)
        val generation = f.controller.coordinator.currentInputGeneration()
        f.now = 1_200L; f.controller.startLocationUpdatesIfAllowed()
        assertEquals(1, f.registrations)
        assertSame(callback, f.controller.callback)
        assertSame(binding, f.controller.binding)
        assertEquals(generation, f.controller.coordinator.currentInputGeneration())
        assertNotNull(f.controller.coordinator.evaluate(1_200L).filtered)
        val replay = f.controller.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 3.0, 1_000L, receivedAtElapsedRealtimeMs = 1_200L))
        assertEquals("Repeated start must not reset the estimator and accept the old measurement again", GnssHardRejectReason.OUT_OF_ORDER, replay.gnssUpdate?.hardRejectReason)
        assertTrue(f.callbackCurrent.single()(requireNotNull(callback)))
    }
    @Test fun queuedExpiryCannotEraseANewerAcceptedPosition() {
        val f = Fixture(); f.start()
        f.publishMotionPosition(1_000L)
        val old = requireNotNull(f.controller.trustedLocation)
        assertNull(f.controller.freshTrustedLocationOrNull(11_001L))
        assertEquals(1, f.ui.size)
        f.now = 11_002L; f.publishMotionPosition(11_002L)
        val fresh = requireNotNull(f.controller.trustedLocation)
        assertNotSame(old, fresh)
        f.ui.removeAt(0).invoke()
        assertSame(fresh, f.controller.trustedLocation)
        assertEquals(11_002L, fresh.elapsedRealtimeMs)
    }
    @Test fun stoppingRetiresTheOriginalCallbackBeforeItsLateDelivery() {
        val f = Fixture(); f.start()
        val old = requireNotNull(f.controller.callback)
        val oldCurrent = f.callbackCurrent.single()
        val oldGeneration = f.controller.callbackGeneration
        f.publishMotionPosition(1_000L)
        assertTrue(f.controller.stopLocationUpdates())
        assertFalse(oldCurrent(old))
        assertNull(f.controller.binding)
        assertNull(f.controller.trustedLocation)
        assertNull(f.controller.collectionOwner)
        assertEquals(oldGeneration + 1, f.controller.callbackGeneration)
        assertEquals(listOf(old), f.removed)
        assertTrue(f.controller.observePendingFusedLocationCancellations())
    }
    @Test fun accountChangeRejectsOldCallbackBeforeAnyRawLocationIsRead() {
        val f = Fixture(); f.start()
        val oldCurrent = f.callbackCurrent.single()
        val old = requireNotNull(f.controller.callback)
        val oldBinding = requireNotNull(f.controller.binding)
        f.owner = f.owner.copy(actorId = "other", gatewayGeneration = 2L)
        assertFalse(oldCurrent(old))
        // Deliberately unreadable raw Location: admission must return before any SDK getter.
        f.controller.handleLocationUpdate(f.unread(Location::class.java), oldBinding.owner,
            oldBinding.locationGeneration, oldBinding, 1_100L)
        assertNull(f.controller.coordinator.evaluate(1_100L).raw)
        assertNull(f.controller.trustedLocation)
    }
    @Test fun qualityStatusPublishesTheCurrentNumericStepCount() {
        val f = Fixture()
        val snapshot = f.controller.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 3.0, 1_000L))
        assertEquals(PositionQuality.MEDIUM, snapshot.quality)
        val trusted = TrustedLocation(37.5, 127.0, 7.9f, 1_000L)
        val publish = PositioningSessionController::class.java.getDeclaredMethod(
            "publishPositioningQualityStatus", PositioningSnapshot::class.java, TrustedLocation::class.java,
        ).apply { isAccessible = true }
        f.steps = 37
        publish.invoke(f.controller, snapshot, trusted)
        assertEquals(listOf("navigation=position_medium max_axis_sigma=7m steps=37 heading=null"), f.statusMessages)
        assertEquals(1, f.stepReads)
        f.steps = 38
        publish.invoke(f.controller, snapshot, trusted)
        assertEquals("navigation=position_medium max_axis_sigma=7m steps=38 heading=null", f.statusMessages.last())
        assertEquals(2, f.stepReads)
    }
    internal class Fixture(
        private val sharedLifecycle: kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle? = null,
        initialOwner: LocationCollectionOwner = LocationCollectionOwner("actor", null, 1L),
        private val sharedRun: NavigationRunState? = null,
        private val sharedRequests: RouteAcquisitionController? = null,
        private val withGpsSource: Boolean = false,
    ) {
        var now = 1_000L; var reads = 0; var registrations = 0
        val g = GuidanceFixture(sharedLifecycle, sharedRun)
        var steps = 0; var stepReads = 0
        var navigationAllowed = true
        var fieldAllowed = false
        var motionObservationAllowed = true
        var collectionAllowed = true
        val statusMessages = mutableListOf<String>()
        var owner = initialOwner
        val ui = mutableListOf<() -> Unit>()
        val callbackCurrent = mutableListOf<(LocationCallback) -> Boolean>()
        var registeredCallback: LocationCallback? = null
        val removed = mutableListOf<LocationCallback>()
        val compass = RouteCompassSession(g.run, unused(), unused(), unused(), unused(), g.diagnostics, { now })
        val gpsObservationTimes = mutableListOf<Long>()
        val gpsFreshRequests = mutableListOf<(Location?) -> Unit>()
        var gpsFreshCancellations = 0
        val gpsGate = RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS,
            gpsFailureConfirmationPeriods = GuidanceRecoveryTiming.GPS_RECHECK_PERIODS)
        val gpsEvidenceStore = OfficialEnvironmentEvidenceStore(Any(), gpsGate, RuntimeMeasuredQualityGate())
        val gps: ContinuousGpsSession = ContinuousGpsSession(object : ContinuousGpsSession.SourceReader {
            override fun current(): ContinuousGpsSource? = if (withGpsSource) controller.binding?.let { binding ->
                ContinuousGpsSource(binding, g.epoch, requireNotNull(WalkSafeEnvironmentProfiles.active(true)).officialEnvironment)
            } else null
            override val latestTrusted: TrustedLocation? get() = controller.trustedLocation
            override val latestRaw: TrustedLocation? get() = controller.rawRouteLocation
            override fun nativeCheckCurrent(epoch: kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch) = false
        }, gpsGate, if (withGpsSource) object : ContinuousGpsSession.EvidenceWriter {
            override fun clearGpsEvidence() = gpsEvidenceStore.clearGpsEvidence()
            override fun record(epoch: kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch,
                observedAtMs: Long, trustedFixAvailable: Boolean?, accuracyM: Double?,
                elapsedRealtimeMs: () -> Long, singleOutlier: Boolean) {
                gpsObservationTimes += observedAtMs
                gpsEvidenceStore.recordGpsObservation(g.epoch, epoch, observedAtMs, trustedFixAvailable, accuracyM,
                    elapsedRealtimeMs, { WalkSafeEnvironmentProfiles.active(true)?.officialEnvironment },
                    g.lifecycle::isRuntimeEpochCurrent, { gps.owner?.epoch }, { false },
                    { g.lifecycle.currentRuntimeEpochOrNull() != null }, singleOutlier, { _, _, _ -> })
            }
        } else unused(), object : ContinuousGpsSession.Actions {
            override fun clearDisconnectedRecovery() {}
            override fun prewalkEvidenceChanged() {}
            override fun requestFresh(result: (Location?) -> Unit): () -> Unit {
                check(withGpsSource) { "unexpected request" }
                gpsFreshRequests += result
                return { gpsFreshCancellations++ }
            }
            override fun consumeFresh(location: Location, receivedAtMs: Long) = error("unexpected input")
        }, { now })
        val controller: PositioningSessionController = PositioningSessionController(sharedRun ?: g.run,
            object : PositioningSessionController.Scope {
                override fun currentOwner() = owner.takeIf {
                    collectionAllowed && (sharedLifecycle ?: g.lifecycle).currentRuntimeEpochOrNull() != null
                }.also { reads++ }
                override val navigationAllowed get() = this@Fixture.navigationAllowed
                override val motionObservationAllowed get() = this@Fixture.motionObservationAllowed
                override val fieldAllowed get() = this@Fixture.fieldAllowed
                override val fieldLeasePresent get() = false
                override val fieldStorageBlocked get() = false
                override val fieldChestConfirmed get() = false
                override val fineLocationGranted get() = false // GNSS/PDR SDK registration is outside this fixture.
                override val developmentBypass get() = false
                override val lifecycle get() = (sharedLifecycle ?: g.lifecycle).also { reads++ }
                override val stepCount get() = steps.also { stepReads++ }
                override val bootId: String? get() = null
                override val processInstanceId get() = "positioning-private"
                override fun reporterId() = "actor"
            },
            object : PositioningSessionController.Sources {
                override val fusedAvailable get() = true
                override fun fusedSource(current: (LocationCallback) -> Boolean, onEvent: (LocationCallback, FusedLocationUpdateEvent) -> Unit): FusedLocationUpdateSource {
                    callbackCurrent += current
                    return FusedLocationUpdateSource(unused<FusedLocationProviderClient>(), { now }, current, onEvent).also {
                        registeredCallback = it.callback
                    }
                }
                override fun requestUpdates(source: FusedLocationUpdateSource) { registrations++ }
                override fun captureRemoval(): (LocationCallback) -> com.google.android.gms.tasks.Task<Void> = { removed += it; Tasks.forResult(null) }
                override val completionExecutor get() = java.util.concurrent.Executor { it.run() }
                override fun gnssObserver(onSnapshot: (GnssQualitySnapshot) -> Unit): GnssQualityObserver = error("SDK boundary")
                override fun pedestrianTracker(onMotion: (PedestrianStationaryDecision) -> Unit): AndroidPedestrianMotionTracker = error("SDK boundary")
                override val profileStoreAvailable get() = false
                override fun profileStore(): AndroidPedestrianProfileStore = error("SDK boundary")
                override fun mock(location: Location): Boolean = false
                override val orientationAvailable get() = false
                override val orientationCanStop get() = false
                override fun clearGeomagneticReference() = error("SDK boundary")
                override fun stopOrientation() = error("SDK boundary")
                override fun geomagneticReference(location: Location) = error("SDK boundary")
                override fun chestHeading(timestampMs: Long, maximumAgeMs: Long): HeadingObservation? = null
            },
            object : PositioningSessionController.Mounting {
                override val observationLock = Any()
                override val profile: ApprovedPhoneMountingProfile? = null
                override val confirmation: PhoneMountingUserConfirmation? = null
                override val explicitConfirmation: PhoneMountingUserConfirmation? = null
                override val cameraAssessment: CameraFrameQualityAssessment? = null
                override val runtimeState: PhoneMountingRuntimeState? = null
                override val outputsAllowed = false
                override val observationGeneration = 0L
                override fun retireExplicitForChangedMotionOwner() {}
                override fun retireExplicitForChangedActor() {}
            },
            object : PositioningSessionController.Effects {
                override fun stopFieldSession() = true
                override fun resetMotionTelemetry() {}
                override fun refreshCalibration() {}
                override fun updateStepLength(stepLengthM: Float) {}
                override fun pauseDirection(reason: String, message: String) {}
                override fun navigationStatus(text: String) { statusMessages += text }
                override fun clearReportLocationStartSchedule() {}
                override fun shouldAppendMotionTelemetry(nowMs: Long, force: Boolean) = false
                override fun appendMotionTrace(snapshot: PositioningSnapshot?, stepDetected: Boolean, zuptApplied: Boolean, measurementElapsedRealtimeMs: Long) {}
                override fun appendGnssTrace(snapshot: PositioningSnapshot, location: Location, matchedEvidenceCurrent: Boolean) {}
                override fun refreshCompassPresentation() {}
                override fun cleanupWarning(failure: RuntimeException) { throw failure }
            }, g.diagnostics, { gps }, { compass }, { g.controller }, { sharedRequests ?: error("No route request") }, { now }, { ui += it })
        fun start() { controller.startLocationUpdatesIfAllowed() }
        fun deliver(location: Location, receivedAtMs: Long) {
            val callback = requireNotNull(registeredCallback)
            now = receivedAtMs
            g.now = receivedAtMs
            callback.onLocationResult(LocationResult.create(listOf(location)))
        }
        fun publishMotionPosition(at: Long) {
            val binding = requireNotNull(controller.binding)
            val snapshot = binding.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 3.0, at))
            assertEquals(binding.coordinator.currentUserMotionOwner(), snapshot.userMotion?.owner)
            controller.applyPositioningMotionSnapshot(snapshot, binding)
        }
        @Suppress("UNCHECKED_CAST") fun <T> unread(type: Class<T>): T {
            val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return (f.get(null) as sun.misc.Unsafe).allocateInstance(type) as T
        }
        inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ -> error("Unexpected port ${m.name}") } as T
    }
}
