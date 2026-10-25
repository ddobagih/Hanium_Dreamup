package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Component runner only: do not start MainActivity or another live snapshot Store in this process.
 * Uses real SharedPreferences, AndroidKeyStore AES-GCM, and the production parser/Store constructor.
 * The two literal bodies are unchanged captured Backend DTO outputs; this test performs no HTTP call.
 * Each input owns fresh UUID preferences and two exact key aliases (key and generation tombstone).
 * It does not touch user preferences/default keys or assert entrance, floor, or field accessibility.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedRouteRoadTypePlatformRoundTripTest {
    @Test(timeout = 90_000L)
    fun providedRoadTypeAndNullableControlSurviveNativeEncryptedArchiveRecreation() {
        // One JUnit method, two independently owned archives; null is not a provider raw-null response.
        val inputs = listOf(ROAD_16_RESPONSE to 16, PROVIDER_ABSENT_RESPONSE to null)
        val request = WalkingRouteRequest(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001), "STAIR_AVOID")
        val evidence = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, listOf(
            DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, request.destination),
            DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT,
                RoutePoint(37.00002, 127.00103, "시험 후보")),
        ))
        inputs.forEach { (body, expectedRoadType) ->
            withNativeArchive { archive ->
                val route = parseWalkingRoute(JSONObject(body), request)
                assertEquals(expectedRoadType, route.steps.single().roadType)
                assertEquals(WalkingRouteSummary(90, 80), route.summary)
                assertEquals(listOf(request.origin, request.destination), route.polyline)
                assertEquals(15, route.steps.single().facilityType)
                assertEquals("보행길", route.steps.single().roadName)
                assertEquals("SP", route.guidePoints.single().pointType)

                val original = archive.newStore()
                assertTrue(original.bindAccount(archive.owner))
                assertTrue(original.beginWalk(archive.scope))
                assertTrue(original.saveFirstRoute(archive.scope, route, request.destination,
                    destinationCoordinateEvidence = evidence))

                val envelope = checkNotNull(archive.preferences.getString(SNAPSHOT_KEY, null))
                assertTrue(JSONObject(envelope).getString("ciphertext").isNotEmpty())
                assertTrue(archive.keyStore().containsAlias(archive.alias))
                assertTrue(archive.preferencesFile.exists())
                val disk = archive.preferencesFile.readText()
                assertTrue(disk.contains("ciphertext"))
                listOf(archive.owner.actorId, archive.scope.walkSessionId, "road_type", "보행길").forEach { plain ->
                    assertFalse(envelope.contains(plain))
                    assertFalse(disk.contains(plain))
                }

                // New real AEAD and Store instances decrypt the committed archive under a fresh walk lease.
                original.detach()
                assertNull(original.load(archive.scope))
                val recreated = archive.newStore()
                assertTrue(recreated.bindAccount(archive.owner))
                assertNull(recreated.load(archive.scope))
                assertTrue(recreated.beginWalk(archive.scope))
                val loaded = recreated.load(archive.scope)
                assertNotNull("Real encrypted archive must decrypt after Store recreation", loaded)
                val snapshot = checkNotNull(loaded)
                assertEquals(archive.scope, snapshot.scope)
                assertEquals(request.destination, snapshot.destination)
                assertEquals(evidence, snapshot.destinationCoordinateEvidence)
                // Full equality also protects guide/step order, geometry, summary, names, and source.
                assertEquals(route, snapshot.route)
                assertEquals(expectedRoadType, snapshot.route.steps.single().roadType)
                assertEquals(envelope, archive.preferences.getString(SNAPSHOT_KEY, null))
            }
        }
    }

    private fun withNativeArchive(block: (NativeArchive) -> Unit) {
        assertFalse("Native Store I/O must not run on the main looper", Looper.myLooper() == Looper.getMainLooper())
        val archive = NativeArchive(InstrumentationRegistry.getInstrumentation().targetContext)
        var originalFailure: Throwable? = null
        try {
            archive.prepare()
            block(archive)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try { archive.cleanup() } catch (cleanupFailure: Throwable) {
                val primary = originalFailure
                if (primary == null) throw cleanupFailure
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private class NativeArchive(private val context: Context) {
        private val id = UUID.randomUUID().toString()
        private val preferencesName = "walksafe_road_type_platform_test_$id"
        val preferencesFile = File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml")
        private val policy = AeadKeyPolicy("walksafe.instrumentation.road_type.$id.v", 1, setOf(1))
        val alias = policy.alias(1)
        private val generationAlias = "$alias.generation"
        val owner = RouteSnapshotOwner("road-type-native-actor-$id", "road-type-native-generation-$id")
        val scope = RouteSnapshotScope(owner, "road-type-native-walk-$id")
        lateinit var preferences: SharedPreferences
            private set
        private val stores = mutableListOf<EncryptedRouteSnapshotStore>()
        private var ownsPreferences = false
        private var ownsAliasNamespace = false

        fun prepare() {
            assertFalse(preferencesFile.exists())
            val keys = keyStore()
            assertFalse(keys.containsAlias(alias))
            assertFalse(keys.containsAlias(generationAlias))
            ownsAliasNamespace = true
            preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            assertTrue(preferences.all.isEmpty())
            ownsPreferences = true
        }

        fun newStore() = EncryptedRouteSnapshotStore(
            preferences = preferences,
            aead = AndroidKeyStoreAead(policy),
            bootCount = ::currentBootCount,
        ).also { stores.add(it) }

        private fun currentBootCount(): Int = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT)
        }.getOrDefault(UNKNOWN_BOOT_COUNT).takeIf { it >= 0 } ?: UNKNOWN_BOOT_COUNT

        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun cleanup() {
            val failures = mutableListOf<Throwable>()
            fun attempt(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let { failures.add(it) } }
            if (ownsPreferences) {
                attempt { assertTrue(stores.lastOrNull()?.bindAccount(null) != false) }
                stores.forEach { store -> attempt { store.detach() } }
                attempt { assertTrue(preferences.edit().clear().commit()) }
                attempt {
                    assertTrue(context.deleteSharedPreferences(preferencesName))
                    assertFalse(preferencesFile.exists())
                }
            }
            if (ownsAliasNamespace) {
                listOf(alias, generationAlias).forEach { ownedAlias -> attempt {
                    val keys = keyStore()
                    if (keys.containsAlias(ownedAlias)) keys.deleteEntry(ownedAlias)
                    assertFalse(keys.containsAlias(ownedAlias))
                } }
            }
            if (failures.isNotEmpty()) {
                val failure = AssertionError("Owned native road-type fixture cleanup failed: ${failures.size}")
                failures.forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    companion object {
        private const val SNAPSHOT_KEY = "encrypted_route_snapshot_v1"
        // actual provider fixture -> normalizer -> WalkingRouteResponse.model_dump_json, step index 0.
        // Body source: src/test/resources/navigation/backend-road-type/road-16.response.json
        // SHA256 e0b07faaec44414de8dc13ff6011f3218e90b961926f205a888a1825229ffc54
        private val ROAD_16_RESPONSE = """{"schema_version":"walksafe.walking_route.v1","provider":"tmap_pedestrian","provider_route_id":null,"distance_source":"provider_total_geometry_scaled","priority":"STAIR_AVOID","summary":{"distance_m":90,"duration_s":80},"polyline":[{"latitude":37.0,"longitude":127.0,"name":null},{"latitude":37.0,"longitude":127.001,"name":null}],"steps":[{"index":0,"distance_m":90,"duration_s":80,"points":[{"latitude":37.0,"longitude":127.0,"name":null},{"latitude":37.0,"longitude":127.001,"name":null}],"instruction":"제공된 구간 설명","road_name":"보행길","turn_type":null,"facility_type":15,"road_type":16}],"guide_points":[{"index":0,"point":{"latitude":37.0,"longitude":127.0,"name":null},"instruction":"출발","turn_type":200,"point_type":"SP","facility_type":11,"distance_from_start_m":0,"remaining_distance_m":90,"bearing_deg":89.99969909244078}],"start_gap_m":0,"end_gap_m":0,"provider_result_code":0,"provider_result_message":"OK"}"""
        // Provider omitted roadType -> normalizer -> nullable DTO road_type:null (not provider raw null).
        // Body source: src/test/resources/navigation/backend-road-type/provider-absent.response.json
        // SHA256 69c9fa17d57c14119373c46a5f892942dbc25f73eb81138462d8dd5811de061c
        private val PROVIDER_ABSENT_RESPONSE = """{"schema_version":"walksafe.walking_route.v1","provider":"tmap_pedestrian","provider_route_id":null,"distance_source":"provider_total_geometry_scaled","priority":"STAIR_AVOID","summary":{"distance_m":90,"duration_s":80},"polyline":[{"latitude":37.0,"longitude":127.0,"name":null},{"latitude":37.0,"longitude":127.001,"name":null}],"steps":[{"index":0,"distance_m":90,"duration_s":80,"points":[{"latitude":37.0,"longitude":127.0,"name":null},{"latitude":37.0,"longitude":127.001,"name":null}],"instruction":"제공된 구간 설명","road_name":"보행길","turn_type":null,"facility_type":15,"road_type":null}],"guide_points":[{"index":0,"point":{"latitude":37.0,"longitude":127.0,"name":null},"instruction":"출발","turn_type":200,"point_type":"SP","facility_type":11,"distance_from_start_m":0,"remaining_distance_m":90,"bearing_deg":89.99969909244078}],"start_gap_m":0,"end_gap_m":0,"provider_result_code":0,"provider_result_message":"OK"}"""
    }
}
