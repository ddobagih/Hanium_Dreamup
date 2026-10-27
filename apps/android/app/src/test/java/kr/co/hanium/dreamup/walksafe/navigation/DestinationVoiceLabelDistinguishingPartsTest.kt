package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-36: long names keep the branch word and long addresses keep the road name with its building
 * number (FP-022-DETAIL-01: same names are told apart by dong or road name).
 */
class DestinationVoiceLabelDistinguishingPartsTest {
    @Test
    fun longSameNameCandidatesStayDistinguishableByBranch() {
        val base = "스타벅스 리저브 강남대로 사거리 교보타워 건너편 대형 복합 쇼핑몰 매장"
        val prompt = prompt(
            candidate("1", "$base 역삼점", "서울 강남구 테헤란로 1"),
            candidate("2", "$base 선릉점", "서울 강남구 테헤란로 2"),
        )
        val first = candidateText(prompt, 1)
        val second = candidateText(prompt, 2)
        assertTrue(first, first.contains("… 역삼점"))
        assertTrue(second, second.contains("… 선릉점"))
        assertNotEquals(nameLabel(first), nameLabel(second))
        assertTrue(nameLabel(first).length <= 40)
    }

    @Test
    fun longAddressKeepsTheRoadNameWithItsBuildingNumber() {
        val address = "세종특별자치시 조치원읍 행정중심복합도시 건설청 인근 대규모 복합 상업시설 동측 출입구 앞 한누리대로 2130 지하 1층"
        assertTrue(address.length > 60)
        val label = addressLabel(candidateText(prompt(candidate("1", "세종 상가", address)), 1))
        assertTrue(label, label.contains("한누리대로 2130"))
        assertTrue(label, label.startsWith("…"))
        assertTrue(label, label.endsWith("…"))
        assertTrue(label, label.length <= 60)
    }

    @Test
    fun longLotAddressKeepsTheDongWithItsLotNumber() {
        val address = "경상북도 울릉군 울릉읍 섬 일주도로 해안 산책로 입구 공영 주차장 옆 매표소 건물 뒤편 공터 도동리 123-45 일원"
        assertTrue(address.length > 60)
        val label = addressLabel(candidateText(prompt(candidate("1", "울릉 매표소", address)), 1))
        assertTrue(label, label.contains("도동리 123의 45"))
        assertTrue(label, label.length <= 60)
    }

    @Test
    fun shortLabelsAreUnchanged() {
        val prompt = prompt(candidate("1", "스타벅스 역삼점", "서울 강남구 테헤란로 152"))
        assertTrue(prompt, prompt.contains("1번 스타벅스 역삼점, 서울 강남구 테헤란로 152, 약 100미터"))
    }

    @Test
    fun labelsWithoutAWordBoundaryFallBackToTheBoundedPrefix() {
        val prompt = prompt(candidate("1", "가".repeat(45), "나".repeat(70)))
        assertTrue(prompt.contains("1번 " + "가".repeat(39) + "…, "))
        assertTrue(prompt.contains(", " + "나".repeat(59) + "…, "))
    }

    @Test
    fun branchPreservationNeverSplitsAnEmojiOrCombiningMark() {
        val name = "가".repeat(30) + "\uD83D\uDE00" + "a".repeat(5) + "e\u0301" + " 역삼점"
        val text = candidateText(prompt(candidate("1", name, "서울 강남구 테헤란로 1")), 1)
        assertTrue(text, text.contains("… 역삼점"))
        assertFalse(text.indices.any { i ->
            val c = text[i]
            (c.isHighSurrogate() && (i + 1 >= text.length || !text[i + 1].isLowSurrogate())) ||
                (c.isLowSurrogate() && (i == 0 || !text[i - 1].isHighSurrogate()))
        })
        assertFalse(text.contains("e…"))
    }

    @Test
    fun overlongBranchWordFallsBackToThePrefixRule() {
        val name = "짧은 " + "지".repeat(30) + "점"
        val text = candidateText(prompt(candidate("1", name + " 끝말이아주아주아주아주아주아주아주긴지점명", "주소")), 1)
        assertEquals(40, nameLabel(text).length)
        assertTrue(nameLabel(text).endsWith("…"))
    }

    private fun candidate(id: String, name: String, address: String) =
        DestinationSearchResult(id, name, RoutePoint(37.0, 127.0), address, null, null, 120)

    // FP-022-DETAIL-01 (S06-37): the address label is read only for same-name candidates, so every
    // candidate gets a same-name twin after the originals (the originals keep numbers 1..n).
    private fun prompt(vararg results: DestinationSearchResult): String =
        DestinationSearchVoiceState(
            "카페",
            results.toList() + results.map { it.copy(id = "twin-${it.id}", address = "서울 중구 세종대로 2") },
        ).voicePromptParts().joinToString(" ")

    /** "N번 name, address, distance" segment of the page prompt. */
    private fun candidateText(prompt: String, number: Int): String {
        val start = prompt.indexOf("${number}번 ", prompt.indexOf("입니다. ") + 1)
        val end = prompt.indexOf("미터", start)
        check(start >= 0 && end > start) { prompt }
        return prompt.substring(start, end + 2)
    }

    private fun nameLabel(text: String): String = text.substringAfter("번 ").substringBefore(", ")

    private fun addressLabel(text: String): String =
        text.substringAfter(", ").substringBeforeLast(", ")
}
