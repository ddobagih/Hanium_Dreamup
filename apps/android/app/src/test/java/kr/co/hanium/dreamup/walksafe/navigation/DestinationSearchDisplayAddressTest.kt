package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-35: screen, voice and confirmation share one address choice. */
class DestinationSearchDisplayAddressTest {
    private fun result(address: String?, roadAddress: String?) = DestinationSearchResult(
        id = "candidate",
        name = "Fixture Mart",
        point = RoutePoint(37.5, 127.1),
        address = address,
        roadAddress = roadAddress,
        category = null,
        distanceM = 120,
    )

    @Test fun roadAddressIsPreferredThenLotAddress() {
        assertEquals("Fixture Road 15", result("Fixture Dong 10", "Fixture Road 15").displayAddressOrNull())
        assertEquals("Fixture Road 15", result(null, "Fixture Road 15").displayAddressOrNull())
        assertEquals("Fixture Dong 10", result("Fixture Dong 10", " ").displayAddressOrNull())
        assertEquals("Fixture Dong 10", result("Fixture Dong 10", null).displayAddressOrNull())
        assertNull(result(" ", null).displayAddressOrNull())
    }

    @Test fun roadAddressOnlyCandidateIsSpokenWithThatAddress() {
        // FP-022-DETAIL-01 (S06-37): the address is spoken to tell same-name candidates apart.
        val twin = result("Fixture Dong 10", null).copy(id = "twin", distanceM = 400)
        val prompt = formatDestinationSearchVoicePrompt("Fixture", listOf(result(null, "Fixture Road 15"), twin))
        assertTrue(prompt, prompt.contains("1번 Fixture Mart, Fixture Road 15, 약 100미터"))
        assertTrue(prompt, prompt.contains("2번 Fixture Mart, Fixture Dong 10, 약 400미터"))
        assertFalse(prompt.contains("주소 미상"))
    }

    @Test fun screenListUsesTheSameAddressSelection() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        assertTrue(MainActivityRefactorSource.member(source, "updateDestinationSearchUi")
            .contains("destinationSearchPresenter.renderSearch("))
        val ui = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
        assertTrue(ui.contains("result.displayAddressOrNull() ?: \"주소 없음\""))
        assertFalse(ui.contains("result.address ?: \"주소 없음\""))
    }
}
