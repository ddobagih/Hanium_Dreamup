package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spoken navigation choices must resolve to the commands offered to the user. */
class SpokenCommandOffersAreRecognizedTest {
    private val navigatorSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
    ).readText()

    /** "새 경로 요청, 위치 다시 확인, 길안내 종료 중에서 선택해 주세요." -> the three names. */
    private fun offersIn(prompt: String): List<String> =
        Regex("([^\".]+?)\\s*중에서 선택해 주세요")
            .find(prompt)
            ?.groupValues
            ?.get(1)
            .orEmpty()
            .split(", ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun theDeviationPromptOffersThreeChoices() {
        val offers = offersIn(navigatorSource)

        assertEquals(listOf("새 경로 요청", "위치 다시 확인", "길안내 종료"), offers)
    }

    @Test
    fun everyDeviationChoiceParses() {
        offersIn(navigatorSource).forEach { offer ->
            assertNotNull("$offer 를 안내하면서 인식하지 못합니다", parseAndroidVoiceCommand(offer))
        }
    }

    @Test
    fun eachDeviationChoiceReachesTheCommandItNames() {
        val expected = mapOf(
            "새 경로 요청" to AndroidVoiceCommand.RequestReroute,
            "위치 다시 확인" to AndroidVoiceCommand.RecheckLocation,
            "길안내 종료" to AndroidVoiceCommand.StopNavigation,
        )

        expected.forEach { (phrase, command) ->
            assertEquals(phrase, command, parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun theOlderRerouteWordingKeepsWorking() {
        // Walkers who learned the help text must not be retrained by this fix.
        listOf("새 경로 찾아줘", "경로 다시 찾아줘", "재탐색해줘").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.RequestReroute, parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun spacingAndPunctuationNeverDecideACommand() {
        // A recognizer's spacing is not a user decision.
        listOf(
            "새경로요청",
            "새 경로 요청.",
            "새  경로  요청",
            "새 경로 요청해줘",
        ).forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.RequestReroute, parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun theLocationRecheckPromptAlsoParses() {
        // A second prompt names one command outright; it has the same obligation.
        assertTrue(navigatorSource.contains("위치 다시 확인이라고 말씀해 주세요"))
        assertEquals(
            AndroidVoiceCommand.RecheckLocation,
            parseAndroidVoiceCommand("위치 다시 확인"),
        )
    }
}
