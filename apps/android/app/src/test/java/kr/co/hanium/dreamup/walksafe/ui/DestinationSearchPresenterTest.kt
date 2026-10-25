package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

class DestinationSearchPresenterTest {
    private fun candidate(index: Int, distance: Int? = 73) = DestinationSearchResult(
        "id-$index", "장소 $index", RoutePoint(37.5 + index * .001, 127.0),
        "주소 $index", null, null, distance, distanceSource = DestinationDistanceSource.STRAIGHT_LINE,
    )

    private fun search(query: String = "약국", results: List<DestinationSearchResult> = emptyList(),
        inFlight: Boolean = false, zeroResultQuery: String? = null, locationMessage: String? = null,
        origin: DestinationSearchOrigin? = null) = DestinationSearchController.State(
        inFlight = inFlight, generation = 42, query = query, zeroResultQuery = zeroResultQuery,
        page = 1, providerHasMore = null, origin = origin, results = results,
        locationMessage = locationMessage, pendingVoiceQuery = null, pendingVoicePage = null, voicePage = null,
    )

    @Test fun unfinishedCancelledSearchIsNotPresentedAsZeroResults() {
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderSearch(search(), true, false) { fail("Rendering selected a destination") }
        assertTrue(view.emptyMessage!!.startsWith("검색이 끝나지 않았습니다."))
        assertEquals(listOf("clear", "empty", "more:false"), view.events)
        presenter.renderSearch(search(zeroResultQuery = "약국"), true, false) { }
        assertEquals("검색 결과가 없습니다. 다른 장소명으로 다시 검색해 주세요.", view.emptyMessage)
    }

    @Test fun availabilityAndLocationKeepTheirMessagePrecedenceWhileBlankQueryHasNoPrompt() {
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderSearch(search(query = "", inFlight = true, locationMessage = "새 위치 대기"), false, true) { }
        assertEquals("필수 기능과 로그인 상태를 확인한 뒤 목적지를 검색하세요.", view.emptyMessage)
        assertTrue(view.statusCard)
        presenter.renderSearch(search(query = "", inFlight = true, locationMessage = "새 위치 대기"), true, true) { }
        assertEquals("새 위치 대기", view.emptyMessage)
        view.events.clear()
        presenter.renderSearch(search(query = "", inFlight = true), true, true) { }
        assertNull(view.emptyMessage)
        assertEquals(listOf("clear", "more:false"), view.events)
        presenter.renderSearch(search(inFlight = true), true, true) { }
        assertTrue(view.emptyMessage!!.startsWith("목적지를 검색하고 있습니다."))
        assertEquals("more:false", view.events.last())
    }

    @Test fun candidateOrderNumbersCoordinateEvidenceAndSelectedObjectStayIntact() {
        val first = candidate(1).copy(coordinateEvidence = DestinationCoordinateEvidence(
            DestinationCoordinateKind.FRONT, listOf(DestinationCoordinateCandidate(
                DestinationCoordinateKind.FRONT, RoutePoint(37.0, 127.0)))))
        val second = candidate(2)
        val results = listOf(first, second)
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        var selected: DestinationSearchResult? = null
        presenter.renderSearch(search(results = results), true, false) { selected = it }
        assertEquals(listOf(1, 2), view.candidates.map { it.number })
        assertSame(first, view.candidates[0].result)
        assertSame(second, view.candidates[1].result)
        assertNull(selected)
        view.selection!!(view.candidates[1].result)
        assertSame(second, selected)
        assertSame(first.coordinateEvidence, view.candidates[0].result.coordinateEvidence)
    }

    @Test fun candidateDistanceKeepsOriginUncertaintyAndDistanceKind() {
        val origin = DestinationSearchOrigin(RoutePoint(37.0, 127.0), 120f)
        val result = candidate(1, 73)
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderSearch(search(results = listOf(result), origin = origin), true, true) { }
        assertTrue(view.candidates.single().details.contains(approximateDestinationDistanceKo(73, 120f)))
        assertTrue(view.candidates.single().details.contains(destinationDistanceKindKo(result.distanceSource)!!))
        assertEquals(destinationOriginUncertaintyNoticeKo(origin), view.originNotice)
        assertFalse(view.candidates.single().details.contains("73m"))
        assertEquals("more:true", view.events.last())
    }

    @Test fun voicePageArrivingBeforeViewBindingIsStoredWithoutReadingSearch() {
        val presenter = DestinationSearchPresenter()
        val page = DestinationSearchVoiceState("약국", listOf(candidate(1)))
        presenter.refreshVoiceRange(page) { error("No header: current search must not be read") }
        assertSame(page, presenter.voiceRangeState)
        val view = RecordingTarget(); presenter.bindPanel(view)
        presenter.refreshVoiceRange(null) { error("No header yet: current search must not be read") }
        assertNull(presenter.voiceRangeState)
        assertTrue(view.events.isEmpty())
    }

    @Test fun voicePageChangeOnlyUpdatesHeaderWithoutRebuildingOrSelecting() {
        val results = (1..5).map { candidate(it) }
        val state = search(results = results)
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderSearch(state, true, false) { fail("Voice range selected a candidate") }
        val oldRows = view.candidates
        val page = DestinationSearchVoiceState("약국", results, pageIndex = 1)
        view.events.clear()
        presenter.refreshVoiceRange(page) { state }
        assertEquals(listOf("range"), view.events)
        assertEquals("음성 안내 중: 4번~5번", view.voiceRange)
        assertSame(oldRows, view.candidates)
        assertSame(page, presenter.voiceRangeState)
    }

    @Test fun retainedVoiceRangeOnlyMatchesTheExactCurrentQueryAndResults() {
        val results = listOf(candidate(1))
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        val page = DestinationSearchVoiceState("약국", results)
        presenter.refreshVoiceRange(page) { error("No header") }
        presenter.renderSearch(search(query = "병원", results = results), true, false) { }
        assertNull(view.voiceRange)
        presenter.refreshVoiceRange(page) { search(results = listOf(candidate(2))) }
        assertNull(view.voiceRange)
        presenter.refreshVoiceRange(page) { search(results = results.toList()) }
        assertEquals("음성 안내 중: 1번", view.voiceRange)
        presenter.refreshVoiceRange(null) { search(results = results) }
        assertNull(view.voiceRange)
    }

    @Test fun rebuildingSearchRetainsDisplayPageButNeverSetsOwnerVoicePage() {
        val results = (1..4).map { candidate(it) }
        val state = search(results = results)
        val page = DestinationSearchVoiceState("약국", results, pageIndex = 1)
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.refreshVoiceRange(page) { error("No header") }
        presenter.renderSearch(state, true, false) { }
        assertEquals("음성 안내 중: 4번", view.voiceRange)
        assertNull(state.voicePage)
        assertSame(page, presenter.voiceRangeState)
    }

    @Test fun availabilityPreservesSeparateResultBindingStage() {
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderAvailability(false, true, true, resultsReady = false)
        assertEquals(listOf(false, true, true, false), view.availability)
        presenter.renderAvailability(true, false, false, resultsReady = true)
        assertEquals(listOf(true, false, false, true), view.availability)
        assertTrue(view.candidates.isEmpty())
    }

    @Test fun guidanceRetryPromptIsAddedOnceAndSpeechFailureIsKeptLast() {
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderGuidanceStatus("안내를 기다립니다.", true, "음성을 확인해 주세요.")
        assertEquals("안내를 기다립니다.\n다시 시도하거나 안내를 종료하세요.\n음성을 확인해 주세요.", view.guidance)
        presenter.renderGuidanceStatus("다시 시도하거나 안내를 종료하세요.", true, null)
        assertEquals("다시 시도하거나 안내를 종료하세요.", view.guidance)
        presenter.renderGuidanceStatus("보행 안전 중지", false, null)
        assertEquals("보행 안전 중지", view.guidance)
        assertNull(view.measurements)
    }

    @Test fun resumeConfirmationAndPendingExplainTheCurrentActionWithoutAnAutomaticStartPromise() {
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderGuidanceStatus("보행 재개 준비 중", false, null,
            kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction.PREPARING)
        assertEquals("보행 재개 준비 중\n방향 교정 안내와 관측을 진행하고 있습니다. 준비가 끝날 때까지 기다려 주세요.", view.guidance)
        presenter.renderGuidanceStatus("안전 제한 안내 확인 필요", false, null,
            kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction.NOTICE)
        assertEquals("안전 제한 안내 확인 필요\n안전 제한 안내 확인을 눌러 안내를 끝까지 들은 뒤 재개 여부를 확인하세요.", view.guidance)
        presenter.renderGuidanceStatus("보행 안내 일시중지", false, null,
            kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction.CONFIRM)
        assertEquals("보행 안내 일시중지\n보행 안내 재개 확인을 눌러 현재 준비 상태를 확인한 뒤 이어가세요.", view.guidance)
        presenter.renderGuidanceStatus("보행 재개 확인 중", true, null,
            kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction.PENDING)
        assertEquals("보행 재개 확인 중\n재개 안내와 응답을 확인하고 있습니다. 확인이 끝날 때까지 기다려 주세요.", view.guidance)
    }

    @Test fun semanticStatusAndGuardedMeasurementsAreSeparateOrderedUpdates() {
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderGuidanceStatus("안전한 곳에서 기다리세요.", false, null)
        assertEquals(listOf("guidance"), view.events)
        assertNull(view.measurements)
        presenter.renderGuidanceMeasurements("  위치 상태\n", "보폭 상태")
        assertEquals(listOf("guidance", "measurements"), view.events)
        assertEquals("위치 상태\n보폭 상태", view.measurements)
        presenter.renderGuidanceMeasurements("\n", " ")
        assertEquals("", view.measurements)
    }

    private fun DestinationSearchPresenter.renderSearch(state: DestinationSearchController.State,
        searchAvailable: Boolean, canLoadMore: Boolean, onSelected: (DestinationSearchResult) -> Unit) =
        renderSearch({ state }, { searchAvailable }, { canLoadMore }, onSelected)

    @Test fun searchObservationsKeepTheirPositionRelativeToViewUpdates() {
        val view = RecordingTarget(); val presenter = DestinationSearchPresenter().also { it.bindPanel(view) }
        presenter.renderSearch(
            currentSearch = { assertEquals(listOf("clear"), view.events); search() },
            searchAvailable = { view.events += "allowed"; true },
            canLoadMore = { error("Empty results must not read more availability") },
            onSelected = { error("Rendering must not select") },
        )
        assertEquals(listOf("clear", "allowed", "allowed", "empty", "more:false"), view.events)
        view.events.clear()
        presenter.renderSearch(
            currentSearch = { assertEquals(listOf("clear"), view.events); search(results = listOf(candidate(1))) },
            searchAvailable = { error("Nonempty rendering defers this to availability update") },
            canLoadMore = { assertEquals(listOf("clear", "results"), view.events); false },
            onSelected = { },
        )
        assertEquals(listOf("clear", "results", "more:false"), view.events)
    }

    private class RecordingTarget : DestinationSearchPresenter.RenderTarget {
        val events = mutableListOf<String>()
        override var hasVoiceRangeHeader = false
        var emptyMessage: String? = null
        var statusCard = false
        var candidates = emptyList<DestinationSearchPresenter.Candidate>()
        var originNotice: String? = null
        var voiceRange: String? = null
        var selection: ((DestinationSearchResult) -> Unit)? = null
        var availability: List<Boolean>? = null
        var guidance: String? = null
        var measurements: String? = null
        override fun clearSearchResults() { events += "clear"; emptyMessage = null }
        override fun renderEmptySearch(message: String, statusCard: Boolean) {
            events += "empty"; emptyMessage = message; this.statusCard = statusCard
        }
        override fun renderSearchResults(originNotice: String?, candidates: List<DestinationSearchPresenter.Candidate>,
            voiceRange: String?, onSelected: (DestinationSearchResult) -> Unit) {
            events += "results"; this.originNotice = originNotice; this.candidates = candidates
            this.voiceRange = voiceRange; this.selection = onSelected; hasVoiceRangeHeader = true
        }
        override fun renderMoreResults(visible: Boolean) { events += "more:$visible" }
        override fun renderVoiceRange(label: String?) { events += "range"; voiceRange = label }
        override fun renderSearchAvailability(searchAvailable: Boolean, inFlight: Boolean, routeAvailable: Boolean, resultsReady: Boolean) {
            events += "availability"; availability = listOf(searchAvailable, inFlight, routeAvailable, resultsReady)
        }
        override fun renderGuidanceStatus(message: String) { events += "guidance"; guidance = message }
        override fun renderGuidanceMeasurements(message: String) { events += "measurements"; measurements = message }
    }
}
