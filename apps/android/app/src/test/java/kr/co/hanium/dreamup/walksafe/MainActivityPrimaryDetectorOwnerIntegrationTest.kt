package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryLoadedDetector
import kr.co.hanium.dreamup.walksafe.inference.DetectorStallWatchdog
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.inference.NativeDetectorFlight
import kr.co.hanium.dreamup.walksafe.inference.NoopAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryDetectorExecutionOwner
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryNativeFlightScheduler
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.*
import org.junit.Test

/** Executes actual private proposed-Main adapters against its actual injected state owner. */
class MainActivityPrimaryDetectorOwnerIntegrationTest {
    @Test fun activityConstructionAndFirstOwnerObservationDoNotCreateALooperOrInvokeANativeDetector() {
        val main = MainActivity()
        val owner = field(main, "primaryDetectorOwner\$delegate") as Lazy<*>
        val state = (owner.value as PrimaryDetectorExecutionOwner).projection()
        assertTrue(state.detector is NoopAndroidFrameDetector)
        assertEquals(0, state.frameGeneration)
        assertFalse(state.workInFlight)
        assertFalse(state.available)
    }

    @Test fun actualPrivateAdmissionCallerChangesTheActualOwnerAndCountsOnlyDueDrops() {
        val f = Fixture()
        assertNotNull(f.start(30L))
        assertTrue(f.owner.projection().workInFlight)
        assertNull(f.start(31L))
        assertEquals(0, f.drops)
        assertNull(f.start(f.owner.pacingSnapshot().nextEligibleAtElapsedRealtimeMs))
        assertEquals(1, f.drops)
    }

    @Test fun actualPrivateCancellationReleasesPhysicalWorkWithoutReadingMainLoadObserversOrTheClock() {
        val f = Fixture()
        val ticket = requireNotNull(f.start(0L))
        f.clockAllowed = false
        call(f.main, "finishDetectionWork",
            arrayOf(InferencePacingTicket::class.java, Boolean::class.javaPrimitiveType!!,
                java.lang.Long::class.java, Boolean::class.javaPrimitiveType!!),
            ticket, false, null, false)
        assertFalse(f.owner.projection().workInFlight)
        assertEquals(0, f.clockReads)
        assertNull(f.start(0L))
        assertNotNull(f.start(f.owner.pacingSnapshot().nextEligibleAtElapsedRealtimeMs))
    }

    @Test fun actualNativeFlightAdaptersRetainOriginalDeadlineAndOneStopClaim() {
        val f = Fixture()
        val epoch = WalkRuntimeEpoch("main-primary", 3L)
        val flight = call(f.main, "beginNativeDetectorFlight",
            arrayOf(WalkRuntimeEpoch::class.java, Int::class.javaPrimitiveType!!), epoch, 5) as NativeDetectorFlight
        assertEquals(800L, flight.ticket.deadlineElapsedRealtimeMs)
        f.now = 800L
        flight.timeout.run()
        call(f.main, "completeNativeDetectorFlight", arrayOf(NativeDetectorFlight::class.java), flight)
        call(f.main, "requestNativeDetectorStall", arrayOf(NativeDetectorFlight::class.java), flight)
        assertEquals(listOf(epoch to 5), f.stalls)
        assertTrue(f.owner.nativeWorkBlocked())
    }

    @Test fun existingInjectedWatchdogEntrySeamStillUsesItsExactWatchdogAndDefaultUsesTheActualOwner() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("entry", 7L)
        var now = 0L
        val expired = DetectorStallWatchdog(800L) { now }
        now = 0L
        val owned = requireNotNull(expired.begin(epoch))
        now = 800L
        expired.complete(owned)
        var stops = 0
        assertTrue(main.rejectUnsafeDetectorAtWalkEntry(epoch, WalkSessionState.ACTIVE, expired) { stops += 1 })
        assertEquals(1, stops)
        assertFalse(main.rejectUnsafeDetectorAtWalkEntry(epoch, WalkSessionState.ACTIVE) { error("unblocked original owner") })
    }

    @Test fun actualStatusExtensionInterpolatesTheOwnerAvailabilityAsOriginalBooleans() {
        val f = Fixture()
        val gate = DeviceGateState()
        fun status() = call(f.main, "statusText", arrayOf(DeviceGateState::class.java), gate) as String
        assertTrue(status().endsWith("detector=false"))
        assertFalse(status().contains("PrimaryDetectorExecutionOwner"))
        f.owner.admitLoadedOnMain(f.owner.beginLoad(), PrimaryLoadedDetector(null, true)) { false }
        assertTrue(status().endsWith("detector=true"))
        assertFalse(status().contains("projection().available"))
        assertEquals(0, f.clockReads)
    }

    private class Fixture {
        val main = MainActivity()
        val scheduler = object : PrimaryNativeFlightScheduler {
            override fun postDelayed(task: Runnable, delayMs: Long) = true
            override fun remove(task: Runnable) = Unit
        }
        var now = 0L
        var clockReads = 0
        var clockAllowed = true
        var drops = 0
        val stalls = mutableListOf<Pair<WalkRuntimeEpoch, Int>>()
        val owner = PrimaryDetectorExecutionOwner(field(main, "frameStateLock")!!, Lane(), Lane(),
            { check(clockAllowed); clockReads += 1; now }, scheduler, { false }, { false },
            { drops += 1 }, { epoch, generation -> stalls += epoch to generation }, { it() })
        init {
            set(main, "primaryDetectorOwner\$delegate", lazyOf(owner))
        }
        fun start(nowMs: Long) = call(main, "tryStartDetectionWork", arrayOf(Long::class.javaPrimitiveType!!), nowMs)
            as InferencePacingTicket?
    }

    private class Lane : AbstractExecutorService() {
        private var closed = false
        override fun execute(command: Runnable) = error("this fixture does not submit native work")
        override fun shutdown() { closed = true }
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = closed
        override fun isTerminated() = closed
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
    }

    companion object {
        private fun primaryAlias(target: Any, name: String): Pair<Any, java.lang.reflect.Field>? {
            if (target !is MainActivity || name != "primaryDetectorOwner\$delegate") return null
            val composition = target.javaClass.getDeclaredField("visionPrimaryComposition").apply { isAccessible = true }.get(target)
            return composition to composition.javaClass.getDeclaredField("detector").apply { isAccessible = true }
        }
        private fun field(target: Any, name: String): Any? {
            val alias = primaryAlias(target, name)
                ?: return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            val actual = alias.second.get(alias.first) as Lazy<*>
            val backing = try { target.javaClass.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) { null }
            val observed = if (backing != null) backing.get(target) else {
                MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                    .findStatic(MainActivity::class.java, "getPrimaryDetectorOwner\$delegate",
                        MethodType.methodType(Any::class.java, MainActivity::class.java)).invokeWithArguments(target)
            }
            check(observed === actual) { "Main alias must be the actual composition Lazy: $name" }
            return actual
        }
        private fun set(target: Any, name: String, value: Any?) {
            val alias = primaryAlias(target, name)
            if (alias == null) {
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
                return
            }
            check(value is Lazy<*>)
            alias.second.set(alias.first, value)
            val backing = try { target.javaClass.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) { null }
            backing?.set(target, value)
            check(field(target, name) === value) { "Fixture must retain the same actual Lazy" }
        }
        private fun call(target: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
            val returnType = when (name) {
                "tryStartDetectionWork" -> InferencePacingTicket::class.java
                "beginNativeDetectorFlight" -> NativeDetectorFlight::class.java
                "statusText" -> String::class.java
                "finishDetectionWork", "completeNativeDetectorFlight", "requestNativeDetectorStall" -> Void.TYPE
                else -> error("No exact return type for $name")
            }
            val lookup = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            return lookup.findVirtual(MainActivity::class.java, name, MethodType.methodType(returnType, types.toList()))
                .bindTo(target).invokeWithArguments(args.toList())
        }
    }
}
