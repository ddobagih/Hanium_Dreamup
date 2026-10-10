package kr.co.hanium.dreamup.walksafe.session

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host changes actual OS permissions between runs and restores them in finally.
 * No MainActivity, onboarding mutation, fabricated permission callback or active walk. */
@RunWith(AndroidJUnit4::class)
class PermissionObservationDeviceTest {
    @Test fun actualOsPermissionsMatchExplanationWithoutInventedRequestResult() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowPermissionObservation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val notifications = granted(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(args.getString("expectedFine").toBoolean(), fine)
        assertEquals(args.getString("expectedCoarse").toBoolean(), coarse)
        assertEquals(args.getString("expectedNotifications").toBoolean(), notifications)
        val missing = buildSet {
            if (!fine) add(PermissionExplanationKind.PRECISE_LOCATION)
            if (!coarse) add(PermissionExplanationKind.APPROXIMATE_LOCATION)
        }
        val explanation = PermissionExplanationPolicy.withoutResult(missing, coarse)
        assertEquals(when {
            fine -> PermissionLocationState.NOT_APPLICABLE
            coarse -> PermissionLocationState.APPROXIMATE_ONLY
            else -> PermissionLocationState.NO_LOCATION
        }, explanation.locationState)
        assertFalse(explanation.requestResultReceived)
        assertTrue(explanation.reRequestabilityByPermission.values.all {
            it == PermissionReRequestability.NOT_EVALUATED
        })
        val enabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (!notifications) assertFalse(enabled)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "OS_PERMISSION fine=$fine coarse=$coarse notifications=$notifications notificationManagerEnabled=$enabled location=${explanation.locationState} no_invented_callback=true; Main_UI_and_ACTIVE_walk=NOT_TESTED\n")
        })
    }
}
