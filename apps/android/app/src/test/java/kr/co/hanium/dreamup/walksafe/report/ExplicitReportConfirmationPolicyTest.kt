package kr.co.hanium.dreamup.walksafe.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitReportConfirmationPolicyTest {
    private val context = ExplicitReportConfirmationContext(
        walkSessionId = "11111111-1111-4111-8111-111111111111",
        recoveryGeneration = 2L,
        reporterId = "walker-1",
        consentReceiptSha256 = "c".repeat(64),
        gatewaySessionGeneration = 7L,
    )

    @Test
    fun firstActionCopiesExactBytesAndSecondActionConsumesThemOnlyOnce() {
        val metadata = metadata(frame = 1)
        val image = jpeg(marker = 1)
        val expectedMetadata = metadata.copyOf()
        val expectedImage = image.copyOf()
        val frozen = frozen(context, metadata, image)
        metadata.fill(0)
        image.fill(0)
        val policy = ExplicitReportConfirmationPolicy(validityMs = 30_000L)
        policy.stage(frozen, 1_000L)
        assertTrue(policy.isAwaitingDisclosure(context))
        assertNull(policy.consumeIfConfirmed(context, 2_000L))
        assertTrue(policy.arm(context, 2_000L))

        val confirmed = requireNotNull(policy.consumeIfConfirmed(context, 31_999L))
        lateinit var ownedMetadata: ByteArray
        lateinit var ownedImage: ByteArray
        confirmed.useExactBytes { exactMetadata, exactImage ->
            ownedMetadata = exactMetadata
            ownedImage = exactImage
            assertArrayEquals(expectedMetadata, exactMetadata)
            assertArrayEquals(expectedImage, exactImage)
        }
        assertThrows(IllegalStateException::class.java) {
            confirmed.useExactBytes { _, _ -> Unit }
        }
        assertNull(policy.consumeIfConfirmed(context, 32_000L))

        confirmed.close()
        assertTrue(confirmed.isZeroizedForTests())
        assertTrue(ownedMetadata.all { it == 0.toByte() })
        assertTrue(ownedImage.all { it == 0.toByte() })
    }

    @Test
    fun timeoutZeroizesWithoutWaitingForAnotherUserAction() {
        val frozen = frozen(context)
        val (ownedMetadata, ownedImage) = ownedByteReferences(frozen)
        val policy = ExplicitReportConfirmationPolicy(
            validityMs = 30_000L,
            disclosureValidityMs = 60_000L,
        )
        policy.stage(frozen, 1_000L)

        assertFalse(policy.invalidateExpired(61_000L))
        assertTrue(policy.invalidateExpired(61_001L))
        assertTrue(frozen.isZeroizedForTests())
        assertTrue(ownedMetadata.all { it == 0.toByte() })
        assertTrue(ownedImage.all { it == 0.toByte() })
        assertNull(policy.consumeIfConfirmed(context, 61_002L))

        val armed = frozen(context)
        val (armedMetadata, armedImage) = ownedByteReferences(armed)
        policy.stage(armed, 70_000L)
        assertTrue(policy.arm(context, 80_000L))
        assertFalse(policy.invalidateExpired(110_000L))
        assertTrue(policy.invalidateExpired(110_001L))
        assertTrue(armedMetadata.all { it == 0.toByte() })
        assertTrue(armedImage.all { it == 0.toByte() })
    }

    @Test
    fun walkReporterConsentAndGatewaySessionChangesZeroizeTheFrozenPayload() {
        listOf(
            context.copy(walkSessionId = "22222222-2222-4222-8222-222222222222"),
            context.copy(recoveryGeneration = 3L),
            context.copy(reporterId = "walker-2"),
            context.copy(consentReceiptSha256 = "d".repeat(64)),
            context.copy(gatewaySessionGeneration = 8L),
        ).forEach { changed ->
            val frozen = frozen(context)
            val policy = ExplicitReportConfirmationPolicy()
            policy.stage(frozen, 1_000L)
            assertTrue(policy.arm(context, 1_500L))

            assertNull(policy.consumeIfConfirmed(changed, 2_000L))
            assertTrue(frozen.isZeroizedForTests())
        }
    }

    @Test
    fun lifecycleInvalidationAndReplacementZeroizePendingImages() {
        val first = frozen(context, image = jpeg(marker = 1))
        val second = frozen(context, image = jpeg(marker = 2))
        val (firstMetadata, firstImage) = ownedByteReferences(first)
        val (secondMetadata, secondImage) = ownedByteReferences(second)
        val policy = ExplicitReportConfirmationPolicy()
        policy.stage(first, 1_000L)
        policy.stage(second, 2_000L)

        assertTrue(first.isZeroizedForTests())
        assertTrue(firstMetadata.all { it == 0.toByte() })
        assertTrue(firstImage.all { it == 0.toByte() })
        assertTrue(policy.invalidate())
        assertTrue(second.isZeroizedForTests())
        assertTrue(secondMetadata.all { it == 0.toByte() })
        assertTrue(secondImage.all { it == 0.toByte() })
        assertFalse(policy.invalidate())
    }

    @Test
    fun payloadRejectsMetadataBoundToAnotherActor() {
        assertNull(
            ExplicitReportFrozenPayload.freeze(
                context = context,
                capturedAtEpochMs = 1_000L,
                metadataUtf8 = metadata(frame = 1, actorId = "walker-2"),
                imageJpeg = jpeg(),
            ),
        )
    }

    private fun frozen(
        context: ExplicitReportConfirmationContext,
        metadata: ByteArray = metadata(),
        image: ByteArray = jpeg(),
    ): ExplicitReportFrozenPayload = requireNotNull(
        ExplicitReportFrozenPayload.freeze(
            context = context,
            capturedAtEpochMs = 1_000L,
            metadataUtf8 = metadata,
            imageJpeg = image,
        ),
    )

    private fun metadata(frame: Int = 1, actorId: String = context.reporterId): ByteArray =
        "{\"reporter_user_id\":\"$actorId\",\"frame\":$frame}".toByteArray()

    private fun jpeg(marker: Int = 1): ByteArray =
        byteArrayOf(
            0xff.toByte(),
            0xd8.toByte(),
            marker.toByte(),
            0xff.toByte(),
            0xd9.toByte(),
        )

    private fun ownedByteReferences(
        frozen: ExplicitReportFrozenPayload,
    ): Pair<ByteArray, ByteArray> {
        val metadataField = frozen.javaClass.getDeclaredField("frozenMetadata").apply {
            isAccessible = true
        }
        val imageField = frozen.javaClass.getDeclaredField("frozenImage").apply {
            isAccessible = true
        }
        return requireNotNull(metadataField.get(frozen) as? ByteArray) to
            requireNotNull(imageField.get(frozen) as? ByteArray)
    }
}
