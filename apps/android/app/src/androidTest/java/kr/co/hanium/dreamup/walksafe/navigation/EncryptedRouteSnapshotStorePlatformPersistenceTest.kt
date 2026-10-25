package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Component runner only, without MainActivity or another live route Store in this process:
 * the production preferences constructor uses RouteSnapshotProcessCoordinator.shared.
 * Each method owns fresh UUID preferences and key aliases, including the generation tombstone.
 * Uses actual SharedPreferences, AndroidKeyStore AES-GCM, wall/elapsed clocks and boot count when
 * accessible. No fake storage/crypto/clock, testOnly process injection, real user preferences,
 * default route alias, account UI, or automatic guidance restoration is used.
 * Synthetic route geometry proves persistence contracts, not provider/entrance/field correctness.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedRouteSnapshotStorePlatformPersistenceTest {
    @Test(timeout = 90_000L)
    fun nativeFirstRouteCiphertextRoundTripPreservesCoordinateEvidence() = withNativeFixture { fixture ->
        val store = fixture.newStore()
        assertTrue(store.bindAccount(fixture.owner))
        assertTrue(store.beginWalk(fixture.scope))
        val firstRoute = fixture.route("native-first-${fixture.id}")
        fixture.save(store, firstRoute)

        val ciphertext = fixture.envelope()
        val envelope = JSONObject(ciphertext)
        assertEquals(1, envelope.getInt("envelope_version"))
        assertEquals(1, envelope.getInt("key_version"))
        assertTrue(envelope.getString("ciphertext").isNotEmpty())
        assertTrue(fixture.nativeKeyStore().containsAlias(fixture.alias))
        assertTrue(fixture.preferencesFile.isFile)
        val diskXml = fixture.preferencesFile.readText(Charsets.UTF_8)
        assertTrue(diskXml.contains("ciphertext"))
        listOf(fixture.owner.actorId, fixture.scope.walkSessionId, checkNotNull(firstRoute.providerRouteId)).forEach {
            assertFalse("No test route/owner plaintext in stored envelope", ciphertext.contains(it))
            assertFalse("No test route/owner plaintext in its committed preferences file", diskXml.contains(it))
        }

        val loaded = fixture.load(store)
        fixture.assertRoute(loaded, firstRoute)
        assertEquals(fixture.evidence, loaded.destinationCoordinateEvidence)
        assertTrue(loaded.savedAtElapsedRealtimeMs in 0L..SystemClock.elapsedRealtime())
        assertEquals(ROUTE_SNAPSHOT_TTL_MS, loaded.expiresAtEpochMs - loaded.savedAtEpochMs)
        val actualBootCount = fixture.currentBootCount()
        if (actualBootCount >= 0) assertEquals(actualBootCount, loaded.savedBootCount)
        else assertEquals(UNKNOWN_BOOT_COUNT, loaded.savedBootCount)

        // The first-route archive is deliberately not a current-active-route recovery record.
        fixture.save(store, fixture.route("native-reroute-${fixture.id}"))
        assertEquals("Same walk must keep the original encrypted first-route archive", ciphertext, fixture.envelope())
        fixture.assertRoute(fixture.load(store), firstRoute)
        fixture.report("FIRST_ROUTE_PERSISTED ciphertextChars=${ciphertext.length} " +
            "bootCount=$actualBootCount coordinateEvidencePreserved=true activeRouteRecovery=NOT_TESTED")
    }

    @Test(timeout = 90_000L)
    fun nativeStoreRecreationRequiresOwnerAndFreshWalkLease() = withNativeFixture { fixture ->
        val original = fixture.newStore()
        assertTrue(original.bindAccount(fixture.owner))
        assertTrue(original.beginWalk(fixture.scope))
        val firstRoute = fixture.route("native-recreated-${fixture.id}")
        fixture.save(original, firstRoute)
        val ciphertext = fixture.envelope()

        val competing = fixture.newStore()
        assertTrue(competing.bindAccount(fixture.owner))
        assertFalse(competing.beginWalk(fixture.scope.copy(walkSessionId = "native-other-${fixture.id}")))
        listOf(
            fixture.scope.copy(owner = fixture.owner.copy(actorId = "native-other-${fixture.id}")),
            fixture.scope.copy(owner = fixture.owner.copy(accountGeneration = "native-generation-2-${fixture.id}")),
            fixture.scope.copy(walkSessionId = "native-other-${fixture.id}"),
        ).forEach { wrongScope ->
            assertNull(original.load(wrongScope))
            assertEquals(ciphertext, fixture.envelope())
        }

        original.detach()
        assertNull(original.load(fixture.scope))
        val replacement = fixture.newStore() // New real AEAD instance, same owned native aliases.
        assertTrue(replacement.bindAccount(fixture.owner))
        assertNull("Account binding alone must not disclose the archived destination", replacement.load(fixture.scope))
        assertTrue(replacement.beginWalk(fixture.scope))
        fixture.assertRoute(fixture.load(replacement), firstRoute)
        assertEquals(fixture.evidence, fixture.load(replacement).destinationCoordinateEvidence)
        assertFalse(original.saveFirstRoute(fixture.scope, fixture.route("native-late-${fixture.id}"), fixture.destination))
        assertEquals(ciphertext, fixture.envelope())
        fixture.report("DETACH_RECREATE ownerAndFreshLeaseRequired=true staleSaveRejected=true " +
            "sameProcess=true processKillRebootAccountUi=NOT_TESTED")
    }

    @Test(timeout = 90_000L)
    fun nativeMalformedAndAuthenticatedTamperedEnvelopesArePurged() = withNativeFixture { fixture ->
        val store = fixture.newStore()
        assertTrue(store.bindAccount(fixture.owner))
        assertTrue(store.beginWalk(fixture.scope))
        val validRoute = fixture.route("native-integrity-${fixture.id}")
        fixture.save(store, validRoute)
        fixture.assertRoute(fixture.load(store), validRoute)

        val malformed = "{invalid-native-test-envelope"
        fixture.assertCryptoBlocked(malformed, AeadBlockReason.MALFORMED_ENVELOPE)
        assertTrue(fixture.preferences.edit().putString(SNAPSHOT_KEY, malformed).commit())
        assertNull(store.load(fixture.scope))
        assertFalse(fixture.preferences.contains(SNAPSHOT_KEY))
        fixture.report("MALFORMED_ENVELOPE nativeSubtype=MALFORMED_ENVELOPE archivePurged=true")

        fixture.save(store, validRoute)
        fixture.assertRoute(fixture.load(store), validRoute)
        val validEnvelope = fixture.envelope()
        val encoded = JSONObject(validEnvelope).getString("ciphertext")
        val bytes = Base64.getDecoder().decode(encoded)
        assertTrue(bytes.isNotEmpty())
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val changed = Base64.getEncoder().encodeToString(bytes)
        bytes.fill(0)
        val tampered = validEnvelope.replace("\"ciphertext\":\"$encoded\"", "\"ciphertext\":\"$changed\"")
        assertNotEquals(validEnvelope, tampered)
        fixture.assertCryptoBlocked(tampered, AeadBlockReason.AUTHENTICATION_FAILED)
        assertTrue(fixture.preferences.edit().putString(SNAPSHOT_KEY, tampered).commit())
        assertNull(store.load(fixture.scope))
        assertFalse(fixture.preferences.contains(SNAPSHOT_KEY))
        fixture.report("TAMPERED_ENVELOPE nativeSubtype=AUTHENTICATION_FAILED archivePurged=true")

        // Auth failure did not destroy the owned usable key or prevent a normal new archive.
        assertTrue(fixture.nativeKeyStore().containsAlias(fixture.alias))
        fixture.save(store, validRoute)
        fixture.assertRoute(fixture.load(store), validRoute)
        fixture.report("INTEGRITY_NORMAL_CONTROL realNewSealAndDecrypt=true transientKeyAccessFailure=NOT_TESTED")
    }

    private fun withNativeFixture(block: (NativeFixture) -> Unit) {
        assertFalse("Run component I/O on the instrumentation thread, not the UI looper",
            Looper.myLooper() == Looper.getMainLooper())
        val fixture = NativeFixture(InstrumentationRegistry.getInstrumentation().targetContext)
        var originalFailure: Throwable? = null
        try {
            fixture.prepare()
            block(fixture)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try { fixture.cleanup() } catch (cleanupFailure: Throwable) {
                val primaryFailure = originalFailure
                if (primaryFailure == null) throw cleanupFailure
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private class NativeFixture(private val context: Context) {
        val id = UUID.randomUUID().toString()
        private val preferencesName = "walksafe_route_platform_test_$id"
        val preferencesFile = File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml")
        private val policy = AeadKeyPolicy("walksafe.instrumentation.route_snapshot.$id.v", 1, setOf(1))
        val alias = policy.alias(1)
        private val generationAlias = "$alias.generation"
        val owner = RouteSnapshotOwner("native-actor-$id", "native-generation-1-$id")
        val scope = RouteSnapshotScope(owner, "native-walk-$id")
        val destination = RoutePoint(37.001, 127.0, "시험 목적지")
        val evidence = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR, listOf(
            DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, destination.copy(name = null)),
            DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, RoutePoint(37.00102, 127.00003, "시험 후보")),
        ))
        lateinit var preferences: SharedPreferences
            private set
        private val aead = AndroidKeyStoreAead(policy)
        private val stores = mutableListOf<EncryptedRouteSnapshotStore>()
        private var ownsPreferences = false
        private var ownsAliasNamespace = false

        fun prepare() {
            assertFalse("Must never reuse or delete an existing preferences file", preferencesFile.exists())
            val keys = nativeKeyStore()
            assertFalse("Test alias must not exist before this method", keys.containsAlias(alias))
            assertFalse("Test generation alias must not exist before this method", keys.containsAlias(generationAlias))
            ownsAliasNamespace = true
            preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            assertTrue("Must not adopt preexisting preferences memory", preferences.all.isEmpty())
            ownsPreferences = true
            report("PREPARED freshPreferences=true freshKeyAliases=true " +
                "bootCount=${currentBootCount()} thread=${Thread.currentThread().name} defaultAliasTouched=false")
        }

        fun newStore(): EncryptedRouteSnapshotStore = EncryptedRouteSnapshotStore(
            preferences = preferences,
            aead = AndroidKeyStoreAead(policy),
            bootCount = ::currentBootCount,
        ).also { stores.add(it) }

        fun save(store: EncryptedRouteSnapshotStore, route: WalkingRoute) {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val saved = store.saveFirstRoute(scope, route, destination, destinationCoordinateEvidence = evidence)
            report("SAVE returned=$saved durationNs=${SystemClock.elapsedRealtimeNanos() - startedNs} " +
                "polylinePoints=${route.polyline.size} mainLooper=false")
            if (!saved) {
                // Diagnostic only on failure, with the same real provider/owned policy and product AAD.
                val diagnosticBytes = "native-route-platform-diagnostic".toByteArray(Charsets.UTF_8)
                val diagnostic = try { aead.seal(diagnosticBytes, SNAPSHOT_AAD, SNAPSHOT_LIMITS) }
                finally { diagnosticBytes.fill(0) }
                if (diagnostic is AeadSealResult.Blocked) {
                    reportNativeAccessPossibility(diagnostic.reason)
                    report("SAVE_CRYPTO_DIAGNOSTIC blocked=${diagnostic.reason}")
                }
                val subtype = when (diagnostic) {
                    is AeadSealResult.Sealed -> "SEALED"
                    is AeadSealResult.Blocked -> "BLOCKED_${diagnostic.reason}"
                }
                throw AssertionError("STORE_SAVE_REJECTED: native crypto diagnostic=$subtype; classification=UNRESOLVED")
            }
        }

        fun load(store: EncryptedRouteSnapshotStore): StoredRouteSnapshot {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val loaded = store.load(scope)
            report("LOAD returned=${loaded != null} durationNs=${SystemClock.elapsedRealtimeNanos() - startedNs} mainLooper=false")
            if (loaded == null) {
                preferences.getString(SNAPSHOT_KEY, null)?.let { envelope ->
                    when (val diagnostic = aead.open(envelope, SNAPSHOT_AAD, SNAPSHOT_LIMITS)) {
                        is AeadOpenResult.Blocked -> {
                            reportNativeAccessPossibility(diagnostic.reason)
                            report("LOAD_CRYPTO_DIAGNOSTIC blocked=${diagnostic.reason}")
                        }
                        is AeadOpenResult.Opened -> diagnostic.plaintext.fill(0)
                    }
                }
                throw AssertionError("STORE_LOAD_REJECTED: no valid owned route returned; classification=UNRESOLVED")
            }
            return loaded
        }

        fun assertCryptoBlocked(envelope: String, reason: AeadBlockReason) {
            val result = aead.open(envelope, SNAPSHOT_AAD, SNAPSHOT_LIMITS)
            if (result is AeadOpenResult.Opened) result.plaintext.fill(0)
            val observed = when (result) {
                is AeadOpenResult.Blocked -> result.reason.name
                is AeadOpenResult.Opened -> "OPENED"
            }
            report("NEGATIVE_CRYPTO_DIAGNOSTIC expected=$reason actual=$observed classification=UNRESOLVED")
            assertEquals("Native crypto subtype must match this exact malformed/tampered input",
                AeadOpenResult.Blocked(reason), result)
        }

        private fun reportNativeAccessPossibility(reason: AeadBlockReason) {
            if (reason == AeadBlockReason.KEY_ACCESS_FAILED || reason == AeadBlockReason.KEY_MISSING) {
                report("NATIVE_KEYSTORE_ACCESS_DIAGNOSTIC reason=$reason ENV_POSSIBLE " +
                    "classification=UNRESOLVED noSkipOrSyntheticCrypto=true")
            }
        }

        fun assertRoute(snapshot: StoredRouteSnapshot, route: WalkingRoute) {
            assertEquals(scope, snapshot.scope)
            assertEquals(destination, snapshot.destination)
            assertEquals(route.providerRouteId, snapshot.route.providerRouteId)
            assertEquals(route.summary, snapshot.route.summary)
            assertEquals(route.polyline, snapshot.route.polyline)
            assertEquals(evidence, snapshot.destinationCoordinateEvidence)
        }

        fun envelope(): String = checkNotNull(preferences.getString(SNAPSHOT_KEY, null))

        fun currentBootCount(): Int = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT)
        }.getOrDefault(UNKNOWN_BOOT_COUNT).takeIf { it >= 0 } ?: UNKNOWN_BOOT_COUNT

        fun nativeKeyStore(): KeyStore = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        } catch (failure: Exception) {
            throw AssertionError("NATIVE_KEYSTORE_ACCESS_FAILED: ${failure.javaClass.simpleName}; " +
                "ENV_POSSIBLE classification=UNRESOLVED")
        }

        fun route(providerRouteId: String): WalkingRoute = WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(111, 90),
            polyline = listOf(RoutePoint(37.0, 127.0), destination),
            guidePoints = listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0005, 127.0), "직진", 56, 55,
                bearingDeg = 0f, turnType = 11, pointType = "guide", facilityType = 15)),
            steps = listOf(WalkingRouteStep(0, 111, 90, listOf(RoutePoint(37.0, 127.0), destination),
                "직진", "시험길", 11, 15)),
            providerRouteId = providerRouteId,
        )

        fun cleanup() {
            val failures = mutableListOf<Throwable>()
            fun attempt(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let { failures.add(it) } }
            if (ownsPreferences) {
                attempt { assertTrue(stores.lastOrNull()?.bindAccount(null) != false) }
                stores.forEach { store -> attempt { store.detach() } }
                attempt { assertTrue(preferences.edit().clear().commit()) }
                attempt { assertTrue(context.deleteSharedPreferences(preferencesName)); assertFalse(preferencesFile.exists()) }
            }
            if (ownsAliasNamespace) {
                listOf(alias, generationAlias).forEach { ownedAlias -> attempt {
                    val keys = nativeKeyStore()
                    if (keys.containsAlias(ownedAlias)) keys.deleteEntry(ownedAlias)
                    assertFalse(keys.containsAlias(ownedAlias))
                } }
            }
            report("CLEANUP ownPreferences=$ownsPreferences ownAliasNamespace=$ownsAliasNamespace " +
                "failures=${failures.size} userPreferencesDefaultKeysTouched=false")
            if (failures.isNotEmpty()) {
                val failure = AssertionError("NATIVE_FIXTURE_CLEANUP_FAILED: ${failures.size}; classification=UNRESOLVED")
                failures.forEach(failure::addSuppressed)
                throw failure
            }
        }

        fun report(text: String) {
            Log.i("NativeRouteSnapshot", text)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,
                Bundle().apply { putString("stream", "NativeRouteSnapshot $text\n") })
        }
    }

    companion object {
        private const val SNAPSHOT_KEY = "encrypted_route_snapshot_v1"
        // Existing product domain/limits, copied verbatim; no new policy or threshold.
        private val SNAPSHOT_AAD = "kr.co.hanium.dreamup.walksafe|USER|navigation|route-snapshot|schema=1"
            .toByteArray(Charsets.UTF_8)
        private val SNAPSHOT_LIMITS = AeadLimits(2 * 1_024 * 1_024, 2 * 1_024 * 1_024 + 16, 3 * 1_024 * 1_024)
    }
}
