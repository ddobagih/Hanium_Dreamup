package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import org.junit.Assert.*
import org.junit.Test

/** Same admitted SDK owner and actual Main/Guide/native path as the maintained freshness fixture. */
class GpsObsoleteStaleNativeRegressionTest {
    @Test fun obsoleteStaleDuringGpsGraceKeepsTheRealPositionCueAndFirstDeadline() {
        val type = Class.forName(GpsFreshnessIntersectionReviewTest::class.java.name + "$" + "Fixture")
        val f = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val position = read<PositioningSessionController>(f, "position")
        val base = read<MainActivityContinuousLocationStartBehaviorTest.Fixture>(f, "base")
        val helper = read<Any>(f, "instance")
        val output = read<kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController>(helper, "output")
        try { withSdk17 {
            call<Unit>(f, "start")
            val acquiredAt = base.now
            call<Unit>(f, "location", 15f, true, acquiredAt)
            val gps = MainActivityControllerTestFixture.controller(base.main, "continuousGpsSession")
            assertNotNull("The real callback must establish accepted source proof",
                read<Any?>(read<Any>(gps, "acceptedGnssSamples"), "accepted"))
            repeat(7) { call<Unit>(f, "step", 300L) }
            val original = requireNotNull(position.trustedLocation)
            val raw = position.rawRouteLocation
            val pass = call<Any?>(f, "evidence")
            val guide = call<RouteGuidanceController>(f, "guide")
            val run = read<NavigationRunState>(guide, "navigationRunState")
            val cue = read<RouteSpeechCueToken>(run.navigator, "pendingSpeechCue")
            val pause = run.directionPauseReason
            val binding = position.binding; val callback = position.callback
            val epoch = base.lifecycle.snapshot().epoch
            call<Unit>(f, "step", 100L); val onset = base.now
            call<Unit>(f, "location", Float.NaN, true, base.now)
            call<Unit>(f, "step", 300L); call<Unit>(helper, "location", 15f, 37.6, 0L, true)
            call<Unit>(f, "step", 300L)
            val obsoleteAt = base.now - 10_001L
            assertTrue(base.now - original.elapsedRealtimeMs < 10_000L)
            call<Unit>(f, "location", 15f, true, obsoleteAt)
            fun retained() {
                assertSame(original, position.trustedLocation); assertSame(raw, position.rawRouteLocation)
                assertSame(pass, call<Any?>(f, "evidence"))
                assertEquals(onset + GuidanceRecoveryTiming.GPS_GRACE_MS, base.gate.pendingFailureDeadlineElapsedRealtimeMs())
                assertEquals(cue, read<Any?>(run.navigator, "pendingSpeechCue"))
                assertEquals(pause, run.directionPauseReason)
                assertFalse(guide.positionPaused); assertEquals(0, call<Int>(f, "stops"))
                assertSame(binding, position.binding); assertSame(callback, position.callback)
                assertEquals(epoch, base.lifecycle.snapshot().epoch)
            }
            retained()
            call<Unit>(f, "step", 300L); retained()
            val native = read<Any>(helper, "native")
            val action = read<List<Any>>(native, "spoken").last()
            call<Unit>(native, "done", read<String>(action, "id"))
            assertEquals(cue, read<Any?>(run.navigator, "completedSpeechCue"))
            assertEquals(0, call<Int>(f, "stops"))
            call<Unit>(f, "location", 15f, true, base.now)
            assertEquals(base.now, position.trustedLocation?.elapsedRealtimeMs)
            assertNull(base.gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertEquals(acquiredAt, original.elapsedRealtimeMs)
        } } finally {
            output.closeActuator()
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        }
    }
    private fun withSdk17(block: () -> Unit) {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val field = android.os.Build.VERSION::class.java.getDeclaredField("SDK_INT")
        val owner = unsafe.staticFieldBase(field); val offset = unsafe.staticFieldOffset(field)
        val previous = unsafe.getInt(owner, offset); unsafe.putInt(owner, offset, 17)
        try { block() } finally { unsafe.putInt(owner, offset, previous) }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    @Suppress("UNCHECKED_CAST") private fun <T> call(owner: Any, name: String, vararg args: Any?): T = try {
        owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(owner, *args) as T
    } catch (failure: InvocationTargetException) { throw failure.targetException }
}
