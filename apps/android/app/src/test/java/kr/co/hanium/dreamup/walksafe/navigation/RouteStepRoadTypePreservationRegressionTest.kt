package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-12, 07.txt:135-143: preserve provider road-code identity without interpreting safety. */
class RouteStepRoadTypePreservationRegressionTest {
    @Test fun differentProvidedRoadTypesRemainDistinctInTheParsedSteps() {
        assertNotEquals(parse(payload(16)).steps, parse(payload(999)).steps)
    }

    @Test fun zeroRoadTypeDoesNotBecomeMissingMetadata() {
        assertNotEquals(parse(payload(0)).steps, parse(payload()).steps)
    }

    @Test fun savingAParsedRouteKeepsTheProvidedCodeInTheArchive() {
        val archive = Archive()
        val route = parse(payload(16))
        assertTrue(archive.store.saveFirstRoute(scope, route, destination, 1_000L))
        assertRoadCodes(archive.plaintext(), listOf(16))
        assertEquals(route, archive.store.load(scope, 2_000L)?.route)
    }

    @Test fun loadingAnAuthenticatedRoadCodeKeepsItForTheNextSerialization() {
        val archive = Archive()
        val missing = parse(payload())
        assertTrue(archive.store.saveFirstRoute(scope, missing, destination, 1_000L))
        val supplied = archive.plaintext()
        supplied.getJSONObject("route").getJSONArray("steps").getJSONObject(0).put("road_type", 999)
        archive.replacePlaintext(supplied)
        val restored = requireNotNull(archive.recreate().load(scope, 2_000L)).route
        assertNotEquals(missing.steps, restored.steps)
        val next = Archive()
        assertTrue(next.store.saveFirstRoute(scope, restored, destination, 2_000L))
        assertRoadCodes(next.plaintext(), listOf(999))
    }

    @Test fun validIntegerCodesSurviveRecreatedStoresWithoutAnEnumOrSafetyMapping() {
        for (code in listOf(0, 16, 999, Int.MAX_VALUE)) {
            val archive = Archive()
            val route = parse(payload(code))
            assertTrue(archive.store.saveFirstRoute(scope, route, destination, 1_000L))
            assertRoadCodes(archive.plaintext(), listOf(code))
            assertEquals(route, archive.recreate().load(scope, 2_000L)?.route)
            assertEquals("STAIR_AVOID", route.priority)
            assertEquals(15, route.steps.single().facilityType)
        }
    }

    @Test fun absentAndNullLegacyMetadataStayUnknownAndAreNotInventedOnSave() {
        val absent = parse(payload())
        assertEquals(absent, parse(payload(JSONObject.NULL)))
        for (wire in listOf(payload(), payload(JSONObject.NULL))) {
            val archive = Archive()
            val route = parse(wire)
            assertTrue(archive.store.saveFirstRoute(scope, route, destination, 1_000L))
            assertFalse(archive.plaintext().getJSONObject("route").getJSONArray("steps")
                .getJSONObject(0).has("road_type"))
            assertEquals(route, archive.recreate().load(scope, 2_000L)?.route)
        }
    }

    @Test fun authenticatedLegacyV4WithNullRoadMetadataStillLoads() {
        val archive = Archive()
        val route = parse(payload())
        assertTrue(archive.store.saveFirstRoute(scope, route, destination, 1_000L))
        val legacy = archive.plaintext()
        assertEquals("walksafe.encrypted-route-snapshot.v4", legacy.getString("schema_version"))
        legacy.getJSONObject("route").getJSONArray("steps").getJSONObject(0)
            .put("road_type", JSONObject.NULL)
        archive.replacePlaintext(legacy)
        val envelope = archive.storage.envelope
        assertEquals(route, archive.recreate().load(scope, 2_000L)?.route)
        assertEquals(envelope, archive.storage.envelope)
    }

    @Test fun orderedStepCodesAndTheExistingPropertiesSurviveTheSameArchive() {
        val wire = payload(16)
        val points = wire.getJSONArray("polyline")
        val middle = pointJson(37.0005)
        val first = wire.getJSONArray("steps").getJSONObject(0)
        first.put("distance_m", 60).put("duration_s", 45)
            .put("points", JSONArray().put(points.getJSONObject(0)).put(middle))
        val second = JSONObject(first.toString()).put("index", 1).put("road_type", 999)
            .put("road_name", "다음길")
            .put("points", JSONArray().put(middle).put(points.getJSONObject(1)))
        wire.put("steps", JSONArray().put(first).put(second))
        wire.put("polyline", JSONArray().put(points.getJSONObject(0)).put(middle).put(points.getJSONObject(1)))
        val route = parse(wire)
        assertExistingProperties(route, listOf(origin, RoutePoint(37.0005, 127.0), destination))
        assertEquals(listOf("보행길", "다음길"), route.steps.map { it.roadName })
        assertEquals(listOf(0, 1), route.steps.map { it.index })
        assertEquals(120, route.steps.sumOf { it.distanceM })
        val archive = Archive()
        assertTrue(archive.store.saveFirstRoute(scope, route, destination, 1_000L))
        assertRoadCodes(archive.plaintext(), listOf(16, 999))
        assertEquals(route, archive.recreate().load(scope, 2_000L)?.route)
    }

    @Test fun unrelatedFieldsDoNotChangeTheExistingRouteProperties() {
        val original = parse(payload())
        val extra = payload().put("future_route_label", "opaque")
        extra.getJSONArray("steps").getJSONObject(0).put("future_road_label", "opaque")
        assertEquals(original, parse(extra))
        assertExistingProperties(original)
    }

    @Test fun bothExistingDistanceSourcesSurviveRoadMetadataRoundTrips() {
        for (source in WalkingRouteDistanceSource.entries) {
            val route = parse(payload(16).put("distance_source", source.wireValue))
            val archive = Archive()
            assertTrue(archive.store.saveFirstRoute(scope, route, destination, 1_000L))
            assertRoadCodes(archive.plaintext(), listOf(16))
            val restored = requireNotNull(archive.recreate().load(scope, 2_000L)).route
            assertEquals(route, restored)
            assertEquals(source, restored.distanceSource)
            assertEquals(restored.summary.distanceM, restored.steps.sumOf { it.distanceM })
        }
    }

    @Test fun theFirstArchiveKeepsItsRoadCodeAfterAReroute() {
        val archive = Archive()
        val first = parse(payload(16))
        assertTrue(archive.store.saveFirstRoute(scope, first, destination, 1_000L))
        val envelope = archive.storage.envelope
        assertTrue(archive.store.saveFirstRoute(scope, parse(payload(999)), destination, 2_000L))
        assertEquals(envelope, archive.storage.envelope)
        assertRoadCodes(archive.plaintext(), listOf(16))
        assertEquals(first, archive.store.load(scope, 3_000L)?.route)
    }

    @Test fun roadMetadataDoesNotGrantAWrongOwnerOrWalkLeaseAccess() {
        val archive = Archive()
        val route = parse(payload(16))
        assertTrue(archive.store.saveFirstRoute(scope, route, destination, 1_000L))
        val otherWalk = RouteSnapshotScope(owner, "other-walk")
        val wrongOwner = RouteSnapshotScope(RouteSnapshotOwner("other-actor", "account:2"), scope.walkSessionId)
        assertNull(archive.store.load(otherWalk, 2_000L))
        assertNull(archive.store.load(wrongOwner, 2_000L))
        assertFalse(archive.store.saveFirstRoute(otherWalk, route, destination, 2_000L))
        assertFalse(archive.store.saveFirstRoute(wrongOwner, route, destination, 2_000L))
        assertEquals(route, archive.store.load(scope, 2_000L)?.route)
    }

    @Test fun negativeCodeUsesTheExistingInvalidStepResponse() = assertInvalid(-1)
    @Test fun fractionalCodeUsesTheExistingInvalidStepResponse() = assertInvalid(16.5)
    @Test fun overflowingCodeUsesTheExistingInvalidStepResponse() = assertInvalid(Int.MAX_VALUE.toLong() + 1L)
    @Test fun booleanCodeUsesTheExistingInvalidStepResponse() = assertInvalid(true)
    @Test fun stringCodeUsesTheExistingInvalidStepResponse() = assertInvalid("16")
    @Test fun objectCodeUsesTheExistingInvalidStepResponse() = assertInvalid(JSONObject().put("code", 16))

    private fun assertInvalid(code: Any) {
        assertEquals("route_steps_invalid", assertThrows(GatewayResponseProtocolException::class.java) {
            parse(payload(code))
        }.reason)
    }

    private fun assertExistingProperties(route: WalkingRoute, expectedPolyline: List<RoutePoint> = listOf(origin, destination)) {
        assertEquals(WalkingRouteSummary(120, 90), route.summary)
        assertEquals(expectedPolyline, route.polyline)
        assertEquals(List(route.steps.size) { 15 }, route.steps.map { it.facilityType })
        assertTrue(route.steps.all { it.turnType == 11 && it.instruction == "제공된 구간 설명" })
        val guide = route.guidePoints.single()
        assertEquals("SP", guide.pointType)
        assertEquals(200, guide.turnType)
        assertEquals(11, guide.facilityType)
        assertEquals(0, guide.distanceFromStartM)
        assertEquals(120, guide.remainingDistanceM)
    }

    private fun assertRoadCodes(plaintext: JSONObject, codes: List<Int>) {
        val steps = plaintext.getJSONObject("route").getJSONArray("steps")
        assertEquals(codes.size, steps.length())
        codes.forEachIndexed { index, code ->
            val step = steps.getJSONObject(index)
            assertTrue("Serialized step $index lost provided road_type=$code", step.has("road_type"))
            assertEquals(code, step.getInt("road_type"))
        }
    }

    private fun parse(wire: JSONObject) = parseWalkingRoute(wire, WalkingRouteRequest(origin, destination))

    private fun payload(code: Any? = null): JSONObject {
        val wire = JSONObject("""{
          "schema_version":"walksafe.walking_route.v1", "provider":"tmap_pedestrian",
          "priority":"STAIR_AVOID", "summary":{"distance_m":120,"duration_s":90},
          "polyline":[{"latitude":37.0,"longitude":127.0},{"latitude":37.001,"longitude":127.0}],
          "steps":[{"index":0,"distance_m":120,"duration_s":90,
            "points":[{"latitude":37.0,"longitude":127.0},{"latitude":37.001,"longitude":127.0}],
            "instruction":"제공된 구간 설명","road_name":"보행길","turn_type":11,"facility_type":15}],
          "guide_points":[{"index":0,"point":{"latitude":37.0,"longitude":127.0},
            "instruction":"출발","turn_type":200,"point_type":"SP","facility_type":11,
            "distance_from_start_m":0,"remaining_distance_m":120,"bearing_deg":0.0}],
          "provider_result_code":0,"provider_result_message":"OK"
        }""")
        if (code != null) wire.getJSONArray("steps").getJSONObject(0).put("road_type", code)
        return wire
    }

    private fun pointJson(latitude: Double) = JSONObject().put("latitude", latitude).put("longitude", 127.0)

    private inner class Archive {
        val storage = RoadTypeMemoryStorage()
        private val aead = RoadTypeMemoryAead()
        private val process = RouteSnapshotProcessCoordinator()
        val store = recreate()
        fun recreate() = EncryptedRouteSnapshotStore(storage, aead, processCoordinator = process,
            elapsedRealtimeMs = { 1_000L }, bootCount = { 7 }).also {
            check(it.bindAccount(owner)); check(it.beginWalk(scope))
        }
        fun plaintext() = JSONObject(String(aead.bytes(requireNotNull(storage.envelope)), Charsets.UTF_8))
        fun replacePlaintext(payload: JSONObject) { storage.envelope = aead.capture(payload.toString().toByteArray()) }
    }

    private val origin = RoutePoint(37.0, 127.0)
    private val destination = RoutePoint(37.001, 127.0)
    private val owner = RouteSnapshotOwner("synthetic-road-actor", "account:1")
    private val scope = RouteSnapshotScope(owner, "road-property-walk")
}

private class RoadTypeMemoryStorage : RouteSnapshotStorage {
    var envelope: String? = null
    override fun read() = envelope
    override fun write(envelope: String): Boolean { this.envelope = envelope; return true }
    override fun clear(): Boolean { envelope = null; return true }
}

/** Synthetic serialization boundary only; crypto correctness uses the existing real AEAD tests. */
private class RoadTypeMemoryAead : LocalAead {
    private val records = mutableMapOf<String, ByteArray>()
    fun capture(plaintext: ByteArray): String = "synthetic-road:${records.size + 1}".also { records[it] = plaintext.copyOf() }
    fun bytes(envelope: String) = requireNotNull(records[envelope]).copyOf()
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) = AeadSealResult.Sealed(capture(plaintext))
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        records[envelope]?.let { AeadOpenResult.Opened(it.copyOf(), 1, false) }
            ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = true
    override fun createFreshAfterVerifiedPurge() = true
}
