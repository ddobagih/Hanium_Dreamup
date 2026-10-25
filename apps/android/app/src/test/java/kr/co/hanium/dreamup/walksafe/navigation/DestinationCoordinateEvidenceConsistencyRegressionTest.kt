package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** S07-11/38: a provider-family claim must describe the requested point, not a snapped endpoint. */
class DestinationCoordinateEvidenceConsistencyRegressionTest {
    private val destination = RoutePoint(37.5547, 126.9706, "서울역")
    private val alternative = RoutePoint(37.5548, 126.9707, "공급자 후보")
    private val scope = RouteSnapshotScope(RouteSnapshotOwner("coordinate-consistency", "account:1"), "walk-coordinate")

    @Test
    fun aConflictingSearchCandidateLosesOnlyTheKnownSelectedFamily() {
        val candidates = listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, alternative))
        val result = parse(DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, candidates))
        assertSelectedPointAndEvidence(result, DestinationCoordinateEvidence(null, candidates))
    }

    @Test
    fun theFirstCandidateCannotClaimAnotherFamilyEvenWhenALaterOneMatches() {
        val candidates = listOf(
            DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, destination.copy(name = "front")),
            DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, destination.copy(name = "noor")),
        )
        val result = parse(DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, candidates))
        assertSelectedPointAndEvidence(result, DestinationCoordinateEvidence(null, candidates))
    }

    @Test
    fun savingContradictoryTypedEvidenceKeepsTheRouteAndCandidatesWithoutTheClaim() {
        val storage = Storage()
        val store = store(storage)
        val originalRoute = route()
        val candidates = listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, alternative))
        assertTrue(store.saveFirstRoute(scope, originalRoute, destination, 1_000L,
            DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, candidates)))
        val archive = JSONObject(requireNotNull(storage.envelope))
        assertFalse(archive.getJSONObject("destination_coordinate_evidence").has("coordinate_kind"))
        val restored = requireNotNull(store.load(scope, 2_000L))
        assertEquals(destination, restored.destination)
        assertEquals(originalRoute, restored.route)
        assertEquals(DestinationCoordinateEvidence(null, candidates), restored.destinationCoordinateEvidence)
    }

    @Test
    fun aConflictingV4ArchiveLoadsAsCandidateOnlyWithoutRewritingItsBytes() {
        val storage = Storage()
        val originalRoute = route()
        assertTrue(store(storage).saveFirstRoute(scope, originalRoute, destination, 1_000L, consistentEvidence()))
        val archive = JSONObject(requireNotNull(storage.envelope))
        val evidence = archive.getJSONObject("destination_coordinate_evidence")
        evidence.getJSONArray("coordinate_candidates").getJSONObject(0).put("point", pointJson(alternative))
        storage.envelope = archive.toString()
        val originalArchive = storage.envelope
        val restored = requireNotNull(store(storage).load(scope, 2_000L))
        assertEquals(DestinationCoordinateEvidence(null,
            listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, alternative))),
            restored.destinationCoordinateEvidence)
        assertEquals(destination, restored.destination)
        assertEquals(originalRoute, restored.route)
        assertEquals(originalArchive, storage.envelope)
    }

    @Test
    fun partialAndUnknownFactsRemainSupportedInSearchAndSnapshot() {
        val candidateOnly = DestinationCoordinateEvidence(null,
            listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, alternative)))
        for (input in listOf(null, DestinationCoordinateEvidence(null, emptyList()),
            DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, emptyList()), candidateOnly)) {
            val expected = input?.takeUnless { it.selectedKind == null && it.candidates.isEmpty() }
            assertSelectedPointAndEvidence(parse(input), expected)
            val storage = Storage()
            val originalRoute = route()
            assertTrue(store(storage).saveFirstRoute(scope, originalRoute, destination, 1_000L, input))
            val restored = requireNotNull(store(storage).load(scope, 2_000L))
            assertEquals(expected, restored.destinationCoordinateEvidence)
            assertEquals(destination, restored.destination)
            assertEquals(originalRoute, restored.route)
        }
    }

    @Test
    fun identicalCoordinatesWithDifferentFamiliesAndNamesPreserveTheirOrderAndMeaning() {
        for (kind in DestinationCoordinateKind.entries) {
            val other = DestinationCoordinateKind.entries.first { it != kind }
            val input = DestinationCoordinateEvidence(kind, listOf(
                DestinationCoordinateCandidate(kind, destination.copy(name = "선택 공급자 이름")),
                DestinationCoordinateCandidate(other, destination.copy(name = "다른 공급자 이름")),
            ))
            assertSelectedPointAndEvidence(parse(input), input)
            val storage = Storage()
            assertTrue(store(storage).saveFirstRoute(scope, route(), destination, 1_000L, input))
            assertEquals(input, requireNotNull(store(storage).load(scope, 2_000L)).destinationCoordinateEvidence)
        }
    }

    @Test
    fun requestedPoiEvidenceDoesNotNeedToMatchTheSnappedRouteEndpoint() {
        val snappedEndpoint = destination.copy(latitude = destination.latitude - 0.00005, name = "경로 종점")
        val snappedRoute = route(snappedEndpoint)
        val input = consistentEvidence()
        val storage = Storage()
        assertTrue(store(storage).saveFirstRoute(scope, snappedRoute, destination, 1_000L, input))
        val restored = requireNotNull(store(storage).load(scope, 2_000L))
        assertEquals(snappedEndpoint, restored.route.polyline.last())
        assertEquals(destination, restored.destination)
        assertEquals(input, restored.destinationCoordinateEvidence)
        val navigator = RouteNavigator().apply {
            setRoute(restored.route, destination = restored.destination,
                destinationCoordinateEvidence = restored.destinationCoordinateEvidence)
        }
        assertEquals(input, navigator.destinationCoordinateEvidenceFor(destination))
        assertNull(navigator.destinationCoordinateEvidenceFor(snappedEndpoint))
    }

    @Test
    fun laterContradictoryMetadataCannotReplaceTheFirstArchive() {
        val storage = Storage()
        val store = store(storage)
        val firstRoute = route()
        val firstEvidence = consistentEvidence()
        assertTrue(store.saveFirstRoute(scope, firstRoute, destination, 1_000L, firstEvidence))
        val firstArchive = storage.envelope
        val contradictory = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR,
            listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, alternative)))
        assertTrue(store.saveFirstRoute(scope, route(providerRouteId = "later"), destination, 2_000L, contradictory))
        assertEquals(firstArchive, storage.envelope)
        val restored = requireNotNull(store(storage).load(scope, 3_000L))
        assertEquals(firstRoute, restored.route)
        assertEquals(firstEvidence, restored.destinationCoordinateEvidence)
        assertEquals(firstArchive, storage.envelope)
    }

    private fun assertSelectedPointAndEvidence(result: DestinationSearchResult, expected: DestinationCoordinateEvidence?) {
        assertEquals(DestinationSearchResult("poi-1", "서울역", destination, null,
            "서울 중구 한강대로 405", "교통", 120, "poi", coordinateEvidence = expected), result)
    }

    private fun parse(evidence: DestinationCoordinateEvidence?): DestinationSearchResult {
        val result = JSONObject().put("id", "poi-1").put("name", "서울역")
            .put("point", pointJson(destination)).put("road_address", "서울 중구 한강대로 405")
            .put("category", "교통").put("distance_m", 120).put("result_type", "poi")
        evidence?.let {
            it.selectedKind?.let { kind -> result.put("coordinate_kind", kind.wireName) }
            result.put("coordinate_candidates", JSONArray().also { array ->
                it.candidates.forEach { candidate ->
                    array.put(JSONObject().put("kind", candidate.kind.wireName).put("point", pointJson(candidate.point)))
                }
            })
        }
        return parseDestinationSearchResponse(JSONObject().put("schema_version", "walksafe.destination_search.v1")
            .put("provider", "tmap_poi").put("query", "서울역").put("results", JSONArray().put(result)), "서울역")
            .results.single()
    }

    private fun pointJson(point: RoutePoint): JSONObject = JSONObject()
        .put("latitude", point.latitude).put("longitude", point.longitude)
        .also { json -> point.name?.let { json.put("name", it) } }

    private fun consistentEvidence() = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR,
        listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, destination.copy(name = "공급자 이름"))))

    private fun route(target: RoutePoint = destination, providerRouteId: String = "first") : WalkingRoute {
        val origin = destination.copy(latitude = destination.latitude - 0.001, name = null)
        val distance = haversineMeters(origin.latitude, origin.longitude, target.latitude, target.longitude).roundToInt()
        return WalkingRoute("STAIR_AVOID", WalkingRouteSummary(distance, 90), listOf(origin, target),
            listOf(WalkingRouteGuidePoint(0,
                RoutePoint((origin.latitude + target.latitude) / 2, origin.longitude), "직진", distance / 2,
                distance - distance / 2, bearingDeg = 0f, turnType = 11, pointType = "guide", facilityType = 15)),
            providerRouteId = providerRouteId)
    }

    private fun store(storage: Storage) = EncryptedRouteSnapshotStore(storage, TransparentAead(), testOnly = Unit,
        processCoordinator = storage.process).also {
        check(it.bindAccount(scope.owner))
        check(it.beginWalk(scope))
    }

    private class Storage : RouteSnapshotStorage {
        val process = RouteSnapshotProcessCoordinator()
        var envelope: String? = null
        override fun read(): String? = envelope
        override fun write(envelope: String): Boolean { this.envelope = envelope; return true }
        override fun clear(): Boolean { envelope = null; return true }
    }

    /** Snapshot codec seam only; encryption and production keystore behavior are not tested here. */
    private class TransparentAead : LocalAead {
        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult =
            AeadSealResult.Sealed(String(plaintext, Charsets.UTF_8))
        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
            AeadOpenResult.Opened(envelope.toByteArray(Charsets.UTF_8), 1, needsRewrap = false)
        override fun destroyVersion(version: Int): Boolean = true
        override fun destroyKnownVersions(): Boolean = true
        override fun createFreshAfterVerifiedPurge(): Boolean = true
    }
}
