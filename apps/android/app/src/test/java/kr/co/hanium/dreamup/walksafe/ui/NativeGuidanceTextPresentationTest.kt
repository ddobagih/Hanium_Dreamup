package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.*
import org.junit.Test
import kr.co.hanium.dreamup.walksafe.navigation.*

class NativeGuidanceTextPresentationTest {
    private val presentation = DestinationGuidancePresentation(DestinationGuidanceDisplayStage.ACTIVE, "제목", "경로", "카메라", false)

    @Test fun noSelectedCandidatePerformsNoOriginOrClockRead() {
        assertEquals("목적지를 선택해 주세요.", NativeGuidanceTextPresentation.destinationConfirmation(null,
            { error("early origin") }, { error("early clock") }))
    }

    @Test fun confirmationKeepsTheSelectedCandidateAddressAndOldOriginReadOrder() {
        val selected = DestinationSearchResult("selected", "같은 이름", RoutePoint(37.1, 127.1), "선택한 후보의 주소", null, null, 150)
        val trace = mutableListOf<String>()
        val text = NativeGuidanceTextPresentation.destinationConfirmation(selected,
            { trace += "origin"; null }, { trace += "clock"; 123L })
        assertTrue(text.contains("선택한 후보의 주소"))
        assertTrue(text.contains("검색 시점 기준"))
        assertEquals(listOf("origin", "origin", "origin", "clock"), trace)
    }

    @Test fun candidateWithoutDistanceSkipsOnlyTheDistanceOriginRead() {
        val selected = DestinationSearchResult("id", "장소", RoutePoint(37.1, 127.1), null, null, null, null)
        val trace = mutableListOf<String>()
        NativeGuidanceTextPresentation.destinationConfirmation(selected, { trace += "origin"; null }, { trace += "clock"; 0L })
        assertEquals(listOf("origin", "origin", "clock"), trace)
    }

    @Test fun currentSafetyStopWinsWithoutAnyMeasurementOrRuntimeQuery() {
        for (includeMeasurements in listOf(false, true)) {
            val text = NativeGuidanceTextPresentation.guidanceStatus(presentation, includeMeasurements, { "현재 중지" },
                { error("bypass") }, "marker", { error("runtime") }, { error("gps") }, { error("preparation") })
            assertEquals("보행 안전 중지\n현재 중지", text)
        }
    }

    @Test fun changedRuntimeEpochDoesNotReuseAPreviousSafetyStopSummary() {
        var epoch = 1L
        val stopEpoch = 1L
        val summary = { if (epoch == stopEpoch) "이전 중지" else null }
        fun render() = NativeGuidanceTextPresentation.guidanceStatus(presentation, false, summary,
            { false }, "marker", { error("runtime") }, { error("gps") }, { error("preparation") })
        assertEquals("보행 안전 중지\n이전 중지", render())
        epoch = 2L
        assertEquals("제목\n\n경로\n\n카메라", render())
    }

    @Test fun bypassNoticeIsVisibleWithOrWithoutMeasurementsAndReadsItsFlagOnce() {
        for (includeMeasurements in listOf(false, true)) {
            val trace = mutableListOf<String>()
            val text = NativeGuidanceTextPresentation.guidanceStatus(presentation, includeMeasurements,
                { trace += "stop"; null }, { trace += "bypass"; true }, "walksafe-debug-v1-bypass-on",
                { error("runtime") }, { error("gps") }, { error("preparation") })
            assertEquals(listOf("stop", "bypass"), trace)
            assertEquals("제목\n\n경로\n\n카메라\n\n시험용 우회 켜짐 [walksafe-debug-v1-bypass-on]: " +
                "환경·장착·성능 기준으로 안내 시작을 차단하지 않습니다.", text)
        }
    }

    @Test fun normalSummaryStillSkipsRuntimeAndMeasurementQueries() {
        val trace = mutableListOf<String>()
        val text = NativeGuidanceTextPresentation.guidanceStatus(presentation, false,
            { trace += "stop"; null }, { trace += "bypass"; false }, "walksafe-release-v1",
            { error("runtime") }, { error("gps") }, { error("preparation") })
        assertEquals(listOf("stop", "bypass"), trace)
        assertEquals("제목\n\n경로\n\n카메라", text)
        assertFalse(text.contains("시험용 우회"))
        assertFalse(text.contains("walksafe-release-v1"))
    }

    @Test fun measurementBranchesReadOnlyTheirOriginalSources() {
        val trace = mutableListOf<String>()
        val text = NativeGuidanceTextPresentation.guidanceStatus(presentation, true, { trace += "stop"; null },
            { trace += "bypass"; false }, "marker", { trace += "runtime"; true },
            { trace += "gps"; "GPS 상태" }, { error("preparation") })
        assertEquals(listOf("stop", "bypass", "runtime", "gps"), trace)
        assertEquals("제목\n\n경로\n\n카메라\n\nGPS 상태", text)
    }
}
