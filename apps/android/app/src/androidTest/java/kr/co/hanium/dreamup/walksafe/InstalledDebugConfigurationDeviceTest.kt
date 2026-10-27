package kr.co.hanium.dreamup.walksafe

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Observe the installed target's runtime constants, not constants inlined into this test APK. */
@RunWith(AndroidJUnit4::class)
class InstalledDebugConfigurationDeviceTest {
    @Test fun installedStandardDebugKeepsReadinessAndLoginBypassesDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val installed = context.classLoader.loadClass("${context.packageName}.BuildConfig")
        fun value(name: String): Any = installed.getField(name).get(null)!!
        assertEquals(true, value("DEBUG"))
        val expected = linkedMapOf<String, Any>(
            "DEVELOPMENT_QUICK_START" to false,
            "WALKSAFE_DEBUG_GUIDANCE_START_BYPASS" to false,
            "WALKSAFE_POST_LOGIN_DEVICE_CHECK_REQUIRED" to true,
            "WALKSAFE_REPORT_QUEUE_ENABLED" to false,
            "WALKSAFE_REPORT_QUEUE_TEST_ORIGIN" to "disabled",
            "WALKSAFE_RAW_QUEUE_MAX_SEGMENTS" to 0,
            "WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES" to 0L,
            "WALKSAFE_SERVER_STT_ENABLED" to true,
            "WALKSAFE_PRODUCT_ROLE" to "USER",
        )
        val report = JSONObject()
        for ((name, wanted) in expected) {
            val observed = value(name)
            assertEquals("Unexpected installed target setting: $name", wanted, observed)
            report.put(name, observed)
        }
        val fields = installed.declaredFields.map { it.name }
        assertFalse("DEVELOPMENT_QUICK_START_EMAIL" in fields)
        assertFalse("DEVELOPMENT_QUICK_START_PASSWORD" in fields)
        report.put("oldCredentialFieldsAbsent", true).put("targetRuntimeReflection", true)
            .put("scope", "Installed standard debug only; not signed release, full DEX secret scan or all build combinations")
        if (android.os.Build.VERSION.SDK_INT >= 31) report.put("microphonePrivacyToggleSupported",
            context.getSystemService(android.hardware.SensorPrivacyManager::class.java).supportsSensorToggle(1))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "INSTALLED_CONFIGURATION $report\n") })
    }
}
