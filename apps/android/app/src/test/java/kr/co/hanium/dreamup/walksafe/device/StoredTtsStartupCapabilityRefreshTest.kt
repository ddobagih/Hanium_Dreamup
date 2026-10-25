package kr.co.hanium.dreamup.walksafe.device
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.postlogin.*
import org.junit.Assert.*
import org.junit.Test
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.readiness.StartupCapabilityReadinessEvidence
import kr.co.hanium.dreamup.walksafe.startup.capability.*

/** Actual Controller + AndroidPostLoginDeviceProbes + result store/resolver.
 * Stable verified actor and native probe-result callback are controlled leaves.
 * Actual Coordinator/evidence/lifecycle and PrewalkGuidanceProgress are also connected.
 * Main adapter/native TTS and prewalk continuation itself are NOT executed; observer port records them.
 */
class StoredTtsStartupCapabilityRefreshTest {
    @Test fun readyAfterInitialPendingMustRefreshReadiness() {
        val f=Fixture(); f.begin(); f.pendingCallback()
        val before=f.presented.size
        f.terminal(true)
        assertEquals(true, f.tts)
        println("ready terminal: actualTtsState=${f.tts}; UI callbacks before=$before after=${f.presented.size}; pending=${f.presented.last().pendingRequirements}")
        assertTrue("READY native-probe result must refresh the prior pending presentation", f.presented.last().mayConfirmAndStart)
        assertSame(f.presented.last(),f.evidence.currentDecision)
        assertSame(f.presented.last(),f.prewalkObservations.last())
        assertEquals(1_000L,f.evidence.originalObservedAtMs)
    }
    @Test fun unavailableAfterInitialPendingMustReplaceCheckingNotice() {
        val f=Fixture(); f.begin(); f.pendingCallback()
        f.terminal(false)
        assertTrue("terminal unavailable must replace checking", WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in f.presented.last().unavailableRequirements)
        assertFalse(f.presented.last().mayConfirmAndStart)
        assertSame(f.presented.last(),f.prewalkObservations.last())
    }
    @Test fun repeatedUnrelatedRefreshDoesNotReplaceTheResolvedReadyDecision() {
        val f=Fixture(); f.begin(); f.pendingCallback(); f.terminal(true)
        assertTrue(f.presented.last().mayConfirmAndStart)
        f.refresh()
        assertTrue(f.presented.last().mayConfirmAndStart)
    }
    @Test fun firstCallbackAlreadyReadyDoesNotStrandPresentation() {
        val f=Fixture(); f.begin(); f.terminal(true)
        assertTrue(f.presented.last().mayConfirmAndStart)
    }
    @Test fun retiredActorAndProbeRejectOldTerminalCallback() {
        val f=Fixture();f.begin();val oldTerminal=f.engineTerminal
        f.actor=f.actor.copy(actorId=null,sessionGeneration=null,verifiedActorId=null,sessionVerified=false,sessionUsable=false)
        f.controller.bindSession(null,null)
        val afterRetirement=f.presented.size
        oldTerminal(true)
        assertNull(f.tts)
        assertEquals(afterRetirement,f.presented.size)
        assertFalse(f.controller.passesFeatureGate())
    }
    @Test fun replacedProbeKeepsNewPendingStateAndRejectsOldTerminalCallback() {
        val f=Fixture();f.begin();val oldTerminal=f.engineTerminal;val oldChanged=f.nativeChanged
        f.controller.cancel("explicit_restart")
        f.controller.maybeValidateStoredBinding()
        assertTrue(f.probes.storedBindingValidationPending)
        val before=f.presented.size
        oldTerminal(true); oldChanged()
        assertNull(f.tts)
        assertEquals(before,f.presented.size)
        f.terminal(true)
        assertTrue(f.presented.last().mayConfirmAndStart)
    }
    private class Fixture {
        var ttsState=OfflineKoreanTextToSpeechProbeState()
        val tts get()=ttsState.available
        lateinit var engineTerminal:(Boolean)->Unit
        var started=false
        lateinit var nativeChanged:()->Unit
        lateinit var probeChanged:()->Unit
        val presented=mutableListOf<WalkSafeStartupCapabilityDecision>()
        var actor=PostLoginDeviceCheckActorFacts(true,"actor",1,"actor",true,true,false,false)
        fun input()=WalkSafeStartupCapabilityInput(true,true,true,true,true,true,tts,true,true,"profile")
        fun binding()=PostLoginDeviceCheckResultBinding("actor","installation","maker","model","device",36,"fingerprint","app",1,"1","source","policy",null,null,"registry","sha",null,null,emptySet(),true,true,tts,true)
        val store=AndroidDeviceCheckResultStore(FakeSharedPreferences())
        var now=1000L
        val lifecycle=WalkSessionLifecycle({"review-walk"},{now},{now}).apply { handle(WalkSessionEvent.EnteredForeground) }
        val evidence=StartupCapabilityReadinessEvidence({WalkSafeStartupCapabilityResolver.resolve(input())},{"review-v1"},{now})
        var confirmed:WalkSafeStartupCapabilityDecision?=null
        var confirmationPending=false
        var retryRequired=false
        val prewalkObservations=mutableListOf<WalkSafeStartupCapabilityDecision>()
        val prewalkProgress=PrewalkGuidanceProgress(proxy<PrewalkGuidancePort> {name,_ -> when(name) {
            "getNativeStartPending" -> true
            "getPreflightActive" -> false
            "continueNativeStart" -> {prewalkObservations += checkNotNull(evidence.currentDecision);Unit}
            else -> error("unexpected prewalk port: $name")
        }},10_000L)
        val startup:StartupCapabilityCoordinator by lazy {
            StartupCapabilityCoordinator(proxy<StartupCapabilityEvaluation> { name,_ -> when(name) {
                "getMetricOverride" -> null
                "getQuickStart","getBypass" -> false
                "getCheckPassesFeatureGate" -> controller.snapshot.passesFeatureGate
                "probeDecision" -> WalkSafeStartupCapabilityResolver.resolve(input())
                "disabledFeatures" -> controller.snapshot.disabledFeatures
                "activityRecognitionPermission" -> true
                else -> error("unexpected evaluation $name")
            }}, {evidence}, {lifecycle}, object:StartupCapabilityConfirmation {
                override var confirmed:WalkSafeStartupCapabilityDecision?
                    get()=this@Fixture.confirmed
                    set(value) {this@Fixture.confirmed=value}
                override var pending:Boolean
                    get()=confirmationPending
                    set(value) {confirmationPending=value}
                override var retryRequiresUserAction:Boolean
                    get()=retryRequired
                    set(value) {retryRequired=value}
                override fun isConfirmed()=confirmed!=null&&confirmed==evidence.currentDecision
            }, proxy<StartupCapabilityUi> {name,args -> when(name) {
                "ready" -> true
                "persist" -> {assertSame(args!![0],evidence.currentDecision);Unit}
                "priority" -> PriorityUserOnboardingDecision(PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION,null,null,emptyList(),false)
                "officialReadiness","mountingReadiness" -> WalkSessionReadinessStatus.READY
                "render" -> {presented += args!![0] as WalkSafeStartupCapabilityDecision;Unit}
                "updatePrewalk" -> {prewalkProgress.updatePrewalkGuidance(args!![0] as WalkRuntimeEpoch,args[1] as Boolean,args[2] as Boolean);Unit}
                "updateOfficialEnvironment","updatePriority","updateFirstRun","updateMounting","updateFieldLog",
                "applyActionButtons","advanceAfterCheck","updateSafetySummary" -> Unit
                else -> error("unexpected UI effect $name")
            }}, proxy<ActiveReadinessPort>(),"한국어 음성 제한")
        }
        fun refresh() {startup.refreshStartupCapabilityUi()}
        val facts=object:PostLoginDeviceCheckFactsReader {
            override fun actor()=actor
            override fun hardware():PostLoginDeviceCheckHardwareFacts=error("unexpected hardware read")
            override fun resultBinding(actorId:String)=binding().also { assertEquals("actor",actorId) }
        }
        val support=proxy<PostLoginProbeRuntimeSupport> { name,args -> when(name) {
            "getCapabilityProbeStarted" -> started
            "mayValidateStoredBinding" -> true
            "startStoredBindingValidation" -> { @Suppress("UNCHECKED_CAST")
                nativeChanged=args!![0] as ()->Unit
                ttsState=OfflineKoreanTextToSpeechProbeState()
                val ownedState=ttsState; val ticket=ownedState.begin(); val bindingChanged=nativeChanged
                probeChanged={startup.refreshAfterStoredBindingProbeChanged(bindingChanged)}
                val changed=probeChanged
                engineTerminal={ value -> if(ownedState.complete(ticket,value)) changed() }
                started=true;true }
            "closeCapabilityAndResetResources" -> {if(started) ttsState.close(); started=false;Unit}
            "cancelCalibration","cancelMetricPreflight" -> Unit
            "mayStartStoredMetricRefresh" -> false
            else -> error("unexpected support: $name")
        }}
        val probes=AndroidPostLoginDeviceProbes(
            proxy<PostLoginLocationSource>(),proxy<PostLoginCameraSource>(),
            PostLoginWakeProbeFactory {_,_,_->error("unexpected wake construction")},
            {error("unexpected haptic construction")},{error("unexpected detector query")},
            Executor { error("unexpected execute") },proxy<PostLoginProbeDispatcher>(),proxy<PostLoginProbeDispatcher>(),
            {error("unexpected clock query")},{error("unexpected native environment")},
            {error("unexpected native binding")},{error("unexpected native currentness")},{true},
            {false},{error("unexpected release block")},{null},{false},{},support,proxy<PostLoginProbeObserver>())
        val effects=proxy<PostLoginDeviceCheckEffects> {name,_ -> when(name) {
            "refreshReadinessPresentation" -> {refresh();Unit}
            "invalidateRouteResponseLease","invalidateSpeechStorageLease","applyCapabilityOverrides",
            "scheduleCalibration","afterRestoredResult","noteCancellation" -> Unit
            else -> error("unexpected effects: $name")
        }}
        val controller=PostLoginDeviceCheckController(facts,AndroidPostLoginDeviceCheckSavedResults {store},probes,
            proxy<PostLoginDeviceCheckPermissionPort>(),effects,proxy<PostLoginDeviceCheckScheduler>(),PostLoginDeviceCheckController.Config())
        fun begin() {
            assertTrue(store.save(PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,"actor",1,1), binding(),
                metricDepthState=PostLoginMetricDepthState.SUPPORTED))
            controller.bindSession("actor",1)
            assertTrue(controller.passesFeatureGate())
            assertTrue(probes.storedBindingValidationPending)
            assertFalse(presented.last().mayConfirmAndStart)
        }
        fun pendingCallback() {
            assertNull(tts);probeChanged()
            assertFalse(probes.storedBindingValidationPending)
            assertEquals("기기 기능을 확인하는 중입니다: 오프라인 한국어 음성 안내.",presented.last().noticeKo)
        }
        fun terminal(value:Boolean) { engineTerminal(value) }
    }
    companion object {
        private inline fun <reified T> proxy(noinline fn:(String,Array<out Any?>?)->Any?={n,_->error("unexpected ${T::class.java.simpleName}.$n")}):T =
            Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,m,args->fn(m.name,args)} as T
    }
    private class FakeSharedPreferences(
        initialValues: Map<String, Any> = emptyMap(),
        var commitResult: Boolean = true,
        private val mutateMemoryOnCommitFailure: Boolean = false,
    ) : SharedPreferences {
        private val values = initialValues.toMutableMap()
        var commitCount = 0
            private set
        var applyCount = 0
            private set

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply { updates[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

            override fun commit(): Boolean {
                commitCount += 1
                if (!commitResult && !mutateMemoryOnCommitFailure) return false
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return commitResult
            }

            override fun apply() {
                applyCount += 1
                commit()
            }
        }
    }

}
