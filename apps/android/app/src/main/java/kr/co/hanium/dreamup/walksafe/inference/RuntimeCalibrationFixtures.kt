package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeCalibrationFixtureKind { JPEG, SOLID_BLACK }

enum class RuntimeCalibrationFixtureRole { POSITIVE, EMPTY, NEAR_THRESHOLD }

data class RuntimeCalibrationGroundTruth(
    val annotationId: Long,
    val appClassId: Int,
    val className: String,
    val bboxNorm: RectNorm,
    val isCrowd: Boolean,
)

data class RuntimeCalibrationFixture(
    val id: String,
    val kind: RuntimeCalibrationFixtureKind,
    val asset: String?,
    val width: Int,
    val height: Int,
    val sha256: String,
    val required: Boolean,
    val roles: Set<RuntimeCalibrationFixtureRole>,
    val groundTruth: List<RuntimeCalibrationGroundTruth>,
)

data class RuntimeCalibrationFixtureCoverage(
    val positiveFixtureIds: Set<String>,
    val emptyFixtureIds: Set<String>,
    val nearThresholdFixtureIds: Set<String>,
    val nearThresholdLimitations: String,
)

data class RuntimeCalibrationFixtureManifest(
    val version: String,
    val sha256: String,
    val fixtures: List<RuntimeCalibrationFixture>,
    val coverage: RuntimeCalibrationFixtureCoverage,
)

/**
 * Loads only the versioned calibration bundle. No camera, inference or application result callback is used.
 * The caller owns the returned ARGB array and calls YuvImagePreprocessor.preprocess(image, modelSize).
 * That preprocessor reuses its buffer: copy or consume the tensor before preparing another fixture.
 */
class RuntimeCalibrationFixtures internal constructor(private val readAsset: (String) -> ByteArray) {
    constructor(context: Context) : this({ path ->
        context.applicationContext.assets.open(path).use { it.readBytes() }
    })

    private val manifest by lazy { parseManifest(readAsset("$ASSET_ROOT/manifest.json")) }

    fun loadManifest(): RuntimeCalibrationFixtureManifest = manifest

    fun loadArgb(fixture: RuntimeCalibrationFixture): ArgbImage {
        require(manifest.fixtures.singleOrNull { it.id == fixture.id } == fixture) {
            "Fixture must belong to the verified runtime calibration manifest"
        }
        return when (fixture.kind) {
            RuntimeCalibrationFixtureKind.JPEG -> decodeJpeg(fixture)
            RuntimeCalibrationFixtureKind.SOLID_BLACK -> {
                val pixels = IntArray(fixture.width * fixture.height) { BLACK_ARGB }
                val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
                bytes.asIntBuffer().put(pixels)
                check(sha256(bytes.array()) == fixture.sha256) { "Generated fixture SHA256 mismatch" }
                ArgbImage(fixture.width, fixture.height, pixels)
            }
        }
    }

    private fun decodeJpeg(fixture: RuntimeCalibrationFixture): ArgbImage {
        val bytes = readAsset("$ASSET_ROOT/${requireNotNull(fixture.asset)}")
        check(sha256(bytes) == fixture.sha256) { "Calibration image SHA256 mismatch" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })) { "Calibration image decode failed" }
        try {
            check(bitmap.width == fixture.width && bitmap.height == fixture.height) {
                "Calibration image dimensions differ from manifest"
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ArgbImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val ASSET_ROOT = "runtime-calibration"
        const val VERSION = "walksafe-runtime-calibration-walkmate21-v2"
        const val MANIFEST_SHA256 = "2bb2e1dd5573f10114911ecf9fad7019a4d670179fafef917f01769249caadac"
        private const val BLACK_ARGB = -16777216
        private val IMAGE_PATH = Regex("images/[0-9]{12}\\.jpg")
        private val HASH = Regex("[0-9a-f]{64}")

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun parseManifest(bytes: ByteArray): RuntimeCalibrationFixtureManifest {
            val hash = sha256(bytes)
            check(hash == MANIFEST_SHA256) { "Runtime calibration manifest SHA256 mismatch" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            check(json.getString("version") == VERSION && json.getInt("model_input_size") == 768)
            val entries = json.getJSONArray("fixtures")
            val fixtures = (0 until entries.length()).map { index ->
                val item = entries.getJSONObject(index)
                val width = item.getInt("width")
                val height = item.getInt("height")
                check(width in 1..1024 && height in 1..1024)
                val kind = RuntimeCalibrationFixtureKind.valueOf(item.getString("kind"))
                val asset = if (item.isNull("asset")) null else item.getString("asset")
                val expectedHash = item.getString("sha256")
                check(HASH.matches(expectedHash))
                check(item.getBoolean("required")) { "Every declared calibration fixture is required" }
                when (kind) {
                    RuntimeCalibrationFixtureKind.JPEG -> {
                        check(asset != null && IMAGE_PATH.matches(asset))
                        check(item.getString("hash_encoding") == "JPEG_BYTES")
                    }
                    RuntimeCalibrationFixtureKind.SOLID_BLACK -> {
                        check(asset == null && width == 768 && height == 768)
                        check(item.getString("hash_encoding") == "ARGB32_BIG_ENDIAN")
                    }
                }
                val roles = strings(item.getJSONArray("roles")).map(RuntimeCalibrationFixtureRole::valueOf).toSet()
                val annotations = item.getJSONArray("ground_truth")
                val groundTruth = (0 until annotations.length()).map { annotationIndex ->
                    val annotation = annotations.getJSONObject(annotationIndex)
                    val box = annotation.getJSONArray("bbox_xywh")
                    check(box.length() == 4)
                    val coordinates = (0..3).map { box.getDouble(it).toFloat() }
                    check(coordinates.all { it.isFinite() } && coordinates[2] > 0f && coordinates[3] > 0f)
                    check(TwoModelClassMap.unifiedWalksafeClassName(annotation.getInt("app_class_id")) ==
                        annotation.getString("app_class_name")) { "Calibration annotation class mapping mismatch" }
                    RuntimeCalibrationGroundTruth(
                        annotation.getLong("annotation_id"), annotation.getInt("app_class_id"),
                        annotation.getString("app_class_name"),
                        RectNorm(coordinates[0] / width, coordinates[1] / height,
                            coordinates[2] / width, coordinates[3] / height), annotation.getInt("iscrowd") != 0,
                    )
                }
                if (RuntimeCalibrationFixtureRole.POSITIVE in roles) check(groundTruth.any { !it.isCrowd })
                if (RuntimeCalibrationFixtureRole.EMPTY in roles) check(groundTruth.isEmpty())
                RuntimeCalibrationFixture(item.getString("id"), kind, asset, width, height, expectedHash,
                    required = true, roles = roles, groundTruth = groundTruth)
            }
            check(fixtures.size == 4 && fixtures.map { it.id }.toSet().size == fixtures.size)
            val declared = json.getJSONObject("coverage")
            val coverage = RuntimeCalibrationFixtureCoverage(
                strings(declared.getJSONArray("positive_fixture_ids")).toSet(),
                strings(declared.getJSONArray("empty_fixture_ids")).toSet(),
                strings(declared.getJSONArray("near_threshold_fixture_ids")).toSet(),
                declared.getString("near_threshold_limitations"),
            )
            listOf(
                RuntimeCalibrationFixtureRole.POSITIVE to coverage.positiveFixtureIds,
                RuntimeCalibrationFixtureRole.EMPTY to coverage.emptyFixtureIds,
                RuntimeCalibrationFixtureRole.NEAR_THRESHOLD to coverage.nearThresholdFixtureIds,
            ).forEach { (role, ids) ->
                check(ids.isNotEmpty() && ids == fixtures.filter { role in it.roles }.map { it.id }.toSet())
            }
            check(coverage.nearThresholdLimitations.isNotBlank())
            return RuntimeCalibrationFixtureManifest(VERSION, hash, fixtures, coverage)
        }

        private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
    }
}
