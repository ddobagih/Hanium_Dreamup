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
        do {
            SystemClock.sleep(250L)
            val current = GatewaySessionProcessCoordinator.snapshot()
            if (current.session?.isUsableFor(current.session.actorId) == true &&
                current.inFlightOperationId == null) break
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
        }
        File(context.cacheDir, "walkmate-live-account-observation.json").writeText(report.toString())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "WALKMATE_ACCOUNT_OBSERVATION=$report\n")
        })
        args.getString("expectedAuthenticated")?.let { assertEquals(it.toBoolean(), report.getBoolean("authenticated")) }
        args.getString("expectedLongLived")?.let { assertEquals(it.toBoolean(), report.getBoolean("longLived")) }
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
