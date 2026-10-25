package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import java.io.OutputStream
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.depth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrameCaptureArtifactsTest {
    @Test fun constructorDoesNotReadModelBuilderBitmapOrClock() { assertTrue(Fixture().events.isEmpty()) }
    @Test fun invalidSizeOrMissingPixelsDoesNotAcquireBitmap() {
        val f = Fixture()
        listOf(ArgbImage(0,2,intArrayOf(1)), ArgbImage(2,-1,intArrayOf(1)), ArgbImage(2,2,intArrayOf())).forEach {
            assertNull(f.owner.toScaledJpeg(it,100,90))
        }
        assertTrue(f.events.isEmpty())
    }
    @Test fun fullSizeEncodingOwnsOneBitmapAndClampsQuality() {
        val f = Fixture(); assertArrayEquals(byteArrayOf(1,2), f.owner.toScaledJpeg(f.image,20,200))
        assertEquals(listOf("create", "compress:original:100", "recycle:original"), f.events)
        assertSame(f.image, f.seenImage)
    }
    @Test fun scaledEncodingReleasesOutputBeforeOriginal() {
        val f = Fixture(); f.owner.toScaledJpeg(f.image,2,-1)
        assertEquals(listOf("create", "scale:2:1:true", "compress:scaled:1", "recycle:scaled", "recycle:original"), f.events)
    }
    @Test fun scaledPlatformReturningSameInstanceIsOnlyRecycledOnce() {
        val f = Fixture(); f.sameScaled = true; f.owner.toScaledJpeg(f.image,2,60)
        assertEquals(listOf("create", "scale:2:1:true", "compress:original:60", "recycle:original"), f.events)
    }
    @Test fun compressionFailureKeepsOriginalCauseAndRecyclesBoth() {
        val f = Fixture(); val failure=IllegalStateException("compress"); f.compressFailure=failure
        try { f.owner.toScaledJpeg(f.image,2,60); fail("expected failure") } catch (caught: IllegalStateException) { assertSame(failure,caught) }
        assertEquals(listOf("recycle:scaled", "recycle:original"),f.events.takeLast(2))
    }
    @Test fun falseCompressionStillReturnsOriginalProducedBytes() {
        val f = Fixture(); f.compressResult=false
        assertArrayEquals(byteArrayOf(1,2),f.owner.toScaledJpeg(f.image,20,90))
    }
    @Test fun scaleFailureRemainsOutsideOriginalFinallyBoundary() {
        val f = Fixture(); val failure=IllegalStateException("scale");f.scaleFailure=failure
        try { f.owner.toScaledJpeg(f.image,2,60); fail("expected failure") } catch(caught: IllegalStateException){assertSame(failure,caught)}
        assertEquals(listOf("create","scale:2:1:true"),f.events)
    }
    @Test fun outputRecycleFailureKeepsOriginalTailSuppression() {
        val f = Fixture(); val failure=IllegalStateException("release");f.recycleFailure=failure
        try { f.owner.toScaledJpeg(f.image,2,60); fail("expected failure") } catch(caught: IllegalStateException){assertSame(failure,caught)}
        assertEquals("recycle:scaled",f.events.last());assertFalse(f.events.contains("recycle:original"))
    }
    @Test fun emptyDetectorResultDoesNotReadReportKeyOrThreshold() {
        val f = Fixture();val json=JSONObject(f.owner.buildFrameCaptureMetadataJson(123,4,2,8,AndroidDetectionResult.empty(),false))
        assertEquals(123L,json.getLong("frame_timestamp_ms"));assertEquals(0,json.getInt("detection_count"))
        assertEquals(listOf("loaded","fallback","reason"),f.events)
        assertFalse(json.has("top_detection_threshold_used"));assertFalse(json.has("latitude"));assertFalse(json.has("depth"))
    }
    @Test fun originalResultMetadataPreservesSortedCapBBoxTimingAndConditionalThreshold() {
        val f = Fixture()
        val detections=(0..24).map { DetectionCandidate("object$it",it/25f,RectNorm(.1f,.2f,.3f,.4f)) }
        val result=AndroidDetectionResult(detections,AndroidDetectorTiming(customInferenceMs=11,cocoInferenceMs=12,
            modelKey="frame-model",modelPreprocessMs=13,modelInferenceMs=14,modelParseMs=15,
            completedModels=listOf("custom"),skippedModels=listOf("coco")),partial=true)
        val json=JSONObject(f.owner.buildFrameCaptureMetadataJson(100,4,2,20,result,true))
        assertEquals(25,json.getInt("detection_count"));assertEquals(20,json.getJSONArray("detections").length())
        assertEquals("object24",json.getJSONArray("detections").getJSONObject(0).getString("class_name"))
        assertEquals("object5",json.getJSONArray("detections").getJSONObject(19).getString("class_name"))
        assertEquals(.3,json.getJSONObject("top_detection_bbox").getDouble("width"),.000001)
        assertEquals(11L,json.getLong("detector_custom_inference_ms"));assertEquals(15L,json.getLong("detector_model_parse_ms"))
        assertEquals("manual_jpeg_no_gps_no_depth_raw_full_detector",json.getString("capture_policy"))
        assertEquals(.72,json.getDouble("top_detection_threshold_used"),.000001)
        assertEquals(listOf("loaded","fallback","reason","report-key","threshold:report-model:object24"),f.events)
        assertEquals("loaded-one",json.getString("detector_loaded_model_key"));assertEquals("reason-three",json.getString("detector_model_load_reason"))
    }
    @Test fun unavailableOptionalTimingAndThresholdStayAbsent() {
        val f=Fixture();f.threshold=null
        val result=AndroidDetectionResult(listOf(DetectionCandidate("person",.5f,RectNorm(0f,0f,.1f,.1f))))
        val json=JSONObject(f.owner.buildFrameCaptureMetadataJson(1,4,2,0,result,false))
        assertFalse(json.has("detector_custom_inference_ms"));assertFalse(json.has("detector_model_key"));assertFalse(json.has("top_detection_threshold_used"))
    }
    @Test fun captureLogUsesActualBuilderOriginalOverlayFactsAndLiveReadOrder() {
        val events=mutableListOf<String>()
        val builder=kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics.CaptureMetadataEntryBuilder(
            object:kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics.CaptureMetadataReadings{
                override val detectorLoadedModelKey:String? get(){events+="loaded";return "actual-model"}
                override val detectorModelFallbackUsed:Boolean get(){events+="fallback";return true}
                override val detectorLoadReason:String? get(){events+="reason";return "actual-reason"}
                override val surfaceWidth:Int get(){events+="width";return 800}
                override val surfaceHeight:Int get(){events+="height";return 600}
                override fun displayRotation():Int{events+="rotation";return 2}
            },400,400)
        val owner=FrameCaptureArtifacts(object:FrameCaptureBitmaps<Any>{
            override fun create(image:ArgbImage):Any=error("log bitmap")
            override fun scale(bitmap:Any,width:Int,height:Int,filter:Boolean):Any=error("log scale")
            override fun compress(bitmap:Any,quality:Int,output:OutputStream):Boolean=error("log compress")
            override fun recycle(bitmap:Any)=error("log recycle")
        },object:FrameCaptureModelReadings{
            override val loadedModelKey:String? get()=error("manual metadata")
            override val fallbackUsed:Boolean get()=error("manual metadata")
            override val loadReason:String? get()=error("manual metadata")
            override val reportModelKey:String? get()=error("manual metadata")
            override fun resolveReportThreshold(modelKey:String?,className:String):Float?=error("manual metadata")
        },{events+="builder";builder},20)
        assertTrue(events.isEmpty())
        val overlay=kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameOverlayDiagnostics("actual",3,2,5,4,1,2,true,true,30,10,"retired-marker")
        val snapshot=kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot.empty()
        val entry=owner.buildCaptureLogEntry(321,500,snapshot,overlay,emptyList(),DepthFrameSnapshot(321,null,null,null),null,"original-transform","original-fallback")
        assertEquals(listOf("builder","loaded","fallback","reason","width","height","rotation"),events)
        assertEquals(321L,entry.frameTimestampMs);assertEquals("actual",entry.overlaySelection)
        assertEquals(5,entry.overlayBoxCount);assertEquals(1,entry.overlayHeldTactileBoxCount)
        assertEquals(true,entry.overlaySnapshotPartial);assertEquals("retired-marker",entry.overlayStaleReason)
        assertEquals("original-transform",entry.depthTransformPath);assertEquals("original-fallback",entry.fallbackReason)
        assertEquals("actual-model",entry.detectorLoadedModelKey);assertEquals(800,entry.previewWidth)
    }
    private class Fixture {
        val events=mutableListOf<String>();val image=ArgbImage(4,2,IntArray(8));val original=Any();val scaled=Any()
        var seenImage:ArgbImage?=null;var sameScaled=false;var compressResult=true;var threshold:Float?=.72f
        var compressFailure:RuntimeException?=null;var scaleFailure:RuntimeException?=null;var recycleFailure:RuntimeException?=null
        val owner=FrameCaptureArtifacts(object:FrameCaptureBitmaps<Any>{
            override fun create(image:ArgbImage):Any {events+="create";seenImage=image;return original}
            override fun scale(bitmap:Any,width:Int,height:Int,filter:Boolean):Any {assertSame(original,bitmap);events+="scale:$width:$height:$filter";scaleFailure?.let{throw it};return if(sameScaled)original else scaled}
            override fun compress(bitmap:Any,quality:Int,output:OutputStream):Boolean {events+="compress:${if(bitmap===original)"original" else "scaled"}:$quality";compressFailure?.let{throw it};output.write(byteArrayOf(1,2));return compressResult}
            override fun recycle(bitmap:Any){events+="recycle:${if(bitmap===original)"original" else "scaled"}";recycleFailure?.let{throw it}}
        },object:FrameCaptureModelReadings{
            override val loadedModelKey:String? get(){events+="loaded";return "loaded-one"}
            override val fallbackUsed:Boolean get(){events+="fallback";return true}
            override val loadReason:String? get(){events+="reason";return "reason-three"}
            override val reportModelKey:String? get(){events+="report-key";return "report-model"}
            override fun resolveReportThreshold(modelKey:String?,className:String):Float? {events+="threshold:$modelKey:$className";return threshold}
        },{error("log builder must stay lazy")},20)
    }
}
