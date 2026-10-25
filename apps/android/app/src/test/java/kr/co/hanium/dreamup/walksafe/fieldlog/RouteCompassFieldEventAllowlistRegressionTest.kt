package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.navigation.EarthOrientationAccuracy
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeading
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassVectorSample
import kr.co.hanium.dreamup.walksafe.navigation.RouteGuidanceDiagnostics
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real mapper -> explicit Field start -> actual encrypted record. Software, not native/Main. */
class RouteCompassFieldEventAllowlistRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualCompassMapsKeepThirteenDiagnosticFieldsAndSameCueInEncryptedEvents() {
        val f = fixture("roundtrip")
        val navigator = RouteNavigator()
        val cases = listOf(fallback(-40.0), fallback(40.0)).map { mapped(it, navigator) }
        val mappedKeys = cases.flatMap { case ->
            case.generated.filter { it.key in DIAGNOSTIC_KEYS && it.value != null }.keys
        }.toSet()
        assertEquals(DIAGNOSTIC_KEYS, mappedKeys)
        // Inclination rejection happens before rear projection; a normal result has no rejection.
        assertNull(cases[0].generated["compass_rejected_check"])
        assertNull(cases[1].generated["rear_horizontal_permille"])
        assertEquals(2, cases.map { it.generated["cue_id"] }.toSet().size)
        assertEquals(SESSION_ID, f.log.startAfterUserConfirmation())
        cases.forEach { case ->
            f.log.recordEvent("route_guidance_generated", case.generated)
            // Existing event name means a start CHECK, not physical playback or a TTS receipt.
            f.log.recordEvent("route_guidance_started", case.startChecked)
        }
        f.log.stop()
        val records = f.crypto.readRecords(File(f.root, SESSION_ID))
        val generated = records.filter { it.optString("event_name") == "route_guidance_generated" }
        val started = records.filter { it.optString("event_name") == "route_guidance_started" }
        assertEquals(2, generated.size); assertEquals(2, started.size)
        val storedKeys = generated.flatMap { row -> row.getJSONObject("fields").keys().asSequence()
            .filter { it in DIAGNOSTIC_KEYS }.toList() }.toSet()
        val missing = mappedKeys - storedKeys
        System.getenv("WALKSAFE_FIELD_COMPASS_EVIDENCE")?.let { destination ->
            val directory = File(destination).apply { mkdirs() }
            val rows = cases.indices.map { index -> JSONObject()
                .put("expected", JSONObject(cases[index].generated.filterValues { it != null }))
                .put("persisted", generated[index].getJSONObject("fields"))
                .put("start_check", started[index].getJSONObject("fields")) }
            File(directory, "roundtrip-observation.json").writeText(JSONObject()
                .put("mapped_unique_diagnostic_keys", JSONArray(mappedKeys.sorted()))
                .put("persisted_unique_diagnostic_keys", JSONArray(storedKeys.sorted()))
                .put("missing_unique_diagnostic_keys", JSONArray(missing.sorted()))
                .put("rows", JSONArray(rows)).toString() + "\n")
            File(f.root, "$SESSION_ID/records-0001.jsonl")
                .copyTo(File(directory, "encrypted-route-events.jsonl"), overwrite = false)
            File(directory, "decrypted-route-events.jsonl").writeText(
                (generated + started).joinToString("\n", postfix = "\n") { it.toString() })
        }
        cases.forEachIndexed { index, case ->
            val fields = generated[index].getJSONObject("fields")
            case.generated.filterValues { it != null }.forEach { (key, expected) ->
                assertTrue("generated[$index].$key was dropped", fields.has(key))
                assertEquals("generated[$index].$key", expected.toString(), fields.get(key).toString())
            }
            assertEquals("CURRENT_SEGMENT", fields.getString("reason"))
            assertEquals(case.compass.reason.name, fields.getString("compass_reason"))
            assertEquals(case.generated["cue_id"], fields.getString("cue_id"))
            assertEquals(case.generatedAtMs, fields.getLong("elapsed_realtime_ms"))
            case.startChecked.filterValues { it != null }.forEach { (key, expected) ->
                assertEquals(expected.toString(), started[index].getJSONObject("fields").get(key).toString())
            }
            assertEquals(fields.getString("cue_id"), started[index].getJSONObject("fields").getString("cue_id"))
            assertFalse(fields.has("heading_accuracy_deg")) // Fallback never invents angular accuracy.
        }
        assertEquals(emptySet<String>(), missing)
        val ciphertext = File(f.root, "$SESSION_ID/records-0001.jsonl").readText()
        assertFalse(ciphertext.contains("magnetic_inclination_deg"))
        assertFalse(ciphertext.contains("route_guidance_generated"))
    }

    @Test fun unstartedAndRealFieldPrivacyFencesAppendNoDiagnosticEvents() {
        listOf("unstarted", "initial-raw", "initial-account", "withdrawn", "account-memory",
            "account-durable").forEach { boundary ->
            val f = fixture(boundary, raw = boundary == "initial-raw", account = boundary == "initial-account")
            val fields = mapped(fallback(40.0)).generated
            when (boundary) {
                "initial-raw", "initial-account" -> assertNull(f.log.startAfterUserConfirmation())
                "unstarted" -> Unit
                else -> {
                    assertEquals(SESSION_ID, f.log.startAfterUserConfirmation())
                    when (boundary) {
                        "withdrawn" -> assertTrue(f.log.blockForRawSourceWithdrawal())
                        "account-memory" -> f.log.blockNewProcessingForAccountDeletion()
                        "account-durable" -> assertTrue(f.log.blockForAccountDeletion())
                    }
                }
            }
            val before = fileState(f.root)
            f.log.recordEvent("route_guidance_generated", fields)
            assertEquals("no post-fence I/O boundary=$boundary", before, fileState(f.root))
            val events = f.crypto.readRecords(File(f.root, SESSION_ID))
            assertFalse(events.any { it.optString("event_name") == "route_guidance_generated" })
            f.log.close()
        }
    }

    @Test fun coordinatesVoiceUnknownKeysAndNonScalarValuesStayRejected() {
        val f = fixture("scalar-controls")
        assertEquals(SESSION_ID, f.log.startAfterUserConfirmation())
        val mapped = mapped(fallback(40.0)).generated
        val forbidden = mapOf("latitude" to 37.123456789, "longitude" to 127.987654321,
            "voice_text" to "private speech", "instruction" to "private destination",
            "unknown_field" to 7, "sensor_vector" to doubleArrayOf(1.0, 2.0, 3.0))
        f.log.recordEvent("route_guidance_generated", mapped + forbidden)
        listOf(doubleArrayOf(1.0, 2.0, 3.0), listOf(50), mapOf("x" to 50)).forEach { value ->
            f.log.recordEvent("route_compass_state_changed", mapOf("magnetic_field_ut" to value,
                "compass_input_age_ms" to 10L))
        }
        f.log.recordEvent("route_compass_state_changed", mapOf("compass_reason" to "private speech",
            "magnetic_field_ut" to null))
        f.log.stop()
        val records = f.crypto.readRecords(File(f.root, SESSION_ID))
        val generated = records.first { it.optString("event_name") == "route_guidance_generated" }
            .getJSONObject("fields")
        forbidden.keys.forEach { assertFalse("forbidden key=$it", generated.has(it)) }
        mapped.filterValues { it != null }.forEach { (key, expected) ->
            assertEquals(expected.toString(), generated.get(key).toString())
        }
        val rejected = records.filter { it.optString("event_name") == "route_compass_state_changed" }
        assertEquals(4, rejected.size)
        rejected.take(3).forEach { row ->
            val fields = row.getJSONObject("fields")
            assertEquals(setOf("compass_input_age_ms"), fields.keys().asSequence().toSet())
            assertEquals(10L, fields.getLong("compass_input_age_ms"))
        }
        assertEquals(0, rejected.last().getJSONObject("fields").length())
        val decoded = records.joinToString("\n") { it.toString() }
        forbidden.keys.forEach { assertFalse(decoded.contains(it)) }
        assertFalse(decoded.contains("private speech")); assertFalse(decoded.contains("private destination"))
    }

    @Test fun missingAndEarlierRejectedChecksDoNotInventUnevaluatedNumbers() {
        val f = fixture("null-controls")
        assertEquals(SESSION_ID, f.log.startAfterUserConfirmation())
        val compass = listOf(RouteCompassHeading.evaluate(null, 0.0, NOW), fallback(-80.0, -60.0))
        val cases = compass.map { mapped(it) }
        cases.forEach { f.log.recordEvent("route_guidance_generated", it.generated) }
        f.log.stop()
        val rows = f.crypto.readRecords(File(f.root, SESSION_ID))
            .filter { it.optString("event_name") == "route_guidance_generated" }
        cases.forEachIndexed { index, case ->
            val fields = rows[index].getJSONObject("fields")
            case.generated.filterValues { it == null }.keys.forEach { assertFalse("not evaluated $it", fields.has(it)) }
            case.generated.filterValues { it != null }.forEach { (key, expected) ->
                assertEquals(expected.toString(), fields.get(key).toString())
            }
            assertEquals(case.compass.reason.name, fields.getString("compass_reason"))
        }
    }

    private data class Fixture(val root: File, val crypto: TestFieldAead, val log: PersistentFieldSessionLog)
    private fun fixture(name: String, raw: Boolean = false, account: Boolean = false): Fixture {
        val root = temporary.newFolder(name)
        val crypto = TestFieldAead()
        val log = PersistentFieldSessionLog(root, FieldSessionDeviceInfo("test", "test", "test", "test-commit"),
            aead = crypto.aead, nowMillis = { 50_000L }, idFactory = { SESSION_ID },
            initiallyBlockedForAccountDeletion = account, initiallyBlockedForRawSourceCollection = raw)
        return Fixture(root, crypto, log)
    }
    private data class Mapped(val compass: RouteCompassHeadingResult, val generated: Map<String, Any?>,
        val startChecked: Map<String, Any?>, val generatedAtMs: Long)
    private fun mapped(compass: RouteCompassHeadingResult, navigator: RouteNavigator = RouteNavigator()): Mapped {
        navigator.setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400), listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), "안내", 200, 200, turnType = 13))))
        val location = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, NOW) }
        // A separate controlled earlier measurement warms the cold nominal clock. The supplied
        // result and all its original sensor time/error/check fields remain unchanged.
        compass.observation?.let { current ->
            val preceding = RouteFacingObservation(current.degreesTrueNorth, current.headingAccuracyDegrees,
                NOW - 100L, current.source, current.sensorQuality, current.reportedHeadingAccuracyDegrees,
                current.angularRateDegreesPerSecond, current.magneticFieldSuspect)
            navigator.update(location.copy(elapsedRealtimeMs = NOW - 100L), NOW - 100L, false,
                facingObservation = preceding)
        }
        var update = navigator.update(location, NOW, false, facingObservation = compass.observation,
            facingUnavailableReason = compass.reason)
        var generatedAt = NOW + 10L
        if (update.speechCueToken == null) {
            // Missing/rejected directions produce their recovery cue after the actual fixed grace.
            // Preserve their original observation, rather than retimestamping it as a recovery.
            update = requireNotNull(navigator.retryGuidance(NOW + 1_500L, compass.observation, compass.reason))
            generatedAt = NOW + 1_510L
        }
        requireNotNull(update.speechCueToken)
        return Mapped(compass, RouteGuidanceDiagnostics.guidanceGenerated(update, generatedAt, compass),
            RouteGuidanceDiagnostics.guidanceStartChecked(update, generatedAt + 10L,
                navigator.isInstructionCurrent(update, generatedAt + 10L, compass.observation)), generatedAt)
    }
    private fun fallback(y: Double, z: Double = -30.0) = RouteCompassHeading.evaluateFallback(
        gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, NOW, EarthOrientationAccuracy.HIGH),
        accelerometer = null, magnetic = RouteCompassVectorSample(0.0, y, z, NOW, EarthOrientationAccuracy.HIGH),
        reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0), nowMs = NOW)
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fileState(root: File): Map<String, String> = root.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(root).path to MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) } }

    private companion object {
        const val NOW = 1_000L
        const val SESSION_ID = "field-compass-diagnostics"
        val DIAGNOSTIC_KEYS = setOf("compass_input_age_ms", "compass_rejected_check",
            "rear_horizontal_permille", "magnetic_field_ut", "expected_magnetic_field_ut",
            "magnetic_field_deviation_ut", "magnetic_field_allowance_ut", "magnetic_horizontal_permille",
            "magnetic_inclination_deg", "expected_magnetic_inclination_deg",
            "magnetic_inclination_residual_deg", "magnetic_inclination_allowance_deg", "compass_reason")
    }
}
