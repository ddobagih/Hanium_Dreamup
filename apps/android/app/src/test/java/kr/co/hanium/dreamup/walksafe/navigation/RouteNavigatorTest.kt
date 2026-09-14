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
    fun remainingDistanceRequiresTrustedProgressAndClearsOnLocationLoss() {
        val navigator = RouteNavigator()
        assertEquals(null, navigator.remainingDistanceM())
        navigator.setRoute(route())
        assertEquals(null, navigator.remainingDistanceM())
        navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)
        val distance = navigator.remainingDistanceM()
        assertNotNull(distance)
        assertTrue(distance!! >= 0.0 && distance <= route().summary.distanceM)
        navigator.onUntrustedLocation()
        assertEquals(null, navigator.remainingDistanceM())
    }

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
    fun exposesCurrentInstructionForAnExplicitVoiceQuery() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val instruction = navigator.currentInstruction(locationNearStart())

        assertTrue(instruction?.contains("오른쪽으로 이동하세요.") == true)
    }

    @Test
    fun tmapCrosswalkTurnTypesReplaceRawCommandsWithTheFixedReferenceNotice() {
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
            val update = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)

            assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, current)
            assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, update.instruction)
            assertFalse(current?.contains(rawCommand) == true)
            assertFalse(current?.contains("지금 건너세요") == true)
        }
    }

    @Test
    fun crosswalkTextMarkersReplaceRawCommandsWithTheFixedReferenceNotice() {
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

            assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, output)
            assertFalse(output?.contains("지금 건너세요") == true)
            assertFalse(output?.contains("건너도 됩니다") == true)
            assertFalse(output?.contains("안전합니다") == true)
            assertFalse(output?.contains("cross now", ignoreCase = true) == true)
        }
    }

    @Test
    fun nonCrosswalkInstructionKeepsExistingRouteGuidance() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val output = navigator.currentInstruction(locationNearStart())

        assertTrue(output?.contains("오른쪽으로 이동하세요.") == true)
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
        val confirmed = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)
        val repeated = navigator.update(offRouteLocation(), nowMs = 3_000L, requestInFlight = false)

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
        val waiting = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = true)

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
        val second = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)

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
        val afterUntrusted = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)

        assertEquals("off_route_pending", first.reason)
        assertEquals("off_route_pending", afterUntrusted.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, afterUntrusted.pendingUserDecision)
        assertFalse(afterUntrusted.offRoute)
    }

    @Test
    fun untrustedLocationRequiresExplicitRecheckBeforeFreshGpsCanResumeGuidance() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "직진하세요."))

        val untrusted = navigator.onUntrustedLocation()
        val recoverySignalOnly = navigator.update(locationNearStart(), nowMs = 2_000L, requestInFlight = false)
        val blocked = navigator.currentInstruction(locationNearStart())
        navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)
        val freshAfterRecheck = navigator.update(locationNearStart(), nowMs = 3_000L, requestInFlight = false)

        assertEquals("location_untrusted_recheck_required", untrusted?.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, untrusted?.pendingUserDecision)
        assertTrue(untrusted?.instruction?.contains("현재 위치 정확도를 신뢰할 수 없어") == true)
        assertTrue(untrusted?.instruction?.contains("위치 다시 확인을 선택할 때까지") == true)
        assertEquals("off_route_location_recheck_required", recoverySignalOnly.reason)
        assertEquals(null, blocked)
        assertEquals("route_guidance", freshAfterRecheck.reason)
        assertTrue(freshAfterRecheck.instruction?.contains("직진하세요") == true)
    }

    @Test
    fun excessiveSampleGapBreaksOffRouteConfirmationEvidence() {
        val navigator = RouteNavigator(RouteNavigatorConfig(maximumOffRouteSampleGapMs = 5_000L))
        navigator.setRoute(route())

        val first = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val afterGap = navigator.update(offRouteLocation(), nowMs = 6_001L, requestInFlight = false)

        assertEquals("off_route_pending", first.reason)
        assertEquals("off_route_pending", afterGap.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, afterGap.pendingUserDecision)
        assertFalse(afterGap.offRoute)
    }

    @Test
    fun suspectedDeviationResumesOnlyAfterExplicitRecheckAndFreshOnRouteGps() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "직진하세요."))
        val suspected = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)

        val recoverySignalOnly = navigator.update(locationNearStart(), nowMs = 2_000L, requestInFlight = false)
        val blockedInstruction = navigator.currentInstruction(locationNearStart())
        val recheck = navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)
        val freshOnRoute = navigator.update(locationNearStart(), nowMs = 3_000L, requestInFlight = false)

        assertEquals("off_route_pending", suspected.reason)
        assertEquals("off_route_location_recheck_required", recoverySignalOnly.reason)
        assertEquals(null, blockedInstruction)
        assertEquals("off_route_location_recheck_requested", recheck.reason)
        assertEquals("route_guidance", freshOnRoute.reason)
        assertTrue(freshOnRoute.instruction?.contains("직진하세요") == true)
    }

    @Test
    fun ignoresOffRouteWhenAccuracyStillOverlapsRoute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(uncertainOffRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val second = navigator.update(uncertainOffRouteLocation(), nowMs = 2_000L, requestInFlight = false)
        val preciseFirst = navigator.update(slightlyOffRouteLocation(accuracyM = 3f), nowMs = 8_000L, requestInFlight = false)

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
        val second = navigator.update(
            TrustedLocation(37.0009, 127.0, 5f, 2_000L),
            nowMs = 2_000L,
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

        navigator.acknowledgeInstruction(second, spokenAtMs = 2_000L)

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
        navigator.update(nearEnd, nowMs = 1_000L, requestInFlight = false)
        val candidate = navigator.update(nearEnd, nowMs = 2_000L, requestInFlight = false)

        val rejected = navigator.rejectArrival()
        val firstAfterRejection = navigator.update(nearEnd, nowMs = 3_000L, requestInFlight = false)

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

        val inconsistent = navigator.update(
            location = nearEnd,
            nowMs = 1_000L,
            requestInFlight = false,
            stepProgressM = 0.0,
        )
        val corroborated = navigator.update(
            location = nearEnd,
            nowMs = 2_000L,
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
        navigator.update(nearEnd, nowMs = 1_000L, requestInFlight = false)
        val firstToken = navigator.pendingDecisionToken()

        navigator.setRoute(route())
        navigator.update(nearEnd, nowMs = 2_000L, requestInFlight = false)
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
        navigator.update(nearEnd, nowMs = 1_000L, requestInFlight = false)
        val staleToken = requireNotNull(navigator.pendingDecisionToken())

        navigator.setRoute(route())
        navigator.update(nearEnd, nowMs = 2_000L, requestInFlight = false)

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
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val base = route().copy(
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.00045, 127.0), "지난 회전", 50, 50, 0f),
                WalkingRouteGuidePoint(1, RoutePoint(37.00081, 127.0), "다음 회전", 90, 10, 0f),
            ),
        )
        navigator.setRoute(base)

        val unacknowledged = navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )
        val repeated = navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 1_001L),
            nowMs = 1_001L,
            requestInFlight = false,
        )
        navigator.acknowledgeInstruction(repeated, spokenAtMs = 1_001L)
        val afterSpeech = navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 1_002L),
            nowMs = 1_002L,
            requestInFlight = false,
        )

        assertTrue(unacknowledged.instruction?.contains("다음 회전") == true)
        assertTrue(repeated.instruction?.contains("다음 회전") == true)
        assertEquals("guidance_already_completed", afterSpeech.reason)
    }

    @Test
    fun acknowledgedTurnIsNotConsumedWhileStillStoppedBeforeTheGuide() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(
            route().copy(
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, RoutePoint(37.00050, 127.0), "첫 회전", 56, 50, 0f),
                    WalkingRouteGuidePoint(1, RoutePoint(37.00081, 127.0), "다음 회전", 90, 10, 0f),
                ),
            ),
        )
        val beforeGuide = TrustedLocation(37.00042, 127.0, 4f, 1_000L)
        val offered = navigator.update(beforeGuide, nowMs = 1_000L, requestInFlight = false)
        navigator.acknowledgeInstruction(offered, spokenAtMs = 1_000L)

        val stillBefore = navigator.update(beforeGuide, nowMs = 1_001L, requestInFlight = false)

        assertEquals("guidance_already_completed", stillBefore.reason)
        assertTrue(navigator.currentInstruction(beforeGuide)?.contains("첫 회전") == true)
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
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val longSummaryRoute = route().copy(
            summary = WalkingRouteSummary(distanceM = 1_000, durationS = 900),
            guidePoints = emptyList(),
        )
        navigator.setRoute(longSummaryRoute)
        val midpoint = TrustedLocation(37.00045, 127.0, 3f, 1_000L)

        val beforeProgress = navigator.currentInstruction(midpoint)
        val update = navigator.update(midpoint, nowMs = 1_000L, requestInFlight = false)

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
    fun crossingRouteProjectionStaysOnTheLaterProgressBranch() {
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

        assertEquals(180f, navigator.currentBearingDeg()!!, 2f)
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
            location = crossing,
            nowMs = 3_000L,
            requestInFlight = false,
            filteredPosition = filteredPosition(crossing, headingDeg = 180.0),
        )
        val confirmed = requireNotNull(navigator.currentRouteMatch())

        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, pending.reason)
        assertEquals(RouteMatchQuality.LOW, pending.quality)
        assertEquals(null, pendingUpdate.instruction)
        assertEquals("route_match_untrusted", pendingUpdate.reason)
        assertEquals(2, confirmed.segmentIndex)
        assertEquals(RouteMatchReason.LOW_CONFIDENCE, confirmed.reason)
        assertEquals(RouteMatchQuality.LOW, confirmed.quality)
        assertEquals(null, navigator.currentBearingDeg())
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
        val secondFresh = navigator.update(nearEnd, nowMs = 2_000L, requestInFlight = false)

        assertFalse(first.arrivalCandidate)
        assertEquals("location_sample_not_newer", duplicate.reason)
        assertEquals("location_sample_not_newer", outOfOrder.reason)
        assertFalse(duplicate.arrivalCandidate)
        assertFalse(outOfOrder.arrivalCandidate)
        assertTrue(secondFresh.arrivalCandidate)
    }

    @Test
    fun duplicateAndOutOfOrderNowMsDoNotAdvanceOffRouteConfirmation() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 2))
        navigator.setRoute(route())
        val offRoute = offRouteLocation()

        val first = navigator.update(offRoute, nowMs = 1_000L, requestInFlight = false)
        val duplicate = navigator.update(offRoute, nowMs = 1_000L, requestInFlight = false)
        val outOfOrder = navigator.update(offRoute, nowMs = 999L, requestInFlight = false)
        val secondFresh = navigator.update(offRoute, nowMs = 2_000L, requestInFlight = false)

        assertEquals("off_route_pending", first.reason)
        assertEquals("location_sample_not_newer", duplicate.reason)
        assertEquals("location_sample_not_newer", outOfOrder.reason)
        assertFalse(duplicate.offRoute)
        assertFalse(outOfOrder.offRoute)
        assertTrue(secondFresh.offRoute)
        assertEquals("off_route_user_decision_required", secondFresh.reason)
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
            nearEnd,
            nowMs = 2_000L,
            requestInFlight = false,
        )
        val secondRecovered = navigator.update(
            nearEnd,
            nowMs = 3_000L,
            requestInFlight = false,
        )

        assertFalse(beforeInterruption.arrivalCandidate)
        assertFalse(firstRecovered.arrivalCandidate)
        assertFalse(firstRecovered.arrived)
        assertTrue(navigator.hasRoute())
        assertTrue(secondRecovered.arrivalCandidate)
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
