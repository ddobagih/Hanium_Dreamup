package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WalkSessionReadinessTest {
    @Test
    fun fullWalkReadinessRequiresEveryObservationFromOneEpoch() {
        val epoch = WalkRuntimeEpoch("walk-1", 3)
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(
                action = WalkSessionAction.RESUME_WALK,
                mode = WalkSessionMode.FULL,
            ),
        )

        requiredForFullResume().forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.READY,
                ),
            )
        }

        val readiness = collector.build(assessedAtEpochMs = 100)

        assertTrue(readiness.isReady)
        assertEquals(epoch, readiness.epoch)
        assertEquals(
            WalkSessionReadinessRequirement.entries.toSet(),
            readiness.observations.keys,
        )
        assertEquals(
            WalkSessionReadinessStatus.NOT_REQUIRED,
            readiness.observations.getValue(
                WalkSessionReadinessRequirement.ROUTE_SERVICE,
            ).status,
        )
        assertEquals(
            WalkSessionReadinessStatus.READY,
            readiness.observations.getValue(
                WalkSessionReadinessRequirement.GATEWAY,
            ).status,
        )
    }

    @Test
    fun collectorRejectsAnObservationFromAnotherRecoveryGeneration() {
        val epoch = WalkRuntimeEpoch("walk-1", 3)
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(
                action = WalkSessionAction.START_WALK,
                mode = WalkSessionMode.DISTANCE_LIMITED,
            ),
        )

        expectIllegalArgument {
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch.copy(recoveryGeneration = 2),
                    requirement = WalkSessionReadinessRequirement.CAMERA,
                    status = WalkSessionReadinessStatus.READY,
                ),
            )
        }
    }

    @Test
    fun collectorCannotBuildWhenOneRequiredObservationIsMissing() {
        val epoch = WalkRuntimeEpoch("walk-1", 0)
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(
                action = WalkSessionAction.START_WALK,
                mode = WalkSessionMode.FULL,
            ),
        )
        collector.recordReadyRequiredExcept(
            epoch = epoch,
            excluded = WalkSessionReadinessRequirement.MODEL,
        )

        expectIllegalState { collector.build(assessedAtEpochMs = 100) }
    }

    @Test
    fun unavailableRequiredObservationBlocksReadiness() {
        val epoch = WalkRuntimeEpoch("walk-1", 0)
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(
                action = WalkSessionAction.START_WALK,
                mode = WalkSessionMode.FULL,
            ),
        )
        collector.recordReadyRequiredExcept(
            epoch = epoch,
            excluded = WalkSessionReadinessRequirement.MODEL,
        )
        collector.record(
            WalkSessionReadinessObservation(
                epoch = epoch,
                requirement = WalkSessionReadinessRequirement.MODEL,
                status = WalkSessionReadinessStatus.UNAVAILABLE,
                reason = "model_not_ready",
            ),
        )

        val readiness = collector.build(assessedAtEpochMs = 100)

        assertFalse(readiness.isReady)
        assertEquals(listOf("model_not_ready"), readiness.blockingReasons)
    }

    @Test
    fun distanceLimitedWalkKeepsLifecycleModeSeparateFromDistanceReadiness() {
        val plan = WalkSessionReadinessPlan(
            action = WalkSessionAction.RESUME_WALK,
            mode = WalkSessionMode.DISTANCE_LIMITED,
        )

        assertTrue(
            plan.requires(WalkSessionReadinessRequirement.LOCATION),
        )
        assertFalse(
            plan.requires(WalkSessionReadinessRequirement.METRIC_DISTANCE),
        )
        assertTrue(
            plan.requires(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT),
        )
        assertTrue(
            plan.requires(WalkSessionReadinessRequirement.VOICE_INPUT),
        )
    }

    @Test
    fun firstRunOnboardingIsRequiredFailClosedForEveryAction() {
        WalkSessionAction.entries.forEach { action ->
            val epoch = WalkRuntimeEpoch("walk-${action.name}", 0)
            val collector = WalkSessionReadinessCollector(
                epoch = epoch,
                plan = WalkSessionReadinessPlan(
                    action = action,
                    mode = WalkSessionMode.FULL,
                ),
            )

            assertTrue(
                collector.plan.requires(
                    WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING,
                ),
            )
            collector.recordReadyRequiredExcept(
                epoch = epoch,
                excluded = WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING,
            )

            expectIllegalState { collector.build(assessedAtEpochMs = 100) }
        }
    }

    @Test
    fun phoneMountingIsRequiredOnlyForWalkStartAndResume() {
        listOf(
            WalkSessionAction.START_WALK,
            WalkSessionAction.RESUME_WALK,
        ).forEach { action ->
            val plan = WalkSessionReadinessPlan(
                action = action,
                mode = WalkSessionMode.FULL,
            )

            assertTrue(plan.requires(WalkSessionReadinessRequirement.PHONE_MOUNTING))
        }

        listOf(
            WalkSessionAction.DESTINATION_SEARCH,
            WalkSessionAction.ROUTE_REQUEST,
            WalkSessionAction.REPORT_TRANSMISSION,
        ).forEach { action ->
            val plan = WalkSessionReadinessPlan(
                action = action,
                mode = WalkSessionMode.FULL,
            )

            assertFalse(plan.requires(WalkSessionReadinessRequirement.PHONE_MOUNTING))
        }
    }

    @Test
    fun actionRequirementMatrixRequiresVerifiedGatewayForWalkReadiness() {
        val baseWalk = WalkSessionReadinessPlan(
            action = WalkSessionAction.START_WALK,
            mode = WalkSessionMode.FULL,
        )
        val resumeWalk = WalkSessionReadinessPlan(
            action = WalkSessionAction.RESUME_WALK,
            mode = WalkSessionMode.FULL,
        )
        listOf(baseWalk, resumeWalk).forEach { plan ->
            assertTrue(
                plan.requires(
                    WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING,
                ),
            )
            assertTrue(
                plan.requires(
                    WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT,
                ),
            )
            assertTrue(plan.requires(WalkSessionReadinessRequirement.DEVICE_RESOURCES))
            assertFalse(plan.requires(WalkSessionReadinessRequirement.PERMISSIONS))
            assertFalse(plan.requires(WalkSessionReadinessRequirement.ROUTE_SERVICE))
            assertTrue(plan.requires(WalkSessionReadinessRequirement.GATEWAY))
        }

        val destinationSearch = WalkSessionReadinessPlan(
            action = WalkSessionAction.DESTINATION_SEARCH,
            mode = WalkSessionMode.FULL,
        )
        assertTrue(destinationSearch.requires(WalkSessionReadinessRequirement.ROUTE_SERVICE))
        assertTrue(destinationSearch.requires(WalkSessionReadinessRequirement.GATEWAY))
        assertFalse(destinationSearch.requires(WalkSessionReadinessRequirement.DEVICE_RESOURCES))
        assertFalse(destinationSearch.requires(WalkSessionReadinessRequirement.CAMERA))
        assertFalse(
            destinationSearch.requires(
                WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING,
            ),
        )
        assertFalse(
            destinationSearch.requires(
                WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT,
            ),
        )

        val routeRequest = WalkSessionReadinessPlan(
            action = WalkSessionAction.ROUTE_REQUEST,
            mode = WalkSessionMode.FULL,
        )
        listOf(
            WalkSessionReadinessRequirement.LOCATION,
            WalkSessionReadinessRequirement.VOICE_OUTPUT,
            WalkSessionReadinessRequirement.ROUTE_SERVICE,
            WalkSessionReadinessRequirement.GATEWAY,
            WalkSessionReadinessRequirement.DEVICE_RESOURCES,
        ).forEach { requirement ->
            assertTrue(routeRequest.requires(requirement))
        }
        assertFalse(routeRequest.requires(WalkSessionReadinessRequirement.CAMERA))

        val report = WalkSessionReadinessPlan(
            action = WalkSessionAction.REPORT_TRANSMISSION,
            mode = WalkSessionMode.FULL,
        )
        listOf(
            WalkSessionReadinessRequirement.LOCATION,
            WalkSessionReadinessRequirement.GATEWAY,
            WalkSessionReadinessRequirement.DEVICE_RESOURCES,
        ).forEach { requirement ->
            assertTrue(report.requires(requirement))
        }
        listOf(
            WalkSessionReadinessRequirement.CAMERA,
            WalkSessionReadinessRequirement.MODEL,
            WalkSessionReadinessRequirement.ROUTE_SERVICE,
        ).forEach { requirement ->
            assertFalse(report.requires(requirement))
        }

        assertFalse(baseWalk.requires(WalkSessionReadinessRequirement.ROUTE_SERVICE))
        assertTrue(baseWalk.requires(WalkSessionReadinessRequirement.GATEWAY))
    }

    private fun WalkSessionReadinessCollector.recordReadyRequiredExcept(
        epoch: WalkRuntimeEpoch,
        excluded: WalkSessionReadinessRequirement,
    ) {
        plan.requiredRequirements
            .filterNot { it == excluded }
            .forEach { requirement ->
                record(
                    WalkSessionReadinessObservation(
                        epoch = epoch,
                        requirement = requirement,
                        status = WalkSessionReadinessStatus.READY,
                    ),
                )
            }
    }

    private fun requiredForFullResume(): Set<WalkSessionReadinessRequirement> =
        WalkSessionReadinessPlan(
            action = WalkSessionAction.RESUME_WALK,
            mode = WalkSessionMode.FULL,
        ).requiredRequirements

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            Unit
        }
    }
}
