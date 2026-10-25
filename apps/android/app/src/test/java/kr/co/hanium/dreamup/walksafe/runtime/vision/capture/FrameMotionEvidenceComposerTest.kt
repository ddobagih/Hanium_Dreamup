package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import com.google.ar.core.*
import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.PositioningDiagnosticsPresenter
import org.junit.Assert.*
import org.junit.Test

/** Actual positioning/calibration/profile owners; source callbacks double clocks and Android view boundaries only. */
class FrameMotionEvidenceComposerTest {
    @Test fun constructionDoesNotQuerySourcesSdkOrActualOwnerProvider() { assertTrue(Fixture().events.isEmpty()) }
    @Test fun depthFreshnessKeepsOriginalBoundariesAndNoRouteDependency() {
        val f=Fixture()
        listOf(-1L to .8f,0L to 1f,2500L to 1f,2501L to .75f,7500L to .75f,7501L to .45f).forEach { (age,quality)->
            f.location=TrustedLocation(37.0,127.0,3f,10_000-age);f.events.clear()
            val result=f.owner.buildDepthMotionContext(10_000)
            assertEquals(quality,result.freshnessQuality,0f);assertNull(result.userMotion.speed)
            assertEquals("location:10000",f.events.first());assertFalse(f.events.contains("route"))
        }
    }
    @Test fun missingLocationKeepsPointSevenQualityWithoutInventingSpeedHeadingOrClock() {
        val f=Fixture();val result=f.owner.buildDepthMotionContext(500)
        assertEquals(.7f,result.freshnessQuality,0f);assertNull(result.userMotion.speed);assertNull(result.userMotion.phoneHeading)
        assertFalse(f.events.contains("clock"));assertFalse(f.events.contains("orientation"))
    }
    @Test fun projectionKeepsOriginalFrameAndElapsedClockAndConditionalLocationReads() {
        val f=Fixture();val frame=FakeFrame(f.events)
        val result=f.owner.buildTactileProjectionContext(frame,4321)
        assertEquals(99L,result.captureFrameId);assertEquals(4321L,result.nowElapsedRealtimeMs)
        assertNull(result.trustedLocation);assertNull(result.magneticDeclinationDeg)
        assertEquals(listOf("location:4321","route","orientation","frame-time","camera","tracking"),f.events)
    }
    @Test fun projectionCarriesSameTrustedObjectWithoutGPSOrMotionRefresh() {
        val f=Fixture();val location=TrustedLocation(37.0,127.0,2f,4000);f.location=location
        val result=f.owner.buildTactileProjectionContext(FakeFrame(f.events),4321)
        assertSame(location,result.trustedLocation);assertEquals(99L,result.captureFrameId)
        assertFalse(f.events.contains("clock"));assertFalse(f.events.contains("positioning"))
    }
    @Test fun motionDiagnosticQueriesGatewayTwiceAndDoesNotConsumeActualMotionState() {
        val f=Fixture();val before=f.positioning.coordinator.peekUserMotion(500)
        val text=f.owner.currentMotionEvidenceStatusMessage()
        assertFalse(text.isBlank());assertEquals(2,f.events.count{it=="gateway"});assertEquals(1,f.events.count{it=="clock"})
        assertEquals(before,f.positioning.coordinator.peekUserMotion(500));assertEquals(2,f.events.count{it=="step"})
    }
    @Test fun unpublishedGuidanceStopsBeforeProfileGatewayClockAndViewScheduling() {
        val f=Fixture();f.published=false;f.owner.refreshWalkingCalibrationDiagnostics()
        assertEquals(listOf("scope","published"),f.events);assertTrue(f.queued.isEmpty());assertTrue(f.messages.isEmpty())
    }
    @Test fun validQueuedCalibrationUsesOriginalProfileWithoutRenewingMeasurementTime() {
        val f=Fixture();f.owner.refreshWalkingCalibrationDiagnostics()
        assertEquals(1,f.queued.size);assertTrue(f.messages.isEmpty());assertFalse(f.events.contains("clock"))
        val before = f.positioning.coordinator.peekUserMotion(500)
        f.queued.removeAt(0).invoke();assertEquals(1,f.messages.size)
        assertEquals("presentation",f.events.last());assertEquals(1,f.events.count { it == "clock" })
        assertEquals(before, f.positioning.coordinator.peekUserMotion(500))
        assertTrue(f.messages.single().contains("기본 보폭"))
        assertTrue(f.messages.single().contains("현재 보행 범위의 움직임 근거를 다시 확인하고 있습니다."))
    }
    @Test fun lifecycleChangeRetiresQueuedCalibrationBeforeCurrentPresentationQuery() {
        val f=Fixture();f.owner.refreshWalkingCalibrationDiagnostics()
        f.lifecycle=WalkSessionSnapshot(WalkRuntimeEpoch("changed",1),WalkSessionState.READY,WalkSessionMode.UNAVAILABLE,null,false,null)
        f.queued.single().invoke();assertTrue(f.messages.isEmpty());assertFalse(f.events.contains("presentation"))
    }
    @Test fun gatewayGenerationChangeRetiresQueuedCalibration() {
        val f=Fixture();f.owner.refreshWalkingCalibrationDiagnostics();f.gateway=GatewaySessionProcessSnapshot(null,null,1,false,null,false)
        f.queued.single().invoke();assertTrue(f.messages.isEmpty());assertFalse(f.events.contains("presentation"))
    }
    @Test fun replacedCalibrationScopeAndWrongPageRetireQueuedUi() {
        val f=Fixture();f.owner.refreshWalkingCalibrationDiagnostics()
        f.positioning.synchronizeWalkingCalibrationScope("other",null);f.queued.single().invoke();assertTrue(f.messages.isEmpty())
        val g=Fixture();g.owner.refreshWalkingCalibrationDiagnostics();g.guidancePage=false;g.queued.single().invoke();assertTrue(g.messages.isEmpty())
    }
    @Test fun mainThreadRefreshDoesNotCreateQueuedTask() {
        val f=Fixture();f.mainThread=true;f.owner.refreshWalkingCalibrationDiagnostics()
        assertTrue(f.queued.isEmpty());assertEquals(1,f.messages.size)
    }
    @Test fun guidanceMessageIncludesReadOnlyMotionFactsWithoutCollectingNewEvidence() {
        val f = Fixture()
        val before = f.positioning.coordinator.peekUserMotion(500)
        val message = f.owner.walkingCalibrationStatusMessage()
        assertTrue(message.contains("기본 보폭"))
        assertTrue(message.contains("현재 보행 범위의 움직임 근거를 다시 확인하고 있습니다."))
        assertEquals(before, f.positioning.coordinator.peekUserMotion(500))
        assertEquals(1, f.events.count { it == "clock" })
        assertTrue(f.messages.isEmpty())
        assertTrue(f.queued.isEmpty())
    }
    @Test fun retiredQueuedPresentationDoesNotReadOrPublishMotionFacts() {
        val f = Fixture()
        f.owner.refreshWalkingCalibrationDiagnostics()
        f.gateway = GatewaySessionProcessSnapshot(null, null, 1, false, null, false)
        f.queued.single().invoke()
        assertTrue(f.messages.isEmpty())
        assertFalse(f.events.contains("clock"))
        assertFalse(f.events.contains("step"))
        assertFalse(f.events.contains("permission"))
    }
    private class Fixture {
        val events=mutableListOf<String>();val queued=mutableListOf<()->Unit>();val messages=mutableListOf<String>()
        var published=true;var guidancePage=true;var mainThread=false;var location:TrustedLocation?=null
        var lifecycle:WalkSessionSnapshot?=null
        var gateway=GatewaySessionProcessSnapshot(null,null,0,false,null,false)
        val navigator=RouteNavigator(nowElapsedRealtimeMs={error("route clock")})
        val positioning=PositioningSessionController(NavigationRunState(navigator),proxy(PositioningSessionController.Scope::class.java),
            proxy(PositioningSessionController.Sources::class.java),proxy(PositioningSessionController.Mounting::class.java),
            proxy(PositioningSessionController.Effects::class.java),proxy(RouteGuidanceController.Diagnostics::class.java),
            {error("gps")},{error("compass")},{error("guidance")},{error("request")},{error("measurement clock")},{error("positioning UI")})
        val owner=FrameMotionEvidenceComposer({events+="positioning";positioning},{events+="route";navigator},object:FrameMotionEvidenceSource{
            override fun freshTrustedLocationOrNull(nowElapsedRealtimeMs:Long):TrustedLocation?{events+="location:$nowElapsedRealtimeMs";return location}
            override fun orientation():DeviceEarthOrientation?{events+="orientation";return null}
            override fun elapsedRealtimeMs():Long{events+="clock";return 500}
            override fun lifecycleSnapshot():WalkSessionSnapshot?{events+="lifecycle";return lifecycle}
            override fun gatewaySnapshot():GatewaySessionProcessSnapshot{events+="gateway";return gateway}
            override fun stepTrackerOrNull():AndroidStepTracker?{events+="step";return null}
            override val stepTrackingEpoch:WalkRuntimeEpoch? get(){events+="step-epoch";return null}
            override fun activityRecognitionPermissionGranted():Boolean{events+="permission";return false}
            override fun synchronizeCalibrationScope():WalkingCalibrationScope{events+="scope";return positioning.synchronizeWalkingCalibrationScope(null,null)}
        },object:WalkingCalibrationDisplay{
            override fun guidanceStatusPublished():Boolean{events+="published";return published}
            override fun guidancePageCurrent():Boolean{events+="page";return guidancePage}
            override fun updateCurrentGuidance(message:String){events+="presentation";messages+=message}
            override fun onMainThread()=mainThread
            override fun onUiThread(action:()->Unit){queued+=action}
        },PositioningDiagnosticsPresenter())
    }
    private class FakeFrame(private val events:MutableList<String>):Frame(){
        override fun getTimestamp():Long{events+="frame-time";return 99}
        override fun getCamera():Camera{events+="camera";return object:Camera(){override fun getTrackingState():TrackingState{events+="tracking";return TrackingState.PAUSED}}}
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> proxy(type:Class<T>):T = Proxy.newProxyInstance(type.classLoader,arrayOf(type)){_,method,_->
            when(method.returnType){java.lang.Boolean.TYPE->false;java.lang.Integer.TYPE->0;java.lang.Long.TYPE->0L;java.lang.Float.TYPE->0f;java.lang.Double.TYPE->0.0;else->null}
        } as T
    }
}
