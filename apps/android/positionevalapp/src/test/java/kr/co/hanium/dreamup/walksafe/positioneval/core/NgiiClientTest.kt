package kr.co.hanium.dreamup.walksafe.positioneval.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream

class NgiiClientTest {
    @Test
    fun parsesStationStatusAndCoverageContracts() {
        val stations = NgiiJson.parseStations(
            """{"resultList":[{"ctrlpnt_eng_nm":"SUWN","rinex_nm":"SUWN","ctrlpnt_nm":"수원","ctrlpnt_se":"ngii","oper_sttus":"O","lat":"37-16-31.8529","lon":"127-3-15.2647"}]}""",
        )
        val health = NgiiJson.parseHourlyHealth(
            """{"ctrlpntSttus":[{"ctrlpnt_eng_nm":"SUWN","rinex_hourly_sttus_at":"Y"}]}""",
        )
        val coverage = NgiiJson.parseCoverage(
            """{"resultList":[{"rinex_time_se":"Hourly","rinex_date_hour":"2025090409","data_count":5,"data_count_all":5}]}""",
        )

        assertEquals("SUWN", stations.single().code)
        assertTrue(stations.single().operational)
        assertTrue(health.getValue("SUWN"))
        assertTrue(coverage.single().complete)
        assertEquals(37.27551469, stations.single().location.latitudeDeg, 0.000001)
    }

    @Test
    fun plansDocumentedHourlyArchiveInPortalKoreanHour() {
        val hours = RinexDownloadPlanner.requiredPortalHours(1_756_953_600_000L, 1_756_953_601_000L)
        assertEquals(1, hours.size)
        assertTrue(RinexDownloadPlanner.archiveUrl("SUWN", hours.single()).contains("/RINEX/Hourly/"))
        assertTrue(RinexDownloadPlanner.archiveUrl("SUWN", "2025090409").endsWith("SUWN247j.25z.zip"))
    }

    @Test
    fun rejectsCredentialOutsideExactFileOrigin() {
        val transport = StrictHttpsTransport()
        assertThrows(NgiiException::class.java) {
            transport.validateUri(URI("https://example.com/file/ngii/a"), true)
        }
        assertThrows(NgiiException::class.java) {
            transport.validateUri(URI("https://geodesy.ngii.go.kr/backend/a"), true)
        }
        assertFalse(NgiiJson.parseCoverage("""{"resultList":[]}""").any())
    }

    @Test
    fun plansCurrentDirectoryGzipAndUnixCompressFiles() {
        val files = NgiiJson.parseDirectoryFiles(
            """{"fileList":[
              {"name":"SUWN247j.25o.Z","size":1200},
              {"name":"SUWN247j.25O.gz","size":1100},
              {"name":"SUWN247j.25n.Z","size":500},
              {"name":"SUWN247j.25N.gz","size":450},
              {"name":"SUWN247j.25l.Z","size":400},
              {"name":"../escape","size":1}
            ]}""",
        )
        val planned = RinexDownloadPlanner.remoteFiles("SUWN", "2025090409", files)
        assertEquals(listOf("SUWN247j.25O.gz", "SUWN247j.25N.gz", "SUWN247j.25l.Z"), planned.map { it.name })
        assertTrue(planned.all { !it.archive && it.url.startsWith("https://geodesy.ngii.go.kr/file/ngii/") })
    }

    @Test
    fun fallsBackToNextStationWhenListedBaseFilesAreInvalid() {
        val sessionStart = LocalDateTime.of(2025, 9, 4, 9, 10).toInstant(ZoneOffset.UTC).toEpochMilli()
        val session = TraceSession(
            samples = emptyList(),
            startUtcEpochMs = sessionStart,
            endUtcEpochMs = sessionStart + 10L * 60L * 1000L,
            center = GeoPoint(37.5459, 126.9606),
            timeOffsetSpreadMs = 0.0,
        )
        val result = NgiiPortalClient(FakeNgiiTransport()).downloadNearestCompleteBase(
            session,
            "test-key".toCharArray(),
            File(System.getProperty("java.io.tmpdir"), "ngii-${System.nanoTime()}"),
        )

        assertEquals("BBBB", result.station.code)
        assertEquals(listOf("2025090409"), result.portalHours)
        assertTrue(result.rinexEntries.any { RinexHeaderValidator.validate(it.file, session.startUtcEpochMs, session.endUtcEpochMs).type == 'O' })
        assertTrue(result.rinexEntries.any { RinexHeaderValidator.validate(it.file, session.startUtcEpochMs, session.endUtcEpochMs).type == 'N' })
    }

    @Test
    fun usesHistoricalFilesEvenWhenCurrentStatusAndCalendarAreNegative() {
        val sessionStart = LocalDateTime.of(2025, 9, 4, 9, 10).toInstant(ZoneOffset.UTC).toEpochMilli()
        val session = TraceSession(
            samples = emptyList(),
            startUtcEpochMs = sessionStart,
            endUtcEpochMs = sessionStart + 10L * 60L * 1000L,
            center = GeoPoint(37.5459, 126.9606),
            timeOffsetSpreadMs = 0.0,
        )
        val result = NgiiPortalClient(FakeNgiiTransport()).downloadNearestCompleteBase(
            session,
            "test-key".toCharArray(),
            File(System.getProperty("java.io.tmpdir"), "ngii-history-${System.nanoTime()}"),
        )
        assertEquals("BBBB", result.station.code)
    }

    @Test
    fun rejectsWhenEveryStationIsBeyondThirtyKilometers() {
        val transport = object : StrictHttpsTransport() {
            override fun getText(url: String): String = when {
                "selectTotalCtrlpntList" in url -> stationCatalogJson("FAR1", "35-0-0", "129-0-0")
                "m001/list" in url -> """{"ctrlpntSttus":[]}"""
                else -> """{"resultList":[]}"""
            }
        }
        val session = TraceSession(
            samples = emptyList(),
            startUtcEpochMs = 1_756_980_600_000L,
            endUtcEpochMs = 1_756_980_601_000L,
            center = GeoPoint(37.5, 127.0),
            timeOffsetSpreadMs = 0.0,
        )
        val error = assertThrows(NgiiException::class.java) {
            NgiiPortalClient(transport).downloadNearestCompleteBase(
                session,
                "test-key".toCharArray(),
                File(System.getProperty("java.io.tmpdir"), "ngii-far-${System.nanoTime()}"),
            )
        }
        assertEquals("BASE_STATION_TOO_FAR", error.reasonCode)
    }

    @Test
    fun catalogCacheRequiresFreshSchemaAndIntegrity() {
        var now = 1_800_000_000_000L
        val cacheFile = File(System.getProperty("java.io.tmpdir"), "ngii-cache-${System.nanoTime()}/catalog.json")
        val cache = NgiiStationCatalogCache(cacheFile, clock = { now })
        val catalog = stationCatalogJson("SUWN", "37-16-31.8529", "127-3-15.2647")
        assertTrue(cache.store(catalog))

        val failingTransport = object : StrictHttpsTransport() {
            override fun getText(url: String): String = throw NgiiException("OFFLINE", "offline")
        }
        assertEquals("SUWN", NgiiPortalClient(failingTransport, cache).stationCatalog().single().code)

        cacheFile.writeText(cacheFile.readText().replace("SUWN", "TAMP"))
        assertNull(cache.load())
        assertThrows(NgiiException::class.java) { NgiiPortalClient(failingTransport, cache).stationCatalog() }

        assertTrue(cache.store(catalog))
        now += 31L * 24L * 60L * 60L * 1000L
        assertNull(cache.load())
    }

    @Test
    fun bindsExtractedNameToStationHourAndType() {
        assertEquals('O', RinexDownloadPlanner.rinexTypeForFile("SUWN", "2025090409", "folder/SUWN247j.25o"))
        assertNull(RinexDownloadPlanner.rinexTypeForFile("SUWN", "2025090409", "OTHER247j.25o"))
        assertNull(RinexDownloadPlanner.rinexTypeForFile("SUWN", "2025090409", "SUWN247k.25o"))
    }

    private class FakeNgiiTransport : StrictHttpsTransport() {
        override fun getText(url: String): String = when {
            "selectTotalCtrlpntList" in url -> """{"totalCtrlpnt_list":[
              {"ctrlpnt_eng_nm":"AAAA","rinex_nm":"AAAA","ctrlpnt_nm":"가까움","ctrlpnt_se":"ngii","oper_sttus":"X","lat":"37-32-45.3154","lon":"126-57-37.9996"},
              {"ctrlpnt_eng_nm":"BBBB","rinex_nm":"BBBB","ctrlpnt_nm":"대체","ctrlpnt_se":"ngii","oper_sttus":"X","lat":"37-32-50","lon":"126-57-40"}
            ]}"""
            "m001/list" in url -> """{"ctrlpntSttus":[
              {"ctrlpnt_eng_nm":"AAAA","rinex_hourly_sttus_at":"N"},
              {"ctrlpnt_eng_nm":"BBBB","rinex_hourly_sttus_at":"N"}
            ]}"""
            "calendar" in url -> """{"resultList":[{"rinex_time_se":"Hourly","rinex_date_hour":"2025090409","data_count":4,"data_count_all":5}]}"""
            "openapi/dir" in url -> """{"fileList":[
              {"name":"AAAA247j.25O.gz","size":100}, {"name":"AAAA247j.25N.gz","size":100},
              {"name":"BBBB247j.25O.gz","size":100}, {"name":"BBBB247j.25N.gz","size":100}
            ]}"""
            else -> error("Unexpected fake URL: $url")
        }

        override fun download(url: String, fileKey: CharArray, destination: File): Download {
            val body = when {
                "AAAA247j.25O.gz" in url -> "not-gzip".toByteArray()
                url.contains("247j.25O.gz") -> gzip(observationRinex())
                url.contains("247j.25N.gz") -> gzip(navigationRinex())
                else -> error("Unexpected fake download: $url")
            }
            destination.parentFile?.mkdirs()
            destination.writeBytes(body)
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            return Download(destination, hash, body.size.toLong(), url)
        }

        private fun gzip(text: String): ByteArray {
            val file = File.createTempFile("ngii-fixture", ".gz")
            GZIPOutputStream(FileOutputStream(file)).use { it.write(text.toByteArray(Charsets.US_ASCII)) }
            return file.readBytes().also { file.delete() }
        }

        private fun observationRinex(): String = buildString {
            append("     2.11           O                   RINEX VERSION / TYPE\n")
            append("  4    C1    L1    D1    S1                  # / TYPES OF OBSERV\n")
            append("  -3040000.0  4040000.0  3860000.0           APPROX POSITION XYZ\n")
            append("  2025     9     4     9     0    0.0000000     GPS         TIME OF FIRST OBS\n")
            append("                                                            END OF HEADER\n")
            append(" 25  9  4  9  0  0.0000000  0  1G01\n")
            append(" 25  9  4  9 59 30.0000000  0  1G01\n")
        }

        private fun navigationRinex(): String = buildString {
            append("     2.11           N                   RINEX VERSION / TYPE\n")
            append("                                                            END OF HEADER\n")
            append(" 1 25  9  4  9  0  0.0 0.0 0.0 0.0\n")
        }
    }

    companion object {
        private fun stationCatalogJson(code: String, latitude: String, longitude: String): String =
            """{"totalCtrlpnt_list":[{"ctrlpnt_eng_nm":"$code","rinex_nm":"$code","ctrlpnt_nm":"test","ctrlpnt_se":"ngii","oper_sttus":"O","lat":"$latitude","lon":"$longitude"}]}"""
    }
}
