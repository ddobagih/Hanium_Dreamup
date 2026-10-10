package kr.co.hanium.dreamup.walksafe.network

import android.content.Intent
import android.os.Process
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observes the real app's restored session. Never creates credentials, consent or readiness. */
@RunWith(AndroidJUnit4::class)
class LiveAccountObservationDeviceTest {
    @Test fun observeRestoredAccountWithoutChangingItsEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowLiveAccountObservation") == "true")
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        val requireHome = args.getString("expectedHomeReady") == "true"
        do {
            SystemClock.sleep(250L)
            val current = GatewaySessionProcessCoordinator.snapshot()
            var homeReady = !requireHome
            if (requireHome) instrumentation.runOnMainSync {
                homeReady = MainActivity::class.java.getDeclaredMethod("nativeHomeFeatureContextAvailable")
                    .apply { isAccessible = true }.invoke(activity) == true
            }
            if (current.session?.isUsableFor(current.session.actorId) == true &&
                current.inFlightOperationId == null && homeReady) break
        } while (SystemClock.elapsedRealtime() < deadline)
        val state = GatewaySessionProcessCoordinator.snapshot()
        val session = state.session
        val report = JSONObject().put("pid", Process.myPid())
            .put("authenticated", session?.isUsableFor(session.actorId) == true)
            .put("verification", session?.verificationState?.name ?: "absent")
            .put("longLived", session?.isLongLived == true)
            .put("accountGeneration", session?.backendAccountGeneration ?: JSONObject.NULL)
            .put("rotation", session?.rotation ?: JSONObject.NULL)
            .put("familyHash", session?.familyId?.let(::hash) ?: JSONObject.NULL)
            .put("actorHash", session?.actorId?.let(::hash) ?: JSONObject.NULL)
            .put("storageBlocked", state.storageBlocked)
            .put("operationPending", state.inFlightOperationId != null)
        instrumentation.runOnMainSync {
            val onboarding = field(activity, "firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot
            report.put("onboardingStage", onboarding.stage.name)
                .put("onboardingComplete", onboarding.isComplete)
                .put("page", field(activity, "nativeUiPage").toString())
                .put("routeActive", field(activity, "isRouteActive"))
            val permissionGate = field(activity, "permissionRecoveryGate") as kr.co.hanium.dreamup.walksafe.session.PermissionRecoveryGate
            val lifecycle = field(activity, "walkSessionLifecycle") as kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
            fun observe(name: String): Any? = MainActivity::class.java.getDeclaredMethod(name)
                .apply { isAccessible = true }.invoke(activity)
            report.put("permissionRecoveryState", permissionGate.state.name)
                .put("permissionRecoveryBlocksResources", permissionGate.blocksAutomaticResourceStart)
                .put("walkState", lifecycle.snapshot().state.name)
                .put("missingAndroidPermissions", org.json.JSONArray(observe("requiredPostLoginDeviceCheckPermissions") as List<*>))
                .put("homeFeatureContextAvailable", observe("nativeHomeFeatureContextAvailable"))
                .put("homeVoiceCommandAvailable", observe("homeVoiceCommandAvailable"))
        }
        File(context.cacheDir, "walkmate-live-account-observation.json").writeText(report.toString())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "WALKMATE_ACCOUNT_OBSERVATION=$report\n")
        })
        args.getString("expectedAuthenticated")?.let { assertEquals(it.toBoolean(), report.getBoolean("authenticated")) }
        args.getString("expectedLongLived")?.let { assertEquals(it.toBoolean(), report.getBoolean("longLived")) }
        if (requireHome) {
            assertTrue(report.getBoolean("onboardingComplete"))
            assertTrue(report.getBoolean("homeFeatureContextAvailable"))
        }
    }

    /** Host temporarily disconnects this phone's USB gateway forwarding and restores it in finally. */
    @Test fun unavailableGatewayRetainsRealSavedAccountWithoutAuthenticating() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveAccountOfflineObservation") == "true")
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("walksafe", android.content.Context.MODE_PRIVATE)
        val origin = preferences.getString("gateway_origin", kr.co.hanium.dreamup.walksafe.BuildConfig.WALKSAFE_GATEWAY_ORIGIN)!!
        val store = AndroidGatewaySessionStore(preferences)
        val before = requireNotNull(store.restoreBackendDevice(origin)) { "Requires an existing real saved test account" }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun views(v: android.view.View): List<android.view.View> = listOf(v) + if (v is android.view.ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        var retryVisible = false
        try {
            val deadline = SystemClock.elapsedRealtime() + 45_000
            do {
                instrumentation.runOnMainSync {
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    retryVisible = views(activity.window.decorView).filterIsInstance<android.widget.Button>().any {
                        it.isShown && it.isEnabled && it.text.toString() == "저장된 로그인 다시 확인"
                    }
                }
                if (retryVisible && GatewaySessionProcessCoordinator.snapshot().inFlightOperationId == null) break
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            org.junit.Assert.assertTrue("Network failure must expose saved-login retry", retryVisible)
            val state = GatewaySessionProcessCoordinator.snapshot()
            org.junit.Assert.assertFalse(state.session?.isUsableFor(state.session.actorId) == true)
            org.junit.Assert.assertFalse(state.storageBlocked)
            val after = requireNotNull(store.restoreBackendDevice(origin))
            assertEquals(before.session.familyId, after.session.familyId)
            assertEquals(before.session.deviceId, after.session.deviceId)
            assertEquals(before.session.actorId, after.session.actorId)
            instrumentation.runOnMainSync {
                org.junit.Assert.assertFalse(field(activity, "isRouteActive") as Boolean)
                org.junit.Assert.assertTrue(field(activity, "backendDeviceRestorePending") as Boolean)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "OFFLINE_ACCOUNT pid=${Process.myPid()} saved_account_retained=true authenticated=false retry_visible=true route_active=false; credentials_or_onboarding_written_by_test=false\n")
            })
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
