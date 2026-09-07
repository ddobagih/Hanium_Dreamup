package kr.co.hanium.dreamup.walksafe.navigation

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationStartContinuityDeviceTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun realSearchStartKeepsDestinationAndCompletesOrExplainsMeasuredBlock() {
        assumeTrue(
            InstrumentationRegistry.getArguments()
                .getString("allowNavigationStartContinuityTest") == "true"
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            DeviceTestAccountUiLogin.ensureLoggedIn(
                instrumentation,
                activity,
                SystemClock.elapsedRealtime() + 25_000L,
            )
            onMain {
                assertTrue("Real account onboarding must be complete", call(activity, "firstRunOnboardingComplete") as Boolean)
                assertFalse("Test must start without an active route", field(activity, "isRouteActive") as Boolean)
                val pageType = Class.forName("${MainActivity::class.java.name}\$NativeUiPage")
                val page = pageType.enumConstants.single { it.toString() == "DESTINATION_SEARCH" }
                MainActivity::class.java.getDeclaredMethod("showNativeUiPage", pageType).apply {
                    isAccessible = true
                }.invoke(activity, page)
                (field(activity, "destinationQueryInput") as EditText).setText("편의점")
                assertTrue("Real search button must accept the click", (field(activity, "destinationSearchButton") as Button).performClick())
            }
            await("Live destination search did not return selectable results", 45_000L) {
                onMain {
                    field(activity, "destinationSearchQuery") == "편의점" &&
                        (field(activity, "destinationSearchResults") as List<*>).isNotEmpty() &&
                        resultButtons(activity).any { it.isEnabled }
                }
            }
            lateinit var destination: Any
            var oldUserConfirmation: Any? = null
            var clickedAt = 0L
            onMain {
                assertTrue(resultButtons(activity).first { it.isEnabled }.performClick())
                assertEquals("DESTINATION_CONFIRM", field(activity, "nativeUiPage").toString())
                destination = requireNotNull(field(activity, "pendingUiDestination"))
                oldUserConfirmation = field(activity, "phoneMountingUserConfirmation")
                clickedAt = SystemClock.elapsedRealtime()
                val start = field(activity, "nativeDestinationStartButton") as Button
                assertTrue("Guide start must be enabled after selecting a destination", start.isEnabled)
                assertTrue(start.performClick())
                assertEquals("Start must retain the selected destination", destination, field(activity, "pendingUiDestination"))
                assertEquals("One start click must enter guidance before sensor readiness", "GUIDANCE", field(activity, "nativeUiPage").toString())
                assertTrue("The real guidance screen must be visible immediately", (field(activity, "nativeGuidanceControls") as View).isShown)
            }
            await("Explicit start did not request a new measured mounting check", 8_000L) {
                onMain { field(activity, "nativePhoneMountingCheckRequest") != null }
            }
            val initialCheck = onMain {
                val requestContext = requireNotNull(field(activity, "nativePhoneMountingCheckRequest"))
                val request = requireNotNull(field(requestContext, "checkRequest"))
                assertTrue((field(request, "requestedAtElapsedRealtimeMs") as Long) >= clickedAt)
                assertTrue((field(request, "requestId") as Long) > 0L)
                val confirmation = field(activity, "phoneMountingUserConfirmation")
                assertTrue("Automatic checks must not invent a user's mounting confirmation", confirmation == null || confirmation === oldUserConfirmation)
                assertNoLegacyPreparationControls(activity)
                checkRequest(activity)
            }
            var outcome = ""
            var blockEvidence = "none"
            var retryRestarted = false
            await("Start neither activated a route nor offered a measured-failure retry", 85_000L) {
                onMain {
                    if (routeReady(activity)) {
                        assertActiveDestination(activity, destination)
                        outcome = "REAL_ROUTE_ACTIVE"
                        true
                    } else {
                        val start = field(activity, "nativeGuidanceRetryButton") as? Button
                        val retry = start != null && start.isShown && start.isEnabled &&
                            start.text.toString() == "다시 시도"
                        if (retry) {
                            assertEquals(initialCheck.id, checkRequest(activity).id)
                            blockEvidence = assertMeasuredBlock(activity, destination)
                            outcome = "MEASURED_BLOCK_WITH_RETRY"
                        }
                        retry
                    }
                }
            }
            if (outcome == "MEASURED_BLOCK_WITH_RETRY") {
                var retryClickedAt = 0L
                var oldCameraGeneration = 0L
                onMain {
                    val start = field(activity, "nativeGuidanceRetryButton") as Button
                    assertTrue("Retry must be visible and enabled", start.isShown && start.isEnabled)
                    oldCameraGeneration = (field(activity, "officialEnvironmentCameraPreflightGeneration") as Number).toLong()
                    retryClickedAt = SystemClock.elapsedRealtime()
                    assertTrue("The real retry button must accept the click", start.performClick())
                }
                await("Retry did not create a fresh request and restart actual measurements", 8_000L) {
                    onMain {
                        if (field(activity, "nativePhoneMountingCheckRequest") == null) return@onMain false
                        val next = checkRequest(activity)
                        if (next.id <= initialCheck.id) return@onMain false
                        assertTrue("Retry must use a new request timestamp", next.requestedAt > initialCheck.requestedAt)
                        assertTrue("Retry request must follow the actual click", next.requestedAt >= retryClickedAt)
                        val snapshot = call(requireNotNull(field(activity, "walkSessionLifecycle")), "snapshot")
                        assertEquals("Retry request must belong to the current epoch", call(requireNotNull(snapshot), "getEpoch"), next.epoch)
                        val camera = field(activity, "latestPhoneMountingCameraAssessment")
                        val freshFrame = camera != null && field(camera, "epoch") == next.epoch &&
                            (field(camera, "observedAtElapsedRealtimeMs") as Long) > next.requestedAt
                        val measuring = field(activity, "officialEnvironmentPreflightPhase").toString() == "MEASURING" &&
                            field(activity, "officialEnvironmentCameraPreflightActive") == true &&
                            (field(activity, "officialEnvironmentCameraPreflightGeneration") as Number).toLong() > oldCameraGeneration
                        if (!measuring && !freshFrame) return@onMain false
                        val confirmation = field(activity, "phoneMountingUserConfirmation")
                        assertTrue("Retry must not invent a user's mounting confirmation", confirmation == null || confirmation === oldUserConfirmation)
                        if (routeReady(activity)) {
                            assertActiveDestination(activity, destination)
                            outcome = "REAL_ROUTE_ACTIVE"
                        } else {
                            assertEquals(destination, field(activity, "pendingUiDestination"))
                            assertEquals("GUIDANCE", field(activity, "nativeUiPage").toString())
                            assertNoLegacyPreparationControls(activity)
                        }
                        retryRestarted = true
                        true
                    }
                }
            }
            onMain {
                val cancel = field(activity, "nativeGuidanceCancelButton") as Button
                assertTrue("Guidance cancellation must be visible and enabled", cancel.isShown && cancel.isEnabled)
                assertTrue("The real cancellation button must accept the click", cancel.performClick())
            }
            await("Guidance cancellation did not clear the current preparation and route", 8_000L) {
                onMain {
                    field(activity, "nativeUiPage").toString() == "HOME" &&
                        field(activity, "pendingNativeUiPage") == null &&
                        field(activity, "pendingUiDestination") == null &&
                        field(activity, "nativePhoneMountingCheckRequest") == null &&
                        field(activity, "nativePrewalkStartEpoch") == null &&
                        !(field(activity, "routeRequestInFlight") as AtomicBoolean).get() &&
                        field(activity, "isRouteActive") == false
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "NAVIGATION_START_CONTINUITY=$outcome; guidance_screen_entered=true; retry_check_restarted=$retryRestarted; cancellation_cleared=true; $blockEvidence; sensor_pass_injected=false; human_walk=UNVERIFIED\n")
            })
        }
    }

    private data class CheckRequest(val id: Long, val requestedAt: Long, val epoch: Any)

    private fun checkRequest(activity: MainActivity): CheckRequest {
        val context = requireNotNull(field(activity, "nativePhoneMountingCheckRequest"))
        val request = requireNotNull(field(context, "checkRequest"))
        return CheckRequest(
            field(request, "requestId") as Long,
            field(request, "requestedAtElapsedRealtimeMs") as Long,
            requireNotNull(field(request, "epoch")),
        )
    }

    private fun assertMeasuredBlock(activity: MainActivity, destination: Any): String {
        assertEquals(destination, field(activity, "pendingUiDestination"))
        assertEquals("GUIDANCE", field(activity, "nativeUiPage").toString())
        assertNoLegacyPreparationControls(activity)
        val request = checkRequest(activity)
        val now = SystemClock.elapsedRealtime()
        val environment = requireNotNull(invoke(activity, "currentOfficialEnvironmentAssessment", request.epoch, now))
        val mountingMethod = MainActivity::class.java.declaredMethods.single {
            it.name == "currentPhoneMountingAssessment" && it.parameterTypes.size == 6
        }.apply { isAccessible = true }
        val preflight = mountingMethod.parameterTypes[2].enumConstants.single { it.toString() == "PREFLIGHT" }
        val mounting = requireNotNull(mountingMethod.invoke(activity, request.epoch, now, preflight, null, null, false))
        val environmentReady = call(environment, "getCanStartWalk") as Boolean
        val mountingReady = call(mounting, "getCanStartOrResumeDetection") as Boolean
        assertFalse("A non-sensor failure must not be classified as a measured block", environmentReady && mountingReady)
        val phase = field(activity, "officialEnvironmentPreflightPhase").toString()
        assertTrue("Retry must correspond to a failed current attempt", phase == "FAILED" || phase == "TIMED_OUT" ||
            field(activity, "startupCapabilityRetryRequiresUserAction") == true)
        val confirmation = field(activity, "nativeGuidanceStatusText") as TextView
        assertTrue("The measured reason must be on the visible guidance screen", confirmation.isShown)
        val displayed = confirmation.text.toString()
        val diagnostic = preparationDiagnostic(activity, displayed)
        val phaseMessage = when (phase) {
            "TIMED_OUT" -> "자동 점검 시간이 초과되었습니다."
            "FAILED" -> "자동 점검을 완료하지 못했습니다."
            else -> "안내 준비 중: 현재 위치와 카메라·자세를 실제로 점검합니다."
        }
        assertTrue("The visible phase must match the current attempt; $diagnostic", displayed.contains(phaseMessage))
        val measurement = (invoke(activity, "officialEnvironmentMeasurementDetail", environment) as String).trim()
        assertTrue("Current measurement detail must not be empty", measurement.isNotBlank())
        assertTrue("Visible measurement detail must match the real environment assessment; $diagnostic", displayed.contains(measurement))
        if (!mountingReady) {
            val reason = call(mounting, "getAccessibleReasonKo") as String
            val action = call(mounting, "getAccessibleActionKo") as String
            assertTrue("The actual mounting reason and action must be nonempty", reason.isNotBlank() && action.isNotBlank())
            assertTrue("Visible reason must match the actual mounting guard; $diagnostic", displayed.contains(reason))
            assertTrue("Visible action must match the actual mounting guard; $diagnostic", displayed.contains(action))
        }
        assertTrue("The visible explanation must offer retry or cancel", displayed.contains("다시 시도를 누르거나 안내 취소를 선택하세요."))
        val factors = call(environment, "getFactorStatuses") as Map<*, *>
        fun factor(name: String): String = (factors.entries.single { (it.key as Enum<*>).name == name }.value as Enum<*>).name
        val reasonName = (call(mounting, "getReason") as Enum<*>).name
        return "phase=$phase; mounting_reason=$reasonName; gps_status=${factor("GPS_QUALITY")}; camera_status=${factor("CAMERA_QUALITY")}"
    }

    private fun preparationDiagnostic(activity: MainActivity, displayed: String): String {
        fun enumName(name: String): String = (field(activity, name) as? Enum<*>)?.name ?: "NONE"
        val textClasses = listOf(
            "preparing" to displayed.contains("안내 준비 중: 현재 위치와 카메라·자세를 실제로 점검합니다."),
            "timed_out" to displayed.contains("자동 점검 시간이 초과되었습니다."),
            "failed" to displayed.contains("자동 점검을 완료하지 못했습니다."),
            "retry_action" to displayed.contains("다시 시도를 누르거나 안내 취소를 선택하세요."),
            "legacy_notice" to displayed.contains("공식 사용환경 안내 필요"),
        ).filter { it.second }.joinToString(",") { it.first }.ifEmpty { "none" }
        return "phase=${enumName("officialEnvironmentPreflightPhase")}; " +
            "pending_page=${enumName("pendingNativeUiPage")}; current_page=${enumName("nativeUiPage")}; " +
            "request_present=${field(activity, "nativePhoneMountingCheckRequest") != null}; " +
            "prewalk_epoch_present=${field(activity, "nativePrewalkStartEpoch") != null}; " +
            "text_classes=$textClasses"
    }

    private fun assertActiveDestination(activity: MainActivity, destination: Any) {
        val snapshot = requireNotNull(call(requireNotNull(field(activity, "walkSessionLifecycle")), "snapshot"))
        assertEquals("A route result alone is not an active walk runtime", "ACTIVE", call(snapshot, "getState").toString())
        assertEquals("Active guidance must target the selected destination", call(destination, "getPoint"), field(activity, "currentDestination"))
    }

    private fun routeReady(activity: MainActivity): Boolean =
        field(activity, "isRouteActive") == true &&
            field(activity, "latestTmapOnRoute") == true &&
            !(field(activity, "routeRequestInFlight") as AtomicBoolean).get()

    private fun assertNoLegacyPreparationControls(activity: MainActivity) {
        val visible = visibleTexts(activity.window.decorView)
        assertFalse("Completed education must not be requested again", visible.any { it.contains("공식 사용환경 안내 필요") })
        assertFalse("A manual chest/necklace selector must not block this start", visible.any {
            it.contains("가슴형 정면 장착 확인") || it.contains("목걸이형 정면 장착 확인")
        })
        listOf("officialEnvironmentConfirmButton", "phoneMountingChestConfirmButton", "phoneMountingNecklaceConfirmButton").forEach { name ->
            assertFalse("Manual preflight actions must not appear on guidance", (field(activity, name) as View).isShown)
        }
        assertEquals("Preparation must remain on the guidance screen", "GUIDANCE", field(activity, "nativeUiPage").toString())
        assertTrue("Guidance controls must remain visible", (field(activity, "nativeGuidanceControls") as View).isShown)
        assertTrue("Preparation or its measured reason must remain visible", (field(activity, "nativeGuidanceStatusText") as TextView).isShown)
    }

    private fun resultButtons(activity: MainActivity): List<Button> {
        val container = field(activity, "destinationSearchResultsContainer") as ViewGroup
        return (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<Button>()
    }

    private fun visibleTexts(view: View): List<String> {
        if (!view.isShown) return emptyList()
        val own = if (view is TextView && view !is EditText) listOf(view.text.toString()) else emptyList()
        return if (view is ViewGroup) {
            own + (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
        } else own
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }

    private fun call(target: Any, name: String): Any? = target.javaClass.getDeclaredMethod(name).run {
        isAccessible = true
        invoke(target)
    }

    private fun invoke(target: Any, name: String, vararg arguments: Any?): Any? = target.javaClass.declaredMethods.single {
        it.name == name && it.parameterTypes.size == arguments.size
    }.run {
        isAccessible = true
        invoke(target, *arguments)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError(message)
    }
}
