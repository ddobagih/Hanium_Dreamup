package kr.co.hanium.dreamup.walksafe.voice.interaction

import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchController
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState

/** Composes peers once without querying SDK, input, route, identity or clock. */
internal class VoiceInteractionControllers(
    search: () -> DestinationSearchController,
    routeRun: () -> NavigationRunState,
    walk: VoiceInteractionWalk,
    route: VoiceInteractionRoute,
    identity: VoiceInteractionIdentity,
    ui: VoiceInteractionPresentation,
    input: VoiceInteractionInput,
    output: VoiceInteractionOutput,
    scheduler: VoiceInteractionScheduler,
    diagnostics: VoiceInteractionDiagnostics = object : VoiceInteractionDiagnostics {},
) {
    val commands: VoiceCommandInteractionController = VoiceCommandInteractionController(search, routeRun, walk, route, identity, ui,
        input, output, scheduler, diagnostics, dialog = { destinations })
    val destinations: DestinationVoiceDialogController = DestinationVoiceDialogController(search, routeRun, walk, route, identity, ui,
        input, output, scheduler, diagnostics, repeatGuidance = { commands.speakNextNavigationInstruction() })
}
