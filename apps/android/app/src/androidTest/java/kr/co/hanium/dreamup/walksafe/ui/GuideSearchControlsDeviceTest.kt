package kr.co.hanium.dreamup.walksafe.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real saved-session HOME/card/page/cancel; no login, readiness, permission or sensor fixtures.
 * Text entry is controlled UI input. The search button is checked without sending a search request.
 */
@RunWith(AndroidJUnit4::class)
class GuideSearchControlsDeviceTest {
    @Test(timeout = 120_000L)
    fun actualGuideCardShowsOnlyDirectSearchControlsAndCancelReturnsHome() {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments()
            .getString("allowGuideSearchControlsTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val probe = MainProbe(instrumentation.targetContext.applicationContext as Application)
        try {
            probe.start()
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            probe.await("PRECONDITION: existing login/onboarding must restore actual HOME", 60_000L) {
                probe.activity.get()?.let { main -> probe.onMain("HOME prerequisite") {
                    probe.created.get() && probe.resumed.get() === main && !main.isDestroyed && main.hasWindowFocus() &&
                        page(main) == "HOME" && boolean(main, "shouldShowNativeHome") &&
                        boolean(main, "firstRunOnboardingComplete") && boolean(main, "nativeHomeFeatureContextAvailable")
                } } == true
            }
            val main = requireNotNull(probe.activity.get())
            probe.onMain("Actual HOME card click") {
                assertFalse("Do not interrupt active navigation", field(main, "isRouteActive") as Boolean)
                assertFalse("Do not interrupt recording", field(main, "voiceRecognitionActive") as Boolean)
                val card = descendants(main.window.decorView).filterIsInstance<TextView>()
                    .firstOrNull { it.text.toString() == "길라잡이" && it.isShown && it.isClickable }
                    ?: throw AssertionError("PRECONDITION: actual 길라잡이 HOME card is missing")
                assertTrue("PRECONDITION: actual guide card must be enabled", card.isEnabled)
                assertTrue("Actual HOME card must accept its real click", card.performClick())
                emit("GUIDE_CARD_CLICK", "actual_view=true session_and_readiness=UNCHANGED")
            }
            probe.await("Actual guide card must open DESTINATION_SEARCH", 10_000L) {
                probe.onMain("Search page") { page(main) == "DESTINATION_SEARCH" }
            }
            probe.onMain("Actual search controls and accessibility") {
                val presenter = field(main, "nativeScreenPresenter") as NativeScreenPresenter
                val panel = presenter.searchBinding()
                assertEquals(View.GONE, panel.nativeVoiceControls.visibility)
                val accessible = accessibilityDescendants(main.window.decorView).toSet()
                listOf(panel.voiceReportButton, panel.nativeVoiceCancelButton).forEach { voice ->
                    assertFalse("Voice button must not be displayed on search", voice.isShown)
                    assertFalse("Voice button must not occur in the accessible child tree", voice in accessible)
                    val node = voice.createAccessibilityNodeInfo()
                    try { assertFalse("Voice button must not expose a visible accessibility node", node.isVisibleToUser) }
                    finally { @Suppress("DEPRECATION") node.recycle() }
                }
                assertTrue("Forbidden search prompt must be absent from labels, hints and descriptions",
                    descendants(panel.nativeDestinationControls).none { view ->
                        val text = view as? TextView
                        listOf(text?.text, text?.hint, view.contentDescription).any { it?.contains(FORBIDDEN_PROMPT) == true }
                    })
                assertTrue("Direct search input must be shown/enabled", panel.destinationQueryInput.isShown && panel.destinationQueryInput.isEnabled)
                assertTrue("Direct search action must be shown/enabled/clickable", panel.destinationSearchButton.isShown &&
                    panel.destinationSearchButton.isEnabled && panel.destinationSearchButton.isClickable && panel.destinationSearchButton.hasOnClickListeners())
                val generation = field(main, "destinationSearchGeneration")
                assertFalse(field(main, "destinationSearchInFlight") as Boolean)
                panel.destinationQueryInput.setText("금오공대") // No search button click or IME action.
                assertEquals("금오공대", panel.destinationQueryInput.text.toString())
                assertTrue(panel.destinationSearchButton.isEnabled)
                assertEquals("Typing alone must not dispatch a search", generation, field(main, "destinationSearchGeneration"))
                assertFalse(field(main, "destinationSearchInFlight") as Boolean)
                emit("SEARCH_CONTROLS", "page=${page(main)} voice_container=GONE voice_buttons_displayed=false voice_accessibility_exposed=false forbidden_prompt=false direct_input=true search_action_enabled=true search_clicks=0")
                assertTrue(panel.destinationCancelButton.isShown && panel.destinationCancelButton.isEnabled)
                assertTrue("Actual cancel button must accept its click", panel.destinationCancelButton.performClick())
            }
            probe.await("Actual search cancel must return to HOME", 10_000L) {
                probe.onMain("HOME after cancel") { page(main) == "HOME" && boolean(main, "shouldShowNativeHome") }
            }
            probe.onMain("Cancelled input") {
                val panel = (field(main, "nativeScreenPresenter") as NativeScreenPresenter).searchBinding()
                assertTrue("Cancel must clear the typed query", panel.destinationQueryInput.text.isNullOrEmpty())
                assertFalse(panel.nativeDestinationControls.isShown)
                emit("CANCEL_HOME", "actual_cancel=true page=${page(main)} query_cleared=true ${probe.heartbeatSummary()}")
            }
        } finally { probe.close() }
    }

    private class MainProbe(private val application: Application) : Application.ActivityLifecycleCallbacks, AutoCloseable {
        val activity = AtomicReference<MainActivity?>()
        val resumed = AtomicReference<Activity?>()
        val created = AtomicBoolean(false)
        private val handler = Handler(Looper.getMainLooper())
        private val deadline = now() + 105_000L
        private val closed = AtomicBoolean(false)
        private val lastBeat = AtomicLong(now())
        private val maxGap = AtomicLong()
        private val beatCount = AtomicLong()
        private val heartbeat = object : Runnable {
            override fun run() {
                if (closed.get()) return
                val at = now(); val gap = at - lastBeat.getAndSet(at)
                maxGap.updateAndGet { maxOf(it, gap) }; beatCount.incrementAndGet()
                handler.postDelayed(this, 50L)
            }
        }
        fun start() { application.registerActivityLifecycleCallbacks(this); handler.post(heartbeat) }
        fun heartbeatSummary() = "beats=${beatCount.get()} original_last_beat_ms=${lastBeat.get()} max_gap_ms=${maxGap.get()} current_gap_ms=${now() - lastBeat.get()}"
        fun <T> onMain(label: String, block: () -> T): T {
            val task = FutureTask<T> { check(!closed.get() && now() < deadline); block() }
            check(handler.post(task))
            try { return task.get(maxOf(1L, minOf(10_000L, deadline - now())), TimeUnit.MILLISECONDS) }
            catch (timeout: TimeoutException) {
                task.cancel(false)
                val thread = Looper.getMainLooper().thread
                emit("MAIN_TIMEOUT", "stage=$label ${heartbeatSummary()} main_state=${thread.state} main_stack=${thread.stackTrace.take(24).joinToString(";")}")
                throw AssertionError("Bounded Main action timed out: $label; ${heartbeatSummary()}", timeout)
            }
        }
        fun await(label: String, timeoutMs: Long, ready: () -> Boolean) {
            val until = minOf(deadline, now() + timeoutMs)
            while (now() < until) { if (ready()) return; SystemClock.sleep(100L) }
            emit("PRECONDITION_OR_STAGE_TIMEOUT", "stage=$label ${heartbeatSummary()}")
            throw AssertionError("$label; no account/readiness state was supplied by this test")
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is MainActivity) { this.activity.set(activity); created.set(true); emit("MAIN_CREATED", "real_onCreate=true") }
        }
        override fun onActivityResumed(activity: Activity) { resumed.set(activity) }
        override fun onActivityPaused(activity: Activity) { if (resumed.get() === activity) resumed.set(null) }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
        override fun close() {
            closed.set(true); handler.removeCallbacksAndMessages(null)
            application.unregisterActivityLifecycleCallbacks(this)
            handler.post { activity.get()?.takeUnless { it.isDestroyed }?.finish() }
        }
    }

    companion object {
        private const val FORBIDDEN_PROMPT = "검색어를 입력하세요"
        private fun now() = SystemClock.elapsedRealtime()
        private fun page(main: MainActivity) = field(main, "nativeUiPage").toString()
        // Main delegates these properties to their production controllers; do not create test state.
        private fun field(owner: Any, name: String): Any? {
            val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
            val method = owner.javaClass.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() && (it.name == getter || name.startsWith("is") && it.name == name)
            }
            if (method != null) return method.apply { isAccessible = true }.invoke(owner)
            return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        }
        private fun boolean(main: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(main) as Boolean
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        private fun accessibilityDescendants(view: View): Sequence<View> = sequence {
            if (view is ViewGroup) {
                val children = arrayListOf<View>()
                view.addChildrenForAccessibility(children)
                for (child in children) { yield(child); yieldAll(accessibilityDescendants(child)) }
            }
        }
        private fun emit(event: String, details: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "GUIDE_SEARCH_CONTROLS event=$event at_ms=${now()} $details\n")
        })
    }
}
