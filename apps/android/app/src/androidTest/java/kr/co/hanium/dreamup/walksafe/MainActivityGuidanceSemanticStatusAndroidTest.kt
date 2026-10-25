package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidancePresentation
import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidancePresentationPolicy
import kr.co.hanium.dreamup.walksafe.navigation.GuidanceFeatureDisplayInput
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TimestampAlignedWalkingCalibration
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.ui.DestinationSearchPresenter
import kr.co.hanium.dreamup.walksafe.ui.NativeScreenPresenter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual TextView writes from the existing Main calibration callback and shared setter.
 * No onCreate/renderMainUi, camera/sensors, healthy runtime, server or TalkBack engine is executed.
 * The default unavailable domain and actual Context/permission observations remain unchanged. */
@RunWith(AndroidJUnit4::class)
class MainActivityGuidanceSemanticStatusAndroidTest {
    @Test fun actualCalibrationCallbackKeepsSemanticTextStableAndStillDeliversChangedReasons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", instrumentation.targetContext.packageName)
        onMain(instrumentation) {
            assertNull("Do not alter another account", GatewaySessionProcessCoordinator.snapshot().session)
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            val main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Guidance semantic probe", null, null, null) as MainActivity
            val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
            val status = TextView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            val measurement = TextView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            }
            var statusWrites = 0
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { statusWrites++ }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            status.addTextChangedListener(watcher)
            try {
                set(main, "walkSessionLifecycle", lifecycle)
                set(main, "nativeGuidanceStatusText", status)
                set(main, "nativeGuidanceMeasurementText", measurement)
                val guidancePanel = (MainActivityControllerTestFixture.controller(main, "nativeScreenPresenter")
                    as NativeScreenPresenter).guidanceBinding()
                assertSame(status, guidancePanel.nativeGuidanceStatusText)
                assertSame(measurement, guidancePanel.nativeGuidanceMeasurementText)
                (field(main, "destinationSearchPresenter") as DestinationSearchPresenter).bindPanel(guidancePanel)
                val page = requireNotNull(field(main, "nativeUiPage"))
                set(main, "nativeUiPage", page.javaClass.enumConstants.single { (it as Enum<*>).name == "GUIDANCE" })
                val before = lifecycle.snapshot()
                invoke(main, "refreshWalkingCalibrationDiagnostics", Void.TYPE)
                assertEquals(1, statusWrites)
                val firstMeaning = status.text.toString()
                val firstDiagnostic = measurement.text.toString()
                val collector = field(main, "walkingCalibrationSegments") as TimestampAlignedWalkingCalibration
                val timestamp = SystemClock.elapsedRealtime()
                val step = StepEvent(1, timestamp, StepEventSource.STEP_DETECTOR, StepEventConfidence.LOW)
                assertTrue(collector.onStep(timestamp, 1, false, SystemClock.elapsedRealtime()).isEmpty())
                set(main, "latestWalkingCalibrationStep", step)
                // Existing production callback observes the real changed diagnostic/profile/scope.
                invoke(main, "refreshWalkingCalibrationDiagnostics", Void.TYPE)
                assertEquals("Individual calibration diagnostics must not rewrite the POLITE status", 1, statusWrites)
                assertEquals(firstMeaning, status.text.toString())
                assertNotEquals(firstDiagnostic, measurement.text.toString())
                assertTrue(measurement.text.contains("신뢰가 낮아"))
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, measurement.importantForAccessibility)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, measurement.accessibilityLiveRegion)
                invoke(main, "refreshWalkingCalibrationDiagnostics", Void.TYPE)
                assertEquals(1, statusWrites)
                val originalPresentation = invoke(main, "currentNativeGuidancePresentation", DestinationGuidancePresentation::class.java)
                    as DestinationGuidancePresentation
                val calibration = invoke(main, "walkingCalibrationStatusMessage", String::class.java) as String
                val changed = DestinationGuidancePresentationPolicy.present(
                    GuidanceFeatureDisplayInput(false, false, "현재 위치를 다시 확인해 주세요."),
                    GuidanceFeatureDisplayInput(false, false, "렌즈를 완전히 열어 주세요."), true)
                invoke(main, "updateNativeGuidanceStatus", Void.TYPE,
                    arrayOf(DestinationGuidancePresentation::class.java, String::class.java), changed, calibration)
                assertEquals(2, statusWrites)
                assertTrue(status.text.contains(changed.routeMessageKo))
                assertTrue(status.text.contains(changed.cameraMessageKo))
                val full = invoke(main, "nativeGuidanceStatusMessage", String::class.java,
                    arrayOf(DestinationGuidancePresentation::class.java), originalPresentation) as String
                assertTrue(full.contains(originalPresentation.routeMessageKo))
                assertEquals(before, lifecycle.snapshot())
                assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            } finally {
                status.removeTextChangedListener(watcher)
                lifecycle.handle(WalkSessionEvent.EndRequested)
            }
        }
    }

    private companion object {
        fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun invoke(main: MainActivity, name: String, result: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
            assertEquals(result, method.returnType)
            return try { method.invoke(main, *args) } catch (t: InvocationTargetException) { throw t.targetException }
        }
        fun onMain(instrumentation: Instrumentation, action: () -> Unit) {
            val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { action() } catch (t: Throwable) { failure.set(t) } }
            failure.get()?.let { throw it }
        }
    }
}
