package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class AndroidTactileAsyncWarningRegionTest {
 private val context=WarningRegionContext(WalkRuntimeEpoch("review",0), "ar:geometry:100x100", 1)
 private val start=10000L
 private val box=RectNorm(.1f,.3f,.2f,.4f)
 private fun output(id:String, now:Long, level:MessageLevel=MessageLevel.STOP, message:String="정지 위험 $id")=TrackedObjectDepth(
  frameId=now*1000000L,timestampMs=now,trackId=id,className=if(id.startsWith("unknown")) "unnamed-obstacle" else "person",
  detectionConfidence=.95f,bboxNorm=box,polygonNorm=emptyList(),maskAreaNorm=box.area,centerNorm=box.center,bottomContactNorm=null,
  source=DepthSource.ARCORE_RAW_DEPTH,zDistanceM=1f,rayDistanceM=1f,groundDistanceM=1f,riskDistanceM=1f,
  validSampleCount=80,validSampleRatio=.9f,depthMedianM=1f,depthP20M=1f,depthIqrM=.05f,trend=Trend.STABLE,
  approachScore=0f,approachSpeedMps=null,timeToCollisionMs=null,
  confidence=DepthConfidenceBreakdown(.95f,1f,1f,1f,1f,1f,1f,1f),userFacing=UserFacingDepth(null,level,message),trackAgeFrames=20,trackStableMs=1900L)
 private fun frame(os:List<TrackedObjectDepth>)=MainActivity.TactileSnapshotFrameResult(null,emptyList(),false,TactileRouteGuidanceResult(os,null,TactileRouteDecision(LocalRouteMode.TMAP,null,"test",null)))
 private fun batch(now:Long, os:List<TrackedObjectDepth>)=UnknownObjectFeedbackBatch(now*1000000L,now,now*1000000L,now,WalkRuntimeEpoch("review",0),os)
 private fun coord(p:WalkSafeFeedbackPolicy)=AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(),p,TactileFrameFeedbackActuator {})
 private fun asynchronousScene(lagMs:Long,separate:Boolean=false):List<String> {
  val p=WalkSafeFeedbackPolicy();val c=coord(p);val delivered=mutableListOf<String>()
  var pending:Pair<FeedbackAction,Long>?=null
  for(now in start..start+3000 step 100) {
   pending?.takeIf {now-it.second>=200}?.let {(a,t)->assertTrue(p.confirmFeedbackDelivery(a.trackId,t,now));delivered+=a.trackId;pending=null}
   val primary=output("primary-fixed",now)
   val other=output("unknown:1:fixed",now-lagMs).let {if(separate)it.copy(bboxNorm=RectNorm(.7f,.3f,.2f,.4f))else it}
   val bs=listOf(batch(now-lagMs,listOf(other)))
   val d=c.dispatchFeedback(frame(listOf(primary)),false,true,now,independentFreshOutputs=bs,warningContext=context)
   d.action?.let {a->assertTrue(p.claimFeedbackDelivery(a.trackId,now));pending=a to now;println("ASYNC_RESERVE lag="+lagMs+" separated="+separate+" at="+now+" track="+a.trackId+" primaryFrame="+primary.frameId+" auxiliaryFrame="+other.frameId)}
  }
  println("ASYNC_COMPLETE lag="+lagMs+" separated="+separate+" completed="+delivered)
  return delivered
 }
 @Test fun auditAsyncCurrentPrimaryAnd100msOldUnknownSameFixedRegionDoesNotDuplicate() {
  val ids=asynchronousScene(100)
  assertFalse("One fixed region must not be spoken again under auxiliary ID because source capture lags current frame",ids.contains("unknown:1:fixed"))
 }
 @Test fun controlSameCaptureSingleFixedRegionStillCoalesces() {
  assertEquals(listOf("primary-fixed","primary-fixed"),asynchronousScene(0))
 }
 @Test fun controlSeparateFixedRegionsRemainIndependentDespite100msLag() {
  assertTrue(asynchronousScene(100,true).containsAll(listOf("primary-fixed","unknown:1:fixed")))
 }


 private val policy = WalkSafeFeedbackPolicy()
 private val coordinator = coord(policy)
 private fun observe(now: Long, primary: List<TrackedObjectDepth> = listOf(output("p",now)),
     batches: List<UnknownObjectFeedbackBatch> = emptyList(), ctx: WarningRegionContext? = context,
     stale: Boolean = false, allowed: Boolean = true) = coordinator.dispatchFeedback(
     frame(primary),stale,allowed,now,independentFreshOutputs=batches,warningContext=ctx)
 private fun unknown(at: Long = start, level: MessageLevel = MessageLevel.STOP) =
     batch(at,listOf(output("unknown:1:u",at,level)))

 @Test fun absentAnchorDoesNotTurnOverlappingDifferentCapturesIntoIdentity() {
  assertEquals(setOf("p","unknown:1:u"),observe(start+100,batches=listOf(unknown())).activeRiskTrackIds)
 }
 @Test fun ambiguousCapturePreservesBothPossibleObjectsEvenWhenOneLaterDisappears() {
  observe(start,primary=listOf(output("p",start),output("q",start)))
  assertEquals(setOf("p","unknown:1:u"),observe(start+100,batches=listOf(unknown())).activeRiskTrackIds)
 }
 @Test fun separateUnknownAlongsideRetainedMatchedBatchRemainsIndependent() {
  observe(start)
  val separate=output("unknown:1:v",start+100).copy(bboxNorm=RectNorm(.7f,.3f,.2f,.4f))
  val result=observe(start+100,batches=listOf(unknown(),batch(start+100,listOf(separate))))
  assertEquals(setOf("p","unknown:1:v"),result.activeRiskTrackIds)
 }
 @Test fun motionDirectionChangePreservesIndependentWarning() {
  observe(start)
  val current=output("p",start+100).copy(motionEstimate=ObjectMotionEstimate(direction=ObjectMovementDirection.LEFT))
  assertEquals(setOf("p","unknown:1:u"),observe(start+100,listOf(current),listOf(unknown())).activeRiskTrackIds)
 }
 @Test fun movingOutOfOriginalRegionPreservesIndependentWarning() {
  observe(start)
  val current=output("p",start+100).copy(bboxNorm=RectNorm(.5f,.3f,.2f,.4f))
  assertEquals(setOf("p","unknown:1:u"),observe(start+100,listOf(current),listOf(unknown())).activeRiskTrackIds)
 }
 @Test fun missingPrimaryBreaksContinuityWhenSameTrackReturns() {
  observe(start)
  observe(start+50,emptyList())
  assertEquals(setOf("p","unknown:1:u"),observe(start+100,batches=listOf(unknown())).activeRiskTrackIds)
 }
 @Test fun intermediateAmbiguityBreaksContinuityEvenWhenCurrentBoxesSeparateAgain() {
  observe(start)
  observe(start+50,listOf(output("p",start+50),output("q",start+50)))
  val peer=output("q",start+100).copy(bboxNorm=RectNorm(.7f,.3f,.2f,.4f))
  assertEquals(setOf("p","q","unknown:1:u"),observe(start+100,listOf(output("p",start+100),peer),listOf(unknown())).activeRiskTrackIds)
 }
 @Test fun sourceGapBeyondActualVisualTrackingContractCannotProveContinuity() {
  observe(start)
  val now=start+kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig().maxFrameGapMs+1
  assertEquals(setOf("p","unknown:1:u"),observe(now,batches=listOf(unknown())).activeRiskTrackIds)
 }
 @Test fun staleAndDeviceGateInterruptionBreakHistoryWithoutCancellingClaimedPrimary() {
  val action=requireNotNull(observe(start).action)
  assertTrue(policy.claimFeedbackDelivery(action.trackId,start))
  observe(start+50,stale=true)
  assertEquals(setOf("p","unknown:1:u"),observe(start+100,batches=listOf(unknown())).activeRiskTrackIds)
  assertTrue(policy.confirmFeedbackDelivery(action.trackId,start,start+100))
  observe(start+150,allowed=false)
  assertEquals(setOf("p","unknown:1:u"),observe(start+200,batches=listOf(unknown(start+100))).activeRiskTrackIds)
 }
 @Test fun geometryWalkAndDetectorRestartCannotReuseMatchingHistory() {
  for(changed in listOf(context.copy(geometryId="rotated"),context.copy(epoch=WalkRuntimeEpoch("new",0)),context.copy(detectorGeneration=2))) {
   val p=WalkSafeFeedbackPolicy(); val c=coord(p)
   c.dispatchFeedback(frame(listOf(output("p",start))),false,true,start,warningContext=context)
   val result=c.dispatchFeedback(frame(listOf(output("p",start+100))),false,true,start+100,
       independentFreshOutputs=listOf(unknown()),warningContext=changed)
   assertEquals(setOf("p","unknown:1:u"),result.activeRiskTrackIds)
  }
 }
 @Test fun noContextKeepsExistingExactCaptureOnlyContract() {
  observe(start,ctx=null)
  assertEquals(setOf("p","unknown:1:u"),observe(start+100,batches=listOf(unknown()),ctx=null).activeRiskTrackIds)
 }
 @Test fun higherUnknownRiskCancelsLowerClaimAndRetainsOriginalDeadline() {
  val action=requireNotNull(observe(start,listOf(output("p",start,MessageLevel.WARNING))).action)
  assertTrue(policy.claimFeedbackDelivery(action.trackId,start))
  val result=observe(start+100,listOf(output("p",start+100,MessageLevel.WARNING)),listOf(unknown()))
  assertEquals("unknown:1:u",result.action?.trackId)
  assertEquals(start+800,result.action?.validUntilMs)
  assertFalse(policy.confirmFeedbackDelivery(action.trackId,start,start+200))
  assertTrue(policy.claimFeedbackDelivery("unknown:1:u",start+100))
 }
 @Test fun completedUnknownStopHistoryFollowsPrimarySeverityCatchupWithoutSecondStop() {
  val primary=output("p",start,MessageLevel.WARNING)
  val initial=requireNotNull(observe(start,listOf(primary)).action)
  assertTrue(policy.claimFeedbackDelivery(initial.trackId,start))
  assertTrue(policy.confirmFeedbackDelivery(initial.trackId,start,start))
  val elevated=requireNotNull(observe(start+100,listOf(primary.copy(frameId=(start+100)*1000000L,timestampMs=start+100)),listOf(unknown())).action)
  assertEquals("unknown:1:u",elevated.trackId)
  assertTrue(policy.claimFeedbackDelivery(elevated.trackId,start+100))
  assertTrue(policy.confirmFeedbackDelivery(elevated.trackId,start+100,start+100))
  val caughtUp=observe(start+200,batches=listOf(unknown(start+100)))
  assertEquals(setOf("p"),caughtUp.activeRiskTrackIds)
  assertNull(caughtUp.action)
 }
 @Test fun reservationDoesNotBecomeCompletedHistoryWhenPrimaryReplacesIt() {
  val old=requireNotNull(observe(start,emptyList(),listOf(unknown())).action)
  // Same capture is a valid anchor; an unclaimed reservation is not counted as spoken.
  val replacement=observe(start+100,listOf(output("p",start)),listOf(unknown()))
  assertEquals("p",replacement.action?.trackId)
  assertFalse(policy.confirmFeedbackDelivery(old.trackId,start,start+200))
 }
 @Test fun expiredAuxiliaryEvidenceCannotExtendItsOriginalSourceLease() {
  for(now in start..start+800 step 100) observe(now)
  assertEquals(setOf("p"),observe(start+801,batches=listOf(unknown())).activeRiskTrackIds)
 }

    @Test
    fun claimedUnknownStopKeepsOwnershipDuringPrimaryCatchupThenSharesCompletedHistory() {
        val initial = requireNotNull(observe(start, listOf(output("p", start, MessageLevel.WARNING))).action)
        assertTrue(policy.confirmFeedbackDelivery(initial.trackId, start, start))
        val batches = listOf(unknown())
        val elevated = requireNotNull(observe(start + 100, listOf(output("p", start + 100, MessageLevel.WARNING)), batches).action)
        assertTrue(policy.claimFeedbackDelivery(elevated.trackId, start + 100))

        val caughtUp = observe(start + 200, batches = batches)
        assertNull(caughtUp.action)
        assertEquals(setOf("unknown:1:u"), caughtUp.activeRiskTrackIds)
        assertTrue(elevated.deliveryKey in caughtUp.activeFeedbackDeliveryKeys)
        assertEquals(start + 800, elevated.validUntilMs)
        assertTrue(policy.confirmFeedbackDelivery(elevated.trackId, start + 100, start + 300))

        val completed = observe(start + 300, batches = batches)
        assertEquals(setOf("p"), completed.activeRiskTrackIds)
        assertNull(completed.action)
        assertFalse(policy.confirmFeedbackDelivery(elevated.trackId, start + 100, start + 400))
    }

    @Test
    fun freshAuxiliaryReplacementCannotRenewAnExistingClaimsStartDeadline() {
        val initial = requireNotNull(observe(start, listOf(output("p", start, MessageLevel.WARNING))).action)
        assertTrue(policy.confirmFeedbackDelivery(initial.trackId, start, start))
        val claimed = requireNotNull(observe(start + 100, listOf(output("p", start + 100, MessageLevel.WARNING)), listOf(unknown())).action)
        assertTrue(policy.claimFeedbackDelivery(claimed.trackId, start + 100))
        for (now in start + 200..start + 800 step 100) {
            assertNull(observe(now, batches = listOf(unknown(now - 100))).action)
        }
        val replacement = observe(start + 801, batches = listOf(unknown(start + 800)))
        assertTrue(claimed.deliveryKey in replacement.activeFeedbackDeliveryKeys)
        assertEquals(start + 800, claimed.validUntilMs)
        assertTrue(start + 801 > claimed.validUntilMs) // Main's original-action start fence still rejects it.
        policy.rejectUndeliveredFeedback(claimed.trackId, start + 100)
        assertFalse(policy.confirmFeedbackDelivery(claimed.trackId, start + 100, start + 900))
    }

    @Test
    fun higherCurrentLevelCannotBorrowAnOlderLowerLevelsClaim() {
        val initial = requireNotNull(observe(start, listOf(output("p", start, MessageLevel.CAUTION))).action)
        assertTrue(policy.confirmFeedbackDelivery(initial.trackId, start, start))
        val old = requireNotNull(observe(start + 100, listOf(output("p", start + 100, MessageLevel.CAUTION)),
            listOf(unknown(level = MessageLevel.WARNING))).action)
        assertTrue(policy.claimFeedbackDelivery(old.trackId, start + 100))
        val higher = observe(start + 200, batches = listOf(unknown(start + 100)))
        assertEquals("p", higher.action?.trackId)
        assertEquals(MessageLevel.STOP, higher.action?.level)
        assertFalse(policy.confirmFeedbackDelivery(old.trackId, start + 100, start + 300))
    }

    @Test
    fun claimedUnknownDoesNotHideASeparateEqualSeverityPrimaryRegion() {
        val claimed = requireNotNull(observe(start, emptyList(), listOf(unknown())).action)
        assertTrue(policy.claimFeedbackDelivery(claimed.trackId, start))
        val separate = output("p", start + 100).copy(bboxNorm = RectNorm(.7f, .3f, .2f, .4f))
        val result = observe(start + 100, listOf(separate), listOf(unknown()))
        assertEquals(setOf("p", "unknown:1:u"), result.activeRiskTrackIds)
        assertTrue(policy.confirmFeedbackDelivery(claimed.trackId, start, start + 200))
    }

    @Test
    fun concurrentClaimAndEqualRegionSelectionCannotCancelAnAcceptedClaim() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val p = WalkSafeFeedbackPolicy()
                val c = coord(p)
                val batches = listOf(unknown())
                val reserved = requireNotNull(c.dispatchFeedback(frame(emptyList()), false, true, start,
                    independentFreshOutputs = batches, warningContext = context).action)
                val ready = java.util.concurrent.CountDownLatch(1)
                val claim = executor.submit<Boolean> {
                    ready.await()
                    p.claimFeedbackDelivery(reserved.trackId, start)
                }
                val selection = executor.submit<TactileFrameFeedbackDispatch> {
                    ready.await()
                    c.dispatchFeedback(frame(listOf(output("p", start))), false, true, start + 100,
                        independentFreshOutputs = batches, warningContext = context)
                }
                ready.countDown()
                val accepted = claim.get(2, java.util.concurrent.TimeUnit.SECONDS)
                val selected = selection.get(2, java.util.concurrent.TimeUnit.SECONDS)
                if (accepted) {
                    assertNull(selected.action)
                    assertEquals(setOf(reserved.trackId), selected.activeRiskTrackIds)
                    assertTrue(p.confirmFeedbackDelivery(reserved.trackId, start, start + 200))
                } else {
                    assertEquals("p", selected.action?.trackId)
                    assertFalse(p.confirmFeedbackDelivery(reserved.trackId, start, start + 200))
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
