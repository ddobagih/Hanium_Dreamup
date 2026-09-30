package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-45 (originals/09.txt:126, "연결 검증"): the operating route-guidance path hands the filtered
 * fix's ENU covariance to RouteNavigator, and only for the exact trusted fix it belongs to.
 */
class MainActivityPositioningCovarianceStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun routeGuidancePassesTheCovarianceOfTheSameTrustedFix() {
        val guidance = block("private fun updateRouteGuidance(")
        val position = guidance.substringAfter("filteredPosition = FilteredRoutePosition(").substringBefore("heading =")
        assertTrue(position, position.contains("covarianceEnu = latestTrustedLocationCovariance?.takeIf { it.first == location }?.second"))
    }

    @Test
    fun everyTrustedFixFromTheFilterRecordsItsCovarianceRightAfterIt() {
        val assignments = Regex("""latestTrustedLocation = (filtered\.toTrustedLocation\(\)|filteredTrusted)\n\s+(\S+)""")
            .findAll(source).map { it.groupValues[2] }.toList()
        assertEquals(listOf("rememberTrustedLocationCovariance(filtered)", "rememberTrustedLocationCovariance(filtered)"), assignments)
        val remember = block("private fun rememberTrustedLocationCovariance(")
        assertTrue(remember.contains("latestTrustedLocation?.let { location -> filtered.covarianceEnu?.let { location to it } }"))
    }

    private fun block(marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "missing $marker" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated $marker")
    }
}
