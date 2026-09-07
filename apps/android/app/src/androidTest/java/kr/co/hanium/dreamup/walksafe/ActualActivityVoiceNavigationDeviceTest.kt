package kr.co.hanium.dreamup.walksafe

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Field
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in state-orchestration fixture for the real MainActivity.
 *
 * This is not speech-recognition, route-provider, or outdoor-walking validation. It supplies only
 * a destination-search callback and a missing-location failure. Login, consent, device readiness,
 * permissions, gateway session, and walk-start gates must already be satisfied by the target app.
 */
@RunWith(AndroidJUnit4::class)
class ActualActivityVoiceNavigationDeviceTest {
    @Test
    fun destinationConfirmationCreatesEpochBeforeMissingGpsRouteAttempt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        requireFixtureCondition(
            arguments.getString(FIXTURE_ARGUMENT) == "true",
            "explicit_flag_missing",
        )
        requireFixtureCondition(!isEmulator(), "physical_device_required")

        val launchIntent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = instrumentation.startActivitySync(launchIntent) as MainActivity
        try {
            DeviceTestAccountUiLogin.ensureLoggedIn(
                instrumentation,
                activity,
                SystemClock.elapsedRealtime() + PREFLIGHT_TIMEOUT_MS,
            )
            val homeReady = waitUntil(PREFLIGHT_TIMEOUT_MS) {
                onMain(instrumentation) {
                    invokeBoolean(activity, "nativeHomeFeatureContextAvailable")
                }
            }
            requireFixtureCondition(homeReady, "native_home_context_unavailable")
            requireFixtureCondition(
                onMain(instrumentation) {
                    invokeBoolean(activity, "currentDestinationSearchAllowsWork")
                },
                "destination_search_gate_unavailable",
            )
            requireFixtureCondition(
                onMain(instrumentation) { runtimeEpochOrNull(activity) == null },
                "cold_home_without_runtime_epoch_required",
            )
            requireFixtureCondition(
                onMain(instrumentation) {
                    !(readField(activity, "routeRequestInFlight") as AtomicBoolean).get()
                },
                "route_request_already_in_flight",
            )
            requireFixtureCondition(
                onMain(instrumentation) { readField(activity, "pendingUiDestination") == null },
                "destination_confirmation_already_pending",
            )

            val destination = DestinationSearchResult(
                id = "fixture",
                name = "공개 시험 후보",
                point = RoutePoint(37.0, 127.0),
                address = null,
                roadAddress = null,
                category = null,
                distanceM = null,
            )
            onMain(instrumentation) {
                invoke(
                    activity,
                    "openNativeDestinationConfirmation",
                    arrayOf<Class<*>>(DestinationSearchResult::class.java),
                    arrayOf(destination),
                )
            }
            instrumentation.waitForIdleSync()
            assertEquals(
                "Destination callback must retain the proposed result",
                destination,
                onMain(instrumentation) { readField(activity, "pendingUiDestination") },
            )
            assertEquals(
                "Destination callback must open confirmation without starting a route",
                "DESTINATION_CONFIRM",
                onMain(instrumentation) { readField(activity, "nativeUiPage").toString() },
            )

            onMain(instrumentation) {
                val startButton = readField(activity, "nativeDestinationStartButton") as Button
                check(startButton.isEnabled) { "native_destination_start_button_disabled" }
                check(startButton.performClick()) { "native_destination_start_click_not_dispatched" }
                // Disarm only the future automatic route callback. The real prewalk remains active.
                writeField(activity, "pendingUiDestination", null)
            }
            val epochReady = waitUntil(PREWALK_RESOLUTION_TIMEOUT_MS) {
                onMain(instrumentation) { runtimeEpochOrNull(activity) != null }
            }
            if (!epochReady) {
                val visibleReason = onMain(instrumentation) {
                    collectText(activity.window.decorView, visibleOnly = true)
                        .filter {
                            it.contains("장착") || it.contains("고정") || it.contains("카메라") ||
                                it.contains("권한") || it.contains("환경") || it.contains("기기") ||
                                it.contains("실패") || it.contains("제한") || it.contains("확인")
                        }
                        .joinToString(" | ")
                        .take(800)
                }
                report(
                    instrumentation,
                    "status=blocked reason=prewalk_epoch_not_created visible=$visibleReason",
                )
                fail("Prewalk did not create a runtime epoch; visible_gate_reason=$visibleReason")
            }

            val result = onMain(instrumentation) {
                invoke(
                    activity,
                    "openNativeDestinationConfirmation",
                    arrayOf<Class<*>>(DestinationSearchResult::class.java),
                    arrayOf(destination),
                )
                // Failure-only fixture. It never invents a trusted position or changes a gate.
                writeField(activity, "latestTrustedLocation", null)
                val epochBeforeRoute = runtimeEpochOrNull(activity)
                val generationBeforeRoute = routeGeneration(activity)
                invoke(activity, "startNativeDestinationGuidance")
                val allTexts = collectText(activity.window.decorView, visibleOnly = false)
                val visibleTexts = collectText(activity.window.decorView, visibleOnly = true)
                RouteAttemptObservation(
                    epochBeforeRoute = epochBeforeRoute,
                    generationBeforeRoute = generationBeforeRoute,
                    generationAfterRoute = routeGeneration(activity),
                    routeInFlight =
                        (readField(activity, "routeRequestInFlight") as AtomicBoolean).get(),
                    routeActive = readField(activity, "isRouteActive") as Boolean,
                    currentDestination = readField(activity, "currentDestination"),
                    pendingStartRetained = pendingExplicitRouteStartOrNull(activity) != null,
                    routeWaitingStatusObserved = allTexts.any {
                        it.contains(
                            "navigation=route_waiting trusted_gps_missing start_request_pending",
                        )
                    },
                    destinationVisible = visibleTexts.any { it.contains(destination.name) },
                    missingLocationReasonVisible = visibleTexts.any {
                        (it.contains("GPS", ignoreCase = true) || it.contains("현재 위치")) &&
                            (it.contains("확인") || it.contains("다시") || it.contains("필요"))
                    },
                    relevantVisibleText = visibleTexts
                        .filter {
                            it.contains(destination.name) ||
                                it.contains("GPS", ignoreCase = true) ||
                                it.contains("현재 위치") || it.contains("경로") ||
                                it.contains("안내")
                        }
                        .joinToString(" | ")
                        .take(600),
                )
            }
            report(
                instrumentation,
                "status=observed epoch_before_route=${result.epochBeforeRoute != null} " +
                    "generation_before=${result.generationBeforeRoute} " +
                    "generation_after=${result.generationAfterRoute} " +
                    "route_in_flight=${result.routeInFlight} route_active=${result.routeActive} " +
                    "route_waiting=${result.routeWaitingStatusObserved} " +
                    "destination_visible=${result.destinationVisible} " +
                    "missing_location_reason_visible=${result.missingLocationReasonVisible} " +
                    "visible=${result.relevantVisibleText}",
            )

            assertNotNull("Walk epoch must exist before the route entry runs", result.epochBeforeRoute)
            assertEquals(
                "Missing trusted GPS must not issue an HTTP route request",
                result.generationBeforeRoute,
                result.generationAfterRoute,
            )
            assertFalse("Missing trusted GPS must not leave a request in flight", result.routeInFlight)
            assertFalse("A start request is not an active route", result.routeActive)
            assertEquals("Selected destination must be retained for retry", destination.point, result.currentDestination)
            assertTrue("The explicit start request must remain pending", result.pendingStartRetained)
            assertTrue(
                "Expected the pending explicit-start missing-GPS branch",
                result.routeWaitingStatusObserved,
            )
            assertTrue("Selected destination must remain visible", result.destinationVisible)
            assertTrue(
                "The visible UI must explain that GPS/current location needs checking before retry",
                result.missingLocationReasonVisible,
            )

            val resolved = waitUntil(ROUTE_START_TIMEOUT_OBSERVATION_MS) {
                onMain(instrumentation) { pendingExplicitRouteStartOrNull(activity) == null }
            }
            assertTrue("Pending explicit start was not resolved within 17 seconds", resolved)
            val resolution = onMain(instrumentation) {
                val allTexts = collectText(activity.window.decorView, visibleOnly = false)
                val visibleTexts = collectText(activity.window.decorView, visibleOnly = true)
                PendingResolutionObservation(
                    generation = routeGeneration(activity),
                    routeInFlight =
                        (readField(activity, "routeRequestInFlight") as AtomicBoolean).get(),
                    routeActive = readField(activity, "isRouteActive") as Boolean,
                    explicitRetryStatusObserved = allTexts.any {
                        it.contains(
                            "navigation=route_waiting trusted_gps_missing timeout " +
                                "explicit_start_required",
                        )
                    },
                    retryReasonVisible = visibleTexts.any {
                        (it.contains("GPS", ignoreCase = true) || it.contains("현재 위치")) &&
                            it.contains("다시")
                    },
                )
            }
            val dispatchCount = resolution.generation - result.generationBeforeRoute
            when {
                resolution.explicitRetryStatusObserved -> {
                    report(instrumentation, "status=resolved outcome=trusted_gps_timeout")
                    assertEquals("Timeout must not issue an HTTP route request", 0, dispatchCount)
                    assertFalse("Timeout must not leave a request in flight", resolution.routeInFlight)
                    assertFalse("Timeout must not mark the route active", resolution.routeActive)
                    assertTrue("Timeout retry reason must be visible", resolution.retryReasonVisible)
                }
                dispatchCount == 1 -> {
                    report(
                        instrumentation,
                        "status=resolved outcome=fresh_trusted_gps_route_dispatched " +
                            "route_in_flight=${resolution.routeInFlight} " +
                            "route_active=${resolution.routeActive}",
                    )
                    assertEquals(
                        "The selected destination must survive the real-fix continuation",
                        destination.point,
                        onMain(instrumentation) { readField(activity, "currentDestination") },
                    )
                }
                else -> fail(
                    "Pending start cleared without timeout or one route dispatch: " +
                        "dispatch_count=$dispatchCount in_flight=${resolution.routeInFlight} " +
                        "active=${resolution.routeActive}",
                )
            }
        } finally {
            onMain(instrumentation) {
                if (!activity.isFinishing) activity.finish()
            }
        }
    }

    private fun runtimeEpochOrNull(activity: MainActivity): Any? {
        val lifecycle = checkNotNull(readField(activity, "walkSessionLifecycle"))
        val method = (lifecycle.javaClass.declaredMethods + lifecycle.javaClass.methods)
            .firstOrNull {
                it.name.startsWith("currentRuntimeEpochOrNull") && it.parameterCount == 0
            } ?: error("currentRuntimeEpochOrNull_missing")
        method.isAccessible = true
        return method.invoke(lifecycle)
    }

    private fun routeGeneration(activity: MainActivity): Int =
        (readField(activity, "routeRequestGeneration") as Number).toInt()

    private fun pendingExplicitRouteStartOrNull(activity: MainActivity): Any? {
        val holder = checkNotNull(readField(activity, "pendingExplicitRouteStart"))
        val method = holder.javaClass.getDeclaredMethod("currentOrNull").apply {
            isAccessible = true
        }
        return method.invoke(holder)
    }

    private fun collectText(root: View, visibleOnly: Boolean): List<String> {
        val values = mutableListOf<String>()
        fun visit(view: View) {
            if (!visibleOnly || (view.visibility == View.VISIBLE && view.isShown)) {
                if (view is TextView) {
                    view.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(values::add)
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
            }
        }
        visit(root)
        return values
    }

    private fun invokeBoolean(target: Any, name: String): Boolean = invoke(target, name) as Boolean

    private fun invoke(
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        arguments: Array<Any> = emptyArray(),
    ): Any? {
        val method = target.javaClass.getDeclaredMethod(name, *parameterTypes).apply {
            isAccessible = true
        }
        return method.invoke(target, *arguments)
    }

    private fun readField(target: Any, name: String): Any? = findField(target, name).get(target)

    private fun writeField(target: Any, name: String, value: Any?) {
        findField(target, name).set(target, value)
    }

    private fun findField(target: Any, name: String): Field {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("missing_field=$name")
    }

    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun requireFixtureCondition(condition: Boolean, reason: String) {
        if (condition) return
        Log.i(TAG, "status=blocked reason=$reason")
        fail("Actual MainActivity navigation fixture blocked: $reason")
    }

    private fun report(instrumentation: Instrumentation, value: String) {
        Log.i(TAG, value)
        instrumentation.sendStatus(0, Bundle().apply { putString("walksafe_navigation_fixture", value) })
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.HARDWARE == "goldfish" ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "cutf_cuttlefish"

    private data class RouteAttemptObservation(
        val epochBeforeRoute: Any?,
        val generationBeforeRoute: Int,
        val generationAfterRoute: Int,
        val routeInFlight: Boolean,
        val routeActive: Boolean,
        val currentDestination: Any?,
        val pendingStartRetained: Boolean,
        val routeWaitingStatusObserved: Boolean,
        val destinationVisible: Boolean,
        val missingLocationReasonVisible: Boolean,
        val relevantVisibleText: String,
    )

    private data class PendingResolutionObservation(
        val generation: Int,
        val routeInFlight: Boolean,
        val routeActive: Boolean,
        val explicitRetryStatusObserved: Boolean,
        val retryReasonVisible: Boolean,
    )

    private companion object {
        const val TAG = "WalkActualNavigationFixture"
        const val FIXTURE_ARGUMENT = "walksafe.actual_navigation_start_fixture"
        const val PREFLIGHT_TIMEOUT_MS = 20_000L
        const val PREWALK_RESOLUTION_TIMEOUT_MS = 30_000L
        const val ROUTE_START_TIMEOUT_OBSERVATION_MS = 17_000L
    }
}
