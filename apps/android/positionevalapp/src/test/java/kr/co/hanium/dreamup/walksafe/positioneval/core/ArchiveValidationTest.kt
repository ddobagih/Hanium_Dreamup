package kr.co.hanium.dreamup.walksafe.positioneval.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveValidationTest {
    @Test
    fun rejectsZipSlipEvenThoughOutputUsesGeneratedNames() {
        val archive = zip("../../outside" to "bad")
        val staging = File(archive.parentFile, "staging-${System.nanoTime()}")
        assertThrows(IllegalArgumentException::class.java) { SafeZipExtractor.extract(archive, staging) }
    }

    @Test
    fun validatesRinexTwoObservationHeaderAndOverlap() {
        val file = File.createTempFile("rinex", ".obs")
        file.writeText(
            buildString {
                append("     2.11           O                   RINEX VERSION / TYPE\n")
                append("  4    C1    L1    D1    S1                  # / TYPES OF OBSERV\n")
                append("  -3040000.0  4040000.0  3860000.0           APPROX POSITION XYZ\n")
                append("  2025     1     2     3     4    5.0000000     GPS         TIME OF FIRST OBS\n")
                append("                                                            END OF HEADER\n")
                append(" 25  1  2  3  4  5.0000000  0  1G01\n")
                append(" 25  1  2  3 59 35.0000000  0  1G01\n")
            },
        )
        val start = LocalDateTime.of(2025, 1, 2, 3, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val header = RinexHeaderValidator.validate(file, start, start + 3_600_000L)
        assertEquals('O', header.type)
        assertEquals(2.11, header.version, 0.001)
        assertTrue(header.lastObservationUtcMs!! > header.firstObservationUtcMs!!)
    }

    @Test
    fun safelyExpandsGzipAndUnixCompressFixtures() {
        val plain = "fixed gzip fixture\n".toByteArray()
        val gzip = File.createTempFile("rinex", ".gz")
        GZIPOutputStream(FileOutputStream(gzip)).use { it.write(plain) }
        val gzipOutput = SafeRinexFileExtractor.extract(
            gzip,
            "SUWN247j.25O.gz",
            File(gzip.parentFile, "gzip-${System.nanoTime()}"),
        )
        assertEquals("fixed gzip fixture\n", gzipOutput.file.readText())

        val unixZ = File.createTempFile("rinex", ".Z")
        unixZ.writeBytes(Base64.getDecoder().decode(UNIX_Z_FIXTURE_BASE64))
        val unixOutput = SafeRinexFileExtractor.extract(
            unixZ,
            "SUWN247j.25o.Z",
            File(unixZ.parentFile, "unix-z-${System.nanoTime()}"),
        )
        assertEquals(10_240L, unixOutput.byteCount)
        assertEquals("8e081a961b6a97f68e19cf6e26f3b85e04ffef27dbe9c4ad664d79a7ced06a38", unixOutput.sha256)
        assertFalse(unixOutput.file.readBytes().take(2) == listOf(0x1f.toByte(), 0x9d.toByte()))
    }

    @Test
    fun rejectsCompressionExtensionWithWrongMagic() {
        val fake = File.createTempFile("rinex", ".Z").apply { writeText("not compressed") }
        assertThrows(IllegalArgumentException::class.java) {
            SafeRinexFileExtractor.extract(fake, "SUWN247j.25o.Z", File(fake.parentFile, "bad-${System.nanoTime()}"))
        }
    }

    @Test
    fun observationTimesHonorGpsAndGlonassTimeSystems() {
        val declared = LocalDateTime.of(2025, 1, 2, 3, 4, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gps = validateObservation(system = "GPS")
        val glonass = validateObservation(system = "GLO")
        assertEquals(declared - 18_000L, gps.firstObservationUtcMs)
        assertEquals(declared - 18_000L, gps.lastObservationUtcMs)
        assertEquals(declared, glonass.firstObservationUtcMs)
    }

    @Test
    fun rejectsBodyWithoutMeasurementEpochs() {
        listOf("not observation data\n", " 25  1  2  3  4  5.0  4  1\n", " 25  1  2  3  4  5.0  0  0\n").forEach { body ->
            assertThrows(IllegalArgumentException::class.java) { validateObservation(body = body) }
        }
    }

    @Test
    fun rejectsDuplicateAndReversedObservationEpochs() {
        listOf("4.0", "5.0").forEach { second ->
            assertThrows(IllegalArgumentException::class.java) {
                validateObservation(body = " 25  1  2  3  4  5.0  0  1G01\n 25  1  2  3  4  $second  0  1G01\n")
            }
        }
    }

    @Test
    fun usesActualEpochRangeEvenWhenHeaderDifferenceIsWithinTolerance() {
        val header = validateObservation(last = "  2025     1     2     3     6    5.0000000")
        assertEquals(header.firstObservationUtcMs, header.lastObservationUtcMs)
    }

    @Test
    fun rejectsHeaderThatFabricatesAnHourOfCoverage() {
        assertThrows(IllegalArgumentException::class.java) {
            validateObservation(last = "  2025     1     2     3    59   35.0000000")
        }
    }

    @Test
    fun rejectsUnsupportedObservationTimeSystem() {
        assertThrows(IllegalArgumentException::class.java) { validateObservation(system = "JST") }
    }

    private fun validateObservation(
        system: String = "GPS",
        last: String? = null,
        body: String = " 25  1  2  3  4  5.0  0  1G01\n",
    ): RinexHeader {
        val file = File.createTempFile("rinex-time", ".obs")
        try {
            file.writeText(buildString {
                append(("     2.11".padEnd(20) + "O".padEnd(20) + "G").padEnd(60) + "RINEX VERSION / TYPE\n")
                append("  4    C1    L1    D1    S1".padEnd(60) + "# / TYPES OF OBSERV\n")
                append("  -3040000.0  4040000.0  3860000.0".padEnd(60) + "APPROX POSITION XYZ\n")
                append(("  2025     1     2     3     4    5.0000000".padEnd(48) + system).padEnd(60) + "TIME OF FIRST OBS\n")
                if (last != null) append((last.padEnd(48) + system).padEnd(60) + "TIME OF LAST OBS\n")
                append("".padEnd(60) + "END OF HEADER\n")
                append(body)
            })
            val start = LocalDateTime.of(2025, 1, 2, 3, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            return RinexHeaderValidator.validate(file, start, start + 3_600_000L)
        } finally {
            file.delete()
        }
    }

    private fun zip(vararg entries: Pair<String, String>): File = File.createTempFile("archive", ".zip").apply {
        ZipOutputStream(FileOutputStream(this)).use { output ->
            entries.forEach { (name, body) ->
                output.putNextEntry(ZipEntry(name))
                output.write(body.toByteArray())
                output.closeEntry()
            }
        }
    }

    companion object {
        private const val UNIX_Z_FIXTURE_BASE64 = "H52QdMrMoRPDBZ42bAAoXMiwocOHECNKnEixIoyLMGzQoAEAI4wbNmp0xAhSpEeMMWLQkAEgxscYIWnUsCHDRscYMmrCAABiZ8WfQIMKHeqwzsAwckCAAEBnTB05ZOgQfdj0adSpWLNq3cq1q1esPH4cZAPCThk5c9K8cQOiB4gTBWGc+OGjgQIeIYg8GUIlC5QiIMasdVNmDB21bubUvSvYDWHDiBXb5UG5Mg8QSIoEIaIj4EAyYeiEeeGZjkGECnqoXt1DgVKlYebY9UG7tg8QN2qMEWNjhhkzNy7WmLGxBo4cYmrcIBPjxowZOHjTuIHDNw4zNGbAGNNZIB3Qokl7P83GtVIejR8fXrv49WseSogIGfIFyhMpVHzEqCEjBo8X8c1X33352eXeeUg8MQUVTgTRRBH6weCCDDTg4AINEqbk3wsJLtjgg+0dyMMQgxW2nhtU5AFHGT4EOMR/JDpmImIprhiiezwQUQYcbLyRBxSxzXHHG1D5YEYYbMxRxn868ugjkHMISSQZN75n1Fk+hAEHHHPIYcd/V8pR5XkxqoeYE2G0waKOdrQgH4wlQrYWmmqOCQIPUyRBhA9PuHRGEk5smOeeBoqoBBlijEGEHGmYJSaRYYzBRhkuqIHoGC6QwaijLjwhR6STLtroWf8dmqiojtrJw31BDMFEEV+4+EVff/lABxppuPEfq67CKiutEBZ65wvpzcjeZMTGeaJkCnzl7LPQRivttNRWa+212FJUmgzkZeutRB5pxJFHJY10UbknoZRTSy/FNFNNN+U0E08+fWtvRUaJltRSVUElFVf9XnXvwAQX3FVYY5V1VlprtfVWXHMthpdefPkFWLFyJiaxmrmOtVgCPEx6FggvSPwCx2543KzBLLfs8sswxyzzzDTXbPPNOOes88489+zzz0AHLfTQRBdt9NFIJ6300kw37fTTUEct9dRUV2311VhnrfXWXHft9ddghy322GSXbfbZaKet9tpst+3223DHLffcdNdt991456333nz37fffgAcuOM0="
    }
}
