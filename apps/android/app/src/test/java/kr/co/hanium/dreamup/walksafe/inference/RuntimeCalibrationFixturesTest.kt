package kr.co.hanium.dreamup.walksafe.inference

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCalibrationFixturesTest {
    private val assets = File("src/main/assets")
    private fun loader() = RuntimeCalibrationFixtures { path -> File(assets, path).readBytes() }

    @Test
    fun shippedManifestRequiresPositiveEmptyAndRuntimeObservedNearThresholdCandidates() {
        val manifest = loader().loadManifest()
        assertEquals(RuntimeCalibrationFixtures.VERSION, manifest.version)
        assertEquals(RuntimeCalibrationFixtures.MANIFEST_SHA256, manifest.sha256)
        assertEquals(setOf("coco_785", "coco_885", "coco_11149", "black_768"), manifest.fixtures.map { it.id }.toSet())
        assertTrue(manifest.fixtures.all { it.required })
        assertEquals(setOf("coco_785", "coco_885", "coco_11149"), manifest.coverage.positiveFixtureIds)
        assertEquals(setOf("black_768"), manifest.coverage.emptyFixtureIds)
        assertEquals(setOf("coco_885", "coco_11149"), manifest.coverage.nearThresholdFixtureIds)
        assertTrue(manifest.coverage.nearThresholdLimitations.contains("NOT_VERIFIED"))
        assertTrue(manifest.fixtures.filter { RuntimeCalibrationFixtureRole.POSITIVE in it.roles }
            .all { it.groundTruth.any { truth -> !truth.isCrowd } })
    }

    @Test
    fun alteredManifestCannotSilentlyChangeTheRequiredFixtureSet() {
        val bytes = File(assets, "runtime-calibration/manifest.json").readBytes()
        val changed = bytes.toString(Charsets.UTF_8).replace("\"required\": true", "\"required\": false").toByteArray()
        assertThrows(IllegalStateException::class.java) { RuntimeCalibrationFixtures.parseManifest(changed) }
    }

    @Test
    fun sourceJpegBytesAndCreditsAreBoundToTheShippedBundle() {
        val manifest = loader().loadManifest()
        val credits = File(assets, "runtime-calibration/ATTRIBUTION.md").readText()
        var imageBytes = 0L
        manifest.fixtures.filter { it.kind == RuntimeCalibrationFixtureKind.JPEG }.forEach { fixture ->
            val image = File(assets, "runtime-calibration/${fixture.asset}")
            assertEquals(fixture.sha256, RuntimeCalibrationFixtures.sha256(image.readBytes()))
            imageBytes += image.length()
        }
        assertEquals(421877L, imageBytes)
        listOf("Nick Webb", "Edwin Martinez", "Umberto Brayj", "https://creativecommons.org/licenses/by/2.0/")
            .forEach { assertTrue(credits.contains(it)) }
        val root = JSONObject(File(assets, "runtime-calibration/manifest.json").readText())
        val annotationCredit = root.getJSONObject("annotation_attribution")
        assertEquals("COCO Consortium", annotationCredit.getString("creator"))
        assertEquals("CC BY 4.0", annotationCredit.getString("license"))
        assertTrue(credits.contains("https://creativecommons.org/licenses/by/4.0/"))
        val entries = root.getJSONArray("fixtures")
        (0..2).forEach { index ->
            val source = entries.getJSONObject(index).getJSONObject("source")
            assertEquals("CC BY 2.0", source.getString("license"))
            assertTrue(source.getString("photo_page").startsWith("https://www.flickr.com/photos/"))
            assertTrue(source.getString("modifications").contains("without any additional transformation"))
        }
    }

    @Test
    fun emptyFixtureIsGeneratedWithoutBitmapDecodeAndHasAnIndependentArray() {
        val loader = loader()
        val fixture = loader.loadManifest().fixtures.single { it.kind == RuntimeCalibrationFixtureKind.SOLID_BLACK }
        val first = loader.loadArgb(fixture)
        assertEquals(768, first.width)
        assertEquals(768, first.height)
        assertEquals(768 * 768, first.pixels.size)
        assertTrue(first.pixels.all { it == -16777216 })
        first.pixels[0] = 0
        val second = loader.loadArgb(fixture)
        assertEquals(-16777216, second.pixels[0])
        assertFalse(first.pixels === second.pixels)
        assertThrows(IllegalArgumentException::class.java) { loader.loadArgb(fixture.copy(width = 1)) }
    }
}
