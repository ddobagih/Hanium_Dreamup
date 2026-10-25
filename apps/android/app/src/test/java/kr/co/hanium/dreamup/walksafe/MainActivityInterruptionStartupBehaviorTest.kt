package kr.co.hanium.dreamup.walksafe

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Main persistence + lifecycle; Android onCreate/dialog rendering and device storage are not executed. */
class MainActivityInterruptionStartupBehaviorTest {
    @Test fun oldInterruptionDoesNotRequireAnAcknowledgementToRecordANewExplicitWalk() {
        val f = Fixture()
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        assertTrue(f.persist())
        assertEquals(false, f.values["walk_session_interrupted"])
        assertFalse(f.values.containsKey("walk_session_interruption_v2"))
        f.startExplicitly()
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        assertTrue(f.persist())
        assertEquals(true, f.values["walk_session_interrupted"])
        val record = WalkInterruptionRecord.restore(f.values["walk_session_interruption_v2"] as String, true)!!
        assertEquals("fresh-explicit-walk", record.walkSessionId)
        assertEquals(WalkInterruptionLastState.ACTIVE, record.lastState)
        f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertTrue(f.persist())
        assertEquals(WalkInterruptionLastState.PAUSED,
            WalkInterruptionRecord.restore(f.values["walk_session_interruption_v2"] as String, true)!!.lastState)
        f.lifecycle.handle(WalkSessionEvent.EndRequested)
        assertTrue(f.persist())
        assertEquals(false, f.values["walk_session_interrupted"])
        assertFalse(f.values.containsKey("walk_session_interruption_v2"))
    }

    @Test fun safetyStopStillRecordsTheCurrentWalkAndCommitFailureReturnsFalse() {
        val f = Fixture()
        f.startExplicitly()
        f.lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        assertTrue(f.persist())
        assertEquals(true, f.values["walk_session_interrupted"])
        assertEquals(WalkInterruptionLastState.SAFE_STOP,
            WalkInterruptionRecord.restore(f.values["walk_session_interruption_v2"] as String, true)!!.lastState)
        f.commitSucceeds = false
        assertFalse(f.persist())
    }

    private class Fixture {
        val main = MainActivity()
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "fresh-explicit-walk" }, clock = { 100L })
        val values = mutableMapOf<String, Any>("walk_session_interrupted" to true,
            "walk_session_interruption_v2" to WalkInterruptionRecord.LEGACY.encode())
        var commitSucceeds = true
        init {
            val changes = mutableMapOf<String, Any?>()
            val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putBoolean", "putString" -> { changes[args!![0] as String] = args[1]; proxy }
                    "remove" -> { changes[args!![0] as String] = null; proxy }
                    "commit" -> {
                        if (commitSucceeds) changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        changes.clear()
                        commitSucceeds
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, _ ->
                check(method.name == "edit") { "Unexpected prefs call: ${method.name}" }
                editor
            } as SharedPreferences
            for ((name, value) in listOf("walkSessionLifecycle" to lifecycle, "stepLengthPrefs" to prefs,
                "previousProcessHadInterruptedWalk" to true)) {
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(main, value)
            }
        }
        fun persist(): Boolean = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, "persistWalkSessionInterruptionMarker",
                MethodType.methodType(java.lang.Boolean.TYPE))
            .bindTo(main).invokeWithArguments() as Boolean
        fun startExplicitly() {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val collector = WalkSessionReadinessCollector(epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(100L)))
            assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
        }
    }
}
