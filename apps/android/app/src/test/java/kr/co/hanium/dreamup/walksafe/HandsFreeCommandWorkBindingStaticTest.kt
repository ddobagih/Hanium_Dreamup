package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S04-11/S05-28: the hands-free controller learns that a command's search or route request is still
 * running from the work bound to that command's id, instead of guessing from 1.2 s without speech.
 */
class HandsFreeCommandWorkBindingStaticTest {
    private val activity = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    )

    private val input = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt")
    private val runtime = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt")

    @Test
    fun theHandsFreeCommandBindsTheWorkItStartedToItsCommandId() {
        val attach = MainActivityRefactorSource.member(runtime, "attachHandsFreeVoiceController")
        assertTrue(attach.contains("workPending = input::commandWorkPending"))
        assertTrue(attach.contains("command = input::recognizedCommand"))
        assertTrue(MainActivityRefactorSource.member(input.replace("\n            override fun ", "\n    override fun "), "commandWorkPending").contains("isHandsFreeCommandWorkPending(commandId)"))
        val onCommand = MainActivityRefactorSource.member(input, "handleHandsFreeCommand")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                onCommand,
                "val workBefore = handsFreeCommandWorkGenerations()",
                "commands.recognized(RecognizedVoiceInput(",
                "bindHandsFreeCommandWork(workBefore)",
            ),
        )
        val bind = MainActivityRefactorSource.member(input, "bindHandsFreeCommandWork")
        assertTrue(bind.contains("handsFreeRuntime.dispatchingCommandId()"))
        assertTrue(bind.contains("current.searchInFlight && it != before.searchGeneration"))
        assertTrue(bind.contains("current.routeInFlight && it != before.routeGeneration"))
        assertTrue(bind.contains("current.locationInFlight && it != before.locationGeneration"))
    }

    @Test
    fun completionFailureAndCancellationAllEndTheBoundWorkOnlyForThatCommand() {
        val pending = MainActivityRefactorSource.member(input, "isHandsFreeCommandWorkPending")
        assertTrue(pending.contains("it.commandId == commandId"))
        assertTrue(pending.contains("current.searchGeneration == work.searchGeneration"))
        assertTrue(pending.contains("current.routeGeneration == work.routeGeneration"))
        assertTrue(pending.contains("if (!pending) handsFreeCommandWork = null"))
    }

    @Test
    fun searchAndRouteRequestsAreMarkedInFlightSynchronouslySoTheBindingSeesThem() {
        val search = MainActivityRefactorSource.function(activity, "private fun performDestinationSearch")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                search,
                "destinationSearchGeneration = requestId",
                "destinationSearchInFlight = true",
            ),
        )
        val routeOwner = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        val route = MainActivityRefactorSource.member(routeOwner, "requestRoute", occurrence = 1)
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                route,
                "routeRequestInFlight.compareAndSet(false, true)",
                "val requestId = ++routeRequestGeneration",
            ),
        )
    }

    @Test
    fun locationWaitRemainsOwnedUntilItsSuccessorSearchOrCancellation() {
        val pending = MainActivityRefactorSource.member(input, "isHandsFreeCommandWorkPending")
        assertTrue(pending.contains("current.locationGeneration == work.locationGeneration"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(pending,
            "ownedLocation && !current.locationInFlight", "current.searchInFlight",
            "work.copy(searchGeneration = current.searchGeneration)", "handsFreeCommandWork = work"))
        assertTrue(pending.contains("ownedLocation && current.locationInFlight"))
    }

    @Test
    fun aCommandResultTimeoutRestartsListeningLikeOtherControllerFailures() {
        val terminal = MainActivityRefactorSource.member(runtime, "handleHandsFreeVoiceTerminalFailure")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                terminal,
                "HandsFreeVoiceTerminalFailure.OutputTimeout,",
                "HandsFreeVoiceTerminalFailure.CommandResultTimeout,",
                "internal_restart_\${failure.statusToken}",
                "scheduleHandsFreeVoiceRestart()",
            ),
        )
    }
}
