package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingMethod
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingRuntimeState
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingUserConfirmation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservationHistory
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingInput
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingSelector
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Executes Main's mounting/lookup leaf and real history/selector; no Activity or sensor callbacks. */
class MainActivityChestHeadingHistoricalConfirmationBehaviorTest {
    private val epoch = WalkRuntimeEpoch("historical-chest-walk", 2L)
    private val otherEpoch = epoch.copy(recoveryGeneration = 3L)

    @Test fun futureConfirmationCannotAuthorizeTheOriginalHistoricalStepOrQueryItsHeading() {
        val observed = lookup(confirmation = confirmation(1_500L), timestampMs = 1_200L)

        assertNull("Confirmation at 1500 cannot authorize the original step at 1200", observed.heading)
        assertEquals("Rejected mounting must not enter the heading history lookup", 0, observed.queries.size)
    }

    @Test fun earlierConfirmationAllowsTheOriginalStepAndDefaultAge() {
        val observed = lookup(confirmation = confirmation(1_100L))

        assertEquals(heading(1_200L), observed.heading)
        assertEquals(listOf(1_200L to 500L), observed.queries)
    }

    @Test fun confirmationAtTheExactStepTimeRemainsAllowed() {
        val observed = lookup(confirmation = confirmation(1_200L))

        assertEquals(heading(1_200L), observed.heading)
        assertEquals(listOf(1_200L to 500L), observed.queries)
    }

    @Test fun confirmationAllowsASubsequentStepWithoutRelabelingItsTime() {
        val observed = lookup(confirmation = confirmation(1_500L), timestampMs = 1_600L)

        assertEquals(heading(1_200L), observed.heading)
        assertEquals(listOf(1_600L to 500L), observed.queries)
    }

    @Test fun missingConfirmationCannotQueryChestHeading() {
        assertBlocked(lookup(confirmation = null))
    }

    @Test fun confirmationFromAnotherWalkCannotQueryChestHeading() {
        assertBlocked(lookup(confirmation = confirmation(1_100L).copy(epoch = otherEpoch)))
    }

    @Test fun mountingRuntimeStateFromAnotherWalkCannotQueryChestHeading() {
        assertBlocked(lookup(runtimeState = PhoneMountingRuntimeState(otherEpoch, null)))
    }

    @Test fun otherMountingMethodsCannotQueryChestHeading() {
        PhoneMountingMethod.values().filter { it != PhoneMountingMethod.CHEST_FORWARD }.forEach { method ->
            assertBlocked(lookup(confirmation = confirmation(1_100L).copy(method = method)))
        }
    }

    @Test fun revokedMountingOutputsCannotQueryChestHeading() {
        assertBlocked(lookup(outputsAllowed = false))
    }

    @Test fun explicitFieldConfirmationKeepsItsIndependentExistingException() {
        listOf(null, confirmation(1_500L), confirmation(1_500L).copy(epoch = otherEpoch)).forEach { receipt ->
            val observed = lookup(
                confirmation = receipt,
                outputsAllowed = false,
                runtimeState = null,
                explicitFieldConfirmation = true,
            )
            assertEquals(heading(1_200L), observed.heading)
            assertEquals(listOf(1_200L to 500L), observed.queries)
        }
    }

    @Test fun defaultHistoryAgeKeepsItsInclusiveFiveHundredMillisecondBoundary() {
        assertEquals(heading(1_200L), lookup(timestampMs = 1_700L).heading)
        val expired = lookup(timestampMs = 1_701L)
        assertNull(expired.heading)
        assertEquals(listOf(1_701L to 500L), expired.queries)
    }

    @Test fun gpsCourseAlignedLookupKeepsItsOriginalOneHundredMillisecondBoundary() {
        val inclusive = lookup(timestampMs = 1_300L, maximumAgeMs = 100L)
        assertEquals(heading(1_200L), inclusive.heading)
        assertEquals(listOf(1_300L to 100L), inclusive.queries)
        val expired = lookup(timestampMs = 1_301L, maximumAgeMs = 100L)
        assertNull(expired.heading)
        assertEquals(listOf(1_301L to 100L), expired.queries)
    }

    @Test fun historyNeverReturnsAFutureSensorHeadingForAnApprovedStep() {
        val observed = lookup(timestampMs = 1_199L)

        assertNull(observed.heading)
        assertEquals(listOf(1_199L to 500L), observed.queries)
    }

    @Test fun validGpsCourseIsIndependentOfMissingChestConfirmation() {
        val chest = lookup(confirmation = null).heading
        val selected = PedestrianHeadingSelector().select(
            PedestrianHeadingInput(
                nowElapsedRealtimeMs = 1_200L,
                speed = WalkingSpeedObservation(1.0, 0.1, 1_200L),
                gpsCourse = HeadingObservation(35.0, 5.0, 1_200L),
                magneticTrueHeading = chest,
                phoneForwardMounted = chest != null,
            ),
        )

        assertNull(chest)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, requireNotNull(selected).source)
        assertEquals(35.0, selected.headingDegreesTrueNorth, 0.0)
        assertEquals(1_200L, selected.sourceObservationAtElapsedRealtimeMs)
    }

    @Test fun actualPrivateCallerRetainsOriginalStepCourseAndExplicitFieldLeaseWiring() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val wrapper = MainActivityRefactorSource.function(source, "private fun chestMountedHeadingForStep(")
        val wiring = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(wiring.contains("override val fieldChestConfirmed get() = positionFieldController.explicitChestConfirmed"))
        assertTrue(wiring.contains("override val fieldLeasePresent get() = currentPositionFieldLeaseOrNull() != null"))
        val androidSources = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        assertTrue(androidSources.contains("orientation().chestMountedHeadingAt(timestampMs, maximumAgeMs)"))
        assertTrue(wrapper.contains("if (!sources.orientationAvailable) return null"))
        assertTrue(wrapper.contains("val walkEpoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return null"))
        assertTrue(wrapper.contains("scope.fieldChestConfirmed &&\n            scope.fieldLeasePresent"))
        val fieldController = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/PositionFieldRecorderController.kt",
        ).readText()
        assertTrue(fieldController.contains("val explicitChestConfirmed: Boolean get() = recording != null"))
        assertTrue(wrapper.contains("timestampMs = timestampMs"))
        assertTrue(wrapper.contains("maximumAgeMs = maximumAgeMs"))
        assertTrue(wrapper.contains("lookupHeading = sources::chestHeading"))
        val step = MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent(")
        assertTrue(step.contains("chestMountedHeadingForStep(event.timestampMs)"))
        assertTrue(step.contains("chestMountedHeadingForStep(courseAtStep.elapsedRealtimeMs, maximumAgeMs = 100L)"))
        assertTrue(step.contains("gpsCourse = courseAtStep"))
        assertTrue(step.contains("phoneForwardMounted = chestHeading != null"))
    }

    private fun confirmation(atMs: Long) = PhoneMountingUserConfirmation(
        epoch = epoch,
        confirmedAtElapsedRealtimeMs = atMs,
        method = PhoneMountingMethod.CHEST_FORWARD,
        postFaultCorrectionConfirmed = false,
    )

    private fun heading(atMs: Long) = HeadingObservation(90.0, 5.0, atMs)

    private data class ObservedLookup(
        val heading: HeadingObservation?,
        val queries: List<Pair<Long, Long>>,
    )

    private fun lookup(
        timestampMs: Long = 1_200L,
        confirmation: PhoneMountingUserConfirmation? = confirmation(1_100L),
        outputsAllowed: Boolean = true,
        runtimeState: PhoneMountingRuntimeState? = PhoneMountingRuntimeState(epoch, null),
        explicitFieldConfirmation: Boolean = false,
        maximumAgeMs: Long = 500L,
    ): ObservedLookup {
        val history = HeadingObservationHistory()
        assertTrue(history.add(heading(1_200L)))
        val queries = mutableListOf<Pair<Long, Long>>()
        val result = MainActivity().chestMountedHeadingForStep(
            timestampMs = timestampMs,
            maximumAgeMs = maximumAgeMs,
            walkEpoch = epoch,
            explicitFieldConfirmation = explicitFieldConfirmation,
            phoneMountingOutputsAllowed = outputsAllowed,
            phoneMountingRuntimeState = runtimeState,
            phoneMountingUserConfirmation = confirmation,
            lookupHeading = { originalTimestamp, originalMaximumAge ->
                queries += originalTimestamp to originalMaximumAge
                history.atOrBefore(originalTimestamp, originalMaximumAge)
            },
        )
        return ObservedLookup(result, queries)
    }

    private fun assertBlocked(observed: ObservedLookup) {
        assertNull(observed.heading)
        assertTrue(observed.queries.isEmpty())
    }
}
