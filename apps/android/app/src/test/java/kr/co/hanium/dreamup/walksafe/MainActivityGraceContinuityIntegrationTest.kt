package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RouteSpeechCueToken
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main/Session/Guide/Output/Actuator with controlled SDK/native leaves, not physical hearing. */
class MainActivityGraceContinuityIntegrationTest {
    @After fun retireGateway() = GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation).let { Unit }

    @Test fun nativeStartAfterNormalLeaseExpiryWithinGraceKeepsTheExactQueuedPacket() = withFixture { f ->
        val action = call(f, "startWithNativeIntro")!!
        call(f, "connectCompassEvents")
        val navigator = field(f, "navigator") as RouteNavigator
        val cue = field(navigator, "pendingSpeechCue") as RouteSpeechCueToken
        val native = field(f, "native")!!
        val clock = field(f, "clock")!!
        val before = field(native, "stops")
        call(clock, "advanceBy", 100L); call(f, "headingTick", 340.0, 30.0)
        val first = requireNotNull(navigator.currentDirectionGraceSnapshot())
        assertEquals("GRACE_RETAINED", first.mode.name)
        call(clock, "advanceBy", 600L); call(f, "headingTick", 340.0, 30.0)
        assertEquals("Queued watchdog must not stop before native START", before, field(native, "stops"))
        assertTrue((call(clock, "getNow") as Long) > requireNotNull(cue.facingValidUntilElapsedRealtimeMs))
        call(native, "start", field(action, "id")!!)
        assertEquals("The authorized same packet starts without cancelling its first syllable", before, field(native, "stops"))
        assertEquals(cue, field(navigator, "pendingSpeechCue"))
        assertEquals(first.deadlineElapsedRealtimeMs, requireNotNull(navigator.currentDirectionGraceSnapshot()).deadlineElapsedRealtimeMs)
        call(native, "done", field(action, "id")!!)
        assertEquals(cue, field(navigator, "completedSpeechCue"))
    }

    @Test fun smallGpsMovementDoesNotCancelRetainedDirectionDuringGrace() = withFixture { f ->
        val action = call(f, "startWithNativeIntro")!!; call(f, "connectCompassEvents")
        val native = field(f, "native")!!; val clock = field(f, "clock")!!
        val navigator = field(f, "navigator") as RouteNavigator
        call(clock, "advanceBy", 100L); call(f, "face"); call(native, "start", field(action, "id")!!)
        val before = field(native, "stops")
        call(clock, "advanceBy", 100L); call(f, "headingTick", 340.0, 30.0)
        val first = requireNotNull(navigator.currentDirectionGraceSnapshot())
        call(clock, "advanceBy", 300L); call(f, "publishWalkingGps", 0.5, 15.0)
        assertEquals(before, field(native, "stops"))
        assertEquals(first.deadlineElapsedRealtimeMs, requireNotNull(navigator.currentDirectionGraceSnapshot()).deadlineElapsedRealtimeMs)
        call(clock, "advanceBy", 100L); call(f, "headingTick", 0.0, 12.0)
        assertEquals(before, field(native, "stops"))
        call(native, "done", field(action, "id")!!)
        assertNotNull(field(navigator, "completedSpeechCue"))
    }

    @Test fun confirmedReversalStillCancelsWithinGraceAndOldDoneCannotConsumeTheCue() = withFixture { f ->
        val action = call(f, "startWithNativeIntro")!!; call(f, "connectCompassEvents")
        val native = field(f, "native")!!; val clock = field(f, "clock")!!
        val navigator = field(f, "navigator") as RouteNavigator
        call(clock, "advanceBy", 100L); call(f, "face"); call(native, "start", field(action, "id")!!)
        val before = field(native, "stops") as Int
        call(clock, "advanceBy", 100L); call(f, "headingTick", 180.0, 3.0)
        assertTrue("A genuinely opposite direction invalidates the old packet", (field(native, "stops") as Int)>before)
        call(native, "done", field(action, "id")!!)
        assertNull(field(navigator, "completedSpeechCue"))
    }

    private fun withFixture(run: (Any) -> Unit) {
        val type = Class.forName(MovingDirectionGraceReviewTest::class.java.name + "$" + "Fixture")
        val f = type.getDeclaredConstructor(Double::class.javaPrimitiveType, java.lang.Double::class.java,
            Double::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(15.0, 12.0, 0.0)
        try { run(f) } finally { call(field(f, "output")!!, "closeActuator") }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun call(owner: Any, name: String, vararg args: Any): Any? {
        val method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        return try { method.apply { isAccessible = true }.invoke(owner, *args) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
}
