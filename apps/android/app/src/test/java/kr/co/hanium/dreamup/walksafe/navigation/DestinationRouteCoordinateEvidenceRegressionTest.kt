package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-38: retain provider provenance through installation and the first-route archive. */
class DestinationRouteCoordinateEvidenceRegressionTest {
    private val destination = RoutePoint(37.001, 127.0, "목적지")
    private val alternative = RoutePoint(37.0011, 127.0001, "공급자 후보")
    private val scope = RouteSnapshotScope(RouteSnapshotOwner("coordinate-test", "account:1"), "walk-coordinate")

    @Test
    fun installedEvidenceRequiresTheSameDestinationCoordinatesAndNameAndOwnsItsInputList() {
        val mutableCandidates = evidence().candidates.toMutableList()
        val input = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, mutableCandidates)
        val expected = input.copy(candidates = mutableCandidates.toList())
        val navigator = RouteNavigator()
        navigator.setRoute(route(), destination = destination, destinationCoordinateEvidence = input)

        assertEquals(expected, navigator.destinationCoordinateEvidenceFor(destination))
        assertNull(navigator.destinationCoordinateEvidenceFor(destination.copy(name = "다른 표시 이름")))
        assertNull(navigator.destinationCoordinateEvidenceFor(destination.copy(latitude = destination.latitude + 0.0001)))
        assertNull(navigator.destinationCoordinateEvidenceFor(destination.copy(longitude = destination.longitude + 0.0001)))
        mutableCandidates.clear()
        assertEquals(expected, navigator.destinationCoordinateEvidenceFor(destination))
        assertEquals(2, requireNotNull(navigator.destinationCoordinateEvidenceFor(destination)).candidates.size)
    }

    @Test
    fun installingAnotherRouteWithoutEvidenceAndClearingCannotReuseOldProviderFacts() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(), destination = destination, destinationCoordinateEvidence = evidence())
        assertEquals(evidence(), navigator.destinationCoordinateEvidenceFor(destination))

        navigator.setRoute(route(providerRouteId = "replacement"), destination = destination)
        assertTrue(navigator.hasRoute())
        assertNull(navigator.destinationCoordinateEvidenceFor(destination))

        val replacementEvidence = DestinationCoordinateEvidence(DestinationCoordinateKind.FRONT,
            listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, alternative)))
        navigator.setRoute(route(alternative, "other-destination"), destination = alternative,
            destinationCoordinateEvidence = replacementEvidence)
        assertEquals(replacementEvidence, navigator.destinationCoordinateEvidenceFor(alternative))
        assertNull(navigator.destinationCoordinateEvidenceFor(destination))
        navigator.clear()
        assertFalse(navigator.hasRoute())
        assertNull(navigator.destinationCoordinateEvidenceFor(alternative))
        assertNull(navigator.destinationCoordinateEvidenceFor(destination))
    }

    @Test
    fun aFrontCoordinateAtTheUserDoesNotChangeArrivalDecisionsOrCertifyAnEntrance() {
        val target = eastPoint(200.0)
        val providerEvidence = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, listOf(
            DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, target),
            DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, eastPoint(190.0)),
        ))
        val walkingRoute = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
            listOf(eastPoint(0.0), target), emptyList())
        val config = RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L)
        val legacy = RouteNavigator(config).apply { setRoute(walkingRoute, destination = target) }
        val withEvidence = RouteNavigator(config).apply {
            setRoute(walkingRoute, destination = target, destinationCoordinateEvidence = providerEvidence)
        }
        for (navigator in listOf(legacy, withEvidence)) {
            listOf(0.0, 30.0, 60.0, 90.0, 120.0, 150.0, 180.0).forEachIndexed { index, east ->
                sample(navigator, east, 1_000L + index * 10_000L, 3f)
            }
        }
        val before = sample(legacy, 190.0, 71_000L, 10f)
        val after = sample(withEvidence, 190.0, 71_000L, 10f)

        assertEquals(before.reason, after.reason)
        val caution = " 실제 출입구와 다를 수 있습니다. 출입구까지의 통로는 확인되지 않았습니다. " +
            "목적지 근처일 수 있습니다. 출입구와 최종 접근을 실제로 확인해 주세요."
        assertEquals("경로 종점 부근입니다. 목적지 위치는" + caution, before.instruction)
        assertEquals("경로 종점 부근입니다. 지도에서 받은 목적지 위치는" + caution, after.instruction)
        assertEquals(before.arrivalCandidate, after.arrivalCandidate)
        assertEquals(before.pendingUserDecision, after.pendingUserDecision)
        assertFalse(before.arrived)
        assertFalse(after.arrived)
        assertFalse(after.arrivalCandidate)
        assertNull(withEvidence.pendingDecisionToken())
        assertTrue(withEvidence.hasRoute())
        assertTrue(after.instruction.orEmpty(), after.instruction.orEmpty().contains("목적지 근처일 수 있습니다"))
        assertEquals(providerEvidence, withEvidence.destinationCoordinateEvidenceFor(target))
    }

    @Test
    fun recreatedStoreAndNavigatorRestoreExactFirstRouteEvidenceWithoutReplacingItsArchive() {
        val storage = CoordinateSnapshotStorage()
        val firstRoute = storeRoute()
        val firstEvidence = evidence()
        val store = newStore(storage)
        assertTrue(store.saveFirstRoute(scope, firstRoute, destination, nowEpochMs = 1_000L,
            destinationCoordinateEvidence = firstEvidence))
        val originalArchive = requireNotNull(storage.envelope)
        val root = JSONObject(originalArchive)
        val encodedEvidence = root.getJSONObject("destination_coordinate_evidence")
        assertEquals("noor", encodedEvidence.getString("coordinate_kind"))
        assertEquals(2, encodedEvidence.getJSONArray("coordinate_candidates").length())

        val laterEvidence = DestinationCoordinateEvidence(DestinationCoordinateKind.FRONT,
            listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, alternative)))
        assertTrue(store.saveFirstRoute(scope, storeRoute(alternative, "replacement"), alternative, nowEpochMs = 2_000L,
            destinationCoordinateEvidence = laterEvidence))
        assertEquals(originalArchive, storage.envelope)

        val restored = requireNotNull(newStore(storage).load(scope, nowEpochMs = 3_000L))
        assertEquals(scope, restored.scope)
        assertEquals(firstRoute, restored.route)
        assertEquals(destination, restored.destination)
        assertEquals(firstEvidence, restored.destinationCoordinateEvidence)
        assertEquals(1_000L, restored.savedAtEpochMs)
        assertEquals(1_000L + ROUTE_SNAPSHOT_TTL_MS, restored.expiresAtEpochMs)
        val navigator = RouteNavigator().apply {
            setRoute(restored.route, destination = restored.destination,
                destinationCoordinateEvidence = restored.destinationCoordinateEvidence)
        }
        assertEquals(firstEvidence, navigator.destinationCoordinateEvidenceFor(destination))
        assertNull(navigator.destinationCoordinateEvidenceFor(alternative))
    }

    @Test
    fun aLegacyV4ArchiveWithoutTheOptionalEvidenceKeyStillRestoresItsOriginalRoute() {
        val storage = CoordinateSnapshotStorage()
        val originalRoute = storeRoute()
        assertTrue(newStore(storage).saveFirstRoute(scope, originalRoute, destination, nowEpochMs = 1_000L,
            destinationCoordinateEvidence = evidence()))
        val legacy = JSONObject(requireNotNull(storage.envelope))
        assertEquals("walksafe.encrypted-route-snapshot.v4", legacy.getString("schema_version"))
        legacy.remove("destination_coordinate_evidence")
        assertFalse(legacy.has("destination_coordinate_evidence"))
        storage.envelope = legacy.toString()
        val legacyArchive = storage.envelope

        val restored = requireNotNull(newStore(storage).load(scope, nowEpochMs = 2_000L))
        assertEquals(originalRoute, restored.route)
        assertEquals(destination, restored.destination)
        assertEquals(scope, restored.scope)
        assertNull(restored.destinationCoordinateEvidence)
        assertEquals(legacyArchive, storage.envelope)
    }

    @Test
    fun invalidTypedEvidenceCannotCreateAnArchiveOrDisturbAnExistingFirstRoute() {
        val invalidInputs = listOf(
            DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR,
                List(5) { DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, destination.copy(name = null)) }),
            DestinationCoordinateEvidence(DestinationCoordinateKind.FRONT,
                listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, alternative.copy(latitude = 91.0)))),
        )
        for (invalidInput in invalidInputs) {
            val storage = CoordinateSnapshotStorage()
            val store = newStore(storage)
            val firstRoute = storeRoute()
            val firstEvidence = evidence()
            assertFalse(store.saveFirstRoute(scope, firstRoute, destination, nowEpochMs = 1_000L,
                destinationCoordinateEvidence = invalidInput))
            assertNull(storage.envelope)

            assertTrue(store.saveFirstRoute(scope, firstRoute, destination, nowEpochMs = 1_001L,
                destinationCoordinateEvidence = firstEvidence))
            val originalArchive = requireNotNull(storage.envelope)
            val before = requireNotNull(store.load(scope, nowEpochMs = 1_500L))
            assertEquals(firstRoute, before.route)
            assertEquals(destination, before.destination)
            assertEquals(firstEvidence, before.destinationCoordinateEvidence)

            assertFalse(store.saveFirstRoute(scope, storeRoute(alternative, "invalid-replacement"), alternative,
                nowEpochMs = 2_000L, destinationCoordinateEvidence = invalidInput))
            assertEquals(originalArchive, storage.envelope)
            assertEquals(before, newStore(storage).load(scope, nowEpochMs = 3_000L))
            assertEquals(originalArchive, storage.envelope)
        }
    }

    private fun evidence() = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, listOf(
        DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, destination.copy(name = null)),
        DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, alternative),
    ))

    private fun route(target: RoutePoint = destination, providerRouteId: String = "first-route") = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(111, 90),
        polyline = listOf(RoutePoint(37.0, 127.0), target),
        guidePoints = emptyList(),
        providerRouteId = providerRouteId,
    )

    private fun storeRoute(target: RoutePoint = destination, providerRouteId: String = "first-route") =
        route(target, providerRouteId).copy(guidePoints = listOf(WalkingRouteGuidePoint(
            index = 0,
            point = RoutePoint(37.0005, 127.0),
            instruction = "직진",
            distanceFromStartM = 56,
            remainingDistanceM = 55,
            bearingDeg = 0f,
            turnType = 11,
            pointType = "guide",
            facilityType = 15,
        )))

    private fun newStore(storage: CoordinateSnapshotStorage) = EncryptedRouteSnapshotStore(
        storage = storage,
        aead = CoordinateSnapshotAead(),
        testOnly = Unit,
        processCoordinator = storage.process,
    ).also {
        check(it.bindAccount(scope.owner))
        check(it.beginWalk(scope))
    }

    private fun eastPoint(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)

    private fun sample(navigator: RouteNavigator, eastM: Double, at: Long, accuracyM: Float): RouteNavigatorUpdate {
        val point = eastPoint(eastM)
        return navigator.update(TrustedLocation(point.latitude, point.longitude, accuracyM, at), at, false,
            facingObservation = RouteFacingObservation(90.0, 5.0, at))
    }

    private class CoordinateSnapshotStorage : RouteSnapshotStorage {
        val process = RouteSnapshotProcessCoordinator()
        var envelope: String? = null
        override fun read(): String? = envelope
        override fun write(envelope: String): Boolean {
            this.envelope = envelope
            return true
        }
        override fun clear(): Boolean {
            envelope = null
            return true
        }
    }

    /** Transparent unit-test AEAD isolates snapshot encoding/decoding; it does not test encryption. */
    private class CoordinateSnapshotAead : LocalAead {
        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult =
            AeadSealResult.Sealed(String(plaintext, Charsets.UTF_8))

        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
            AeadOpenResult.Opened(envelope.toByteArray(Charsets.UTF_8), keyVersion = 1, needsRewrap = false)

        override fun destroyVersion(version: Int): Boolean = true
        override fun destroyKnownVersions(): Boolean = true
        override fun createFreshAfterVerifiedPurge(): Boolean = true
    }
}
