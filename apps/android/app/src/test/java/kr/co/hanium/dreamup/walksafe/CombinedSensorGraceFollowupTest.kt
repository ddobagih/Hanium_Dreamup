package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RouteSpeechCueToken
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main/GPS/Nav/Guide/Output owners with controlled SDK/native leaves. No field claim. */
class CombinedSensorGraceFollowupTest {
    @After fun retireGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
    @Test fun simultaneousGpsAndDirectionNoiseKeepsTheCurrentSentenceAndIndependentDeadlines() = withFixture { f ->
        call(f,"start")
        val inner=field(f,"fixture")!!
        val clock=field(f,"clock")!!
        val nav=field(inner,"navigator") as RouteNavigator
        val native=field(f,"native")!!
        val positions=field(f,"position") as PositioningSessionController
        val original=positions.trustedLocation
        val cue=field(nav,"pendingSpeechCue") as RouteSpeechCueToken
        val before=field(native,"stops")
        call(clock,"advanceBy",100L)
        call(inner,"unavailableHeadingTick")
        call(f,"location",Float.NaN,37.5,0L,true)
        val directionDeadline=requireNotNull(nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        val base=field(f,"base")!!
        val gate=call(base,"getGate")!!
        val gpsDeadline=call(gate,"pendingFailureDeadlineElapsedRealtimeMs")
        assertNotNull(gpsDeadline)
        assertSame(original,positions.trustedLocation)
        assertEquals(before,field(native,"stops"))
        repeat(3) {
            call(clock,"advanceBy",300L)
            call(inner,"unavailableHeadingTick")
            call(f,"location",Float.NaN,37.5,0L,true)
            assertEquals(directionDeadline,nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
            assertEquals(gpsDeadline,call(gate,"pendingFailureDeadlineElapsedRealtimeMs"))
            assertSame(original,positions.trustedLocation)
            assertEquals(before,field(native,"stops"))
            assertEquals(cue,field(nav,"pendingSpeechCue"))
        }
        call(clock,"advanceBy",300L)
        call(inner,"headingTick",90.0,12.0)
        assertEquals("GRACE_RETAINED",nav.currentDirectionGraceSnapshot()?.mode?.name)
        assertEquals(directionDeadline,nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        call(inner,"headingTick",90.0,12.0)
        assertEquals("GRACE_RETAINED",nav.currentDirectionGraceSnapshot()?.mode?.name)
        assertEquals(directionDeadline,nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        call(clock,"advanceBy",21L)
        call(inner,"headingTick",90.0,12.0)
        assertEquals("NORMAL",nav.currentDirectionGraceSnapshot()?.mode?.name)
        assertEquals(gpsDeadline,call(gate,"pendingFailureDeadlineElapsedRealtimeMs"))
        assertEquals(before,field(native,"stops"))
        val action=(field(native,"spoken") as List<*>).last()!!
        call(native,"done",field(action,"id")!!)
        assertEquals(cue,field(nav,"completedSpeechCue"))
        assertSame(original,positions.trustedLocation)
        assertEquals(before,field(native,"stops"))
    }
    private fun withFixture(run:(Any)->Unit) {
        val type=Class.forName(GpsMeasuredFailureNativeRegressionTest::class.java.name+"$"+"Fixture")
        val f=type.getDeclaredConstructor().apply{isAccessible=true}.newInstance()
        try {run(f)} finally {call(field(f,"output")!!,"closeActuator")}
    }
    private fun field(o:Any,n:String):Any?=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
    private fun call(o:Any,n:String,vararg args:Any):Any?=try {
        o.javaClass.declaredMethods.single{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)
    } catch(failure:InvocationTargetException){throw failure.targetException}
}
