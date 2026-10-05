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

    private fun block(signature: String) = ReportStaticSourceInspector.functionBlock(activity, signature)

    @Test
    fun theHandsFreeCommandBindsTheWorkItStartedToItsCommandId() {
        val attach = block("private fun attachHandsFreeVoiceController")
        assertTrue(attach.contains("isCommandWorkPending = ::isHandsFreeCommandWorkPending"))
        val onCommand = ReportStaticSourceInspector.blockAfter(attach, "onCommand = command@")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                onCommand,
                "val workBefore = handsFreeCommandWorkGenerations()",
                "handleVoiceCommandPhrases(",
                "bindHandsFreeCommandWork(workBefore)",
            ),
        )
        val bind = block("private fun bindHandsFreeCommandWork")
        assertTrue(bind.contains("handsFreeVoiceController?.dispatchingCommandId()"))
        assertTrue(bind.contains("destinationSearchInFlight && it != before.first"))
        assertTrue(bind.contains("routeRequestInFlight.get() && it != before.second"))
    }

    @Test
    fun completionFailureAndCancellationAllEndTheBoundWorkOnlyForThatCommand() {
        val pending = block("private fun isHandsFreeCommandWorkPending")
        assertTrue(pending.contains("it.commandId == commandId"))
        assertTrue(pending.contains("destinationSearchGeneration == work.searchGeneration"))
        assertTrue(pending.contains("routeRequestGeneration == work.routeGeneration"))
        assertTrue(pending.contains("if (!pending) handsFreeCommandWork = null"))
    }

    @Test
    fun searchAndRouteRequestsAreMarkedInFlightSynchronouslySoTheBindingSeesThem() {
        val search = block("private fun performDestinationSearch")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                search,
                "destinationSearchGeneration = requestId",
                "destinationSearchInFlight = true",
            ),
        )
        val route = block("private fun requestRoute")
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
        val bind = block("private fun bindHandsFreeCommandWork")
        assertTrue(bind.contains("it.isAcquiring && it.completionGeneration != before.third"))
        val pending = block("private fun isHandsFreeCommandWorkPending")
        assertTrue(pending.contains("it.isAcquiring && it.completionGeneration == work.searchLocationGeneration"))
        val acquire = block("private fun requestDestinationSearchLocation")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            acquire,
            "it.searchLocationGeneration == controller.completionGeneration",
            "performDestinationSearch(reset)",
            "handsFreeCommandWork === locationWork",
            "searchGeneration = destinationSearchGeneration",
            "searchLocationGeneration = null",
        ))
    }

    @Test
    fun aCommandResultTimeoutRestartsListeningLikeOtherControllerFailures() {
        val terminal = block("private fun handleHandsFreeVoiceTerminalFailure")
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
