package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import com.google.ar.core.*
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointLumaCapture
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Real recognition policy, luma copies, Unknown lease checks and unopened service; no inference/AR field claim. */
class UnknownFrameCaptureBridgeTest {
    @Test fun constructionAndNullInputDoNotInitializeAnySdkRuntimeOrClock()=withFixture { f->
        assertTrue(f.events.isEmpty());f.submit(null);assertTrue(f.events.isEmpty())
    }
    @Test fun absentOwnedLumaDoesNotQueryFeatureFrameOrLazyUnknown()=withFixture{f->
        f.submit(f.input(luma=false));assertTrue(f.events.isEmpty())
    }
    @Test fun disabledObstacleFeatureDoesNotReadFrameTimestamp()=withFixture{f->
        f.enabled=false;f.submit(f.input());assertEquals(listOf("feature"),f.events)
    }
    @Test fun missingActualCpuClockStopsBeforeTrackingAuthorityAndCaptureLease()=withFixture{f->
        f.submit(f.input().copy(cameraTimestampNs=null))
        assertEquals(listOf("feature","frame","recognition"),f.events)
    }
    @Test fun foreignDepthClockCannotBorrowSameFrameVisualPermission()=withFixture{f->
        f.snapshot=DepthFrameSnapshot(99,null,null,null,cameraImageTimestampNs=78)
        f.submit(f.input());assertEquals(listOf("feature","frame","recognition"),f.events)
    }
    @Test fun wrongLumaFrameOrOriginalCaptureClockCannotEnterUnknownOwner()=withFixture{f->
        f.submit(f.input(tokenFrame=98));assertFalse(f.events.contains("unknown"));assertFalse(f.events.contains("ar-generation"))
        f.events.clear();f.submit(f.input(capturedNs=101_000_000));assertFalse(f.events.contains("unknown"));assertFalse(f.events.contains("ar-generation"))
    }
    @Test fun actualProcessingLeaseKeepsOriginalIdentityFrameReceiptAndGapDeadlineUnderSameLock()=withFixture{f->
        f.outputAllowed=false;f.lastValid=50;f.submit(f.input())
        val lease=requireNotNull(f.observedLease);assertSame(f.originalLease,lease.arLease.originalLease)
        assertEquals(7L,lease.arLease.generation);assertEquals(99L,lease.frameId);assertEquals(100L,lease.capturedAtElapsedRealtimeMs)
        assertEquals(650L,lease.gapDeadlineElapsedRealtimeMs)
        assertEquals(listOf("ar-generation","frame","metric-allowed","last-valid"),f.lockedBridgeEvents)
        assertEquals(5,f.events.count{it=="frame"})
        assertFalse(f.events.contains("geometry"));assertFalse(f.events.contains("motion"));assertFalse(f.events.contains("model"))
        assertFalse(f.coordinator.frameCopyInFlight)
    }
    @Test fun availableMetricLeaseDoesNotReadOrManufactureGapDeadline()=withFixture{f->
        f.submit(f.input());assertNull(requireNotNull(f.observedLease).gapDeadlineElapsedRealtimeMs)
        assertFalse(f.events.contains("last-valid"));assertTrue(f.events.contains("unknown"))
    }
    @Test fun untrackedSourceStopsBeforeMetricLockAndUnknownCapture()=withFixture{f->
        f.tracking=false;f.submit(f.input());assertFalse(f.events.contains("ar-generation"));assertFalse(f.events.contains("unknown"))
    }
    @Test fun sharedFrozenInputMappingRetainsOriginalOwnedImageLumaQualityAndClocks()=withFixture{f->
        val input=f.input();val frozen=input.toUnknownCaptureFrameInput()
        assertSame(input.cameraFrame,frozen.cameraFrame);assertSame(input.unknownLuma,frozen.unknownLuma)
        assertSame(input.qualityObservation,frozen.qualityObservation);assertSame(input.timestampEvidence,frozen.timestampEvidence)
        assertEquals(input.cameraTimestampNs,frozen.cameraTimestampNs);assertEquals(input.imageQuarterTurns,frozen.imageQuarterTurns)
        assertEquals(input.geometryVersion,frozen.geometryVersion);assertTrue(f.events.isEmpty())
    }
    private class Fixture {
        val events=mutableListOf<String>();val lockedBridgeEvents=mutableListOf<String>();val metricLock=Any();val originalLease=Any()
        val epoch=WalkRuntimeEpoch("bridge",1);var enabled=true;var tracking=true;var outputAllowed=true;var lastValid=0L
        var observedLease:UnknownCaptureProcessingLease?=null
        var snapshot=DepthFrameSnapshot(99,null,null,null,cameraImageTimestampNs=77)
        val service:FastSamRuntimeService<UnknownRuntimeAttachment> = UnknownFrameBridgeServiceFixture.unopened()
        val clock=object:UnknownCaptureClock{
            override fun elapsedRealtimeMs()=100L;override fun elapsedRealtimeNanos()=100_000_000L;override fun cpuNanos()=0L
        }
        val services=UnknownRuntimeServiceOwner(never(UnknownRuntimeProfileRepository::class.java),never(UnknownRuntimeFactory::class.java),
            never(UnknownRuntimeServiceAuthority::class.java),clock,Executor{error("native scheduling")},{error("UI scheduling")},{},{},{_,_->},{})
        val coordinator=UnknownCaptureCoordinator(Any(),Executor{error("frame scheduling")},{error("UI scheduling")},object:UnknownCaptureScopeReader{
            override fun runtime()=UnknownCaptureRuntimeScope(epoch,true,false,4,7,2,9,3,.65f,null)
            override fun withDepthAuthority(lease:UnknownCaptureProcessingLease,check:(UnknownCaptureDepthAuthority)->Boolean):Boolean=synchronized(metricLock){
                observedLease=lease;check(UnknownCaptureDepthAuthority(99,tracking){lease.arLease.originalLease===originalLease&&lease.arLease.generation==7L})
            }
            override fun ownsFrame(generation:Int,epoch:WalkRuntimeEpoch)=generation==4&&epoch==this@Fixture.epoch
            override fun execution()=UnknownCaptureExecutionObservation(false)
            override fun metricOutputAllowsWork(expectedArGeneration:Long)=outputAllowed&&expectedArGeneration==7L
            override fun cameraProfile():ApprovedCameraFrameQualityProfile?=null
            override fun powerSaveMode()=false
        },clock,never(UnknownCaptureObserver::class.java),services){false}
        val frame=object:Frame(){
            override fun getTimestamp():Long{event("frame");return 99}
            override fun getCamera():Camera{event("camera");return object:Camera(){override fun getTrackingState():TrackingState{event("tracking");return if(tracking)TrackingState.TRACKING else TrackingState.PAUSED}}}
        }
        val bridge=UnknownFrameCaptureBridge(metricLock,object:UnknownFrameAdmission<Any>{
            override fun obstacleDetectionEnabled():Boolean{event("feature");return enabled}
            override fun recognitionAllowsVisualWork(cpuTimestampNs:Long?,frameTimestampNs:Long,snapshot:DepthFrameSnapshot,depthAllowsWork:()->Boolean):Boolean{
                event("recognition");return FrameRecognitionAdmission.allowsVisualWork(cpuTimestampNs,frameTimestampNs,snapshot,depthAllowsWork)
            }
            override fun visualDepthAllowsWork(lease:Any,frameTimestampNs:Long,tracking:Boolean):Boolean{event("visual");return lease===originalLease&&frameTimestampNs==99L&&tracking}
            override fun arGeneration(lease:Any):Long{assertTrue(Thread.holdsLock(metricLock));event("ar-generation");return 7}
            override val metricOutputAllowed:Boolean get(){assertTrue(Thread.holdsLock(metricLock));event("metric-allowed");return outputAllowed}
            override val lastValidMetricFrameAtMs:Long get(){assertTrue(Thread.holdsLock(metricLock));event("last-valid");return lastValid}
            override fun modelGeneration():Long{event("model");return 9}
        },{event("unknown");coordinator},{event("geometry");error("unopened source cannot freeze")},{event("motion");error("unopened source cannot calculate motion")})
        fun event(name:String){events+=name;if(Thread.holdsLock(metricLock))lockedBridgeEvents+=name}
        fun input(luma:Boolean=true,tokenFrame:Long=99,capturedNs:Long=100_000_000):FrameRecognitionInput{
            val y=ByteBuffer.wrap(ByteArray(4){77});val uv=ByteBuffer.wrap(byteArrayOf(128.toByte()))
            val camera=OwnedYuvFrame.copyOf(2,2,y,uv,uv,2,1,1,1)
            val token=FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,1,tokenFrame,99,77,capturedNs,"7:2:2x2:q0",2,2)
            val captured=requireNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(token,0,77,2,2,y,2,1))
            return FrameRecognitionInput(camera,77,null,CameraFrameQualityObservation(epoch,100,true,.5,0.0,null,null),0,2,
                if(luma)UnknownCaptureLumaInput(service,captured)else null)
        }
        fun submit(input:FrameRecognitionInput?){bridge.scheduleUnknownDetectionIfDue(input,frame,snapshot,100,4,epoch,originalLease)}
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> never(type:Class<T>):T=Proxy.newProxyInstance(type.classLoader,arrayOf(type)){_,method,_->error("unexpected ${type.simpleName}.${method.name}")} as T
        private fun withFixture(body:(Fixture)->Unit){val f=Fixture();try{body(f)}finally{f.service.close();assertTrue(f.service.releasedFuture().isDone)}}
    }
}
