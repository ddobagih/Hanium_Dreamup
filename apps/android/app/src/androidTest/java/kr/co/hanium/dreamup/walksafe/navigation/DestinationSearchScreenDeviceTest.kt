package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires runDestinationSearchScreenDeviceTest=true and allowLiveDestinationSearchRequests=true.
 * Uses the existing signed-in, education-complete, permission-granted MainActivity unchanged.
 * EditText.setText is a text-input fixture, not ASR evidence. Exactly two search-button clicks
 * are issued, with no pagination, retries, mocked location, injected session or fake transport.
 * Real foreground location and live provider availability are prerequisites, not simulated passes.
 */
@RunWith(AndroidJUnit4::class)
class DestinationSearchScreenDeviceTest {
    @Test(timeout = 110_000L)
    fun campusFirstCancelHomeThenNearbySearchUsesRealScreenCallbacks() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Explicit real-screen search opt-in is required",
            arguments.getString("runDestinationSearchScreenDeviceTest") == "true",
        )
        assumeTrue(
            "Two live destination-search requests require explicit opt-in",
            arguments.getString("allowLiveDestinationSearchRequests") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadlineMs = SystemClock.elapsedRealtime() + 100_000L
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            val main = checkNotNull(activity)
            DeviceTestAccountUiLogin.ensureLoggedIn(instrumentation, main, deadlineMs)
            waitUntil(deadlineMs, 20_000L, "Existing account and onboarding did not restore to usable HOME") {
                onMain(deadlineMs) {
                    !main.isFinishing && !main.isDestroyed && main.hasWindowFocus() && page(main) == "HOME" &&
                        invokeBoolean(main, "firstRunOnboardingComplete") &&
                        invokeBoolean(main, "nativeHomeFeatureContextAvailable")
                }
            }
            onMain(deadlineMs) {
                assertTrue("Saved onboarding must already be complete", invokeBoolean(main, "firstRunOnboardingComplete"))
                assertTrue("Real account and education prerequisites are required", invokeBoolean(main, "nativeHomeFeatureContextAvailable"))
                assertFalse("Do not interrupt active navigation", field(main, "isRouteActive") as Boolean)
                assertFalse("Do not interrupt active recording", field(main, "voiceRecognitionActive") as Boolean)
            }

            openSearchFromVisibleHomeCard(main, deadlineMs)
            val firstGeneration = submitSearch(main, "금오공대", deadlineMs)
            awaitSearchResults(main, "금오공대", firstGeneration, deadlineMs)
            val firstResultButton = onMain(deadlineMs) { resultButtons(main).first() }
            reveal(main, firstResultButton, deadlineMs)
            onMain(deadlineMs) {
                val first = (field(main, "destinationSearchResults") as List<*>).first() as DestinationSearchResult
                assertTrue("Expected campus was not the first returned institution", first.name == CAMPUS_NAME)
                assertTrue(
                    "Expected campus was not the first displayed institution",
                    firstResultButton.text.toString().substringBefore('\n') == CAMPUS_NAME,
                )
                assertTrue("The first institution must be visibly displayed", visible(main, firstResultButton))
            }

            clickField(main, "destinationCancelButton", deadlineMs)
            waitUntil(deadlineMs, 5_000L, "Search cancellation did not return to HOME") {
                onMain(deadlineMs) { page(main) == "HOME" }
            }
            onMain(deadlineMs) {
                assertTrue("Cancelled candidates remained in search state", (field(main, "destinationSearchResults") as List<*>).isEmpty())
                assertTrue("Cancelled text remained in the input", (field(main, "destinationQueryInput") as EditText).text.isNullOrEmpty())
                assertTrue("Cancelled voice candidate selection remained", field(main, "destinationSearchVoiceState") == null)
            }

            openSearchFromVisibleHomeCard(main, deadlineMs)
            val secondGeneration = submitSearch(main, "편의점", deadlineMs)
            awaitSearchResults(main, "편의점", secondGeneration, deadlineMs)
            val nearbyResultButton = onMain(deadlineMs) { resultButtons(main).first() }
            reveal(main, nearbyResultButton, deadlineMs)
            onMain(deadlineMs) {
                val results = field(main, "destinationSearchResults") as List<*>
                assertTrue("The new search did not replace the campus candidates", results.none {
                    (it as DestinationSearchResult).name == CAMPUS_NAME
                })
                assertTrue("New search candidates were not rendered", resultButtons(main).size == results.size)
                assertTrue("A new candidate was not visibly displayed", visible(main, nearbyResultButton))
            }
            Log.i("DestinationScreenTest", "campus_first=true cancel_home=true second_search_callback=true search_clicks=2")
        } finally {
            activity?.let { main ->
                onMain(SystemClock.elapsedRealtime() + 3_000L) { main.finish() }
            }
        }
    }

    private fun openSearchFromVisibleHomeCard(activity: MainActivity, deadlineMs: Long) {
        val card = onMain(deadlineMs) {
            assertTrue("Search entry requires the actual HOME screen", page(activity) == "HOME")
            descendants(activity.window.decorView).filterIsInstance<TextView>()
                .filter { it.isShown && it.text.toString() == "목적지 검색" }
                .mapNotNull { label ->
                    var candidate: View? = label
                    while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
                    candidate?.takeIf { it.isEnabled && it.isShown }
                }.firstOrNull() ?: throw AssertionError("Visible enabled destination-search home card was not found")
        }
        click(activity, card, deadlineMs)
        waitUntil(deadlineMs, 5_000L, "The actual home card did not open destination search") {
            onMain(deadlineMs) { page(activity) == "DESTINATION_SEARCH" }
        }
    }

    private fun submitSearch(activity: MainActivity, query: String, deadlineMs: Long): Long {
        val before = onMain(deadlineMs) {
            assertTrue("Search must use its visible screen", page(activity) == "DESTINATION_SEARCH")
            assertFalse("A previous request is still active", field(activity, "destinationSearchInFlight") as Boolean)
            val input = field(activity, "destinationQueryInput") as EditText
            assertTrue("Real query input is unavailable", input.isShown && input.isEnabled)
            input.setText(query)
            (field(activity, "destinationSearchGeneration") as Number).toLong()
        }
        clickField(activity, "destinationSearchButton", deadlineMs)
        return before
    }

    private fun awaitSearchResults(activity: MainActivity, query: String, before: Long, deadlineMs: Long) {
        waitUntil(
            deadlineMs,
            35_000L,
            "Real search callback did not supply new candidates; check login, location and provider",
            onTimeout = { onMain(deadlineMs) { searchFailureDiagnostic(activity, query, before) } },
        ) {
            onMain(deadlineMs) {
                page(activity) == "DESTINATION_SEARCH" &&
                    (field(activity, "destinationSearchGeneration") as Number).toLong() > before &&
                    field(activity, "destinationSearchQuery") == query &&
                    !(field(activity, "destinationSearchInFlight") as Boolean) &&
                    (field(activity, "destinationSearchResults") as List<*>).isNotEmpty() &&
                    resultButtons(activity).isNotEmpty()
            }
        }
    }

    private fun searchFailureDiagnostic(activity: MainActivity, query: String, before: Long): String {
        val generation = (field(activity, "destinationSearchGeneration") as Number).toLong()
        val inFlight = field(activity, "destinationSearchInFlight") as Boolean
        val acquiring = (field(activity, "destinationSearchLocationController") as? DestinationSearchLocationController)?.isAcquiring == true
        val locationMessage = field(activity, "destinationSearchLocationMessage") as? String
        val messageFailureKind = NavigationBackendErrorKind.values().firstOrNull { it.userMessage == locationMessage }
        val safeLocationMessage = when {
            locationMessage == null -> "none"
            messageFailureKind != null -> "backend_error_message_${messageFailureKind.name}"
            locationMessage in SAFE_LOCATION_MESSAGES -> locationMessage
            else -> "unrecognized_location_message_redacted"
        }
        val navigationText = (field(activity, "navigationStatusText") as TextView).text.toString()
        val latestNavigationState = (field(activity, "latestNavigationState") as? String).orEmpty()
        val navigationStage = Regex("(?:^|\\s)navigation=([a-z_]+)")
            .find(latestNavigationState)?.groupValues?.get(1)
            ?: Regex("(?:^|\\s)navigation=([a-z_]+)")
                .find(navigationText)?.groupValues?.get(1) ?: "unavailable"
        val failureKind = Regex("navigation=destination_search_failed ([a-z_]+)")
            .find(navigationText)?.groupValues?.get(1)
            ?.takeIf { candidate ->
                candidate == "executor_rejected" || NavigationBackendErrorKind.values().any { it.statusToken == candidate }
            } ?: messageFailureKind?.statusToken ?: "none"
        val stage = when {
            generation <= before && acquiring -> "awaiting_location"
            generation <= before -> "blocked_before_search_dispatch"
            inFlight -> "gateway_preflight_or_search_in_flight"
            else -> "search_finished_without_expected_candidates"
        }
        val locationMessageVisible = locationMessage != null && descendants(activity.window.decorView)
            .filterIsInstance<TextView>().any { it.text.toString() == locationMessage && visible(activity, it) }
        val permission = runCatching { invokeBoolean(activity, "hasLocationPermission") }.getOrNull()
        val service = runCatching { invokeBoolean(activity, "isLocationServiceEnabledForDeviceCheck") }.getOrNull()
        val searchAllowed = runCatching { invokeBoolean(activity, "currentDestinationSearchAllowsWork") }.getOrNull()
        return "approved_query=$query stage=$stage generation_before=$before generation_now=$generation " +
            "in_flight=$inFlight location_acquiring=$acquiring page=${page(activity)} " +
            "query_matches=${field(activity, "destinationSearchQuery") == query} " +
            "result_count=${(field(activity, "destinationSearchResults") as List<*>).size} " +
            "button_count=${resultButtons(activity).size} location_permission=$permission " +
            "location_service=$service search_allowed=$searchAllowed navigation_stage=$navigationStage " +
            "failure_kind=$failureKind failure_message_kind=${messageFailureKind?.name ?: "none"} " +
            "location_message_visible=$locationMessageVisible location_message=$safeLocationMessage"
    }

    private fun resultButtons(activity: MainActivity): List<Button> {
        val container = field(activity, "destinationSearchResultsContainer") as ViewGroup
        return (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<Button>()
    }

    private fun clickField(activity: MainActivity, name: String, deadlineMs: Long) {
        click(activity, onMain(deadlineMs) { field(activity, name) as View }, deadlineMs)
    }

    private fun click(activity: MainActivity, view: View, deadlineMs: Long) {
        reveal(activity, view, deadlineMs)
        onMain(deadlineMs) {
            assertTrue("The real control is disabled", view.isEnabled)
            assertTrue("The real control is not visibly displayed", visible(activity, view))
            assertTrue("The real control did not accept performClick", view.performClick())
        }
    }

    private fun reveal(activity: MainActivity, view: View, deadlineMs: Long) {
        onMain(deadlineMs) { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        waitUntil(deadlineMs, 3_000L, "Requested real control could not be displayed") {
            onMain(deadlineMs) { visible(activity, view) }
        }
    }

    private fun visible(activity: MainActivity, view: View): Boolean =
        activity.hasWindowFocus() && view.isShown && view.getGlobalVisibleRect(Rect())

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun page(activity: MainActivity): String = (field(activity, "nativeUiPage") as Enum<*>).name

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun invokeBoolean(activity: MainActivity, name: String): Boolean =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) as Boolean

    private fun waitUntil(
        deadlineMs: Long,
        timeoutMs: Long,
        message: String,
        onTimeout: (() -> String)? = null,
        condition: () -> Boolean,
    ) {
        val untilMs = minOf(deadlineMs, SystemClock.elapsedRealtime() + timeoutMs)
        while (SystemClock.elapsedRealtime() < untilMs) {
            if (condition()) return
            Thread.sleep(50L)
        }
        val completed = condition()
        val diagnostic = if (!completed) onTimeout?.invoke() else null
        if (diagnostic != null) Log.e("DestinationScreenTest", diagnostic)
        assertTrue(if (diagnostic == null) message else "$message; $diagnostic", completed)
    }

    private fun <T> onMain(deadlineMs: Long, action: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        assertTrue("Main-thread dispatch failed", Handler(Looper.getMainLooper()).post {
            try {
                result.set(action())
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                done.countDown()
            }
        })
        val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
        assertTrue("Device-test deadline expired", remainingMs > 0L)
        assertTrue("Main-thread action exceeded the deadline", done.await(remainingMs, TimeUnit.MILLISECONDS))
        error.get()?.let { throw it }
        return result.get()
    }

    private companion object {
        const val CAMPUS_NAME = "국립금오공과대학교"
        val SAFE_LOCATION_MESSAGES = setOf(
            "현재 위치를 확인할 수 없습니다. 위치 권한과 위치 서비스를 켠 뒤 검색을 다시 눌러 주세요.",
            "현재 위치를 확인하고 있습니다. 최대 10초 동안 기다려 주세요.",
            "현재 위치를 확인하지 못했습니다. 위치 서비스를 확인하고 검색을 다시 눌러 주세요.",
        )
    }
}
