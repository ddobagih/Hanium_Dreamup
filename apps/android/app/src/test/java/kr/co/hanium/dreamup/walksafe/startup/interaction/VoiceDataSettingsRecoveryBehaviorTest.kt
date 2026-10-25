package kr.co.hanium.dreamup.walksafe.startup.interaction

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.postlogin.PostLoginLocationFixTerminalFailure
import kr.co.hanium.dreamup.walksafe.voice.*
import org.junit.Assert.*
import org.junit.Test

class VoiceDataSettingsRecoveryBehaviorTest {
    @Test fun stoppedPendingHomeProbeActuallyStartsAndDoesNotInventATerminalResult() {
        val f=VoiceSettingsFixture(); f.started=false; f.available=null; f.override=null
        assertTrue(f.controller.ensurePendingKoreanSpeechProbeStarted())
        assertTrue(f.started); assertNull(f.available); assertNull(f.override)
        assertEquals(1,f.trace.count { it=="start" }); assertFalse(f.trace.contains("recoverPassedGate"))
        f.trace.clear()
        assertFalse(f.controller.ensurePendingKoreanSpeechProbeStarted())
        assertFalse(f.trace.contains("replace")); assertFalse(f.trace.contains("start"))
        f.available=true; f.changed!!.invoke()
        assertEquals(true,f.available); assertNull(f.override)
        assertTrue(f.trace.contains("refresh")); assertFalse(f.trace.contains("recoverPassedGate"))
    }
    @Test fun runningHomeProbeAndMeasuredTerminalStatesAreNeverRestarted() {
        for(available in listOf<Boolean?>(null,true,false)) {
            val running=VoiceSettingsFixture(); running.available=available
            assertFalse(running.controller.ensurePendingKoreanSpeechProbeStarted())
            assertFalse(running.trace.contains("start")); assertFalse(running.trace.contains("close"))
        }
        for(available in listOf(true,false)) {
            val completed=VoiceSettingsFixture(); completed.started=false; completed.available=available
            assertFalse(completed.controller.ensurePendingKoreanSpeechProbeStarted())
            assertFalse(completed.trace.contains("start")); assertFalse(completed.trace.contains("close"))
        }
    }
    @Test fun unavailableHomeProbeCompletionCannotPromoteVoiceCapability() {
        val f=VoiceSettingsFixture(); f.started=false; f.available=null; f.override=null
        f.controller.ensurePendingKoreanSpeechProbeStarted()
        f.available=false; f.changed!!.invoke()
        assertEquals(false,f.available); assertNull(f.override)
        assertFalse(f.trace.contains("recoverPassedGate")); assertTrue(f.trace.contains("refresh"))
    }
    @Test fun synchronousHomeProbeCompletionStillRefreshesTheActualResult() {
        val f=VoiceSettingsFixture(); f.started=false; f.available=null; f.override=null
        f.onStart={f.available=true;f.changed!!.invoke()}
        assertTrue(f.controller.ensurePendingKoreanSpeechProbeStarted())
        assertEquals(true,f.available); assertNull(f.override)
        assertEquals(1,f.trace.count { it=="refresh" })
    }
    @Test fun failedHomeProbeStartIsVisibleAndNeverReportsReady() {
        val f=VoiceSettingsFixture(); f.started=false; f.available=null; f.override=null
        f.onStart={error("controlled native start failure")}
        assertFalse(f.controller.ensurePendingKoreanSpeechProbeStarted())
        assertFalse(f.started); assertNull(f.available); assertNull(f.override)
        assertTrue(f.statuses.last().first.contains("실패"))
        assertFalse(f.trace.contains("recoverPassedGate"))
    }
    @Test fun constructionAndNoPendingReturnDoNotQueryProbeOrLaunchSettings() {
        val f=VoiceSettingsFixture(); assertTrue(f.trace.isEmpty())
        f.controller.recheckVoiceDataAfterSettingsIfNeeded(); f.controller.completeVoiceDataInstallRecheckIfPossible()
        assertTrue(f.trace.isEmpty())
    }
    @Test fun voiceCandidatesAreConstructedInOriginalOrderBeforePreferredEngineLaunch() {
        val f=VoiceSettingsFixture(); f.cause=OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN
        f.controller.openVoiceDataInstallSettings()
        assertEquals(listOf("getInitialized","unavailableCause","build:install","build:engine","build:app","start:engine","status"),f.trace)
    }
    @Test fun unavailableVoiceScreensFallBackInOrderAndCannotArmReturnRecheck() {
        val f=VoiceSettingsFixture(); f.launchFailures=setOf("install","engine","app")
        f.controller.openVoiceDataInstallSettings()
        assertEquals(listOf("start:install","start:engine","start:app"),f.trace.filter { it.startsWith("start:") })
        assertTrue(f.trace.indexOf("status")<f.trace.indexOf("speak"))
        f.trace.clear(); f.controller.recheckVoiceDataAfterSettingsIfNeeded(); assertTrue(f.trace.isEmpty())
    }
    @Test fun settingsFailureUsesCauseSpecificFallbackWithoutQueryingLaterCause() {
        val f=VoiceSettingsFixture(); f.failure=PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE
        f.locationFailure=PostLoginLocationFixTerminalFailure.REQUEST_FAILED; f.launchFailures=setOf("app")
        f.controller.openPostLoginDeviceCheckSettings()
        assertEquals(listOf("getSnapshot","getLocationTerminalFailure","build:app","start:app","build:app","start:app"),f.trace)
        assertFalse(f.trace.contains("getLocationReason"))
    }
    @Test fun mockLocationSettingsUseDeveloperScreenAndTtsFailureUsesVoiceRecoveryFlow() {
        val f=VoiceSettingsFixture(); f.failure=PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE; f.locationReason=DeviceCheckLocationFixReason.MOCK_LOCATION
        f.controller.openPostLoginDeviceCheckSettings(); assertTrue(f.trace.contains("start:development"))
        val g=VoiceSettingsFixture(); g.failure=PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE
        g.controller.openPostLoginDeviceCheckSettings(); g.trace.clear(); g.controller.recheckVoiceDataAfterSettingsIfNeeded()
        assertTrue(g.trace.contains("recheck"))
    }
    @Test fun recheckIsOneAttemptAndResetsOverrideBeforeOriginalProbeQuery() {
        val f=VoiceSettingsFixture(); f.open(); f.trace.clear(); f.controller.recheckVoiceDataAfterSettingsIfNeeded()
        assertEquals(listOf("getInitialized","setOfflineTtsOverride","getStarted","recheck","status"),f.trace)
        assertNull(f.override); f.trace.clear(); f.controller.recheckVoiceDataAfterSettingsIfNeeded(); assertTrue(f.trace.isEmpty())
    }
    @Test fun synchronousRecheckCompletionCannotBeOverwrittenByCheckingNotice() {
        val f=VoiceSettingsFixture(); f.open(); f.onRecheck={ f.controller.completeVoiceDataInstallRecheckIfPossible() }
        f.trace.clear(); f.controller.recheckVoiceDataAfterSettingsIfNeeded()
        assertEquals(true,f.override); assertTrue(f.statuses.last().first.contains("재점검 완료"))
        assertEquals(1,f.trace.count { it=="status" }); assertTrue(f.trace.indexOf("recoverPassedGate")<f.trace.indexOf("status"))
    }
    @Test fun unknownProbeResultKeepsAttemptPendingUntilMeasuredTerminalResult() {
        val f=VoiceSettingsFixture(); f.open(); f.available=null; f.controller.recheckVoiceDataAfterSettingsIfNeeded()
        f.trace.clear(); f.controller.completeVoiceDataInstallRecheckIfPossible()
        assertEquals(listOf("snapshot"),f.trace); assertNull(f.override)
        f.available=true; f.controller.completeVoiceDataInstallRecheckIfPossible(); assertEquals(true,f.override)
        f.trace.clear(); f.controller.completeVoiceDataInstallRecheckIfPossible(); assertTrue(f.trace.isEmpty())
    }
    @Test fun unavailableSpeechDoesNotRecoverPassedGateOrSetTrueOverride() {
        val f=VoiceSettingsFixture(); f.open(); f.available=false; f.controller.recheckVoiceDataAfterSettingsIfNeeded()
        f.trace.clear(); f.controller.completeVoiceDataInstallRecheckIfPossible()
        assertFalse(f.trace.contains("recoverPassedGate")); assertNull(f.override)
        assertTrue(f.statuses.last().second.contains("아직 사용할 수 없습니다"))
    }
    @Test fun failedGatePersistenceDoesNotPromoteObservedSpeechToEnabled() {
        val f=VoiceSettingsFixture(); f.open(); f.recover=false; f.controller.recheckVoiceDataAfterSettingsIfNeeded()
        f.controller.completeVoiceDataInstallRecheckIfPossible()
        assertNull(f.override); assertEquals("음성 기능 복구 저장 실패",f.statuses.last().first)
    }
    @Test fun stoppedProbeReplacementClosesBeforeReplaceAndMarksStartedBeforeStart() {
        val f=VoiceSettingsFixture(); f.open(); f.started=false; f.trace.clear()
        f.onStart={ f.changed!!() }; f.controller.recheckVoiceDataAfterSettingsIfNeeded()
        assertEquals(listOf("getInitialized","setOfflineTtsOverride","getStarted","close","replace","setStarted","start","snapshot","recoverPassedGate","setOfflineTtsOverride","status","refresh"),f.trace)
        assertEquals(true,f.override)
    }
    @Test fun nativeRecheckFailureClosesProbeAndEndsAttemptWithoutFalseRecovery() {
        val f=VoiceSettingsFixture(); f.open(); f.onRecheck={ error("native failure") }; f.trace.clear()
        f.controller.recheckVoiceDataAfterSettingsIfNeeded()
        assertEquals(listOf("getInitialized","setOfflineTtsOverride","getStarted","recheck","setStarted","close","status"),f.trace)
        assertFalse(f.started); assertNull(f.override)
        f.trace.clear(); f.controller.completeVoiceDataInstallRecheckIfPossible(); f.controller.recheckVoiceDataAfterSettingsIfNeeded(); assertTrue(f.trace.isEmpty())
    }
}

private class VoiceSettingsFixture {
    val trace=mutableListOf<String>(); val statuses=mutableListOf<Pair<String,String>>()
    var initialized=true; var started=true; var available:Boolean?=true; var cause:OfflineKoreanSpeechUnavailableCause?=null
    var failure:PostLoginDeviceCheckFailure?=null; var locationFailure:PostLoginLocationFixTerminalFailure?=null; var locationReason:DeviceCheckLocationFixReason?=null
    var override:Boolean?=false; var recover=true; var launchFailures=emptySet<String>(); var changed:(()->Unit)?=null
    var onRecheck:(()->Unit)?=null; var onStart:(()->Unit)?=null
    val navigation=StartupSettingsNavigation(startupPort<StartupSettingsNative<String>> { name,args ->
        val target=when(name){"installVoiceData"->"install";"chooseVoiceEngine"->"engine";"applicationSettings"->"app";"locationSettings"->"location";"developmentSettings"->"development";else->null}
        if(target!=null){trace+="build:$target";target}else if(name=="start") { val intent=args[0] as String; trace+="start:$intent"; if(intent in launchFailures) error("unavailable"); Unit }else error(name)
    })
    val controller=VoiceDataSettingsRecoveryController(
        startupPort<VoiceDataProbePort>{name,args->trace+=name;when(name){
            "getInitialized"->initialized;"getStarted"->started;"setStarted"->{started=args[0] as Boolean;Unit}
            "snapshot"->startupInput(available);"unavailableCause"->cause;"recheck"->{onRecheck?.invoke();Unit}
            "close"->Unit;"replace"->{@Suppress("UNCHECKED_CAST") val action=args[0] as ()->Unit;changed=action;Unit}
            "start"->{onStart?.invoke();Unit};else->error(name)
        }},
        startupPort<VoiceSettingsFacts>{name,_->trace+=name;when(name){
            "getSnapshot"->PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,"actor",1L,1L,failure)
            "getLocationTerminalFailure"->locationFailure;"getLocationReason"->locationReason;"requiredPermissions"->emptyList<String>();"locationServiceEnabled"->true;else->error(name)
        }},
        startupPort<VoiceSettingsEffects>{name,args->trace+=name;when(name){
            "getOfflineTtsOverride"->override;"setOfflineTtsOverride"->{override=args[0] as Boolean?;Unit}
            "recoverPassedGate"->recover;"status"->{statuses+=(args[0] as String) to (args[1] as String);Unit}
            "refresh","speak"->Unit;else->error(name)
        }},navigation,
    )
    fun open()=controller.openVoiceDataInstallSettings()
}
