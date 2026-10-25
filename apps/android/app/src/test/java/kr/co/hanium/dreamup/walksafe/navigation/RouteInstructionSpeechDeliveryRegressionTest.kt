package kr.co.hanium.dreamup.walksafe.navigation

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transport can race duplicate terminals; this tests the shared delivery boundary, not Android TTS. */
class RouteInstructionSpeechDeliveryRegressionTest {
    @Test
    fun concurrentDuplicateActionDoneCallbacksCannotSubmitOrConsumeInformationTwice() {
        val navigator = RouteNavigator().apply {
            setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(111, 120),
                listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001)),
                listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0), null, 0, 111,
                        turnType = 200, pointType = "SP"),
                    WalkingRouteGuidePoint(1, RoutePoint(0.0, 0.0009), null, 100, 11, turnType = 12))))
        }
        val action = navigator.update(TrustedLocation(0.0, 0.0, 2f, 1_000L), 1_000L, false,
            facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        val information = requireNotNull(action.infoUtterance)
        assertTrue(navigator.reserveInstruction(action))
        val completedActions = AtomicInteger()
        val failedActions = AtomicInteger()
        val finishedInformation = AtomicInteger()
        val submitted = ConcurrentLinkedQueue<Submission>()
        assertTrue(dispatchRouteInstructionSpeech(
            update = action,
            isActionCurrent = { navigator.isInstructionCurrent(action, 1_000L,
                RouteFacingObservation(90.0, 5.0, 1_000L)) },
            isInformationCurrent = { navigator.isInformationCurrent(it, 1_700L) },
            reserveInformation = { navigator.reserveInformation(it, 1_700L) },
            finishInformation = {
                finishedInformation.incrementAndGet()
                navigator.finishInformation(it)
            },
            onActionCompleted = {
                completedActions.incrementAndGet()
                navigator.acknowledgeInstruction(action, 1_700L)
            },
            onActionFailed = { failedActions.incrementAndGet(); Unit },
            speak = { message, current, completed, failed ->
                submitted.add(Submission(message, current, completed, failed))
                true
            },
        ))
        val actionSubmission = submitted.single()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val terminals = List(2) {
                executor.submit {
                    assertTrue(start.await(3L, TimeUnit.SECONDS))
                    actionSubmission.completed()
                }
            }
            start.countDown()
            terminals.forEach { it.get(3L, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, completedActions.get())
        assertEquals(0, failedActions.get())
        assertEquals(2, submitted.size)
        val informationSubmission = submitted.last()
        assertEquals(information.message, informationSubmission.message)
        assertTrue(informationSubmission.current())
        actionSubmission.failed()
        assertEquals(0, failedActions.get())
        assertEquals(0, finishedInformation.get())
        informationSubmission.completed()
        informationSubmission.failed()
        assertEquals(1, finishedInformation.get())
    }

    private data class Submission(
        val message: String,
        val current: () -> Boolean,
        val completed: () -> Unit,
        val failed: () -> Unit,
    )
}
