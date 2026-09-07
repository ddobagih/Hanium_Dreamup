package kr.co.hanium.dreamup.walksafe

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArCoreDepthCapabilityDeviceTest {
    @Test
    fun reportLiveDepthCapability() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        val declaredDepthFeature = context.packageManager.hasSystemFeature(ARCORE_DEPTH_FEATURE)
        var automaticDepthSupported: Boolean? = null
        var rawDepthOnlySupported: Boolean? = null
        var sessionError: String? = null
        var session: Session? = null

        try {
            session = Session(context)
            automaticDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            rawDepthOnlySupported = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        } catch (error: Exception) {
            sessionError = error::class.java.simpleName
        } finally {
            session?.close()
        }

        val summary = buildString {
            append("availability=")
            append(availability.name)
            append(", declaredDepthFeature=")
            append(declaredDepthFeature)
            append(", automaticDepthSupported=")
            append(automaticDepthSupported)
            append(", rawDepthOnlySupported=")
            append(rawDepthOnlySupported)
            append(", sessionError=")
            append(sessionError ?: "none")
        }
        Log.i(LOG_TAG, summary)
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString(STATUS_KEY, summary) },
        )

        assertTrue(summary.contains("availability="))
    }

    private companion object {
        const val ARCORE_DEPTH_FEATURE = "com.google.ar.core.depth"
        const val LOG_TAG = "WalkSafeArCoreDepthTest"
        const val STATUS_KEY = "walksafe_arcore_depth_capability"
    }
}
