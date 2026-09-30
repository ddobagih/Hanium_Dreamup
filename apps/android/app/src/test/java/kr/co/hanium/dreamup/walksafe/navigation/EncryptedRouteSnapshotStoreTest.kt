package kr.co.hanium.dreamup.walksafe.navigation

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.Base64
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class EncryptedRouteSnapshotStoreTest {
    @Test
    fun backendProducedDistanceFixturesSurviveParserAndRecreatedAeadStore() {
        // Captured outputs of the Backend normalizer, with its STAIR_AVOID requests.
        val fixtures = requireNotNull(javaClass.getResourceAsStream("/navigation/s20-28-backend-distance-fixtures.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        fixtures.keys().forEach { name ->
            val fixture = fixtures.getJSONObject(name)
            val requestJson = fixture.getJSONObject("request")
            fun point(key: String) = requestJson.getJSONObject(key).let {
                RoutePoint(it.getDouble("latitude"), it.getDouble("longitude"))
            }
            val request = WalkingRouteRequest(point("origin"), point("destination"))
            val parsed = parseWalkingRoute(fixture.getJSONObject("response"), request)
            val storage = FakeRouteSnapshotStorage()
            val keys = FakeKeyProvider()
            assertTrue(newStore(storage, keys, "backend-fixture").saveFirstRoute(testScope("backend-fixture"), parsed, request.destination, 1_000L))
            val restored = requireNotNull(newStore(storage, keys, "backend-fixture").load(testScope("backend-fixture"), 2_000L)).route

            assertEquals(name, parsed, restored)
            assertEquals(parsed.summary.distanceM.toLong(), restored.steps.sumOf { it.distanceM.toLong() })
            if (name == "source-bound-loop") {
                assertEquals(listOf(0, 11, 22), restored.guidePoints.map { it.distanceFromStartM })
                assertEquals(14, restored.guidePoints[1].turnType)
                assertEquals(restored.guidePoints.first().point, restored.guidePoints.last().point)
            }
            if (name.startsWith("nonuniform-")) {
                assertEquals(listOf(100, 100), restored.steps.map { it.distanceM })
                val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0)).apply { setRoute(restored) }
                val update = navigator.update(
                    TrustedLocation(0.0, 0.00045, 3f, 1_000L), 1_000L, false,
                    facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L),
                )
                // At summary progress 50m, the normalized 100m guide is 50m ahead.
                assertTrue("$name: ${update.instruction}", update.instruction?.contains("50m") == true)
            }
        }
    }

    @Test
    fun recreatedAeadStorePreservesBothSourcesAndDoesNotInventLegacySource() {
        (WalkingRouteDistanceSource.entries + listOf(null)).forEach { source ->
            val storage = FakeRouteSnapshotStorage()
            val keys = FakeKeyProvider()
            val original = route("source-roundtrip").copy(distanceSource = source)
            assertTrue(newStore(storage, keys).saveFirstRoute(testScope("walk-1"), original, destination, 1_000L))

            val plaintext = openSnapshot(storage, keys)
            val routeJson = plaintext.getJSONObject("route")
            if (source == null) assertFalse(routeJson.has("distance_source"))
            else assertEquals(source.wireValue, routeJson.getString("distance_source"))
            val restored = newStore(storage, keys).load(testScope("walk-1"), 2_000L)
            assertEquals(original, restored?.route)
            assertEquals(1_000L + ROUTE_SNAPSHOT_TTL_MS, restored?.expiresAtEpochMs)
        }
    }

    @Test
    fun authenticatedUnknownSourceIsRejectedAfterAeadOpen() {
        val storage = FakeRouteSnapshotStorage()
        val keys = FakeKeyProvider()
        assertTrue(newStore(storage, keys).saveFirstRoute(testScope("walk-1"), route("first"), destination, 1_000L))
        val payload = openSnapshot(storage, keys)
        payload.getJSONObject("route").put("distance_source", "geometry_guess")
        sealSnapshot(storage, keys, payload)
        // A valid envelope reaches route decoding; this is not a ciphertext authentication failure.
        assertEquals("geometry_guess", openSnapshot(storage, keys).getJSONObject("route").getString("distance_source"))

        assertNull(newStore(storage, keys).load(testScope("walk-1"), 2_000L))
        assertNull(storage.envelope)
    }

    @Test
    fun legacySnapshotsWithMissingOrNullSourceRetainOldDistanceTolerance() {
        listOf(false, true).forEach { explicitNull ->
            val storage = FakeRouteSnapshotStorage()
            val keys = FakeKeyProvider()
            val original = route("legacy").let { route ->
                route.copy(
                    steps = route.steps.map { it.copy(distanceM = 110) },
                    guidePoints = route.guidePoints.map { it.copy(remainingDistanceM = 54) },
                )
            }
            assertTrue(newStore(storage, keys).saveFirstRoute(testScope("walk-1"), original, destination, 1_000L))
            if (explicitNull) {
                val payload = openSnapshot(storage, keys)
                payload.getJSONObject("route").put("distance_source", JSONObject.NULL)
                sealSnapshot(storage, keys, payload)
            }
            val envelope = storage.envelope

            assertEquals(original, newStore(storage, keys).load(testScope("walk-1"), 2_000L)?.route)
            assertEquals(envelope, storage.envelope)
        }
    }

    @Test
    fun restoredNormalizedRoutesUseCurrentPositionForRemainingDistance() {
        WalkingRouteDistanceSource.entries.forEach { source ->
            val storage = FakeRouteSnapshotStorage()
            val keys = FakeKeyProvider()
            val original = route("scaled").let { route ->
                route.copy(
                    summary = route.summary.copy(distanceM = 200),
                    distanceSource = source,
                    steps = route.steps.map { it.copy(distanceM = 200) },
                    guidePoints = listOf(WalkingRouteGuidePoint(0, destination, "도착", 200, 0, 0f)),
                )
            }
            assertTrue(newStore(storage, keys).saveFirstRoute(testScope("walk-1"), original, destination, 1_000L))
            val restored = requireNotNull(newStore(storage, keys).load(testScope("walk-1"), 2_000L)).route
            val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0)).apply { setRoute(restored) }
            val midpoint = TrustedLocation(37.0005, 127.0, 3f, 1_000L)
            val update = navigator.update(
                midpoint, nowMs = 1_000L, requestInFlight = false,
                facingObservation = RouteFacingObservation(0.0, 5.0, 1_000L),
            )

            assertTrue(update.instruction.toString(), update.instruction?.contains("100m") == true)
            assertFalse(update.instruction?.contains("56m") == true)
            val backtracked = navigator.update(
                TrustedLocation(37.000475, 127.0, 3f, 2_000L), nowMs = 2_000L, requestInFlight = false,
                facingObservation = RouteFacingObservation(0.0, 5.0, 2_000L),
            )
            assertNotNull(navigator.currentAcceptedRouteMatchFor(2_000L))
            assertTrue(backtracked.instruction.toString(), backtracked.instruction?.contains("105m") == true)
        }
    }

    @Test
    fun storesTheFirstCompleteRouteAsCiphertextAndRestoresItWithin24Hours() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        val route = route(providerRouteId = "first-route")

        assertTrue(store.saveFirstRoute(testScope("walk-1"), route, destination, nowEpochMs = 1_000L))

        val rawEnvelope = requireNotNull(storage.envelope)
        assertFalse(rawEnvelope.contains("walk-1"))
        assertFalse(rawEnvelope.contains("first-route"))
        assertFalse(rawEnvelope.contains("37.001"))
        val ciphertextBytes = Base64.getDecoder().decode(
            JSONObject(rawEnvelope).getString("ciphertext"),
        )
        val ciphertextAsText = String(ciphertextBytes, Charsets.UTF_8)
        assertFalse(ciphertextAsText.contains("walk-1"))
        assertFalse(ciphertextAsText.contains("first-route"))
        assertFalse(ciphertextAsText.contains("walk_session_id"))
        val restored = store.load(testScope("walk-1"), nowEpochMs = 2_000L)
        assertEquals("walk-1", restored?.walkSessionId)
        assertEquals(route, restored?.route)
        assertEquals(destination, restored?.destination)
    }

    @Test
    fun keepsTheFirstRouteForTheSameWalkAndDeletesItAt24Hours() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        assertTrue(store.saveFirstRoute(testScope("walk-1"), route("first"), destination, nowEpochMs = 1_000L))
        assertTrue(store.saveFirstRoute(testScope("walk-1"), route("replacement"), destination, nowEpochMs = 2_000L))

        assertEquals("first", store.load(testScope("walk-1"), nowEpochMs = 3_000L)?.route?.providerRouteId)
        assertNull(store.load(testScope("walk-1"), nowEpochMs = 1_000L + ROUTE_SNAPSHOT_TTL_MS))
        assertNull(storage.envelope)
    }

    @Test
    fun tamperedCiphertextAndSessionEndBothFailClosedToNoRoute() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        assertTrue(store.saveFirstRoute(testScope("walk-1"), route("first"), destination, nowEpochMs = 1_000L))
        val envelope = requireNotNull(storage.envelope)
        val offset = envelope.indexOf("\"ciphertext\":\"") + "\"ciphertext\":\"".length
        storage.envelope = envelope.replaceRange(
            offset,
            offset + 1,
            if (envelope[offset] == 'A') "B" else "A",
        )

        assertNull(store.load(testScope("walk-1"), nowEpochMs = 2_000L))
        assertNull(storage.envelope)

        assertTrue(store.endWalk())
        assertTrue(store.beginWalk(testScope("walk-2")))
        assertTrue(store.saveFirstRoute(testScope("walk-2"), route("second"), destination, nowEpochMs = 3_000L))
        assertTrue(store.endWalk())
        assertNull(store.load(testScope("walk-1"), nowEpochMs = 4_000L))
    }

    @Test
    fun transientKeyAccessFailurePreservesCiphertextUntilRecovery() {
        val storage = FakeRouteSnapshotStorage()
        val keyProvider = FakeKeyProvider()
        val store = newStore(storage, keyProvider)
        val firstRoute = route("first")
        assertTrue(store.saveFirstRoute(testScope("walk-1"), firstRoute, destination, nowEpochMs = 1_000L))
        val originalEnvelope = requireNotNull(storage.envelope)

        keyProvider.failAccess = true
        assertNull(store.load(testScope("walk-1"), nowEpochMs = 2_000L))
        assertEquals(originalEnvelope, storage.envelope)

        keyProvider.failAccess = false
        assertEquals(firstRoute, store.load(testScope("walk-1"), nowEpochMs = 3_000L)?.route)
        assertEquals(originalEnvelope, storage.envelope)
    }

    @Test
    fun malformedEnvelopeDoesNotRestorePlaintextAndIsDeleted() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        storage.envelope = "{\"walk_session_id\":\"walk-1\"}"

        assertNull(store.load(testScope("walk-1"), nowEpochMs = 2_000L))
        assertNull(storage.envelope)
    }

    @Test
    fun reportsFailedTtlAndSessionEndPurges() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        assertTrue(store.saveFirstRoute(testScope("walk-1"), route("first"), destination, nowEpochMs = 1_000L))
        storage.clearSucceeds = false

        assertFalse(store.purgeExpired(nowEpochMs = 1_000L + ROUTE_SNAPSHOT_TTL_MS))
        assertNotNull(storage.envelope)
        assertFalse(store.endWalk())
        assertNotNull(storage.envelope)
    }

    private fun newStore(
        storage: FakeRouteSnapshotStorage,
        keyProvider: FakeKeyProvider = FakeKeyProvider(),
        walkSessionId: String = "walk-1",
    ): EncryptedRouteSnapshotStore {
        return EncryptedRouteSnapshotStore(
            storage = storage,
            aead = newAead(keyProvider),
            testOnly = Unit,
            processCoordinator = storage.process,
        ).also {
            check(it.bindAccount(testOwner))
            check(it.beginWalk(testScope(walkSessionId)))
        }
    }

    private val testOwner = RouteSnapshotOwner("actor-a", "account:1")
    private fun testScope(walk: String) = RouteSnapshotScope(testOwner, walk)

    private fun newAead(keyProvider: FakeKeyProvider): VersionedLocalAead = VersionedLocalAead(
        AeadKeyPolicy(
            aliasPrefix = "walksafe.test.route_snapshot.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        ),
        keyProvider,
    )

    private fun openSnapshot(storage: FakeRouteSnapshotStorage, keys: FakeKeyProvider): JSONObject {
        val result = newAead(keys).open(requireNotNull(storage.envelope), snapshotAad, snapshotLimits)
        return JSONObject(String((result as AeadOpenResult.Opened).plaintext, Charsets.UTF_8))
    }

    private fun sealSnapshot(storage: FakeRouteSnapshotStorage, keys: FakeKeyProvider, payload: JSONObject) {
        val result = newAead(keys).seal(payload.toString().toByteArray(Charsets.UTF_8), snapshotAad, snapshotLimits)
        storage.envelope = (result as AeadSealResult.Sealed).envelope
    }

    private val snapshotAad = "kr.co.hanium.dreamup.walksafe|USER|navigation|route-snapshot|schema=1".toByteArray(Charsets.UTF_8)
    private val snapshotLimits = AeadLimits(2 * 1_024 * 1_024, 2 * 1_024 * 1_024 + 16, 3 * 1_024 * 1_024)

    private fun route(providerRouteId: String): WalkingRoute = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(distanceM = 111, durationS = 90),
        polyline = listOf(RoutePoint(37.0, 127.0), destination),
        guidePoints = listOf(
            WalkingRouteGuidePoint(
                index = 0,
                point = RoutePoint(37.0005, 127.0),
                instruction = "직진",
                distanceFromStartM = 56,
                remainingDistanceM = 55,
                bearingDeg = 0f,
                turnType = 11,
                pointType = "guide",
                facilityType = 15,
            ),
        ),
        steps = listOf(
            WalkingRouteStep(
                index = 0,
                distanceM = 111,
                durationS = 90,
                points = listOf(RoutePoint(37.0, 127.0), destination),
                instruction = "직진",
                roadName = "테스트길",
                turnType = 11,
                facilityType = 15,
            ),
        ),
        providerRouteId = providerRouteId,
    )

    private val destination = RoutePoint(37.001, 127.0, "목적지")
}

private class FakeRouteSnapshotStorage : RouteSnapshotStorage {
    val process = RouteSnapshotProcessCoordinator()
    var envelope: String? = null
    var clearSucceeds = true

    override fun read(): String? = envelope

    override fun write(envelope: String): Boolean {
        this.envelope = envelope
        return true
    }

    override fun clear(): Boolean {
        if (clearSucceeds) envelope = null
        return clearSucceeds
    }
}

private class FakeKeyProvider : LocalAeadKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()
    var failAccess = false

    override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias, ::newKey)

    override fun getExisting(alias: String): SecretKey? {
        if (failAccess) throw IllegalStateException("Key temporarily unavailable")
        return keys[alias]
    }

    override fun delete(alias: String): Boolean {
        keys.remove(alias)
        return true
    }

    override fun createFreshAfterVerifiedPurge(alias: String): SecretKey {
        return newKey().also { keys[alias] = it }
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
