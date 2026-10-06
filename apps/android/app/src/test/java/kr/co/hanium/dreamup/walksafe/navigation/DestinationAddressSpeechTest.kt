package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class DestinationAddressSpeechTest {
    @Test fun mainAndSubNumbersAreSpokenExplicitlyWithoutChangingDisplay() {
        val address = "서울 중구 세종대로 12-3"
        val result = DestinationSearchResult("a", "약국", RoutePoint(37.5, 127.0), null, address, null, null)
        val sameName = result.copy(id = "b", roadAddress = "서울 중구 세종대로 12-4")
        val prompt = DestinationSearchVoiceState("약국", listOf(result, sameName)).voicePrompt()
        assertTrue(prompt, prompt.contains("세종대로 12의 3"))
        assertTrue(prompt, prompt.contains("세종대로 12의 4"))
        assertEquals(address, result.displayAddressOrNull())
        assertEquals("역삼동 123의 45", "역삼동 123-45".toSpokenDestinationAddressKo())
        assertEquals("세종대로 12", "세종대로 12".toSpokenDestinationAddressKo())
        assertEquals("A-B 010-1234-5678", "A-B 010-1234-5678".toSpokenDestinationAddressKo())
    }
}
