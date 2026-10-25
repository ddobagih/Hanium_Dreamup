package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.BuildConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReportQueueContractTest {
    @Test fun sharedCopiesCloseTheSameOwnerAndPreserveCallerBytes() {
        val metadata = "{\"kind\":\"hazard\"}".toByteArray()
        val image = reportTestJpeg()
        val originalImage = image.copyOf()
        val payload = requireNotNull(FrozenReportPayload.freeze(REPORT_ID, metadata, image))
        val report = QueuedReport(payload, ReportQueuePriority.EXPLICIT, "walker", REPORT_ID,
            "a".repeat(64), 1L, 2L)
        report.copy(quarantined = true).close()
        report.close()
        assertThrows(IllegalStateException::class.java) { payload.metadataUtf8() }
        for (name in listOf("frozenMetadata", "frozenImage")) {
            val field = FrozenReportPayload::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertTrue((field.get(payload) as ByteArray).all { it == 0.toByte() })
        }
        assertArrayEquals(originalImage, image)
        assertEquals("{\"kind\":\"hazard\"}", String(metadata))
    }

    @Test fun exactByteScopeZerosItsCopiesWhenTheConsumerThrows() {
        val payload = requireNotNull(FrozenReportPayload.freeze(REPORT_ID, "{}".toByteArray(), reportTestJpeg()))
        var metadataCopy: ByteArray? = null
        var imageCopy: ByteArray? = null
        assertThrows(IllegalStateException::class.java) {
            payload.useExactBytes { metadata, image -> metadataCopy = metadata; imageCopy = image; error("consumer failure") }
        }
        assertTrue(requireNotNull(metadataCopy).all { it == 0.toByte() })
        assertTrue(requireNotNull(imageCopy).all { it == 0.toByte() })
        assertEquals("{}", String(payload.metadataUtf8()))
        payload.close()
    }

    @Test
    fun productionProfileMatchesTheValidatedBuildConfigExactly() {
        val profile = PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE
        if (!BuildConfig.DEBUG || !BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED) {
            assertNull(profile)
            return
        }

        val active = requireNotNull(profile)
        assertEquals(BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_ENTRIES, active.maxEntries)
        assertEquals(BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES, active.maxPayloadBytes)
        assertEquals(
            BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES,
            active.maxStoredEntryBytes,
        )
        assertEquals(BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES, active.maxTotalBytes)
        assertEquals(
            BuildConfig.WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES,
            active.automaticMaxEntries,
        )
        assertEquals(
            BuildConfig.WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES,
            active.automaticMaxTotalBytes,
        )
    }

    @Test
    fun disabledBuildConfigKeepsProductionQueueCapacityZeroed() {
        assumeFalse(BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED)
        assertFalse(BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED)
        assertEquals(0, BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_ENTRIES)
        assertEquals(0, BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES)
        assertEquals(0L, BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES)
        assertEquals(0L, BuildConfig.WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES)
        assertEquals(0, BuildConfig.WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES)
        assertEquals(0L, BuildConfig.WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES)
    }

    @Test
    fun enabledDebugBuildConfigPreservesExplicitCapacityAndApprovedOrigin() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED)
        val profile = requireNotNull(PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE)

        assertTrue(profile.maxEntries > profile.automaticMaxEntries)
        assertTrue(
            profile.maxTotalBytes - profile.automaticMaxTotalBytes >= profile.maxStoredEntryBytes,
        )
        assertEquals(
            BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN,
            approvedReportQueueGatewayOriginOrNull(BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN),
        )
    }

    @Test
    fun payloadHashUsesTheExactDomainUuidLengthsAndFrozenBytes() {
        val metadata = "{\"kind\":\"hazard\"}".toByteArray(Charsets.UTF_8)
        // The digest contract is independent of JPEG validation: same golden bytes as before.
        val hashedImage = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        assertEquals(
            "5cb52b328e68d2aa0ca279709bca137a3e886dd45efa98567ccf0eeb5877d5ef",
            reportPayloadSha256(REPORT_ID, metadata, hashedImage),
        )
        val image = reportTestJpeg()

        val payload = requireNotNull(FrozenReportPayload.freeze(REPORT_ID, metadata, image))

        assertEquals(17L + image.size, payload.payloadBytes)
        assertEquals(reportPayloadSha256(REPORT_ID, metadata, image), payload.payloadSha256)
        metadata.fill('x'.code.toByte())
        image.fill(0)
        assertArrayEquals("{\"kind\":\"hazard\"}".toByteArray(), payload.metadataUtf8())
        assertArrayEquals(reportTestJpeg(), payload.imageJpeg())
    }

    @Test
    fun malformedUtf8AndNonJpegInputsFailClosed() {
        val jpeg = reportTestJpeg()

        assertNotNull(FrozenReportPayload.freeze(REPORT_ID, "{}".toByteArray(), jpeg))
        assertNull(FrozenReportPayload.freeze(REPORT_ID, "{}".toByteArray(), byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
        assertNull(FrozenReportPayload.freeze(REPORT_ID, byteArrayOf(0xc3.toByte(), 0x28), jpeg))
        assertNull(FrozenReportPayload.freeze(REPORT_ID, "{}".toByteArray(), byteArrayOf(1, 2)))
        assertNull(FrozenReportPayload.freeze("not-a-uuid", "{}".toByteArray(), jpeg))
    }

    @Test
    fun productionCapacityProfileIsDefaultOffAndRejectsMissingReserve() {
        assertNull(
            approvedReportQueueCapacityProfile(
                enabled = false,
                maxEntries = 0,
                maxPayloadBytes = 0,
                maxStoredEntryBytes = 0L,
                maxTotalBytes = 0L,
                automaticMaxEntries = 0,
                automaticMaxTotalBytes = 0L,
            ),
        )
        assertNull(
            approvedReportQueueCapacityProfile(
                enabled = true,
                maxEntries = 10,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 10_240L,
                automaticMaxEntries = 10,
                automaticMaxTotalBytes = 10_240L,
            ),
        )
        assertNull(
            approvedReportQueueCapacityProfile(
                enabled = true,
                maxEntries = 10,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 10_240L,
                automaticMaxEntries = 8,
                automaticMaxTotalBytes = 512L,
            ),
        )
        assertNull(
            approvedReportQueueCapacityProfile(
                enabled = true,
                maxEntries = 10,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 10_240L,
                automaticMaxEntries = 8,
                automaticMaxTotalBytes = 9_500L,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ApprovedReportQueueCapacityProfile(
                maxEntries = 10,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 10_240L,
                automaticMaxEntries = 10,
                automaticMaxTotalBytes = 10_240L,
            )
        }
    }

    @Test
    fun approvedProductionProfileKeepsExplicitReportReserve() {
        val profile = approvedReportQueueCapacityProfile(
            enabled = true,
            maxEntries = 10,
            maxPayloadBytes = 1_024,
            maxStoredEntryBytes = 1_024L,
            maxTotalBytes = 10_240L,
            automaticMaxEntries = 8,
            automaticMaxTotalBytes = 8_192L,
        )

        assertNotNull(profile)
        assertEquals(10, profile?.maxEntries)
        assertEquals(1_024, profile?.maxPayloadBytes)
        assertEquals(1_024L, profile?.maxStoredEntryBytes)
        assertEquals(10_240L, profile?.maxTotalBytes)
        assertEquals(8, profile?.automaticMaxEntries)
        assertEquals(8_192L, profile?.automaticMaxTotalBytes)
    }

    private companion object {
        const val REPORT_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}
