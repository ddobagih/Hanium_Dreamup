package kr.co.hanium.dreamup.walksafe.network

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, emulator-only integration against an isolated real Backend/Gateway. The login phase
 * submits the visible login form; offline/restore run in another process without credentials.
 * No account, onboarding receipt, cookie, or authenticated coordinator state is fabricated here.
 * The harness supplies its own synthetic account in the app's private cache and owns server cleanup.
 */
@RunWith(AndroidJUnit4::class)
class BackendAccountSessionRestartDeviceTest {
    @Test(timeout = 75_000L)
    fun visiblePasswordLoginPersistsAndAutomaticallyRevalidatesAfterProcessDeath() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowIsolatedBackendAccountE2E") == "true")
        assertTrue("This probe never runs on a physical device",
            Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish") &&
                (Build.FINGERPRINT.startsWith("generic") || Build.PRODUCT.lowercase(Locale.ROOT).contains("sdk")))
        assertTrue(BuildConfig.DEBUG)
        assertFalse("Quick start must not replace the actual login flow", BuildConfig.DEVELOPMENT_QUICK_START)
        val phase = requireNotNull(arguments.getString("accountRestartPhase"))
        require(phase in setOf("login", "offline", "restore"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = JSONObject(File(context.cacheDir, "walksafe-isolated-account-e2e.json").readText())
        val runId = fixture.getString("runId")
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        require(fixture.getBoolean("isolatedTestOnly"))
        require(fixture.getString("gatewayOrigin") == ORIGIN)
        val expectedActor = fixture.getString("actorId")
        val email = fixture.getString("email")
        require(email.startsWith("codex-e2e-") && email.endsWith("@example.com"))
        val appPreferences = context.getSharedPreferences("walksafe", Context.MODE_PRIVATE)
        assertEquals("Use the isolated port forward; never redirect a user installation", ORIGIN,
            appPreferences.getString("gateway_origin", BuildConfig.WALKSAFE_GATEWAY_ORIGIN))
        val probe = context.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE)
        if (phase == "login") {
            assertTrue("A fresh probe is required; existing probe data is preserved", probe.all.isEmpty())
        } else {
            assertEquals(runId, probe.getString("run_id", null))
            assertNotEquals("Restore must use a genuinely new Android process",
                probe.getInt("writer_pid", -1), Process.myPid())
        }
        assertNull("The process must start without a published session",
            GatewaySessionProcessCoordinator.snapshot().session)
        val previouslySaved = AndroidGatewaySessionStore(appPreferences).restoreBackendDevice(ORIGIN)
        if (phase != "login") assertNotNull("The previous process must leave its actual saved account", previouslySaved)
        val observationOwner = Any()
        val observations = Collections.synchronizedList(mutableListOf<JSONObject>())
        GatewaySessionProcessCoordinator.attach(observationOwner) { state ->
            if (observations.size < 80) observations.add(
                JSONObject().put("generation", state.generation)
                    .put("verification", state.session?.verificationState?.name ?: "absent")
                    .put("actorMatches", state.session?.actorId == expectedActor)
                    .put("operationInFlight", state.inFlightOperationId != null)
                    .put("stage", state.restoredFirstRunSnapshot?.stage?.name ?: "absent")
                    .put("ageBand", state.restoredFirstRunSnapshot?.ageBand?.name ?: "absent")
                    .put("callers", JSONArray(Thread.currentThread().stackTrace.filter {
                        it.className == MainActivity::class.java.name || it.className.startsWith(MainActivity::class.java.name + "$" )
                    }.map { "${it.methodName}:${it.lineNumber}" })),
            )
        }
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            val main = requireNotNull(activity)
            waitFor(10_000L, "MainActivity did not receive window focus") {
                onMain { main.hasWindowFocus() }
            }
            if (phase == "login") {
                waitFor(10_000L, "The visible email login form is unavailable") {
                    onMain { loginEmail(main) != null }
                }
                onMain {
                    assertNull("Do not replace an existing account", GatewaySessionProcessCoordinator.snapshot().session)
                    requireNotNull(loginEmail(main)).setText(email)
                    val visibleViews = descendants(main.window.decorView).filter(::visible)
                    val password = visibleViews.filterIsInstance<EditText>().single {
                        it.contentDescription?.toString()?.startsWith("계정 비밀번호 입력") == true
                    }
                    password.setText(fixture.getString("password"))
                    val login = visibleViews.filterIsInstance<Button>().single { it.text.toString() == "로그인" }
                    assertTrue("A valid fixture must enable the real login button", login.isEnabled)
                    assertTrue(login.performClick())
                }
            }
            if (phase == "offline") {
                waitFor(35_000L, "A temporarily unavailable server must preserve pending login") {
                    val state = GatewaySessionProcessCoordinator.snapshot()
                    !state.storageBlocked && !state.deletionRecoveryOnly && state.session == null &&
                        state.inFlightOperationId == null && onMain {
                            booleanField(main, "backendDeviceRestorePending") &&
                                descendants(main.window.decorView).filterIsInstance<Button>().any {
                                    visible(it) && it.isEnabled && it.text.toString() == "저장된 로그인 다시 확인"
                                }
                        }
                }
                val retained = AndroidGatewaySessionStore(appPreferences).restoreBackendDevice(ORIGIN)
                assertNotNull("Transient network loss must retain the actual encrypted account", retained)
                assertEquals(previouslySaved?.session?.familyId, retained?.session?.familyId)
                assertEquals(previouslySaved?.session?.deviceId, retained?.session?.deviceId)
                assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, retained?.session?.verificationState)
                assertTrue(onMain { loginEmail(main) == null })
                File(context.cacheDir, "walksafe-account-e2e-$phase-result.json").writeText(
                    JSONObject().put("phase", phase).put("pid", Process.myPid())
                        .put("writerPid", probe.getInt("writer_pid", -1))
                        .put("accountRetained", true).put("authenticatedWhileUnavailable", false)
                        .put("retryVisibleAndEnabled", true).put("loginFormHidden", true).toString(),
                )
                return
            }
            waitFor(40_000L, "The real login/automatic server revalidation did not publish a verified account") {
                val state = GatewaySessionProcessCoordinator.snapshot()
                !state.storageBlocked && !state.deletionRecoveryOnly &&
                    state.session?.isUsableFor(expectedActor) == true
            }
            waitFor(5_000L, "The authenticated account did not reach the actual onboarding UI") {
                onMain {
                    firstRun(main).verifiedActorBinding?.value == expectedActor && loginEmail(main) == null
                }
            }
            val stableUntil = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < stableUntil) {
                assertTrue("Startup callbacks must retain the verified account",
                    GatewaySessionProcessCoordinator.snapshot().session?.isUsableFor(expectedActor) == true)
                assertTrue("Pending education must not reopen the password form", onMain { loginEmail(main) == null })
                SystemClock.sleep(100L)
            }
            val live = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertTrue(live.isBackendAccountDeviceBound)
            assertEquals(GatewaySessionVerificationState.VERIFIED, live.verificationState)
            val saved = AndroidGatewaySessionStore(appPreferences).restoreBackendDevice(ORIGIN)
            assertNotNull("MainActivity must persist the actual server-issued session", saved)
            assertTrue(saved?.session?.actorId == expectedActor)
            assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, saved?.session?.verificationState)
            assertEquals(live.familyId, saved?.session?.familyId)
            assertEquals(live.deviceId, saved?.session?.deviceId)
            if (phase == "login") {
                assertTrue(probe.edit().putString("run_id", runId).putInt("writer_pid", Process.myPid()).commit())
            }
            File(context.cacheDir, "walksafe-account-e2e-$phase-result.json").writeText(
                JSONObject().put("phase", phase).put("pid", Process.myPid())
                    .put("writerPid", probe.getInt("writer_pid", -1))
                    .put("verifiedBackendAccount", true).put("persistedByMainActivity", true)
                    .put("loginFormHidden", true).toString(),
            )
            if (phase == "restore") assertTrue(context.deleteSharedPreferences(PROBE_PREFERENCES))
        } catch (error: Throwable) {
            val state = GatewaySessionProcessCoordinator.snapshot()
            val diagnostic = JSONObject().put("phase", phase).put("pid", Process.myPid())
                .put("storageBlocked", state.storageBlocked).put("deletionRecoveryOnly", state.deletionRecoveryOnly)
                .put("sessionPresent", state.session != null)
                .put("verification", state.session?.verificationState?.name ?: "absent")
                .put("actorMatches", state.session?.actorId == expectedActor)
                .put("operationInFlight", state.inFlightOperationId != null)
                .put("savedAccountPresent", AndroidGatewaySessionStore(appPreferences).restoreBackendDevice(ORIGIN) != null)
            activity?.let { main -> onMain {
                diagnostic.put("loginFormVisible", loginEmail(main) != null)
                for (field in listOf("backendDeviceRestorePending", "privacyStartupInspectionComplete",
                    "privacyStartupInspectionDestroyed", "isActivityForeground")) {
                    diagnostic.put(field, booleanField(main, field))
                }
            } }
            File(context.cacheDir, "walksafe-account-e2e-$phase-diagnostic.json").writeText(diagnostic.toString())
            throw error
        } finally {
            GatewaySessionProcessCoordinator.detach(observationOwner)
            File(context.cacheDir, "walksafe-account-e2e-$phase-observations.json").writeText(
                synchronized(observations) { JSONArray(observations.toList()).toString() },
            )
            activity?.let { current -> onMain { current.finish() } }
        }
    }

    private fun loginEmail(activity: MainActivity): EditText? =
        descendants(activity.window.decorView).filterIsInstance<EditText>().firstOrNull {
            visible(it) && it.contentDescription?.toString() == "계정 이메일 입력"
        }

    private fun firstRun(activity: MainActivity): FirstRunOnboardingSnapshot =
        MainActivity::class.java.getDeclaredField("firstRunOnboardingSnapshot").apply {
            isAccessible = true
        }.get(activity) as FirstRunOnboardingSnapshot

    private fun booleanField(activity: MainActivity, name: String): Boolean =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.getBoolean(activity)

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun visible(view: View): Boolean =
        view.isShown && view.windowVisibility == View.VISIBLE && view.getGlobalVisibleRect(Rect())

    private fun waitFor(timeoutMs: Long, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError(message)
    }

    private fun <T> onMain(action: () -> T): T {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        val handler = Handler(Looper.getMainLooper())
        val run = Runnable { result.set(runCatching(action)); done.countDown() }
        check(handler.post(run))
        if (!done.await(4, TimeUnit.SECONDS)) {
            handler.removeCallbacks(run)
            throw AssertionError("Main thread observation timed out")
        }
        return result.get().getOrThrow()
    }

    private companion object {
        const val ORIGIN = "http://127.0.0.1:8081"
        const val PROBE_PREFERENCES = "walksafe_isolated_backend_account_restart_probe"
    }
}
