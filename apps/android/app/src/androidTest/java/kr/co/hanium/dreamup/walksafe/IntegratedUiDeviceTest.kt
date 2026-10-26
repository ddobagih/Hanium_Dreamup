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
            kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin.ensureLoggedIn(
                instrumentation, activity, SystemClock.elapsedRealtime()+20_000L)
            waitUntil("Real account/onboarding HOME unavailable") { call("nativeHomeFeatureContextAvailable") == true }
            block()
        } finally {
            main { call("cancelVoiceCommandRecognition"); call("closeVoiceHelp", false); call("closeGuidanceTouchGuard"); activity.finish() }
        }
    }

    /** Synthetic candidates, real MainActivity/actuator/TTS callbacks. No route or report submitted. */
    @Test fun candidateSpeechCancellationRetryAndDetailKeepSelectionAuthority() = withHome {
        val state = kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState(
            "시험 후보", (1..6).map {
                kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult(
                    "$it", "시험 후보 $it", kr.co.hanium.dreamup.walksafe.navigation.RoutePoint(37.5, 127.0),
                    null, "시험로 12-$it", null, 100,
                )
            }, pageIndex = 1,
        )
        fun set(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(activity, value)
        fun selectable() = state.onCommand(
            kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand.SelectCandidate(4),
        ).accepted
        lateinit var tracker: kr.co.hanium.dreamup.walksafe.voice.CommandSpeechTracker
        main {
            assertFalse(call("isWalkSessionRuntimeActive") == true)
            call("cancelVoiceCommandRecognition")
            val page = field("nativeUiPage") as Enum<*>
            call("showNativeUiPage", page.javaClass.enumConstants!!.first { (it as Enum<*>).name == "VOICE_COMMAND" })
            set("destinationSearchQuery", state.query)
            set("destinationSearchVoiceState", state)
            tracker = field("commandSpeechTracker") as kr.co.hanium.dreamup.walksafe.voice.CommandSpeechTracker
            set("commandSpeechOwner", tracker.begin(101))
            try { assertTrue(call("speakHomeDestinationVoicePrompt", state, state.voicePrompt(), false) as Boolean) }
            finally { set("commandSpeechOwner", null) }
        }
        waitUntil("First real candidate did not finish", 45_000L) { state.completedCandidateNumbers().contains(4) }
        main {
            assertFalse("A partly read page is not selectable", selectable())
            call("cancelCommandSpeechResponse")
            assertFalse(selectable())
            assertEquals(kr.co.hanium.dreamup.walksafe.voice.CommandSpeechState.CANCELLED, tracker.progress(101).state)
        }
        SystemClock.sleep(500L)
        main {
            assertFalse("Late callbacks must not authorize the cancelled page", selectable())
            set("commandSpeechOwner", tracker.begin(102))
            try { assertTrue(call("speakHomeDestinationVoicePrompt", state, state.voicePrompt(), false) as Boolean) }
            finally { set("commandSpeechOwner", null) }
        }
        waitUntil("Retry did not complete the real page", 75_000L) { selectable() }
        val before = field("pendingUiDestination")
        main {
            assertEquals(setOf(4, 5, 6), state.completedCandidateNumbers())
            assertEquals(kr.co.hanium.dreamup.walksafe.voice.CommandSpeechState.COMPLETED, tracker.progress(102).state)
            set("commandSpeechOwner", tracker.begin(103))
            try { call("describeVoiceDestinationCandidate", 4) }
            finally { set("commandSpeechOwner", null) }
        }
        waitUntil("Detail did not complete real TTS", 45_000L) {
            tracker.progress(103).state == kr.co.hanium.dreamup.walksafe.voice.CommandSpeechState.COMPLETED
        }
        main {
            assertEquals("Detail must not select a destination", before, field("pendingUiDestination"))
            call("cancelVoiceCommandRecognition")
            call("clearNativeDestinationSearchState")
        }
        report("CANDIDATE_SEQUENCE real_tts=true cancelled_page_blocked=true retry_completed=true detail_read_only=true candidates=synthetic")
    }

    /** Real page handlers and TTS; synthetic candidates isolate replacement from search/ASR. */
    @Test fun replacingPartlySpokenCandidatePageCannotAuthorizeRetiredNumbers() = withHome {
        val mode=requireNotNull(InstrumentationRegistry.getArguments().getString("candidatePageReplacement"))
        require(mode in setOf("next","previous","repeat"))
        val initialPage=if(mode=="previous") 1 else 0
        val expectedPage=if(mode=="next") 1 else 0
        val state=kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState(
            "목록 전환 시험",(1..6).map {
                kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult(
                    "replacement-$it","시험역 $it",kr.co.hanium.dreamup.walksafe.navigation.RoutePoint(37.5,127.0),
                    null,"시험로 $it",null,100)
            },pageIndex=initialPage)
        fun current()=field("destinationSearchVoiceState") as? kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
        fun selected(s:kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState,number:Int)=
            s.onCommand(kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand.SelectCandidate(number)).selectedResult
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            call("cancelVoiceCommandRecognition")
            val page=field("nativeUiPage") as Enum<*>
            call("showNativeUiPage",page.javaClass.enumConstants!!.single{(it as Enum<*>).name=="DESTINATION_SEARCH"})
            MainActivity::class.java.getDeclaredField("destinationSearchQuery").apply{isAccessible=true}.set(activity,state.query)
            MainActivity::class.java.getDeclaredField("destinationSearchVoiceState").apply{isAccessible=true}.set(activity,state)
            assertTrue(call("speakHomeDestinationVoicePrompt",state,state.voicePrompt(),true) as Boolean)
        }
        waitUntil("Native first candidate must finish while next candidate still speaks",45_000) {
            val a=field("feedbackActuator") ?: return@waitUntil false
            val tts=a.javaClass.getDeclaredField("textToSpeech").apply{isAccessible=true}.get(a) as android.speech.tts.TextToSpeech
            state.completedCandidateNumbers()==setOf(initialPage*3+1)&&tts.isSpeaking
        }
        lateinit var replacement:kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
        main {
            assertNull(selected(state,initialPage*3+1));assertNull(field("gatewayVoiceRecorder"))
            call(when(mode){"next"->"hearMoreVoiceDestinationCandidates";"previous"->"previousVoiceDestinationCandidates";else->"repeatVoiceDestinationCandidates"})
            replacement=requireNotNull(current());assertEquals(expectedPage,replacement.pageIndex)
            if(mode!="repeat")assertNotSame(state,replacement)
            assertTrue(replacement.completedCandidateNumbers().isEmpty())
            assertNull(selected(replacement,expectedPage*3+1));assertNull(field("gatewayVoiceRecorder"))
        }
        waitUntil("Replacement page must complete before opening microphone",90_000) {
            current()===replacement&&replacement.completedCandidateNumbers()==(expectedPage*3+1..expectedPage*3+3).toSet()&&field("gatewayVoiceRecorder")!=null
        }
        main {
            assertNotNull(selected(replacement,expectedPage*3+1))
            assertNull(selected(replacement,if(expectedPage==0)4 else 1))
            if(mode!="repeat")assertNull(selected(state,initialPage*3+1))
            assertFalse(field("isRouteActive") as Boolean);assertNull(field("pendingUiDestination"))
            assertEquals(false,field("destinationSearchInFlight"))
            call("cancelNativeDestinationSearchAndReturnHome")
            assertNull(field("gatewayVoiceRecorder"));assertNull(current())
        }
        report("CANDIDATE_PAGE_REPLACEMENT mode=$mode actual_native_speech=true incomplete_page_blocked=true current_page_only_authorized=true old_page_not_revived=true actual_followup_mic=true destination_not_selected=true synthetic_candidates=true ASR_and_human_audibility=NOT_TESTED")
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

    /** Actual OS denial through the permission dialog; host restores permission and flags. */
    @Test fun deniedMicrophoneShowsSettingsAndReturningDoesNotStartCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowIntegratedUiDeviceTest") == "true")
        val deniedPermission = InstrumentationRegistry.getArguments().getString("deniedPermission")
            ?: android.Manifest.permission.RECORD_AUDIO
        val label = mapOf(android.Manifest.permission.RECORD_AUDIO to "마이크",
            android.Manifest.permission.POST_NOTIFICATIONS to "알림",
            android.Manifest.permission.ACCESS_FINE_LOCATION to "정확한 위치",
            android.Manifest.permission.ACTIVITY_RECOGNITION to "보행 센서")[deniedPermission]
            ?: error("Unsupported isolated permission probe")
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                activity.checkSelfPermission(deniedPermission))
            // Launch legitimately requests the selected missing permission before HOME.
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val deadline = SystemClock.elapsedRealtime() + 15000
            var denied = false
            var lastWindows = ""
            while (!denied && SystemClock.elapsedRealtime() < deadline) {
                val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                lastWindows = roots.joinToString { it.packageName.toString() }
                val button = roots.filter { it.packageName?.toString()?.endsWith("permissioncontroller") == true }
                    .flatMap { it.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button") }
                    .firstOrNull { it.isClickable && it.isEnabled }
                if (button != null) denied = button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                if (!denied) SystemClock.sleep(100)
            }
            assertTrue("Actual OS permission dialog must be denied by this test; windows=$lastWindows", denied)
            fun recoveryDialog() = field("initialAppPermissionExitDialog") as? android.app.AlertDialog
            waitUntil("Initial permission denial must expose its recovery dialog") { recoveryDialog()?.isShowing == true }
            main {
                val content = requireNotNull(recoveryDialog()?.window?.decorView)
                val text = views(content).filterIsInstance<android.widget.TextView>().joinToString(" ") { it.text }
                report("PERMISSION_DENIED_CAUSE permission=$deniedPermission detail=$text")
                assertTrue("Missing human-readable permission cause: $label", text.contains(label))
                assertFalse("Internal Android identifiers must not be shown to the user", text.contains("android.permission."))
                assertTrue(field("initialAppPermissionExitRequired") as Boolean)
                assertNull(field("gatewayVoiceRecorder"))
                assertFalse(field("voiceRecognitionActive") as Boolean)
                assertTrue(views(content).filterIsInstance<Button>().single { it.text == "설정 열기" }.performClick())
            }
            waitUntil("Actual Android settings must take the foreground") { field("isActivityForeground") == false }
            SystemClock.sleep(1000)
            assertEquals("com.android.settings", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitUntil("Back from settings must restore the Activity") { field("isActivityForeground") == true }
            val until = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < until) {
                main { assertNull(field("gatewayVoiceRecorder")); assertFalse(field("voiceRecognitionActive") as Boolean) }
                SystemClock.sleep(100)
            }
            main {
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                    activity.checkSelfPermission(deniedPermission))
                assertTrue(recoveryDialog()?.isShowing == true)
                assertTrue(field("initialAppPermissionExitRequired") as Boolean)
            }
            report("PERMISSION_DENIED permission=$deniedPermission real_os_dialog=true initial_entry_blocked=true cause_visible=true actual_settings_opened=true return_did_not_record=true observation_ms=3000 walking_session=false")
        } finally { main { call("cancelVoiceCommandRecognition"); activity.finish() } }
    }

    /** Requires the actual persisted recheck left by the permission/settings round-trip test. */
    @Test fun restoredPermissionsAllowMainVoiceWithoutClearingWalkRecovery() = withHome {
        waitUntil("Restored main microphone availability") { call("homeVoiceCommandAvailable") == true }
        fun gate() = field("permissionRecoveryGate") as kr.co.hanium.dreamup.walksafe.session.PermissionRecoveryGate
        main {
            assertTrue("Do not seed or clear the real persisted recovery state", gate().blocksAutomaticResourceStart)
            assertTrue((call("requiredPostLoginDeviceCheckPermissions") as List<*>).isEmpty())
            assertFalse((field("permissionDenialPanel") as View).isShown)
            assertFalse(call("isInWalkInteractionArea") == true)
            assertFalse(field("isRouteActive") as Boolean)
            assertTrue(views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.isShown && it.text == "음성 명령" }.performClick())
        }
        waitUntil("Real server recorder must start after an explicit button") {
            field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true
        }
        main {
            assertTrue(gate().blocksAutomaticResourceStart)
            assertFalse(field("isRouteActive") as Boolean)
            call("cancelVoiceCommandRecognition")
            call("returnToCurrentInteractionArea")
            assertEquals("HOME", field("nativeUiPage").toString())
            assertFalse((field("permissionDenialPanel") as View).isShown)
        }
        report("MAIN_PERMISSION_RECOVERY real_persisted_gate=true gate_not_cleared=true main_panel_hidden=true explicit_native_recording=true route_started=false readiness_injected=false")
    }

    /** Host removes only this phone's USB forwarding after capture starts, then restores it. */
    @Test fun actualSpeechConnectionFailureOffersExplicitRecovery() = withHome {
        val help = InstrumentationRegistry.getArguments().getString("speechNetworkHelp") == "true"
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.isShown && it.text == if (help) "도움말" else "음성 명령" }.performClick()
        }
        waitUntil("Real microphone did not start", 45_000) { field("gatewayVoiceRecorder") != null }
        report("NETWORK_FAILURE_READY help=$help")
        waitUntil("Actual unavailable server was not reported", 30_000) {
            field("activeGatewaySpeechInteraction") == null &&
                (field("gatewayVoiceStatusText") as android.widget.TextView).text.contains("서버 음성 인식을 사용할 수 없습니다")
        }
        main {
            assertNull(field("gatewayVoiceRecorder")); assertNull(field("gatewaySpeechCall")); assertNull(field("gatewayVoiceUploadFile"))
            assertFalse(field("isRouteActive") as Boolean)
            if (help) {
                assertNotNull(field("voiceHelpScreen"))
                assertTrue((field("voiceHelpRecovery") as Button).isShown)
                assertTrue((field("voiceHelpSummary") as android.widget.TextView).text.contains("서버 음성"))
            }
        }
        report("NETWORK_FAILURE_RESTORE help=$help cause_exposed=true audio_released=true")
        repeat(30) { main { assertNull(field("gatewayVoiceRecorder")); assertNull(field("gatewaySpeechCall")) }; SystemClock.sleep(100) }
        waitUntil("Retry control did not become available", 20_000) {
            val button = field(if (help) "voiceHelpRecovery" else "voiceReportButton") as Button
            button.isShown && button.isEnabled && call("isHandsFreeVoiceOutputActive") != true
        }
        main { assertTrue((field(if (help) "voiceHelpRecovery" else "voiceReportButton") as Button).performClick()) }
        waitUntil("Explicit retry did not open microphone", 30_000) { field("gatewayVoiceRecorder") != null }
        val verifyResponse = InstrumentationRegistry.getArguments().getString("verifyRecoveredSpeechResponse") == "true"
        if (verifyResponse) {
            report("NETWORK_RECOVERY_SPEAK help=$help")
            waitUntil("Recovered real server speech did not produce expected help action", 45_000) {
                field("voiceHelpScreen") != null &&
                    (!help || (field("voiceHelpSummary") as android.widget.TextView).text.toString() == "길안내 사용법")
            }
            main { assertFalse(field("isRouteActive") as Boolean) }
            report("NETWORK_RECOVERY_RESPONSE help=$help expected_help_action=true input=synthetic_speaker actual_microphone_and_server=true")
        }
        main {
            call("cancelVoiceCommandRecognition")
            if (help) call("closeVoiceHelp", false)
            call("returnToCurrentInteractionArea")
        }
        report("NETWORK_FAILURE help=$help automatic_capture_absent_ms=3000 explicit_retry_recording=true account_gate_preserved=true; recovered_response_tested=$verifyResponse")
    }

    /** Actual microphone/upload cancellation, with host synthetic speaker input only. */
    @Test fun actualSpeechUploadExitCancelsRequestAndPreventsLateExecution() = withHome {
        val mode = requireNotNull(InstrumentationRegistry.getArguments().getString("speechUploadExit"))
        require(mode in setOf("button", "help", "background", "recreate", "recreate_help", "lock", "lock_help", "screen_off", "screen_off_help"))
        if (mode.startsWith("lock")) {
            assumeTrue("Actual keyguard must be configured before testing lock",
                instrumentation.targetContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardSecure)
        }
        val help = mode == "help" || mode.endsWith("_help")
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.isShown && it.text == if (help) "도움말" else "음성 명령" }.performClick()
        }
        waitUntil("Real microphone did not start", 45_000) { field("gatewayVoiceRecorder") != null }
        report("UPLOAD_EXIT_READY mode=$mode")
        waitUntil("No actual in-flight transcription observed", 30_000) { field("gatewaySpeechCall") != null }
        var oldCall: kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall<*>? = null
        var audio: File? = null
        main {
            oldCall = field("gatewaySpeechCall") as? kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall<*>
            audio = field("gatewayVoiceUploadFile") as? File
            assertNotNull("Server completed before cancellation; cannot claim this case", oldCall)
            assertNotNull(audio)
            report("UPLOAD_EXIT_IN_FLIGHT mode=$mode audio_bytes=${audio!!.length()}")
            if (mode == "button") assertTrue((field("nativeVoiceCancelButton") as Button).performClick())
            if (mode == "help") assertTrue(views(field("voiceHelpScreen") as View).filterIsInstance<Button>()
                .single { it.text == "도움말 닫기" }.performClick())
        }
        if (mode == "background") {
            instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("Activity did not leave foreground") { field("isActivityForeground") == false }
        }
        if (mode.startsWith("recreate")) {
            val old = activity
            main { old.recreate() }
            waitUntil("In-flight request Activity did not recreate", 30_000) {
                val next = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull { it !== old }
                if (next != null) activity = next
                next != null && old.isDestroyed
            }
            waitUntil("Actual account did not restore after recreation", 30_000) { call("nativeHomeFeatureContextAvailable") == true }
        }
        if (mode.startsWith("lock") || mode.startsWith("screen_off")) {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            waitUntil("Locked Activity did not leave foreground") { field("isActivityForeground") == false }
            val power = instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
            waitUntil("Actual screen did not turn off") { !power.isInteractive }
            if (mode.startsWith("lock")) {
                val keyguard = instrumentation.targetContext.getSystemService(android.app.KeyguardManager::class.java)
                waitUntil("Real keyguard did not lock") { keyguard.isKeyguardLocked }
            }
        }
        main {
            assertTrue("Actual network call must be cancelled", oldCall!!.isCancelled())
            assertFalse("Uploaded audio must be removed", audio!!.exists())
        }
        repeat(50) {
            main {
                assertNull(field("gatewaySpeechCall")); assertNull(field("gatewayVoiceRecorder"))
                assertNull(field("activeGatewaySpeechInteraction")); assertNull(field("voiceHelpScreen"))
                assertFalse(field("isRouteActive") as Boolean)
                assertFalse((field("voiceCommandErrorPanel") as View).isShown)
            }
            SystemClock.sleep(100)
        }
        if (mode.startsWith("lock") || mode.startsWith("screen_off")) {
            report("UPLOAD_EXIT_UNLOCK mode=$mode screen_off_observed=true")
            val keyguard = instrumentation.targetContext.getSystemService(android.app.KeyguardManager::class.java)
            val power = instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
            waitUntil("Host did not wake/unlock the real device", 60_000) { power.isInteractive && !keyguard.isKeyguardLocked }
        }
        if (mode == "background" || mode.startsWith("lock") || mode.startsWith("screen_off")) {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitUntil("Activity did not return") { field("isActivityForeground") == true }
            main { call("returnToCurrentInteractionArea") }
        }
        repeat(30) {
            main {
                assertNull(field("gatewayVoiceRecorder")); assertNull(field("gatewaySpeechCall"))
                assertNull(field("voiceHelpScreen")); assertNull(field("activeGatewaySpeechInteraction"))
            }
            SystemClock.sleep(100)
        }
        main { assertEquals("HOME", field("nativeUiPage").toString()) }
        report("UPLOAD_EXIT mode=$mode cancelled=true audio_deleted=true late_effects_absent_ms=5000 command_accuracy=NOT_TESTED")
        waitUntil("Explicit voice retry unavailable") { call("homeVoiceCommandAvailable") == true }
        main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick() }
        waitUntil("Explicit request did not start a new microphone") { field("gatewayVoiceRecorder") != null }
        main { (field("nativeVoiceCancelButton") as Button).performClick() }
        report("UPLOAD_EXIT mode=$mode explicit_retry_recording=true")
    }

    @Test fun listeningCuePrecedesCaptureAndCancelledCueCannotReopenMicrophone() = withHome {
        assumeTrue(BuildConfig.WALKSAFE_SERVER_STT_ENABLED)
        for (mode in listOf("capture", "cancel", "back", "help")) {
            waitUntil("Real home voice readiness unavailable") { call("homeVoiceCommandAvailable") == true }
            main {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (mode == "help") call("openVoiceHelp")
                else assertTrue(views(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.isShown && it.text == "음성 명령" }.performClick())
            }
            var observed = false
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            while (!observed && SystemClock.elapsedRealtime() < deadline) {
                main {
                    val cue = field("voiceListeningStartCue") as? kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementPlayer
                    if (cue?.isPlaying() == true) {
                        observed = true
                        assertNull("The cue must not be recorded", field("gatewayVoiceRecorder"))
                        assertEquals(false, field("voiceRecognitionActive"))
                        assertEquals(false, call("isHandsFreeVoiceSpeechOutputActive"))
                        // A duplicate request must leave the original bounded cue running.
                        val generation = cue.javaClass.getDeclaredField("generation").apply { isAccessible = true }
                        val before = generation.get(cue)
                        assertEquals(true, call("startVoiceCommandRecognition", field("voiceRecognitionPurpose"), null))
                        assertEquals(before, generation.get(cue))
                        when (mode) {
                            "cancel" -> call("cancelVoiceCommandRecognition")
                            "back" -> activity.onBackPressed()
                        }
                    }
                }
                if (!observed) SystemClock.sleep(5)
            }
            assertTrue("No actual listening cue observed: $mode", observed)
            if (mode == "capture" || mode == "help") {
                waitUntil("Cue did not hand over to the real microphone: $mode") { field("gatewayVoiceRecorder") != null }
                main { assertFalse((field("voiceListeningStartCue") as kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementPlayer).isPlaying()) }
            } else {
                SystemClock.sleep(800)
                main { assertNull("Cancelled cue reopened microphone: $mode", field("gatewayVoiceRecorder")); assertEquals(false, field("voiceRecognitionActive")) }
            }
            main { call("cancelVoiceCommandRecognition"); call("closeVoiceHelp", false); call("returnToCurrentInteractionArea") }
            report("LISTENING_CUE mode=$mode real_tts=true cue_observed=true capture_after_cue_only=true duplicate_preserved=true human_audibility=NOT_TESTED")
        }
    }

    /** Fault injection into the audio owner only; account, microphone and session gates stay real. */
    @Test fun failedListeningCueKeepsMicrophoneClosedAndOffersExplicitRetry() = withHome {
        assumeTrue(BuildConfig.WALKSAFE_SERVER_STT_ENABLED)
        val cueField = MainActivity::class.java.getDeclaredField("voiceListeningStartCue").apply { isAccessible = true }
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            call("cancelVoiceCommandRecognition")
            (cueField.get(activity) as? kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementPlayer)?.close()
            cueField.set(activity, kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementPlayer(activity, { false }).also { it.close() })
        }
        try {
            waitUntil("Real home voice readiness unavailable") { call("homeVoiceCommandAvailable") == true }
            main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick() }
            waitUntil("Cue failure did not expose recovery") {
                (field("voiceCommandErrorPanel") as View).isShown &&
                    (field("voiceCommandErrorText") as android.widget.TextView).text.contains("알림음")
            }
            SystemClock.sleep(800)
            main {
                assertNull(field("gatewayVoiceRecorder"))
                assertEquals(false, field("voiceRecognitionActive"))
                cueField.set(activity, null) // Restore a usable audio owner before explicit retry.
                assertTrue((field("voiceCommandErrorAction") as Button).performClick())
            }
            waitUntil("Explicit retry did not open the real microphone") { field("gatewayVoiceRecorder") != null }
            report("LISTENING_CUE injected_closed_owner=true failure_no_capture=true explicit_retry_real_capture=true")
        } finally {
            main { (cueField.get(activity) as? kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementPlayer)?.close(); cueField.set(activity, null) }
        }
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

    @Test fun longRealCommandSpeechCompletesAfterThirtySecondsWithoutEarlySuccess() = withHome {
        val sentence = "음성 안내의 완료 처리를 확인하고 있습니다. 안내가 모두 끝나기 전에는 다음 명령을 듣거나 완료로 처리하지 않습니다."
        val singleUtterance = InstrumentationRegistry.getArguments().getString("singleLongUtterance") == "true"
        val parts = if (singleUtterance) listOf(List(8) { sentence }.joinToString(" ")) else List(12) { sentence }
        var completed = 0
        var failed = 0
        var delivered = 0
        var began = 0L
        lateinit var tracker: kr.co.hanium.dreamup.walksafe.voice.CommandSpeechTracker
        val commandId = 9001L
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            call("cancelVoiceCommandRecognition")
            val page = field("nativeUiPage") as Enum<*>
            call("showNativeUiPage", page.javaClass.enumConstants!!.single { (it as Enum<*>).name == "VOICE_COMMAND" })
            tracker = field("commandSpeechTracker") as kr.co.hanium.dreamup.walksafe.voice.CommandSpeechTracker
            val ownerField = MainActivity::class.java.getDeclaredField("commandSpeechOwner").apply { isAccessible = true }
            ownerField.set(activity, tracker.begin(commandId))
            began = SystemClock.elapsedRealtime()
            try {
                assertTrue(call("speakCommandResponse", parts.joinToString(" "), false,
                    { completed++ }, { failed++ }, false, parts, { _: Int -> delivered++ }, { true }) as Boolean)
            } finally { ownerField.set(activity, null) }
        }
        waitUntil("Real long response did not reach 31 seconds", 35_000) { SystemClock.elapsedRealtime() - began >= 31_000 }
        main {
            assertEquals("No failure at the former fixed 30 second boundary", 0, failed)
            assertEquals("Partial long speech must not be complete", 0, completed)
            assertNotEquals(kr.co.hanium.dreamup.walksafe.voice.CommandSpeechState.COMPLETED, tracker.progress(commandId).state)
            assertNull("Speech must not open capture early", field("gatewayVoiceRecorder"))
        }
        val engineStop = InstrumentationRegistry.getArguments().getString("longEngineStop")
        if (engineStop != null) {
            require(singleUtterance && engineStop in setOf("timeout", "cancel"))
            fun actualTts(): android.speech.tts.TextToSpeech {
                val actuator = field("feedbackActuator")!!
                return actuator.javaClass.getDeclaredField("textToSpeech").apply { isAccessible = true }
                    .get(actuator) as android.speech.tts.TextToSpeech
            }
            main { assertTrue("Host must interrupt a still-playing long utterance", actualTts().isSpeaking) }
            report("LONG_ENGINE_STOP_READY mode=$engineStop elapsed_ms=${SystemClock.elapsedRealtime() - began} actual_single_utterance_speaking=true")
            waitUntil("Host did not interrupt the actual speech service", 15_000) { !actualTts().isSpeaking }
            if (engineStop == "cancel") {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitUntil("Back during dead-engine response did not return HOME") { field("nativeUiPage").toString() == "HOME" }
            } else {
                waitUntil("Dead long utterance never reached failure", 130_000) { failed > 0 }
                main {
                    assertEquals(1, failed)
                    assertEquals(kr.co.hanium.dreamup.walksafe.voice.CommandSpeechState.FAILED, tracker.progress(commandId).state)
                }
            }
            repeat(30) {
                main {
                    assertEquals(0, completed); assertEquals(0, delivered)
                    assertNotEquals(kr.co.hanium.dreamup.walksafe.voice.CommandSpeechState.COMPLETED, tracker.progress(commandId).state)
                    assertNull(field("gatewayVoiceRecorder"))
                }
                SystemClock.sleep(100)
            }
            main {
                call("returnToCurrentInteractionArea")
                views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick()
            }
            waitUntil("Explicit new help action did not recover speech and microphone", 65_000) { field("gatewayVoiceRecorder") != null }
            main { assertEquals(0, completed); assertEquals(0, delivered); call("closeVoiceHelp", false) }
            report("LONG_ENGINE_FAILURE mode=$engineStop elapsed_ms=${SystemClock.elapsedRealtime() - began} old_complete=0 old_parts=0 no_auto_mic=true explicit_new_help_recovered=true human_audibility=NOT_TESTED")
            return@withHome
        }
        waitUntil("Long actual speech did not finish", 120_000) { completed > 0 || failed > 0 }
        main {
            assertEquals(0, failed)
            assertEquals(1, completed)
            assertEquals(parts.size, delivered)
            assertEquals(kr.co.hanium.dreamup.walksafe.voice.CommandSpeechState.COMPLETED, tracker.progress(commandId).state)
            report("LONG_COMMAND_TTS single_utterance=$singleUtterance elapsed_ms=${SystemClock.elapsedRealtime() - began} completed_parts=$delivered actual_onDone=true no_early_success_at_31_seconds=true; synthesized_test_text=true human_audibility=NOT_TESTED")
            call("returnToCurrentInteractionArea")
        }
    }

    @Test fun actualServerRecordingCancellationLatencyAcrossNineEntries() = withHome {
        val samples = mutableListOf<Double>()
        val frameSamples = org.json.JSONArray()
        main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        repeat(9) { index ->
            waitUntil("Real HOME voice entry unavailable") { call("homeVoiceCommandAvailable") == true }
            main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick() }
            waitUntil("Real microphone did not start", 30_000) {
                field("gatewayVoiceRecorder") != null && field("activeGatewaySpeechInteraction") != null
            }
            val nextFrame = java.util.concurrent.CountDownLatch(1)
            var previousFrameNs = 0L
            var cancelBeganNs = 0L
            var frameGapMs = 0.0
            var cancelToFrameMs = 0.0
            val observer = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (cancelBeganNs > 0L) {
                        frameGapMs = (frameTimeNanos - previousFrameNs) / 1_000_000.0
                        cancelToFrameMs = (SystemClock.elapsedRealtimeNanos() - cancelBeganNs) / 1_000_000.0
                        nextFrame.countDown()
                    } else {
                        previousFrameNs = frameTimeNanos
                        android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
            main { android.view.Choreographer.getInstance().postFrameCallback(observer) }
            try {
                SystemClock.sleep(150)
                main {
                    assertTrue("Pre-cancel frame not observed", previousFrameNs > 0L)
                    val began = SystemClock.elapsedRealtimeNanos()
                    cancelBeganNs = began
                    assertTrue((field("nativeVoiceCancelButton") as Button).performClick())
                    samples += (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0
                    assertNull(field("gatewayVoiceRecorder"))
                    assertNull(field("activeGatewaySpeechInteraction"))
                    assertNull(field("gatewaySpeechCall"))
                    assertFalse(field("voiceRecognitionActive") as Boolean)
                    assertEquals("HOME", field("nativeUiPage").toString())
                }
                assertTrue("Post-cancel frame not observed", nextFrame.await(5, java.util.concurrent.TimeUnit.SECONDS))
                frameSamples.put(org.json.JSONObject().put("frameGapMs", frameGapMs).put("cancelToNextFrameCallbackMs", cancelToFrameMs))
                report("CANCEL_LATENCY sample=${index + 1} main_handler_ms=${samples.last()} frame_gap_ms=$frameGapMs cancel_to_next_frame_callback_ms=$cancelToFrameMs real_recording=true")
            } finally {
                main { android.view.Choreographer.getInstance().removeFrameCallback(observer) }
            }
        }
        val sorted = samples.sorted()
        val result = org.json.JSONObject().put("samplesMs", org.json.JSONArray(samples))
            .put("medianMs", sorted[sorted.size / 2]).put("maxMs", sorted.last()).put("frameSamples", frameSamples)
            .put("scope", "Actual server-recording cancel handler and Choreographer callback gaps; nine repetitions. Callback recovery is not display presentation latency or long-run ANR certification")
        File(instrumentation.targetContext.cacheDir, "walkmate-cancel-latency.json").writeText(result.toString(2))
        report("CANCEL_LATENCY_RESULT $result")
    }

    @Test fun helpRecordingBackgroundReturnRequiresNewUserRequest() = withHome {
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick()
        }
        waitUntil("Real help TTS did not hand off to microphone", 60_000) {
            field("gatewayVoiceRecorder") != null && field("activeGatewaySpeechInteraction") != null
        }
        val lockScreen = InstrumentationRegistry.getArguments().getString("lockHelpScreen") == "true"
        if (lockScreen) {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
        } else {
            instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        waitUntil("Android background did not pause help") { field("isActivityForeground") == false }
        main {
            assertNull(field("voiceHelpScreen"))
            assertNull(field("gatewayVoiceRecorder"))
            assertNull(field("activeGatewaySpeechInteraction"))
            assertNull(field("gatewaySpeechCall"))
            assertFalse(field("voiceRecognitionActive") as Boolean)
        }
        if (lockScreen) {
            val keyguard = instrumentation.targetContext.getSystemService(android.app.KeyguardManager::class.java)
            waitUntil("The real OS screen did not lock") { keyguard.isKeyguardLocked }
            report("HELP_LOCKED capture_released=true awaiting_host_unlock=true")
            waitUntil("Host did not unlock the actual device", 60_000) { !keyguard.isKeyguardLocked }
        }
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        waitUntil("Help host did not return") { field("isActivityForeground") == true }
        repeat(30) {
            main {
                assertNull("Old help must not reappear", field("voiceHelpScreen"))
                assertNull("Old help turn must not reopen recording", field("gatewayVoiceRecorder"))
                assertNull(field("activeGatewaySpeechInteraction"))
                assertEquals("HOME", field("nativeUiPage").toString())
            }
            SystemClock.sleep(100)
        }
        main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick() }
        waitUntil("Explicit help reopening did not regain actual microphone", 60_000) {
            field("voiceHelpScreen") != null && field("gatewayVoiceRecorder") != null && field("activeGatewaySpeechInteraction") != null
        }
        report("HELP_LIFECYCLE screen_lock=$lockScreen real_TTS_and_mic=true android_home_closes_help_and_capture=true return_does_not_reopen=true explicit_reopen_records=true; transcription_accuracy=NOT_TESTED")
    }

    @Test fun helpRecordingActivityRecreationRequiresExplicitReentry() =
        recordingActivityRecreationRequiresExplicitReentry(help=true)

    @Test fun serverVoiceRecordingActivityRecreationRequiresExplicitReentry() =
        recordingActivityRecreationRequiresExplicitReentry(help=false)

    private fun recordingActivityRecreationRequiresExplicitReentry(help:Boolean) = withHome {
        assumeTrue(BuildConfig.WALKSAFE_SERVER_STT_ENABLED)
        val buttonLabel=if(help) "도움말" else "음성 명령"
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>().single{it.isShown&&it.text==buttonLabel}.performClick()
        }
        waitUntil("Requested UI must reach actual microphone recording",60_000){
            field("gatewayVoiceRecorder")!=null&&field("activeGatewaySpeechInteraction")!=null
        }
        val old=activity
        main { old.recreate() }
        waitUntil("Actual Activity recreation did not produce a resumed replacement",30_000){
            val next=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull{it!==old}
            if(next!=null) activity=next
            next!=null&&old.isDestroyed
        }
        waitUntil("Actual account/education did not restore after recreation",30_000){call("nativeHomeFeatureContextAvailable")==true}
        repeat(50){
            main {
                assertNull(field("voiceHelpScreen"));assertNull(field("gatewayVoiceRecorder"))
                assertNull(field("activeGatewaySpeechInteraction"));assertNull(field("gatewaySpeechCall"))
                assertEquals(false,field("voiceRecognitionActive"))
                assertEquals("HOME",field("nativeUiPage").toString())
            }
            SystemClock.sleep(100)
        }
        main {views(activity.window.decorView).filterIsInstance<Button>().single{it.isShown&&it.text==buttonLabel}.performClick()}
        waitUntil("Explicit UI reentry did not open fresh microphone",60_000){
            (!help||field("voiceHelpScreen")!=null)&&field("gatewayVoiceRecorder")!=null&&field("activeGatewaySpeechInteraction")!=null
        }
        main {if(help) call("closeVoiceHelp",false) else {call("cancelVoiceCommandRecognition");call("returnToCurrentInteractionArea")}
            assertNull(field("gatewayVoiceRecorder"));assertNull(field("activeGatewaySpeechInteraction"))}
        report("${if(help) "HELP" else "VOICE"}_RECREATE old_destroyed=true actual_activity_recreated=true actual_account_restored=true automatic_reopen_absent_observation_ms=5000 explicit_reentry_records=true close_releases_capture=true speech_accuracy=NOT_TESTED")
    }

    @Test fun serverRecordingProcessDeathDoesNotLeaveAudioOrRestartInput() = withHome {
        val args=InstrumentationRegistry.getArguments()
        val phase=requireNotNull(args.getString("voiceCrashPhase"))
        val runId=requireNotNull(args.getString("voiceCrashRunId"))
        require(phase in setOf("prepare","read")&&runId.matches(Regex("[a-z0-9]{32}")))
        val marker=File(instrumentation.targetContext.cacheDir,"voice-crash-$runId.json")
        if(phase=="prepare") {
            assertFalse(marker.exists())
            main {views(activity.window.decorView).filterIsInstance<Button>().single{it.isShown&&it.text=="음성 명령"}.performClick()}
            waitUntil("Real microphone must start before abrupt process termination",45_000){
                field("gatewayVoiceRecorder")!=null&&field("activeGatewaySpeechInteraction")!=null
            }
            var audio:File?=null
            main {
                val recorder=requireNotNull(field("gatewayVoiceRecorder"))
                val active=recorder.javaClass.getDeclaredField("active").apply{isAccessible=true}.get(recorder)
                audio=requireNotNull(active).javaClass.getDeclaredField("file").apply{isAccessible=true}.get(active) as File
            }
            val recorded=requireNotNull(audio)
            assertTrue(recorded.name.startsWith("walksafe-command-")&&recorded.name.endsWith(".m4a"))
            waitUntil("Recorder must write beyond the media container header"){recorded.length()>256L}
            marker.writeText(org.json.JSONObject().put("pid",android.os.Process.myPid())
                .put("audio",recorded.name).put("bytes_before_kill",recorded.length()).toString())
            val cause=args.getString("voiceCrashCause")?:"self"
            require(cause in setOf("self","permission-revoke"))
            report("VOICE_CRASH_READY actual_recording=true pid=${android.os.Process.myPid()} local_only=true cause=$cause")
            if(cause=="permission-revoke") {
                // Host revokes RECORD_AUDIO through Android, then restores its original grant/flags.
                SystemClock.sleep(30_000)
                fail("Android did not terminate capture after host permission revocation")
            }
            // SIGKILL of this test's own app process skips Activity/recorder cleanup callbacks.
            android.os.Process.killProcess(android.os.Process.myPid())
            fail("Self termination unexpectedly returned")
        } else {
            val saved=org.json.JSONObject(marker.readText())
            assertTrue(saved.getInt("pid")!=android.os.Process.myPid())
            val name=saved.getString("audio")
            require(name.matches(Regex("walksafe-command-[A-Za-z0-9_-]+\\.m4a")))
            val retained=File(instrumentation.targetContext.cacheDir,name)
            report("VOICE_CRASH_RESTART different_pid=true orphan_audio_exists=${retained.exists()} previous_recorded_bytes=${saved.getLong("bytes_before_kill")}")
            assertFalse("Completed process must not leave a raw command recording after app restart",retained.exists())
            repeat(30){main{assertNull(field("gatewayVoiceRecorder"));assertNull(field("activeGatewaySpeechInteraction"))};SystemClock.sleep(100)}
            marker.delete()
            report("VOICE_CRASH_RECOVERY orphan_audio_removed=true automatic_recording_absent_observation_ms=3000 actual_home_ready=true")
        }
    }

    @Test fun serverCandidateCompletionOpensMicrophoneAndPageExitPreventsReopen() = withHome {
        assertTrue("This probe targets the shipped server STT configuration", BuildConfig.WALKSAFE_SERVER_STT_ENABLED)
        val state = kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState(
            "시험 목적지", listOf(kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult(
                "test-1", "시험역", kr.co.hanium.dreamup.walksafe.navigation.RoutePoint(37.5, 127.0),
                null, "시험로 12-3", null, 100)),
        )
        fun set(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(activity, value)
        fun prompt() {
            val page = field("nativeUiPage") as Enum<*>
            call("showNativeUiPage", page.javaClass.enumConstants!!.single { (it as Enum<*>).name == "DESTINATION_SEARCH" })
            set("destinationSearchQuery", state.query)
            set("destinationSearchVoiceState", state)
            assertTrue(call("speakHomeDestinationVoicePrompt", state, state.voicePrompt(), true) as Boolean)
        }
        main { call("cancelVoiceCommandRecognition"); prompt() }
        waitUntil("Real server capture did not follow candidate TTS completion", 65000) {
            field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true
        }
        main {
            assertEquals(setOf(1), state.completedCandidateNumbers())
            assertEquals("DESTINATION_SEARCH", field("nativeUiPage").toString())
            call("cancelVoiceCommandRecognition")
            prompt()
            call("cancelNativeVoiceCommandAndReturnHome")
            assertEquals("HOME", field("nativeUiPage").toString())
        }
        val until = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < until) {
            main { assertNull(field("gatewayVoiceRecorder")); assertFalse(field("voiceRecognitionActive") as Boolean) }
            SystemClock.sleep(100)
        }
        report("SERVER_CANDIDATE real_tts_done_before_capture=true real_recorder=true results_page_preserved=true explicit_exit_no_reopen_10s=true candidates=synthetic human_stt_accuracy=NOT_TESTED")
    }

    /** Fixture candidates only. Actual Android BACK/HOME, TTS completion and recorder ownership. */
    @Test fun candidateHardwareExitInvalidatesSpeechAndCapture() = withHome {
        assertTrue(BuildConfig.WALKSAFE_SERVER_STT_ENABLED)
        val mode = InstrumentationRegistry.getArguments().getString("candidateExitMode")
        require(mode in setOf("back_speaking", "back_listening", "home_speaking", "home_listening", "recreate_speaking", "recreate_listening"))
        val listening = mode!!.endsWith("listening")
        val state = kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState(
            "후보 이탈 시험", (1..if (listening) 1 else 3).map {
                kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult(
                    "exit-$it", "시험역 $it", kr.co.hanium.dreamup.walksafe.navigation.RoutePoint(37.5, 127.0),
                    null, "시험로 12-$it", null, 100)
            },
        )
        fun set(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(activity, value)
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            call("cancelVoiceCommandRecognition")
            val page = field("nativeUiPage") as Enum<*>
            call("showNativeUiPage", page.javaClass.enumConstants!!.single { (it as Enum<*>).name == "DESTINATION_SEARCH" })
            set("destinationSearchQuery", state.query)
            set("destinationSearchVoiceState", state)
            assertTrue(call("speakHomeDestinationVoicePrompt", state, state.voicePrompt(), true) as Boolean)
        }
        if (listening) {
            waitUntil("Candidate TTS did not open the real microphone", 65_000) {
                field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true
            }
            main { assertEquals(setOf(1), state.completedCandidateNumbers()) }
        } else {
            waitUntil("First actual candidate utterance did not finish", 45_000) {
                state.completedCandidateNumbers() == setOf(1)
            }
            main {
                assertTrue("The candidate page must still be speaking", field("voiceCommandPromptPending") as Boolean)
                assertNull(field("gatewayVoiceRecorder"))
            }
        }
        if (mode.startsWith("home_")) {
            instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("Android HOME did not pause the Activity") { field("isActivityForeground") == false }
        } else if (mode.startsWith("recreate_")) {
            val old = activity
            main { old.recreate() }
            waitUntil("Candidate Activity did not recreate", 30_000) {
                val next = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull { it !== old }
                if (next != null) activity = next
                next != null && old.isDestroyed
            }
            waitUntil("Real account and education did not restore", 30_000) { call("nativeHomeFeatureContextAvailable") == true }
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } else {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitUntil("Android BACK did not return to HOME") { field("nativeUiPage").toString() == "HOME" }
        }
        fun assertReleased() {
            assertNull(field("gatewayVoiceRecorder"))
            assertNull(field("activeGatewaySpeechInteraction"))
            assertNull(field("gatewaySpeechCall"))
            assertFalse(field("voiceRecognitionActive") as Boolean)
            assertFalse(field("voiceCommandPromptPending") as Boolean)
            assertNull(call("currentVoiceDestinationDialogState"))
            assertTrue("Cancelled candidate numbers must not remain authorized", state.completedCandidateNumbers().isEmpty())
            assertNull(state.onCommand(kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand.SelectCandidate(1)).selectedResult)
            assertFalse("Purposeful exit must not show a stale speech failure", (field("voiceCommandErrorPanel") as View).isShown)
            assertFalse(field("isRouteActive") as Boolean)
        }
        main { assertReleased() }
        if (mode.startsWith("home_")) {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitUntil("Activity did not return to foreground") { field("isActivityForeground") == true }
        }
        repeat(100) {
            main { assertReleased() }
            SystemClock.sleep(100)
        }
        var returnedHome = false
        main { returnedHome = field("nativeUiPage").toString() == "HOME" }
        if (mode.startsWith("home_") && !returnedHome) {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitUntil("Returned candidate screen did not exit on BACK") { field("nativeUiPage").toString() == "HOME" }
        }
        waitUntil("Explicit main voice action did not become available") { call("homeVoiceCommandAvailable") == true }
        main {
            assertNull(field("destinationSearchVoiceState"))
            views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick()
        }
        waitUntil("New explicit button did not open a fresh recorder", 30_000) {
            field("gatewayVoiceRecorder") != null && field("activeGatewaySpeechInteraction") != null
        }
        main { call("cancelNativeVoiceCommandAndReturnHome"); assertEquals("HOME", field("nativeUiPage").toString()) }
        report("CANDIDATE_HARDWARE_EXIT mode=$mode actual_tts_completion=true real_android_navigation=true old_dialog_invalidated=true no_reopen_10s=true explicit_new_capture=true synthetic_candidates=true human_accuracy_and_real_search=NOT_TESTED")
    }

    @Test fun candidateAudioFocusLossRequiresVisibleReplayBeforeNumberSelection() = withHome {
        assertTrue(BuildConfig.WALKSAFE_SERVER_STT_ENABLED)
        val externalTtsStop = InstrumentationRegistry.getArguments().getString("externalTtsStop") == "true"
        val state = kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState(
            "음성 중단 시험", (1..3).map {
                kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult(
                    "focus-$it", "시험역 $it", kr.co.hanium.dreamup.walksafe.navigation.RoutePoint(37.5, 127.0),
                    null, "시험로 12-$it", null, 100)
            },
        )
        val manager = instrumentation.targetContext.getSystemService(android.media.AudioManager::class.java)
        val competing = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ }, android.os.Handler(android.os.Looper.getMainLooper())).build()
        try {
            main {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                call("cancelVoiceCommandRecognition")
                val page = field("nativeUiPage") as Enum<*>
                call("showNativeUiPage", page.javaClass.enumConstants!!.single { (it as Enum<*>).name == "DESTINATION_SEARCH" })
                MainActivity::class.java.getDeclaredField("destinationSearchQuery").apply { isAccessible = true }.set(activity, state.query)
                MainActivity::class.java.getDeclaredField("destinationSearchVoiceState").apply { isAccessible = true }.set(activity, state)
                assertTrue(call("speakHomeDestinationVoicePrompt", state, state.voicePrompt(), true) as Boolean)
            }
            waitUntil("Native candidate TTS must have completed one candidate and still be speaking", 45_000) {
                val actuator = field("feedbackActuator") as kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
                val tts = actuator.javaClass.getDeclaredField("textToSpeech").apply { isAccessible = true }.get(actuator) as android.speech.tts.TextToSpeech
                state.completedCandidateNumbers() == setOf(1) && tts.isSpeaking
            }
            if (externalTtsStop) report("TTS_ENGINE_STOP_READY actual_second_candidate_speaking=true")
            else main { assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competing)) }
            waitUntil("Interrupted engine did not terminate the unfinished candidate prompt", if (externalTtsStop) 90_000 else 25_000) { field("voiceCommandPromptPending") == false }
            main {
                assertTrue(state.completedCandidateNumbers().isEmpty())
                assertNull(state.onCommand(kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand.SelectCandidate(1)).selectedResult)
                assertNull(field("gatewayVoiceRecorder"))
                assertFalse(field("voiceRecognitionActive") as Boolean)
                assertSame(state, call("currentVoiceDestinationDialogState"))
                val action = field("voiceCommandErrorAction") as Button
                val message = (field("voiceCommandErrorText") as android.widget.TextView).text.toString()
                report("CANDIDATE_FOCUS_ERROR action=${action.text} shown=${action.isShown} detail=$message")
                assertTrue("Failure recovery action must be visible", action.isShown && action.isEnabled)
                assertEquals("Interrupted candidate speech needs replay on this screen", "다시 듣기", action.text.toString())
            }
            main { manager.abandonAudioFocusRequest(competing) }
            repeat(30) {
                main { assertNull(field("gatewayVoiceRecorder")); assertFalse(field("voiceRecognitionActive") as Boolean) }
                SystemClock.sleep(100)
            }
            main { assertTrue((field("voiceCommandErrorAction") as Button).performClick()) }
            waitUntil("Explicit replay did not complete all candidates and open actual microphone", 90_000) {
                state.completedCandidateNumbers() == setOf(1, 2, 3) && field("gatewayVoiceRecorder") != null
            }
            main {
                assertEquals("DESTINATION_SEARCH", field("nativeUiPage").toString())
                assertEquals(state.results.first(), state.onCommand(kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand.SelectCandidate(1)).selectedResult)
                call("cancelNativeDestinationSearchAndReturnHome")
            }
            report("CANDIDATE_FOCUS_RECOVERY actual_native_focus_loss=${!externalTtsStop} external_tts_stop_requested=$externalTtsStop incomplete_number_rejected=true visible_replay=true no_auto_capture_3s=true same_candidates_replayed=true completed_number_authorized=true actual_mic=true route_not_started=true other_app_or_call=NOT_TESTED")
        } finally { main { manager.abandonAudioFocusRequest(competing) } }
    }

    @Test fun helpEngineDeathRequiresExplicitActionAndReconnectsBeforeListening() = withHome {
        val action = InstrumentationRegistry.getArguments().getString("helpEngineRecovery") ?: "retry"
        require(action in setOf("retry", "topic", "menu", "reenter"))
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.isShown && it.text == "도움말" }.performClick()
        }
        waitUntil("Actual help TTS did not start", 30_000) {
            val actuator = field("feedbackActuator") ?: return@waitUntil false
            val tts = actuator.javaClass.getDeclaredField("textToSpeech").apply { isAccessible = true }.get(actuator) as? android.speech.tts.TextToSpeech
            field("voiceHelpScreen") != null && field("voiceCommandPromptPending") == true && tts?.isSpeaking == true
        }
        report("HELP_ENGINE_STOP_READY action=$action actual_help_speaking=true")
        waitUntil("Actual engine interruption did not expose help recovery", 100_000) {
            (field("voiceHelpRecovery") as? Button)?.isShown == true && field("voiceCommandPromptPending") == false
        }
        repeat(30) {
            main { assertNull(field("gatewayVoiceRecorder")); assertFalse(field("voiceRecognitionActive") as Boolean) }
            SystemClock.sleep(100)
        }
        main {
            assertTrue((field("voiceHelpSummary") as android.widget.TextView).text.contains("중단"))
            when(action) {
                "retry" -> assertTrue((field("voiceHelpRecovery") as Button).performClick())
                "topic", "menu" -> views(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.isShown && it.text.startsWith(if(action == "topic") "1번" else "4번") }.performClick()
                else -> {
                    views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말 닫기" }.performClick()
                    views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick()
                }
            }
            assertNull("An explicit retry must finish TTS before capture", field("gatewayVoiceRecorder"))
        }
        waitUntil("Explicit help action did not reconnect TTS and open actual microphone", 65_000) {
            field("voiceHelpScreen") != null && field("gatewayVoiceRecorder") != null
        }
        main {
            assertEquals(false, field("voiceCommandPromptPending"))
            assertFalse((field("voiceHelpRecovery") as Button).isShown)
            val summary = (field("voiceHelpSummary") as android.widget.TextView).text.toString()
            report("HELP_RECOVERED_SUMMARY action=$action text=$summary")
            assertFalse("Recovered help must not still display the interruption error", summary.contains("중단"))
            assertFalse(field("isRouteActive") as Boolean)
            call("closeVoiceHelp", false)
        }
        report("HELP_ENGINE_RECOVERY action=$action no_auto_capture_3s=true explicit_action_reconnected=true actual_TTS_completion_before_mic=true route_not_started=true human_audibility=NOT_TESTED")
    }

    @Test fun largeFontHelpControlsRemainReachableAndUnclipped() = withHome {
        val expected = InstrumentationRegistry.getArguments().getString("expectedFontScale")!!.toFloat()
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(expected, activity.resources.configuration.fontScale, 0.01f)
            views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick()
        }
        waitUntil("Help screen not visible") { (field("voiceHelpScreen") as? View)?.isShown == true }
        val labels = mutableListOf<String>()
        main { labels += views(field("voiceHelpScreen") as View).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() } }
        assertEquals(5, labels.size)
        for (label in labels) {
            lateinit var button: Button
            main {
                button = views(field("voiceHelpScreen") as View).filterIsInstance<Button>().single { it.text.toString() == label }
                button.requestRectangleOnScreen(android.graphics.Rect(0, 0, button.width, button.height), true)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            main {
                val visible = android.graphics.Rect()
                assertTrue("Button must be reachable by scrolling: $label", button.getGlobalVisibleRect(visible))
                assertTrue("At least 48dp must be touchable: $label", visible.height() >= (48 * activity.resources.displayMetrics.density).toInt())
                val layout = requireNotNull(button.layout)
                assertTrue("Text exceeds button height: $label", layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                for (line in 0 until layout.lineCount) assertEquals("Ellipsized help label: $label", 0, layout.getEllipsisCount(line))
                val others = views(field("voiceHelpScreen") as View).filterIsInstance<Button>().filter { it !== button && it.isShown }
                for (other in others) {
                    val rect = android.graphics.Rect()
                    if (other.getGlobalVisibleRect(rect)) assertFalse("Overlapping help buttons", android.graphics.Rect.intersects(visible, rect))
                }
                assertTrue(button.isEnabled && button.isClickable)
                report("LARGE_FONT scale=$expected label=$label visible_height=${visible.height()} text_unclipped=true")
            }
        }
        screenshot("large-font-help-${expected}")
        main { call("closeVoiceHelp", false); assertNull(field("voiceHelpScreen")) }
        report("LARGE_FONT_HELP scale=$expected five_controls_reachable=true no_button_overlap=true; human_TalkBack_navigation=NOT_TESTED")
    }

    /** Exact transcripts isolate help dispatch from the separate real-microphone failures. */
    @Test fun helpExactTranscriptDispatchAndRejectionKeepScreenOwnership() = withHome {
        main { assertTrue(views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick()) }
        waitUntil("Help overlay absent") { (field("voiceHelpScreen") as? View)?.isShown == true }
        for ((phrase, title) in listOf("2번" to "길안내 사용법", "3번" to "문제 해결", "음성 명령 사용법" to "음성 명령 사용법", "4번" to "도움말 목록")) main {
            call("handleVoiceHelpInput", listOf(phrase))
            assertEquals(title, (field("voiceHelpSummary") as android.widget.TextView).text.toString())
            assertNotNull(field("voiceHelpScreen"))
            assertFalse(field("isRouteActive") as Boolean)
            report("HELP_EXACT phrase=$phrase expected_topic=true actual_Main=true input=synthetic_transcript")
        }
        val remembered = field("voiceHelpLastText")
        for (phrases in listOf(listOf("오늘 날씨"), listOf("도움말 닫기", "3번"))) main {
            call("handleVoiceHelpInput", phrases)
            assertNotNull(field("voiceHelpScreen"))
            assertEquals(remembered, field("voiceHelpLastText"))
            assertEquals("도움말 목록", (field("voiceHelpSummary") as android.widget.TextView).text.toString())
        }
        main {
            call("handleVoiceHelpInput", listOf("도움말 닫기"))
            assertNull(field("voiceHelpScreen"))
            assertEquals("HOME", field("nativeUiPage").toString())
            assertNull(field("gatewayVoiceRecorder"))
        }
        report("HELP_EXACT unknown_and_conflicting_rejected=true close_restores_HOME=true; ASR_accuracy=NOT_TESTED")
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

    /** Inject only the recorder failure notification; real capture/UI/TTS/retry are exercised.
     * The separate ServerRecorderSilencingDeviceTest exercises the real twenty-second cap.
     */
    @Test fun serverRecordingLimitShowsCauseAndRequiresManualRetry() = withHome {
        waitUntil("Home microphone not ready") { call("homeVoiceCommandAvailable") == true }
        main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick() }
        waitUntil("Actual server microphone did not start") { field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true }
        main {
            val recorder = field("gatewayVoiceRecorder")!!
            @Suppress("UNCHECKED_CAST")
            val notifyLimit = recorder.javaClass.getDeclaredField("onCaptureLimitExceeded").apply { isAccessible = true }.get(recorder) as () -> Unit
            notifyLimit()
            assertNull(field("gatewayVoiceRecorder"))
            assertNull(field("gatewayVoiceUploadFile"))
            assertNull(field("gatewaySpeechCall"))
            assertNull(field("activeGatewaySpeechInteraction"))
            assertFalse(field("voiceRecognitionActive") as Boolean)
            assertTrue((field("voiceCommandErrorPanel") as View).isShown)
            assertTrue((field("voiceCommandErrorText") as android.widget.TextView).text.contains("녹음 제한"))
            assertEquals("다시 시도", (field("voiceCommandErrorAction") as Button).text.toString())
            notifyLimit() // A late duplicate must not start another interaction.
        }
        waitUntil("Limit explanation never finished", 30000) { call("isHandsFreeVoiceOutputActive") != true }
        main {
            assertNull("Explanation alone must not reopen capture", field("gatewayVoiceRecorder"))
            (field("voiceCommandErrorAction") as Button).performClick()
        }
        waitUntil("Explicit retry did not reopen real capture", 20000) { field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true }
        main { call("cancelVoiceCommandRecognition") }
        report("SERVER_LIMIT_UI notification=injected capture=real no_upload=true cause_visible=true manual_retry=true duplicate_ignored=true")
    }

    /** Continuous host speaker input; real recorder timer, no injected limit callback. */
    @Test fun actualContinuousSpeechLimitRejectsPrefixAndAllowsExplicitRetry() = withHome {
        val help = InstrumentationRegistry.getArguments().getString("continuousSpeechHelp") == "true"
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.isShown && it.text == if (help) "도움말" else "음성 명령" }.performClick()
        }
        waitUntil("Actual microphone did not start", 45_000) { field("gatewayVoiceRecorder") != null }
        var startedAt = 0L
        var recordedFile: File? = null
        main {
            val recorder = field("gatewayVoiceRecorder")!!
            val active = recorder.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(recorder)!!
            startedAt = active.javaClass.getDeclaredField("startedAtElapsedRealtimeMs").apply { isAccessible = true }.getLong(active)
            recordedFile = active.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(active) as File
        }
        report("CONTINUOUS_SPEECH_READY help=$help")
        var elapsed = 0L
        waitUntil("Actual recording limit did not reject continuous input", 26_000) {
            assertNull("No prefix may be uploaded", field("gatewaySpeechCall"))
            assertNull("No prefix may become upload input", field("gatewayVoiceUploadFile"))
            assertFalse(field("isRouteActive") as Boolean)
            val done = field("gatewayVoiceRecorder") == null
            if (done) elapsed = SystemClock.elapsedRealtime() - startedAt
            done
        }
        main {
            assertTrue("Recording ended early; this is not a 20-second limit test: $elapsed", elapsed >= 19_000)
            assertTrue("Recording exceeded expected bounded timer window: $elapsed", elapsed <= 23_000)
            assertFalse(recordedFile!!.exists())
            assertNull(field("activeGatewaySpeechInteraction"))
            val message = field(if (help) "voiceHelpSummary" else "voiceCommandErrorText") as android.widget.TextView
            assertTrue("Actual limit cause missing: ${message.text}", message.text.contains("녹음 제한"))
        }
        report("CONTINUOUS_SPEECH_LIMIT help=$help elapsed_ms=$elapsed file_deleted=true prefix_upload_absent=true")
        waitUntil("Manual recovery control not available", 30_000) {
            val button = field(if (help) "voiceHelpRecovery" else "voiceCommandErrorAction") as Button
            button.isShown && button.isEnabled && call("isHandsFreeVoiceOutputActive") != true
        }
        repeat(30) { main { assertNull(field("gatewayVoiceRecorder")); assertNull(field("gatewaySpeechCall")) }; SystemClock.sleep(100) }
        main { (field(if (help) "voiceHelpRecovery" else "voiceCommandErrorAction") as Button).performClick() }
        waitUntil("Explicit retry must start actual microphone", 30_000) { field("gatewayVoiceRecorder") != null }
        main { call("cancelVoiceCommandRecognition"); if (help) call("closeVoiceHelp", false) }
        report("CONTINUOUS_SPEECH_PASS help=$help manual_retry=true no_callback_injection=true speech_accuracy=NOT_TESTED")
    }

    /** Injects acoustic rejection only; real help TTS, recorder and retry UI must recover. */
    @Test fun helpAcousticRejectionExposesRecoveryAndRetryReopensMicrophone() = withHome {
        main { call("openVoiceHelp") }
        waitUntil("Help introduction did not open real capture", 60000) {
            field("activeGatewaySpeechInteraction") != null && field("gatewayVoiceRecorder") != null
        }
        main {
            val active = field("activeGatewaySpeechInteraction")!!
            (field("gatewayVoiceRecorder") as kr.co.hanium.dreamup.walksafe.feedback.ForegroundAacRecorder).cancel()
            call("handleGatewaySpeechTranscript", active,
                kr.co.hanium.dreamup.walksafe.network.GatewaySpeechTranscript(
                    java.util.UUID.randomUUID().toString(), "", kr.co.hanium.dreamup.walksafe.network.GatewaySpeechAcoustic(
                        0.0, null, null, false), "test-rejection",
                ))
            assertNull(field("gatewayVoiceRecorder"))
            assertNull(field("activeGatewaySpeechInteraction"))
            assertTrue((field("voiceHelpRecovery") as Button).isShown)
            assertTrue((field("voiceHelpSummary") as android.widget.TextView).text.contains("확인하지 못했습니다"))
        }
        waitUntil("Failure explanation did not finish", 30000) { call("isHandsFreeVoiceOutputActive") != true }
        main {
            assertNull("No hidden listening after a rejected capture", field("gatewayVoiceRecorder"))
            assertTrue((field("voiceHelpRecovery") as Button).performClick())
        }
        waitUntil("Explicit help retry did not reopen actual capture", 60000) {
            field("activeGatewaySpeechInteraction") != null && field("gatewayVoiceRecorder") != null
        }
        main { call("cancelVoiceCommandRecognition") }
        report("HELP_RECOVERY acoustic_rejection=injected cause_visible=true retry_visible=true real_tts_and_recorder=true manual_retry_reopens=true")
    }

    /** Physical accessory change only; no route/error callback injection. Host prompts after READY. */
    @Test fun actualWiredInputChangeStopsRecordingAndRequiresExplicitRetry() = withHome {
        val direction = InstrumentationRegistry.getArguments().getString("wiredRouteDirection")
        require(direction in setOf("connect", "disconnect", "no_mic_connect"))
        val beforeType = if (direction != "disconnect") android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
            else android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
        val afterType = if (direction == "connect") android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
            else android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
        fun capture(): Pair<android.media.MediaRecorder, File> {
            val owner = checkNotNull(field("gatewayVoiceRecorder"))
            val active = checkNotNull(owner.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(owner))
            val recorder = active.javaClass.getDeclaredField("recorder").apply { isAccessible = true }.get(active) as android.media.MediaRecorder
            val file = active.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(active) as File
            return recorder to file
        }
        val audio = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        fun wiredOutputPresent() = audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        if (direction == "no_mic_connect") assertFalse("Accessory must begin disconnected", wiredOutputPresent())
        main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        waitUntil("Home microphone not ready") { call("homeVoiceCommandAvailable") == true }
        main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick() }
        waitUntil("Actual microphone did not start") { field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true }
        lateinit var recordedFile: File
        main {
            val (recorder, file) = capture()
            assertEquals("Unexpected physical starting microphone", beforeType, recorder.routedDevice?.type)
            recordedFile = file
            android.widget.Toast.makeText(activity,
                if (direction == "disconnect") "시험: 지금 이어폰을 뽑아 주세요" else "시험: 지금 이어폰을 꽂아 주세요",
                android.widget.Toast.LENGTH_LONG).show()
            report("WIRED_ROUTE_READY direction=$direction actual_input_type=${recorder.routedDevice?.type} change_accessory_now=true no_speech_limit_ms=7000")
        }
        if (direction == "no_mic_connect") {
            waitUntil("Wired output accessory was not observed while capture was active", 18_000) { wiredOutputPresent() }
            main {
                assertTrue(field("voiceRecognitionActive") as Boolean)
                assertNotNull(field("activeGatewaySpeechInteraction"))
                assertEquals(recordedFile, capture().second)
                assertEquals(android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC, capture().first.routedDevice?.type)
                assertFalse((field("voiceCommandErrorPanel") as View).isShown)
                call("cancelVoiceCommandRecognition")
                assertFalse(recordedFile.exists())
            }
            report("WIRED_OUTPUT_ONLY_RESULT physical_output_connected=true input_remained_builtin=true same_recording=true no_false_route_error=true explicit_cancel=true; input_microphone_change=NOT_TESTED")
            return@withHome
        }
        waitUntil("Physical route change did not expose the expected recovery within recording limit", 22_000) {
            field("gatewayVoiceRecorder") == null && (field("voiceCommandErrorPanel") as View).isShown
        }
        main {
            assertEquals(kr.co.hanium.dreamup.walksafe.voice.MICROPHONE_INPUT_ROUTE_CHANGED_GUIDANCE,
                (field("voiceCommandErrorText") as android.widget.TextView).text.toString())
            assertNull(field("activeGatewaySpeechInteraction"))
            assertNull(field("gatewaySpeechCall"))
            assertFalse(field("voiceRecognitionActive") as Boolean)
            assertFalse("Changed-input audio must be discarded", recordedFile.exists())
        }
        waitUntil("Cause speech never finished", 30_000) { call("isHandsFreeVoiceOutputActive") != true }
        repeat(20) { main { assertNull(field("gatewayVoiceRecorder")) }; SystemClock.sleep(100) }
        main { assertTrue((field("voiceCommandErrorAction") as Button).performClick()) }
        waitUntil("Explicit retry did not reopen microphone", 25_000) { field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true }
        main {
            assertEquals("Retry must use changed physical microphone", afterType, capture().first.routedDevice?.type)
            call("cancelVoiceCommandRecognition")
        }
        report("WIRED_ROUTE_RESULT direction=$direction physical_change=true original_audio_deleted=true no_passive_restart=true explicit_retry_type=$afterType")
    }

    @Test fun serverInputSilencingShowsCauseAndManualRetry() = withHome {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowRecorderAppOpTest") == "true")
        val privacy = InstrumentationRegistry.getArguments().getString("useGlobalMicrophonePrivacy") == "true"
        fun privacyState(): Boolean? {
            if (android.os.Build.VERSION.SDK_INT < 31) return null
            val manager = activity.getSystemService(android.hardware.SensorPrivacyManager::class.java)
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_SENSOR_PRIVACY")
            return try {
                manager.javaClass.getMethod("isSensorPrivacyEnabled", Int::class.javaPrimitiveType)
                    .invoke(manager, 1) as Boolean
            } catch (_: ReflectiveOperationException) { null }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
        if (privacy) {
            val observed = privacyState()
            // Some vendors hide the getter. Host may supply the state it read in Android Settings;
            // this is test cleanup metadata, never application readiness or permission evidence.
            val initial = observed ?: InstrumentationRegistry.getArguments()
                .getString("originalMicrophonePrivacyFromSettings")?.toBooleanStrictOrNull()
            report("MICROPHONE_PRIVACY original_state=$initial source=${if (observed != null) "platform" else "host_settings_ui"}")
            assumeTrue("Original microphone privacy must be readable and off before testing", initial == false)
        }
        val original = if (privacy) "allow" else requireNotNull(InstrumentationRegistry.getArguments().getString("originalRecordAudioUidMode"))
        require(original in setOf("foreground", "allow", "default"))
        fun mode(value: String) {
            val command = if (privacy) "cmd sensor_privacy ${if (value == "ignore") "enable" else "disable"} 0 microphone"
                else "cmd appops set --uid ${activity.packageName} RECORD_AUDIO $value"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                command,
            )).use { assertTrue(it.readBytes().toString(Charsets.UTF_8).isBlank()) }
            if (privacy) privacyState()?.let { assertEquals(value == "ignore", it) }
        }
        try {
            waitUntil("Home microphone not ready") { call("homeVoiceCommandAvailable") == true }
            main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick() }
            waitUntil("Actual server microphone did not start") { field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true }
            mode("ignore")
            waitUntil("Silenced recording did not expose recovery", 10000) {
                field("gatewayVoiceRecorder") == null && (field("voiceCommandErrorPanel") as View).isShown
            }
            main {
                assertFalse(field("voiceRecognitionActive") as Boolean)
                assertEquals(kr.co.hanium.dreamup.walksafe.voice.MICROPHONE_INPUT_SILENCED_GUIDANCE,
                    (field("voiceCommandErrorText") as android.widget.TextView).text.toString())
                assertEquals("다시 시도", (field("voiceCommandErrorAction") as Button).text.toString())
                if (privacy) assertTrue("Global microphone privacy recovery must mention its microphone-access setting",
                    (field("voiceCommandErrorText") as android.widget.TextView).text.contains("마이크 사용"))
            }
            screenshot("full-review-microphone-silenced")
            mode(original)
            waitUntil("Cause speech never finished", 30000) { call("isHandsFreeVoiceOutputActive") != true }
            main {
                assertNull("Restoring permission alone must not resume capture", field("gatewayVoiceRecorder"))
                (field("voiceCommandErrorAction") as Button).performClick()
            }
            waitUntil("Explicit retry did not reopen real capture", 20000) { field("gatewayVoiceRecorder") != null && field("voiceRecognitionActive") == true }
            main { call("cancelVoiceCommandRecognition") }
            report("SERVER_SILENCED_UI native_appop=${!privacy} global_microphone_privacy=$privacy cause_visible=true manual_retry=true no_passive_restart=true")
        } finally { mode(original) }
    }

    @Test fun guidancePreviewShieldRequiresContinuousThreeSecondHold() = withHome {
        main { call("openGuidancePreview") }
        waitUntil("Preview did not open") { field("guidancePreviewIndex") != null && field("nativeUiPage").toString() == "GUIDANCE" }
        main {
            val buttons = listOf("guidanceVoiceButton", "nativeGuidancePauseButton", "nativeGuidanceCancelButton", "guidanceTouchGuardButton")
                .map { field(it) as Button }
            assertTrue(buttons.all { it.isShown && it.isEnabled })
            InstrumentationRegistry.getArguments().getString("expectedFontScale")?.let { scale ->
                assertEquals(scale.toFloat(), activity.resources.configuration.fontScale, 0.01f)
                val rects = buttons.map { button ->
                    val rect = android.graphics.Rect()
                    assertTrue("Guidance control must be visible", button.getGlobalVisibleRect(rect))
                    assertTrue(rect.height() >= (48 * activity.resources.displayMetrics.density).toInt())
                    val layout = requireNotNull(button.layout)
                    assertTrue("Guidance label clipped: ${button.text}", layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                    rect
                }
                for (i in rects.indices) for (j in i + 1 until rects.size)
                    assertFalse("Guidance controls overlap", android.graphics.Rect.intersects(rects[i], rects[j]))
                report("LARGE_FONT_GUIDANCE scale=$scale four_controls_visible=true labels_unclipped=true real_walk=false")
            }
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

    /** Real preview overlay and injected Android input; no ACTIVE walking or TalkBack claim. */
    @Test fun touchGuardInterruptedHoldRequiresANewContinuousGesture() = withHome {
        val mode = requireNotNull(InstrumentationRegistry.getArguments().getString("touchGuardInterruption"))
        require(mode in setOf("cancel", "outside", "multitouch", "background", "key"))
        main { call("openGuidancePreview") }
        waitUntil("Preview unavailable") { field("guidancePreviewIndex") != null }
        main { assertTrue((field("guidanceTouchGuardButton") as Button).performClick()) }
        waitUntil("Touch guard unavailable") { (field("guidanceTouchGuardScreen") as? View)?.hasWindowFocus() == true }
        lateinit var release: Button
        val position = IntArray(2)
        main {
            val screen = field("guidanceTouchGuardScreen") as View
            assertEquals(android.graphics.Color.BLACK, (screen.background as android.graphics.drawable.ColorDrawable).color)
            release = views(screen).filterIsInstance<Button>().single()
            release.getLocationOnScreen(position)
        }
        var downTime = SystemClock.uptimeMillis()
        fun pointer(action: Int, outside: Boolean = false, two: Boolean = false) {
            val count = if (two) 2 else 1
            val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                x = if (outside) position[0] - 8f else position[0] + release.width / 2f + i * 20
                y = position[1] + release.height / 2f
                pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        if (mode == "key") {
            repeat(12) {
                var focused = false
                main { focused = release.hasFocus() }
                if (!focused) instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            }
            main { assertTrue("Actual keyboard navigation must focus the release control", release.hasFocus()) }
            instrumentation.sendKeySync(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        } else pointer(MotionEvent.ACTION_DOWN)
        waitUntil("Hold must really start before interruption") { release.text == "계속 눌러 주세요" }
        SystemClock.sleep(900)
        when (mode) {
            "cancel" -> pointer(MotionEvent.ACTION_CANCEL)
            "outside" -> { pointer(MotionEvent.ACTION_MOVE, outside = true); pointer(MotionEvent.ACTION_MOVE) }
            "multitouch" -> pointer(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), two = true)
            "key" -> instrumentation.sendKeySync(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            "background" -> {
                instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitUntil("Actual HOME transition absent") { field("isActivityForeground") == false }
            }
        }
        SystemClock.sleep(2500)
        if (mode == "background") {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitUntil("Activity did not regain focus") { activity.hasWindowFocus() && field("isActivityForeground") == true }
        }
        main {
            assertNotNull("Interrupted hold must not unlock after the old deadline", field("guidanceTouchGuardScreen"))
            assertEquals("3초간 눌러 해제", release.text.toString())
            assertFalse(field("isRouteActive") as Boolean)
        }
        pointer(MotionEvent.ACTION_CANCEL)
        downTime = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(2700)
        main { assertNotNull("New hold must not reuse time from the old gesture", field("guidanceTouchGuardScreen")) }
        waitUntil("A new continuous hold must unlock", 2500) { field("guidanceTouchGuardScreen") == null }
        pointer(MotionEvent.ACTION_UP)
        main {
            assertTrue((field("guidanceVoiceButton") as Button).isShown)
            (field("nativeGuidanceCancelButton") as Button).performClick()
        }
        report("TOUCH_GUARD interruption=$mode actual_android_input=true old_deadline_rejected=true fresh_hold_required=true black_background=true preview_only=true TalkBack_or_ACTIVE_walk=NOT_TESTED")
    }

    /** External synthetic speaker -> real microphone -> current server -> real search/cancel UI. */
    @Test fun acousticNearbySearchAndCandidateCancellationUseRealServer() = withHome {
        main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("acousticExpectedQuery"))
        require(expected in setOf("가까운 편의점", "근처 약국", "서울역"))
        var phase = "command"
        try {
            main {
                assertFalse(field("isRouteActive") as Boolean)
                views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick()
            }
            waitUntil("Native command microphone unavailable", 45_000) {
                (field("gatewayVoiceRecorder") as? kr.co.hanium.dreamup.walksafe.feedback.ForegroundAacRecorder)?.isRecording == true
            }
            report("ACOUSTIC_READY phase=command")
            waitUntil("Spoken command did not produce the expected destination query", 45_000) {
                field("destinationSearchQuery") == expected
            }
            phase = "search"
            waitUntil("Real search did not produce candidates", 50_000) {
                field("destinationSearchInFlight") == false && (field("destinationSearchResults") as List<*>).isNotEmpty()
            }
            report("ACOUSTIC_SEARCH expected_query_matched=true live_candidates=true nearby_fixture=${expected != "서울역"} real_walk=false")
            phase = "candidate_speech"
            waitUntil("Candidate narration did not reach follow-up recording", 100_000) {
                field("destinationSearchVoiceState") != null &&
                    (field("gatewayVoiceRecorder") as? kr.co.hanium.dreamup.walksafe.feedback.ForegroundAacRecorder)?.isRecording == true
            }
            phase = "cancel"
            report("ACOUSTIC_READY phase=cancel")
            waitUntil("Spoken cancel did not clear candidate selection", 45_000) {
                field("destinationSearchVoiceState") == null && field("destinationSearchInFlight") == false
            }
            main {
                assertFalse(field("isRouteActive") as Boolean)
                assertNull(field("pendingUiDestination"))
            }
            report("ACOUSTIC_SEARCH_CANCEL actual_microphone_and_server=true selected_destination=false human_accuracy=NOT_TESTED")
        } finally {
            main {
                report("ACOUSTIC_FINAL phase=$phase expected_query_matched=${field("destinationSearchQuery") == expected} candidate_count=${(field("destinationSearchResults") as List<*>).size} route_active=${field("isRouteActive")}")
                call("cancelNativeDestinationSearchAndReturnHome")
            }
        }
    }

    /** Observed server score reproduced at Main's boundary; no real ASR accuracy claim. */
    @Test fun recognizedButUntrustedCommandExplainsConfidenceWithoutExecuting() = withHome {
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val page = field("nativeUiPage") as Enum<*>
            call("showNativeUiPage", page.javaClass.enumConstants!!.first { (it as Enum<*>).name == "VOICE_COMMAND" })
        }
        for (phrase in listOf("취소", "설정 열어줘")) {
            main {
                call("cancelVoiceCommandRecognition")
                call("cancelCommandSpeechResponse")
                call("handleVoiceCommandPhrases", listOf(phrase), floatArrayOf(0.48170787f), null, false, "gateway_stt", null, false)
                val message = (field("gatewayVoiceStatusText") as android.widget.TextView).text.toString()
                report("UNTRUSTED_COMMAND explanation=$message page=${field("nativeUiPage")} synthetic_score=0.48170787")
                assertTrue("Supported but low-confidence command needs its actual rejection reason", message.contains("확실히 확인하지 못해"))
                assertEquals("VOICE_COMMAND", field("nativeUiPage").toString())
                assertFalse(field("isRouteActive") as Boolean)
            }
        }
        for ((phrase, expected) in listOf("목적지 도움말 그리고 서울역 설정해" to "한 번에 한 가지씩", "오늘 날씨가 좋네요" to "못 알아들었습니다")) {
            main {
                call("cancelCommandSpeechResponse")
                call("handleVoiceCommandPhrases", listOf(phrase), floatArrayOf(0.9f), null, false, "gateway_stt", null, false)
                assertTrue((field("gatewayVoiceStatusText") as android.widget.TextView).text.contains(expected))
                assertEquals("VOICE_COMMAND", field("nativeUiPage").toString())
            }
        }
        main {
            call("cancelCommandSpeechResponse")
            call("handleVoiceCommandPhrases", listOf("설정 열어줘"), floatArrayOf(0.55f), null, false, "gateway_stt", null, false)
            assertEquals("SETTINGS", field("nativeUiPage").toString())
        }
        report("UNTRUSTED_COMMAND unchanged_threshold=0.55 denied_low_score=true supported_threshold_executes=true real_Main=true ASR_result=synthetic")
    }

    /** Real UI/TTS, rapid topic replacements before completion; no transcript or readiness injection. */
    @Test fun rapidHelpTopicExitCannotReopenInput() = withHome {
        val mode = requireNotNull(InstrumentationRegistry.getArguments().getString("helpTopicExit"))
        require(mode in setOf("close", "back", "home", "recreate"))
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "도움말" }.performClick()
        }
        waitUntil("Help UI absent") { (field("voiceHelpScreen") as? View)?.isShown == true }
        main {
            for (number in listOf(1, 2, 3, 4)) {
                views(field("voiceHelpScreen") as View).filterIsInstance<Button>()
                    .single { it.text.startsWith("${number}번") }.performClick()
                assertNull("Replacement prompt opened microphone before completion", field("gatewayVoiceRecorder"))
            }
            assertEquals("도움말 목록", (field("voiceHelpSummary") as android.widget.TextView).text.toString())
            assertTrue("Must interrupt a pending real prompt", field("voiceCommandPromptPending") as Boolean)
            if (mode == "close") views(field("voiceHelpScreen") as View).filterIsInstance<Button>()
                .single { it.text == "도움말 닫기" }.performClick()
        }
        if (mode == "back") instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        if (mode == "home") {
            instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("Help did not leave foreground") { field("isActivityForeground") == false }
        }
        if (mode == "recreate") recreateRealMainForExitTest()
        waitUntil("Retired help prompt still owns capture") {
            field("voiceCommandPromptPending") == false && field("gatewayVoiceRecorder") == null &&
                field("voiceRecognitionActive") == false
        }
        if (mode == "home") {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitUntil("Help did not return to foreground") { field("isActivityForeground") == true }
        }
        repeat(50) {
            main {
                assertNull(field("gatewayVoiceRecorder")); assertNull(field("gatewaySpeechCall"))
                assertNull(field("activeGatewaySpeechInteraction")); assertFalse(field("voiceRecognitionActive") as Boolean)
                assertFalse(field("isRouteActive") as Boolean)
            }
            SystemClock.sleep(100)
        }
        main { call("closeVoiceHelp", false); call("returnToCurrentInteractionArea") }
        waitUntil("Main explicit voice did not recover") { call("homeVoiceCommandAvailable") == true }
        main { views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "음성 명령" }.performClick() }
        waitUntil("Explicit action could not start a new microphone") { field("gatewayVoiceRecorder") != null }
        main { call("cancelNativeVoiceCommandAndReturnHome") }
        report("RAPID_HELP_EXIT mode=$mode topic_replacements=4 early_microphone=false no_late_capture_5s=true explicit_fresh_capture=true human_audibility=NOT_TESTED")
    }

    private fun recreateRealMainForExitTest() {
        val old = activity
        main { old.recreate() }
        waitUntil("Real MainActivity did not recreate", 30_000) {
            val next = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull { it !== old }
            if (next != null) activity = next
            next != null && old.isDestroyed
        }
        waitUntil("Account/onboarding not restored after recreation", 30_000) { call("nativeHomeFeatureContextAvailable") == true }
        main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    /** Cancel the genuine UI search at its initial location/request phase; don't fabricate a reply. */
    @Test fun destinationSearchExitCannotApplyRetiredWork() = withHome {
        val mode = requireNotNull(InstrumentationRegistry.getArguments().getString("destinationExit"))
        val awaitSearchRequest = InstrumentationRegistry.getArguments().getString("awaitDestinationRequest") == "true"
        require(mode in setOf("back", "home", "recreate"))
        var httpStarted = false
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "길라잡이" }.performClick()
            (field("destinationQueryInput") as EditText).setText("서울역")
            assertTrue((field("destinationSearchButton") as Button).performClick())
            assertEquals("서울역", field("destinationSearchQuery"))
            httpStarted = field("destinationSearchInFlight") == true
        }
        if (awaitSearchRequest) {
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            var observed = false
            while (!observed && SystemClock.elapsedRealtime() < deadline) {
                main { observed = field("destinationSearchInFlight") == true }
                if (!observed) SystemClock.sleep(5)
            }
            assertTrue("No live search request observed; cannot claim request cancellation", observed)
        }
        if (mode == "back") instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        if (mode == "home") {
            instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("Search did not pause") { field("isActivityForeground") == false }
        }
        if (mode == "recreate") recreateRealMainForExitTest()
        waitUntil("Search work survived exit") { field("destinationSearchInFlight") == false }
        if (mode == "home") {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitUntil("Search did not return") { field("isActivityForeground") == true }
        }
        repeat(50) {
            main {
                assertFalse(field("destinationSearchInFlight") as Boolean)
                assertTrue((field("destinationSearchResults") as List<*>).isEmpty())
                assertNull(field("pendingUiDestination")); assertNull(field("gatewayVoiceRecorder"))
                assertFalse(field("isRouteActive") as Boolean)
            }
            SystemClock.sleep(100)
        }
        main { call("cancelNativeDestinationSearchAndReturnHome") }
        waitUntil("Search exit did not release HOME") { field("nativeUiPage").toString() == "HOME" && call("homeVoiceCommandAvailable") == true }
        report("SEARCH_INITIAL_EXIT mode=$mode search_in_flight_at_click=$httpStarted search_request_phase_observed=$awaitSearchRequest no_late_results_5s=true no_route_or_capture=true; server_request_execution=NOT_MEASURED delayed_server_reply=NOT_INJECTED location_gate_preserved=true")
    }

    @Test fun preparationRecreationCannotRestoreStartAuthorization() = withHome {
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "길라잡이" }.performClick()
            (field("destinationQueryInput") as EditText).setText("서울역")
            (field("destinationSearchButton") as Button).performClick()
        }
        waitUntil("Real search did not provide candidates", 45_000) {
            field("destinationSearchInFlight") == false && views(field("destinationSearchResultsContainer") as View)
                .filterIsInstance<Button>().any { it.isShown && it.isEnabled }
        }
        main {
            views(field("destinationSearchResultsContainer") as View).filterIsInstance<Button>()
                .first { it.isShown && it.isEnabled }.performClick()
            assertTrue(call("isInWalkInteractionArea") == true)
            assertFalse(field("isRouteActive") as Boolean)
        }
        recreateRealMainForExitTest()
        repeat(50) {
            main {
                assertFalse(field("isRouteActive") as Boolean)
                assertNull(field("nativeGuidanceConfirmationAction")); assertNull(field("nativeGuidanceConfirmationScreen"))
                assertNull(field("gatewayVoiceRecorder"))
            }
            SystemClock.sleep(100)
        }
        main {
            report("PREPARATION_RECREATE restored_page=${field("nativeUiPage")} destination_retained=${field("pendingUiDestination") != null} walk_area=${call("isInWalkInteractionArea")} no_auto_start_5s=true no_old_confirmation=true sensor_pass_injected=false")
            call("cancelNativeGuidanceAndReturnHome")
        }
        waitUntil("Recreated preparation cannot be cancelled") { field("nativeUiPage").toString() == "HOME" && field("pendingUiDestination") == null && call("isInWalkInteractionArea") == false }
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
                { statusSpoken = true }, { statusFailed = true }, false,
                listOf("현재 위치와 휴대전화 상태를 확인하고 있습니다."), { _: Int -> }, { true })
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

    /** Real server destination and Android lifecycle; no readiness or walking state injection. */
    @Test fun preparationBackgroundReturnAndBackKeepStartExplicit() = withHome {
        main {
            views(activity.window.decorView).filterIsInstance<Button>().single { it.isShown && it.text == "길라잡이" }.performClick()
            (field("destinationQueryInput") as EditText).setText("서울역")
            (field("destinationSearchButton") as Button).performClick()
        }
        fun candidates() = views(field("destinationSearchResultsContainer") as View).filterIsInstance<Button>()
        waitUntil("Real destination search unavailable", 45_000) {
            field("destinationSearchInFlight") == false && candidates().any { it.isShown && it.isEnabled }
        }
        main {
            candidates().first { it.isShown && it.isEnabled }.performClick()
            assertEquals("GUIDANCE", field("nativeUiPage").toString())
            assertTrue(call("isInWalkInteractionArea") == true)
            assertFalse(field("isRouteActive") as Boolean)
        }
        val destination = field("pendingUiDestination")
        assertNotNull(destination)
        try {
            instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("Android HOME did not pause the real activity") { field("isActivityForeground") == false }
            main {
                assertNull("Background must cancel pending sensor work", field("nativePhoneMountingCheckRequest"))
                assertNull("Background must cancel a start authorization", field("nativeGuidanceConfirmationAction"))
                assertNull(field("nativeGuidanceConfirmationScreen"))
                assertFalse(field("isRouteActive") as Boolean)
            }
            SystemClock.sleep(2_000)
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitUntil("Original activity did not return") { field("isActivityForeground") == true }
            waitUntil("Return has no explicit retry", 20_000) {
                (field("nativeGuidanceRetryButton") as Button).let { it.isShown && it.isEnabled }
            }
            main {
                assertEquals(destination, field("pendingUiDestination"))
                assertFalse("Foreground return must not start a route", field("isRouteActive") as Boolean)
                assertNull(field("nativeGuidanceConfirmationAction"))
                assertTrue(call("isInWalkInteractionArea") == true)
                assertFalse(call("homeVoiceCommandAvailable") == true)
                assertTrue((field("nativeGuidanceRetryButton") as Button).performClick())
                report("PREPARATION_RETRY depthPending=${field("storedMetricDepthRefreshPending")} sensorPending=${field("nativePhoneMountingCheckRequest") != null} page=${field("nativeUiPage")} status=${(field("nativeGuidanceStatusText") as android.widget.TextView).text}")
            }
            waitUntil("Explicit retry did not reach a new measured check", 60_000) {
                field("nativePhoneMountingCheckRequest") != null
            }
            main {
                assertFalse(field("isRouteActive") as Boolean)
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitUntil("Back did not end preparation and release the interaction area") {
                field("nativeUiPage").toString() == "HOME" && field("pendingUiDestination") == null &&
                    field("nativePhoneMountingCheckRequest") == null && call("isInWalkInteractionArea") == false
            }
            main { assertFalse(field("isRouteActive") as Boolean) }
            report("PREPARATION_LIFECYCLE real_search=true android_home=true pending_check_cancelled=true return_auto_start=false destination_retained=true explicit_retry=true back_clears=true; ACTIVE_walk=NOT_TESTED")
        } finally {
            main { call("cancelNativeGuidanceAndReturnHome") }
        }
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
        main { call("openReportPanel", false) }
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
