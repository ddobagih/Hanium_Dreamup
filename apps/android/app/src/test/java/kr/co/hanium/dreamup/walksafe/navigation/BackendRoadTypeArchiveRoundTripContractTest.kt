package kr.co.hanium.dreamup.walksafe.navigation

import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
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

/** Actual Backend normalizer/DTO output bytes; no network or AndroidKeyStore execution. */
class BackendRoadTypeArchiveRoundTripContractTest {
    @Test fun providedSixteenSurvivesTheActualResponseAndFirstArchive() =
        assertActualResponseRoundTrip("road-16", 16)

    @Test fun providedUnknownNineNineNineSurvivesWithoutAnEnumMapping() =
        assertActualResponseRoundTrip("road-999", 999)

    @Test fun providedZeroRemainsDistinctFromMissingMetadata() =
        assertActualResponseRoundTrip("road-0", 0)

    @Test fun providedIntMaxSurvivesWithoutNumericTruncation() =
        assertActualResponseRoundTrip("road-int-max", Int.MAX_VALUE)

    @Test fun providerAbsentSerializedAsDtoNullStaysUnknown() =
        assertActualResponseRoundTrip("provider-absent", null)

    @Test fun explicitNullableDtoInputSerializedAsNullStaysUnknown() =
        assertActualResponseRoundTrip("explicit-dto-null", null)

    @Test fun legacyWireWithoutRoadMetadataStillRoundTrips() {
        val wire = actualResponse("provider-absent")
        wire.getJSONArray("steps").getJSONObject(0).remove("road_type")
        val route = parseWalkingRoute(wire, request)
        assertNull(route.steps.single().roadType)
        val archive = Archive()
        assertTrue(archive.store.saveFirstRoute(scope, route, request.destination, 1_000L))
        assertFalse(archive.plaintext().getJSONObject("route").getJSONArray("steps")
            .getJSONObject(0).has("road_type"))
        assertEquals(route, archive.recreate().load(scope, 2_000L)?.route)
    }

    @Test fun authenticatedLegacyV4NullableRoadMetadataStillLoads() {
        val route = parseWalkingRoute(actualResponse("explicit-dto-null"), request)
        val archive = Archive()
        assertTrue(archive.store.saveFirstRoute(scope, route, request.destination, 1_000L))
        val legacy = archive.plaintext()
        assertEquals("walksafe.encrypted-route-snapshot.v4", legacy.getString("schema_version"))
        legacy.getJSONObject("route").getJSONArray("steps").getJSONObject(0)
            .put("road_type", JSONObject.NULL)
        archive.replacePlaintext(legacy)
        val envelope = archive.storage.envelope
        assertEquals(route, archive.recreate().load(scope, 2_000L)?.route)
        assertEquals(envelope, archive.storage.envelope)
    }

    @Test fun actualMetadataDoesNotReplaceTheFirstArchiveOrGrantAnotherOwnerOrLeaseAccess() {
        val first = parseWalkingRoute(actualResponse("road-16"), request)
        val reroute = parseWalkingRoute(actualResponse("road-999"), request)
        val archive = Archive()
        assertTrue(archive.store.saveFirstRoute(scope, first, request.destination, 1_000L))
        val envelope = archive.storage.envelope
        assertTrue(archive.store.saveFirstRoute(scope, reroute, request.destination, 2_000L))
        assertEquals(envelope, archive.storage.envelope)
        assertEquals(first, archive.store.load(scope, 3_000L)?.route)
        val otherWalk = RouteSnapshotScope(owner, "other-actual-response-walk")
        val otherOwner = RouteSnapshotScope(RouteSnapshotOwner("other-actor", "account:2"), scope.walkSessionId)
        assertNull(archive.store.load(otherWalk, 3_000L))
        assertNull(archive.store.load(otherOwner, 3_000L))
        assertFalse(archive.store.saveFirstRoute(otherWalk, reroute, request.destination, 3_000L))
        assertFalse(archive.store.saveFirstRoute(otherOwner, reroute, request.destination, 3_000L))
        assertEquals(envelope, archive.storage.envelope)
        assertTrue(archive.recreate().endWalk())
        assertNull(archive.store.load(scope, 4_000L))
        assertFalse(archive.store.saveFirstRoute(scope, reroute, request.destination, 4_000L))
    }

    private fun assertActualResponseRoundTrip(case: String, expectedCode: Int?) {
        val record = responseRecord(case)
        assertEquals(0, record.getInt("step_index"))
        if (expectedCode == null) assertTrue(record.isNull("expected_road_type"))
        else assertEquals(expectedCode, record.getInt("expected_road_type"))
        if (case == "explicit-dto-null") {
            assertTrue(record.getString("source_path").contains("explicit nullable DTO input"))
            assertFalse(record.getBoolean("provider_raw_null_normalized"))
        } else {
            assertTrue(record.getString("source_path").contains("_normalize_tmap_response"))
        }
        val route = parseWalkingRoute(actualResponse(case), request)
        assertEquals(expectedCode, route.steps.single().roadType)
        assertExistingProperties(route)
        val archive = Archive()
        assertTrue(archive.store.saveFirstRoute(scope, route, request.destination, 1_000L))
        val serializedStep = archive.plaintext().getJSONObject("route").getJSONArray("steps").getJSONObject(0)
        if (expectedCode == null) assertFalse(serializedStep.has("road_type"))
        else assertEquals(expectedCode, serializedStep.getInt("road_type"))
        val restored = requireNotNull(archive.recreate().load(scope, 2_000L))
        assertEquals(scope, restored.scope)
        assertEquals(request.destination, restored.destination)
        assertEquals(1_000L, restored.savedAtEpochMs)
        assertEquals(1_000L, restored.savedAtElapsedRealtimeMs)
        assertEquals(7, restored.savedBootCount)
        assertNull(restored.destinationCoordinateEvidence)
        assertEquals(route, restored.route)
        assertEquals(expectedCode, restored.route.steps.single().roadType)
        assertExistingProperties(restored.route)
    }

    private fun assertExistingProperties(route: WalkingRoute) {
        assertEquals("STAIR_AVOID", route.priority)
        assertEquals(WalkingRouteSummary(90, 80), route.summary)
        assertEquals(listOf(request.origin, request.destination), route.polyline)
        assertEquals(listOf(0), route.steps.map { it.index })
        val step = route.steps.single()
        assertEquals(route.polyline, step.points)
        assertEquals(90, step.distanceM)
        assertEquals(80, step.durationS)
        assertEquals("제공된 구간 설명", step.instruction)
        assertEquals("보행길", step.roadName)
        assertEquals(15, step.facilityType)
        assertNull(step.turnType)
        val guide = route.guidePoints.single()
        assertEquals(0, guide.index)
        assertEquals(request.origin, guide.point)
        assertEquals("출발", guide.instruction)
        assertEquals("SP", guide.pointType)
        assertEquals(200, guide.turnType)
        assertEquals(11, guide.facilityType)
        assertEquals(0, guide.distanceFromStartM)
        assertEquals(90, guide.remainingDistanceM)
        assertEquals(89.99969909244078, requireNotNull(guide.bearingDeg).toDouble(), 0.00001)
        assertEquals(WalkingRouteDistanceSource.PROVIDER_TOTAL_GEOMETRY_SCALED, route.distanceSource)
        assertNull(route.providerRouteId)
    }

    private fun responseRecord(case: String): JSONObject {
        val records = manifest.getJSONArray("responses")
        return (0 until records.length()).map(records::getJSONObject).single { it.getString("case") == case }
    }

    private fun actualResponse(case: String): JSONObject {
        val output = responseRecord(case).getJSONObject("output")
        val bytes = resource(output.getString("path").substringAfterLast('/'))
        assertEquals(output.getString("sha256"), sha256(bytes))
        assertEquals(output.getInt("bytes"), bytes.size)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun resource(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/navigation/backend-road-type/$name")
    ) { "Missing actual Backend response resource: $name" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val manifest by lazy {
        val bytes = resource("upstream-manifest.json")
        assertEquals("b32b0304c04c62b8428bad18454cd260408481a6e3e225c37e405268f5a91f3f", sha256(bytes))
        JSONObject(String(bytes, Charsets.UTF_8)).also {
            assertFalse(it.getJSONObject("raw_provider_null").getBoolean("successful_response"))
        }
    }

    private val request by lazy {
        val bytes = resource("request.json")
        assertEquals(manifest.getJSONObject("request").getString("sha256"), sha256(bytes))
        val wire = JSONObject(String(bytes, Charsets.UTF_8))
        fun point(name: String) = wire.getJSONObject(name).let {
            RoutePoint(it.getDouble("latitude"), it.getDouble("longitude"))
        }
        WalkingRouteRequest(point("origin"), point("destination"), wire.getString("priority"))
    }

    private inner class Archive {
        val storage = ActualResponseMemoryStorage()
        private val aead = ActualResponseMemoryAead()
        private val process = RouteSnapshotProcessCoordinator()
        val store = recreate()
        fun recreate() = EncryptedRouteSnapshotStore(storage, aead, processCoordinator = process,
            elapsedRealtimeMs = { 1_000L }, bootCount = { 7 }).also {
            check(it.bindAccount(owner)); check(it.beginWalk(scope))
        }
        fun plaintext() = JSONObject(String(aead.bytes(requireNotNull(storage.envelope)), Charsets.UTF_8))
        fun replacePlaintext(payload: JSONObject) { storage.envelope = aead.capture(payload.toString().toByteArray()) }
    }

    private val owner = RouteSnapshotOwner("synthetic-actual-response-actor", "account:1")
    private val scope = RouteSnapshotScope(owner, "actual-response-walk")
}

private class ActualResponseMemoryStorage : RouteSnapshotStorage {
    var envelope: String? = null
    override fun read() = envelope
    override fun write(envelope: String): Boolean { this.envelope = envelope; return true }
    override fun clear(): Boolean { envelope = null; return true }
}

/** Existing synthetic plaintext seam: tests the production codec, not AndroidKeyStore/AEAD. */
private class ActualResponseMemoryAead : LocalAead {
    private val records = mutableMapOf<String, ByteArray>()
    fun capture(plaintext: ByteArray): String = "synthetic-actual-response:${records.size + 1}".also {
        records[it] = plaintext.copyOf()
    }
    fun bytes(envelope: String) = requireNotNull(records[envelope]).copyOf()
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) = AeadSealResult.Sealed(capture(plaintext))
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        records[envelope]?.let { AeadOpenResult.Opened(it.copyOf(), 1, false) }
            ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = true
    override fun createFreshAfterVerifiedPurge() = true
}
