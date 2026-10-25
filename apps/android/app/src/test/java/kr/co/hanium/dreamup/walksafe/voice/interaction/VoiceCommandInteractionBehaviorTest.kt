package kr.co.hanium.dreamup.walksafe.voice.interaction

import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.RecognizedVoiceInput
import org.junit.Assert.*
import org.junit.Test

/** Real command/parser/dialog/search/policy owners; only SDK/application effects are controlled. */
class VoiceCommandInteractionBehaviorTest {
    @Test fun constructionDoesNotReadClockIdentityRouteOrStartAnything() {
        val f = InteractionFixture()
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.nowReads)
        assertEquals(0, f.routeClockReads)
        assertTrue(f.responses.isEmpty())
    }

    @Test fun localReportDeleteRequiresHeardListAndHeardSelectionFromTheSameOwner() {
        val f=InteractionFixture(); f.localSummaries=listOf("기기 보관 신고 하나")
        f.recognize("기기 신고 목록"); f.recognize("기기 신고 1번 선택")
        assertFalse(f.events.contains("localSelect:1"))
        f.responses.first().done!!.invoke(); f.recognize("기기 신고 1번 선택")
        assertTrue(f.events.contains("localSelect:1"))
        f.recognize("기기 신고 삭제 확인"); assertFalse(f.events.contains("localDelete"))
        f.responses.last().done!!.invoke(); f.recognize("기기 신고 삭제 확인")
        assertEquals(1,f.events.count { it=="localDelete" })
    }
    @Test fun asynchronousLocalReportResultFromAnOldCommandOrActorCannotSpeakOrAuthorize() {
        for (retire in listOf<(InteractionFixture)->Unit>({it.actor="other"},{it.recognize("도움말")})) {
            val f=InteractionFixture();f.deferLocalLoad=true;f.localSummaries=listOf("신고")
            f.recognize("기기 신고 목록");assertTrue(f.responses.isEmpty())
            retire(f);f.localLoaded!!(f.localSummaries)
            assertTrue(f.responses.isEmpty());f.recognize("기기 신고 삭제 확인")
            assertFalse(f.events.contains("localDelete"))
        }
    }

    @Test fun localReportLateCompletionAndChangedMetadataCannotAuthorizeDeletion() {
        for (retire in listOf<(InteractionFixture)->Unit>({it.actor="other"},{it.process++},{it.lifetime++},
            {it.epoch=WalkRuntimeEpoch("replacement",0)}, {it.localSummaries=listOf("바뀐 신고")})) {
            val f=InteractionFixture(); f.localSummaries=listOf("신고")
            f.recognize("기기 신고 목록"); val late=f.responses.last().done!!; retire(f); late()
            f.recognize("기기 신고 1번 선택"); f.recognize("기기 신고 삭제 확인")
            assertFalse(f.events.contains("localSelect:1")); assertFalse(f.events.contains("localDelete"))
        }
    }

    @Test fun identicalSpokenDescriptionsFromNewReportIdentityCannotReuseHeardListOrLateDone() {
        for (completeBeforeReplacement in listOf(false,true)) {
            val f=InteractionFixture(); f.localSummaries=listOf("촬영 시각과 우선순위가 같은 신고")
            f.localReportId="old-report";f.localPayloadSha="a".repeat(64);f.localPayloadBytes=100
            f.recognize("기기 신고 목록");val done=f.responses.last().done!!
            if (completeBeforeReplacement) done()
            val spoken=f.localSummaries.toList()
            f.localReportId="new-report";f.localPayloadSha="b".repeat(64);f.localPayloadBytes=101;f.localListToken=2L
            assertEquals(spoken,f.localSummaries)
            if (!completeBeforeReplacement) done()
            f.recognize("기기 신고 1번 선택")
            assertFalse(f.events.contains("localSelect:1"));assertNull(f.selectedLocalReportId)
        }
    }
    @Test fun identicalDescriptionsAfterHeardSelectionCannotAuthorizeDeletionOfARefreshedList() {
        val f=InteractionFixture();f.localSummaries=listOf("동일한 안내 문구")
        f.localReportId="old-report";f.localPayloadSha="a".repeat(64);f.localPayloadBytes=100
        f.recognize("기기 신고 목록");f.responses.last().done!!.invoke()
        f.recognize("기기 신고 1번 선택");f.responses.last().done!!.invoke()
        assertEquals("old-report",f.selectedLocalReportId)
        val spoken=f.localSummaries.toList()
        f.localReportId="new-report";f.localPayloadSha="b".repeat(64);f.localPayloadBytes=101;f.localListToken=2L
        assertEquals(spoken,f.localSummaries)
        f.recognize("기기 신고 삭제 확인")
        assertFalse(f.events.contains("localDelete"))
    }

    @Test fun changingTheUiSelectionWithinTheSameListCannotReuseVoiceSelectionDoneOrDelete() {
        for (completeBeforeUiSelection in listOf(false,true)) {
            val f=InteractionFixture();f.localSummaries=listOf("첫 신고","둘째 신고")
            f.recognize("기기 신고 목록");f.responses.last().done!!.invoke()
            f.recognize("기기 신고 1번 선택");val selectedDone=f.responses.last().done!!
            if (completeBeforeUiSelection) selectedDone()
            val listToken=f.localListToken
            f.selectedLocalReportId="ui-second-report";f.localSelectionToken=f.localSelectionToken!!+1
            assertEquals(listToken,f.localListToken)
            if (!completeBeforeUiSelection) selectedDone()
            f.recognize("기기 신고 삭제 확인")
            assertEquals(0,f.localConfirmRequests);assertFalse(f.events.contains("localDelete"))
        }
    }

    @Test fun helpDoesNotExpireRouteDecisionAndClickCapturesOnlyAtOriginalEntry() {
        val f = InteractionFixture(); f.home = false
        val destination = RoutePoint(0.0, .001)
        f.navigator.setRoute(WalkingRoute("fixture", WalkingRouteSummary(111, 111),
            listOf(RoutePoint(0.0, 0.0), destination), emptyList()))
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, f.navigator.onUntrustedLocation()?.pendingUserDecision)
        f.routeRun.markRequestActive(); f.routeRun.selectDestination(destination); f.routeClockReads = 0
        f.recognize("도움말")
        assertEquals(0, f.routeClockReads)
        f.controllers.commands.requestRerouteFromVoice()
        // Original click token capture, decision branch, then unavailable-message branch.
        assertEquals(3, f.routeClockReads)
        assertEquals("위치 다시 확인만 할 수 있습니다.", f.spoken.last())
    }

    @Test fun pausedResumeIsHandledBeforeNormalCommandAndDestinationDialogQueries() {
        val f = InteractionFixture(); f.paused = true
        f.recognize("보행 재개")
        assertTrue(f.events.contains("resume"))
        assertFalse(f.events.any { it.startsWith("page:") || it == "home" || it == "identity" })
        assertEquals("필수 기능을 다시 확인한 뒤 보행 재개를 확인합니다.", f.spoken.last())
    }

    @Test fun walkEndDispatchAcknowledgementDoesNotStartMicrophoneButActualDoneDoes() {
        val f = InteractionFixture(); f.paused = true
        f.recognize("보행 종료")
        assertTrue(f.controllers.commands.endConfirmationPending)
        assertEquals(0, f.microphoneStarts)
        f.responses.single().done!!.invoke()
        assertEquals(1, f.microphoneStarts)
        f.recognize("보행 종료 확인")
        assertFalse(f.controllers.commands.endConfirmationPending)
        assertEquals(1, f.events.count { it == "end" })
        assertEquals("보행을 종료했습니다.", f.spoken.last())
    }

    @Test fun retiredWalkEpochAndLifetimeCannotStartMicrophoneFromOldQuestion() {
        for (retire in listOf<(InteractionFixture)->Unit>({ it.epoch = WalkRuntimeEpoch("new", 0) }, { it.lifetime++ })) {
            val f = InteractionFixture(); f.paused = true; f.recognize("보행 종료")
            retire(f); f.responses.single().done!!.invoke()
            assertEquals(0, f.microphoneStarts)
        }
    }

    @Test fun unrecognizedFinalConsumesWalkQuestionWithoutExecutingAnotherCommand() {
        val f = InteractionFixture(); f.paused = true; f.recognize("보행 종료")
        f.recognize("서울역으로 안내해줘")
        assertFalse(f.controllers.commands.endConfirmationPending)
        assertEquals("보행 종료를 확인하지 못했습니다. 종료하려면 다시 요청해 주세요.", f.spoken.last())
        assertFalse(f.events.contains("end"))
    }

    @Test fun ambiguousGuidanceStopAsksOnceAndDoneReanchorsThirtySecondWindow() {
        val f = InteractionFixture(); f.home = false; f.routeRun.markRequestActive(); f.showPage()
        f.recognize("안내 취소")
        assertEquals(GUIDANCE_STOP_CONFIRMATION_PROMPT_KO, f.responses.last().message)
        assertFalse(f.events.contains("resetRoute")); assertEquals(0, f.microphoneStarts)
        f.now += 40_000; f.responses.last().done!!.invoke()
        assertEquals(1, f.microphoneStarts)
        f.now += 29_000; f.recognize("안내 취소")
        assertEquals(1, f.events.count { it == "resetRoute" })
        assertEquals("길안내를 중지했습니다.", f.spoken.last())
    }

    @Test fun explicitGuidanceStopHasPriorityOverOpenCandidateDialog() {
        val f = InteractionFixture(); f.home = false; f.routeRun.markRequestActive(); f.showPage()
        f.recognize("길안내 종료")
        assertEquals(1, f.events.count { it == "resetRoute" })
        assertTrue(f.responses.isEmpty())
    }

    @Test fun unrelatedFinalConsumesGuidanceQuestionSoLaterAmbiguousStopAsksAgain() {
        val f = InteractionFixture(); f.home = false; f.routeRun.markRequestActive(); f.showPage()
        f.recognize("안내 취소"); f.responses.last().done!!.invoke()
        f.recognize("도움말"); f.recognize("안내 취소")
        assertEquals(2, f.responses.count { it.message == GUIDANCE_STOP_CONFIRMATION_PROMPT_KO })
        assertFalse(f.events.contains("resetRoute"))
    }

    @Test fun staleInputGenerationRejectsLateGuidanceQuestionDone() {
        val f = InteractionFixture(); f.home = false; f.routeRun.markRequestActive(); f.showPage()
        f.recognize("안내 취소"); f.inputGeneration++
        f.responses.last().done!!.invoke()
        assertEquals(0, f.microphoneStarts)
        assertFalse(f.promptPending)
    }

    @Test fun plainCancelClosesExplicitReportBeforeCandidateDialogAndPreservesRoute() {
        val f = InteractionFixture(); f.home = false; f.routeRun.markRequestActive(); val page=f.showPage(); f.reportOpen=true
        f.recognize("취소")
        assertFalse(f.reportOpen); assertSame(page, f.search.state.voicePage)
        assertTrue(f.routeRun.active); assertEquals("신고를 취소했습니다. 저장하지 않았습니다.",f.spoken.last())
        f.recognize("취소")
        assertNull(f.search.state.voicePage); assertTrue(f.routeRun.active)
        assertEquals("후보 선택을 닫았습니다. 진행 중인 길안내는 계속됩니다.",f.spoken.last())
    }

    @Test fun stoppedQueryKeepsExactStateTextInsteadOfRenderingFunctionObject() {
        val f = InteractionFixture(); f.home = false; f.recognize("길안내 종료")
        assertTrue(f.events.contains("navigation:voice=navigation_stopped hadActive=false"))
        assertEquals("진행 중인 길안내가 없습니다.",f.spoken.last())
    }

    @Test fun missingPlatformConfidenceCanOnlyPreviewAndNeverStartRoute() {
        val f = InteractionFixture(); f.pendingDestination=f.candidates.first()
        f.controllers.commands.handleRecognized(RecognizedVoiceInput(listOf("안내 시작"),listOf(0f),null,backend=OfflineSpeechEngine.PLATFORM))
        assertTrue(f.events.contains("destination-confirm"))
        assertFalse(f.events.contains("startGuidance"))
        assertEquals("안내 시작은 화면의 안내 시작 버튼을 눌러 확인해 주세요.",f.spoken.last())
    }
}

internal class InteractionFixture {
    data class Response(val message:String,val done:(()->Unit)?,val failed:(()->Unit)?,val cancelled:(()->Unit)?)
    var now=1_000L; var nowReads=0; var routeClockReads=0; var epoch=WalkRuntimeEpoch("fixture-walk",0)
    var lifetime=1; var actor:String?="fixture-actor"; var process=2L; var routeGeneration=3
    var inputGeneration=4; var responseGeneration=0L; var promptPending=false; var microphoneStarts=0
    var home=true; var paused=false; var routePending=false; var reportOpen=false; var pendingDestination:DestinationSearchResult?=null
    var range:DestinationSearchVoiceState?=null
    val events=mutableListOf<String>(); val spoken=mutableListOf<String>(); val responses=mutableListOf<Response>()
    val expiry=mutableListOf<Pair<Long,()->Unit>>()
    val candidates=(1..5).map { DestinationSearchResult("c$it","후보 $it",RoutePoint(37.0,127.0+it*0.001),null,null,null,it*100) }
    val navigator=RouteNavigator(nowElapsedRealtimeMs={ routeClockReads++; now })
    val routeRun=NavigationRunState(navigator)
    val search=DestinationSearchController(
        facts=port { name,_ -> when(name) {
            "searchAllowed","isEpochAllowed","locationAccessAllowed","locationSourceReady" -> true
            "rawQuery" -> ""; "epoch" -> epoch; "actorId" -> actor
            else -> error("Unexpected search fact $name")
        } },
        actions=port { name,_ -> when(name) {
            "render","controls","navigationStatus" -> {events+="search:$name";null}
            else -> error("Unexpected search action $name")
        } },
        backend=port { name,_ -> error("Unexpected network call $name") },
        navigationRequests=AndroidNavigationRequestCoordinator(),worker=Executor { error("Unexpected network work") },
        postToUi={it()},locationFactory={error("Unexpected location acquisition")},
    )
    var localSummaries=emptyList<String>()
    var localListToken:Long?=1L
    var localSelectionToken:Long?=null
    var localConfirmRequests=0
    var localReportId="report"
    var localPayloadSha="a".repeat(64)
    var localPayloadBytes=100
    var selectedLocalReportId:String?=null
    var deferLocalLoad=false
    var localLoaded:((List<String>)->Unit)?=null
    val controllers=VoiceInteractionControllers(
        search={search},routeRun={routeRun},
        walk=port { name,args -> when(name) {
            "snapshot" -> snapshot(); "runtimeEpochOrNull" -> epoch; "runtimeActive" -> !home && !paused
            "pauseWalk" -> {events+="pause";null}; "prepareResume" -> {events+="resume";null}
            "confirmWalkEnd" -> {events+="end";null}; "requireReporterUserId" -> actor!=null
            "cancelExplicitReportIfOpen" -> reportOpen.also {if(it)reportOpen=false}
            "requestExplicitVoiceReport" -> {events+="report";null}
            "refreshLocalQuarantine" -> {
                @Suppress("UNCHECKED_CAST")
                val loaded=args[0] as (List<String>)->Unit
                if (deferLocalLoad) localLoaded=loaded else loaded(localSummaries)
                null
            }
            "localQuarantineDescriptions" -> localSummaries
            "localQuarantineListToken" -> localListToken
            "localQuarantineSelectionToken" -> localSelectionToken
            "localQuarantineDescription" -> localSummaries.getOrNull((args[0] as Int)-1)
            "selectLocalQuarantineNumber" -> { selectedLocalReportId=localReportId;localSelectionToken=(localSelectionToken?:0L)+1;events+="localSelect:${args[0]}";true }
            "confirmLocalQuarantineDelete" -> {
                localConfirmRequests++
                if (args.size > 1 && (args[1] != localListToken || args[2] != localSelectionToken)) false
                else { events+="localDelete";true }
            }
            else -> error("Unexpected walk $name")
        } },
        route=port { name,args -> when(name) {
            "requestGeneration" -> routeGeneration; "requestPending" -> routePending; "requestReason" -> "user_destination"
            "navigationCollectionAllowsWork","destinationSearchAllowsWork","currentRouteLocationAvailable" -> true
            "blockRouteMutationWhileDeviationChoicePending","snapshotPurgeFailed" -> false
            "resetRouteState" -> { events+="resetRoute";routeRun.markInactive();null }
            "cancelActiveRouteRequest" -> {events+="cancelRoute:${args[0]}";routePending=false;null}
            "startNativeDestinationGuidance" -> {events+="startGuidance";null}
            "onDestinationSelected" -> {events+="select:${(args[0] as DestinationSearchResult).id}";true}
            "currentRouteCompassHeadingResult" -> null
            "updateRouteDeviationActions","applyRouteDeviationSafetyUpdate","startNavigationServicesIfNeeded",
            "explainNavigationFeatureUnavailable","requestRoute","retainRouteAfterRerouteFailure","tryDispatchRouteInstruction" -> {events+="route:$name";null}
            else -> error("Unexpected route $name")
        } },
        identity=port { name,_ -> when(name) {
            "actorId" -> {events+="identity";actor}; "processGeneration" -> process; "feedbackLifetimeGeneration" -> lifetime
            else -> error(name)
        } },
        ui=port { name,args -> when(name) {
            "homeContextAvailable" -> {events+="home";home}; "voiceInteractionPage" -> true
            "pendingDestination" -> pendingDestination; "clearPendingDestination" -> {pendingDestination=null;null}
            "showDestinationConfirmation" -> {events+="destination-confirm";null}
            "showCommandPage" -> {events+="page:${args[0]}";null}
            "navigationStatus" -> {events+="navigation:${args[0]}";null}; "voiceStatus" -> {events+="voice:${args[0]}";null}
            "status" -> {events+="status:${args[0]}";null}
            "refreshVoiceControls" -> {events+="controls:${args[0]}";null}
            "refreshDestinationRange" -> {range=args[0] as DestinationSearchVoiceState?;null}
            "destinationRangeState" -> range
            "queryHasText","latitudeHasText","longitudeHasText" -> false
            "setQueryText","clearCoordinateText","renderSearch" -> {events+="ui:$name";null}
            "openDestinationConfirmation" -> {pendingDestination=args[0] as DestinationSearchResult;events+="chosen:${pendingDestination!!.id}";null}
            else -> error("Unexpected UI $name")
        } },
        input=port { name,args -> when(name) {
            "generation" -> inputGeneration; "active" -> false; "promptPending" -> promptPending
            "completePreparedPrompt" -> {events+="promptReady:${args[0]}";promptPending=false;null}
            "clearPromptReady" -> {events+="promptClear";null}
            "startVoiceCommandRecognition" -> {microphoneStarts++;true}
            else -> error(name)
        } },
        output=port { name,args -> when(name) {
            "responseGeneration" -> responseGeneration
            "reserveCommandSpeech" -> { _:Boolean -> Unit }
            "speakInteraction","speakStatusExplanation" -> {spoken+=args[0] as String;true}
            "speakCurrentState" -> {events+="currentState";null}
            "speakCommandParts" -> {
                responseGeneration++; promptPending=args[1] as Boolean
                @Suppress("UNCHECKED_CAST")
                responses+=Response((args[0] as List<String>).joinToString(" "),args[2] as (() -> Unit)?,args[3] as (() -> Unit)?,args[4] as (() -> Unit)?);true
            }
            "speakCommandResponse" -> {
                responseGeneration++;promptPending=args[1] as Boolean
                @Suppress("UNCHECKED_CAST")
                responses+=Response(args[0] as String,args[2] as (() -> Unit)?,args[3] as (() -> Unit)?,args[4] as (() -> Unit)?);true
            }
            "speakWalkEndQuestion" -> {
                @Suppress("UNCHECKED_CAST")
                responses+=Response(args[0] as String,args[1] as (() -> Unit)?,args[2] as (() -> Unit)?,null);true
            }
            "speakRouteDecision" -> {spoken+=args[0] as String;true}
            else -> error(name)
        } },
        scheduler=port { name,args -> when(name) {
            "nowMs" -> {nowReads++;now}
            "runOnUiThread" -> { @Suppress("UNCHECKED_CAST") (args[0] as ()->Unit).invoke();null }
            "postDialogExpiry" -> { @Suppress("UNCHECKED_CAST") expiry+=(now+(args[1] as Long)) to (args[0] as ()->Unit);null }
            else -> error(name)
        } },
        diagnostics=port { _,_ -> null },
    )
    fun recognize(phrase:String)=controllers.commands.handleRecognized(RecognizedVoiceInput(listOf(phrase),listOf(.99f),null))
    fun showPage(origin:DestinationSearchOrigin?=null):DestinationSearchVoiceState =
        DestinationSearchVoiceState("",candidates,origin=origin).also(search::showVoicePage)
    fun snapshot():WalkSessionSnapshot {
        val state=if(paused)WalkSessionState.PAUSED else if(home)WalkSessionState.READY else WalkSessionState.ACTIVE
        val plan=WalkSessionReadinessPlan(WalkSessionAction.START_WALK,WalkSessionMode.FULL)
        val ready=if(state==WalkSessionState.ACTIVE)WalkSessionReadinessSnapshot(epoch,plan,now,
            WalkSessionReadinessRequirement.values().associateWith { requirement -> WalkSessionReadinessObservation(epoch,requirement,
                if(plan.requires(requirement))WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.NOT_REQUIRED,"fixture") }) else null
        return WalkSessionSnapshot(epoch,state,WalkSessionMode.FULL,if(paused)WalkSessionRecoveryStage.RECHECK_REQUIRED else null,true,ready)
    }
}

/** Strict SDK/application port fake: unplanned effects fail; no domain decision is mocked. */
internal inline fun <reified T> port(noinline call:(String,Array<out Any?>)->Any?):T {
    return Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _,m,a ->
        call(m.name,a ?: emptyArray())
    } as T
}
