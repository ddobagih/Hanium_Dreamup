package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationVoiceLabelBoundaryRegressionTest {
    @Test
    fun longNamesAreNeverCutInsideASurrogatePairOrCombiningSequence() {
        for (name in listOf(
            "가".repeat(38) + "\uD83D\uDE00" + "끝", // emoji straddles the 40-char cut
            "a".repeat(38) + "e\u0301" + "끝", // combining acute straddles the cut
        )) {
            val prompt = prompt(name, address = "서울 중구 세종대로 1")
            assertFalse(name, prompt.indices.any { i ->
                val c = prompt[i]
                (c.isHighSurrogate() && (i + 1 >= prompt.length || !prompt[i + 1].isLowSurrogate())) ||
                    (c.isLowSurrogate() && (i == 0 || !prompt[i - 1].isHighSurrogate()))
            })
            assertFalse(name, prompt.contains("e…"))
            assertTrue(name, prompt.contains("…"))
        }
    }

    @Test
    fun plainLabelsKeepTheirExistingLengthLimit() {
        val prompt = prompt("가".repeat(45), address = "나".repeat(70))
        assertTrue(prompt.contains("1번 " + "가".repeat(39) + "…, "))
        assertTrue(prompt.contains(", " + "나".repeat(59) + "…, "))
        assertTrue(prompt("가".repeat(40), address = "주소").contains("1번 " + "가".repeat(40) + ", 주소"))
    }

    // FP-022-DETAIL-01 (S06-37): the address label is read when another candidate has the same name.
    private fun prompt(name: String, address: String): String = DestinationSearchVoiceState(
        "카페",
        listOf(
            DestinationSearchResult("1", name, RoutePoint(37.0, 127.0), address, null, null, 120),
            DestinationSearchResult("2", name, RoutePoint(37.001, 127.0), "서울 중구 세종대로 2", null, null, 200),
        ),
    ).voicePrompt()
}
