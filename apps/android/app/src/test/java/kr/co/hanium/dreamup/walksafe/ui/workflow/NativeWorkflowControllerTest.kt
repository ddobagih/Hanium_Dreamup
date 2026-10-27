package kr.co.hanium.dreamup.walksafe.ui.workflow

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.ui.NativeScreenPresenter
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

class NativeWorkflowControllerTest {
    @Test fun constructionDoesNotReadQueriesOrExecuteEffects() {
        fun <T> forbidden(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            error("Constructor invoked ${method.name}")
        })!!
        val controller = NativeWorkflowController(forbidden(NativeWorkflowController.EntryQueries::class.java),
            forbidden(NativeWorkflowController.PageUi::class.java), forbidden(NativeWorkflowController.PreparationActions::class.java),
            forbidden(NativeWorkflowController.DestinationTransactions::class.java))
        assertEquals(NativeUiPage.HOME, controller.nativeUiPage)
        assertNull(controller.pendingUiDestination)
        assertNull(controller.nativePhoneMountingCheckRequest)
    }

    @Test fun pageSetterInvalidatesOnlyAfterAnActualPageChange() {
        val f = Fixture()
        f.controller.nativeUiPage = NativeUiPage.HOME
        assertTrue(f.effects.isEmpty())
        f.controller.nativeUiPage = NativeUiPage.SETTINGS
        assertEquals(listOf("invalidatePage"), f.effects)
        f.controller.nativeUiPage = NativeUiPage.SETTINGS
        assertEquals(1, f.effects.count { it == "invalidatePage" })
    }

    @Test fun searchPageRetiresOldInteractionBeforeRenderingAndRequestingLocation() {
        val f = Fixture()
        f.controller.showNativeUiPage(NativeUiPage.DESTINATION_SEARCH)
        assertEquals(listOf("releaseCommandPage", "invalidateStop", "cancelCalibration", "retireWake:false",
            "clearDialog", "retireOutgoing:false", "invalidatePage", "display:DESTINATION_SEARCH", "liveVisibility",
            "frame:DESTINATION_SEARCH", "refreshWake", "requestSearchLocation", "postFocus"), f.effects)
        f.focusQueue.single().run()
        assertEquals(listOf("requestFocus", "accessibilityFocus"), f.effects.takeLast(2))
    }

    @Test fun preservingVoiceInteractionDoesNotRetireItsInputOrPostFocus() {
        val f = Fixture()
        f.controller.nativeUiPage = NativeUiPage.VOICE_COMMAND
        f.effects.clear()
        f.controller.showNativeUiPage(NativeUiPage.DESTINATION_SEARCH, preserveVoiceInteraction = true)
        assertFalse(f.effects.contains("releaseCommandPage"))
        assertFalse(f.effects.contains("invalidateStop"))
        assertFalse(f.effects.any { it.startsWith("retireOutgoing:") })
        assertFalse(f.effects.contains("clearDialog"))
        assertFalse(f.effects.contains("postFocus"))
        assertTrue(f.effects.contains("retireWake:true"))
        assertTrue(f.effects.contains("requestSearchLocation"))
    }

    @Test fun focusPostedForAnOldPageDoesNotFocusTheNewPage() {
        val f = Fixture()
        f.controller.showNativeUiPage(NativeUiPage.DESTINATION_SEARCH)
        f.controller.nativeUiPage = NativeUiPage.SETTINGS
        f.effects.clear()
        f.focusQueue.single().run()
        assertTrue(f.effects.isEmpty())
    }

    @Test fun renderingReentryUpdatesVisibilityButDoesNotReadReadinessOrDrawTwice() {
        val f = Fixture()
        f.onFrame = { f.controller.renderMainUi() }
        f.controller.renderMainUi()
        assertEquals(2, f.effects.count { it == "liveVisibility" })
        assertEquals(1, f.readinessReads)
        assertEquals(1, f.frames.size)
        f.onFrame = null
        f.controller.renderMainUi()
        assertEquals(2, f.frames.size)
    }

    @Test fun unavailableViewsPreventPreparationAndAuthorityQueries() {
        val f = Fixture()
        f.ready = false
        f.controller.renderMainUi()
        assertEquals(listOf("liveVisibility"), f.effects)
        assertEquals(1, f.readinessReads)
        assertTrue(f.queries.isEmpty())
        assertEquals(0, f.continuationCount)
    }

    @Test fun deletionRecoveryDoesNotReadTheProcessingGate() {
        val f = Fixture()
        f.deletionRecovery = true
        f.controller.renderMainUi()
        assertTrue(f.queries.contains("deletionRecovery"))
        assertFalse(f.queries.contains("processingBlocked"))
        assertEquals(NativeUiPage.SETTINGS, f.controller.nativeUiPage)
        assertTrue(f.frames.single().forceSettings)
    }

    @Test fun postedVoiceStartRejectsAChangedPageBeforeReadingRuntimeAdmission() {
        val f = Fixture()
        f.active = true
        f.featureAvailable = true
        f.controller.pendingNativeUiPage = NativeUiPage.VOICE_COMMAND
        f.controller.renderMainUi()
        assertEquals(NativeUiPage.VOICE_COMMAND, f.controller.nativeUiPage)
        assertNull(f.controller.pendingNativeUiPage)
        assertEquals(1, f.voiceStartQueue.size)
        f.controller.nativeUiPage = NativeUiPage.HOME
        f.queries.clear()
        assertFalse(f.voiceStartQueue.single().invoke())
        assertTrue(f.queries.isEmpty())
    }

    @Test fun renderingFailureReleasesTheReentryGuard() {
        val f = Fixture()
        f.onFrame = { error("draw failed") }
        assertThrows(IllegalStateException::class.java) { f.controller.renderMainUi() }
        f.onFrame = null
        f.controller.renderMainUi()
        assertEquals(2, f.frames.size)
    }

    @Test fun deniedEntryDoesNotReadFeatureOrRuntimeAdmission() {
        val f = Fixture()
        f.homeAvailable = false
        f.controller.requestNativeFeature(NativeUiPage.DESTINATION_CONFIRM)
        assertFalse(f.queries.contains("feature:DESTINATION_CONFIRM"))
        assertFalse(f.queries.contains("runtime"))
        assertNull(f.controller.pendingUiDestination)
    }

    @Test fun explicitPendingIntentExistsBeforePhysicalPreparationIsRequested() {
        val f = Fixture()
        f.beginWalk = {
            assertEquals(NativeUiPage.DESTINATION_CONFIRM, f.controller.pendingNativeUiPage)
            f.controller.nativePrewalkStartEpoch = WalkRuntimeEpoch("walk", 3)
            false
        }
        f.controller.requestNativeFeature(NativeUiPage.DESTINATION_CONFIRM)
        assertEquals(NativeUiPage.DESTINATION_CONFIRM, f.controller.pendingNativeUiPage)
        assertEquals(WalkRuntimeEpoch("walk", 3), f.controller.nativePrewalkStartEpoch)
    }

    @Test fun duplicatePostedStartUsesOneApprovalAndOneQueuedStart() {
        val f = Fixture()
        f.active = true
        val destination = destination()
        f.controller.pendingUiDestination = destination
        f.controller.postNativeDestinationStart(destination, f.startQueue::add, { true }) {
            f.startCount += 1
            f.controller.clearPendingNativeDestinationStart()
        }
        f.controller.postNativeDestinationStart(destination, f.startQueue::add, { true }) { f.startCount += 1 }
        assertEquals(1, f.startQueue.size)
        assertEquals(1, f.captureCount)
        f.startQueue.single().run()
        f.startQueue.single().run()
        assertEquals(1, f.startCount)
    }

    @Test fun actorGatewayEpochDestinationAndRequestReplacementsRejectPostedApproval() {
        for (replacement in listOf("actor", "gateway", "epoch", "destination", "request")) {
            val f = Fixture()
            f.active = true
            val destination = destination()
            f.controller.pendingUiDestination = destination
            f.controller.nativePhoneMountingCheckRequest = mountingRequest(destination)
            f.controller.postNativeDestinationStart(destination, f.startQueue::add, { error("stale approval reached navigation") }) {
                error("stale approval started")
            }
            when (replacement) {
                "actor" -> f.actor = "replacement"
                "gateway" -> f.gateway += 1
                "epoch" -> f.epoch += 1
                "destination" -> f.controller.pendingUiDestination = destination.copy()
                "request" -> f.controller.nativePhoneMountingCheckRequest = mountingRequest(destination)
            }
            f.startQueue.single().run()
            assertNull(replacement, f.controller.pendingNativeUiPage)
        }
    }

    @Test fun explicitLocationWaitAndAdmittedOrdinaryFailureBothConsumeTheApproval() {
        for (ownedWait in listOf(true, false)) {
            val f = Fixture()
            f.active = true
            f.ownedWait = ownedWait
            val destination = destination()
            f.controller.pendingUiDestination = destination
            var refreshes = 0
            f.controller.handOffNativeDestinationStart(destination, {}, { false }, { refreshes += 1 }, { true })
            assertNull(f.controller.pendingNativeUiPage)
            assertEquals(1, refreshes)
            assertTrue(f.effects.indexOf("clearExplicit") < f.effects.indexOf("ownedWait"))
        }
    }

    @Test fun closedNavigationAdmissionRetainsTheApprovalWithoutSelecting() {
        val f = Fixture()
        f.active = true
        val destination = destination()
        f.controller.pendingUiDestination = destination
        f.controller.handOffNativeDestinationStart(destination, {}, { error("admission closed") }, {}, { false })
        assertEquals(NativeUiPage.DESTINATION_CONFIRM, f.controller.pendingNativeUiPage)
    }

    @Test fun clearingFeatureIntentCancelsPrewalkAfterClearingThePendingPage() {
        val f = Fixture()
        f.controller.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
        f.controller.nativePrewalkStartEpoch = WalkRuntimeEpoch("walk", 4)
        f.cancelPrewalk = {
            assertNull(f.controller.pendingNativeUiPage)
            f.controller.nativePrewalkStartEpoch = null
        }
        f.controller.cancelNativePendingFeatureEntry()
        assertEquals(listOf("cancelPrewalk:false"), f.effects)
        assertNull(f.controller.nativePrewalkStartEpoch)
    }

    @Test fun backActionKeepsSearchGuidanceVoiceSettingsAndCameraBoundariesDistinct() {
        val f = Fixture()
        val expected = mapOf(
            NativeUiPage.DESTINATION_SEARCH to NativeWorkflowController.BackAction.CANCEL_DESTINATION,
            NativeUiPage.DESTINATION_CONFIRM to NativeWorkflowController.BackAction.CANCEL_DESTINATION,
            NativeUiPage.GUIDANCE to NativeWorkflowController.BackAction.CANCEL_GUIDANCE,
            NativeUiPage.VOICE_COMMAND to NativeWorkflowController.BackAction.CANCEL_VOICE,
            NativeUiPage.SETTINGS to NativeWorkflowController.BackAction.RETURN_HOME,
            NativeUiPage.HOME to NativeWorkflowController.BackAction.CLOSE_CAMERA_OR_EXIT,
        )
        for ((page, action) in expected) {
            f.controller.nativeUiPage = page
            assertEquals(action, f.controller.backAction())
        }
    }

    private class Fixture {
        var actor: String? = "actor"
        var homeAvailable = true
        var active = false
        var gateway = 1L
        var epoch = 1L
        var ownedWait = false
        var ready = true
        var featureAvailable = false
        var deletionRecovery = false
        var continuationCount = 0
        var readinessReads = 0
        var captureCount = 0
        var startCount = 0
        var beginWalkCount = 0
        var onFrame: (() -> Unit)? = null
        var beginWalk: (() -> Boolean)? = null
        var cancelPrewalk: (() -> Unit)? = null
        val effects = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val frames = mutableListOf<NativeScreenPresenter.FrameFacts>()
        val focusQueue = mutableListOf<Runnable>()
        val startQueue = mutableListOf<Runnable>()
        val voiceStartQueue = mutableListOf<() -> Boolean>()
        private val focus = object : NativeWorkflowController.FocusTarget {
            override val isShown: Boolean get() = true
            override fun post(action: Runnable) { effects += "postFocus"; focusQueue += action }
            override fun requestFocus() { effects += "requestFocus" }
            override fun requestAccessibilityFocus() { effects += "accessibilityFocus" }
        }
        private fun <T> proxy(type: Class<T>, body: (String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> body(method.name, args ?: emptyArray()) })!!

        lateinit var controller: NativeWorkflowController
            private set

        init { controller = NativeWorkflowController(
            proxy(NativeWorkflowController.EntryQueries::class.java) { name, args -> when (name) {
                "shouldShowNativeHome" -> { queries += "homeAvailable"; homeAvailable }
                "walkingArea" -> false
                "nativeHomeFeatureContextAvailable" -> false
                "isWalkSessionRuntimeActive" -> { queries += "runtime"; active }
                "nativeFeatureAvailable" -> { queries += "feature:${args[0]}"; featureAvailable }
                "deletionRecoveryRequired" -> { queries += "deletionRecovery"; deletionRecovery }
                "deletionProcessingBlocked" -> { queries += "processingBlocked"; false }
                else -> error("Unexpected query $name")
            } },
            proxy(NativeWorkflowController.PageUi::class.java) { name, args -> when (name) {
                "invalidatePagePresentationOwner" -> { effects += "invalidatePage"; null }
                "invalidateGuidanceStopConfirmation" -> { effects += "invalidateStop"; null }
                "cancelDeviceRuntimeCalibration" -> { effects += "cancelCalibration"; null }
                "retireForegroundWake" -> { effects += "retireWake:${args[0]}"; null }
                "clearPageVoiceDialog" -> { effects += "clearDialog"; null }
                "retireOutgoingVoiceInteraction" -> { effects += "retireOutgoing:${args[0]}"; null }
                "displayPage" -> { effects += "display:${args[0]}"; controller.renderMainUi(); focus }
                "updateLiveVisibility" -> { effects += "liveVisibility"; null }
                "readyForNativeRendering" -> { readinessReads += 1; ready }
                "renderFrame" -> { val frame = args[0] as NativeScreenPresenter.FrameFacts; frames += frame; effects += "frame:${frame.pageName}"; onFrame?.invoke(); null }
                "releaseCommandPageOwnership" -> { effects += "releaseCommandPage"; null }
                "clearDestinationFocus" -> { effects += "clearFocus"; null }
                "listenForVoiceCommand" -> { effects += "listenVoice"; null }
                "showHomeLockNotice" -> { effects += "lockNotice"; null }
                "resetSearchInput" -> { effects += "resetSearchInput"; null }
                "updateNavigationStatus" -> { effects += "navigationStatus:${args[0]}"; null }
                "postVoiceStart" -> { @Suppress("UNCHECKED_CAST") val isCurrent = args[0] as () -> Boolean; voiceStartQueue += isCurrent; null }
                else -> error("Unexpected page effect $name")
            } },
            proxy(NativeWorkflowController.PreparationActions::class.java) { name, args -> when (name) {
                "beginNativeWalkFromHome" -> { beginWalkCount += 1; beginWalk?.invoke() ?: false }
                "continueNativeFeaturePreparation" -> { continuationCount += 1; null }
                "cancelNativePrewalkPreparation" -> { effects += "cancelPrewalk:${args[0]}"; cancelPrewalk?.invoke(); null }
                "refreshForegroundHomeWakeListening" -> { effects += "refreshWake"; null }
                "hasPreparationDialog" -> false
                else -> error("Unexpected preparation $name")
            } },
            proxy(NativeWorkflowController.DestinationTransactions::class.java) { name, args -> when (name) {
                "captureNativeDestinationStartOwner" -> capture(args[0] as DestinationSearchResult)
                "currentDestinationSearchAllowsWork" -> true
                "blockRouteMutationWhileDeviationChoicePending" -> false
                "explainNavigationFeatureUnavailable" -> { effects += "explainUnavailable"; null }
                "clearPendingExplicitRouteStart" -> { effects += "clearExplicit"; null }
                "cancelDestinationSearchLocation" -> { effects += "cancelSearchLocation"; null }
                "requestDestinationSearchLocation" -> { effects += "requestSearchLocation"; null }
                "cancelSearchRequestsAndResetEntry" -> { effects += "cancelSearchRequests"; null }
                "needsGuidancePreparationSpeech" -> false
                "ownsExplicitLocationWait" -> { effects += "ownedWait"; ownedWait }
                "postNativeDestinationStart" -> error("No renderer start expected")
                else -> error("Unexpected destination $name")
            } },
        )
        }

        private fun capture(destination: DestinationSearchResult): (() -> Boolean)? {
            if (controller.pendingUiDestination !== destination || !homeAvailable || !active) return null
            captureCount += 1
            val expectedActor = actor
            val expectedGateway = gateway
            val expectedEpoch = epoch
            val expectedRequest = controller.nativePhoneMountingCheckRequest
            controller.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
            return {
                controller.pendingNativeUiPage == NativeUiPage.DESTINATION_CONFIRM &&
                    controller.pendingUiDestination === destination && homeAvailable && active &&
                    actor == expectedActor && gateway == expectedGateway && epoch == expectedEpoch &&
                    controller.nativePhoneMountingCheckRequest === expectedRequest
            }
        }
    }

    companion object {
        private fun destination() = DestinationSearchResult("id", "장소", RoutePoint(37.1, 127.1), null, null, null, null)
        private fun mountingRequest(destination: DestinationSearchResult) = NativePhoneMountingCheckRequest("actor", 1,
            destination, PhoneMountingCheckRequest(WalkRuntimeEpoch("walk", 1), 1, 10))
    }
}
