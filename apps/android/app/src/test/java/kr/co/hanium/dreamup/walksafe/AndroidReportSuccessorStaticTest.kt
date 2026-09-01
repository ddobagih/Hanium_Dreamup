package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReportSuccessorStaticTest {
    @Test
    fun traceAndReceiptAreActorSessionNamespacedAndExplicitlyBound() {
        val main = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val uploader = ReportStaticSourceInspector.read(UPLOADER_PATH)
        val store = ReportStaticSourceInspector.read(QUEUE_STORE_PATH)

        val process =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun processReportCandidate",
            )
        assertTrue(
            appearsInOrder(
                process,
                "walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)",
                "!officialEnvironmentOutputsAllowed || !phoneMountingOutputsAllowed",
                "integratedConsentSession.currentConfirmationOrNull()",
                "reportQueueStore.enqueue(",
                "walkSessionId = expectedWalkEpoch.walkSessionId",
                "consentReceiptSha256 =",
                "consentConfirmation.backendConsentReceiptSha256",
            ),
        )
        assertFalse(process.contains("uploadCall("))

        val capture =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun buildReportQueueDrainTrigger",
            )
        assertTrue(
            appearsInOrder(
                capture,
                "val reporter = currentReporterUserId() ?: return null",
                "val gatewaySnapshot = GatewaySessionProcessCoordinator.snapshot()",
                "gatewaySession.isUsableFor(reporter)",
                "gatewaySessionGeneration = gatewaySnapshot.generation",
            ),
        )

        val queuedUpload =
            ReportStaticSourceInspector.functionBlock(uploader, "internal fun queuedUploadCall")
        assertFalse(queuedUpload.contains("consentConfirmation.backendConsentReceiptSha256 =="))
        assertTrue(
            uploader.contains(
                "setRequestProperty(\n                    CONSENT_RECEIPT_HEADER,\n                    consentConfirmation.backendConsentReceiptSha256",
            ),
        )
        assertTrue(queuedUpload.contains("parseQueuedUploadReceiptOrNull(response.body, report)"))

        val transport =
            ReportStaticSourceInspector.functionBlock(uploader, "private fun <T> reportTransportCall")
        assertTrue(
            appearsInOrder(
                transport,
                "ReportPrivacyConsentSession.withLiveUploadPermit(",
                "networkBinding.openConnection(URL(endpoint))",
                "setRequestProperty(REPORT_ID_HEADER, report.payload.reportId)",
                "setRequestProperty(REPORT_PAYLOAD_SHA256_HEADER, report.payload.payloadSha256)",
                "consentConfirmation.backendConsentReceiptSha256",
                "session.requestHeaders().forEach(::setRequestProperty)",
            ),
        )

        val receipt =
            ReportStaticSourceInspector.functionBlock(uploader, "private fun strictTransportReceipt")
        assertTrue(
            appearsInOrder(
                receipt,
                "receipt.reportId == report.payload.reportId",
                "receipt.payloadSha256 == report.payload.payloadSha256",
                "receipt.payloadBytes == report.payload.payloadBytes",
                "receipt.marker == REPORT_RECEIPT_MARKER",
                "isCanonicalReportUuid(receipt.persistenceMarker)",
            ),
        )
        val deletion = ReportStaticSourceInspector.functionBlock(store, "fun deleteAfterReceipt")
        assertTrue(deletion.contains("report.reporterActorId != reporterActorId"))
        assertTrue(deletion.contains("report.payload.reportId != receipt.reportId"))
        assertTrue(deletion.contains("report.payload.payloadSha256 != receipt.payloadSha256"))
        assertTrue(deletion.contains("report.payload.payloadBytes != receipt.payloadBytes"))
    }

    @Test
    fun cooldownCommitPrecedesAttemptClearAndSuccessPublication() {
        val main = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val coordinator = ReportStaticSourceInspector.read(DRAIN_COORDINATOR_PATH)
        val startNext =
            ReportStaticSourceInspector.functionBlock(coordinator, "fun startNext")
        val statusReceipt = startNext.substringAfter("if (statusReceipt != null)")
            .substringBefore("val uploadCall")
        assertTrue(
            appearsInOrder(
                statusReceipt,
                "store.deleteAfterReceipt(lease.reporterActorId, statusReceipt)",
                "ReportQueueDrainOutcome.DELETED_AFTER_STATUS",
                "ReportQueueDrainOutcome.RECEIPT_REJECTED",
            ),
        )
        val uploadReceipt = startNext.substringAfter("val uploadReceipt = uploadCall.execute()")
        assertTrue(
            appearsInOrder(
                uploadReceipt,
                "policy.isCurrent(lease, contextProvider())",
                "store.deleteAfterReceipt(lease.reporterActorId, uploadReceipt)",
                "ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD",
                "ReportQueueDrainOutcome.RECEIPT_REJECTED",
            ),
        )
        assertTrue(startNext.contains("finally"))
        assertTrue(startNext.contains("policy.release(lease)"))

        val drain =
            ReportStaticSourceInspector.functionBlock(main, "private fun drainInitialExactReportQueue")
        assertTrue(
            appearsInOrder(
                drain,
                "ReportQueueDrainOutcome.DELETED_AFTER_STATUS",
                "ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD",
                "break",
                "remaining -= 1",
            ),
        )
        assertTrue(
            drain.contains(
                "beforeDeleteAfterReceipt =\n" +
                    "                        ::persistAutomaticReportCooldownBeforeQueueDelete",
            ),
        )
        val commit = ReportStaticSourceInspector.functionBlock(
            main,
            "private fun persistAutomaticReportCooldownBeforeQueueDelete",
        )
        assertTrue(
            appearsInOrder(
                commit,
                "report.automaticCooldownScopeOrNull()",
                "persistReportCooldownForSuccess(",
                "reportAttemptStore.markSucceeded(",
                "reportAttemptStore.release(",
            ),
        )

        val process = ReportStaticSourceInspector.functionBlock(
            main,
            "private fun processReportCandidate",
        )
        assertTrue(
            appearsInOrder(
                process,
                "automaticReportCooldownScopeOrNull(candidate.metadata)",
                "reportAttemptStore.acquire(",
                "reportQueueStore.enqueue(",
                "reportAttemptStore::release",
            ),
        )
    }

    @Test
    fun httpClassificationAndActorHashedV2FailureStateContainNoReportPayload() {
        val main = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val uploader = ReportStaticSourceInspector.read(UPLOADER_PATH)
        val queuedUpload =
            ReportStaticSourceInspector.functionBlock(uploader, "internal fun queuedUploadCall")
        assertTrue(
            appearsInOrder(
                queuedUpload,
                "if (response.statusCode !in 200..299) throw response.toUploadException(connection)",
                "parseQueuedUploadReceiptOrNull(response.body, report)",
                "throw ReportUploadProtocolException()",
            ),
        )

        val drain =
            ReportStaticSourceInspector.functionBlock(main, "private fun drainInitialExactReportQueue")
        val failure = ReportStaticSourceInspector.blockAfter(drain, "catch (_: Exception)")
        assertTrue(failure.contains("break"))
        assertFalse(failure.contains("deleteAfterReceipt"))
        assertFalse(failure.contains("persistTerminalReportAttemptState"))
        assertTrue(
            appearsInOrder(
                drain,
                "call.execute()",
                "catch (_: Exception)",
                "break",
                "ReportQueueDrainOutcome.DELETED_AFTER_STATUS",
                "ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD",
                "remaining -= 1",
            ),
        )
    }

    private fun appearsInOrder(source: String, vararg tokens: String): Boolean {
        if (
            source.isEmpty() ||
            tokens.isEmpty() ||
            tokens.any(String::isBlank) ||
            tokens.toSet().size != tokens.size
        ) return false
        var offset = 0
        tokens.forEach { token ->
            val match = source.indexOf(token, offset)
            if (match < 0) return false
            offset = match + token.length
        }
        return true
    }

    private companion object {
        const val MAIN_ACTIVITY_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
        const val UPLOADER_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/" +
                "AndroidReportUploader.kt"
        const val QUEUE_STORE_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/" +
                "AndroidReportQueueStore.kt"
        const val DRAIN_COORDINATOR_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/" +
                "ReportQueueDrainCoordinator.kt"
    }
}
