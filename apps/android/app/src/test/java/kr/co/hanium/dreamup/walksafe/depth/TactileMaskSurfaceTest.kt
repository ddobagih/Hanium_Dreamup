package kr.co.hanium.dreamup.walksafe.depth

import com.google.ar.core.Frame
import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TactileMaskSurfaceTest {
    @Test fun everyMeasuredCellKeepsOwnDepthAndSubpixelOriginBeyondTheOld256Bound() {
        val f = TactileSurfaceFixture { x,y -> x in 20..43 && y in 22..47 }
        val values = f.depths().also { it[35*64+32] -= 10 }
        val surface = f.project(values = values).surface!!
        assertTrue(surface.samples.size > 256)
        assertTrue(surface.samples.map { it.axialDepthM }.distinct().size > 1)
        assertTrue(surface.samples.any { it.depthCellIndex == 35*64+32 })
        surface.samples.forEach { s ->
            assertEquals(values[s.depthCellIndex]/1000f,s.axialDepthM,0f)
            assertEquals(s.depthX+.5,s.imageX,.000001)
            assertEquals(s.depthY+.5,s.imageY,.000001)
            assertEquals(f.pose.objectCenterInAnchor(Point2((s.imageX/64).toFloat(),(s.imageY/64).toFloat()),s.axialDepthM),s.pointInAnchor)
        }
        assertEquals(f.token.cpuImageTimestampNs,surface.depthTimestampNs)
        assertTrue(surface.depthMatchesCameraImage)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH,surface.source)
    }

    @Test fun fullFallbackRetainsOwnCellsAndDoesNotInventRawConfidence() {
        val f=TactileSurfaceFixture()
        val surface=f.project(full=true).surface!!
        assertEquals(DepthSource.ARCORE_FULL_DEPTH,surface.source)
        assertTrue(surface.samples.all { it.pixelConfidence==null })
        assertTrue(surface.samples.map { it.axialDepthM }.distinct().size>1)
        assertEquals(f.token.cpuImageTimestampNs,surface.depthTimestampNs)
    }

    @Test fun foreignEpochGeometryRotationAndSubmillisecondExpiryCannotProduceSurface() {
        val f=TactileSurfaceFixture()
        val foreign=FastSamFrameToken(f.token.source,2,f.token.frameId,f.token.cameraTimestampNs,
            f.token.cpuImageTimestampNs,f.token.capturedElapsedNs,"foreign",64,64)
        assertEquals(TactileSurfaceRejection.FOREIGN_CAPTURE,TactileMaskSurfaceProjector().project(f.candidate,
            f.frozen(token=foreign),f.decisionNs).rejection)
        assertEquals(TactileSurfaceRejection.FOREIGN_CAPTURE,TactileMaskSurfaceProjector().project(f.candidate,
            f.frozen(q=1),f.decisionNs).rejection)
        assertEquals(TactileSurfaceRejection.EXPIRED_CAPTURE,TactileMaskSurfaceProjector().project(f.candidate,
            f.frozen(),f.capture.originalDeadlineElapsedNs+1).rejection)
    }

    @Test fun originalSensorMaskIsNotRotatedAgainForAnyQuarterTurn() {
        for(q in 0..3) {
            val f=TactileSurfaceFixture(q=q)
            val surface=f.project().surface!!
            assertTrue(surface.samples.all { f.candidate.sensorMask.contains(it.imageX.toInt(),it.imageY.toInt()) })
            assertTrue(surface.samples.all { it.imageX in 26.0..39.0 })
        }
    }

    @Test fun capturedCropAndDifferentDepthGridUseInverseMappingAndOwnCellZ() {
        val f=TactileSurfaceFixture { x,y -> x in 18..45 && y in 22..47 }
        val surface=f.project(depthWidth=32,depthHeight=32,crop=.1).surface!!
        surface.samples.forEach { s ->
            assertEquals(2*(s.depthX+.5)-6.4,s.imageX,.000001)
            assertEquals(2*(s.depthY+.5),s.imageY,.000001)
            assertEquals(f.ownZ(s.imageY),s.axialDepthM,.001f)
        }
    }
}

/** Shared controlled source fixture, not a physical color/depth/semantic accuracy claim. */
internal class TactileSurfaceFixture(
    val q:Int=0,
    val foreground:(Int,Int)->Boolean={x,y -> x in 26..37 && y in 24..47},
) {
    val token=FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,1,7,9_000_000_000L,
        8_900_000_000L,100_000_000_000L,"surface-64-q$q",64,64)
    val decisionNs=token.capturedElapsedNs+50_000_000L
    val capture=TactileSemanticCapture(CameraImageTimestampEvidence(Any(),1,object:Frame(){},
        token.cameraTimestampNs,token.cpuImageTimestampNs),token,q,token.capturedElapsedNs+800_000_000L)
    val s=1f/sqrt(2f)
    val pose=CameraPoseEvidence(7,token.cameraTimestampNs/1_000_000L,0f,1.5f,0f,0f,-s,-s,
        CameraImageProjection(64,64,96f,96f,32f,32f,1f,0f,0f,0f,s,-s),Vec3(0f,1f,0f),
        listOf(CapturedHorizontalPlane(7,token.cameraTimestampNs/1_000_000L,
            listOf(Vec3(-5f,0f,-5f),Vec3(5f,0f,-5f),Vec3(5f,0f,5f),Vec3(-5f,0f,5f)))))
    val candidate:TactileSemanticMaskCandidate
    init {
        val bits=BitSet(64*64)
        for(y in 0 until 64) for(x in 0 until 64) if(foreground(x,y)) bits.set(y*64+x)
        val constructor=InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible=true }
        val mask=constructor.newInstance(64,64,0,0,64,64,bits.cardinality(),17,0f,0f,64f,64f,.9f,bits) as InstanceMask
        val classes=TactileSemanticKind.values().map{it.className}
        val model=ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,"model/existing.tflite",768,
            classes,classes.toSet(),classes.associateWith{.25f},artifactSha256="d".repeat(64))
        val config=TwoModelRuntimeConfig("surface-fixture",null,model.key,null,model,null,null)
        val named=TactileNamedSemanticEvidence.fromResult(capture,AndroidDetectionResult(
            listOf(DetectionCandidate(TactileSemanticKind.LINE.className,.9f,RectNorm(0f,0f,1f,1f))),
            timing=AndroidDetectorTiming(modelKey=model.key)),TactileSemanticCoordinates.SENSOR_ORIGINAL)
        candidate=TactileSemanticMaskSelector(config).select(named,TactileInstanceMaskEvidence(capture,token,
            listOf(mask),TactileSemanticCoordinates.SENSOR_ORIGINAL),decisionNs).candidates.single()
    }
    fun ownZ(imageY:Double)=1.5f/(s+s*((imageY-32)/96).toFloat())
    fun depths(width:Int=64,height:Int=64)=IntArray(width*height) { i -> (ownZ((i/width+.5)*64/height)*1000).roundToInt() }
    fun frozen(values:IntArray?=null,full:Boolean=false,depthWidth:Int=64,depthHeight:Int=64,
               crop:Double=0.0,token:FastSamFrameToken=this.token,q:Int=this.q):FrozenUnknownDepthCapture {
        val z=values?:depths(depthWidth,depthHeight)
        val snapshot=DepthFrameSnapshot(token.cameraTimestampNs,
            DepthImage16(depthWidth,depthHeight,if(full) IntArray(z.size) else z),
            ConfidenceImage8(depthWidth,depthHeight,ByteArray(z.size){if(full) 0 else 255.toByte()}),
            if(full) DepthImage16(depthWidth,depthHeight,z) else null,
            rawDepthTimestampNs=token.cpuImageTimestampNs,cameraPoseEvidence=pose,
            fullDepthTimestampNs=if(full) token.cpuImageTimestampNs else null,
            cameraImageTimestampNs=token.cpuImageTimestampNs,rawConfidenceTimestampNs=token.cpuImageTimestampNs)
        return requireNotNull(FrozenUnknownDepthCapture.freeze(token,snapshot,
            doubleArrayOf(1.0/64,0.0,crop,0.0,1.0/64,0.0,0.0,0.0,1.0),token.frameId,imageQuarterTurns=q))
    }
    fun project(values:IntArray?=null,full:Boolean=false,depthWidth:Int=64,depthHeight:Int=64,crop:Double=0.0)=
        TactileMaskSurfaceProjector().project(candidate,frozen(values,full,depthWidth,depthHeight,crop),decisionNs,
            kr.co.hanium.dreamup.walksafe.navigation.surfaceContext(this))
}
