package kr.co.hanium.dreamup.walksafe

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real account/device gates. Preview checks do not claim a real walking session. */
@RunWith(AndroidJUnit4::class)
class IntegratedUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods.single {
        it.name == name && it.parameterCount == args.size
    }.apply { isAccessible = true }.invoke(activity, *args)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitUntil(description: String, timeout: Long = 25_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do { var ready = false; main { ready = predicate() }; if (ready) return; SystemClock.sleep(100) }
        while (SystemClock.elapsedRealtime() < deadline)
        fail(description)
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
    private fun withHome(block: () -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowIntegratedUiDeviceTest") == "true")
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            waitUntil("Real account/onboarding HOME unavailable") { call("nativeHomeFeatureContextAvailable") == true }
            block()
        } finally {
            main { call("cancelVoiceCommandRecognition"); call("closeVoiceHelp", false); call("closeGuidanceTouchGuard"); activity.finish() }
        }
    }

    @Test fun logoutFixtureAccountThroughRealHandler() {
        val expected = InstrumentationRegistry.getArguments().getString("logoutExpectedActorSha256")
        assumeTrue(expected != null)
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitUntil("Account not restored") {
            val session = kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator.snapshot().session
            session?.isUsableFor(session.actorId) == true
        }
        main {
            val actor = kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator.snapshot().session!!.actorId
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(actor.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals("Only the specified synthetic account may be logged out", expected, hash)
            call("onAccountSessionButtonClicked")
        }
        waitUntil("Logout did not clear authentication") {
            kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator.snapshot().session == null
        }
        report("FIXTURE_ACCOUNT real_logout_handler=true authenticated=false")
    }

    @Test fun serverRecordingCanCancelAndReenterTwice() = withHome {
        assumeTrue(BuildConfig.WALKSAFE_SERVER_STT_ENABLED)
        for (ordinal in 1..2) {
            waitUntil("Real home voice readiness unavailable") { call("homeVoiceCommandAvailable") == true }
            main {
                val button = views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }
                assertTrue(button.performClick())
            }
            waitUntil("Server microphone capture did not begin") {
                field("gatewayVoiceRecorder") != null && field("activeGatewaySpeechInteraction") != null && field("voiceRecognitionActive") == true
            }
            main {
                assertEquals("VOICE_COMMAND", field("nativeUiPage").toString())
                assertNull("Home wake must release microphone", field("foregroundHomeWakeProbe"))
                call("cancelVoiceCommandRecognition")
                assertNull(field("gatewayVoiceRecorder")); assertNull(field("activeGatewaySpeechInteraction"))
                assertEquals(false, field("voiceRecognitionActive"))
                call("returnToCurrentInteractionArea")
                assertEquals("HOME", field("nativeUiPage").toString())
            }
            report("SERVER_CAPTURE ordinal=$ordinal real_recording=true cancel_cleared=true home_return=true; recognition_accuracy=NOT_TESTED")
        }
    }

    @Test fun fullScreenHelpButtonsAndBackRestoreHome() = withHome {
        main { assertTrue(views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick()) }
        waitUntil("Help overlay absent") { (field("voiceHelpScreen") as? View)?.isShown == true }
        main {
            val overlay = field("voiceHelpScreen") as View
            assertEquals(activity.findViewById<View>(android.R.id.content).width, overlay.width)
            assertEquals(5, views(overlay).filterIsInstance<Button>().count { it.visibility == View.VISIBLE })
        }
        screenshot("full-review-help")
        for (number in 1..4) main {
            val overlay = field("voiceHelpScreen") as View
            assertTrue(views(overlay).filterIsInstance<Button>().single { it.text.startsWith("${number}번") }.performClick())
            assertNotNull(field("voiceHelpScreen"))
        }
        main { activity.onBackPressed(); assertNull(field("voiceHelpScreen")); assertEquals("HOME", field("nativeUiPage").toString()) }
        report("HELP full_screen=true four_topic_buttons=true back_restores_home=true; spoken_content=NOT_HUMAN_VERIFIED")
    }

    @Test fun guidancePreviewShieldRequiresContinuousThreeSecondHold() = withHome {
        main { call("openGuidancePreview") }
        waitUntil("Preview did not open") { field("guidancePreviewIndex") != null && field("nativeUiPage").toString() == "GUIDANCE" }
        main {
            val buttons = listOf("guidanceVoiceButton", "nativeGuidancePauseButton", "nativeGuidanceCancelButton", "guidanceTouchGuardButton")
                .map { field(it) as Button }
            assertTrue(buttons.all { it.isShown && it.isEnabled })
            val ys = buttons.map { b -> IntArray(2).also { b.getLocationOnScreen(it) }[1] }
            assertEquals(ys.sorted(), ys)
            assertTrue((field("guidanceTouchGuardButton") as Button).performClick())
        }
        waitUntil("Shield not visible") { (field("guidanceTouchGuardScreen") as? View)?.isShown == true }
        screenshot("full-review-touch-guard")
        lateinit var release: Button
        main {
            release = views(field("guidanceTouchGuardScreen") as View).filterIsInstance<Button>().single()
            release.performClick(); assertNotNull(field("guidanceTouchGuardScreen"))
            activity.onBackPressed(); assertNotNull(field("guidanceTouchGuardScreen"))
        }
        fun touch(action: Int) = main {
            MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action,
                release.width / 2f, release.height / 2f, 0).also { release.dispatchTouchEvent(it); it.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(900); touch(MotionEvent.ACTION_UP)
        SystemClock.sleep(2300)
        main { assertNotNull("Short hold must not unlock", field("guidanceTouchGuardScreen")) }
        touch(MotionEvent.ACTION_DOWN)
        waitUntil("Continuous 3-second hold did not unlock", 5000) { field("guidanceTouchGuardScreen") == null }
        touch(MotionEvent.ACTION_UP)
        screenshot("full-review-guidance-preview")
        main { assertFalse(field("isRouteActive") as Boolean); (field("nativeGuidanceCancelButton") as Button).performClick() }
        report("GUIDANCE_PREVIEW four_vertical_controls=true shield_click_back_short_hold_rejected=true three_second_unlock=true; real_walk=NOT_TESTED")
    }

    @Test fun realDestinationPreparationRetryAndCancellation() = withHome {
        main {
            val button = views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "길라잡이" }
            assertTrue(button.performClick())
            (field("destinationQueryInput") as EditText).setText("서울역")
            assertTrue((field("destinationSearchButton") as Button).performClick())
        }
        fun candidates() = views(field("destinationSearchResultsContainer") as View).filterIsInstance<Button>()
        waitUntil("Live search did not return candidates", 45000) {
            field("destinationSearchInFlight") == false && candidates().any { it.isShown && it.isEnabled }
        }
        screenshot("full-review-station-results")
        main {
            val first = candidates().first { it.isEnabled }
            assertTrue("Named station must not resolve to an unrelated nearby shop", first.text.contains("서울역"))
            val result = (field("destinationSearchResults") as List<*>).first()!!
            val distance = result.javaClass.getMethod("getDistanceM").invoke(result) as? Int
            if (distance != null && distance >= 1000) assertTrue(first.text.contains("킬로미터"))
            report("SEARCH_DISTANCE known=${distance != null} km_when_at_least_1000m=true")
            first.performClick()
            assertEquals("GUIDANCE", field("nativeUiPage").toString())
            assertTrue("Preparation must own the walking interaction area", call("isInWalkInteractionArea") == true)
            assertFalse("Main wake must be unavailable in preparation", call("homeVoiceCommandAvailable") == true)
        }
        val destination = field("pendingUiDestination")
        var statusSpoken = false
        var statusFailed = false
        main {
            call("cancelVoiceCommandRecognition")
            call("speakCommandResponse", "현재 위치와 휴대전화 상태를 확인하고 있습니다.", false,
                { statusSpoken = true }, { statusFailed = true }, false)
        }
        waitUntil("Preparation status speech did not complete", 20000) { statusSpoken || statusFailed }
        main { assertTrue("A pending sensor check must not block real status TTS", statusSpoken && !statusFailed) }
        waitUntil("No measured result or retry action", 90000) {
            val retry = field("nativeGuidanceRetryButton") as Button
            (retry.isShown && retry.isEnabled) || field("nativeGuidanceConfirmationScreen") != null || field("isRouteActive") == true
        }
        screenshot("full-review-preparation-result")
        var retryAvailable = false
        main {
            assertEquals(destination, field("pendingUiDestination"))
            val retry = field("nativeGuidanceRetryButton") as Button
            retryAvailable = retry.isShown && retry.isEnabled
            if (retryAvailable) {
                assertFalse(field("isRouteActive") as Boolean)
                val prior = field("nativePhoneMountingCheckRequest")
                assertTrue(retry.performClick())
                assertNotSame("Retry must replace the measurement attempt", prior, field("nativePhoneMountingCheckRequest"))
                assertEquals(destination, field("pendingUiDestination"))
            }
            call("cancelNativeGuidanceAndReturnHome")
        }
        waitUntil("Cancellation did not clear route preparation") {
            field("nativeUiPage").toString() == "HOME" && field("pendingUiDestination") == null &&
                field("nativePhoneMountingCheckRequest") == null && field("isRouteActive") == false &&
                call("isInWalkInteractionArea") == false
        }
        report("REAL_SEARCH named_station=true preparing_owns_walk=true main_voice_blocked=true preparation_status_tts_completed=$statusSpoken measured_retry=$retryAvailable cancellation_cleared=true; real_walk=NOT_TESTED")
    }

    @Test fun optionalConsentGrantAndWithdrawalReachServer() = withHome {
        main { assertTrue(views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "설정" }.performClick()) }
        val item = kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem.AUTOMATIC_REPORTING
        fun consent() = field("integratedConsentSession") as kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
        waitUntil("Settings did not fetch current server consent") {
            consent().status(item).name == "CONFIRMED_DENIED" && (field("automaticReportConsentButton") as Button).isEnabled
        }
        fun diagnostic() {
            report("CONSENT_STATE status=${consent().status(item)} allowed=${consent().isAllowed(item)} " +
                "draft=${field("integratedConsentDraft")} bootstrap=${field("integratedConsentBootstrapReady")} " +
                "pending=${field("pendingIntegratedConsentMutation") != null} inFlight=${field("integratedConsentRequestInFlight")}")
        }
        main { diagnostic(); assertFalse("Fixture starts with optional reporting off", consent().isAllowed(item)); (field("automaticReportConsentButton") as Button).performClick(); diagnostic() }
        try { waitUntil("Server did not confirm grant") { consent().isAllowed(item) && (field("automaticReportConsentButton") as Button).isEnabled } }
        finally { main { diagnostic() } }
        screenshot("full-review-consent-granted")
        main {
            (field("automaticReportConsentButton") as Button).performClick()
            assertFalse("Withdrawal must block reporting immediately", consent().isAllowed(item))
        }
        waitUntil("Server withdrawal remained pending") {
            consent().status(item).name == "CONFIRMED_DENIED" && (field("automaticReportConsentButton") as Button).isEnabled
        }
        screenshot("full-review-consent-withdrawn")
        report("CONSENT real_server_grant=true withdrawal_blocks_immediately=true server_denial_confirmed=true")
    }

    @Test fun reportRecoveryCannotOverlapCloseOrSendWithoutWalk() = withHome {
        main { call("openReportPanel") }
        waitUntil("Report dialog absent") { (field("reportPanelDialog") as? android.app.Dialog)?.isShowing == true }
        instrumentation.waitForIdleSync()
        main {
            assertFalse(field("isRouteActive") as Boolean)
            assertEquals(false, field("reportPanelConfirmationArmed"))
            val dialog = field("reportPanelDialog") as android.app.Dialog
            val buttons = views(dialog.window!!.decorView).filterIsInstance<Button>().filter { it.isShown }
            val close = buttons.single { it.text == "취소 · 닫기" }
            val closeRect = android.graphics.Rect(); assertTrue(close.getGlobalVisibleRect(closeRect))
            for (button in buttons.filter { it !== close }) {
                val rect = android.graphics.Rect()
                if (button.getGlobalVisibleRect(rect)) assertFalse("Report button overlaps close action", android.graphics.Rect.intersects(rect, closeRect))
            }
        }
        screenshot("full-review-report-unavailable")
        main {
            val dialog = field("reportPanelDialog") as android.app.Dialog
            views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == "취소 · 닫기" }.performClick()
            assertNull(field("reportPanelDialog"))
        }
        report("REPORT_UI close_no_overlap=true confirmation_unarmed_without_walk=true cancel=true; actual_capture_upload=NOT_TESTED")
    }
}
