package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianPositionEstimatorConfig
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/** Actual Main Location bridge; SDK permissions/services stay real, transport and login receipts are controlled. */
@RunWith(AndroidJUnit4::class)
class MainActivityGnssOriginalAccuracyAndroidTest {
    @Test fun suppliedNaNIsHardRejectedWithoutAdvancingAcceptedPosition() = rejectedAccuracy(Float.NaN)
    @Test fun suppliedPositiveInfinityIsHardRejectedWithoutAdvancingAcceptedPosition() = rejectedAccuracy(Float.POSITIVE_INFINITY)
    @Test fun suppliedNegativeInfinityIsHardRejectedWithoutAdvancingAcceptedPosition() = rejectedAccuracy(Float.NEGATIVE_INFINITY)
    @Test fun suppliedNegativeAccuracyIsHardRejectedWithoutAdvancingAcceptedPosition() = rejectedAccuracy(-1f)

    @Test fun missingZeroAndFiniteAccuracyKeepTheirOriginalReportedAndDefaultSigmaMeanings() {
        for (accuracy in listOf<Float?>(null, 0f, 2f)) withFixture { f ->
            val fix = onMain(f.instrumentation) { f.location(accuracy) }
            val beforeLogs = f.gnssLogs().size
            onMain(f.instrumentation) { f.deliver(fix) }
            assertEquals(1, f.gnssLogs().drop(beforeLogs).size)
            assertFalse(f.gnssLogs().last().contains("HARD_REJECTED"))
            onMain(f.instrumentation) {
                val raw = if (accuracy == null) {
                    MainActivity::class.java.getDeclaredMethod("rawAndroidRouteLocation",
                        java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Float::class.java, java.lang.Long.TYPE)
                        .apply { isAccessible = true; check(returnType == TrustedLocation::class.java) }
                        .invoke(f.main, fix.latitude, fix.longitude, null,
                            fix.elapsedRealtimeNanos / 1_000_000L) as TrustedLocation
                } else field(f.main, "latestRawRouteLocation") as TrustedLocation
                assertEquals(accuracy ?: Float.MAX_VALUE, raw.accuracyM)
                assertEquals(fix.elapsedRealtimeNanos / 1_000_000L, f.checkpoint().acceptedAt)
                val config = field(f.estimator, "config") as PedestrianPositionEstimatorConfig
                val expectedSigma = if (accuracy == null) config.missingAccuracySigmaM
                    else if (accuracy == 0f) config.minimumGnssSigmaM else accuracy.toDouble()
                assertEquals(expectedSigma, sqrt(f.checkpoint().covariance.first()), 1e-6)
                if (accuracy == null) {
                    assertNull("An accepted missing-accuracy estimator fix cannot publish a route position",
                        field(f.main, "latestTrustedLocation"))
                    assertNull("The raw scalar conversion is not a route publication",
                        field(f.main, "latestRawRouteLocation"))
                } else {
                    val trusted = field(f.main, "latestTrustedLocation") as TrustedLocation
                    assertEquals(expectedSigma.toFloat(), trusted.accuracyM, 1e-5f)
                }
                assertEquals(f.generation, f.coordinator.currentInputGeneration())
            }
        }
    }

    private fun rejectedAccuracy(accuracy: Float) = withFixture { f ->
        val seed = onMain(f.instrumentation) { f.location(2f).also(f::deliver) }
        val before = onMain(f.instrumentation) {
            assertNotNull(field(f.main, "latestTrustedLocation"))
            assertNotNull(field(f.main, "latestRawRouteLocation"))
            f.checkpoint()
        }
        while (SystemClock.elapsedRealtime() <= seed.elapsedRealtimeNanos / 1_000_000L) Thread.sleep(1L)
        val invalid = onMain(f.instrumentation) { f.location(accuracy) }
        val beforeLogs = f.gnssLogs().size
        onMain(f.instrumentation) { f.deliver(invalid) }
        val actual = f.gnssLogs().drop(beforeLogs)
        assertEquals("Actual Main diagnostic must identify exactly this callback", 1, actual.size)
        assertTrue("Supplied $accuracy must remain invalid at the actual Main estimator boundary: $actual",
            actual.single().contains("gnss disposition=HARD_REJECTED reason=INVALID_ACCURACY"))
        onMain(f.instrumentation) {
            assertEquals("A rejected raw fix may not advance the estimator/accepted GNSS watermark", before, f.checkpoint())
            // Main output invalidation differs from preserving the estimator's accepted state.
            assertNull(field(f.main, "latestTrustedLocation"))
            assertNull(field(f.main, "latestRawRouteLocation"))
            assertEquals(f.generation, f.coordinator.currentInputGeneration())
        }
    }

    private data class Checkpoint(val state: List<Double>, val covariance: List<Double>, val at: Long?,
        val acceptedAt: Long?, val informativeAt: Long?)

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val coordinator: PositioningCoordinator
        val estimator: Any
        val generation: Long
        private val ownerClass = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner")
        private val bindingClass = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding")
        private var gatewayGeneration: Long? = null
        private lateinit var owner: Any
        private lateinit var binding: Any
        private var locationGeneration = 0

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main GNSS original accuracy probe",
                null, null, null) as MainActivity
            coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
            estimator = field(coordinator, "estimator")!!
            generation = coordinator.currentInputGeneration()
        }

        fun prepare() {
            assertTrue(BuildConfig.DEBUG)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            val context = instrumentation.targetContext
            setField(main, "walkSessionLifecycle", WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime))
            assertEquals("SDK fixture requires an actual FINE permission grant", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue("SDK fixture requires the real location service", requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            assertTrue(restored.fullyRestored)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_gnss_accuracy_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, restored.snapshot))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            setField(main, "firstRunOnboardingSnapshot", restored.snapshot)
            setField(main, "isActivityForeground", true)
            owner = requireNotNull(invoke("currentLocationCollectionOwnerOrNull", ownerClass))
            locationGeneration = field(main, "locationCallbackGeneration") as Int
            binding = bindingClass.getDeclaredConstructor(PositioningCoordinator::class.java, java.lang.Long.TYPE,
                ownerClass, Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(coordinator, generation, owner, locationGeneration, SystemClock.elapsedRealtime())
            setField(main, "locationCollectionOwner", owner)
            setField(main, "positioningCollectionBinding", binding)
            setField(main, "locationCallback", object : LocationCallback() {}) // Controlled transport; never registered with Fused.
            assertEquals(true, invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE, arrayOf(bindingClass), binding))
            assertEquals(false, invoke("currentNavigationCollectionAllowsWork", java.lang.Boolean.TYPE))
            assertEquals(false, invoke("currentPositionFieldCollectionAllowsWork", java.lang.Boolean.TYPE))
        }

        fun location(accuracy: Float?): Location = Location("gps").apply {
            latitude = 37.0; longitude = 127.0
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (accuracy != null) this.accuracy = accuracy
            assertEquals(accuracy != null, hasAccuracy())
            if (accuracy != null) assertEquals("Android must retain the supplied accuracy before Main runs",
                accuracy.toDouble(), this.accuracy.toDouble(), 0.0)
            assertFalse(isMock)
        }

        fun deliver(location: Location) = invoke("handleLocationUpdate", Void.TYPE,
            arrayOf(Location::class.java, ownerClass, Integer.TYPE, bindingClass, java.lang.Long.TYPE),
            location, owner, locationGeneration, binding, SystemClock.elapsedRealtime())

        fun checkpoint() = Checkpoint((field(estimator, "state") as DoubleArray).toList(),
            (field(estimator, "covariance") as DoubleArray).toList(), field(estimator, "stateElapsedRealtimeMs") as Long?,
            field(estimator, "lastAcceptedGnssElapsedRealtimeMs") as Long?, field(estimator, "lastInformativeGnssElapsedRealtimeMs") as Long?)

        fun gnssLogs(): List<String> {
            check(Looper.myLooper() != Looper.getMainLooper())
            val command = "logcat -d --pid=${Process.myPid()} -v raw -s WalkSafePositioning:D *:S"
            return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readLines().filter { line -> line.startsWith("gnss disposition=") } }
        }

        private fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply {
                isAccessible = true; check(returnType == returns)
            }
            return try { method.invoke(main, *values) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }

        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (first == null) first = failure else first!!.addSuppressed(failure)
                }
            }
            cleanup { setField(main, "locationCallback", null) }
            cleanup { setField(main, "positioningCollectionBinding", null) }
            cleanup { setField(main, "locationCollectionOwner", null) }
            cleanup { setField(main, "isActivityForeground", false) }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
            first?.let { throw it }
        }
    }

    private companion object {
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            val sharedUid = context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId
            assertNull(sharedUid)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U kr.co.hanium.dreamup.walksafe")
            val original = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:kr\\.co\\.hanium\\.dreamup\\.walksafe\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            }
            assertEquals(1, original.size)
            assertNotEquals(original.single(), Process.myUid())
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            var first: Throwable? = null
            try { onMain(instrumentation) { f.prepare() }; test(f) }
            catch (failure: Throwable) { first = failure; throw failure }
            finally {
                try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
                catch (cleanup: Throwable) { if (first == null) throw cleanup else first.addSuppressed(cleanup) }
            }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val value = AtomicReference<T>(); val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { value.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }; return value.get()
        }
        fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(target)
        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
    }
}
