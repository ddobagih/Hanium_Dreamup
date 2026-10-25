package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOURCE draft: actual Main view factory, central announcement admission and pane property setter.
 * Purpose views and typed onboarding/device/education are controlled setup; onCreate/renderMainUi,
 * a bound spoken service, reading completion, physical interruption and title speech are NOT_RUN.
 * No actor, camera, microphone, server walk lease, preference or key owner is created.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityAccessibilitySemanticConsumerAndroidTest {
    @Test
    fun actualCentralConsumerWritesTheSemanticLeafAndRejectsDuplicateAndRetiredOwners() = withFixture { f ->
        onMain(f.instrumentation) {
            val owner = f.main.captureCommandSpeechResponseOwner()
            assertTrue("The actual owner must be current before testing retirement", owner.isCurrent(f.main))
            val before = f.lifecycle.snapshot()
            var assignments = 0
            var requestCallbacks = 0 // Presentation request only, not a TalkBack reading ACK.
            f.leaf.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { assignments++ }
                override fun afterTextChanged(text: Editable?) = Unit
            })
            assertEquals(View.VISIBLE, f.leaf.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, f.leaf.importantForAccessibility)
            assertFalse("Property consumption is not an attached spoken-service observation", f.leaf.isAttachedToWindow)
            val message = "준비 상태를 확인하세요."
            assertTrue(f.announce(message, MainActivity.TalkBackAnnouncementPriority.INTERACTION, owner) { requestCallbacks++ })
            assertEquals(message, f.leaf.text.toString())
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, f.leaf.accessibilityLiveRegion)
            assertEquals("Hidden legacy status is not the new output target", LEGACY_STATUS, f.status.text.toString())
            assertEquals(1, assignments)
            assertEquals(1, requestCallbacks)
            val issuedAt = field(f.main, "lastInteractionAnnouncementMs")
            assertFalse(f.announce(message, MainActivity.TalkBackAnnouncementPriority.INTERACTION, owner) { requestCallbacks++ })
            assertEquals(issuedAt, field(f.main, "lastInteractionAnnouncementMs"))
            assertEquals(1, assignments)
            assertEquals(1, requestCallbacks)

            assertTrue(f.announce("멈추세요.", MainActivity.TalkBackAnnouncementPriority.RISK, owner) { requestCallbacks++ })
            assertEquals("멈추세요.", f.leaf.text.toString())
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE, f.leaf.accessibilityLiveRegion)
            assertEquals(2, assignments)
            assertEquals(2, requestCallbacks)
            assertTrue(SystemClock.elapsedRealtime() < field(f.main, "riskAnnouncementHoldUntilMs") as Long)
            assertTrue(f.announce("안내가 끝났습니다.", MainActivity.TalkBackAnnouncementPriority.INTERACTION, owner) { requestCallbacks++ })
            val deferred = field(f.main, "pendingTalkBackInteraction") as Runnable
            assertEquals("Risk holds the interaction without presenting it", 2, assignments)
            f.invoke("cancelCommandSpeechResponse", Void.TYPE) // Actual generation retirement.
            assertFalse(owner.isCurrent(f.main))
            val replacement = f.main.captureCommandSpeechResponseOwner()
            assertTrue(replacement.isCurrent(f.main))
            f.status.removeCallbacks(deferred)
            deferred.run() // Actual scheduled body; its original owner closure must remain stale.
            assertNull(field(f.main, "pendingTalkBackInteraction"))
            assertEquals(2, assignments)
            assertEquals(2, requestCallbacks)
            assertFalse(f.announce("원래 요청의 안내", MainActivity.TalkBackAnnouncementPriority.INTERACTION, owner) { requestCallbacks++ })
            assertEquals(2, assignments)
            assertEquals(2, requestCallbacks)
            assertTrue(replacement.isCurrent(f.main))
            assertEquals(before, f.lifecycle.snapshot())
        }
    }

    @Test
    fun actualPaneSetterUsesTheFinalVisibleTargetAndRetiresTheInactivePane() = withFixture { f ->
        onMain(f.instrumentation) {
            assertTrue("Actual typed HOME input precedes pane property checks", f.homeAvailable())
            val before = f.lifecycle.snapshot()
            f.pane(homeAvailable = f.homeAvailable(), settingsVisible = false)
            assertEquals("홈", ViewCompat.getAccessibilityPaneTitle(f.root)?.toString())
            assertNull(ViewCompat.getAccessibilityPaneTitle(f.camera))
            f.pane(homeAvailable = f.homeAvailable(), settingsVisible = false)
            assertEquals("홈", ViewCompat.getAccessibilityPaneTitle(f.root)?.toString())

            f.page("SETTINGS") // Existing private page setter, not a capability/admission override.
            f.pane(homeAvailable = f.homeAvailable(), settingsVisible = true)
            assertEquals("설정", ViewCompat.getAccessibilityPaneTitle(f.root)?.toString())
            f.invoke("setCameraScreenVisible", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), true)
            f.root.visibility = View.GONE
            f.camera.visibility = View.VISIBLE
            f.pane(homeAvailable = f.homeAvailable(), settingsVisible = false)
            assertEquals("카메라 화면", ViewCompat.getAccessibilityPaneTitle(f.camera)?.toString())
            assertNull(ViewCompat.getAccessibilityPaneTitle(f.root))

            f.invoke("setCameraScreenVisible", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), false)
            f.camera.visibility = View.GONE
            f.root.visibility = View.VISIBLE
            f.page("HOME")
            f.pane(homeAvailable = f.homeAvailable(), settingsVisible = false)
            assertEquals("홈", ViewCompat.getAccessibilityPaneTitle(f.root)?.toString())
            assertNull(ViewCompat.getAccessibilityPaneTitle(f.camera))
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, f.root.accessibilityLiveRegion)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, f.camera.accessibilityLiveRegion)
            assertEquals(before, f.lifecycle.snapshot())
            // Public onboarding transitions retain FP004 training; purpose views model a hidden
            // education parent after device prerequisites retire, without running the whole renderer.
            val evidence = listOf(
                FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)))
            val currentOnboarding = field(f.main, "firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot
            assertEquals(FirstRunOnboardingStage.COMPLETE, currentOnboarding.stage)
            val verifier = FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage }
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(currentOnboarding.epoch, evidence, verifier)
            assertTrue(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
                restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            assertTrue(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            assertTrue(device.accepted)
            assertEquals(FirstRunOnboardingStage.FP004_TRAINING, device.current.stage)
            assertTrue(device.current.revision < currentOnboarding.revision)
            setField(f.main, "firstRunOnboardingSnapshot", device.current)
            assertSame("A stale same-epoch restore must retain the current completed owner", currentOnboarding,
                field(f.main, "firstRunOnboardingSnapshot"))
            assertTrue("The retained completed snapshot still admits HOME", f.homeAvailable())
            // A new logical onboarding lifetime supplies the intended incomplete state without
            // weakening the controller's protection against stale replay of completed progress.
            val fresh = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(currentOnboarding.epoch + 1L, evidence, verifier)
            assertTrue(fresh.fullyRestored)
            val freshJit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
                fresh.snapshot, fresh.snapshot.epoch, fresh.snapshot.revision)
            assertTrue(freshJit.accepted)
            val freshDevice = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
                freshJit.current, freshJit.current.epoch, freshJit.current.revision)
            assertTrue(freshDevice.accepted)
            assertEquals(FirstRunOnboardingStage.FP004_TRAINING, freshDevice.current.stage)
            setField(f.main, "firstRunOnboardingSnapshot", freshDevice.current)
            val appliedOnboarding = field(f.main, "firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot
            assertSame("The new incomplete snapshot must be held by the actual controller", freshDevice.current, appliedOnboarding)
            assertEquals(FirstRunOnboardingStage.FP004_TRAINING, appliedOnboarding.stage)
            assertFalse(appliedOnboarding.isComplete)
            val deviceNotRun = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(),
                ACTOR, GatewaySessionProcessCoordinator.snapshot().generation)
            assertEquals(PostLoginDeviceCheckState.NOT_RUN, deviceNotRun.state)
            setField(f.main, "postLoginDeviceCheckSnapshot", deviceNotRun)
            val appliedDevice = field(f.main, "postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot
            assertSame(deviceNotRun, appliedDevice)
            assertEquals(PostLoginDeviceCheckState.NOT_RUN, appliedDevice.state)
            val homeAvailable = f.homeAvailable()
            assertFalse("The actual FP004/device input must not admit HOME", homeAvailable)
            val firstHeading = TextView(f.context).apply { text = "기기 점검"; visibility = View.GONE }
            val educationHeading = TextView(f.context).apply { text = "사전 연습"; visibility = View.VISIBLE }
            val education = LinearLayout(f.context).apply { visibility = View.GONE; addView(educationHeading) }
            f.root.addView(firstHeading)
            f.root.addView(education)
            setField(f.main, "firstRunOnboardingStatusText", firstHeading)
            setField(f.main, "priorityUserOnboardingStatusText", educationHeading)
            setField(f.main, "priorityUserOnboardingControls", education)
            assertEquals(View.GONE, education.visibility)
            assertEquals(View.VISIBLE, educationHeading.visibility)
            f.pane(homeAvailable = homeAvailable, settingsVisible = false)
            assertEquals("첫 실행 등록", ViewCompat.getAccessibilityPaneTitle(f.root)?.toString())
            assertNotEquals(educationHeading.text.toString(), ViewCompat.getAccessibilityPaneTitle(f.root)?.toString())
            assertEquals(before, f.lifecycle.snapshot())
            // These purpose-view properties do not execute showNativeUiPage/renderMainUi or open a camera.
        }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val context: Context = instrumentation.targetContext
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val status = TextView(context).apply { text = LEGACY_STATUS }
        val root = LinearLayout(context)
        val camera = LinearLayout(context).apply { visibility = View.GONE }
        lateinit var leaf: TextView
            private set
        private var gatewayGeneration: Long? = null

        init {
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main semantic output properties",
                null, null, null) as MainActivity
        }

        fun prepare() {
            check(Looper.myLooper() == Looper.getMainLooper())
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertNull("Do not replace a process session owned by another fixture", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_semantic_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(requireNotNull(gatewayGeneration)))
            setField(main, "reporterUserId", session.actorId)
            setField(main, "priorityUserOnboardingActorId", session.actorId)
            setField(main, "priorityUserOnboardingPolicy", completedPriorityEducation())
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            setField(main, "statusText", status)
            setField(main, "nativeRootOverlay", root)
            setField(main, "cameraScreenControls", camera)
            root.addView(LinearLayout(context).apply { visibility = View.GONE; addView(status) })
            leaf = invoke("createAccessibilityStatusView", TextView::class.java) as TextView
            assertSame(leaf, field(main, "accessibilityStatusText"))
            assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
            assertTrue(homeAvailable())
            assertNull(field(main, "feedbackActuator"))
            assertNull(field(main, "session"))
        }

        fun homeAvailable() = invoke("shouldShowNativeHome", java.lang.Boolean.TYPE) as Boolean
        fun pane(homeAvailable: Boolean, settingsVisible: Boolean) = invoke("updateNativeAccessibilityPaneTitle", Void.TYPE,
            arrayOf(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE), homeAvailable, settingsVisible)
        fun page(name: String) {
            val page = requireNotNull(field(main, "nativeUiPage"))
            val selected = page.javaClass.enumConstants.single { (it as Enum<*>).name == name }
            invoke("setNativeUiPage", Void.TYPE, arrayOf(page.javaClass), selected)
        }
        fun announce(message: String, priority: MainActivity.TalkBackAnnouncementPriority,
            owner: MainActivity.CommandSpeechResponseOwner, onRequest: () -> Unit): Boolean =
            invoke("announceForTalkBack", java.lang.Boolean.TYPE,
                arrayOf(String::class.java, MainActivity.TalkBackAnnouncementPriority::class.java,
                    kotlin.jvm.functions.Function0::class.java, java.lang.Integer::class.java, String::class.java,
                    kotlin.jvm.functions.Function0::class.java), message, priority, onRequest,
                if (priority == MainActivity.TalkBackAnnouncementPriority.RISK) 1 else null,
                if (priority == MainActivity.TalkBackAnnouncementPriority.RISK) "semantic-risk-owner" else null,
                { owner.isCurrent(main) }) as Boolean
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true; check(returnType == returns) }
            return try { method.invoke(main, *values) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (problem: Throwable) {
                if (first == null) first = problem else first!!.addSuppressed(problem)
            } }
            cleanup { (field(main, "pendingTalkBackInteraction") as Runnable?)?.let(status::removeCallbacks) }
            cleanup { invoke("cancelCommandSpeechResponse", Void.TYPE) }
            cleanup { lifecycle.handle(WalkSessionEvent.EnteredBackground); setField(main, "isActivityForeground", lifecycle.snapshot().isForeground) }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
            first?.let { throw it }
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        const val LEGACY_STATUS = "기존 상태 문구"

        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            // Entry guard only; Application/SDK initialization may precede it.
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val userUids = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.takeIf { match -> match.groupValues[1] == USER_PACKAGE }
                    ?.groupValues?.get(2)?.toIntOrNull() }
            }
            assertEquals(1, userUids.size)
            assertNotEquals(userUids.single(), Process.myUid())
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            var first: Throwable? = null
            try { onMain(instrumentation) { f.prepare() }; test(f) }
            catch (failure: Throwable) { first = failure; throw failure }
            finally { try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
                catch (cleanup: Throwable) { if (first == null) throw cleanup else first.addSuppressed(cleanup) } }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { value.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }
            return value.get()
        }
        fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
        fun completedOnboarding(): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            check(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            check(device.accepted && device.current.mayEnterWalk)
            return device.current
        }
        fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
        fun completedPriorityEducation() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true)
            reviewPracticeNecessity(true)
            acceptEducationConsent()
            acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
            confirmSafePracticePlace()
            PriorityUserPractice.entries.forEach { practice ->
                val token = requireNotNull(beginPractice(practice))
                PriorityUserPracticeDeliverySignal.entries.forEach { recordPracticeDelivery(token, it) }
                if (practice == PriorityUserPractice.HAZARD_ALERT) confirmHazardResponse(token)
            }
            acceptAppUsageEducation()
            check(snapshot().nativeEducationComplete)
        }
    }
}
