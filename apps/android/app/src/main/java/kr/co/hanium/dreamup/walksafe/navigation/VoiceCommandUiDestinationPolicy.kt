package kr.co.hanium.dreamup.walksafe.navigation

enum class VoiceCommandUiDestination {
    VOICE_COMMAND,
    DESTINATION_SEARCH,
    SETTINGS,
}

fun AndroidVoiceAction.uiDestination(): VoiceCommandUiDestination {
    return when (this) {
        is AndroidVoiceAction.SearchDestination,
        is AndroidVoiceAction.SelectDestinationCandidate,
        AndroidVoiceAction.RepeatDestinationCandidates,
        AndroidVoiceAction.HearMoreDestinationCandidates,
        AndroidVoiceAction.CancelDestination,
        AndroidVoiceAction.StartNavigation,
        -> VoiceCommandUiDestination.DESTINATION_SEARCH

        AndroidVoiceAction.OpenSettings -> VoiceCommandUiDestination.SETTINGS

        AndroidVoiceAction.CreateReport,
        AndroidVoiceAction.SpeakCurrentGuidance,
        AndroidVoiceAction.SpeakVoiceHelp,
        AndroidVoiceAction.SpeakNextNavigationInstruction,
        AndroidVoiceAction.RequestReroute,
        AndroidVoiceAction.RecheckLocation,
        AndroidVoiceAction.ConfirmArrival,
        AndroidVoiceAction.RejectArrival,
        AndroidVoiceAction.StopNavigation,
        -> VoiceCommandUiDestination.VOICE_COMMAND
    }
}
