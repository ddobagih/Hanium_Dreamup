package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route guidance repeats while someone walks — every guide point, every distance refresh — and
 * the map provider is fixed, so naming it tells the walker nothing they can act on. A right turn
 * is a right turn whoever supplied the route. The scaffolding around it ("저장된", "경로 기준")
 * describes how the app stores a route, not where the walker is.
 *
 * Failures keep the name: they are rare, and knowing the route provider could not be reached is
 * something a user can understand and wait out.
 */
class RouteGuidanceOmitsProviderNameTest {
    private val navigatorSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
    ).readText()
    private val mainSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun noRepeatedGuidanceLineNamesTheProvider() {
        koreanLiteralsIn(navigatorSource).forEach { line ->
            assertFalse(line, line.contains("TMAP"))
        }
    }

    @Test
    fun noRepeatedGuidanceLineDescribesHowTheRouteIsStored() {
        koreanLiteralsIn(navigatorSource).forEach { line ->
            assertFalse(line, line.contains("저장된"))
            assertFalse(line, line.contains("경로 기준"))
        }
    }

    @Test
    fun theRemainingDistanceStillReachesTheWalker() {
        val literals = koreanLiteralsIn(navigatorSource)

        assertTrue(literals.any { it.contains("목적지까지 약") && it.contains("남았습니다") })
        assertTrue(literals.any { it.contains("안내 지점까지 이동하세요") })
    }

    @Test
    fun destinationSelectionDoesNotNameTheProviderEither() {
        // Spoken once, before walking, and still nothing the walker chooses between.
        val selection = mainSource
            .substringAfter("목적지를 선택했습니다.")
            .substringBefore("\")")

        assertFalse(selection, selection.contains("TMAP"))
    }

    @Test
    fun failuresKeepTheProviderName() {
        // The distinction is the point: a walker can wait out a named provider being unreachable.
        assertTrue(mainSource.contains("TMAP 경로를 확인할 수 없어 길안내를 시작하지 않았습니다."))
    }

    @Test
    fun spokenFailuresDoNotNameInternalComponents() {
        // "Gateway" is this system's word for itself; the walker has no such concept. Screen
        // labels and the developer settings panel keep it — this is about what is said aloud.
        spokenLiteralsIn(mainSource).forEach { line ->
            assertFalse(line, line.contains("Gateway"))
        }
    }

    /** First Korean literal passed to each speak* call, which is what the walker hears. */
    private fun spokenLiteralsIn(source: String): List<String> {
        val call = Regex(
            "\\b(speakInteraction|speakNavigation|speakExplicitConfirmation|" +
                "speakConsentClause|speakPriorityUserTraining|speakHomeCommandInteraction)\\s*\\(",
        )
        val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)+)\"")
        val hangul = Regex("[가-힣]")
        return call.findAll(source).mapNotNull { match ->
            val window = source.substring(match.range.last, minOf(source.length, match.range.last + 400))
            literal.findAll(window)
                .map { it.groupValues[1] }
                .firstOrNull { hangul.containsMatchIn(it) }
        }.toList()
    }

    private fun koreanLiteralsIn(source: String): List<String> =
        Regex("\"((?:[^\"\\\\\\n]|\\\\.)+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .filter { Regex("[가-힣]").containsMatchIn(it) }
            .toList()
}
