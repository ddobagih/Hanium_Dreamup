package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorTest {
    @Test
    fun preservesLegacyUpdateDefaultArgumentJvmBridge() {
        val expectedParameterTypes = listOf(
            RouteNavigator::class.java,
            TrustedLocation::class.java,
            java.lang.Long.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Double::class.java,
            Integer.TYPE,
            Any::class.java,
        )

        val legacyDefaultBridgeExists = RouteNavigator::class.java.declaredMethods.any { method ->
            method.name == "update\$default" &&
                method.parameterTypes.toList() == expectedParameterTypes
        }

        assertTrue(legacyDefaultBridgeExists)
        val navigator = RouteNavigator().apply { setRoute(route()) }
        assertEquals(
            "route_guidance",
            navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false).reason,
        )
    }

    @Test
    fun emitsFallbackInstructionWhenGuideInstructionMissing() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = null))

        val update = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)

        assertNotNull(update.instruction)
        assertFalse(update.shouldReroute)
    }

    @Test
    fun exposesCurrentRouteBearingForMotionContext() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(bearingDeg = 12.5f))

        assertEquals(12.5f, navigator.currentBearingDeg()!!, 0.001f)
    }

    @Test
    fun explicitVoiceQueryDoesNotSpeakUncodedProviderTurnText() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val instruction = navigator.currentInstruction(locationNearStart())

        assertTrue(instruction?.contains("안내 지점") == true)
        assertFalse(instruction?.contains("오른쪽으로 이동하세요.") == true)
    }

    @Test
    fun tmapCrosswalkTurnTypesKeepReferenceNoticeWithoutRawCrossingCommands() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        listOf(211, 212, 213).forEach { turnType ->
            val navigator = RouteNavigator()
            val rawCommand = "신호가 초록색이니 지금 건너세요."
            val baseRoute = route(guideInstruction = rawCommand)
            navigator.setRoute(
                baseRoute.copy(
                    guidePoints = baseRoute.guidePoints.map { guide ->
                        guide.copy(turnType = turnType)
                    },
                ),
            )

            val current = navigator.currentInstruction(locationNearStart())
            val update = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 1_000L))

            assertTrue(current?.contains(CROSSWALK_REFERENCE_NOTICE_KO) == true)
            assertTrue(update.instruction?.contains(CROSSWALK_REFERENCE_NOTICE_KO) == true)
            assertTrue(update.instruction?.contains("m 앞") == true)
            assertFalse(current?.contains(rawCommand) == true)
            assertFalse(current?.contains("지금 건너세요") == true)
        }
    }

    @Test
    fun crosswalkTextMarkersKeepReferenceNoticeWithoutRawCrossingCommands() {
        val cases = listOf(
            Triple("횡단보도를 건너세요.", null, null),
            Triple("CrossWalk ahead, cross now.", null, null),
            Triple("지금 건너도 안전합니다.", "zebra", null),
            Triple("건너도 됩니다.", null, "횡단보도"),
        )

        cases.forEach { (instruction, pointType, pointName) ->
            val navigator = RouteNavigator()
            val baseRoute = route(guideInstruction = instruction)
            navigator.setRoute(
                baseRoute.copy(
                    guidePoints = baseRoute.guidePoints.map { guide ->
                        guide.copy(
                            point = guide.point.copy(name = pointName),
                            pointType = pointType,
                        )
                    },
                ),
            )

            val output = navigator.currentInstruction(locationNearStart())

            assertTrue(output?.contains(CROSSWALK_REFERENCE_NOTICE_KO) == true)
            assertFalse(output?.contains("지금 건너세요") == true)
            assertFalse(output?.contains("건너도 됩니다") == true)
            assertFalse(output?.contains("안전합니다") == true)
            assertFalse(output?.contains("cross now", ignoreCase = true) == true)
        }
    }

    @Test
    fun nonCrosswalkUncodedInstructionUsesSafeGuideNotice() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val output = navigator.currentInstruction(locationNearStart())

        assertTrue(output?.contains("안내 지점") == true)
        assertFalse(output?.contains("오른쪽으로 이동하세요.") == true)
        assertFalse(output == CROSSWALK_REFERENCE_NOTICE_KO)
    }

    @Test
    fun voiceInstructionQueryReturnsNullWithoutAnActiveRoute() {
        assertEquals(null, RouteNavigator().currentInstruction(locationNearStart()))
    }

    @Test
    fun exposesWhetherAnAuthoritativeTmapRouteIsInstalled() {
        val navigator = RouteNavigator()
        assertFalse(navigator.hasRoute())

        navigator.setRoute(route())
        assertTrue(navigator.hasRoute())

        navigator.clear()
        assertFalse(navigator.hasRoute())
    }

    @Test
    fun confirmedOffRouteRequiresUserDecisionWithoutAutomaticReroute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val pending = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val confirmed = navigator.update(offRouteLocation().copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false)
        val repeated = navigator.update(offRouteLocation().copy(elapsedRealtimeMs = 3_000L), nowMs = 3_000L, requestInFlight = false)

        assertFalse(pending.shouldReroute)
        assertTrue(pending.userDecisionRequired)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, pending.pendingUserDecision)
        assertTrue(pending.cancelStaleNavigationSpeech)
        assertEquals(null, navigator.currentInstruction(offRouteLocation()))
        listOf(confirmed, repeated).forEach { update ->
            assertTrue(update.offRoute)
            assertFalse(update.shouldReroute)
            assertTrue(update.userDecisionRequired)
            assertEquals(RouteNavigatorUserDecision.REROUTE, update.pendingUserDecision)
            assertEquals("off_route_user_decision_required", update.reason)
            assertTrue(update.instruction?.contains("사용자 선택이 필요") == true)
            assertFalse(update.instruction?.contains("직진하세요") == true)
            assertFalse(update.instruction?.contains("다시 찾습니다") == true)
        }
        assertEquals(RouteNavigatorUserDecision.REROUTE, navigator.pendingUserDecision())
    }

    @Test
    fun onlyExplicitRerouteApprovalRequestsANewRoute() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route())
        val detected = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)

        val approved = navigator.approveReroute()
        val waiting = navigator.update(offRouteLocation().copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = true)

        assertFalse(detected.shouldReroute)
        assertTrue(approved.shouldReroute)
        assertEquals("off_route_reroute_approved", approved.reason)
        assertEquals(null, navigator.pendingUserDecision())
        assertFalse(waiting.shouldReroute)
        assertFalse(waiting.userDecisionRequired)
        assertEquals("off_route_reroute_approved_in_flight", waiting.reason)
        assertEquals(null, navigator.currentInstruction(offRouteLocation()))
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun failedRerouteRequestRestoresTheExplicitUserDecision() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route())
        navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        assertTrue(navigator.approveReroute().shouldReroute)

        val restored = navigator.rerouteRequestFailed()

        assertEquals(RouteNavigatorUserDecision.REROUTE, navigator.pendingUserDecision())
        assertEquals(RouteNavigatorUserDecision.REROUTE, restored?.pendingUserDecision)
        assertTrue(restored?.instruction?.contains("새 경로 요청") == true)
        assertTrue(navigator.approveReroute().shouldReroute)
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun confirmedDeviationOffersOnlyNewRouteLocationRecheckOrEndNavigation() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route())
        navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)

        val recheck = navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)
        val stillLatched = navigator.update(locationNearStart(), nowMs = 2_000L, requestInFlight = false)

        assertEquals("off_route_location_recheck_requested", recheck.reason)
        assertFalse(recheck.shouldReroute)
        assertEquals(RouteNavigatorUserDecision.REROUTE, recheck.pendingUserDecision)
        assertTrue(stillLatched.offRoute)
        assertTrue(stillLatched.userDecisionRequired)
        assertEquals(null, navigator.currentInstruction(locationNearStart()))

        val ended = navigator.selectDeviationChoice(RouteDeviationChoice.END_NAVIGATION)
        assertEquals("off_route_navigation_ended", ended.reason)
        assertFalse(navigator.hasRoute())
    }

    @Test
    fun confirmedOffRouteInstructionOffersAllThreeApprovedChoices() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route())

        val update = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)

        val instruction = update.instruction ?: ""
        assertTrue(instruction.contains("새 경로"))
        assertTrue(instruction.contains("위치 다시 확인"))
        assertTrue(instruction.contains("길안내 종료"))
    }

    @Test
    fun confirmedLocationRecheckInvalidatesThePreviousDecisionToken() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route())
        navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val beforeRecheck = navigator.pendingDecisionToken()

        navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)

        assertNotNull(beforeRecheck)
        assertNotNull(navigator.pendingDecisionToken())
        assertTrue(beforeRecheck != navigator.pendingDecisionToken())
    }

    @Test
    fun confirmedOffRouteNeverRepeatsTheStaleTurnWhileRerouteIsInFlight() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val update = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = true)

        assertTrue(update.offRoute)
        assertFalse(update.shouldReroute)
        assertTrue(update.userDecisionRequired)
        assertEquals("off_route_user_decision_required", update.reason)
        assertTrue(update.instruction?.contains("사용자 선택이 필요") == true)
        assertFalse(update.instruction?.contains("오른쪽") == true)
        assertEquals(null, navigator.currentInstruction(offRouteLocation()))
    }

    @Test
    fun requiresConsecutiveOffRouteSamplesBeforeReroute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val second = navigator.update(offRouteLocation().copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false)

        assertFalse(first.shouldReroute)
        assertFalse(first.offRoute)
        assertTrue(first.userDecisionRequired)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, first.pendingUserDecision)
        assertEquals("off_route_pending", first.reason)
        assertFalse(second.shouldReroute)
        assertTrue(second.offRoute)
        assertTrue(second.userDecisionRequired)
        assertEquals("off_route_user_decision_required", second.reason)
    }

    @Test
    fun untrustedFixBreaksOffRouteConfirmationEvidence() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        navigator.onUntrustedLocation()
        val afterUntrusted = navigator.update(offRouteLocation().copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false)

        assertEquals("off_route_pending", first.reason)
        assertEquals("off_route_pending", afterUntrusted.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, afterUntrusted.pendingUserDecision)
        assertFalse(afterUntrusted.offRoute)
    }

    @Test
    fun untrustedLocationRequiresExplicitRecheckBeforeFreshGpsCanResumeGuidance() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "직진하세요."))

        val untrusted = navigator.onUntrustedLocation()
        val recoverySignalOnly = navigator.update(locationNearStart().copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 2_000L))
        val blocked = navigator.currentInstruction(locationNearStart())
        navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)
        val freshAfterRecheck = navigator.update(locationNearStart().copy(elapsedRealtimeMs = 3_000L), nowMs = 3_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 3_000L))

        assertEquals("location_untrusted_recheck_required", untrusted?.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, untrusted?.pendingUserDecision)
        assertTrue(untrusted?.instruction?.contains("현재 위치 정확도를 신뢰할 수 없어") == true)
        assertTrue(untrusted?.instruction?.contains("위치 다시 확인을 선택할 때까지") == true)
        assertEquals("off_route_location_recheck_required", recoverySignalOnly.reason)
        assertEquals(null, blocked)
        assertEquals("route_guidance", freshAfterRecheck.reason)
        assertTrue(freshAfterRecheck.instruction?.contains("안내 지점") == true)
        assertFalse(freshAfterRecheck.instruction?.contains("직진하세요") == true)
    }

    @Test
    fun excessiveSampleGapBreaksOffRouteConfirmationEvidence() {
        val navigator = RouteNavigator(RouteNavigatorConfig(maximumOffRouteSampleGapMs = 5_000L))
        navigator.setRoute(route())

        val first = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val afterGap = navigator.update(offRouteLocation().copy(elapsedRealtimeMs = 6_001L), nowMs = 6_001L, requestInFlight = false)

        assertEquals("off_route_pending", first.reason)
        assertEquals("off_route_pending", afterGap.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, afterGap.pendingUserDecision)
        assertFalse(afterGap.offRoute)
    }

    @Test
    fun suspectedDeviationResumesOnlyAfterExplicitRecheckAndFreshOnRouteGps() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "직진하세요."))
        val suspected = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 1_000L))

        val recoverySignalOnly = navigator.update(locationNearStart().copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 2_000L))
        val blockedInstruction = navigator.currentInstruction(locationNearStart())
        val recheck = navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)
        val freshOnRoute = navigator.update(locationNearStart().copy(elapsedRealtimeMs = 3_000L), nowMs = 3_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 3_000L))

        assertEquals("off_route_pending", suspected.reason)
        assertEquals("off_route_location_recheck_required", recoverySignalOnly.reason)
        assertEquals(null, blockedInstruction)
        assertEquals("off_route_location_recheck_requested", recheck.reason)
        assertEquals("route_guidance", freshOnRoute.reason)
        assertTrue(freshOnRoute.instruction?.contains("안내 지점") == true)
        assertFalse(freshOnRoute.instruction?.contains("직진하세요") == true)
    }

    @Test
    fun ignoresOffRouteWhenAccuracyStillOverlapsRoute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(uncertainOffRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val second = navigator.update(uncertainOffRouteLocation().copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false)
        val preciseFirst = navigator.update(slightlyOffRouteLocation(accuracyM = 3f).copy(elapsedRealtimeMs = 8_000L), nowMs = 8_000L, requestInFlight = false)

        assertFalse(first.shouldReroute)
        assertFalse(first.offRoute)
        assertFalse(second.shouldReroute)
        assertFalse(second.offRoute)
        assertFalse(preciseFirst.shouldReroute)
        assertEquals("off_route_pending", preciseFirst.reason)
    }

    @Test
    fun arrivalCandidateRequiresExplicitConfirmationBeforeClearingRoute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(
            TrustedLocation(37.0009, 127.0, 5f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )
        // S07-32 (R6): the arrival question needs 3 s of consecutive near-destination evidence.
        for (at in listOf(2_000L, 3_000L)) {
            assertFalse(navigator.update(TrustedLocation(37.0009, 127.0, 5f, at), nowMs = at, requestInFlight = false).arrivalCandidate)
        }
        val second = navigator.update(
            TrustedLocation(37.0009, 127.0, 5f, 4_000L),
            nowMs = 4_000L,
            requestInFlight = false,
        )

        assertFalse(first.arrived)
        assertFalse(first.arrivalCandidate)
        assertFalse(second.arrived)
        assertTrue(second.arrivalCandidate)
        assertTrue(second.userDecisionRequired)
        assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, second.pendingUserDecision)
        assertEquals("arrival_confirmation_required", second.reason)
        assertTrue(second.instruction?.contains("도착 후보") == true)
        assertTrue(navigator.hasRoute())

        navigator.acknowledgeInstruction(second, spokenAtMs = 4_000L)

        assertTrue(navigator.hasRoute())

        val confirmed = navigator.confirmArrival()

        assertTrue(confirmed.arrived)
        assertEquals("arrival_confirmed", confirmed.reason)
        assertFalse(navigator.hasRoute())
    }

    @Test
    fun rejectingArrivalKeepsRouteAndResetsTheCandidate() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())
        val nearEnd = TrustedLocation(37.0009, 127.0, 5f, 1_000L)
        // S07-32 (R6): the arrival question needs 3 s of consecutive near-destination evidence.
        for (at in 1_000L..3_000L step 1_000L) navigator.update(nearEnd.copy(elapsedRealtimeMs = at), nowMs = at, requestInFlight = false)
        val candidate = navigator.update(nearEnd.copy(elapsedRealtimeMs = 4_000L), nowMs = 4_000L, requestInFlight = false)

        val rejected = navigator.rejectArrival()
        val firstAfterRejection = navigator.update(nearEnd.copy(elapsedRealtimeMs = 5_000L), nowMs = 5_000L, requestInFlight = false)

        assertTrue(candidate.arrivalCandidate)
        assertFalse(rejected.arrived)
        assertFalse(rejected.arrivalCandidate)
        assertEquals("arrival_rejected_route_retained", rejected.reason)
        assertTrue(navigator.hasRoute())
        assertFalse(firstAfterRejection.arrivalCandidate)
        assertEquals("route_guidance", firstAfterRejection.reason)
    }

    @Test
    fun strideProgressCannotCreateArrivalWithoutTrustedGpsAndTmapEndEvidence() {
        val navigator = RouteNavigator(RouteNavigatorConfig(arrivalConfirmSamples = 1))
        navigator.setRoute(route())

        val update = navigator.update(
            location = locationNearStart(),
            nowMs = 1_000L,
            requestInFlight = false,
            stepProgressM = 100.0,
        )

        assertFalse(update.arrivalCandidate)
        assertFalse(update.arrived)
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun strideConsistencyIsObservedWithoutOverridingAuthoritativeArrivalEvidence() {
        val navigator = RouteNavigator(RouteNavigatorConfig(arrivalConfirmSamples = 1))
        navigator.setRoute(route())
        val nearEnd = TrustedLocation(37.0009, 127.0, 5f, 1_000L)
        // S07-32 (R6): the arrival question needs 3 s of consecutive near-destination evidence.
        for (at in 1_000L..3_000L step 1_000L) {
            assertFalse(navigator.update(nearEnd.copy(elapsedRealtimeMs = at), at, false, stepProgressM = 0.0).arrivalCandidate)
        }

        val inconsistent = navigator.update(
            location = nearEnd.copy(elapsedRealtimeMs = 4_000L),
            nowMs = 4_000L,
            requestInFlight = false,
            stepProgressM = 0.0,
        )
        val corroborated = navigator.update(
            location = nearEnd.copy(elapsedRealtimeMs = 5_000L),
            nowMs = 5_000L,
            requestInFlight = false,
            stepProgressM = 100.0,
        )

        assertEquals(false, inconsistent.stepProgressConsistent)
        assertTrue(inconsistent.arrivalCandidate)
        assertFalse(inconsistent.arrived)
        assertEquals(true, corroborated.stepProgressConsistent)
        assertTrue(corroborated.arrivalCandidate)
        assertFalse(corroborated.arrived)
    }

    @Test
    fun stepProgressOnlyReportsConsistencyAndNeverChangesTheRouteDecision() {
        fun decisionWith(stepProgressM: Double?): RouteNavigatorUpdate {
            val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
            navigator.setRoute(route())
            return navigator.update(
                location = locationNearStart(),
                nowMs = 1_000L,
                requestInFlight = false,
                stepProgressM = stepProgressM,
            )
        }

        val withoutSteps = decisionWith(null)
        val consistent = decisionWith(5.0)
        val wildlyOff = decisionWith(100_000.0)

        listOf(consistent, wildlyOff).forEach { update ->
            assertEquals(withoutSteps.instruction, update.instruction)
            assertEquals(withoutSteps.arrived, update.arrived)
            assertEquals(withoutSteps.offRoute, update.offRoute)
            assertEquals(withoutSteps.shouldReroute, update.shouldReroute)
            assertEquals(withoutSteps.reason, update.reason)
        }
        assertEquals(false, wildlyOff.stepProgressConsistent)
    }

    @Test
    fun pendingDecisionTokenChangesWhenTheSameRouteIsReinstalled() {
        val navigator = RouteNavigator(RouteNavigatorConfig(arrivalConfirmSamples = 1))
        val nearEnd = TrustedLocation(37.0009, 127.0, 5f, 1_000L)
        navigator.setRoute(route())
        // S07-32 (R6): the arrival question needs 3 s of consecutive near-destination evidence.
        for (at in 1_000L..4_000L step 1_000L) navigator.update(nearEnd.copy(elapsedRealtimeMs = at), nowMs = at, requestInFlight = false)
        val firstToken = navigator.pendingDecisionToken()

        navigator.setRoute(route())
        for (at in 5_000L..8_000L step 1_000L) navigator.update(nearEnd.copy(elapsedRealtimeMs = at), nowMs = at, requestInFlight = false)
        val replacementToken = navigator.pendingDecisionToken()

        assertNotNull(firstToken)
        assertNotNull(replacementToken)
        assertTrue(firstToken != replacementToken)
    }

    @Test
    fun staleArrivalDecisionTokenCannotConfirmOrRejectAReplacementRoute() {
        val navigator = RouteNavigator(RouteNavigatorConfig(arrivalConfirmSamples = 1))
        val nearEnd = TrustedLocation(37.0009, 127.0, 5f, 1_000L)
        navigator.setRoute(route())
        // S07-32 (R6): the arrival question needs 3 s of consecutive near-destination evidence.
        for (at in 1_000L..4_000L step 1_000L) navigator.update(nearEnd.copy(elapsedRealtimeMs = at), nowMs = at, requestInFlight = false)
        val staleToken = requireNotNull(navigator.pendingDecisionToken())

        navigator.setRoute(route())
        for (at in 5_000L..8_000L step 1_000L) navigator.update(nearEnd.copy(elapsedRealtimeMs = at), nowMs = at, requestInFlight = false)

        assertEquals("arrival_confirmation_stale", navigator.confirmArrival(staleToken).reason)
        assertEquals("arrival_confirmation_stale", navigator.rejectArrival(staleToken).reason)
        assertTrue(navigator.hasRoute())
        assertEquals(
            RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,
            navigator.pendingUserDecision(),
        )
    }

    @Test
    fun confirmedDeviationCannotBeClearedByOneOnRouteGpsFix() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route())
        navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val firstToken = navigator.pendingDecisionToken()

        val onRouteFix = navigator.update(locationNearStart(), nowMs = 2_000L, requestInFlight = false)
        val retainedToken = navigator.pendingDecisionToken()

        assertNotNull(firstToken)
        assertEquals(firstToken, retainedToken)
        assertTrue(onRouteFix.offRoute)
        assertTrue(onRouteFix.userDecisionRequired)
        assertEquals(null, navigator.currentInstruction(locationNearStart()))
    }

    @Test
    fun inaccurateSingleFixCannotEndNavigation() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val update = navigator.update(
            TrustedLocation(37.00082, 127.0, 10f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )

        assertFalse(update.arrived)
        assertNotNull(navigator.currentBearingDeg())
    }

    @Test
    fun passedTurnAdvancesEvenWhenItsSpeechWasNeverDelivered() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val base = route().copy(
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.00045, 127.0), "지난 회전", 50, 50, 0f),
                WalkingRouteGuidePoint(1, RoutePoint(37.00081, 127.0), "다음 회전", 90, 10, 0f),
            ),
        )
        navigator.setRoute(base)

        val unacknowledged = navigator.update(TrustedLocation(37.00063, 127.0, 5f, 1_000L), nowMs = 1_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 1_000L))
        val repeated = navigator.update(TrustedLocation(37.00063, 127.0, 5f, 1_001L), nowMs = 1_001L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 1_001L))
        navigator.acknowledgeInstruction(repeated, spokenAtMs = 1_001L)
        val afterSpeech = navigator.update(TrustedLocation(37.00063, 127.0, 5f, 1_002L), nowMs = 1_002L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 1_002L))

        assertEquals(1, unacknowledged.guideIndex)
        assertTrue(unacknowledged.instruction?.contains("안내 지점") == true)
        assertTrue(repeated.instruction?.contains("안내 지점") == true)
        assertFalse(unacknowledged.instruction?.contains("다음 회전") == true)
        assertEquals("guidance_already_completed", afterSpeech.reason)
    }

    @Test
    fun oneAcceptedJumpCannotConsumeTwoNearbyTurnsWithoutSequentialMovement() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(twoImportantTurnRoute())
        val start = TrustedLocation(37.0, 127.0, 5f, 1_000L)
        val jump = TrustedLocation(37.00063, 127.0, 5f, 31_000L)
        val smallStep = TrustedLocation(37.00068, 127.0, 5f, 32_000L)
        val continued = TrustedLocation(37.00078, 127.0, 5f, 33_000L)

        assertEquals(0, navigator.update(start, nowMs = 1_000L, requestInFlight = false).guideIndex)
        val jumped = navigator.update(jump, nowMs = 31_000L, requestInFlight = false)
        val match = requireNotNull(navigator.currentRouteMatch())
        assertTrue(match.quality == RouteMatchQuality.HIGH || match.quality == RouteMatchQuality.MEDIUM)
        assertEquals("route_guidance", jumped.reason)
        // S07-15 (R7, 07.txt:166): after the 30 s gap the accepted jump only realigns (no forced catch-up).
        assertEquals(0, jumped.guideIndex)
        assertTrue(jumped.instruction?.isNotBlank() == true)
        assertTrue(navigator.currentInstruction(jump)?.isNotBlank() == true)
        assertFalse(jumped.arrived)

        val duplicate = navigator.update(jump, nowMs = 31_000L, requestInFlight = false)
        assertEquals(0, duplicate.guideIndex)
        // The next continuous, reachable fix resumes and passes one guide only.
        val stationaryFresh = navigator.update(
            jump.copy(elapsedRealtimeMs = 31_001L), nowMs = 31_001L, requestInFlight = false,
        )
        assertEquals(1, stationaryFresh.guideIndex)
        assertEquals(1, navigator.update(smallStep, nowMs = 32_000L, requestInFlight = false).guideIndex)
        val nextObservation = navigator.update(continued, nowMs = 33_000L, requestInFlight = false)
        assertEquals("route_guidance", nextObservation.reason)
        assertNotNull(navigator.currentAcceptedRouteMatchFor(33_000L))
        assertEquals(1, nextObservation.guideIndex)
        assertTrue(nextObservation.instruction?.isNotBlank() == true)
        // The three fresh observations after the resuming fix now include genuine forward movement.
        assertEquals(null, navigator.update(
            TrustedLocation(37.00089, 127.0, 5f, 34_000L), nowMs = 34_000L, requestInFlight = false,
        ).guideIndex)
        assertEquals(null, navigator.update(
            TrustedLocation(37.00100, 127.0, 5f, 35_000L), nowMs = 35_000L, requestInFlight = false,
        ).guideIndex)
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun nearbyImportantTurnAdvancesAfterIntermediateBeyondAndForwardObservations() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val base = twoImportantTurnRoute()
        navigator.setRoute(base.copy(
            guidePoints = base.guidePoints.map { guide ->
                if (guide.index == 1) guide.copy(
                    point = RoutePoint(37.00036, 127.0),
                    distanceFromStartM = 40,
                    remainingDistanceM = 100,
                ) else guide
            },
        ))
        val first = TrustedLocation(37.000232, 127.0, 5f, 1_000L)
        val fiveMetersLater = TrustedLocation(37.000277, 127.0, 5f, 2_000L)
        val beyondTurn = TrustedLocation(37.000430, 127.0, 5f, 3_000L)
        val forwardAgain = TrustedLocation(37.000540, 127.0, 5f, 4_000L)

        assertEquals(1, navigator.update(first, 1_000L, false).guideIndex)
        val firstAdvance = navigator.update(fiveMetersLater, 2_000L, false)
        assertTrue(navigator.currentRouteMatch()?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM))
        assertEquals(1, firstAdvance.guideIndex)
        val beyond = navigator.update(beyondTurn, 3_000L, false)
        assertEquals(1, beyond.guideIndex)
        val secondAdvance = navigator.update(forwardAgain, 4_000L, false)
        assertTrue(navigator.currentRouteMatch()?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM))
        assertEquals(null, secondAdvance.guideIndex)
        assertFalse(secondAdvance.arrived)
    }

    @Test
    fun closeImportantTurnsAdvanceWithSequentialAcceptedProgress() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(closeImportantTurnRoute())
        val samples = listOf(
            TrustedLocation(37.000232, 127.0, 5f, 1_000L),
            TrustedLocation(37.000245, 127.0, 5f, 3_000L),
            TrustedLocation(37.00036, 127.0, 5f, 11_000L),
            TrustedLocation(37.00048, 127.0, 5f, 19_000L),
        )

        samples.forEachIndexed { index, sample ->
            val update = navigator.update(sample, sample.elapsedRealtimeMs, false)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(sample.elapsedRealtimeMs))
            assertEquals(if (index == samples.lastIndex) null else 1, update.guideIndex)
            assertFalse(update.arrived)
        }
    }

    @Test
    fun closeImportantTurnsRejectSingleJumpAndCompressedFixes() {
        val route = closeImportantTurnRoute()
        val first = TrustedLocation(37.000232, 127.0, 5f, 1_000L)
        val jumpedNavigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        jumpedNavigator.setRoute(route)
        assertEquals(1, jumpedNavigator.update(first, 1_000L, false).guideIndex)
        listOf(19_000L, 27_000L).forEach { atMs ->
            val jump = TrustedLocation(37.00048, 127.0, 5f, atMs)
            assertEquals(1, jumpedNavigator.update(jump, atMs, false).guideIndex)
        }

        val compressedNavigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        compressedNavigator.setRoute(route)
        listOf(37.000232, 37.000245, 37.00036, 37.00048).forEachIndexed { index, latitude ->
            val atMs = 1_000L + index
            val fix = TrustedLocation(latitude, 127.0, 5f, atMs)
            assertEquals(1, compressedNavigator.update(fix, atMs, false).guideIndex)
        }
    }

    @Test
    fun millisecondCompressedFixesCannotConsumeTheNextImportantTurn() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val base = twoImportantTurnRoute()
        navigator.setRoute(base.copy(
            guidePoints = base.guidePoints.map { guide ->
                if (guide.index == 1) guide.copy(
                    point = RoutePoint(37.00036, 127.0),
                    distanceFromStartM = 40,
                    remainingDistanceM = 100,
                ) else guide
            },
        ))

        val fixes = listOf(
            TrustedLocation(37.000232, 127.0, 5f, 1_000L),
            TrustedLocation(37.000277, 127.0, 5f, 1_001L),
            TrustedLocation(37.000430, 127.0, 5f, 1_002L),
            TrustedLocation(37.000540, 127.0, 5f, 1_003L),
        )
        fixes.forEach { fix ->
            val update = navigator.update(fix, fix.elapsedRealtimeMs, false)
            assertEquals(1, update.guideIndex)
            assertFalse(update.arrived)
        }
    }

    @Test
    fun scaledSummaryTurnAdvancesAfterMappedIntermediateBeyondAndForwardObservations() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(scaledSummaryTwoImportantTurnRoute())
        val samples = listOf(
            TrustedLocation(37.00025, 127.0, 2f, 1_000L),
            TrustedLocation(37.000405, 127.0, 2f, 2_000L),
            TrustedLocation(37.00063, 127.0, 2f, 3_000L),
            TrustedLocation(37.00072, 127.0, 2f, 4_000L),
        )

        samples.forEachIndexed { index, sample ->
            val update = navigator.update(sample, sample.elapsedRealtimeMs, false)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(sample.elapsedRealtimeMs))
            assertEquals(if (index == samples.lastIndex) null else 1, update.guideIndex)
            assertFalse(update.arrived)
        }
    }

    @Test
    fun acceptedJitterAndOneTwelveMeterJumpCannotReplaceSequentialTurnEvidence() {
        val base = twoImportantTurnRoute()
        val closeTurns = base.copy(
            guidePoints = base.guidePoints.map { guide ->
                if (guide.index == 1) guide.copy(
                    point = RoutePoint(37.00036, 127.0),
                    distanceFromStartM = 40,
                    remainingDistanceM = 100,
                ) else guide
            },
        )
        val jumpedNavigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        jumpedNavigator.setRoute(closeTurns)
        val beforeSecondTurn = TrustedLocation(37.000315, 127.0, 2f, 1_000L)
        val jitter = TrustedLocation(37.0003159, 127.0, 2f, 2_000L) // About 0.1m forward.
        val jump = TrustedLocation(37.000424, 127.0, 2f, 3_000L) // About 12m beyond the jitter.

        assertEquals(1, jumpedNavigator.update(beforeSecondTurn, 1_000L, false).guideIndex)
        assertEquals(1, jumpedNavigator.update(jitter, 2_000L, false).guideIndex)
        assertNotNull(jumpedNavigator.currentAcceptedRouteMatchFor(2_000L))
        val singleJump = jumpedNavigator.update(jump, 3_000L, false)
        assertTrue(jumpedNavigator.currentRouteMatch()?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM))
        assertEquals(1, singleJump.guideIndex)
        val duplicate = jumpedNavigator.update(jump, 3_000L, false)
        assertEquals(1, duplicate.guideIndex)

        val observedNavigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        observedNavigator.setRoute(closeTurns)
        val observed = listOf(
            TrustedLocation(37.000232, 127.0, 2f, 1_000L),
            TrustedLocation(37.000277, 127.0, 2f, 2_000L),
            TrustedLocation(37.000430, 127.0, 2f, 3_000L),
            TrustedLocation(37.000540, 127.0, 2f, 4_000L),
        )
        observed.forEachIndexed { index, sample ->
            val update = observedNavigator.update(sample, sample.elapsedRealtimeMs, false)
            assertEquals(if (index == observed.lastIndex) null else 1, update.guideIndex)
        }
    }

    @Test
    fun gpsWobbleDoesNotAccumulatePassedTurnMovement() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(twoImportantTurnRoute())
        val first = TrustedLocation(37.00063, 127.0, 5f, 1_000L)

        assertEquals(1, navigator.update(first, nowMs = 1_000L, requestInFlight = false).guideIndex)
        listOf(37.000666, 37.00063, 37.000666, 37.00063, 37.000666).forEachIndexed { index, latitude ->
            val nowMs = 2_000L + index * 1_000L
            val update = navigator.update(
                TrustedLocation(latitude, 127.0, 5f, nowMs), nowMs = nowMs, requestInFlight = false,
            )
            val match = requireNotNull(navigator.currentRouteMatch())
            assertTrue(match.quality == RouteMatchQuality.HIGH || match.quality == RouteMatchQuality.MEDIUM)
            assertEquals(1, update.guideIndex)
        }
    }

    @Test
    fun twoMeterStepsAccumulateAgainstTheFirstTurnAnchor() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(twoImportantTurnRoute())
        assertEquals(1, navigator.update(
            TrustedLocation(37.000232, 127.0, 2f, 1_000L), 1_000L, false,
        ).guideIndex)

        (1..25).forEach { step ->
            val atMs = 1_000L + step * 1_000L
            val update = navigator.update(
                TrustedLocation(37.000232 + step * 0.000018, 127.0, 2f, atMs), atMs, false,
            )
            if (step <= 3) assertEquals(1, update.guideIndex)
            if (step == 8) assertEquals(1, update.guideIndex)
            if (step == 20) assertEquals(null, update.guideIndex)
            if (step == 25) assertEquals(null, update.guideIndex)
        }
    }

    @Test
    fun sidewaysRawFixesCannotCorroborateFilteredRouteProgress() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(twoImportantTurnRoute())
        assertEquals(1, navigator.update(
            TrustedLocation(37.000232, 127.0, 5f, 1_000L), 1_000L, false,
        ).guideIndex)
        val betweenGuides = TrustedLocation(37.000315, 127.0, 5f, 2_000L)
        assertEquals(1, navigator.update(betweenGuides, 2_000L, false).guideIndex)
        assertNotNull(navigator.currentAcceptedRouteMatchFor(2_000L))

        listOf(37.00075, 37.00087, 37.00099).forEachIndexed { index, filteredLatitude ->
            val atMs = 3_000L + index * 1_000L
            val raw = TrustedLocation(37.000315, 127.0002 + index * 0.0002, 5f, atMs)
            val filtered = FilteredRoutePosition(
                RoutePoint(filteredLatitude, 127.0), 5.0, atMs,
                heading = RouteHeadingEstimate(0.0, standardDeviationDeg = 5.0),
            )
            val update = navigator.update(raw, atMs, false, filtered)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(atMs))
            assertEquals(1, update.guideIndex)
        }
    }

    @Test
    fun hugeRawAccuracyWithZeroRawFilteredGapCannotAccumulateTurnEvidence() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(twoImportantTurnRoute())
        assertEquals(1, navigator.update(
            TrustedLocation(37.000232, 127.0, 2f, 1_000L), 1_000L, false,
        ).guideIndex)

        listOf(37.000315, 37.00055, 37.00065).forEachIndexed { index, latitude ->
            val atMs = 2_000L + index * 1_000L
            val raw = TrustedLocation(latitude, 127.0, 70f, atMs)
            val filtered = FilteredRoutePosition(
                RoutePoint(latitude, 127.0), 2.0, atMs,
                heading = RouteHeadingEstimate(0.0, standardDeviationDeg = 5.0),
            )
            val update = navigator.update(raw, atMs, false, filtered)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(atMs))
            assertEquals(1, update.guideIndex)
        }
    }

    @Test
    fun sameCoordinatesAndOneLongGapJumpCannotReleaseSecondTurn() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(twoImportantTurnRoute())

        assertEquals(1, navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 1_000L), nowMs = 1_000L, requestInFlight = false,
        ).guideIndex)
        assertEquals(1, navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 12_000L), nowMs = 12_000L, requestInFlight = false,
        ).guideIndex)
        val afterGap = navigator.update(
            TrustedLocation(37.00081, 127.0, 5f, 23_000L), nowMs = 23_000L, requestInFlight = false,
        )
        assertEquals(1, afterGap.guideIndex)
        val standstill = navigator.update(
            TrustedLocation(37.00081, 127.0, 5f, 34_000L), nowMs = 34_000L, requestInFlight = false,
        )
        assertEquals(1, standstill.guideIndex)
    }

    @Test
    fun elevenSecondSpacedMeaningfulAdvancesReleaseSecondTurn() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(twoImportantTurnRoute())
        val samples = listOf(
            TrustedLocation(37.000232, 127.0, 2f, 1_000L),
            TrustedLocation(37.000315, 127.0, 2f, 12_000L),
            TrustedLocation(37.00055, 127.0, 2f, 23_000L),
            TrustedLocation(37.00065, 127.0, 2f, 34_000L),
        )

        samples.forEachIndexed { index, sample ->
            val update = navigator.update(sample, nowMs = sample.elapsedRealtimeMs, requestInFlight = false)
            val match = requireNotNull(navigator.currentRouteMatch())
            assertTrue(match.quality == RouteMatchQuality.HIGH || match.quality == RouteMatchQuality.MEDIUM)
            assertEquals(if (index >= 3) null else 1, update.guideIndex)
        }
    }

    @Test
    fun matchedLoopCanReleaseSecondTurnNearFirstTurnPosition() {
        assertMatchedLoopReleasesSecondTurn(distanceSource = null)
    }

    @Test
    fun normalizedDistanceSourcesPreserveRepeatedVisitGuideOrder() {
        WalkingRouteDistanceSource.entries.forEach(::assertMatchedLoopReleasesSecondTurn)
    }

    private fun assertMatchedLoopReleasesSecondTurn(distanceSource: WalkingRouteDistanceSource?) {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val loop = WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 250, durationS = 300),
            polyline = listOf(
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0006, 127.0),
                RoutePoint(37.0006, 127.0007),
                RoutePoint(37.0001, 127.0007),
                RoutePoint(37.0001, 127.00006),
            ),
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.00005, 127.0), "첫 회전", 5, 245, 0f)
                    .copy(turnType = 12),
                WalkingRouteGuidePoint(1, RoutePoint(37.0001, 127.00025), "다음 회전", 230, 20, 270f)
                    .copy(turnType = 13),
            ),
        )
        navigator.setRoute(loop.copy(
            distanceSource = distanceSource,
            steps = if (distanceSource == null) loop.steps else listOf(
                WalkingRouteStep(0, 250, 300, loop.polyline, null, null, null, null),
            ),
        ))
        val samples = listOf(
            TrustedLocation(37.00012, 127.0, 2f, 1_000L) to 0.0,
            TrustedLocation(37.0006, 127.0, 2f, 2_000L) to 0.0,
            TrustedLocation(37.0006, 127.00035, 2f, 3_000L) to 90.0,
            TrustedLocation(37.0006, 127.0007, 2f, 4_000L) to 90.0,
            TrustedLocation(37.00035, 127.0007, 2f, 5_000L) to 180.0,
            TrustedLocation(37.0001, 127.0007, 2f, 6_000L) to 180.0,
            TrustedLocation(37.0001, 127.00055, 2f, 7_000L) to 270.0,
            TrustedLocation(37.0001, 127.00035, 2f, 8_000L) to 270.0,
            TrustedLocation(37.0001, 127.0002, 2f, 8_500L) to 270.0,
            TrustedLocation(37.0001, 127.00006, 2f, 9_000L) to 270.0,
        )
        val first = samples.first().first
        val last = samples.last().first
        assertTrue(kotlin.math.hypot(
            (last.latitude - first.latitude) * 111_000.0,
            (last.longitude - first.longitude) * 88_800.0,
        ) < 8.0)

        samples.forEachIndexed { index, (sample, headingDeg) ->
            val update = navigator.update(
                location = sample,
                nowMs = sample.elapsedRealtimeMs,
                requestInFlight = false,
                filteredPosition = filteredPosition(sample, headingDeg),
            )
            val match = requireNotNull(navigator.currentRouteMatch())
            assertTrue(match.quality == RouteMatchQuality.HIGH || match.quality == RouteMatchQuality.MEDIUM)
            mapOf(2 to 1, 4 to 2, 6 to 3, samples.lastIndex to 3)[index]?.let { segmentIndex ->
                assertEquals(segmentIndex, match.segmentIndex)
            }
            assertEquals(if (index == samples.lastIndex) null else 1, update.guideIndex)
        }
    }

    @Test
    fun acknowledgedTurnIsNotConsumedWhileStillStoppedBeforeTheGuide() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(
            route().copy(
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, RoutePoint(37.00050, 127.0), "첫 회전", 56, 50, 0f, turnType = 12),
                    WalkingRouteGuidePoint(1, RoutePoint(37.00081, 127.0), "다음 회전", 90, 10, 0f, turnType = 13),
                ),
            ),
        )
        val beforeGuide = TrustedLocation(37.00042, 127.0, 4f, 1_000L)
        val offered = navigator.update(beforeGuide, nowMs = 1_000L, requestInFlight = false,
            facingObservation = RouteFacingObservation(0.0, 5.0, 1_000L))
        navigator.acknowledgeInstruction(offered, spokenAtMs = 1_000L)

        val stillBefore = navigator.update(beforeGuide, nowMs = 1_001L, requestInFlight = false,
            facingObservation = RouteFacingObservation(0.0, 5.0, 1_001L))

        assertTrue(offered.instruction?.contains("왼쪽으로 꺾으세요") == true)
        assertEquals("guidance_already_completed", stillBefore.reason)
        val current = navigator.currentInstruction(beforeGuide)
        assertTrue(current?.contains("왼쪽으로 꺾으세요") == true)
        assertFalse(current?.contains("오른쪽으로 꺾으세요") == true)
        assertFalse(current?.contains("첫 회전") == true)
    }

    @Test
    fun guidanceCooldownStartsOnlyAfterCallerAcknowledgesSpeech() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 6_000L))
        navigator.setRoute(route(guideInstruction = "직진하세요."))

        val first = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)
        val stillOffered = navigator.update(locationNearStart(), nowMs = 1_100L, requestInFlight = false)
        navigator.acknowledgeInstruction(stillOffered, spokenAtMs = 1_100L)
        val rateLimited = navigator.update(locationNearStart(), nowMs = 1_200L, requestInFlight = false)

        assertEquals("route_guidance", first.reason)
        assertEquals("route_guidance", stillOffered.reason)
        assertEquals("guidance_rate_limited", rateLimited.reason)
        assertEquals(null, rateLimited.instruction)
    }

    @Test
    fun routeReplacementResetsPendingUserDecision() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route())
        val offRoute = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        navigator.setRoute(route())
        val afterReplacement = navigator.update(locationNearStart(), nowMs = 2_000L, requestInFlight = false)

        assertTrue(offRoute.userDecisionRequired)
        assertFalse(afterReplacement.shouldReroute)
        assertFalse(afterReplacement.userDecisionRequired)
        assertEquals("route_guidance", afterReplacement.reason)
    }

    @Test
    fun remainingDistanceUsesStoredRouteSummaryAndProgressInsteadOfStraightLineFallback() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val longSummaryRoute = route().copy(
            summary = WalkingRouteSummary(distanceM = 1_000, durationS = 900),
            guidePoints = emptyList(),
        )
        navigator.setRoute(longSummaryRoute)
        val midpoint = TrustedLocation(37.00045, 127.0, 3f, 1_000L)

        val beforeProgress = navigator.currentInstruction(midpoint)
        val update = navigator.update(midpoint, nowMs = 1_000L, requestInFlight = false, facingObservation = RouteFacingObservation(0.0, 5.0, 1_000L))

        assertTrue(beforeProgress?.contains("남은 거리를 확인 중") == true)
        assertTrue(update.instruction?.contains("500m") == true)
        assertFalse(update.instruction?.contains("50m") == true)
    }

    @Test
    fun currentBearingUsesNearestPolylineSegmentAfterLocationUpdate() {
        val navigator = RouteNavigator()
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 180, durationS = 120),
                polyline = listOf(
                    RoutePoint(37.0, 127.0),
                    RoutePoint(37.0, 127.001),
                    RoutePoint(37.001, 127.001),
                ),
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, RoutePoint(37.001, 127.001), "북쪽 회전", 180, 0, 0f),
                ),
            ),
        )

        navigator.update(
            TrustedLocation(37.0, 127.0004, 5f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )

        assertEquals(90f, navigator.currentBearingDeg()!!, 1f)
    }

    @Test
    fun targetDirectionComesFromTheStoredRouteAndNotFromStepProgress() {
        fun bearingWith(stepProgressM: Double?): Float {
            val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
            navigator.setRoute(route(bearingDeg = 42.5f))
            navigator.update(
                location = locationNearStart(),
                nowMs = 1_000L,
                requestInFlight = false,
                stepProgressM = stepProgressM,
            )
            return navigator.currentBearingDeg()!!
        }

        // 저장된 경로 폴리라인이 정북이므로 투영 방위는 0도다. 가이드 포인트의 42.5도는
        // 투영 전에만 쓰이는 대체값이고, 보폭 진행량은 어느 쪽에도 관여하지 않는다.
        assertEquals(0f, bearingWith(null), 0.001f)
        assertEquals(bearingWith(null), bearingWith(99_999.0), 0.001f)
    }

    @Test
    fun overlappingReturnLegsDoNotChooseTravelPhaseFromCourseAlone() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val crossingRoute = WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 444, durationS = 300),
            polyline = listOf(
                RoutePoint(37.0, 126.999),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.001, 127.0),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0, 127.001),
            ),
            guidePoints = emptyList(),
        )
        navigator.setRoute(crossingRoute)
        val north = TrustedLocation(37.001, 127.0, 5f, 1_000L)
        repeat(3) { index -> navigator.update(north, nowMs = 1_000L + index, requestInFlight = false) }
        navigator.update(TrustedLocation(37.0005, 127.0, 5f, 2_000L), nowMs = 2_000L, requestInFlight = false)
        navigator.update(TrustedLocation(37.0, 127.0, 5f, 3_000L), nowMs = 3_000L, requestInFlight = false)

        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertEquals(null, navigator.currentBearingDeg())
        assertEquals(null, navigator.currentAcceptedRouteMatchFor(3_000L))
    }

    @Test
    fun nearDestinationBeforeLoopEndDoesNotArrive() {
        val destination = RoutePoint(37.0, 127.00004)
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 350, durationS = 300),
                polyline = listOf(
                    RoutePoint(37.0, 127.0),
                    RoutePoint(37.002, 127.0),
                    destination,
                ),
                guidePoints = emptyList(),
            ),
            destination = destination,
        )

        val first = navigator.update(TrustedLocation(destination.latitude, destination.longitude, 2f, 1_000L), 1_000L, false)
        val second = navigator.update(TrustedLocation(destination.latitude, destination.longitude, 2f, 2_000L), 2_000L, false)

        assertFalse(first.arrived)
        assertFalse(second.arrived)
    }

    @Test
    fun snappedTmapEndpointDoesNotClaimRequestedPoiArrival() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val route = route()
        val requested = RoutePoint(37.00162, 127.0)
        navigator.setRoute(route, destination = requested)
        val endpoint = route.polyline.last()

        val first = navigator.update(TrustedLocation(endpoint.latitude, endpoint.longitude, 2f, 1_000L), 1_000L, false)
        navigator.acknowledgeInstruction(first, spokenAtMs = 1_000L)
        val second = navigator.update(TrustedLocation(endpoint.latitude, endpoint.longitude, 2f, 2_000L), 2_000L, false)

        assertFalse(first.arrived)
        assertFalse(second.arrived)
        assertEquals("guidance_already_completed", second.reason)
        assertTrue(navigator.currentInstruction(TrustedLocation(endpoint.latitude, endpoint.longitude, 2f, 2_000L))?.contains("최종 접근") == true)
    }

    @Test
    fun crossingRouteDoesNotConsumeAConfirmedButLowConfidenceBranch() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val crossingRoute = WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 400, durationS = 300),
            polyline = listOf(
                RoutePoint(37.0, 126.999),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.001, 127.0),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0, 127.001),
            ),
            guidePoints = emptyList(),
        )
        navigator.setRoute(crossingRoute)
        val west = TrustedLocation(37.0, 126.9995, 3f, 1_000L)
        navigator.update(
            location = west,
            nowMs = 1_000L,
            requestInFlight = false,
            filteredPosition = filteredPosition(west, headingDeg = 90.0),
        )
        val crossing = TrustedLocation(37.0, 127.0, 3f, 2_000L)

        val pendingUpdate = navigator.update(
            location = crossing,
            nowMs = 2_000L,
            requestInFlight = false,
            filteredPosition = filteredPosition(crossing, headingDeg = 180.0),
        )
        val pending = requireNotNull(navigator.currentRouteMatch())
        navigator.update(
            location = crossing.copy(elapsedRealtimeMs = 3_000L),
            nowMs = 3_000L,
            requestInFlight = false,
            filteredPosition = filteredPosition(crossing.copy(elapsedRealtimeMs = 3_000L), headingDeg = 180.0),
        )
        val confirmed = requireNotNull(navigator.currentRouteMatch())

        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, pending.reason)
        assertEquals(RouteMatchQuality.LOW, pending.quality)
        assertEquals(null, pendingUpdate.instruction)
        assertEquals("route_match_untrusted", pendingUpdate.reason)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, confirmed.reason)
        assertEquals(RouteMatchQuality.LOW, confirmed.quality)
        assertEquals(null, navigator.currentBearingDeg())
    }

    @Test
    fun ambiguousLoopObservationCannotAdvanceAnImportantTurn() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 400, durationS = 300),
            polyline = listOf(
                RoutePoint(37.0, 126.999),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.001, 127.0),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0, 127.001),
            ),
            guidePoints = listOf(
                // Guide 0 sits at its declared 20 m shape position (07.txt #4: turn timing no
                // longer uses a declared distance 46 m away from the guide's own coordinate).
                WalkingRouteGuidePoint(0, RoutePoint(37.0, 126.999225), "첫 회전", 20, 380, 90f)
                    .copy(turnType = 12),
                WalkingRouteGuidePoint(1, RoutePoint(37.0, 127.0), "다음 회전", 80, 320, 90f)
                    .copy(turnType = 13),
            ),
        ))
        val west = TrustedLocation(37.0, 126.9995, 3f, 1_000L)
        assertEquals(1, navigator.update(west, 1_000L, false, filteredPosition(west, 90.0)).guideIndex)
        val crossing = TrustedLocation(37.0, 127.0, 3f, 2_000L)
        val ambiguous = navigator.update(crossing, 2_000L, false, filteredPosition(crossing, 180.0))
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, navigator.currentRouteMatch()?.reason)
        assertEquals("route_match_untrusted", ambiguous.reason)
        assertEquals(null, navigator.currentAcceptedRouteMatchFor(2_000L))
        val westAgain = west.copy(elapsedRealtimeMs = 3_000L)
        assertEquals(1, navigator.update(westAgain, 3_000L, false, filteredPosition(westAgain, 90.0)).guideIndex)
    }

    @Test
    fun ambiguousParallelRouteDoesNotUseTheLowConfidenceSnapForGuidance() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 190, durationS = 180),
                polyline = listOf(
                    RoutePoint(37.0, 127.0),
                    RoutePoint(37.0, 127.001),
                    RoutePoint(37.00005, 127.001),
                    RoutePoint(37.00005, 127.0),
                ),
                guidePoints = emptyList(),
            ),
        )
        val midpoint = TrustedLocation(37.000025, 127.0005, 5f, 1_000L)

        val update = navigator.update(midpoint, nowMs = 1_000L, requestInFlight = false)
        val match = requireNotNull(navigator.currentRouteMatch())

        assertFalse(update.offRoute)
        assertEquals(null, update.instruction)
        assertEquals("route_match_untrusted", update.reason)
        assertEquals(RouteMatchQuality.LOW, match.quality)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, match.reason)
        assertEquals(null, navigator.currentBearingDeg())
    }

    @Test
    fun routeSnapAloneCannotCreateArrivalWithoutUnsnappedDistanceEvidence() {
        val navigator = RouteNavigator(
            RouteNavigatorConfig(arrivalConfirmSamples = 1, guidanceIntervalMs = 0),
        )
        val route = route().copy(guidePoints = emptyList())
        val endpoint = route.polyline.last()
        navigator.setRoute(route, destination = endpoint)
        val unsnapped = TrustedLocation(endpoint.latitude + 0.000072, endpoint.longitude, 10f, 1_000L)

        val update = navigator.update(unsnapped, nowMs = 1_000L, requestInFlight = false)
        val match = requireNotNull(navigator.currentRouteMatch())

        assertTrue(match.quality == RouteMatchQuality.HIGH || match.quality == RouteMatchQuality.MEDIUM)
        assertEquals(endpoint.latitude, requireNotNull(match.matchedPoint).latitude, 0.000001)
        assertFalse(update.arrivalCandidate)
        assertFalse(update.arrived)
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun duplicateAndOutOfOrderNowMsDoNotAdvanceArrivalConfirmation() {
        val navigator = RouteNavigator(RouteNavigatorConfig(arrivalConfirmSamples = 2, guidanceIntervalMs = 0))
        navigator.setRoute(route())
        val nearEnd = TrustedLocation(37.0009, 127.0, 5f, 1_000L)

        val first = navigator.update(nearEnd, nowMs = 1_000L, requestInFlight = false)
        val duplicate = navigator.update(nearEnd, nowMs = 1_000L, requestInFlight = false)
        val outOfOrder = navigator.update(nearEnd, nowMs = 999L, requestInFlight = false)
        val secondFresh = navigator.update(nearEnd.copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false)

        assertFalse(first.arrivalCandidate)
        assertFalse(duplicate.cancelStaleNavigationSpeech)
        assertEquals("location_sample_not_newer", outOfOrder.reason)
        assertFalse(duplicate.arrivalCandidate)
        assertFalse(outOfOrder.arrivalCandidate)
        assertFalse(secondFresh.arrivalCandidate)
        // S07-32 (R6): the evidence restarted at 2 s, so 3 s of it is reached at 5 s, not at 4 s.
        for (at in listOf(3_000L, 4_000L)) {
            assertFalse(navigator.update(nearEnd.copy(elapsedRealtimeMs = at), nowMs = at, requestInFlight = false).arrivalCandidate)
        }
        val thirdFresh = navigator.update(nearEnd.copy(elapsedRealtimeMs = 5_000L), nowMs = 5_000L, requestInFlight = false)
        assertTrue(thirdFresh.arrivalCandidate)
    }

    @Test
    fun duplicateAndOutOfOrderNowMsDoNotAdvanceOffRouteConfirmation() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 2))
        navigator.setRoute(route())
        val offRoute = offRouteLocation()

        val first = navigator.update(offRoute, nowMs = 1_000L, requestInFlight = false)
        val duplicate = navigator.update(offRoute, nowMs = 1_000L, requestInFlight = false)
        val outOfOrder = navigator.update(offRoute, nowMs = 999L, requestInFlight = false)
        val secondFresh = navigator.update(offRoute.copy(elapsedRealtimeMs = 2_000L), nowMs = 2_000L, requestInFlight = false)

        assertEquals("off_route_pending", first.reason)
        assertEquals("location_sample_duplicate", duplicate.reason)
        assertFalse(duplicate.cancelStaleNavigationSpeech)
        assertEquals("location_sample_not_newer", outOfOrder.reason)
        assertFalse(duplicate.offRoute)
        assertFalse(outOfOrder.offRoute)
        assertFalse(secondFresh.offRoute)
        val thirdFresh = navigator.update(offRoute.copy(elapsedRealtimeMs = 3_000L), nowMs = 3_000L, requestInFlight = false)
        assertTrue(thirdFresh.offRoute)
        assertEquals("off_route_user_decision_required", thirdFresh.reason)
    }

    @Test
    fun positioningInterruptionBreaksArrivalConfirmationEvidence() {
        val navigator = RouteNavigator(RouteNavigatorConfig(arrivalConfirmSamples = 2, guidanceIntervalMs = 0))
        navigator.setRoute(route())
        val nearEnd = TrustedLocation(37.0009, 127.0, 5f, 1_000L)

        val beforeInterruption = navigator.update(
            nearEnd,
            nowMs = 1_000L,
            requestInFlight = false,
        )
        navigator.onPositioningEvidenceInterrupted()
        val firstRecovered = navigator.update(
            nearEnd.copy(elapsedRealtimeMs = 2_000L),
            nowMs = 2_000L,
            requestInFlight = false,
        )
        // S07-32 (R6): the evidence restarted at 2 s, so 3 s of it is reached at 5 s, not at 4 s.
        for (at in listOf(3_000L, 4_000L)) {
            assertFalse(navigator.update(nearEnd.copy(elapsedRealtimeMs = at), nowMs = at, requestInFlight = false).arrivalCandidate)
        }
        val secondRecovered = navigator.update(
            nearEnd.copy(elapsedRealtimeMs = 5_000L),
            nowMs = 5_000L,
            requestInFlight = false,
        )

        assertFalse(beforeInterruption.arrivalCandidate)
        assertFalse(firstRecovered.arrivalCandidate)
        assertFalse(firstRecovered.arrived)
        assertTrue(navigator.hasRoute())
        assertTrue(secondRecovered.arrivalCandidate)
    }

    @Test
    fun clockSectorChangesOnlyAfterTwoDistinctConsecutiveFreshObservations() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        fun facing(degrees: Double, observedAtMs: Long) = RouteFacingObservation(degrees, 0.0, observedAtMs)
        fun guidance(nowMs: Long, degrees: Double, observedAtMs: Long = nowMs) =
            navigator.currentGuidance(nowMs, facing(degrees, observedAtMs))?.instruction
        fun hasClock(instruction: String?) = instruction?.contains("시 방향") == true

        // S08-5: with no spoken sector yet, the first fresh sample is spoken; only a change waits.
        val first = navigator.update(locationNearStart(), 1_000L, false, facingObservation = facing(355.0, 1_000L))
        assertTrue(first.instruction?.contains("12시 방향") == true)
        assertTrue(guidance(1_001L, 355.0, 1_000L)?.contains("12시 방향") == true)
        assertTrue(guidance(1_002L, 355.0)?.contains("12시 방향") == true)
        assertFalse(hasClock(guidance(1_003L, 335.0)))
        assertTrue(guidance(1_004L, 355.0)?.contains("12시 방향") == true)
        assertFalse(hasClock(guidance(1_005L, 335.0)))
        // Repeating the same sensor sample is not a second fresh observation of the new sector.
        assertFalse(hasClock(guidance(1_006L, 335.0, 1_005L)))
        assertTrue(guidance(1_007L, 335.0)?.contains("1시 방향") == true)

        val independent = RouteNavigator().apply { setRoute(route()) }
        assertTrue(independent.update(locationNearStart(), 1_000L, false,
            facingObservation = facing(335.0, 1_000L)).instruction?.contains("1시 방향") == true)
    }

    @Test
    fun invalidFacingAndRouteReplacementClearConfirmedClockSector() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        fun facing(observedAtMs: Long, degrees: Double = 355.0) = RouteFacingObservation(degrees, 0.0, observedAtMs)
        fun hasClock(instruction: String?) = instruction?.contains("시 방향") == true

        navigator.update(locationNearStart(), 1_000L, false, facingObservation = facing(1_000L))
        val confirmed = requireNotNull(navigator.currentGuidance(1_001L, facing(1_001L)))
        assertTrue(confirmed.instruction?.contains("12시 방향") == true)
        assertFalse(navigator.isInstructionCurrent(confirmed, 1_002L, null))
        assertFalse(hasClock(navigator.currentGuidance(1_002L, null)?.instruction))
        // S08-5: the invalid sample cleared 12 o'clock, so a different fresh sector is spoken at once
        // instead of waiting as a change from a sector that is no longer confirmed.
        assertTrue(navigator.currentGuidance(1_003L, facing(1_003L, 335.0))?.instruction?.contains("1시 방향") == true)
        assertTrue(navigator.currentGuidance(1_004L, facing(1_004L, 335.0))?.instruction?.contains("1시 방향") == true)

        navigator.setRoute(route())
        val newRoute = navigator.update(locationNearStart().copy(elapsedRealtimeMs = 2_000L), 2_000L, false,
            facingObservation = facing(2_000L))
        // Route replacement cleared 1 o'clock as well: 12 o'clock is spoken with its first fresh sample.
        assertTrue(newRoute.instruction?.contains("12시 방향") == true)
        assertTrue(navigator.currentGuidance(2_001L, facing(2_001L))?.instruction?.contains("12시 방향") == true)
    }

    private fun twoImportantTurnRoute(): WalkingRoute {
        val base = route()
        val guide = base.guidePoints.single()
        return base.copy(
            summary = WalkingRouteSummary(distanceM = 140, durationS = 140),
            polyline = listOf(
                RoutePoint(37.0, 127.0),
                RoutePoint(37.00036, 127.0),
                RoutePoint(37.00072, 127.0),
                RoutePoint(37.00126, 127.0),
            ),
            guidePoints = listOf(
                guide.copy(
                    index = 0,
                    point = RoutePoint(37.00018, 127.0),
                    instruction = "왼쪽으로 이동하세요.",
                    turnType = 12,
                    distanceFromStartM = 20,
                    remainingDistanceM = 120,
                ),
                guide.copy(
                    index = 1,
                    point = RoutePoint(37.00045, 127.0),
                    instruction = "오른쪽으로 이동하세요.",
                    turnType = 13,
                    distanceFromStartM = 50,
                    remainingDistanceM = 90,
                ),
            ),
        )
    }

    private fun closeImportantTurnRoute(): WalkingRoute {
        val base = twoImportantTurnRoute()
        return base.copy(
            guidePoints = base.guidePoints.map { guide ->
                if (guide.index == 1) guide.copy(
                    point = RoutePoint(37.000261, 127.0),
                    distanceFromStartM = 29,
                    remainingDistanceM = 111,
                ) else guide
            },
        )
    }

    private fun scaledSummaryTwoImportantTurnRoute(): WalkingRoute {
        val base = twoImportantTurnRoute()
        return base.copy(
            summary = WalkingRouteSummary(distanceM = 200, durationS = 200),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0009, 127.0)),
            guidePoints = base.guidePoints.map { guide ->
                if (guide.index == 0) guide.copy(
                    point = RoutePoint(37.00018, 127.0),
                    distanceFromStartM = 40,
                    remainingDistanceM = 160,
                ) else guide.copy(
                    point = RoutePoint(37.00054, 127.0),
                    distanceFromStartM = null,
                    remainingDistanceM = null,
                )
            },
        )
    }

    private fun route(guideInstruction: String? = "직진하세요.", bearingDeg: Float? = null): WalkingRoute {
        return WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 100, durationS = 90),
            polyline = listOf(
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0009, 127.0),
            ),
            guidePoints = listOf(
                WalkingRouteGuidePoint(
                    index = 0,
                    point = RoutePoint(37.00045, 127.0),
                    instruction = guideInstruction,
                    distanceFromStartM = 50,
                    remainingDistanceM = 50,
                    bearingDeg = bearingDeg,
                ),
            ),
        )
    }

    private fun locationNearStart(): TrustedLocation {
        return TrustedLocation(37.0, 127.0, 5f, 1_000L)
    }

    private fun offRouteLocation(): TrustedLocation {
        return TrustedLocation(37.0, 127.01, 5f, 1_000L)
    }

    private fun uncertainOffRouteLocation(): TrustedLocation {
        return slightlyOffRouteLocation(accuracyM = 10f)
    }

    private fun slightlyOffRouteLocation(accuracyM: Float): TrustedLocation {
        return TrustedLocation(37.0, 127.00045, accuracyM, 1_000L)
    }

    private fun filteredPosition(location: TrustedLocation, headingDeg: Double): FilteredRoutePosition {
        return FilteredRoutePosition(
            point = RoutePoint(location.latitude, location.longitude),
            horizontalAccuracyM = location.accuracyM.toDouble(),
            elapsedRealtimeMs = location.elapsedRealtimeMs,
            heading = RouteHeadingEstimate(headingDeg, standardDeviationDeg = 5.0),
        )
    }
}
