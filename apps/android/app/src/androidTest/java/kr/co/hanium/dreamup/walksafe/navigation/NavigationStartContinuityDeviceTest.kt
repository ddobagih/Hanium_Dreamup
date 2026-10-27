package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture

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
            await("Real account onboarding and home readiness did not finish restoring", 25_000L) {
                onMain {
                    call(activity, "firstRunOnboardingComplete") as Boolean &&
                        call(activity, "nativeHomeFeatureContextAvailable") as Boolean
                }
            }
            onMain {
                assertTrue("Real account onboarding must be complete", call(activity, "firstRunOnboardingComplete") as Boolean)
                assertFalse("Test must start without an active route", field(activity, "isRouteActive") as Boolean)
                val pageType = Class.forName("kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController\$NativeUiPage")
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
                oldUserConfirmation = field(activity, "phoneMountingUserConfirmation")
                clickedAt = SystemClock.elapsedRealtime()
                assertTrue(resultButtons(activity).first { it.isEnabled }.performClick())
                destination = requireNotNull(field(activity, "pendingUiDestination"))
                assertEquals("Selecting a destination must enter preparation directly", "GUIDANCE", field(activity, "nativeUiPage").toString())
                assertTrue("The real guidance screen must be visible immediately", (field(activity, "nativeGuidanceControls") as View).isShown)
                assertTrue("Preparation must own walking interaction", call(activity, "isInWalkInteractionArea") as Boolean)
                assertFalse("Home voice must be blocked during preparation", call(activity, "homeVoiceCommandAvailable") as Boolean)
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
                            start.text.toString() == "재점검"
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
        assertTrue("Measured failure must select its recovery UI", call(activity, "nativeGuidanceCheckFailed") as Boolean)
        assertFalse("Long guidance text must be hidden on the failure screen", (field(activity, "guidanceInstructionCard") as View).isShown)
        assertFalse("Destination text must be hidden on the failure screen", (field(activity, "nativeGuidanceDestinationText") as View).isShown)
        val retry = field(activity, "nativeGuidanceRetryButton") as Button
        val cancel = field(activity, "nativeGuidanceCancelButton") as Button
        assertTrue(retry.isShown && retry.isEnabled && retry.text == "재점검")
        assertTrue(cancel.isShown && cancel.isEnabled && cancel.text == "취소")
        assertTrue("Recovery controls must retain a vertical order", retry.top < cancel.top)
        val spokenReason = call(activity, "nativeGuidanceFailureMessage") as String
        assertTrue("Failure speech must explain non-start and both actions",
            spokenReason.contains("안내를 시작하지 않았습니다") &&
                spokenReason.contains("재점검") && spokenReason.contains("취소"))
        assertEquals("This measured attempt must schedule its failure announcement",
            request.id, field(activity, "guidanceFailureAnnouncedForRequest"))
        val factors = call(environment, "getFactorStatuses") as Map<*, *>
        fun factor(name: String): String = (factors.entries.single { (it.key as Enum<*>).name == name }.value as Enum<*>).name
        val reasonName = (call(mounting, "getReason") as Enum<*>).name
        return "phase=$phase; mounting_reason=$reasonName; gps_status=${factor("GPS_QUALITY")}; camera_status=${factor("CAMERA_QUALITY")}"
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
        if (call(activity, "nativeGuidanceCheckFailed") != true) {
            assertTrue("Preparation status must remain visible while checking", (field(activity, "nativeGuidanceStatusText") as TextView).isShown)
        }
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

    private fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).run {
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
