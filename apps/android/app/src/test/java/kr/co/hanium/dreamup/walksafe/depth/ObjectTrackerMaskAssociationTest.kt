package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** Analytic synthetic evidence only; these tests do not measure ARCore or real-video accuracy. */
class ObjectTrackerMaskAssociationTest {
    private fun mask(x0:Int=10,x1:Int=50,y0:Int=20,y1:Int=70,w:Int=100,h:Int=100):BinaryImageMask {
        val bytes=ByteArray((w*h+7)/8)
        for(y in y0 until y1)for(x in x0 until x1) {
            val bit=y*w+x;bytes[bit/8]=(bytes[bit/8].toInt() or (1 shl (bit%8))).toByte()
        }
        return BinaryImageMask.fromPackedRoi(w,h,0,0,w,h,bytes)
    }
    private fun geometry(name:String=UNNAMED_OBSTACLE_CLASS):ObjectGeometry {
        val box=RectNorm(.05f,.1f,.8f,.65f)
        return ObjectGeometry(name,.95f,box,bboxPolygon(box,0f),box.area,Point2(.5f,.5f),Point2(.5f,.75f))
    }
    private fun evidence(at:Long,m:BinaryImageMask,depth:Float?=null,epoch:String?=null):MaskAssociationEvidence {
        val ns=9_000_000_000L+at*1_000_000L
        return MaskAssociationEvidence(at,ns,m,depth?.let {z -> SpatialAssociationEvidence(z,
            Vec3(0f,0f,-z),pose(at),ns,.9f,DepthSource.ARCORE_RAW_DEPTH)},epoch)
    }
    private fun input(index:Int,at:Long,m:BinaryImageMask,depth:Float?=null,epoch:String?=null)=
        IndexedObjectGeometry(index,geometry(),evidence(at,m,depth,epoch))
    private fun pose(at:Long,cameraZ:Float=0f)=CameraPoseEvidence(42L,at,0f,0f,cameraZ,0f,0f,-1f,
        CameraImageProjection(100,100,60f,60f,50f,50f,1f,0f,0f,0f,1f,0f))
    private fun record(t:ObjectTracker,s:TrackState,at:Long,z:Float,worldZ:Float=-z,cameraZ:Float=0f,raw:Long=9_000_000_000L+at*1_000_000L)=
        t.recordDistance(s,z,DepthSource.ARCORE_RAW_DEPTH,.9f,at,pose(at,cameraZ),Vec3(0f,0f,worldZ),raw,true)

    @Test fun packedRoiIoUAgreesWithIndependentPixelTruthAcrossWordBoundaries() {
        val random=Random(6712)
        repeat(100) {
            val w=130;val h=19;val left=random.nextInt(65);val rw=1+random.nextInt(w-left)
            val source=BooleanArray(rw*h) {random.nextBoolean()};val bytes=ByteArray((source.size+7)/8)
            source.forEachIndexed {bit,on->if(on)bytes[bit/8]=(bytes[bit/8].toInt() or (1 shl(bit%8))).toByte()}
            val a=BinaryImageMask.fromPackedRoi(w,h,left,0,rw,h,bytes)
            val b=mask(2,125,3,17,w,h)
            var expected=0
            for(y in 0 until h)for(x in 0 until w) {
                val truth=x>=left && x<left+rw && source[y*rw+x-left]
                assertEquals(truth,a.contains(x,y));if(truth&&x in 2 until 125&&y in 3 until 17)expected++
            }
            assertEquals(source.count {it},a.area);assertEquals(expected,a.intersectionArea(b));assertEquals(expected,b.intersectionArea(a))
            assertEquals(expected.toFloat()/(a.area+b.area-expected),a.iou(b),1e-6f)
            bytes.fill(0);assertEquals(source.count {it},a.area)
        }
    }
    @Test fun holesDisconnectedAndSinglePixelArePreserved() {
        val b=ByteArray(4);for(i in listOf(0,4,16,24))b[i/8]=(b[i/8].toInt() or(1 shl(i%8))).toByte()
        val m=BinaryImageMask.fromPackedRoi(5,5,0,0,5,5,b)
        assertEquals(4,m.area);assertFalse(m.contains(2,2));assertEquals(1f,m.iou(m),0f)
        val one=BinaryImageMask.fromPackedRoi(100,100,63,4,1,1,byteArrayOf(1))
        assertTrue(one.contains(63,4));assertEquals(1,one.intersectionArea(one))
    }
    @Test fun exactMasksPreserveIdentityDespiteIdenticalBoundingBoxes() {
        val t=ObjectTracker();var initial=listOf<String>()
        for(i in 0..6) {
            val at=1000L+i*250L;val a=t.updateMaskAwareWithAssignments(listOf(input(0,at,mask(10,30)),input(1,at,mask(60,80))),at)
            assertTrue(a.all {it.track!=null});val ids=a.map {it.track!!.trackId}
            if(i==0)initial=ids else assertEquals(initial,ids)
        }
    }
    @Test fun optionalFreshDepthAndPoseResolveNearEqualMaskOverlap() {
        val t=ObjectTracker();val old=t.updateMaskAwareWithAssignments(listOf(input(0,1000,mask(10,50),2f),input(1,1000,mask(40,80),6f)),1000)
        val now=t.updateMaskAwareWithAssignments(listOf(input(0,1250,mask(24,64),2f),input(1,1250,mask(26,66),6f)),1250)
        assertEquals(old.map{it.track!!.trackId},now.map{it.track!!.trackId})
        assertTrue(now.all {it.status==MaskAssociationStatus.MATCHED})
    }
    @Test fun ambiguousCrossingKeepsOldHistoryButPublishesNoGuessedIdentityOrMotion() {
        val t=ObjectTracker();var old=listOf<MaskTrackAssignment>()
        for(i in 0..6) {
            val at=1000L+i*250L;old=t.updateMaskAwareWithAssignments(listOf(input(0,at,mask(10,50)),input(1,at,mask(40,80))),at)
            old.forEach {assertTrue(record(t,it.track!!,at,6f-i*.25f))}
        }
        val histories=old.map {it.track!!.distanceHistory.size};val ids=old.map {it.track!!.trackId}
        val ambiguous=t.updateMaskAwareWithAssignments(listOf(input(0,2750,mask(25,65)),input(1,2750,mask(25,65))),2750)
        assertEquals(2,ambiguous.size);assertTrue(ambiguous.all {it.track==null&&it.status==MaskAssociationStatus.AMBIGUOUS})
        assertEquals(histories,old.map {it.track!!.distanceHistory.size})
        old.forEach {assertFalse(record(t,it.track!!,2750,1f));assertEquals(ObjectMovementDirection.UNKNOWN,ObjectMotionPolicy.estimate(it.track!!).direction)}
        val recovered=t.updateMaskAwareWithAssignments(listOf(input(0,3000,mask(10,50)),input(1,3000,mask(40,80))),3000)
        assertEquals(ids,recovered.map {it.track!!.trackId})
        recovered.forEach {assertTrue(it.track!!.distanceHistory.isEmpty());assertTrue(record(t,it.track!!,3000,2f));assertNull(t.approachKinematics(it.track!!,2f,DepthSource.ARCORE_RAW_DEPTH).timeToCollisionMs)}
    }
    @Test fun missingObjectHoldsIdentityForBoundedGapAndRequiresNewMotionWarmup() {
        val t=ObjectTracker();val old=t.updateMaskAwareWithAssignments(listOf(input(0,1000,mask())),1000).single().track!!
        assertTrue(record(t,old,1000,5f));t.updateMaskAwareWithAssignments(emptyList(),1250)
        val returned=t.updateMaskAwareWithAssignments(listOf(input(0,1500,mask())),1500).single().track!!
        assertEquals(old.trackId,returned.trackId);assertTrue(returned.distanceHistory.isEmpty())
        t.updateMaskAwareWithAssignments(emptyList(),1750)
        val expired=t.updateMaskAwareWithAssignments(listOf(input(0,3251,mask())),3251).single().track!!
        assertNotEquals(old.trackId,expired.trackId)
    }
    @Test fun representativeEpochChangeClearsMotionAndScopedAssociationSnapshot() {
        val t=ObjectTracker();var old:TrackState?=null
        for(i in 0..6) {val at=1000L+i*250L;old=t.updateMaskAwareWithAssignments(listOf(input(0,at,mask(),6f-i*.25f,"surface-A")),at).single().track!!;assertTrue(record(t,old,at,6f-i*.25f))}
        val id=old!!.trackId;val next=t.updateMaskAwareWithAssignments(listOf(input(0,2750,mask(),2f,"surface-B")),2750).single().track!!
        assertEquals(id,next.trackId);assertTrue(next.distanceHistory.isEmpty());assertTrue(record(t,next,2750,2f))
        assertNull(t.approachKinematics(next,2f,DepthSource.ARCORE_RAW_DEPTH).timeToCollisionMs)
        assertTrue(next.resetMetricAndSpatialHistoryPreservingTrackId(2750));assertNull(next.latestMaskAssociationEvidence!!.spatial)
        assertEquals(1f,next.latestMaskAssociationEvidence!!.mask.iou(mask()),0f)
    }
    @Test fun partialAndUnknownMasksCanKeep2dIdWithoutAnySpatialEvidence() {
        val t=ObjectTracker();var id:String?=null
        for(i in 0..6) {val at=1000L+i*250L;val s=t.updateMaskAwareWithAssignments(listOf(input(0,at,mask())),at).single().track!!
            if(id==null)id=s.trackId else assertEquals(id,s.trackId)
            assertTrue(s.resetMetricAndSpatialHistoryPreservingTrackId(at));assertTrue(s.distanceHistory.isEmpty());assertEquals(ObjectMovementDirection.UNKNOWN,ObjectMotionPolicy.estimate(s).direction)}
    }
    @Test fun duplicateAndReversedRawCannotSupplyMotionWhileMaskIdentityContinues() {
        for(reverse in listOf(false,true)) {
            val t=ObjectTracker();var s:TrackState?=null
            for(i in 0..6) {val at=1000L+i*250L;s=t.updateMaskAwareWithAssignments(listOf(input(0,at,mask())),at).single().track!!
                record(t,s,at,6f-i*.25f,raw=if(reverse&&i==6)1L else 9_000_000_000L)}
            assertEquals(ObjectMovementDirection.UNKNOWN,ObjectMotionPolicy.estimate(s!!).direction)
            assertNull(t.approachKinematics(s!!,4.5f,DepthSource.ARCORE_RAW_DEPTH).timeToCollisionMs)
        }
    }
    @Test fun invalidCameraFrameDoesNotTransferIdsOrDropDetectionRecords() {
        val t=ObjectTracker();val first=input(0,1000,mask());t.updateMaskAwareWithAssignments(listOf(first),1000)
        val bad=input(0,1250,mask()).copy(association=evidence(1250,mask()).copy(cameraTimestampNs=first.association!!.cameraTimestampNs))
        val r=t.updateMaskAwareWithAssignments(listOf(bad),1250);assertEquals(1,r.size);assertNull(r.single().track)
        val mismatch=input(0,1500,mask()).copy(association=evidence(1499,mask()))
        assertNull(t.updateMaskAwareWithAssignments(listOf(mismatch),1500).single().track)
    }
    @Test fun objectApproachAndUserApproachRemainDistinctUnderMaskAssociations() {
        for(userMoves in listOf(false,true)) {
            val t=ObjectTracker();var s:TrackState?=null
            for(i in 0..6) {val at=1000L+i*250L;val z=6f-i*.25f
                s=t.updateMaskAwareWithAssignments(listOf(input(0,at,mask())),at).single().track!!
                assertTrue(record(t,s,at,z,worldZ=if(userMoves)-6f else -z,cameraZ=if(userMoves)-i*.25f else 0f))}
            val k=t.approachKinematics(s!!,4.5f,DepthSource.ARCORE_RAW_DEPTH)
            assertEquals(if(userMoves)ObjectMotion.USER_APPROACHING_STATIONARY else ObjectMotion.OBJECT_APPROACHING,k.objectMotion)
        }
    }
}
