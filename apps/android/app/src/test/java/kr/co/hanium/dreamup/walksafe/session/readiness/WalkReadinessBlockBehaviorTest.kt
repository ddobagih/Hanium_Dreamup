package kr.co.hanium.dreamup.walksafe.session.readiness

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkReadinessBlockBehaviorTest {
    @Test fun constructionIsInertAndFullyReadyPathUsesLazyOrderedSources() {
        val f = ReadinessBlockFixture(); assertTrue(f.trace.isEmpty())
        assertNull(f.check())
        assertEquals(listOf("onboardingComplete", "developmentBypassEnabled", "observedPermissions", "voiceFeatureEnabled",
            "offlineTtsAllowed", "priorityOnboarding", "quickStartEnabled", "reporterUserId", "gatewayReadyForCurrentActor",
            "officialEnvironmentBlockReason", "phoneMountingBlockReason", "walkState", "missingRequiredPermissions",
            "runningDetectorBlocksEntry", "cameraEnabled", "locationFeatureEnabled"), f.trace)
        assertEquals(WalkSessionAction.START_WALK, f.requestedAction)
        assertSame(f.decision, f.seenTtsDecision); assertSame(f.decision, f.seenOnboardingDecision)
    }
    @Test fun firstRunBlockPrecedesEvenBypassAndBlockedCapability() {
        val f = ReadinessBlockFixture(); f.onboarded = false; f.bypass = true
        assertTrue(f.check().orEmpty().contains("첫 실행"))
        assertEquals(listOf("onboardingComplete"), f.trace)
    }
    @Test fun developmentBypassDoesNotQueryCapabilityDependenciesOrOwners() {
        val f = ReadinessBlockFixture(); f.bypass = true
        assertNull(f.check(f.decision.copy(tier = WalkSafeStartupCapabilityTier.BLOCKED)))
        assertEquals(listOf("onboardingComplete", "developmentBypassEnabled"), f.trace)
    }
    @Test fun capabilityBlockReturnsActualNoticeBeforePermissionSnapshot() {
        val f = ReadinessBlockFixture()
        assertEquals("capability unavailable", f.check(f.decision.copy(tier = WalkSafeStartupCapabilityTier.BLOCKED)))
        assertFalse(f.trace.contains("observedPermissions"))
    }
    @Test fun actualWholeWalkPermissionPolicyBlocksBeforeTtsAndActorReads() {
        val f = ReadinessBlockFixture(); f.permissions = ObservedPermissionSnapshot(false, false, true, true)
        assertTrue(f.check().orEmpty().contains("카메라와 정확한 위치 권한"))
        assertEquals("observedPermissions", f.trace.last())
        // A single unavailable source is still decided by later feature gates, not an invented whole-stop.
        val g = ReadinessBlockFixture(); g.permissions = ObservedPermissionSnapshot(false, true, true, true)
        assertNull(g.check()); assertTrue(g.trace.contains("locationFeatureEnabled"))
    }
    @Test fun disabledVoiceShortCircuitsOfflineEngineQueryAndReturnsOriginalNotice() {
        val f = ReadinessBlockFixture(); f.voice = false
        assertEquals("Korean TTS unavailable", f.check())
        assertEquals("voiceFeatureEnabled", f.trace.last()); assertNull(f.seenTtsDecision)
    }
    @Test fun onboardingDecisionIsReadBeforeQuickStartButQuickStartMayBypassOnlyThatBlock() {
        val f = ReadinessBlockFixture(); f.onboardingBlock = PriorityUserBlockReason.SAFETY_EDUCATION_NOT_REVIEWED
        assertEquals(f.onboardingBlock!!.noticeKo, f.check())
        assertEquals(listOf("priorityOnboarding", "quickStartEnabled"), f.trace.takeLast(2))
        val g = ReadinessBlockFixture(); g.quick = true; g.onboardingBlock = f.onboardingBlock; g.reporter = null
        assertTrue(g.check().orEmpty().contains("로그인이 완료"))
        assertEquals("reporterUserId", g.trace.last())
    }
    @Test fun accountAndGatewayBlockBeforeEnvironmentAndMountingQueries() {
        val f = ReadinessBlockFixture(); f.reporter = null
        assertTrue(f.check().orEmpty().contains("로그인이 완료")); assertFalse(f.trace.contains("gatewayReadyForCurrentActor"))
        val g = ReadinessBlockFixture(); g.gatewayReady = false
        assertTrue(g.check().orEmpty().contains("게이트웨이")); assertFalse(g.trace.contains("officialEnvironmentBlockReason"))
    }
    @Test fun environmentAndMountingReasonsAreReturnedUnmodifiedAndStopLaterQueries() {
        val f = ReadinessBlockFixture(); f.environmentBlock = "actual environment evidence"
        assertEquals(f.environmentBlock, f.check()); assertFalse(f.trace.contains("phoneMountingBlockReason"))
        val g = ReadinessBlockFixture(); g.mountBlock = "actual mounting epoch"
        assertEquals(g.mountBlock, g.check()); assertFalse(g.trace.contains("walkState"))
    }
    @Test fun pausedSessionChecksResumePermissionsAndFormatsOnlyActualMissingList() {
        val f = ReadinessBlockFixture(); f.state = WalkSessionState.PAUSED; f.missing = listOf("precise-location")
        assertEquals("필수 권한이 필요합니다: precise-location", f.check())
        assertEquals(WalkSessionAction.RESUME_WALK, f.requestedAction)
        assertSame(f.missing, f.labelInput); assertFalse(f.trace.contains("runningDetectorBlocksEntry"))
    }
    @Test fun failedModelInferenceBlocksBeforeCameraAndLocation() {
        val f = ReadinessBlockFixture(); f.modelBlocks = true
        assertEquals(RunningDetectorReadiness.WALK_BLOCK_NOTICE_KO, f.check())
        assertFalse(f.trace.contains("cameraEnabled")); assertFalse(f.trace.contains("locationFeatureEnabled"))
    }
    @Test fun cameraBlockDistinguishesFailedLoadWithoutEagerAvailableRead() {
        val f = ReadinessBlockFixture(); f.camera = false
        assertTrue(f.check().orEmpty().contains("카메라 장애물 안내"))
        assertFalse(f.trace.contains("detectorAvailable")); assertFalse(f.trace.contains("locationFeatureEnabled"))
        val g = ReadinessBlockFixture(); g.camera = false; g.loadAttempted = true; g.detector = false
        assertTrue(g.check().orEmpty().contains("모델을 불러오지 못해")); assertEquals("detectorAvailable", g.trace.last())
    }
    @Test fun locationBlockOccursOnlyAfterAllPreviousRequirementsPass() {
        val f = ReadinessBlockFixture(); f.location = false
        assertTrue(f.check().orEmpty().contains("정확한 위치를 사용할 수 없어"))
        assertEquals("locationFeatureEnabled", f.trace.last())
    }
}

private class ReadinessBlockFixture {
    val trace = mutableListOf<String>()
    var onboarded = true; var bypass = false; var voice = true; var offlineTts = true; var quick = false
    var reporter: String? = "actor"; var gatewayReady = true; var environmentBlock: String? = null; var mountBlock: String? = null
    var state = WalkSessionState.READY; var missing = emptyList<String>(); var modelBlocks = false
    var camera = true; var loadAttempted = false; var detector = true; var location = true
    var permissions = ObservedPermissionSnapshot(true, true, true, true)
    var onboardingBlock: PriorityUserBlockReason? = null
    var seenTtsDecision: WalkSafeStartupCapabilityDecision? = null; var seenOnboardingDecision: WalkSafeStartupCapabilityDecision? = null
    var requestedAction: WalkSessionAction? = null; var labelInput: List<String>? = null
    val decision = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "capability unavailable")
    val sources = Proxy.newProxyInstance(WalkReadinessBlockSources::class.java.classLoader, arrayOf(WalkReadinessBlockSources::class.java)) { _, method, arguments ->
        val args = arguments.orEmpty(); trace += method.name
        when(method.name) {
            "onboardingComplete" -> onboarded
            "developmentBypassEnabled" -> bypass
            "observedPermissions" -> permissions
            "voiceFeatureEnabled" -> voice
            "offlineTtsAllowed" -> { seenTtsDecision = args[0] as WalkSafeStartupCapabilityDecision; offlineTts }
            "priorityOnboarding" -> { seenOnboardingDecision = args[0] as WalkSafeStartupCapabilityDecision
                PriorityUserOnboardingDecision(PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION, null, onboardingBlock, emptyList(), false) }
            "quickStartEnabled" -> quick
            "reporterUserId" -> reporter
            "gatewayReadyForCurrentActor" -> gatewayReady
            "officialEnvironmentBlockReason" -> environmentBlock
            "phoneMountingBlockReason" -> mountBlock
            "walkState" -> state
            "missingRequiredPermissions" -> { requestedAction = args[0] as WalkSessionAction; missing }
            "permissionGuidanceLabels" -> { @Suppress("UNCHECKED_CAST") val supplied = args[0] as List<String>; labelInput = supplied; supplied.joinToString() }
            "runningDetectorBlocksEntry" -> modelBlocks
            "cameraEnabled" -> camera
            "detectorLoadAttempted" -> loadAttempted
            "detectorAvailable" -> detector
            "locationFeatureEnabled" -> location
            else -> error(method.name)
        }
    } as WalkReadinessBlockSources
    val controller = WalkReadinessBlockController(sources, "Korean TTS unavailable")
    fun check(value: WalkSafeStartupCapabilityDecision = decision) = controller.walkSessionReadinessBlockReason(value)
}
