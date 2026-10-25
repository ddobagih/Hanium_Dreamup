package kr.co.hanium.dreamup.walksafe.startup.interaction

import kr.co.hanium.dreamup.walksafe.device.*
import org.junit.Assert.*
import org.junit.Test

class PostLoginDeviceInteractionBehaviorTest {
    @Test fun constructionIsInertAndUncompletedStoredCheckDoesNotQueryLiveSensors() {
        val f=PostLoginInteractionFixture(); assertTrue(f.trace.isEmpty())
        f.state=PostLoginDeviceCheckState.RUNNING; f.disabled=setOf(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION)
        assertEquals(f.disabled,f.controller.currentPostLoginDisabledFeatures())
        assertEquals(listOf("getQuickStart","getSnapshot","getSnapshot"),f.trace)
    }
    @Test fun deferredCameraAndRuntimeOverridesRemainIndependentRestrictions() {
        val f=PostLoginInteractionFixture(); f.deferred=true
        assertEquals(setOf(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION,PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),f.controller.currentPostLoginDisabledFeatures())
        f.deferred=false; f.obstacle=false; f.metric=false
        assertEquals(setOf(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION,PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),f.controller.currentPostLoginDisabledFeatures())
    }
    @Test fun permissionAndTtsOverridesKeepOriginalShortCircuitReads() {
        val f=PostLoginInteractionFixture(); f.location=false; f.tts=false; f.microphone=false
        val disabled=f.controller.currentPostLoginDisabledFeatures()
        assertTrue(disabled.containsAll(listOf(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE,PostLoginDeviceCheckFeature.VOICE_GUIDANCE,PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)))
        assertFalse(f.trace.contains("locationServiceEnabled")); assertEquals(1,f.trace.count {it=="startupSnapshot"})
        assertFalse(f.trace.contains("notificationPermission")); assertFalse(f.trace.contains("disclosureAccepted"))
    }
    @Test fun bypassFeatureGateDoesNotReadStoredOrLiveRestrictions() {
        val f=PostLoginInteractionFixture(); f.bypass=true
        assertTrue(f.controller.postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION))
        assertEquals(listOf("getBypass"),f.trace)
    }
    @Test fun progressTextDeduplicatesButTerminalDisablesLiveRegionBeforeFocusRequest() {
        val f=PostLoginInteractionFixture(); f.controller.announcePostLoginDeviceCheckProgress("checking")
        assertEquals(listOf("published","liveRegionPolite","show","text:checking"),f.trace)
        f.trace.clear(); f.controller.announcePostLoginDeviceCheckProgress("checking"); assertEquals(listOf("published"),f.trace)
        f.trace.clear(); f.screenReader=true; f.state=PostLoginDeviceCheckState.FAIL
        f.controller.announcePostLoginDeviceCheckTerminal("failed")
        assertEquals(listOf("published","liveRegionNone","show","text:failed","screenReaderActive","post"),f.trace)
        assertFalse(f.trace.contains("requestAccessibilityFocus")); f.posted!!(); assertEquals("requestAccessibilityFocus",f.trace.last())
    }
    @Test fun delayedTerminalFocusRechecksCurrentFailureAndVisibility() {
        val f=PostLoginInteractionFixture(); f.screenReader=true; f.state=PostLoginDeviceCheckState.FAIL
        f.controller.announcePostLoginDeviceCheckTerminal("failed"); f.state=PostLoginDeviceCheckState.FULL; f.trace.clear(); f.posted!!()
        assertEquals(listOf("getSnapshot"),f.trace)
        f.state=PostLoginDeviceCheckState.FAIL; f.shown=false; f.trace.clear(); f.posted!!()
        assertEquals(listOf("getSnapshot","shown"),f.trace)
    }
    @Test fun notPublishedAnnouncementDoesNotChangeStatusOwnerOrResolveNativeView() {
        val f=PostLoginInteractionFixture(); f.published=false
        f.controller.announcePostLoginDeviceCheckProgress("progress"); f.controller.announcePostLoginDeviceCheckTerminal("terminal")
        assertNull(f.controller.liveStatus()); assertEquals(listOf("published","published"),f.trace)
    }
    @Test fun liveStatusResetAllowsSameProgressToBeAnnouncedAfterNewCheck() {
        val f=PostLoginInteractionFixture(); f.controller.announcePostLoginDeviceCheckProgress("checking"); f.controller.writeLiveStatus(null); f.trace.clear()
        f.controller.announcePostLoginDeviceCheckProgress("checking"); assertTrue(f.trace.contains("text:checking"))
    }
    @Test fun notRunItemsDoNotProbePermissionsOrStartupOwner() {
        val f=PostLoginInteractionFixture(); f.state=PostLoginDeviceCheckState.NOT_RUN
        val text=f.controller.postLoginDeviceCheckItemsMessage(); assertTrue(text.isNotBlank())
        assertEquals(listOf("getSnapshot"),f.trace)
    }
    @Test fun terminalCameraAndMetricDisplayPreserveDistinctFailureInformation() {
        val f=PostLoginInteractionFixture(); f.deferred=true; f.camera=false
        assertEquals("권한 허용 후 재점검 필요",f.controller.terminalCameraDetectorStatusText())
        assertEquals("권한 허용 후 재점검 필요",f.controller.terminalMetricDistanceStatusText())
        f.deferred=false; f.depth=PostLoginMetricDepthState.UNKNOWN
        assertEquals("확인 불가(AR 서비스·권한 확인)",f.controller.terminalMetricDistanceStatusText())
    }
}

private class PostLoginInteractionFixture {
    val trace=mutableListOf<String>(); var state=PostLoginDeviceCheckState.FULL; var disabled=emptySet<PostLoginDeviceCheckFeature>()
    var quick=false; var bypass=false; var deferred=false; var obstacle:Boolean?=null; var metric:Boolean?=null; var tts:Boolean?=null
    var camera=true; var location=true; var microphone=true; var screenReader=false; var published=true; var shown=true
    var depth=PostLoginMetricDepthState.AVAILABLE; var posted:(()->Unit)?=null
    val controller=PostLoginDeviceInteractionController(
        startupPort<PostLoginDeviceFacts>{name,_->trace+=name;when(name){
            "getSnapshot"->PostLoginDeviceCheckSnapshot(state,"actor",1L,1L,disabledFeatures=disabled)
            "getQuickStart"->quick;"getBypass"->bypass;"getCameraChecksDeferred"->deferred
            "getObstacleOverride"->obstacle;"getMetricOverride"->metric;"getOfflineTtsOverride"->tts
            "getProbeInitialized"->true;"getMetricDepth"->depth;"getCameraPipelineActive"->false;"getResourceWaitNotice"->null
            "cameraPermission"->camera;"locationPermission"->location;"recordAudioPermission"->microphone
            "locationServiceEnabled","notificationPermission","disclosureAccepted","activityRecognitionPermission","passesFeatureGate","detectorLoadAttempted","detectorAvailable"->true
            "startupSnapshot"->startupInput();"screenReaderActive"->screenReader
            else->error(name)
        }},
        startupPort<PostLoginDeviceLiveStatusView>{name,args->when(name){
            "text"->{trace+="text:${args[0]}";Unit}
            "published"->{trace+=name;published};"shown"->{trace+=name;shown}
            "post"->{trace+=name;@Suppress("UNCHECKED_CAST") val action=args[0] as ()->Unit;posted=action;Unit}
            "liveRegionPolite","liveRegionNone","show","requestAccessibilityFocus"->{trace+=name;Unit};else->error(name)
        }},
    )
}
