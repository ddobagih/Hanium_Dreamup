package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-30 (06.txt:232): the screen list and the confirmation name the kind of candidate distance too. */
class MainActivityDestinationDistanceKindStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun theScreenListAndTheConfirmationSayTheDistanceKind() {
        val list = source.substringAfter("private fun updateDestinationSearchUi()")
            .substringBefore("private fun updateRouteButtonText()")
        assertTrue(list.contains("approximateDestinationDistanceKo(result.distanceM, originAccuracyM) +"))
        assertTrue(list.contains("navigation.destinationDistanceKindKo(result.distanceSource)"))
        val confirmation = source.substringAfter("private fun nativeDestinationConfirmationMessage(): String {")
            .substringBefore("private fun selectedDestinationSpeechKo(")
        assertTrue(confirmation.contains("navigation.destinationDistanceKindKo(destination.distanceSource)"))
        assertTrue(confirmation.contains("listOfNotNull(destination.name, address, distance, distanceKind, originNotice"))
    }
}
