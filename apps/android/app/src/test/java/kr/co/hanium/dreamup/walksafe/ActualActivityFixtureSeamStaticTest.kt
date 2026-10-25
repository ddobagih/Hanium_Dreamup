package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21-32: the real-Activity navigation fixture observes UI and one narrow seam instead of private
 * MainActivity names, so internal renames no longer look like functional regressions.
 */
class ActualActivityFixtureSeamStaticTest {
    private val fixture = File(
        "src/androidTest/java/kr/co/hanium/dreamup/walksafe/ActualActivityVoiceNavigationDeviceTest.kt",
    ).readText()
    private val activity = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun fixtureUsesNoReflectionOnPrivateActivityState() {
        for (token in listOf("java.lang.reflect", "getDeclaredField", "getDeclaredMethod", "isAccessible", "declaredMethods", "readField(", "writeField(")) {
            assertFalse("fixture still uses $token", fixture.contains(token))
        }
        assertTrue(fixture.contains("activity.navigationFixtureObservation()"))
        assertTrue("start is pressed through the visible button", fixture.contains("clickVisibleButton(activity, START_GUIDANCE_LABEL)"))
        assertTrue(fixture.contains("const val START_GUIDANCE_LABEL = \"안내 시작\""))
        val composition = MainActivityRefactorSource.member(activity, "buildContentView")
        assertTrue(composition.contains("val destinationPanel = DestinationGuidancePanel("))
        assertTrue(composition.contains("onStart = ::startNativeDestinationGuidance,"))
        assertTrue(composition.contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
        val button = MainActivityRefactorSource.property(panel, "nativeDestinationStartButton")
        assertTrue(button.contains("label = \"안내 시작\""))
        assertTrue(button.contains("emphasis = true"))
        assertTrue(button.contains("onClick = actions.onStart"))
    }

    @Test
    fun activitySeamIsNarrowTestOnlyAndUnusedByProductCode() {
        val seams = listOf(
            "internal fun navigationFixtureObservation()",
            "internal fun deliverDestinationSearchResultForFixture(",
            "internal fun disarmPendingDestinationForFixture()",
            "internal fun dropTrustedLocationForFixture()",
        )
        for (seam in seams) {
            val at = activity.indexOf(seam)
            assertTrue("missing $seam", at >= 0)
            assertTrue("$seam must be @VisibleForTesting",
                activity.substring(maxOf(0, at - 50), at).contains("@androidx.annotation.VisibleForTesting"))
            val name = seam.substringAfter("internal fun ").substringBefore("(")
            assertEquals("product code must not call $name", 1, Regex("""\b$name\(""").findAll(activity).count())
        }
        assertEquals("only the fixture seams are VisibleForTesting", 5,
            Regex("""@androidx\.annotation\.VisibleForTesting""").findAll(activity).count())
        // S21-32-F1: the mutating seams are compiled into release but refuse to run there.
        for (seam in seams.drop(1)) {
            val body = activity.substringAfter(seam).substringAfter("{\n").trimStart()
            assertTrue("$seam must start with the debug-only check",
                body.startsWith("check(BuildConfig.DEBUG) {"))
        }
        val observation = activity.substringAfter("internal fun navigationFixtureObservation()").substringBefore("/** Fixture input only")
        assertFalse("observation is read-only", observation.contains("pendingUiDestination = null"))
        assertFalse(observation.contains("latestTrustedLocation = null"))
        assertFalse(observation.contains(".set("))
    }
}
