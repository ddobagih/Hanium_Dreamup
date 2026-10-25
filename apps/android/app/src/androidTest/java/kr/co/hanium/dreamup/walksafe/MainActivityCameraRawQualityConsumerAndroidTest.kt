package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldRecorderController
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled CPU Y-plane and raw sensor-math inputs -> real quality policy -> actual Main
 * raw storage/consumer. No Image/ARCore/sensor acquisition, ACTIVE readiness, final emit,
 * OS privacy getter/toggle or permission grant is performed. DEBUG candidate is not approved.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityCameraRawQualityConsumerAndroidTest {
    @Test
    fun actualMainVetoesLatestUnknownMissingAndBlankYuvQualityDespiteHeldPass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", target.packageName)
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        assertNotEquals("Original USER UID", 10209, Process.myUid())
        @Suppress("DEPRECATION")
        assertNull(target.packageManager.getPackageInfo(target.packageName, 0).sharedUserId)
        assertTrue("The existing Main DEBUG profile selection is a fixture premise", BuildConfig.DEBUG)
        assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        val bundle = requireNotNull(WalkSafeEnvironmentProfiles.active(allowTestCandidate = BuildConfig.DEBUG))
        assertSame(WalkSafeEnvironmentProfiles.testCandidate, bundle)
        assertEquals(WalkSafeEnvironmentProfileStatus.TEST_CANDIDATE, bundle.status)
        assertFalse(bundle.approvedForProduction)
        assertNull(WalkSafeEnvironmentProfiles.productionApproved)
        val profile = bundle.cameraFrameQuality
        val context = OwnedContext(target)
        var main: MainActivity? = null
        var first: Throwable? = null
        try {
            context.prepare()
            onMain(instrumentation) {
                val component = ComponentName(context, MainActivity::class.java)
                @Suppress("DEPRECATION")
                val actual = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                    target.applicationContext as Application, Intent().setComponent(component),
                    context.packageManager.getActivityInfo(component, 0), "Actual raw camera quality consumer",
                    null, null, null) as MainActivity
                main = actual
                assertSame(context, actual.applicationContext)
                assertEquals(context.filesDir.canonicalFile, actual.applicationContext.filesDir.canonicalFile)
                assertEquals(context.noBackupFilesDir.canonicalFile, actual.noBackupFilesDir.canonicalFile)
                val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
                set(actual, "walkSessionLifecycle", lifecycle)
                assertSame(lifecycle, field(actual, "walkSessionLifecycle"))
                assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
                assertNull(lifecycle.snapshot().readiness)
                assertNull(lifecycle.currentRuntimeEpochOrNull()) // This is not an ACTIVE/final-output test.
                val epoch = lifecycle.snapshot().epoch
                assertUnstartedOwners(actual)
                actual.resetRuntimeRawCameraAssessment()
                assertNull(latest(actual))
                assertFalse(actual.rawCameraAssessmentAllowsNewOutput(latest(actual), epoch, profile,
                    SystemClock.elapsedRealtime()))

                // Same stride-aware normal Y plane as the existing luma producer's direct test.
                val normalPlane = ByteBuffer.wrap(byteArrayOf(51, 99, 102, 99, 0, 0,
                    153.toByte(), 99, 204.toByte(), 99, 0, 0))
                val observedAt = SystemClock.elapsedRealtime()
                val normal = CameraFrameQualityPolicy.assess(epoch, SystemClock.elapsedRealtime(),
                    observation(epoch, observedAt, true, normalPlane), profile)
                assertEquals(EnvironmentEvidenceStatus.PASS, normal.status)
                assertEquals(CameraFrameQualityReason.PASSED, normal.reason)
                assertEquals(epoch, normal.epoch)
                assertEquals(observedAt, normal.observedAtElapsedRealtimeMs)
                assertTrue(actual.observeRuntimeRawCameraAssessment(normal, SystemClock.elapsedRealtime()))
                assertSame(normal, latest(actual))
                assertTrue(actual.rawCameraAssessmentAllowsNewOutput(latest(actual), epoch, profile,
                    SystemClock.elapsedRealtime()))

                // Preserve this control's original epoch/receipt, including equal-time rejection.
                val blank = CameraFrameQualityPolicy.assess(epoch, SystemClock.elapsedRealtime(),
                    observation(epoch, observedAt, true, ByteBuffer.wrap(ByteArray(12))), profile)
                assertEquals(EnvironmentEvidenceStatus.FAIL, blank.status)
                assertEquals(CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE, blank.reason)
                assertTrue(actual.observeRuntimeRawCameraAssessment(blank, SystemClock.elapsedRealtime()))
                assertSame(blank, latest(actual))
                assertEquals(observedAt, latest(actual)!!.observedAtElapsedRealtimeMs)
                val held = (field(actual, "runtimeCameraQualityGate") as RuntimeMeasuredQualityGate)
                    .current(SystemClock.elapsedRealtime())
                assertNotNull("The held PASS must still be live for this contrast", held)
                assertEquals(EnvironmentEvidenceStatus.PASS, held!!.status)
                assertEquals(normal.asMeasuredEnvironmentEvidence(), held)
                assertEquals(observedAt, held.observedAtElapsedRealtimeMs)
                assertFalse(actual.rawCameraAssessmentAllowsNewOutput(latest(actual), epoch, profile,
                    SystemClock.elapsedRealtime()))
                assertFalse("Duplicate old PASS cannot replace the same-time latest FAIL",
                    actual.observeRuntimeRawCameraAssessment(normal, SystemClock.elapsedRealtime()))
                assertSame(blank, latest(actual))
                assertFalse(actual.rawCameraAssessmentAllowsNewOutput(latest(actual), epoch, profile,
                    SystemClock.elapsedRealtime()))

                val unknownFrame = CameraFrameQualityPolicy.assess(epoch, SystemClock.elapsedRealtime(),
                    observation(epoch, observedAt, null, null), profile)
                assertEquals(EnvironmentEvidenceStatus.UNKNOWN, unknownFrame.status)
                assertEquals(CameraFrameQualityReason.FRAME_STATE_UNKNOWN, unknownFrame.reason)
                assertTrue(actual.observeRuntimeRawCameraAssessment(unknownFrame, SystemClock.elapsedRealtime()))
                assertSame(unknownFrame, latest(actual))
                assertEquals(epoch, latest(actual)!!.epoch)
                assertEquals(observedAt, latest(actual)!!.observedAtElapsedRealtimeMs)
                assertFalse(actual.rawCameraAssessmentAllowsNewOutput(latest(actual), epoch, profile,
                    SystemClock.elapsedRealtime()))

                val unknownLuma = CameraFrameQualityPolicy.assess(epoch, SystemClock.elapsedRealtime(),
                    observation(epoch, observedAt, true, ByteBuffer.allocate(3)), profile)
                assertEquals(EnvironmentEvidenceStatus.UNKNOWN, unknownLuma.status)
                assertEquals(CameraFrameQualityReason.INVALID_MEASUREMENT, unknownLuma.reason)
                assertTrue(actual.observeRuntimeRawCameraAssessment(unknownLuma, SystemClock.elapsedRealtime()))
                assertSame(unknownLuma, latest(actual))
                assertEquals(observedAt, latest(actual)!!.observedAtElapsedRealtimeMs)
                assertFalse(actual.rawCameraAssessmentAllowsNewOutput(latest(actual), epoch, profile,
                    SystemClock.elapsedRealtime()))

                val missing = CameraFrameQualityPolicy.assess(epoch, SystemClock.elapsedRealtime(), null, profile)
                assertEquals(EnvironmentEvidenceStatus.UNKNOWN, missing.status)
                assertEquals(CameraFrameQualityReason.MISSING_OBSERVATION, missing.reason)
                assertNull(missing.epoch)
                assertNull(missing.observedAtElapsedRealtimeMs)
                assertTrue(actual.observeRuntimeRawCameraAssessment(missing, SystemClock.elapsedRealtime()))
                assertSame(missing, latest(actual))
                assertNull(latest(actual)!!.epoch)
                assertNull(latest(actual)!!.observedAtElapsedRealtimeMs)
                assertFalse(actual.rawCameraAssessmentAllowsNewOutput(latest(actual), epoch, profile,
                    SystemClock.elapsedRealtime()))
                assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
                assertEquals(epoch, lifecycle.snapshot().epoch)
                assertNull(lifecycle.snapshot().readiness)
                assertUnstartedOwners(actual)
            }
        } catch (caught: Throwable) { first = caught }
        finally {
            try { onMain(instrumentation) { main?.resetRuntimeRawCameraAssessment() } }
            catch (cleanup: Throwable) { if (first == null) first = cleanup else first!!.addSuppressed(cleanup) }
            try { context.close() }
            catch (cleanup: Throwable) { if (first == null) first = cleanup else first!!.addSuppressed(cleanup) }
        }
        first?.let { throw it }
    }

    private fun observation(epoch: WalkRuntimeEpoch, at: Long, available: Boolean?, plane: ByteBuffer?): CameraFrameQualityObservation {
        val luma = if (available == true && plane != null) CameraLumaMeasurementPolicy.measure(
            plane, width = 2, height = 2, rowStride = 6, pixelStride = 2) else null
        // Controlled raw inputs go through original math, not a constructed PASS assessment.
        val shake = if (available == true) PhoneMountingSensorMath.angularShakeDegreesPerSecond(0.0, 0.0, 0.0) else null
        val pitch = if (available == true) PhoneMountingSensorMath.cameraPitchFromHorizontalDegrees(0.0, 9.81, 0.0) else null
        return CameraFrameQualityObservation(epoch, at, available, luma?.normalizedBrightness,
            luma?.darkOrOccludedFraction, shake, pitch)
    }
    private fun latest(main: MainActivity) = field(main, "latestRuntimeRawCameraAssessment") as CameraFrameQualityAssessment?
    private fun assertUnstartedOwners(main: MainActivity) {
        listOf("feedbackActuator", "rawCollectionRuntimeCoordinator", "fieldSessionLog", "routeSnapshotIoLease",
            "fusedLocationClient", "stepTracker", "session",
            "frameProvider", "speechRecognizer").forEach { assertNull("Unstarted own resource: $it", field(main, it)) }
        val position = (field(main, "positionFieldController\$delegate") as Lazy<*>).value as PositionFieldRecorderController
        assertNull("Unstarted position store", field(position, "recorder"))
        assertNull("No owned position recording", field(position, "recording"))
        assertNull("No initialized local position scope", field(position, "localScopeId"))
        assertFalse(position.explicitChestConfirmed)
        assertFalse(position.hasPendingExport)
        val controls = position.controls { null }
        assertFalse(controls.active)
        assertFalse(controls.canExport)
    }
    private fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name)
        .apply { isAccessible = true }.get(target)
    private fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
        .apply { isAccessible = true }.set(main, value)
    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(action()) } catch (caught: Throwable) { failure.set(caught) } }
        failure.get()?.let { throw it }
        return result.get()
    }
    private class OwnedContext(base: Context) : ContextWrapper(base) {
        private val id = UUID.randomUUID().toString()
        val root = File(base.noBackupFilesDir, "camera-raw-quality-$id").canonicalFile
        private val files = File(root, "files")
        private val noBackup = File(root, "no-backup")
        private val names = mutableMapOf<String, String>()
        fun prepare() {
            assertFalse(root.exists())
            assertTrue(files.mkdirs())
            assertTrue(noBackup.mkdir())
            assertEquals(Process.myUid(), Os.stat(root.path).st_uid)
            assertFalse(Files.isSymbolicLink(root.toPath()))
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
        @Synchronized override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(names.getOrPut(name) { "camera-raw-quality-$id-$name" }, mode)
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (caught: Throwable) {
                if (first == null) first = caught else first!!.addSuppressed(caught)
            } }
            names.values.forEach { name -> cleanup { assertTrue(baseContext.deleteSharedPreferences(name)) } }
            cleanup { if (root.exists()) assertTrue(root.deleteRecursively()) }
            first?.let { throw it }
        }
    }
}
