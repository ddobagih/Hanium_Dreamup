package kr.co.hanium.dreamup.walksafe.navigation
enum class VoiceCommandUiDestination {
    VOICE_COMMAND,
    DESTINATION_SEARCH,
    SETTINGS,
    /**
     * S05-33: a read-only answer (repeat, help, next instruction) keeps the voice page the user is on;
     * the screen and TalkBack context do not change just because something was spoken again.
     * The same holds for a state change whose result is only spoken (closing a dialog, cancelling
     * the destination, the route decisions and stopping guidance): nothing has to be chosen on
     * another screen, so the walking guidance page stays as well.
     */
    CURRENT_VOICE_PAGE,
}
fun AndroidVoiceAction.uiDestination(): VoiceCommandUiDestination {
    return when (this) {
        is AndroidVoiceAction.SearchDestination,
        is AndroidVoiceAction.SelectDestinationCandidate,
        AndroidVoiceAction.HearMoreDestinationCandidates,
        AndroidVoiceAction.PreviousDestinationCandidates,
        AndroidVoiceAction.StartNavigation,
        -> VoiceCommandUiDestination.DESTINATION_SEARCH
        AndroidVoiceAction.OpenSettings -> VoiceCommandUiDestination.SETTINGS
        AndroidVoiceAction.RepeatDestinationCandidates,
        AndroidVoiceAction.SpeakCurrentGuidance,
        AndroidVoiceAction.SpeakVoiceHelp,
        AndroidVoiceAction.SpeakNextNavigationInstruction,
        // S05-12: closing the open dialog needs no other screen; the result is spoken.
        AndroidVoiceAction.CancelDialog,
        // S05-33: spoken-only state changes; there is no list or form to show for them.
        AndroidVoiceAction.CancelDestination,
        AndroidVoiceAction.RequestReroute,
        AndroidVoiceAction.RecheckLocation,
        AndroidVoiceAction.ConfirmArrival,
        AndroidVoiceAction.RejectArrival,
        AndroidVoiceAction.StopNavigation,
        -> VoiceCommandUiDestination.CURRENT_VOICE_PAGE
        // The report flow confirms on the voice command page.
        AndroidVoiceAction.CreateReport,
        -> VoiceCommandUiDestination.VOICE_COMMAND
    }
}
