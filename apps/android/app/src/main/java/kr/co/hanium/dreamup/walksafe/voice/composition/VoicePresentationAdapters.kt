package kr.co.hanium.dreamup.walksafe.voice.composition

import android.view.View
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.diagnostics.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.ui.*
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.*
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import kr.co.hanium.dreamup.walksafe.voice.output.*

/** UI publishers remain authoritative for status ownership and the current controls. */
internal interface VoiceUiEffects {
    fun voiceStatus(message: String)
    fun navigationStatus(message: String)
    fun status(title: String, detail: String)
    fun refreshControls(active: Boolean)
    fun recognitionSignal(message: String)
    fun openHelp() = Unit
    fun openLocalReports() = Unit
}

internal interface VoiceTextTarget {
    val identity: Any
    var text: CharSequence?
    var contentDescription: CharSequence?
    var visible: Boolean
    var assertive: Boolean
}

/** Wraps an existing published target; construction does not query Android. */
internal class AndroidVoiceTextTarget(private val view: TextView) : VoiceTextTarget {
    override val identity: Any get() = view
    override var text: CharSequence?
        get() = view.text
        set(value) { view.text = value }
    override var contentDescription: CharSequence?
        get() = view.contentDescription
        set(value) { view.contentDescription = value }
    override var visible: Boolean
        get() = view.visibility == View.VISIBLE
        set(value) { view.visibility = if(value) View.VISIBLE else View.GONE }
    override var assertive: Boolean
        get() = view.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        set(value) { view.accessibilityLiveRegion = if(value) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else View.ACCESSIBILITY_LIVE_REGION_POLITE }
}

/** Captures exact accessibility targets per request; it does not retain a second current View. */
internal class AndroidSpeechPresentation(
    private val ui: VoiceUiEffects,
    private val input: () -> VoiceInputSessionController,
    private val accessibilityTarget: () -> VoiceTextTarget?,
    private val lastResultTarget: () -> VoiceTextTarget?,
    private val callbacks: AndroidVoiceCallbacks,
    private val diagnostic: (VoiceInputDiagnosticEvent) -> Unit = { logVoiceInputDiagnostic(it) },
) : SpeechOutputPresentation {
    override fun showLastResult(message: String) {
        val target = lastResultTarget() ?: return
        val line = "마지막 안내: $message"
        target.visible = true
        if (target.text.toString() != line) {
            target.text = line
            target.contentDescription = line
        }
    }
    override fun showVoiceStatus(message: String) = ui.voiceStatus(message)
    override fun refreshVoiceControls() = ui.refreshControls(input().state.recognitionActive)
    override fun navigationStatus(message: String) = ui.navigationStatus(message)
    override fun commandTerminalDiagnostic(completed: Boolean) = diagnostic(
        if (completed) VoiceInputDiagnosticEvent.OUTPUT_DONE else VoiceInputDiagnosticEvent.OUTPUT_FAILED)
    override fun accessibilityTargetOrNull(): SpeechAccessibilityTarget? {
        val target = accessibilityTarget() ?: return null
        return SpeechAccessibilityTarget { message, priority ->
            if (accessibilityTarget()?.identity !== target.identity || !target.visible) false
            else {
                target.assertive = priority == SpeechAnnouncementPriority.RISK
                target.text = message
                true
            }
        }
    }
    override fun canScheduleInteraction() = callbacks.canScheduleInteraction()
    override fun postInteraction(task: Runnable, delayMs: Long) = callbacks.postInteraction(task, delayMs)
    override fun removeInteraction(task: Runnable) = callbacks.removeInteraction(task)
}

internal class VoiceInputPresentationAdapter(
    private val ui: VoiceUiEffects,
    private val input: () -> VoiceInputSessionController,
    private val workflow: () -> NativeWorkflowController,
    private val speechPresentation: () -> SpeechOutputPresentation,
    private val statusInitialized: () -> Boolean,
) : VoiceInputPresentation {
    override fun voiceStatus(message: String) = ui.voiceStatus(message)
    override fun navigationStatus(message: String) = ui.navigationStatus(message)
    override fun refreshControls(active: Boolean?) = ui.refreshControls(active ?: input().state.recognitionActive)
    override fun status(title: String, detail: String) = ui.status(title, detail)
    override fun lastResult(message: String) = speechPresentation().showLastResult(message)
    override fun recognitionSignal(message: String) = ui.recognitionSignal(message)
    override fun showCommandPage() = workflow().showNativeUiPage(NativeUiPage.VOICE_COMMAND, preserveVoiceInteraction = true)
    override fun canShowStatus() = statusInitialized()
}

internal class VoiceInteractionPresentationAdapter(
    private val ui: VoiceUiEffects,
    private val workflow: () -> NativeWorkflowController,
    private val screens: () -> NativeScreenPresenter,
    private val searchPresenter: () -> DestinationSearchPresenter,
    private val search: () -> DestinationSearchController,
    private val homeContextAvailable: () -> Boolean,
    private val coordinates: Coordinates,
    private val renderSearch: () -> Unit,
) : VoiceInteractionPresentation {
    interface Coordinates {
        fun latitudeHasText(): Boolean
        fun longitudeHasText(): Boolean
        fun clear()
    }
    override fun homeContextAvailable() = homeContextAvailable.invoke()
    override fun voiceInteractionPage() = workflow().nativeUiPage.isVoicePage()
    override fun pendingDestination() = workflow().pendingUiDestination
    override fun clearPendingDestination() { workflow().pendingUiDestination = null }
    override fun showDestinationConfirmation() = workflow().showNativeUiPage(NativeUiPage.DESTINATION_CONFIRM)
    override fun showCommandPage(action: AndroidVoiceAction) {
        val commandPage = when (action.uiDestination()) {
            VoiceCommandUiDestination.VOICE_COMMAND -> NativeUiPage.VOICE_COMMAND
            VoiceCommandUiDestination.DESTINATION_SEARCH -> NativeUiPage.DESTINATION_SEARCH
            VoiceCommandUiDestination.SETTINGS -> NativeUiPage.SETTINGS
            VoiceCommandUiDestination.HELP -> { ui.openHelp(); null }
            VoiceCommandUiDestination.LOCAL_REPORTS -> { ui.openLocalReports(); null }
            VoiceCommandUiDestination.CURRENT_VOICE_PAGE ->
                if (action == AndroidVoiceAction.SpeakCurrentState ||
                    action is AndroidVoiceAction.SelectLocalQuarantinedReport ||
                    action == AndroidVoiceAction.ConfirmLocalQuarantinedReportDelete ||
                    workflow().nativeUiPage.isVoicePage() ||
                    workflow().nativeUiPage == NativeUiPage.GUIDANCE) null else NativeUiPage.VOICE_COMMAND
        }
        commandPage?.let { workflow().showNativeUiPage(it, preserveVoiceInteraction = true) }
    }
    override fun navigationStatus(message: String) = ui.navigationStatus(message)
    override fun voiceStatus(message: String) = ui.voiceStatus(message)
    override fun status(title: String, detail: String) = ui.status(title, detail)
    override fun refreshVoiceControls(active: Boolean) = ui.refreshControls(active)
    override fun refreshDestinationRange(state: DestinationSearchVoiceState?) = searchPresenter().refreshVoiceRange(state) { search().state }
    override fun destinationRangeState() = searchPresenter().voiceRangeState
    override fun queryHasText() = screens().searchBinding().destinationQueryInput.text?.isNotBlank() == true
    override fun latitudeHasText() = coordinates.latitudeHasText()
    override fun longitudeHasText() = coordinates.longitudeHasText()
    override fun setQueryText(query: String) = screens().searchBinding().destinationQueryInput.setText(query)
    override fun clearCoordinateText() = coordinates.clear()
    override fun renderSearch() = renderSearch.invoke()
    override fun openDestinationConfirmation(result: DestinationSearchResult) = workflow().openNativeDestinationConfirmation(result)
}

internal fun NativeUiPage.isVoicePage() = this == NativeUiPage.VOICE_COMMAND || this == NativeUiPage.DESTINATION_SEARCH
