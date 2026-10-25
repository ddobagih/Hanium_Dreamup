package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction

import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchController
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
import kr.co.hanium.dreamup.walksafe.navigation.approximateDestinationDistanceKo
import kr.co.hanium.dreamup.walksafe.navigation.destinationDistanceKindKo
import kr.co.hanium.dreamup.walksafe.navigation.destinationOriginUncertaintyNoticeKo
import kr.co.hanium.dreamup.walksafe.navigation.displayAddressOrNull

/** Destination display only; search requests, selected destination and voice decisions keep their owners. */
internal class DestinationSearchPresenter {
    data class Candidate(val result: DestinationSearchResult, val number: Int, val details: String)

    interface RenderTarget {
        val hasVoiceRangeHeader: Boolean
        fun clearSearchResults()
        fun renderEmptySearch(message: String, statusCard: Boolean)
        fun renderSearchResults(originNotice: String?, candidates: List<Candidate>, voiceRange: String?,
            onSelected: (DestinationSearchResult) -> Unit)
        fun renderMoreResults(visible: Boolean)
        fun renderVoiceRange(label: String?)
        fun renderSearchAvailability(searchAvailable: Boolean, inFlight: Boolean, routeAvailable: Boolean, resultsReady: Boolean)
        fun renderGuidanceStatus(message: String)
        fun renderGuidanceMeasurements(message: String)
    }

    private var target: RenderTarget? = null
    /** Displayed voice page, not the dialog's authority or the search controller's result state. */
    var voiceRangeState: DestinationSearchVoiceState? = null
        private set

    fun bindPanel(panel: RenderTarget) { target = panel }

    fun renderSearch(
        currentSearch: () -> DestinationSearchController.State,
        searchAvailable: () -> Boolean,
        canLoadMore: () -> Boolean,
        onSelected: (DestinationSearchResult) -> Unit,
    ) {
        val panel = requireNotNull(target)
        panel.clearSearchResults()
        val state = currentSearch()
        if (state.results.isEmpty()) {
            val message = when {
                !searchAvailable() -> "필수 기능과 로그인 상태를 확인한 뒤 목적지를 검색하세요."
                state.locationMessage != null -> state.locationMessage
                state.query.isBlank() -> null
                state.inFlight -> "목적지를 검색하고 있습니다. 결과가 오면 바로 표시합니다."
                state.zeroResultQuery != state.query ->
                    "검색이 끝나지 않았습니다. 다시 검색하려면 검색을 누르거나 목적지를 말씀해 주세요."
                else -> "검색 결과가 없습니다. 다른 장소명으로 다시 검색해 주세요."
            }
            if (message != null) {
                panel.renderEmptySearch(message, !searchAvailable() || state.query.isNotBlank())
            }
            panel.renderMoreResults(false)
            return
        }
        val candidates = state.results.mapIndexed { index, result ->
            val details = "${result.displayAddressOrNull() ?: "주소 없음"} · " +
                approximateDestinationDistanceKo(result.distanceM, state.origin?.accuracyM) +
                (destinationDistanceKindKo(result.distanceSource)?.let { " ($it)" } ?: "")
            Candidate(result, index + 1, details)
        }
        panel.renderSearchResults(destinationOriginUncertaintyNoticeKo(state.origin), candidates,
            voiceRangeLabel(state), onSelected)
        panel.renderMoreResults(canLoadMore())
    }

    /** A voice page change updates only the existing header; it never rebuilds candidates or moves focus. */
    fun refreshVoiceRange(state: DestinationSearchVoiceState?, currentSearch: () -> DestinationSearchController.State) {
        voiceRangeState = state
        val panel = target?.takeIf { it.hasVoiceRangeHeader } ?: return
        panel.renderVoiceRange(voiceRangeLabel(currentSearch()))
    }

    private fun voiceRangeLabel(search: DestinationSearchController.State): String? = voiceRangeState
        ?.takeIf { it.query == search.query && it.results == search.results }
        ?.voicePageRangeLabelKo()

    fun renderAvailability(searchAvailable: Boolean, inFlight: Boolean, routeAvailable: Boolean, resultsReady: Boolean) =
        requireNotNull(target).renderSearchAvailability(searchAvailable, inFlight, routeAvailable, resultsReady)

    fun renderGuidanceStatus(guidanceMessage: String, retryAvailable: Boolean, speechFailure: String?,
        resumeAction: DestinationGuidanceResumeAction = DestinationGuidanceResumeAction.NONE,
    ) {
        val retryMessage = when (resumeAction) {
            DestinationGuidanceResumeAction.PREPARING ->
                "\n방향 교정 안내와 관측을 진행하고 있습니다. 준비가 끝날 때까지 기다려 주세요."
            DestinationGuidanceResumeAction.NOTICE ->
                "\n안전 제한 안내 확인을 눌러 안내를 끝까지 들은 뒤 재개 여부를 확인하세요."
            DestinationGuidanceResumeAction.CONFIRM ->
                "\n보행 안내 재개 확인을 눌러 현재 준비 상태를 확인한 뒤 이어가세요."
            DestinationGuidanceResumeAction.PENDING ->
                "\n재개 안내와 응답을 확인하고 있습니다. 확인이 끝날 때까지 기다려 주세요."
            DestinationGuidanceResumeAction.NONE ->
                if (retryAvailable && !guidanceMessage.contains("다시 시도하거나 안내를 종료하세요."))
                    "\n다시 시도하거나 안내를 종료하세요." else ""
        }
        requireNotNull(target).renderGuidanceStatus(
            listOfNotNull(guidanceMessage + retryMessage, speechFailure).joinToString("\n"))
    }

    /** Kept separate so the caller checks measurement binding before observing diagnostics. */
    fun renderGuidanceMeasurements(environmentDetail: String, calibrationMessage: String) {
        requireNotNull(target).renderGuidanceMeasurements(
            listOf(environmentDetail.trim(), calibrationMessage).filter { it.isNotBlank() }.joinToString("\n"))
    }
}
