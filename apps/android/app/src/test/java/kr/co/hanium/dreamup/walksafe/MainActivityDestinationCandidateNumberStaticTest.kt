package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-34: screen candidate buttons carry the same global number the voice dialog uses. */
class MainActivityDestinationCandidateNumberStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
    private val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()

    @Test fun buttonLabelStartsWithTheVoiceNumber() {
        val ui = MainActivityRefactorSource.member(source, "updateDestinationSearchUi")
        assertTrue(ui.contains("destinationSearchPresenter.renderSearch("))
        assertTrue(source.contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        assertTrue(presenter.contains("state.results.mapIndexed { index, result ->"))
        assertTrue(presenter.contains("Candidate(result, index + 1, details)"))
        assertTrue(panel.contains("android.text.SpannableStringBuilder(\"\${candidate.number}번 \${candidate.result.name}\")"))
        assertFalse(panel.contains("android.text.SpannableStringBuilder(candidate.result.name)"))
        // Touch selection of any shown candidate stays available.
        assertTrue(ui.contains("onSelected = ::openNativeDestinationConfirmation"))
        assertTrue(panel.contains("setOnClickListener { onSelected(candidate.result) }"))
    }
}
