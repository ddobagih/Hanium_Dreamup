package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.voice.input.VoiceInputSessionController
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled Activity-reporting test only. Platform startForeground denial is not injected:
 * production has no hook that can bypass the real foreground-service promotion.
 */
@RunWith(AndroidJUnit4::class)
class WalkVoiceForegroundServiceStartupFailureDeviceTest {
    @Test
    fun controlledFailuresReachVisibleActivityUiAndBlockAutomaticRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(
            "RECORD_AUDIO must be granted to test the granted-but-restricted branch",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val application = context.applicationContext as Application
        val resumedActivity = AtomicReference<Activity?>()
        val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) resumedActivity.set(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                resumedActivity.compareAndSet(activity, null)
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                resumedActivity.compareAndSet(activity, null)
            }
        }
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        var launchedActivity: MainActivity? = null
        try {
            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            launchedActivity = activity
            awaitResumedStatusViews(instrumentation, activity, resumedActivity)
            val visibilityDeadline = SystemClock.elapsedRealtime() + 10_000L
            while (true) {
                var reported = false
                var diagnostic = ""
                instrumentation.runOnMainSync {
                    val resumed = resumedActivity.get() === activity
                    val foreground = field(activity, "isActivityForeground") == true
                    val focused = activity.hasWindowFocus()
                    diagnostic = "resumed=$resumed foreground=$foreground focused=$focused"
                    if (resumed && foreground && focused) {
                        val voice = MainActivityControllerTestFixture.controller(activity, "voiceInputController") as
                            VoiceInputSessionController
                        voice.reportWalkVoiceStartFailure(WalkVoiceStartFailure.SECURITY)
                        assertFailureUi(activity, "마이크 권한은 허용", "자동 재시도를 중단")
                        assertTrue(recovery(activity).blocksAutomaticRetry)

                        voice.reportWalkVoiceStartFailure(WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED)
                        assertFailureUi(activity, "음성 서비스 시작을 제한", "자동 재시도를 중단")
                        assertTrue(recovery(activity).blocksAutomaticRetry)
                        reported = true
                    }
                }
                if (reported) break
                if (SystemClock.elapsedRealtime() >= visibilityDeadline) {
                    fail("MainActivity did not remain visible for failure report: $diagnostic")
                }
                SystemClock.sleep(50L)
            }
            // runOnMainSync returns only after both reports and UI assertions finish.
            // No service request is issued, so this does not claim onStartCommand coverage.
        } finally {
            application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
            launchedActivity?.let { activity ->
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    private fun awaitResumedStatusViews(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
        resumedActivity: AtomicReference<Activity?>,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (true) {
            var ready = false
            var diagnostic = ""
            instrumentation.runOnMainSync {
                val resumed = resumedActivity.get() === activity
                val foreground = field(activity, "isActivityForeground") == true
                val statusReady = field(activity, "statusText") is TextView
                val detailReady = field(activity, "detailText") is TextView
                val focused = activity.hasWindowFocus()
                ready = resumed && foreground && statusReady && detailReady && focused
                diagnostic = "resumed=$resumed foreground=$foreground statusReady=$statusReady " +
                    "detailReady=$detailReady focused=$focused finishing=${activity.isFinishing} " +
                    "destroyed=${activity.isDestroyed}"
            }
            if (ready) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                fail("MainActivity did not reach resumed foreground with status views: $diagnostic")
            }
            SystemClock.sleep(50L)
        }
    }

    private fun assertFailureUi(activity: MainActivity, detail: String, recovery: String) {
        val status = field(activity, "statusText") as TextView
        val explanation = field(activity, "detailText") as TextView
        assertTrue(status.text.toString().contains("호출어 음성 시작 실패"))
        assertTrue(explanation.text.toString().contains(detail))
        assertTrue(explanation.text.toString().contains(recovery))
    }

    private fun recovery(activity: MainActivity): WalkVoiceStartRecovery =
        field(activity, "handsFreeVoiceStartRecovery") as WalkVoiceStartRecovery

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(activity)
}

/**
 * Run separately on an API 35 emulator after externally revoking target RECORD_AUDIO.
 * Revoking inside the instrumented UID can kill this test process before cleanup runs.
 */
@RunWith(AndroidJUnit4::class)
class WalkVoiceForegroundServiceDeniedPermissionDeviceTest {
    @Test
    fun deniedMicrophoneFailsInRealServiceThenExplicitGrantedRequestStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        requireProbe(
            InstrumentationRegistry.getArguments().getString(PROBE_ARGUMENT) == "true",
            "explicit_restore_grant_opt_in_missing",
        )
        requireProbe(Build.VERSION.SDK_INT == 35 && isEmulator(), "api35_emulator_required")
        requireProbe(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_DENIED,
            "revoke_RECORD_AUDIO_before_instrumentation",
        )
        val app = context.applicationContext as WalkSafeApplication
        requireProbe(app.walkVoiceSessionController == null, "cold_controller_required")

        val startCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val callbackCount = AtomicInteger()
        val reported = AtomicReference<Triple<Long, WalkVoiceSessionController, WalkVoiceStartFailure>?>()
        val controller = object : WalkVoiceSessionController {
            override fun start(): Boolean {
                startCount.incrementAndGet()
                return true
            }

            override fun stop() {
                stopCount.incrementAndGet()
            }
        }
        val onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit =
            { requestId, failedController, failure ->
                reported.set(Triple(requestId, failedController, failure))
                callbackCount.incrementAndGet()
            }
        val serviceIntent = Intent(context, WalkVoiceForegroundService::class.java)
        var scenario: ActivityScenario<WalkVoiceForegroundServiceTestHostActivity>? = null
        var attached = false
        var firstRequestId = 0L
        var secondRequestId = 0L
        try {
            val launched = ActivityScenario.launch<WalkVoiceForegroundServiceTestHostActivity>(
                Intent(context, WalkVoiceForegroundServiceTestHostActivity::class.java),
            )
            scenario = launched
            lateinit var host: WalkVoiceForegroundServiceTestHostActivity
            launched.onActivity { host = it }
            awaitVisibleHost(instrumentation, launched, host)

            instrumentation.runOnMainSync {
                app.attachWalkVoiceSessionController(controller)
                attached = true
                firstRequestId = app.registerWalkVoiceStartRequest(controller, onFailure)
            }
            val deniedIntent = Intent(serviceIntent)
                .setAction(WalkVoiceForegroundService.ACTION_START)
                .putExtra(WalkVoiceForegroundService.EXTRA_START_REQUEST_ID, firstRequestId)
            val deniedStart = runCatching { context.startService(deniedIntent) }
            deniedStart.exceptionOrNull()?.let { error ->
                fail(
                    "Context.startService rejected before observable onStartCommand: " +
                        "${error.javaClass.name}: ${error.message}; " +
                        "callback=${callbackCount.get()} stop=${stopCount.get()}",
                )
            }
            assertNotNull("Context.startService returned no component", deniedStart.getOrNull())
            awaitCondition("real service failure report", {
                "callback=${callbackCount.get()} start=${startCount.get()} " +
                    "stop=${stopCount.get()} running=${isServiceRunning(context)}"
            }) { callbackCount.get() > 0 || startCount.get() > 0 }
            assertEquals("Controller must not start before FGS promotion", 0, startCount.get())
            assertEquals(1, callbackCount.get())
            assertEquals(firstRequestId, reported.get()?.first)
            assertSame(controller, reported.get()?.second)
            assertEquals(WalkVoiceStartFailure.SECURITY, reported.get()?.third)
            assertEquals("Failed service must clean up its controller", 1, stopCount.get())
            awaitCondition("failed service stopSelf", {
                "running=${isServiceRunning(context)} callback=${callbackCount.get()} " +
                    "stop=${stopCount.get()}"
            }) { !isServiceRunning(context) }
            assertFalse(app.acceptsWalkVoiceStartRequest(firstRequestId, controller))

            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO,
            )
            awaitCondition("RECORD_AUDIO regrant", {
                "permission=${context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)}"
            }) {
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            }
            awaitVisibleHost(instrumentation, launched, host)
            instrumentation.runOnMainSync {
                secondRequestId = app.registerWalkVoiceStartRequest(controller, onFailure)
            }
            val grantedIntent = Intent(serviceIntent)
                .setAction(WalkVoiceForegroundService.ACTION_START)
                .putExtra(WalkVoiceForegroundService.EXTRA_START_REQUEST_ID, secondRequestId)
            val grantedStart = runCatching { context.startService(grantedIntent) }
            grantedStart.exceptionOrNull()?.let { error ->
                fail("Next explicit Context.startService rejected: ${error.javaClass.name}: ${error.message}")
            }
            assertNotNull("Next explicit request returned no component", grantedStart.getOrNull())
            awaitCondition("next explicit controller start", {
                "callback=${callbackCount.get()} start=${startCount.get()} " +
                    "stop=${stopCount.get()} running=${isServiceRunning(context)}"
            }) { startCount.get() > 0 || callbackCount.get() > 1 }
            assertEquals("Next explicit request must start once", 1, startCount.get())
            assertEquals("Failure callback must remain exactly once", 1, callbackCount.get())
            assertTrue("Started service must accept explicit stop", context.stopService(serviceIntent))
            awaitCondition("explicit service stop", {
                "start=${startCount.get()} stop=${stopCount.get()} " +
                    "running=${isServiceRunning(context)}"
            }) { stopCount.get() == 2 && !isServiceRunning(context) }
            assertEquals(1, callbackCount.get())
        } finally {
            try {
                context.stopService(serviceIntent)
                if (attached) {
                    instrumentation.runOnMainSync {
                        if (firstRequestId != 0L) {
                            app.invalidateWalkVoiceStartRequest(firstRequestId, controller)
                        }
                        if (secondRequestId != 0L) {
                            app.invalidateWalkVoiceStartRequest(secondRequestId, controller)
                        }
                        app.detachWalkVoiceSessionController(controller)
                    }
                }
                scenario?.close()
            } finally {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.RECORD_AUDIO,
                )
            }
        }
    }

    private fun awaitVisibleHost(
        instrumentation: android.app.Instrumentation,
        scenario: ActivityScenario<WalkVoiceForegroundServiceTestHostActivity>,
        host: WalkVoiceForegroundServiceTestHostActivity,
    ) {
        awaitCondition("visible resumed same-UID test host", {
            "state=${scenario.state} focus=${host.hasWindowFocus()} finishing=${host.isFinishing}"
        }) {
            var focused = false
            var finishing = false
            instrumentation.runOnMainSync {
                focused = host.hasWindowFocus()
                finishing = host.isFinishing
            }
            scenario.state == Lifecycle.State.RESUMED && focused && !finishing
        }
    }

    private fun awaitCondition(
        label: String,
        diagnostic: () -> String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (true) {
            if (condition()) return
            if (SystemClock.elapsedRealtime() >= deadline) fail("$label timed out: ${diagnostic()}")
            SystemClock.sleep(50L)
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(100)
            .orEmpty()
            .any { it.service.className == WalkVoiceForegroundService::class.java.name }

    private fun requireProbe(condition: Boolean, reason: String) {
        if (!condition) fail("Denied-permission FGS probe blocked: $reason")
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.HARDWARE == "goldfish" ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "cutf_cuttlefish"

    private companion object {
        const val PROBE_ARGUMENT = "walksafe.fgs_denied_permission_probe"
    }
}

/**
 * A seeded request is invalidated by a real onPause. A separate request injected after onPause
 * exercises the Service/Application-to-Activity relay seam, not an ordinary user start flow.
 */
@RunWith(AndroidJUnit4::class)
class WalkVoiceForegroundServiceLateFailureDeviceTest {
    @Test
    fun onPauseInvalidatesSeededRequestAndPausedRelayDefersInjectedServiceFailure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        requireProbe(
            InstrumentationRegistry.getArguments().getString(PROBE_ARGUMENT) == "true",
            "explicit_restore_grant_opt_in_missing",
        )
        requireProbe(Build.VERSION.SDK_INT == 35 && isEmulator(), "api35_emulator_required")
        requireProbe(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_DENIED,
            "revoke_RECORD_AUDIO_before_instrumentation",
        )
        val app = context.applicationContext as WalkSafeApplication
        requireProbe(app.walkVoiceSessionController == null, "cold_controller_required")

        var mainScenario: ActivityScenario<MainActivity>? = null
        var hostScenario: ActivityScenario<WalkVoiceForegroundServiceTestHostActivity>? = null
        var activity: MainActivity? = null
        var controller: HandsFreeVoiceController? = null
        var injectedController = false
        var prePauseRequestId = 0L
        var failedRequestId = 0L
        var newerRequestId = 0L
        val callbackCount = AtomicInteger()
        val serviceIntent = Intent(context, WalkVoiceForegroundService::class.java)
        var bodyFailure: Throwable? = null
        try {
            val launchedMain = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java),
            )
            mainScenario = launchedMain
            launchedMain.onActivity { activity = it }
            val main = checkNotNull(activity) { "MainActivity was not created" }
            lateinit var failureOwner: VoiceInputSessionController
            val onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit =
                { id, failedController, failure ->
                    callbackCount.incrementAndGet()
                    failureOwner.handleWalkVoiceStartFailure(id, failedController, failure)
                    Unit
                }
            var lastReadinessSnapshot: String? = null
            awaitCondition("resumed MainActivity status views before seeded request") {
                val scenarioResumed = launchedMain.state == Lifecycle.State.RESUMED
                var ready = false
                instrumentation.runOnMainSync {
                    val stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(main)
                    val foreground = field(main, "isActivityForeground") == true
                    val focused = main.hasWindowFocus()
                    val microphoneDenied = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_DENIED
                    val statusReady = field(main, "statusText") is TextView
                    val detailReady = field(main, "detailText") is TextView
                    val destroyed = main.isDestroyed
                    val readinessSnapshot = "scenarioResumed=$scenarioResumed stage=$stage " +
                        "foreground=$foreground focused=$focused microphoneDenied=$microphoneDenied " +
                        "statusReady=$statusReady detailReady=$detailReady destroyed=$destroyed"
                    if (readinessSnapshot != lastReadinessSnapshot) {
                        Log.d("WalkSafeFgsLateSdk", "event=LATE_READINESS $readinessSnapshot")
                        lastReadinessSnapshot = readinessSnapshot
                    }
                    // This seeded relay test does not require user input or window focus.
                    ready = scenarioResumed && stage == Stage.RESUMED && foreground &&
                        microphoneDenied && statusReady && detailReady && !destroyed
                    if (!ready) return@runOnMainSync
                    failureOwner = MainActivityControllerTestFixture.voiceInput(main)
                    val existing = field(main, "handsFreeVoiceController") as? HandsFreeVoiceController
                    if (existing == null) {
                        requireProbe(app.walkVoiceSessionController == null, "unexpected_provider_controller")
                        val replacement = noStartController()
                        setField(main, "handsFreeVoiceController", replacement)
                        app.attachWalkVoiceSessionController(replacement)
                        controller = replacement
                        injectedController = true
                    } else {
                        assertSame(existing, app.walkVoiceSessionController)
                        controller = existing
                    }
                    val currentController = checkNotNull(controller) { "No controller for service request" }
                    prePauseRequestId = app.registerWalkVoiceStartRequest(currentController, onFailure)
                    setField(main, "handsFreeVoiceStartRequestId", prePauseRequestId)
                    Log.d("WalkSafeFgsLateSdk", "event=LATE_REQUEST_SEEDED requestId=$prePauseRequestId " +
                        "elapsedMs=${SystemClock.elapsedRealtime()} foreground=$foreground focused=$focused")
                }
                ready
            }
            val launchedHost = ActivityScenario.launch<WalkVoiceForegroundServiceTestHostActivity>(
                Intent(context, WalkVoiceForegroundServiceTestHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            )
            hostScenario = launchedHost
            awaitCondition("visible host and backgrounded MainActivity") {
                var ready = false
                val hostResumed = launchedHost.state == Lifecycle.State.RESUMED
                instrumentation.runOnMainSync {
                    ready = hostResumed &&
                        field(main, "isActivityForeground") == false &&
                        !main.isDestroyed
                }
                ready
            }

            var backgroundStatus = ""
            instrumentation.runOnMainSync {
                val currentController = checkNotNull(controller) { "Controller disappeared" }
                assertTrue("onPause must clear the Activity request", field(main, "handsFreeVoiceStartRequestId") == null)
                assertFalse(app.acceptsWalkVoiceStartRequest(prePauseRequestId, currentController))
                assertFalse(
                    "A callback for the pre-pause request must be rejected",
                    app.reportWalkVoiceStartFailure(
                        prePauseRequestId,
                        currentController,
                        WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED,
                    ),
                )
                assertEquals(0, callbackCount.get())
                assertTrue(field(main, "pendingHandsFreeVoiceStartFailure") == null)

                // Deliberate after-onPause injection: cover the relay seam, not a user start path.
                failedRequestId = app.registerWalkVoiceStartRequest(currentController, onFailure)
                setField(main, "handsFreeVoiceStartRequestId", failedRequestId)
                backgroundStatus = (field(main, "statusText") as TextView).text.toString()
            }
            val deniedStart = runCatching {
                context.startService(
                    Intent(serviceIntent)
                        .setAction(WalkVoiceForegroundService.ACTION_START)
                        .putExtra(WalkVoiceForegroundService.EXTRA_START_REQUEST_ID, failedRequestId),
                )
            }
            deniedStart.exceptionOrNull()?.let { error ->
                fail("Context.startService rejected before onStartCommand: ${error.javaClass.name}: ${error.message}")
            }
            assertNotNull("Service start returned no component", deniedStart.getOrNull())
            awaitCondition("service-to-Activity failure and stop") {
                callbackCount.get() == 1 &&
                    field(main, "pendingHandsFreeVoiceStartFailure") != null &&
                    !isServiceRunning(context)
            }
            instrumentation.runOnMainSync {
                val currentController = checkNotNull(controller) { "Controller disappeared" }
                val pending = field(main, "pendingHandsFreeVoiceStartFailure") as
                    WalkVoiceStartFailurePresentation
                assertEquals("microphone_permission_missing", pending.statusToken)
                assertFalse(field(main, "isActivityForeground") == true)
                assertEquals(backgroundStatus, (field(main, "statusText") as TextView).text.toString())
                assertFalse(app.acceptsWalkVoiceStartRequest(failedRequestId, currentController))

                newerRequestId = app.registerWalkVoiceStartRequest(currentController, onFailure)
                setField(main, "handsFreeVoiceStartRequestId", newerRequestId)
                failureOwner.handleWalkVoiceStartFailure(
                    failedRequestId,
                    currentController,
                    WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED,
                )
                assertEquals(newerRequestId, field(main, "handsFreeVoiceStartRequestId"))
                assertSame(pending, field(main, "pendingHandsFreeVoiceStartFailure"))
                assertTrue(app.acceptsWalkVoiceStartRequest(newerRequestId, currentController))
                app.invalidateWalkVoiceStartRequest(newerRequestId, currentController)
                setField(main, "handsFreeVoiceStartRequestId", null)
                newerRequestId = 0L
            }

            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO,
            )
            hostScenario.close()
            hostScenario = null
            awaitCondition("foreground recovery presentation") {
                var ready = false
                val mainResumed = launchedMain.state == Lifecycle.State.RESUMED
                instrumentation.runOnMainSync {
                    ready = mainResumed &&
                        field(main, "isActivityForeground") == true &&
                        field(main, "pendingHandsFreeVoiceStartFailure") == null &&
                        (field(main, "statusText") as TextView).text.toString()
                            .contains("호출어 음성 시작 실패") &&
                        (field(main, "detailText") as TextView).text.toString()
                            .contains("마이크 권한이 없어")
                }
                ready
            }
            assertEquals(1, callbackCount.get())
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            hostScenario?.close()
            context.stopService(serviceIntent)
            val main = activity
            val currentController = controller
            if (currentController != null) {
                instrumentation.runOnMainSync {
                    if (prePauseRequestId != 0L) {
                        app.invalidateWalkVoiceStartRequest(prePauseRequestId, currentController)
                    }
                    if (failedRequestId != 0L) {
                        app.invalidateWalkVoiceStartRequest(failedRequestId, currentController)
                    }
                    if (newerRequestId != 0L) {
                        app.invalidateWalkVoiceStartRequest(newerRequestId, currentController)
                    }
                    if (injectedController) {
                        if (main != null && !main.isDestroyed &&
                            field(main, "handsFreeVoiceController") === currentController
                        ) {
                            setField(main, "handsFreeVoiceController", null)
                        }
                        app.detachWalkVoiceSessionController(currentController)
                        currentController.close()
                    }
                }
            }
            try {
                main?.let { activity ->
                    instrumentation.runOnMainSync {
                        if (!activity.isDestroyed) activity.finish()
                    }
                }
                mainScenario?.let { scenario ->
                    awaitCondition("MainActivity destroyed before scenario close") {
                        try {
                            scenario.state == Lifecycle.State.DESTROYED
                        } catch (_: NullPointerException) {
                            false
                        }
                    }
                    scenario.close()
                }
            } catch (cleanupFailure: Throwable) {
                if (bodyFailure != null) bodyFailure.addSuppressed(cleanupFailure)
                else throw cleanupFailure
            } finally {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.RECORD_AUDIO,
                )
            }
        }
    }

    private fun noStartController(): HandsFreeVoiceController = HandsFreeVoiceController(
        dependencies = HandsFreeVoiceDependencies(
            runtime = HandlerVoiceCallbackRuntime(Handler(Looper.getMainLooper())),
            createStream = { _, _, _ ->
                object : HandsFreeStreamingPort {
                    override fun start(onRunReserved: (Long) -> Unit): Long? = null
                    override fun isRunning(): Boolean = false
                    override fun stop() = Unit
                    override fun close() = Unit
                }
            },
            playAcknowledgement = { _ -> },
            cancelAcknowledgement = {},
            closeAcknowledgement = {},
        ),
        eligibility = {
            HandsFreeVoiceEligibility(
                WalkSessionState.ACTIVE,
                microphoneGranted = false,
                disclosureAccepted = false,
                modelReady = false,
            )
        },
        isAppSpeechActive = { false },
        onCommand = { _, _ -> },
        onStatus = {},
    )

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (true) {
            if (condition()) return
            if (SystemClock.elapsedRealtime() >= deadline) fail("$label timed out")
            SystemClock.sleep(50L)
        }
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(activity)

    private fun setField(activity: MainActivity, name: String, value: Any?) {
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(activity, value)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(100)
            .orEmpty()
            .any { it.service.className == WalkVoiceForegroundService::class.java.name }

    private fun requireProbe(condition: Boolean, reason: String) {
        if (!condition) fail("Late-failure FGS probe blocked: $reason")
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.HARDWARE == "goldfish" ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "cutf_cuttlefish"

    private companion object {
        const val PROBE_ARGUMENT = "walksafe.fgs_denied_permission_probe"
    }
}
