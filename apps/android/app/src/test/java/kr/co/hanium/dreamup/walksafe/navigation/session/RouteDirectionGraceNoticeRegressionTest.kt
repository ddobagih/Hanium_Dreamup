package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import org.junit.Assert.*
import org.junit.Test

/** Actual Guide/Nav reserve, START validator and terminal callback path with controlled speech ports.
 * Existing GuidanceFixture supplies an admitted walk; this is not a new HOME admission or acoustic test.
 */
class RouteDirectionGraceNoticeRegressionTest {
    @Test fun staleActiveNoticeKeepsItsTerminalOwnerAndCannotDeliverOrRetireItsReplacement() {
        for (done in listOf(false, true)) {
            val f = Fixture()
            f.g.install()
            var oldDeliveries = 0
            var newDeliveries = 0
            f.g.controller.offerEnvironmentRecheckNotice("old environment", { true }) { oldDeliveries++ }
            val old = f.g.statusSent.single()
            f.g.positioningGeneration++
            f.g.controller.offerEnvironmentRecheckNotice("new environment", { true }) { newDeliveries++ }
            assertFalse(old.current())
            assertEquals("The active native attempt owns its terminal until STOP/ERROR/DONE", 1, f.g.statusSent.size)

            if (done) old.done() else old.failed()
            f.at(1_000L + if (done) 250L else f.g.nav.instructionRetryIntervalMs); f.runDue()
            val replacement = f.g.statusSent.last()
            assertEquals(2, f.g.statusSent.size)
            assertEquals("new environment", replacement.message)
            assertTrue(replacement.current())
            old.done(); old.failed()
            assertTrue("Duplicate stale terminals cannot retire the replacement", replacement.current())
            assertEquals(0, oldDeliveries)
            assertEquals(0, newDeliveries)
            replacement.done(); replacement.done()
            assertEquals(1, newDeliveries)
        }
    }

    @Test fun coldRouteWithoutRetainedNormalDirectionDoesNotAnnounceASecondCalibrationNotice() {
        val f=Fixture()
        f.g.install()
        f.g.heading=RouteCompassHeadingResult(null,RouteCompassHeadingReason.HEADING_ACCURACY)
        f.g.controller.updateRouteGuidance(f.g.fix)
        assertEquals(RouteDirectionGraceMode.RECHECK_WAIT,f.g.nav.currentDirectionGraceSnapshot()?.mode)
        assertNull("The new route has never accepted a normal direction",f.g.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        assertTrue("Initial direction preparation already has its calibration prompt and must not occupy the first cue's speech owner",f.g.statusSent.isEmpty())
        f.good(1_100L)
        assertFalse("Actual fresh heading must be able to produce the first normal cue",f.g.sent.isEmpty())
        assertTrue(f.g.sent.first().current())
    }

    @Test fun aRecheckNoticeWaitsForTheFirstInformationNativeDoneAndMarksOnlyItsOwnDoneDelivered() {
        val f=Fixture(informationEnabled=true);val action=f.startPlaying()
        assertTrue("The INFO case must actually enable departure summary generation",action.message.contains("길안내를 시작합니다."))
        var current=true;var delivered=0
        f.g.controller.offerEnvironmentRecheckNotice("위치를 다시 확인하고 있습니다.",{current}){delivered++}
        assertTrue("Queued environment information must retain the actual ACTION",f.g.statusSent.isEmpty())
        f.at(1_200L);action.done()
        assertEquals("The original ACTION still owns its captured first INFO",2,f.g.sent.size)
        val information=f.g.sent.last()
        assertTrue(information.message,information.message.contains("전체 경로는"))
        assertTrue(information.current())
        f.at(1_201L);f.runDue()
        assertTrue("A notice must not be submitted before INFO native DONE",f.g.statusSent.isEmpty())
        f.at(1_250L);f.g.validatingStarted=true
        assertTrue("The original INFO START remains current",information.current())
        f.g.validatingStarted=false
        f.at(1_300L);information.done()
        f.at(1_301L);f.runDue()
        assertEquals(1,f.g.statusSent.size)
        val notice=f.g.statusSent.single()
        assertTrue(notice.current())
        assertEquals("Status enqueue/START is not delivery",0,delivered)
        notice.done();notice.done()
        assertEquals("Only the exact native DONE delivers this current explanation once",1,delivered)
        assertFalse(f.g.events.contains("cancel"))
        current=false
    }

    @Test fun aContinuingDirectionFaultStaysSilentThroughGraceAndExplainsOnlyTheActualRestriction() {
        val f=Fixture();val playing=f.startPlaying()
        f.bad(1_100L)
        assertTrue("The original playing sentence must survive a soft fault",playing.current())
        assertFalse(f.g.events.contains("cancel"))
        assertTrue("A notice must not interrupt the playing ACTION",f.g.statusSent.isEmpty())
        f.at(1_200L);playing.done();f.at(1_201L);f.runDue()
        assertTrue("A short sensor fault remains a silent recheck after the previous sentence",f.g.statusSent.isEmpty())
        f.bad(1_400L);f.runDue()
        f.bad(1_700L);f.runDue()
        assertTrue("Ordinary sensor variation must not announce a fault during grace",f.g.statusSent.isEmpty())
        f.bad(10_100L);f.at(10_101L);f.runDue()
        assertEquals("Only a sustained fault beyond grace needs the restriction explanation",1,f.g.statusSent.size)
        val notice=f.g.statusSent.single()
        assertTrue(notice.message,notice.message.contains("방향")&&notice.message.contains("확인"))
        assertTrue(notice.message,notice.message.contains("안전한 곳")&&notice.message.contains("고정"))
        assertFalse("A recheck explanation cannot claim a new clock/metres measurement",notice.message.contains("시 방향"))
        assertTrue(notice.current())
        notice.done();notice.done()
        f.bad(10_200L);f.runDue()
        f.bad(10_400L);f.runDue()
        assertEquals("Repeated BAD belongs to the same explained episode",1,f.g.statusSent.size)
        assertEquals(1_100L,f.g.nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(10_100L,f.g.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
    }

    @Test fun aShortFaultThatRecoveredBeforeDoneNeverProducesAnOutdatedRecheckNotice() {
        val f=Fixture();val playing=f.startPlaying()
        f.bad(1_100L)
        f.good(1_150L)
        assertFalse(f.g.nav.isDirectionRecheckPending())
        f.at(1_200L);playing.done();f.runDue()
        assertTrue("Recovered sensors make the queued explanation obsolete",f.g.statusSent.isEmpty())
        assertFalse(f.g.events.contains("cancel"))
        assertEquals(1,f.g.lastResults.size)
    }

    @Test fun lateDoneAfterPositioningRequestFeedbackOrEpochLossCannotPublishTheNotice() {
        for(change in listOf<(Fixture)->Unit>(
            {it.g.positioningGeneration++},
            {it.g.requestGeneration++},
            {it.g.feedbackCurrent=false},
            {it.g.lifecycle.handle(WalkSessionEvent.EnteredBackground)},
        )) {
            val f=Fixture();val playing=f.startPlaying()
            f.bad(1_100L)
            change(f)
            f.at(1_200L);playing.done();f.at(1_201L);f.runDue()
            assertTrue("An old native DONE owns neither the replacement collection nor the notice",f.g.statusSent.isEmpty())
            assertTrue(f.g.lastResults.isEmpty())
        }
    }

    @Test fun aQueuedNoticeCannotBecomeCurrentAgainForAReplacementCollectionOrRecoveredEpisode() {
        for(change in listOf<(Fixture)->Unit>({it.g.positioningGeneration++},{it.good(10_300L)})) {
            val f=Fixture();val playing=f.startPlaying()
            f.bad(1_100L)
            f.at(1_200L);playing.done();f.bad(10_100L);f.at(10_101L);f.runDue()
            val notice=f.g.statusSent.single()
            assertTrue(notice.current())
            change(f)
            assertFalse("Late status START must validate the captured exact owner and current BAD episode",notice.current())
            notice.done();f.runDue()
            assertEquals(1,f.g.statusSent.size)
        }
    }

    @Test fun actualGraceExpiryRetiresPlayingSpeechWithoutWaitingForItsLateDone() {
        val f=Fixture();val playing=f.startPlaying()
        f.bad(1_100L)
        assertTrue(f.g.statusSent.isEmpty())
        f.bad(10_100L)
        assertTrue("The deadline's safety restriction must not wait behind the unfinished sentence",f.g.events.contains("cancel"))
        assertEquals(RouteDirectionGraceMode.PROBLEM,f.g.nav.currentDirectionGraceSnapshot()?.mode)
        assertEquals(10_100L,f.g.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        f.at(10_101L);playing.done();f.runDue()
        assertTrue("Late completion cannot acknowledge a retired ACTION",f.g.lastResults.isEmpty())
    }

    private class Fixture(private val informationEnabled:Boolean=false) {
        private var clock=1_000L
        private val run=NavigationRunState(RouteNavigator(RouteNavigatorConfig(
            directionRecheckGraceMs=GuidanceRecoveryTiming.DIRECTION_GRACE_MS),nowElapsedRealtimeMs={clock}))
        val g=GuidanceFixture(suppliedRun=run).apply {captureStatus=true}
        fun at(value:Long){clock=value;g.now=value}
        fun startPlaying():GuidanceFixture.Submission {
            fun point(eastM:Double)=RoutePoint(37.0,127.0+eastM/88_804.0)
            val guides=if(informationEnabled) listOf(
                WalkingRouteGuidePoint(0,point(0.0),null,0,200,turnType=200,pointType="SP"),
                WalkingRouteGuidePoint(1,point(100.0),null,100,100,turnType=11),
            ) else listOf(WalkingRouteGuidePoint(0,point(100.0),null,100,100,turnType=11))
            g.nav.setRoute(WalkingRoute("TEST",WalkingRouteSummary(200,200),listOf(point(0.0),point(200.0)),
                guides),
                announceStartSummary=informationEnabled)
            g.run.markRequestActive();g.run.selectDestination(point(200.0))
            g.controller.updateRouteGuidance(g.fix)
            val first=g.sent.single()
            at(1_050L);g.validatingStarted=true
            assertTrue(first.current()) // controlled native START invokes the production validator.
            g.validatingStarted=false
            return first
        }
        fun bad(at:Long) {
            this.at(at)
            g.heading=RouteCompassHeadingResult(RouteFacingObservation(90.0,80.0,at),RouteCompassHeadingReason.HEADING_ACCURACY)
            g.controller.retryCurrentNavigationSpeechForObservation(at,1_100L,g.heading)
        }
        fun good(at:Long) {
            this.at(at)
            g.heading=RouteCompassHeadingResult(RouteFacingObservation(90.0,3.0,at),RouteCompassHeadingReason.VALID)
            g.controller.retryCurrentNavigationSpeechAfterReady(at)
        }
        fun runDue() {
            var runs=0
            while(true) {
                val next=g.pending.filter{it.first<=g.now}.minByOrNull{it.first}?:break
                g.pending.remove(next);next.second.run()
                check(++runs<100){"Unexpected zero-delay callback loop"}
            }
        }
    }
}
