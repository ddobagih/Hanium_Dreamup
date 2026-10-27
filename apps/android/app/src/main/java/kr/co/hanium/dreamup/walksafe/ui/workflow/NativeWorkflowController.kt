package kr.co.hanium.dreamup.walksafe.ui.workflow

import kr.co.hanium.dreamup.walksafe.device.PhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.NativeDestinationStartCoordinator
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.ui.NativeScreenPresenter

/** Owns native page and explicit-start transactions; physical preparation remains its own boundary. */
internal class NativeWorkflowController(
    private val queries: EntryQueries,
    private val pages: PageUi,
    private val preparation: PreparationActions,
    private val destinations: DestinationTransactions,
) {
    enum class NativeUiPage { HOME, DESTINATION_SEARCH, DESTINATION_CONFIRM, GUIDANCE, VOICE_COMMAND, SETTINGS }

    data class NativePhoneMountingCheckRequest(
        val actorId: String,
        val gatewayGeneration: Long,
        val destination: DestinationSearchResult?,
        val checkRequest: PhoneMountingCheckRequest,
    )

    interface EntryQueries {
        fun shouldShowNativeHome(): Boolean
        fun nativeHomeFeatureContextAvailable(): Boolean
        fun isWalkSessionRuntimeActive(): Boolean
        fun nativeFeatureAvailable(page: NativeUiPage): Boolean
        fun deletionRecoveryRequired(): Boolean
        fun deletionProcessingBlocked(): Boolean
        fun walkingArea(): Boolean = false
    }

    interface FocusTarget {
        val isShown: Boolean
        fun post(action: Runnable)
        fun requestFocus()
        fun requestAccessibilityFocus()
    }

    interface PageUi {
        fun invalidatePagePresentationOwner()
        fun invalidateGuidanceStopConfirmation()
        fun cancelDeviceRuntimeCalibration()
        fun retireForegroundWake(preserveVoiceInteraction: Boolean)
        fun clearPageVoiceDialog()
        fun retireOutgoingVoiceInteraction(wasVoicePage: Boolean)
        fun displayPage(page: NativeUiPage): FocusTarget?
        fun updateLiveVisibility()
        fun readyForNativeRendering(): Boolean
        fun renderFrame(frame: NativeScreenPresenter.FrameFacts)
        fun showHomeLockNotice()
        fun resetSearchInput()
        fun listenForVoiceCommand()
        fun postVoiceStart(isCurrent: () -> Boolean)
        fun releaseCommandPageOwnership()
        fun clearDestinationFocus()
        fun updateNavigationStatus(message: String)
    }

    interface PreparationActions {
        fun beginNativeWalkFromHome(): Boolean
        fun continueNativeFeaturePreparation()
        fun cancelNativePrewalkPreparation(cancelFeatureEntry: Boolean)
        fun refreshForegroundHomeWakeListening()
        fun hasPreparationDialog(): Boolean
    }

    interface DestinationTransactions {
        fun captureNativeDestinationStartOwner(destination: DestinationSearchResult): (() -> Boolean)?
        fun currentDestinationSearchAllowsWork(): Boolean
        fun blockRouteMutationWhileDeviationChoicePending(): Boolean
        fun explainNavigationFeatureUnavailable()
        fun clearPendingExplicitRouteStart()
        fun cancelDestinationSearchLocation()
        fun requestDestinationSearchLocation()
        fun cancelSearchRequestsAndResetEntry()
        fun needsGuidancePreparationSpeech(): Boolean
        fun ownsExplicitLocationWait(destination: DestinationSearchResult): Boolean
        fun postNativeDestinationStart(destination: DestinationSearchResult)
    }

    var nativeUiPage = NativeUiPage.HOME
        set(value) {
            if (field != value) {
                field = value
                pages.invalidatePagePresentationOwner()
            }
        }
    var pendingNativeUiPage: NativeUiPage? = null
    var pendingUiDestination: DestinationSearchResult? = null
    var nativePrewalkStartEpoch: WalkRuntimeEpoch? = null
    @Volatile var nativePhoneMountingCheckRequest: NativePhoneMountingCheckRequest? = null
    private var nativeUiRendering = false
    private val startCoordinator = NativeDestinationStartCoordinator {
        if (pendingNativeUiPage == NativeUiPage.DESTINATION_CONFIRM) pendingNativeUiPage = null
    }

    val isDestinationStartDispatching: Boolean get() = startCoordinator.isDispatching

    fun clearPendingNativeDestinationStart() = startCoordinator.clear()

    fun retainNativeDestinationStart(destination: DestinationSearchResult): Boolean =
        startCoordinator.retain(destination) { destinations.captureNativeDestinationStartOwner(destination) }

    fun consumePendingNativeDestinationStart(): DestinationSearchResult? {
        if (pendingNativeUiPage != NativeUiPage.DESTINATION_CONFIRM) return null
        val destination = pendingUiDestination ?: run { clearPendingNativeDestinationStart(); return null }
        if (!retainNativeDestinationStart(destination)) return null
        return startCoordinator.consumableDestination()
    }

    fun postNativeDestinationStart(
        destination: DestinationSearchResult,
        post: (Runnable) -> Unit,
        navigationAdmitted: () -> Boolean,
        start: () -> Unit,
    ) {
        if (!retainNativeDestinationStart(destination)) return
        startCoordinator.post(post,
            admitted = { queries.isWalkSessionRuntimeActive() && navigationAdmitted() }, start = start)
    }

    fun handOffNativeDestinationStart(
        destination: DestinationSearchResult,
        prepare: (DestinationSearchResult) -> Unit,
        select: (DestinationSearchResult) -> Boolean,
        refreshUi: () -> Unit,
        navigationAdmitted: () -> Boolean,
    ) {
        if (!retainNativeDestinationStart(destination)) return
        startCoordinator.handOff(
            prepareIfNeeded = { if (destinations.needsGuidancePreparationSpeech()) prepare(destination) },
            admitted = navigationAdmitted,
            selectAndCheckExplicitWait = {
                destinations.clearPendingExplicitRouteStart()
                val started = select(destination)
                val handedToLocationWait = destinations.ownsExplicitLocationWait(destination)
                started || handedToLocationWait
            },
            refreshUi = refreshUi,
        )
    }

    fun cancelNativePendingFeatureEntry() {
        clearPendingNativeDestinationStart()
        pendingNativeUiPage = null
        if (nativePrewalkStartEpoch != null) preparation.cancelNativePrewalkPreparation(cancelFeatureEntry = false)
    }

    fun requestNativeFeature(page: NativeUiPage) {
        if (!queries.shouldShowNativeHome()) { renderMainUi(); return }
        if (queries.walkingArea() && page == NativeUiPage.VOICE_COMMAND) {
            pages.listenForVoiceCommand()
            return
        }
        if (page == NativeUiPage.VOICE_COMMAND || page == NativeUiPage.DESTINATION_SEARCH) {
            if (!queries.isWalkSessionRuntimeActive()) preparation.cancelNativePrewalkPreparation(cancelFeatureEntry = true)
            else cancelNativePendingFeatureEntry()
            showNativeUiPage(page)
            if (page == NativeUiPage.VOICE_COMMAND) pages.listenForVoiceCommand()
            return
        }
        if (queries.nativeFeatureAvailable(page)) {
            pendingNativeUiPage = null
            showNativeUiPage(page)
            return
        }
        pendingNativeUiPage = page
        if (preparation.beginNativeWalkFromHome()) {
            if (queries.nativeFeatureAvailable(page)) {
                pendingNativeUiPage = null
                showNativeUiPage(page)
                return
            }
            pendingNativeUiPage = null
            pages.showHomeLockNotice()
        } else if (pendingNativeUiPage == null && queries.shouldShowNativeHome()) pages.showHomeLockNotice()
        renderMainUi()
    }

    private fun NativeUiPage.isVoiceInteractionPage(): Boolean =
        this == NativeUiPage.VOICE_COMMAND || this == NativeUiPage.DESTINATION_SEARCH

    /** The ordinary wrapper resets ownership before the preserving overload is entered. */
    fun showNativeUiPage(page: NativeUiPage) {
        pages.releaseCommandPageOwnership()
        showNativeUiPage(page, preserveVoiceInteraction = false)
    }

    fun showNativeUiPage(page: NativeUiPage, preserveVoiceInteraction: Boolean) {
        if (queries.walkingArea() && page == NativeUiPage.HOME) {
            showNativeUiPage(NativeUiPage.GUIDANCE, preserveVoiceInteraction)
            return
        }
        if (nativeUiPage != page && !preserveVoiceInteraction) pages.invalidateGuidanceStopConfirmation()
        if (nativeUiPage != page) pages.cancelDeviceRuntimeCalibration()
        pages.retireForegroundWake(preserveVoiceInteraction)
        if (nativeUiPage != page) {
            if (!preserveVoiceInteraction || !nativeUiPage.isVoiceInteractionPage() || !page.isVoiceInteractionPage()) {
                pages.clearPageVoiceDialog()
            }
            if (!preserveVoiceInteraction) pages.retireOutgoingVoiceInteraction(nativeUiPage.isVoiceInteractionPage())
        }
        if ((page == NativeUiPage.HOME || page == NativeUiPage.SETTINGS) && !queries.walkingArea()) {
            if (queries.nativeHomeFeatureContextAvailable()) clearNativeDestinationSearchState()
            pendingNativeUiPage = null
            if (!queries.isWalkSessionRuntimeActive() && preparationNeedsCancellation()) {
                preparation.cancelNativePrewalkPreparation(cancelFeatureEntry = true)
            }
        }
        nativeUiPage = page
        if (page != NativeUiPage.DESTINATION_SEARCH && page != NativeUiPage.VOICE_COMMAND) {
            destinations.cancelDestinationSearchLocation()
        }
        val target = pages.displayPage(page)
        if (page == NativeUiPage.DESTINATION_SEARCH) destinations.requestDestinationSearchLocation()
        if (preserveVoiceInteraction) return
        target?.post {
            if (nativeUiPage == page && target.isShown) {
                target.requestFocus()
                target.requestAccessibilityFocus()
            }
        }
    }

    private fun preparationNeedsCancellation(): Boolean =
        nativePrewalkStartEpoch != null || preparation.hasPreparationDialog()

    fun renderMainUi() {
        pages.updateLiveVisibility()
        if (nativeUiRendering || !pages.readyForNativeRendering()) return
        nativeUiRendering = true
        var startVoice = false
        var startDestination: DestinationSearchResult? = null
        try {
            preparation.continueNativeFeaturePreparation()
            val homeAvailable = queries.shouldShowNativeHome()
            val deletionRecovery = queries.deletionRecoveryRequired()
            val forceSettings = deletionRecovery || queries.deletionProcessingBlocked()
            if (!homeAvailable && !forceSettings) {
                preparation.cancelNativePrewalkPreparation(cancelFeatureEntry = true)
                nativeUiPage = NativeUiPage.HOME
                pendingNativeUiPage = null
            } else if (forceSettings) {
                preparation.cancelNativePrewalkPreparation(cancelFeatureEntry = true)
                nativeUiPage = NativeUiPage.SETTINGS
                pendingNativeUiPage = null
            }
            val pending = pendingNativeUiPage
            if (homeAvailable && pending != null && queries.isWalkSessionRuntimeActive() && queries.nativeFeatureAvailable(pending)) {
                if (pending == NativeUiPage.DESTINATION_CONFIRM) startDestination = consumePendingNativeDestinationStart()
                else { pendingNativeUiPage = null; nativeUiPage = pending }
                startVoice = pending == NativeUiPage.VOICE_COMMAND
            }
            val settingsVisible = forceSettings || (homeAvailable && nativeUiPage == NativeUiPage.SETTINGS)
            val homeVisible = homeAvailable && !forceSettings && nativeUiPage == NativeUiPage.HOME
            val featureVisible = homeAvailable && !forceSettings && nativeUiPage in setOf(
                NativeUiPage.DESTINATION_SEARCH, NativeUiPage.DESTINATION_CONFIRM, NativeUiPage.GUIDANCE, NativeUiPage.VOICE_COMMAND)
            pages.renderFrame(NativeScreenPresenter.FrameFacts(nativeUiPage.name, homeAvailable, homeVisible,
                settingsVisible, featureVisible, forceSettings, deletionRecovery))
        } finally { nativeUiRendering = false }
        startDestination?.let { destinations.postNativeDestinationStart(it) }
        if (startVoice) pages.postVoiceStart {
            nativeUiPage == NativeUiPage.VOICE_COMMAND && queries.isWalkSessionRuntimeActive() &&
                queries.nativeFeatureAvailable(NativeUiPage.VOICE_COMMAND)
        }
        preparation.refreshForegroundHomeWakeListening()
    }

    fun openNativeDestinationConfirmation(result: DestinationSearchResult) {
        if (!destinations.currentDestinationSearchAllowsWork()) { destinations.explainNavigationFeatureUnavailable(); return }
        if (destinations.blockRouteMutationWhileDeviationChoicePending()) return
        clearPendingNativeDestinationStart()
        destinations.clearPendingExplicitRouteStart()
        pendingUiDestination = result
        pages.clearDestinationFocus()
        showNativeUiPage(NativeUiPage.DESTINATION_CONFIRM)
    }

    fun clearNativeDestinationSearchState() {
        clearPendingNativeDestinationStart()
        if (nativePrewalkStartEpoch != null && !queries.isWalkSessionRuntimeActive()) {
            preparation.cancelNativePrewalkPreparation(cancelFeatureEntry = true)
        }
        destinations.clearPendingExplicitRouteStart()
        destinations.cancelDestinationSearchLocation()
        destinations.cancelSearchRequestsAndResetEntry()
        pendingUiDestination = null
        pages.resetSearchInput()
    }

    fun cancelNativeDestinationSearchAndReturnHome() {
        cancelNativePendingFeatureEntry()
        clearNativeDestinationSearchState()
        pages.updateNavigationStatus("navigation=destination_search_cancelled")
        showNativeUiPage(NativeUiPage.HOME)
    }

    enum class BackAction { CANCEL_DESTINATION, CANCEL_GUIDANCE, CANCEL_VOICE, RETURN_HOME, CLOSE_CAMERA_OR_EXIT }

    fun backAction(): BackAction = when (nativeUiPage) {
        NativeUiPage.DESTINATION_SEARCH, NativeUiPage.DESTINATION_CONFIRM -> BackAction.CANCEL_DESTINATION
        NativeUiPage.GUIDANCE -> BackAction.CANCEL_GUIDANCE
        NativeUiPage.VOICE_COMMAND -> BackAction.CANCEL_VOICE
        NativeUiPage.SETTINGS -> BackAction.RETURN_HOME
        NativeUiPage.HOME -> BackAction.CLOSE_CAMERA_OR_EXIT
    }
}
