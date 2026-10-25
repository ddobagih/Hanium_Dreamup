package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-30 (06.txt:232): the screen list and the confirmation name the kind of candidate distance too. */
class MainActivityDestinationDistanceKindStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun theScreenListAndTheConfirmationSayTheDistanceKind() {
        assertTrue(MainActivityRefactorSource.member(source, "updateDestinationSearchUi")
            .contains("destinationSearchPresenter.renderSearch("))
        val list = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
        assertTrue(list.contains("approximateDestinationDistanceKo(result.distanceM, state.origin?.accuracyM) +"))
        assertTrue(list.contains("destinationDistanceKindKo(result.distanceSource)"))
        assertTrue(MainActivityRefactorSource.member(source, "nativeDestinationConfirmationMessage")
            .contains("NativeGuidanceTextPresentation.destinationConfirmation(pendingUiDestination, { destinationSearchOrigin }, SystemClock::elapsedRealtime)"))
        val confirmation = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeGuidanceTextPresentation.kt").readText()
        assertTrue(confirmation.contains("destinationDistanceKindKo(selected.distanceSource)"))
        assertTrue(confirmation.contains("listOfNotNull(selected.name, address, distance, distanceKind, originNotice"))
    }
}
