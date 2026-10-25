package kr.co.hanium.dreamup.walksafe.report.interaction

import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.*
import org.json.JSONObject
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class UserReportInteractionOwnerTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    private fun localStore(onLockedAccess: () -> Unit = {}): AndroidReportQueueStore {
        val aead = object : LocalAead {
            override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) = AeadSealResult.Sealed(Base64.getEncoder().encodeToString(plaintext))
            override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) = AeadOpenResult.Opened(Base64.getDecoder().decode(envelope), 1, false)
            override fun destroyVersion(version: Int) = true
            override fun destroyKnownVersions() = true
            override fun createFreshAfterVerifiedPurge() = true
        }
        val profile = ApprovedReportQueueCapacityProfile(4, 1_000_000, 1_000_000, 8_000_000, 2, 4_000_000)
        val file = FileReportQueueStorage(temporary.newFolder())
        val storage = object : ReportQueueStorage by file {
            override fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T? =
                file.withLockedAccess { access -> onLockedAccess(); action(access) }
        }
        val store = AndroidReportQueueStore(profile, { storage }, aead,
            idFactory = { UUID.fromString(REPORT) }, nowMillis = { 1_000L }, testOnly = Unit)
        val metadata = JSONObject().put("reporter_user_id", ACTOR).put("trigger", "voice").put("auto_reported", false)
            .put("class_name", "damaged_tactile_block").put("confidence", .91).put("captured_at", "2026-10-11T00:00:00Z").toString().toByteArray()
        val report = requireNotNull(store.enqueue(ACTOR, metadata, reportTestJpeg(), ReportQueuePriority.EXPLICIT, OTHER, "a".repeat(64), 7L))
        assertTrue(store.recordUploadFailure(report, true)); report.close()
        return store
    }

    @Test fun localListLoadsOnWorkerAndSelectionPlusSeparateConfirmationAreRequired() {
        val f = Fixture(); f.localStore = localStore()
        var loaded: List<String>? = null
        f.owner.refreshLocalQuarantine { loaded = it }
        assertNull(loaded)
        assertFalse(f.owner.confirmLocalQuarantineDelete())
        f.localWork.removeFirst().run()
        assertEquals(1, requireNotNull(loaded).size)
        assertTrue(f.owner.selectLocalQuarantineNumber(1))
        assertFalse(f.owner.confirmLocalQuarantineDelete(null))
        assertFalse(f.owner.confirmLocalQuarantineDelete(1.01))
        assertTrue(f.owner.confirmLocalQuarantineDelete(.9))
        assertEquals(1, f.localStore!!.quarantinedSummaries(ACTOR, 7L).size)
        f.positive!!.invoke()
        assertEquals(1, f.localStore!!.quarantinedSummaries(ACTOR, 7L).size)
        f.localWork.removeFirst().run()
        assertTrue(f.localStore!!.quarantinedSummaries(ACTOR, 7L).isEmpty())
    }

    @Test fun epochChangeWhileStorageLockIsBlockedCancelsBeforePhysicalDelete() {
        val f = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        var block = false
        f.localStore = localStore {
            if (block) { entered.countDown(); check(resume.await(2L, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        f.owner.refreshLocalQuarantine(); f.localWork.removeFirst().run()
        assertTrue(f.owner.selectLocalQuarantineNumber(1)); assertTrue(f.owner.confirmLocalQuarantineDelete())
        f.positive!!.invoke()
        block = true
        val task = f.localWork.removeFirst()
        val worker = Thread { task.run() }
        try {
            worker.start()
            assertTrue(entered.await(2L, java.util.concurrent.TimeUnit.SECONDS))
            f.localEpoch++
            resume.countDown(); worker.join(2_000L)
            assertFalse(worker.isAlive)
            assertEquals(1, f.localStore!!.quarantinedSummaries(ACTOR, 7L).size)
        } finally { resume.countDown(); worker.join(2_000L) }
    }

    @Test fun localListTokenChangesOnIdenticalDescriptionRefreshButSurvivesNumberSelection() {
        val f = Fixture(); f.localStore = localStore()
        f.owner.refreshLocalQuarantine(); f.localWork.removeFirst().run()
        val first = requireNotNull(f.owner.localQuarantineListToken())
        val descriptions = f.owner.localQuarantineDescriptions()
        assertTrue(f.owner.selectLocalQuarantineNumber(1))
        assertEquals(first, f.owner.localQuarantineListToken())
        f.owner.refreshLocalQuarantine()
        assertNull(f.owner.localQuarantineListToken())
        f.localWork.removeFirst().run()
        assertEquals(descriptions, f.owner.localQuarantineDescriptions())
        assertNotEquals(first, f.owner.localQuarantineListToken())
        f.owner.invalidateLocalQuarantineSelection()
        assertNull(f.owner.localQuarantineListToken())
    }

    @Test fun voiceExpectedListAndSelectionTokensCannotDeleteAReplacementSelection() {
        val f = Fixture(); f.localStore = localStore()
        f.owner.refreshLocalQuarantine(); f.localWork.removeFirst().run()
        assertTrue(f.owner.selectLocalQuarantineNumber(1))
        val list = requireNotNull(f.owner.localQuarantineListToken())
        val selected = requireNotNull(f.owner.localQuarantineSelectionToken())
        assertTrue(f.owner.selectLocalQuarantineNumber(1)) // A new UI selection owns a new confirmation.
        assertEquals(list, f.owner.localQuarantineListToken())
        assertNotEquals(selected, f.owner.localQuarantineSelectionToken())
        assertFalse(f.owner.confirmLocalQuarantineDelete(.9, list, selected))
        assertNull(f.positive)
        assertTrue(f.owner.confirmLocalQuarantineDelete(.9, list, f.owner.localQuarantineSelectionToken()))
    }

    @Test fun navigationOrAuthorityChangeBeforeDeleteWorkerKeepsExactLocalPayload() {
        for (change in listOf<(Fixture) -> Unit>({ it.localEpoch++ }, { it.generation++ }, { it.foreground = false })) {
            val f = Fixture(); f.localStore = localStore()
            f.owner.refreshLocalQuarantine(); f.localWork.removeFirst().run()
            assertTrue(f.owner.selectLocalQuarantineNumber(1)); assertTrue(f.owner.confirmLocalQuarantineDelete())
            f.positive!!.invoke(); change(f); f.localWork.removeFirst().run()
            assertEquals(1, f.localStore!!.quarantinedSummaries(ACTOR, 7L).size)
            assertTrue(f.owner.localQuarantineDescriptions().isEmpty())
        }
    }

    @Test fun staleLocalListCallbackCannotRevealMetadataAndAlwaysTerminatesTheLoadingReservation() {
        val f = Fixture(); f.localStore = localStore()
        var loaded: List<String>? = null
        f.owner.refreshLocalQuarantine { loaded = it }
        f.localEpoch++
        f.localWork.removeFirst().run()
        assertEquals(emptyList<String>(), loaded)
        assertTrue(f.owner.localQuarantineDescriptions().isEmpty())
        assertFalse(f.owner.selectLocalQuarantineNumber(1))
    }

    @Test fun constructorIsPureAndRestoreFilterKeepsOriginalWireRules() {
        val f=Fixture();assertTrue(f.events.isEmpty())
        f.owner.restoreStatusFilter("RECEIVED");assertEquals(UserReportStatus.RECEIVED,f.owner.restoredStatusFilter())
        f.owner.restoreStatusFilter("bad");assertNull(f.owner.restoredStatusFilter())
    }
    @Test fun onlyVerifiedBackendDeviceActorWithCompleteLocalEpochCanBecomeAuthority() {
        val f=Fixture();val a=requireNotNull(f.owner.currentUserReportAuthorityOrNull())
        assertSame(f.session,a.session);assertEquals(7L,a.sessionGeneration);assertEquals(9L,a.localIdentityEpoch)
        f.deletionBlocked=true;assertNull(f.owner.currentUserReportAuthorityOrNull())
        f.deletionBlocked=false;f.blocked=true;assertNull(f.owner.currentUserReportAuthorityOrNull())
        f.blocked=false;f.recovery=true;assertNull(f.owner.currentUserReportAuthorityOrNull())
        f.recovery=false;f.firstRun=FirstRunOnboardingPolicy.initialEmailAccount(9L);assertNull(f.owner.currentUserReportAuthorityOrNull())
        f.firstRun=completeFirstRun(9L);f.policy=PermissionSessionPolicy();assertNull(f.owner.currentUserReportAuthorityOrNull())
    }
    @Test fun ordinaryGatewayCookieCannotAcquireUserRightsAuthority() {
        val f=Fixture();f.session=GatewayFieldSession.verified(ORIGIN,ACTOR,"walksafe_field_session=ordinary-scope-token-00001",Long.MAX_VALUE)
        assertNull(f.owner.currentUserReportAuthorityOrNull())
    }
    @Test fun currentPermissionSupplierIsQueriedAfterOriginalProcessAndDeletionChecks() {
        val f=Fixture();f.owner.currentUserReportAuthorityOrNull()
        assertEquals(listOf("process","deletion","policy"),f.events)
        f.policy=PermissionSessionPolicy();assertNull(f.owner.currentUserReportAuthorityOrNull())
    }
    @Test fun expiryUsesTheMinimumDeadlineAndIdentityAndOldRunnableCannotClearNewSchedule() {
        val f=Fixture();val authority=requireNotNull(f.owner.currentUserReportAuthorityOrNull())
        f.events.clear();f.owner.scheduleUserReportAuthorityExpiryIfNeeded(authority)
        val old=f.timers.last().first;val expected=minOf(f.session.accessExpiresAtEpochMs,f.session.idleExpiresAtEpochMs,f.session.absoluteExpiresAtEpochMs)-f.wall
        assertEquals(expected,f.timers.last().second)
        f.owner.scheduleUserReportAuthorityExpiryIfNeeded(authority)
        val newer=f.timers.last().first;old.run()
        assertFalse(f.events.contains("clear-ui"));assertFalse(f.events.contains("authority-changed"))
        f.wall+=expected;newer.run();assertEquals(listOf("clear-ui","authority-changed"),f.events.takeLast(2))
    }
    @Test fun cancelledOrBackgroundExpiryCannotPublishAUiFence() {
        val f=Fixture();f.owner.scheduleUserReportAuthorityExpiryIfNeeded();val r=f.timers.last().first
        f.owner.cancelUserReportAuthorityExpirySchedule();r.run();assertFalse(f.events.contains("clear-ui"))
        f.owner.scheduleUserReportAuthorityExpiryIfNeeded();val next=f.timers.last().first
        f.foreground=false;next.run();assertFalse(f.events.contains("clear-ui"))
    }
    @Test fun sameValueDifferentSessionOrChangedLocalEpochReschedulesInsteadOfClearingNewOwner() {
        val f=Fixture();f.owner.scheduleUserReportAuthorityExpiryIfNeeded();val r=f.timers.last().first
        f.session=backendSession(f.expires);f.generation++
        r.run();assertFalse(f.events.contains("clear-ui"));assertEquals(2,f.timers.size)
        val r2=f.timers.last().first;f.firstRun=completeFirstRun(10L);r2.run()
        assertFalse(f.events.contains("clear-ui"));assertEquals(3,f.timers.size)
    }
    @Test fun earlyTimerWakeReschedulesWithSameAuthorityAndRejectedPostLeavesNoActiveRunnable() {
        val f=Fixture();f.owner.scheduleUserReportAuthorityExpiryIfNeeded();f.timers.last().first.run();assertEquals(2,f.timers.size)
        f.postAccepted=false;f.owner.scheduleUserReportAuthorityExpiryIfNeeded();val rejected=f.timers.last().first
        f.wall=f.expires;rejected.run();assertFalse(f.events.contains("clear-ui"))
    }
    @Test fun destroyedOrUnpublishedPresentationDoesNotReadAuthorityOrRender() {
        val f=Fixture();f.destroyed=true;f.owner.renderUserReportState(UserReportUiState());assertTrue(f.events.isEmpty())
        f.destroyed=false;f.presentationReady=false;f.owner.renderUserReportState(UserReportUiState());assertTrue(f.events.isEmpty())
    }
    @Test fun publishedPresenterCanRenderObserverStateBeforeControllerAliasPublication() {
        val f=Fixture();f.reportsReady=false;f.owner.renderUserReportState(UserReportUiState())
        assertTrue(f.events.contains("render"))
    }
    @Test fun resumePreservesRevalidateThenControllerThenExpiryQueryOrder() {
        val f=Fixture();f.owner.revalidateUserReportAuthorityOnResume()
        assertTrue(f.events.indexOf("revalidate-ui")<f.events.indexOf("authority-changed"))
        assertTrue(f.events.indexOf("authority-changed")<f.events.lastIndexOf("process"))
    }
    @Test fun correctionCapturesReportAndDescriptionButReadsCategoryAtPositiveClick() {
        val f=Fixture();f.select();f.draft=UserReportInteractionOwner.CorrectionDraft("  저장 설명  ",false,UserReportCorrectionPatch.Omitted)
        f.owner.confirmUserReportCorrection();assertEquals("신고 내용 정정 확인",f.dialogTitle)
        f.draft=UserReportInteractionOwner.CorrectionDraft("변경된 입력",false,UserReportCorrectionPatch.Value(UserReportContentCategory.ROAD_DAMAGE))
        f.positive!!.invoke()
        assertEquals(REPORT,f.correction!!.first);assertEquals(UserReportCorrectionPatch.Value("저장 설명"),f.correction!!.second)
        assertEquals(UserReportCorrectionPatch.Value(UserReportContentCategory.ROAD_DAMAGE),f.correction!!.third)
    }
    @Test fun correctionMissingContentInvalidLengthAndEmptyPatchCannotOpenDialog() {
        val f=Fixture();f.owner.confirmUserReportCorrection();assertNull(f.positive)
        f.select();f.state=f.state.copy(selectedContent=null);f.owner.confirmUserReportCorrection();assertNull(f.positive)
        f.select();f.draft=UserReportInteractionOwner.CorrectionDraft("a".repeat(501),false,UserReportCorrectionPatch.Omitted)
        f.owner.confirmUserReportCorrection();assertNull(f.positive)
        f.draft=UserReportInteractionOwner.CorrectionDraft("",false,UserReportCorrectionPatch.Omitted);f.owner.confirmUserReportCorrection();assertNull(f.positive)
    }
    @Test fun rejectedCurrentnessAtCorrectionClickKeepsExactFailureNotice() {
        val f=Fixture();f.select();f.draft=UserReportInteractionOwner.CorrectionDraft("설명",false,UserReportCorrectionPatch.Omitted)
        f.owner.confirmUserReportCorrection();f.submitAccepted=false;f.positive!!.invoke()
        assertEquals("내 신고 상태: 현재 신고 내용을 다시 불러온 뒤 시도하세요.",f.status)
    }
    @Test fun deletionDialogKeepsTheCapturedReportAndRequestEvenWhenSelectionChanges() {
        val f=Fixture();f.select();f.request="선택 원본 삭제 요청";f.owner.confirmUserReportDeleteRequest()
        f.state=f.state.copy(selectedDetail=detail(OTHER));f.request="다른 입력";f.positive!!.invoke()
        assertEquals(Triple(REPORT,UserReportRequestType.DELETE,"선택 원본 삭제 요청"),f.deletion)
    }
    @Test fun invalidDeletionReasonCannotOpenDialog() {
        val f=Fixture();f.select();f.request="";f.owner.confirmUserReportDeleteRequest();assertNull(f.positive)
        assertEquals("내 신고 상태: 요청 사유를 1자 이상 500자 이하로 입력하세요.",f.status)
    }
    @Test fun deletionStatusUsesSelectedTrackedDeleteThenTheLastTrackedFallback() {
        val f=Fixture();f.select();f.state=f.state.copy(selectedDetail=detail(REPORT,summary(REQUEST,UserReportRequestType.DELETE)),trackedDeletionRequestIds=listOf(REQUEST,REQUEST2))
        f.owner.refreshLatestUserReportDeletionStatus();assertEquals(REQUEST,f.deletionStatusId)
        f.state=f.state.copy(selectedDetail=detail(REPORT,summary(REQUEST,UserReportRequestType.CORRECTION)),trackedDeletionRequestIds=listOf(REQUEST2))
        f.owner.refreshLatestUserReportDeletionStatus();assertEquals(REQUEST2,f.deletionStatusId)
    }
    @Test fun requestStatusUsesSelectedRequestThenOnlyLastReferenceForThatReport() {
        val f=Fixture();f.select();f.state=f.state.copy(trackedRequestReferences=listOf(
            UserReportRequestReference(REPORT,REQUEST,UserReportRequestType.DELETE),UserReportRequestReference(OTHER,REQUEST2,UserReportRequestType.DELETE)))
        f.owner.refreshLatestUserReportRequestStatus();assertEquals(REPORT to REQUEST,f.requestStatusIds)
    }
    @Test fun filterCycleAndSelectedContentKeepTheOriginalRequestCommands() {
        val f=Fixture();val expected=listOf(UserReportStatus.RECEIVED,UserReportStatus.REJECTED,UserReportStatus.INSTITUTION_SUBMITTED,UserReportStatus.RESOLVED,null)
        expected.forEach { value->f.owner.cycleStatusFilter();assertEquals(value,f.state.statusFilter) }
        f.select();f.owner.loadSelectedContent();assertEquals(REPORT,f.loadedContent)
        f.state=f.state.copy(selectedDetail=null);f.loadedContent=null;f.owner.loadSelectedContent();assertNull(f.loadedContent)
    }
    @Test fun initialBindingKeepsAuthorityChangedBeforeRestoredListLoad() {
        val f=Fixture();f.owner.restoreStatusFilter("REJECTED");f.owner.initializeRequestsAfterBinding()
        assertTrue(f.events.indexOf("authority-changed")<f.events.indexOf("load-filter"));assertEquals(UserReportStatus.REJECTED,f.state.statusFilter)
    }

    private class Fixture:UserReportInteractionOwner.Reports,UserReportInteractionOwner.Presentation,UserReportInteractionOwner.Activity,UserReportInteractionOwner.Dialogs {
        val events=mutableListOf<String>();var wall=System.currentTimeMillis();val expires=wall+60_000L
        var session=backendSession(expires);var firstRun=completeFirstRun(9L);var generation=7L
        var blocked=false;var recovery=false;var deletionBlocked=false;var foreground=true;var destroyed=false
        var presentationReady=true;var reportsReady=true;var postAccepted=true;val timers=mutableListOf<Pair<Runnable,Long>>()
        var policy=PermissionSessionPolicy().apply{authenticated(ACTOR)}
        var localStore: AndroidReportQueueStore? = null
        var localEpoch = 1L
        val localWork = java.util.ArrayDeque<Runnable>()
        var state=UserReportUiState();var draft=UserReportInteractionOwner.CorrectionDraft("",false,UserReportCorrectionPatch.Omitted)
        var request="";var status="";var positive:(()->Unit)?=null;var dialogTitle="";var submitAccepted=true
        var correction:Triple<String,UserReportCorrectionPatch<String>,UserReportCorrectionPatch<UserReportContentCategory>>?=null
        var deletion:Triple<String,UserReportRequestType,String>?=null;var deletionStatusId:String?=null;var requestStatusIds:Pair<String,String>?=null;var loadedContent:String?=null
        val owner=UserReportInteractionOwner(this,object:UserReportInteractionOwner.Presentation by this { override fun initialized()=presentationReady },this,this,
            {events.add("process");GatewaySessionProcessSnapshot(session,firstRun,generation,blocked,null,recovery)},
            {events.add("deletion");deletionBlocked},{events.add("policy");policy},{wall},
            localQueue = { requireNotNull(localStore) }, localInteractionEpoch = { localEpoch },
            localExecutor = java.util.concurrent.Executor { localWork.add(it) })
        fun select(){state=state.copy(selectedDetail=detail(REPORT),selectedContent=UserReportContentCurrent(REPORT,1L,"a".repeat(64),null,null,null))}
        override fun initialized()=reportsReady
        override fun snapshot()=state
        override fun onAuthorityChanged(){events.add("authority-changed")}
        override fun loadReports(){events.add("load")}
        override fun loadReports(filter:UserReportStatus?){events.add("load-filter");state=state.copy(statusFilter=filter)}
        override fun retry(){events.add("retry")}
        override fun loadNextPage(){events.add("more")}
        override fun loadNextRequestHistoryPage(){events.add("history-more")}
        override fun loadContent(reportId:String){loadedContent=reportId}
        override fun openDetail(reportId:String){events.add("detail:$reportId")}
        override fun loadRequestHistory(){events.add("history")}
        override fun submitCorrection(reportId:String,userDescription:UserReportCorrectionPatch<String>,categoryHint:UserReportCorrectionPatch<UserReportContentCategory>):Boolean{correction=Triple(reportId,userDescription,categoryHint);return submitAccepted}
        override fun submitRequest(reportId:String,requestType:UserReportRequestType,requestText:String):Boolean{deletion=Triple(reportId,requestType,requestText);return submitAccepted}
        override fun refreshDeletionStatus(requestId:String):Boolean{deletionStatusId=requestId;return true}
        override fun refreshRequestStatus(reportId:String,requestId:String):Boolean{requestStatusIds=reportId to requestId;return true}
        override fun revalidateAuthority(authority:UserReportAuthority?){events.add("revalidate-ui")}
        override fun render(state:UserReportUiState,authority:UserReportAuthority?){events.add("render")}
        override fun clearForAuthorityFence(){events.add("clear-ui")}
        override fun setStatusMessage(message:String){status=message}
        override fun correctionDraft()=draft
        override fun requestText()=request
        override fun toggleDescriptionClear(){events.add("toggle-clear")}
        override fun nextCategory(){events.add("next-category")}
        override fun foreground()=foreground
        override fun destroyed()=destroyed
        override fun removeTimer(task:Runnable){events.add("remove-timer")}
        override fun postTimer(task:Runnable,delayMs:Long):Boolean{timers.add(task to delayMs);return postAccepted}
        override fun confirm(title:String,message:String,negativeLabel:String,positiveLabel:String,onConfirmed:()->Unit){dialogTitle=title;positive=onConfirmed}
    }
    private companion object {
        const val ACTOR="123e4567-e89b-42d3-a456-426614174000";const val ORIGIN="https://gateway.example.test"
        const val REPORT="11111111-1111-4111-8111-111111111111";const val OTHER="22222222-2222-4222-8222-222222222222"
        const val REQUEST="33333333-3333-4333-8333-333333333333";const val REQUEST2="44444444-4444-4444-8444-444444444444"
        fun detail(id:String,latest:UserReportRequestSummary?=null)=UserReportDetail(id,"2026-10-06T00:00:00.000Z",UserReportStatus.RECEIVED,null,latest)
        fun summary(id:String,type:UserReportRequestType)=UserReportRequestSummary(id,type,UserReportRequestStatus.RECEIVED,1L,null,"2026-10-06T00:00:00.000Z","2026-10-06T00:00:00.000Z")
        fun completeFirstRun(epoch:Long)=FirstRunOnboardingSnapshot(EMAIL_ACCOUNT_ONBOARDING_POLICY_VERSION,FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4,epoch,3L,FirstRunOnboardingStage.COMPLETE,
            FirstRunAgeBand.VERIFIED_14_PLUS,listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,FirstRunOnboardingStage.PURPOSE_AND_SAFETY,FirstRunOnboardingStage.FP004_TRAINING).associateWith{FirstRunReceiptHash.fromSha256Hex("a".repeat(64))},null,FirstRunOpaqueActorBinding.fromProvider(ACTOR),null)
        fun backendSession(expires:Long):GatewayFieldSession {
            val seconds=expires/1_000L;val encodedActor=Base64.getUrlEncoder().withoutPadding().encodeToString(ACTOR.toByteArray());val device="user-report-test-device";val encodedDevice=Base64.getUrlEncoder().withoutPadding().encodeToString(device.toByteArray());val sid="b".repeat(32)
            val cookie="walksafe_field_session="+listOf("v7",encodedActor,"7","1",encodedDevice,"general",seconds.toString(),sid,"s".repeat(43)).joinToString(".")
            return GatewayFieldSession.backendAccountDeviceSession(ORIGIN,BackendAccountDeviceCookieBinding(ACTOR,7L,1L,device,sid,seconds*1_000L),cookie,seconds*1_000L)
        }
    }
}
