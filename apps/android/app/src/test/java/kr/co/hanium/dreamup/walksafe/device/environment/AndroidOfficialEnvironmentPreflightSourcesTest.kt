package kr.co.hanium.dreamup.walksafe.device.environment

import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import java.nio.ByteBuffer
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.CameraPreflightStabilityGate
import kr.co.hanium.dreamup.walksafe.device.OfficialEnvironmentGpsSample
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingSensorMeasurement
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class AndroidOfficialEnvironmentPreflightSourcesTest {
    @Test fun androidCameraFactoryDoesNotReadContextFallbackOrSensors() {
        val source = AndroidOfficialEnvironmentCameraSource.android(
            context = { error("constructor context read") },
            fallbackController = { error("constructor fallback read") },
            fallbackInitialized = { error("constructor initialization read") },
            displayRotation = { error("constructor rotation read") },
            detectorExecutor = Executor { error("constructor executor work") },
            mainExecutor = { error("constructor UI executor read") },
            runOnUiThread = { error("constructor UI dispatch") },
            nowMs = { error("constructor clock read") },
            admission = FakeAdmission(), mounting = FakeMounting(), observe = { _, _ -> error("constructor observation") },
        )
        assertNotNull(source)
    }

    @Test fun gpsFactoryAndRequestCreationDoNotReadClientOrSubscribe() {
        val source = AndroidOfficialEnvironmentGpsSource.android(
            client = { error("constructor client read") }, mainExecutor = { error("constructor SDK executor read") },
            maximumDurationMs = 120_000L)
        val request = source.createRequest(PROFILE, { false }, {}, {}, {})
        request.cancel()
        request.start()
    }

    @Test fun lazySeparateGpsActiveQueryDoesNotConstructActualSourcesOrLookUpAnExecutor() {
        var mainExecutorReads = 0
        var sourceFactories = 0
        val executorSource: () -> Executor = {
            ++mainExecutorReads
            error("executor looked up by read-only preflight query")
        }
        val preflight by lazy(LazyThreadSafetyMode.NONE) {
            val cameraSource: () -> OfficialEnvironmentCameraSource = { AndroidOfficialEnvironmentCameraSource.android(
                context = { error("read-only context read") },
                fallbackController = { error("read-only fallback read") },
                fallbackInitialized = { error("read-only initialization read") },
                displayRotation = { error("read-only rotation read") },
                detectorExecutor = Executor { error("read-only detector work") },
                mainExecutor = executorSource,
                runOnUiThread = { error("read-only UI work") },
                nowMs = { error("read-only camera clock read") },
                admission = FakeAdmission(), mounting = FakeMounting(), observe = { _, _ -> error("read-only observation") },
            ).also { ++sourceFactories } }
            val gpsSource: () -> OfficialEnvironmentGpsSource = { AndroidOfficialEnvironmentGpsSource.android(
                client = { error("read-only client read") }, mainExecutor = executorSource,
                maximumDurationMs = 120_000L,
            ).also { ++sourceFactories } }
            OfficialEnvironmentPreflightController(
                OfficialEnvironmentEvidenceStore(Any(), RuntimeMeasuredQualityGate(3_000L), RuntimeMeasuredQualityGate()),
                unreadPort<OfficialEnvironmentPreflightFactsReader>(), cameraSource, gpsSource,
                unreadPort<OfficialEnvironmentPreflightEffects>(), unreadPort<OfficialEnvironmentPreflightQueue>(),
                CameraPreflightStabilityGate(3, 2), { error("read-only owner clock read") },
                OfficialEnvironmentPreflightController.Config(100L, 500L, 60_000L, 120_000L, 2_000L),
            )
        }
        assertEquals(0, sourceFactories)
        repeat(2) { assertFalse(preflight.separateGpsActive) }
        assertEquals(0, sourceFactories)
        assertEquals(0, mainExecutorReads)
    }

    @Test fun firstAdmissionFailureDoesNotReadLaterFactsOrStartSensor() {
        val f = CameraFixture()
        f.admission.permission = false
        assertEquals("CAMERA_PERMISSION_MISSING", f.source.startFailureReason())
        assertEquals(listOf("permission"), f.admission.reads)
        assertEquals(0, f.mounting.starts)
    }

    @Test fun admissionKeepsCameraOwnershipOrderAndStartsSensorLast() {
        val f = CameraFixture()
        assertNull(f.source.startFailureReason())
        assertEquals(listOf("permission", "blocked", "postLoginActive", "postLoginCleanup", "postLoginMetric",
            "arBusy", "fallbackCleanup", "fallbackRequested"), f.admission.reads)
        assertEquals(1, f.mounting.starts)
        f.mounting.startAvailable = false
        assertEquals("MOUNTING_SENSOR_UNAVAILABLE", f.source.startFailureReason())
    }

    @Test fun retiredCameraFrameStillTakesOriginalClockReadingAndClosesWithoutLuma() {
        val f = CameraFixture()
        var current = true
        f.source.start(EPOCH, 7, { current }, { true }, {})
        current = false
        val image = FakeImage()
        f.platform.image!!(image)
        assertEquals(1, f.clockReads)
        assertEquals(0, image.planeReads)
        assertEquals(1, image.closes)
        assertEquals(0, f.mounting.latestReads)
        assertTrue(f.observations.isEmpty())
    }

    @Test fun intervalRejectedFrameClosesBeforeAnyImagePlaneOrSensorRead() {
        val f = CameraFixture()
        val admittedTimes = mutableListOf<Long>()
        f.source.start(EPOCH, 7, { true }, { admittedTimes += it; false }, {})
        val image = FakeImage()
        f.platform.image!!(image)
        assertEquals(listOf(500L), admittedTimes)
        assertEquals(0, image.planeReads)
        assertEquals(0, f.mounting.latestReads)
        assertEquals(1, image.closes)
    }

    @Test fun lumaThenSensorObservationCommitsSynchronouslyBeforeImageFinallyCloses() {
        val f = CameraFixture()
        val image = FakeImage()
        f.mounting.afterLatest = { image.bytes.fill(0) }
        f.onObserve = { observation, generation ->
            assertFalse(image.closed)
            assertEquals(7L, generation)
            assertEquals(EPOCH, observation.epoch)
            assertEquals(500L, observation.observedAtElapsedRealtimeMs)
            assertEquals(1.0, observation.normalizedBrightness!!, 0.0)
            assertEquals(0.0, observation.nearBlackFraction!!, 0.0)
            assertNull(observation.occludedFraction)
            assertEquals(1.5, observation.angularShakeDegreesPerSecond!!, 0.0)
            assertEquals(12.0, observation.mountPitchDegrees!!, 0.0)
        }
        f.source.start(EPOCH, 7, { true }, { true }, {})
        f.platform.image!!(image)
        assertEquals(1, image.closes)
        assertEquals(1, f.clockReads)
        assertEquals(1, f.mounting.latestReads)
    }

    @Test fun observationFailureCannotLeakNativeImage() {
        val f = CameraFixture()
        f.onObserve = { _, _ -> error("commit failed") }
        f.source.start(EPOCH, 7, { true }, { true }, {})
        val image = FakeImage()
        assertThrows(IllegalStateException::class.java) { f.platform.image!!(image) }
        assertEquals(1, image.closes)
    }

    @Test fun unavailableFrameDoesNotReadMountingSensorOrClaimPhysicalOcclusion() {
        val f = CameraFixture()
        f.source.start(EPOCH, 7, { true }, { true }, {})
        f.platform.image!!(FakeImage(available = false))
        val observation = f.observations.single()
        assertEquals(false, observation.frameAvailable)
        assertNull(observation.normalizedBrightness)
        assertNull(observation.nearBlackFraction)
        assertNull(observation.occludedFraction)
        assertEquals(0, f.mounting.latestReads)
    }

    @Test fun sharedResourceDetachAndClosedProofKeepTheSameOwnedHandle() {
        val f = CameraFixture()
        val owned = FakeResources()
        f.slot.owned = owned
        assertSame(owned, f.source.detachOwnedResources())
        assertNull(f.slot.owned)
        owned.clearAnalyzer()
        owned.unbindOwned()
        val closed = mutableListOf<Boolean>()
        f.source.awaitClosed(owned) { closed += it }
        assertSame(owned, f.slot.awaited)
        assertTrue(closed.isEmpty())
        f.slot.complete!!(false)
        assertEquals(listOf(false), closed)
        f.source.markSharedReleaseBlocked()
        assertTrue(f.slot.blocked)
    }

    @Test fun analyzerBarrierUsesExistingExecutorBeforeUiReleaseAndReportsRejection() {
        val f = CameraFixture()
        val operations = mutableListOf<String>()
        f.source.awaitAnalyzerBarrier(Runnable { operations += "release" }, { operations += "rejected" })
        assertTrue(operations.isEmpty())
        f.detector.drain()
        assertEquals(listOf("release"), operations)
        f.detector.reject = true
        f.source.awaitAnalyzerBarrier(Runnable { operations += "second release" }, { operations += "rejected" })
        assertEquals(listOf("release", "rejected"), operations)
    }

    @Test fun gpsKeepsNanosecondBatchOrderOriginalTimestampAndRequestOptions() {
        val f = GpsFixture()
        val request = f.source.createRequest(PROFILE.copy(maximumMeasuredEvidenceAgeMs = 50_000), { true }, f.samples::add, {}, {})
        assertTrue(f.platform.requests.isEmpty())
        request.start()
        val native = f.platform.requests.single()
        assertEquals(1_000L, native.options.intervalMs)
        assertEquals(500L, native.options.minimumIntervalMs)
        assertEquals(30_000L, native.options.maximumAgeMs)
        assertEquals(120_000L, native.options.maximumDurationMs)
        native.samples(listOf(sample(2_000_999L, latitude = 2.0), sample(2_000_001L, latitude = 1.0), sample(1_000_000L)))
        assertEquals(listOf(1L, 2L, 2L), f.samples.map { it.observedAtElapsedRealtimeMs })
        assertEquals(listOf(37.0, 1.0, 2.0), f.samples.map { it.latitude })
    }

    @Test fun ownerRetirementDuringGpsBatchStopsLaterSamples() {
        val f = GpsFixture()
        var current = true
        val request = f.source.createRequest(PROFILE, { current }, { f.samples += it; current = false }, {}, {})
        request.start()
        f.platform.requests.single().samples(listOf(sample(2_000_000), sample(1_000_000)))
        assertEquals(listOf(1L), f.samples.map { it.observedAtElapsedRealtimeMs })
    }

    @Test fun cancellationInsideInlineRegistrationCallbackAlreadyOwnsNativeRemovalHandle() {
        val f = GpsFixture()
        lateinit var request: OfficialEnvironmentGpsRequest
        f.platform.startAction = { it.samples(listOf(sample(1_000_000))) }
        request = f.source.createRequest(PROFILE, { true }, { request.cancel() }, {}, {})
        request.start()
        assertEquals(1, f.platform.requests.single().cancels)
        request.start()
        assertEquals(1, f.platform.requests.single().starts)
    }

    @Test fun retiredGpsCallbackAndFailedListenerCannotPublishAndLateSuccessRemoves() {
        val f = GpsFixture()
        var current = true
        var unavailable = 0
        var failures = 0
        val request = f.source.createRequest(PROFILE, { current }, f.samples::add, { ++unavailable }, { ++failures })
        request.start()
        val native = f.platform.requests.single()
        current = false
        native.samples(listOf(sample(1_000_000)))
        native.unavailable()
        native.failure()
        native.registered()
        assertTrue(f.samples.isEmpty())
        assertEquals(0, unavailable)
        assertEquals(0, failures)
        assertEquals(1, native.cancels)
    }

    @Test fun synchronousRegistrationFailureAllowsParentToCancelTheExactCallback() {
        val f = GpsFixture()
        lateinit var request: OfficialEnvironmentGpsRequest
        f.platform.startAction = { throw SecurityException("permission revoked") }
        var failures = 0
        request = f.source.createRequest(PROFILE, { true }, {}, {}, { ++failures; request.cancel() })
        request.start()
        assertEquals(1, failures)
        assertEquals(1, f.platform.requests.single().cancels)
    }

    private class CameraFixture {
        val platform = FakeCameraPlatform()
        val admission = FakeAdmission()
        val mounting = FakeMounting()
        val slot = FakeSlot()
        val detector = QueueExecutor()
        var clockReads = 0
        val observations = mutableListOf<CameraFrameQualityObservation>()
        var onObserve: (CameraFrameQualityObservation, Long) -> Unit = { _, _ -> }
        val source = AndroidOfficialEnvironmentCameraSource(platform, admission, mounting, slot, detector,
            { it.run() }, { ++clockReads; 500L }, { observation, generation -> observations += observation; onObserve(observation, generation) })
    }

    private class FakeAdmission : OfficialEnvironmentCameraAdmission {
        val reads = mutableListOf<String>()
        var permission = true
        private fun value(name: String, value: Boolean = false): Boolean { reads += name; return value }
        override fun cameraPermission() = value("permission", permission)
        override fun releaseBlocked() = value("blocked")
        override fun postLoginCameraActive() = value("postLoginActive")
        override fun postLoginCameraCleanupPending() = value("postLoginCleanup")
        override fun postLoginMetricCheckActive() = value("postLoginMetric")
        override fun arCameraBusy() = value("arBusy")
        override fun fallbackCleanupPending() = value("fallbackCleanup")
        override fun fallbackRequested() = value("fallbackRequested")
    }

    private class FakeMounting : OfficialEnvironmentMountingSource {
        var starts = 0
        var stops = 0
        var latestReads = 0
        var startAvailable = true
        var afterLatest: () -> Unit = {}
        override fun start(): Boolean { ++starts; return startAvailable }
        override fun stopIfInitialized() { ++stops }
        override fun latestNow(): PhoneMountingSensorMeasurement {
            ++latestReads
            afterLatest()
            return PhoneMountingSensorMeasurement(1.5, 12.0)
        }
    }

    private class FakeCameraPlatform : OfficialEnvironmentCameraPlatform {
        var image: ((OfficialEnvironmentCameraImage) -> Unit)? = null
        override fun requestBind(isCurrent: () -> Boolean, onImage: (OfficialEnvironmentCameraImage) -> Unit, onFailure: () -> Unit) {
            image = onImage
        }
    }
    private class FakeImage(private val available: Boolean = true) : OfficialEnvironmentCameraImage {
        val bytes = ByteArray(4) { 255.toByte() }
        override val width = 2
        override val height = 2
        var planeReads = 0
        var closes = 0
        var closed = false
        override fun hasImage() = available
        override fun <T> withLuma(block: (ByteBuffer?, Int?, Int?) -> T): T {
            check(!closed); ++planeReads
            return block(ByteBuffer.wrap(bytes), 2, 1)
        }
        override fun close() { check(!closed); closed = true; ++closes }
    }
    private class FakeResources : OfficialEnvironmentCameraResources {
        override val hasAnalysis = true
        override val hasCamera = true
        override fun clearAnalyzer() {}
        override fun unbindOwned() {}
    }
    private class FakeSlot : OfficialEnvironmentSharedCameraSlot {
        var owned: OfficialEnvironmentCameraResources? = null
        var awaited: OfficialEnvironmentCameraResources? = null
        var complete: ((Boolean) -> Unit)? = null
        var blocked = false
        override fun resumeLifecycle() {}
        override fun moveLifecycleToCreatedIfInitialized() {}
        override fun bindPreflightCamera(provider: ProcessCameraProvider, analysis: ImageAnalysis) {}
        override fun detachOwnedResources() = owned.also { owned = null }
        override fun awaitClosed(resources: OfficialEnvironmentCameraResources?, completion: (Boolean) -> Unit) {
            awaited = resources; complete = completion
        }
        override fun markReleaseBlocked() { blocked = true }
    }
    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) { if (reject) throw RejectedExecutionException(); queue += command }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class GpsFixture {
        val platform = FakeGpsPlatform()
        val source = AndroidOfficialEnvironmentGpsSource(platform, 120_000)
        val samples = mutableListOf<OfficialEnvironmentGpsSample>()
    }
    private class FakeGpsPlatform : OfficialEnvironmentGpsPlatform {
        class Request(val options: OfficialEnvironmentGpsRequestOptions,
            val samples: (List<OfficialEnvironmentNativeGpsSample>) -> Unit,
            val unavailable: () -> Unit, val registered: () -> Unit, val failure: () -> Unit,
            val startAction: (Request) -> Unit) : OfficialEnvironmentGpsRegistration {
            var starts = 0
            var cancels = 0
            override fun start() { ++starts; startAction(this) }
            override fun cancel() { ++cancels }
        }
        val requests = mutableListOf<Request>()
        var startAction: (Request) -> Unit = {}
        override fun createRegistration(options: OfficialEnvironmentGpsRequestOptions, isCurrent: () -> Boolean,
            onSamples: (List<OfficialEnvironmentNativeGpsSample>) -> Unit, onTemporarilyUnavailable: () -> Unit,
            onRegistered: () -> Unit, onFailure: () -> Unit): OfficialEnvironmentGpsRegistration =
            Request(options, onSamples, onTemporarilyUnavailable, onRegistered, onFailure, startAction).also(requests::add)
    }

    private companion object {
        inline fun <reified T> unreadPort(): T = Proxy.newProxyInstance(T::class.java.classLoader,
            arrayOf(T::class.java)) { _, method, _ -> error("read-only port invocation: ${method.name}") } as T
        val EPOCH = WalkRuntimeEpoch("walk-source", 1L)
        val PROFILE = ApprovedOfficialEnvironmentProfile("source-test", "camera-test", 15.0, 10_000, 2)
        fun sample(ns: Long, latitude: Double = 37.0) = OfficialEnvironmentNativeGpsSample(ns, latitude, 127.0, 5.0, false)
    }
}
