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
            MainActivityRefactorSource.function(
                main,
                "private fun processReportCandidate",
            )
        assertTrue(
            appearsInOrder(
                process,
                "authority.isRuntimeEpochCurrent(expectedWalkEpoch)",
                "!authority.walkSafetyOutputsAllowed()",
                "integratedConsentSession.currentConfirmationOrNull()",
                "queue.enqueue(",
                "walkSessionId = expectedWalkEpoch.walkSessionId",
                "consentReceiptSha256 =",
                "consentConfirmation.backendConsentReceiptSha256",
            ),
        )
        assertFalse(process.contains("uploadCall("))
        val candidateWiring = MainActivityRefactorSource.composition(main, "reportCandidateController")
        assertTrue(candidateWiring.contains("override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = walkSessionLifecycle.isRuntimeEpochCurrent(epoch)"))
        assertTrue(candidateWiring.contains("override fun walkSafetyOutputsAllowed() = this@MainActivity.walkSafetyOutputsAllowed()"))
        assertTrue(candidateWiring.contains("= reportQueueStore.enqueue("))
        assertTrue(candidateWiring.contains("expectedReporterActorId, metadataUtf8, imageJpeg, priority, walkSessionId, consentReceiptSha256, accountGeneration"))

        val capture =
            MainActivityRefactorSource.function(
                main,
                "private fun buildReportQueueDrainTrigger",
            )
        assertTrue(
            appearsInOrder(
                capture,
                "val reporter = sources.reporterActorId() ?: return null",
                "val gatewaySnapshot = sources.gatewaySnapshot()",
                "gatewaySession.isUsableFor(reporter)",
                "gatewaySessionGeneration = gatewaySnapshot.generation",
            ),
        )

        val drainWiring = MainActivityRefactorSource.composition(main, "reportQueueDrainController")
        assertTrue(drainWiring.contains("gatewaySnapshot = GatewaySessionProcessCoordinator::snapshot"))
        assertTrue(drainWiring.contains("reporterActorId = ::currentReporterUserId"))

        val queuedUpload =
            ReportStaticSourceInspector.functionBlock(uploader, "internal fun queuedUploadResultCall")
        assertFalse(queuedUpload.contains("consentConfirmation.backendConsentReceiptSha256 =="))
        assertTrue(
            uploader.contains(
                "setRequestProperty(\n                    CONSENT_RECEIPT_HEADER,\n                    consentConfirmation.backendConsentReceiptSha256",
            ),
        )
        assertTrue(queuedUpload.contains("parseQueuedUploadResultOrNull(response.body, report)"))

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
            ReportStaticSourceInspector.functionBlock(coordinator, "fun startNextWithResult")
        val statusReceipt = startNext.substringAfter("if (statusReceipt != null)")
            .substringBefore("val uploadCall")
        assertTrue(
            appearsInOrder(
                statusReceipt,
                "return@execute completeReceipt(",
                "report, statusReceipt, lease, outer, contextProvider,",
                "beforeDeleteAfterReceipt, ReportQueueDrainOutcome.DELETED_AFTER_STATUS",
            ),
        )
        val uploadReceipt = startNext.substringAfter("val uploadResult = uploadCall.execute()")
        assertTrue(
            appearsInOrder(
                uploadReceipt,
                "policy.isCurrent(lease, contextProvider())",
                "completeReceipt(",
                "report, uploadResult.receipt, lease, outer, contextProvider,",
                "beforeDeleteAfterReceipt, ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD",
            ),
        )
        val completion = ReportStaticSourceInspector.functionBlock(
            coordinator,
            "private fun completeReceipt",
        )
        val cooldownCommit =
            "if (!beforeDelete(report)) {"
        assertTrue(completion.contains(cooldownCommit))
        val commitFailure = ReportStaticSourceInspector.blockAfter(completion, cooldownCommit.removeSuffix(" {"))
        assertTrue(appearsInOrder(commitFailure,
            "store.recordUploadFailure(report, false)",
            "return ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED"))
        assertTrue(
            appearsInOrder(
                completion.substringBefore(cooldownCommit),
                "if (!currentAdmission() || !policy.isCurrent(lease, contextProvider()))",
                "return ReportQueueDrainOutcome.CANCELLED",
                "if (!receipt.matches(report)) {",
                "store.recordUploadFailure(report, false)",
                "ReportQueueDrainOutcome.RECEIPT_UNCONFIRMED",
                "else ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED",
            ),
        )
        assertTrue(
            appearsInOrder(
                completion.substringAfter(cooldownCommit),
                "if (!currentAdmission() || !policy.isCurrent(lease, contextProvider()))",
                "return ReportQueueDrainOutcome.CANCELLED",
                "return if (store.deleteAfterReceipt(lease.reporterActorId, receipt)) success",
                "store.recordUploadFailure(report, false)",
                "ReportQueueDrainOutcome.LOCAL_DELETE_FAILED",
            ),
        )
        assertTrue(startNext.contains("finally"))
        assertTrue(startNext.contains("policy.release(lease)"))

        val drain =
            MainActivityRefactorSource.function(main, "private fun drainInitialExactReportQueue")
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
        assertTrue(MainActivityRefactorSource.composition(main, "reportQueueDrainController")
            .contains("persistCooldownForSuccess = ::persistReportCooldownForSuccess"))
        assertTrue(MainActivityRefactorSource.composition(main, "reportCandidateController")
            .contains("attempts = { reportAttemptStore }"))
        val commit = MainActivityRefactorSource.function(
            main,
            "private fun persistAutomaticReportCooldownBeforeQueueDelete",
        )
        assertTrue(
            appearsInOrder(
                commit,
                "report.automaticCooldownScopeOrNull()",
                "persistCooldownForSuccess(",
                "reportAttemptStore.markSucceeded(",
                "reportAttemptStore.release(",
            ),
        )

        val process = MainActivityRefactorSource.function(
            main,
            "private fun processReportCandidate",
        )
        assertTrue(
            appearsInOrder(
                process,
                "automaticReportCooldownScopeOrNull(candidate.metadata)",
                "attempts().acquire(",
                "queue.enqueue(",
                "attempts()::release",
            ),
        )
    }

    @Test
    fun httpClassificationAndActorHashedV2FailureStateContainNoReportPayload() {
        val main = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val uploader = ReportStaticSourceInspector.read(UPLOADER_PATH)
        val queuedUpload =
            ReportStaticSourceInspector.functionBlock(uploader, "internal fun queuedUploadResultCall")
        val httpFailure = ReportStaticSourceInspector.blockAfter(
            queuedUpload,
            "if (response.statusCode !in 200..299)",
        )
        assertTrue(
            appearsInOrder(
                httpFailure,
                "val error = response.toUploadException(connection)",
                "cancellation.throwIfCancelled()",
                "consumeGatewayAuthenticationStaleResponse(authenticationRequest, session, response)",
                "throw error",
            ),
        )
        assertTrue(
            appearsInOrder(
                queuedUpload,
                "val authenticationRequest = captureGatewayAuthenticationRequest(session)",
                "return reportTransportCall(",
                "connection.readBoundedResponse(REPORT_UPLOAD_MAX_RESPONSE_BYTES, cancellation)",
                "if (response.statusCode !in 200..299)",
                "parseQueuedUploadResultOrNull(response.body, report)",
                "throw ReportUploadProtocolException()",
            ),
        )

        val drain =
            MainActivityRefactorSource.function(main, "private fun drainInitialExactReportQueue")
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
