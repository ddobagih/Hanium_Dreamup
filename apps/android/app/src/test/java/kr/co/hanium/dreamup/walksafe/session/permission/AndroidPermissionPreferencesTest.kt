package kr.co.hanium.dreamup.walksafe.session.permission

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AndroidPermissionPreferencesTest {
    private class Store {
        val values = mutableMapOf<String, Any>()
        val events = mutableListOf<String>()
        var commitResult = true
        var ready = true
        var reads = 0
        val prefs: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
        fun editor(): SharedPreferences.Editor {
            val puts = linkedMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean" -> { puts[args!![0] as String] = args[1]; events += method.name + ":" + args[0]; proxy }
                    "remove" -> { puts[args!![0] as String] = null; events += "remove:" + args[0]; proxy }
                    "commit" -> { puts.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; events += "commit"; commitResult }
                    else -> error("Unexpected editor method ${method.name}")
                }
            } as SharedPreferences.Editor
        }
        val adapter = AndroidPermissionPreferences({ reads++; prefs }, { ready }, "state", "items", "started", "completed")
    }

    @Test fun constructionAndReadinessDoNotReadPreferencesBeforeInitialized() {
        val s = Store(); s.ready = false
        assertFalse(s.adapter.ready()); assertEquals(0, s.reads)
    }

    @Test fun invalidOrMissingPersistedStateRestoresClear() {
        val s = Store()
        assertEquals(PermissionRecoveryGate(), s.adapter.restoreRecovery())
        s.values["state"] = "unrecognized"
        assertEquals(PermissionRecoveryGate(), s.adapter.restoreRecovery())
    }

    @Test fun awaitingResumeIsRestoredBlockedWithoutCarryingEphemeralBinding() {
        val s = Store(); s.values["state"] = "AWAITING_EXPLICIT_RESUME"; s.values["items"] = "CAMERA,unrecognized,MICROPHONE"
        val gate = s.adapter.restoreRecovery()
        assertEquals(PermissionRecoveryGateState.BLOCKED, gate.state)
        assertEquals(setOf(ObservedPermission.CAMERA, ObservedPermission.MICROPHONE), gate.affectedPermissions)
        assertNull(gate.recheckBinding)
    }

    @Test fun nonclearPersistenceSortsNamesAndCommitsBothFieldsTogether() {
        val s = Store(); val gate = PermissionRecoveryGate().blocked(linkedSetOf(ObservedPermission.MICROPHONE, ObservedPermission.CAMERA))
        s.adapter.persistRecovery(gate)
        assertEquals("BLOCKED", s.values["state"]); assertEquals("CAMERA,MICROPHONE", s.values["items"])
        assertEquals(listOf("putString:state", "putString:items", "commit"), s.events)
    }

    @Test fun clearingRecoveryRemovesBothKeysWithoutWritingAnUnscopedAwaitingState() {
        val s = Store(); s.values["state"] = "BLOCKED"; s.values["items"] = "CAMERA"
        s.adapter.persistRecovery(PermissionRecoveryGate())
        assertFalse(s.values.containsKey("state")); assertFalse(s.values.containsKey("items"))
        assertEquals(listOf("remove:state", "remove:items", "commit"), s.events)
    }

    @Test fun initialCompletionReturnsCommitResultAndRemovesStartedInTheSameEdit() {
        val s = Store(); s.values["started"] = true; s.commitResult = false
        assertFalse(s.adapter.markInitialCompleted())
        assertEquals(listOf("putBoolean:completed", "remove:started", "commit"), s.events)
    }
}
