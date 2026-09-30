package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-34: screen candidate buttons carry the same global number the voice dialog uses. */
class MainActivityDestinationCandidateNumberStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val ui = source.substringAfter("private fun updateDestinationSearchUi()")
        .substringBefore("private fun updateRouteButtonText()")

    @Test fun buttonLabelStartsWithTheVoiceNumber() {
        assertTrue(ui.contains("destinationSearchResults.forEachIndexed { index, result ->"))
        assertTrue(ui.contains("android.text.SpannableStringBuilder(\"\${index + 1}번 \${result.name}\")"))
        assertFalse(ui.contains("android.text.SpannableStringBuilder(result.name)"))
        // Touch selection of any shown candidate stays available.
        assertTrue(ui.contains("openNativeDestinationConfirmation(result)"))
    }
}
