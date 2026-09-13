package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualBaseRinexImporterTest {
    @Test
    fun plainHourlyObservationAndNavigationRunOfflineUsingHeaderStation() = withWorkspace { root ->
        val inputs = listOf(artifact(root, "KUNW251j.26o", observation()), artifact(root, "KUNW251j.26n", navigation()))
        val result = ManualBaseRinexImporter.prepare(inputs, trace(), File(root, "prepared"))
        assertEquals("KUNW", result.station.code)
        assertEquals(2, result.rinexEntries.size)
        assertTrue(result.downloads.isEmpty())
        assertFalse(result.station.operational)
        assertEquals(0.0, result.station.location.latitudeDeg, 0.001)
    }

    @Test
    fun zipRelativeFoldersAndGzipEntriesPreserveOriginalSources() = withWorkspace { root ->
        val archive = File(root, "portal-download.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hour/KUNW251j.26o.gz"))
            zip.write(gzip(observation()))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("hour/KUNW251j.26n"))
            zip.write(navigation().toByteArray())
            zip.closeEntry()
        }
        val input = imported(archive)
        val result = ManualBaseRinexImporter.prepare(listOf(input), trace(), File(root, "prepared"))
        assertEquals(2, result.rinexEntries.size)
        assertEquals(input.sha256, imported(archive).sha256)
        assertTrue(result.rinexEntries.all { it.file.isFile })
    }

    @Test
    fun directGzipInputsAreAccepted() = withWorkspace { root ->
        val observation = File(root, "kunw251j.26O.gz").apply { writeBytes(gzip(observation())) }
        val navigation = File(root, "kunw251j.26N.gz").apply { writeBytes(gzip(navigation())) }
        assertEquals(2, ManualBaseRinexImporter.prepare(
            listOf(imported(observation), imported(navigation)), trace(), File(root, "prepared"),
        ).rinexEntries.size)
    }

    @Test
    fun rejectsWrongDayMissingNavigationAndFilenameHeaderMismatch() = withWorkspace { root ->
        val obs = artifact(root, "KUNW251j.26o", observation())
        val nav = artifact(root, "KUNW251j.26n", navigation())
        assertInvalid(root, listOf(obs), "missing-nav")
        assertInvalid(root, listOf(obs.copy(displayName = "KUNW250j.26o"), nav), "wrong-day")
        assertInvalid(root, listOf(obs.copy(displayName = "KUNW251j.26n"), nav), "wrong-type")
        assertInvalid(root, listOf(obs, nav.copy(displayName = "OTHER251j.26n")), "wrong-name")
    }

    @Test
    fun rejectsStationMismatchFarStationAndUncoveredWalk() = withWorkspace { root ->
        val obs = artifact(root, "KUNW251j.26o", observation(marker = "SEOU"))
        val nav = artifact(root, "KUNW251j.26n", navigation())
        assertInvalid(root, listOf(obs, nav), "wrong-marker")
        val valid = artifact(root, "valid-observation.bin", observation()).copy(displayName = "KUNW251j.26o")
        val far = assertThrows(NgiiException::class.java) {
            ManualBaseRinexImporter.prepare(listOf(valid, nav), trace().copy(center = GeoPoint(1.0, 0.0)), File(root, "far"))
        }
        assertEquals("BASE_STATION_TOO_FAR", far.reasonCode)
        assertThrows(NgiiException::class.java) {
            ManualBaseRinexImporter.prepare(
                listOf(valid, nav), trace().copy(endUtcEpochMs = START + 1_000_000L), File(root, "uncovered"),
            )
        }
    }

    @Test
    fun rejectsOldNavigationDuplicateFilesAndChangedPrivateCopy() = withWorkspace { root ->
        val obs = artifact(root, "KUNW251j.26o", observation())
        val nav = artifact(root, "KUNW251j.26n", navigation(day = 7))
        assertInvalid(root, listOf(obs, nav), "old-navigation")
        nav.file.writeText(navigation())
        val validNav = imported(nav.file)
        assertInvalid(root, listOf(obs, validNav, obs), "duplicate")
        obs.file.appendText("changed")
        assertInvalid(root, listOf(obs, validNav), "tampered")
    }

    @Test
    fun rejectsIncompleteNavigationRecordAndDeclaredInputLimitBeforeExtraction() = withWorkspace { root ->
        val obs = artifact(root, "KUNW251j.26o", observation())
        val nav = artifact(root, "KUNW251j.26n", navigation().lineSequence().take(3).joinToString("\n"))
        assertInvalid(root, listOf(obs, nav), "short-navigation")
        assertInvalid(root, listOf(obs.copy(byteCount = ManualBaseRinexImporter.MAX_INPUT_BYTES + 1L)), "oversize")
    }

    @Test
    fun rejectsZipSlipNestedZipAndUnexpectedDocumentWithoutLeavingExtractedData() = withWorkspace { root ->
        listOf("../../escape", "nested.zip", "README.txt").forEachIndexed { index, name ->
            val archive = File(root, "bad-$index.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(observation().toByteArray())
                zip.closeEntry()
            }
            assertInvalid(root, listOf(imported(archive)), "bad-$index")
        }
    }

    @Test
    fun cancelledManualPreparationDoesNotCreateOutputOrModifySources() = withWorkspace { root ->
        val obs = artifact(root, "KUNW251j.26o", observation())
        val nav = artifact(root, "KUNW251j.26n", navigation())
        val cancellation = AnalysisCancellation().apply { cancel() }
        val output = File(root, "cancelled")
        assertThrows(AnalysisCancelledException::class.java) {
            ManualBaseRinexImporter.prepare(listOf(obs, nav), trace(), output, cancellation)
        }
        assertFalse(output.exists())
        assertEquals(obs.sha256, imported(obs.file).sha256)
    }

    private fun assertInvalid(root: File, inputs: List<ImportedArtifact>, outputName: String) {
        val output = File(root, outputName)
        val error = assertThrows(NgiiException::class.java) {
            ManualBaseRinexImporter.prepare(inputs, trace(), output)
        }
        assertEquals("BASE_DATA_INVALID", error.reasonCode)
        assertFalse(output.exists())
    }

    private fun observation(marker: String = "KUNW") = buildString {
        append(header("     2.11           OBSERVATION DATA    G", "RINEX VERSION / TYPE"))
        append(header(marker, "MARKER NAME"))
        append(header("  6378137.0 0.0 0.0", "APPROX POSITION XYZ"))
        append(header("     1    C1", "# / TYPES OF OBSERV"))
        append(header("  2026     9     8     9     0   18.0000000     GPS", "TIME OF FIRST OBS"))
        append(header("", "END OF HEADER"))
        for (second in listOf(18, 48, 78)) {
            append(String.format(Locale.ROOT, " 26  9  8  9 %2d %10.7f  0  1G01\n", second / 60, (second % 60).toDouble()))
            append("  12345678.000\n")
        }
    }

    private fun navigation(day: Int = 8) =
        header("     2.11           NAVIGATION DATA     G", "RINEX VERSION / TYPE") +
            header("", "END OF HEADER") + " 1 26  9 $day  9  0 18.0 0.0D+00 0.0D+00 0.0D+00\n" +
            "    0.0D+00 0.0D+00 0.0D+00 0.0D+00\n".repeat(7)

    private fun header(content: String, label: String) = content.padEnd(60) + label + "\n"
    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
    }.toByteArray()
    private fun artifact(root: File, name: String, content: String) = imported(File(root, name).apply { writeText(content) })
    private fun imported(file: File) = ImportedArtifact(
        file.name, file, file.length(), MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
    )
    private fun trace() = TraceSession(emptyList(), START, START + 60_000L, GeoPoint(0.0, 0.0), 0.0)
    private fun withWorkspace(block: (File) -> Unit) {
        val root = createTempDirectory("manual-base-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private companion object {
        val START = LocalDateTime.of(2026, 9, 8, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
