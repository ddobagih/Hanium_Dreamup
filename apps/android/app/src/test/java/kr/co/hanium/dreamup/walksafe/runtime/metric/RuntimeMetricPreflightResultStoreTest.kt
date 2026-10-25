package kr.co.hanium.dreamup.walksafe.runtime.metric

import android.content.SharedPreferences
import kr.co.hanium.dreamup.walksafe.device.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RuntimeMetricPreflightResultStoreTest {
    @Test fun constructionDoesNotReadPreferenceClockOrAndroidBuildFacts() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); assertTrue(f.persisted.isEmpty())
    }
    @Test fun originalKeysValuesClockAndApplyOrderArePreserved() {
        val f = Fixture(); f.store.persist(f.result, f.match)
        val expected = listOf("runtime_metric_preflight_policy", "runtime_metric_preflight_status", "runtime_metric_preflight_reason",
            "runtime_metric_preflight_generation", "runtime_metric_preflight_distinct_frames", "runtime_metric_preflight_passing_frames",
            "runtime_metric_preflight_observation_span_ms", "runtime_metric_preflight_completed_at_elapsed_ms",
            "runtime_metric_preflight_recorded_at_epoch_ms", "runtime_metric_preflight_app_version", "runtime_metric_preflight_source_commit",
            "runtime_metric_preflight_device_manufacturer", "runtime_metric_preflight_device_model", "runtime_metric_preflight_device_code",
            "runtime_metric_preflight_android_sdk", "runtime_metric_preflight_profile_approved", "runtime_metric_preflight_profile_id",
            "runtime_metric_preflight_profile_version", "runtime_metric_preflight_profile_reason", "runtime_metric_preflight_profile_registry_id",
            "runtime_metric_preflight_profile_registry_revision", "runtime_metric_preflight_profile_registry_sha256")
        assertEquals(expected, f.persisted.keys.toList())
        assertEquals("original-policy", f.persisted[expected[0]])
        assertEquals("UNKNOWN", f.persisted[expected[1]])
        assertEquals("LIFECYCLE_CANCELLED", f.persisted[expected[2]])
        assertEquals(5L, f.persisted[expected[3]]); assertEquals(3, f.persisted[expected[4]])
        assertEquals(2, f.persisted[expected[5]]); assertEquals(80L, f.persisted[expected[6]])
        assertEquals(-1L, f.persisted[expected[7]]); assertEquals(9_000L, f.persisted[expected[8]])
        assertEquals("version", f.persisted[expected[9]]); assertEquals("commit", f.persisted[expected[10]])
        assertEquals(35, f.persisted[expected[14]]); assertEquals(false, f.persisted[expected[15]])
        assertNull(f.persisted[expected[16]]); assertNull(f.persisted[expected[17]])
        assertEquals(WalkSafeApprovedDeviceProfiles.registryContentSha256, f.persisted[expected.last()])
        assertTrue(f.events.indexOf("write:${expected[7]}") < f.events.indexOf("clock"))
        assertTrue(f.events.indexOf("clock") < f.events.indexOf("write:${expected[8]}"))
        assertEquals("apply", f.events.last()); assertEquals(1, f.events.count { it == "clock" })
    }
    @Test fun originalCompletionStampIsStoredWithoutReclockingOrCoercion() {
        val f = Fixture(); f.store.persist(f.result.copy(completedAtElapsedRealtimeMs = 77), f.match)
        assertEquals(77L, f.persisted["runtime_metric_preflight_completed_at_elapsed_ms"])
        assertEquals(9_000L, f.persisted["runtime_metric_preflight_recorded_at_epoch_ms"])
    }
    @Test fun clockFailureKeepsItsOriginalWritePointAndDoesNotApplyPartialChanges() {
        val f = Fixture(); val failure = IllegalStateException("wall clock"); f.clockFailure = failure
        assertSame(failure, runCatching { f.store.persist(f.result, f.match) }.exceptionOrNull())
        assertEquals("clock", f.events.last()); assertFalse(f.events.contains("app-version"))
        assertTrue(f.persisted.isEmpty()); assertFalse(f.events.contains("apply"))
    }
    private class Fixture {
        val events = mutableListOf<String>(); val staged = linkedMapOf<String, Any?>(); val persisted = linkedMapOf<String, Any?>()
        var clockFailure: RuntimeException? = null
        lateinit var editor: SharedPreferences.Editor
        val preferences: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            if(method.name != "edit") error("Unexpected prefs ${method.name}")
            events += "edit"; editor
        } as SharedPreferences
        init {
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                if(method.name == "apply") { events += "apply"; persisted.putAll(staged); null }
                else { check(method.name in setOf("putString", "putLong", "putInt", "putBoolean"))
                    val key = args!![0] as String; events += "write:$key"; staged[key] = args[1]; editor }
            } as SharedPreferences.Editor
        }
        val store = RuntimeMetricPreflightResultStore({ events += "prefs"; preferences }, "original-policy",
            { events += "clock"; clockFailure?.let { throw it }; 9_000L }, { events += "app-version"; "version" },
            { events += "source-commit"; "commit" }, { events += "manufacturer"; "make" },
            { events += "model"; "model" }, { events += "device"; "device" }, { events += "sdk"; 35 })
        val result = RuntimeMetricPreflightResult(5, RuntimeMetricPreflightStatus.UNKNOWN,
            RuntimeMetricPreflightReason.LIFECYCLE_CANCELLED, 3, 2, 80, null)
        val match = ApprovedDeviceProfileMatch(false, null, null, ApprovedDeviceProfileMatchReason.NO_EXACT_MATCH)
    }
}
