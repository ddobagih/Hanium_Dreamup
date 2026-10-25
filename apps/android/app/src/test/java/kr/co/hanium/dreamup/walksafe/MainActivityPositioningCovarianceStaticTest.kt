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
        val guidance = NavigationPolicySource.guidance(source)
        val position = guidance.substringAfter("filteredPosition = FilteredRoutePosition(").substringBefore("heading =")
        assertTrue(guidance.contains("filteredPosition = FilteredRoutePosition("))
        assertTrue(position, position.contains("covarianceEnu = position.covariance?.takeIf { !location.navigationOnly && it.first == location }?.second"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeGuidanceController").contains("override val covariance get() = positioning.value.trustedCovariance"))
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(MainActivityRefactorSource.property(source, "latestTrustedLocationCovariance").contains("positioningSessionController.trustedCovariance"))
    }

    @Test
    fun everyTrustedFixFromTheFilterRecordsItsCovarianceRightAfterIt() {
        val positioning = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        assertEquals(2, Regex("""latestTrustedLocation = (filtered\.toTrustedLocation\(\)|filteredTrusted)""")
            .findAll(positioning).count())
        val motion = MainActivityRefactorSource.member(positioning, "applyPositioningMotionSnapshot")
        assertTrue(motion.contains("latestTrustedLocation = filtered.toTrustedLocation()\n        rememberTrustedLocationCovariance(filtered)"))
        val location = NavigationPolicySource.observation(source)
        assertTrue(location.contains("val filteredTrusted = warmingSelection?.location ?: filtered.toTrustedLocation()"))
        assertTrue(location.contains("latestTrustedLocation = filteredTrusted\n        if (warmingSelection != null) latestTrustedLocationCovariance = null\n        else rememberTrustedLocationCovariance(filtered)"))
        val remember = MainActivityRefactorSource.function(source, "private fun rememberTrustedLocationCovariance(")
        assertTrue(remember.contains("latestTrustedLocation?.let { location -> filtered.covarianceEnu?.let { location to it } }"))
        MainActivityRefactorSource.composition(source, "positioningSessionController")
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
