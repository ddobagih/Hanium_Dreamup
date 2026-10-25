package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-33: only commands that need another screen move the page; read-only answers and spoken-only
 * state changes stay put, on a voice page and on the walking guidance page.
 */
class VoiceCommandPageCouplingStaticTest {
    private fun source(path: String) = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/$path")
    private val activity = source("MainActivity.kt")
    private val adapters = source("voice/composition/VoicePresentationAdapters.kt")
    private fun member(source: String, name: String, occurrence: Int = 0) =
        MainActivityRefactorSource.member(source, name, occurrence)

    @Test
    fun readOnlyAnswersOnAVoicePageDoNotCallShowNativeUiPage() {
        val execute = member(source("voice/interaction/VoiceCommandInteractionController.kt"), "executeVoiceAction")
        assertTrue(execute.contains("ui.showCommandPage(action)"))
        assertFalse(execute.contains("showNativeUiPage("))
        val ports = MainActivityRefactorSource.composition(activity, "voiceInteractionControllers.commands")
        assertTrue(ports.contains("ui = VoiceInteractionPresentationAdapter(voiceUiEffects, { nativeWorkflowController }"))
        val pageSelection = member(adapters, "showCommandPage", occurrence = 1)
        // The walking guidance page and the current voice page both retain their presentation.
        assertTrue(pageSelection.contains("VoiceCommandUiDestination.CURRENT_VOICE_PAGE ->"))
        assertTrue(pageSelection.contains("workflow().nativeUiPage.isVoicePage() ||"))
        assertTrue(pageSelection.contains("workflow().nativeUiPage == NativeUiPage.GUIDANCE) null else NativeUiPage.VOICE_COMMAND"))
        assertTrue(pageSelection.contains("commandPage?.let { workflow().showNativeUiPage(it, preserveVoiceInteraction = true) }"))
        assertFalse(pageSelection.contains("workflow().showNativeUiPage(commandPage, preserveVoiceInteraction = true)"))
        assertFalse(pageSelection.contains("requestDestinationSearchLocation("))
        assertFalse(pageSelection.contains("renderMainUi("))
    }

    @Test
    fun aSkippedPageChangeAlsoSkipsTheDestinationLocationRequestAndReRender() {
        val workflow = source("ui/workflow/NativeWorkflowController.kt")
        val show = member(workflow, "showNativeUiPage", occurrence = 1)
        assertTrue(show.contains("if (page == NativeUiPage.DESTINATION_SEARCH) destinations.requestDestinationSearchLocation()"))
        assertTrue(show.contains("pages.displayPage(page)"))
        val ports = MainActivityRefactorSource.composition(activity, "nativeWorkflowController")
        assertTrue(ports.contains("return this@MainActivity.nativePagePresentationController.displayPage(page)"))
        assertTrue(ports.contains("override fun requestDestinationSearchLocation() { this@MainActivity.requestDestinationSearchLocation() }"))
        val display = member(source("ui/NativePagePresentationController.kt"), "displayPage")
        assertTrue(display.contains("phases.renderMainUi()"))
        val pagePorts = MainActivityRefactorSource.composition(activity, "nativePagePresentationController")
        assertTrue(pagePorts.contains("override fun renderMainUi() { this@MainActivity.renderMainUi() }"))
    }

    @Test
    fun wakingTheAssistantKeepsTheWalkingGuidancePage() {
        val listening = member(source("voice/input/VoiceInputSessionController.kt"), "handleHandsFreeVoiceCommandListeningStarted")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(listening,
            "if (!environment.page().destinationSearch && !environment.page().guidance) {",
            "presentation.showCommandPage()"))
        assertFalse(listening.contains("showNativeUiPage(NativeUiPage.GUIDANCE"))
        val inputPorts = MainActivityRefactorSource.composition(activity, "voiceInputController")
        assertTrue(inputPorts.contains("nativeUiPage == NativeUiPage.VOICE_COMMAND, nativeUiPage == NativeUiPage.GUIDANCE,"))
        assertTrue(inputPorts.contains("nativeUiPage == NativeUiPage.DESTINATION_SEARCH, pendingNativeUiPage == NativeUiPage.VOICE_COMMAND,"))
        assertTrue(inputPorts.contains("presentation = voiceInputPresentation"))
        val presentation = MainActivityRefactorSource.property(activity, "voiceInputPresentation")
        assertTrue(presentation.contains("VoiceInputPresentationAdapter(voiceUiEffects, { voiceInputController }, { nativeWorkflowController }"))
        val show = member(adapters, "showCommandPage")
        assertTrue(show.contains("workflow().showNativeUiPage(NativeUiPage.VOICE_COMMAND, preserveVoiceInteraction = true)"))
        assertFalse(show.contains("showNativeUiPage(NativeUiPage.GUIDANCE"))
    }
}
