package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReportSuccessorStaticTest {
    @Test
    fun traceAndReceiptAreActorSessionNamespacedAndExplicitlyBound() {
        val main = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val uploader = ReportStaticSourceInspector.read(UPLOADER_PATH)

        val traceFactory =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun newStableReportTraceId",
            )
        assertTrue(traceFactory.contains("actorId"))
        assertTrue(traceFactory.contains("sessionGeneration"))
        assertTrue(traceFactory.contains("sha256Hex"))
        assertTrue(traceFactory.contains(".take(24)"))
        assertTrue(traceFactory.contains("UUID.randomUUID()"))
        assertTrue(traceFactory.contains("\"android:"))
        assertTrue(traceFactory.contains(".take(128)"))
        val returnLine = traceFactory.lineSequence().first { it.trimStart().startsWith("return ") }
        assertFalse("The raw actor id must not appear in the emitted trace.", returnLine.contains("actorId"))

        val process =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun processReportCandidate",
            )
        assertTrue(
            Regex(
                """newStableReportTraceId\s*\([\s\S]*?actorId\s*=\s*gatewaySession\.actorId[\s\S]*?sessionGeneration\s*=\s*gatewaySessionGeneration""",
            ).containsMatchIn(process),
        )
        assertTrue(
            Regex(
                """\.put\s*\(\s*"trace_id"\s*,\s*stableTraceId\s*\)""",
            ).containsMatchIn(process),
        )
        assertTrue(Regex("""stableTraceId\s*=\s*stableTraceId""").containsMatchIn(process))
        assertTrue(
            Regex(
                """expectedGatewayActorId\s*=\s*gatewaySession\.actorId""",
            ).containsMatchIn(process),
        )

        val expectation =
            ReportStaticSourceInspector.functionBlock(
                uploader,
                "internal fun reportUploadReceiptExpectation",
            )
        assertTrue(expectation.contains("traceId = stableTraceId"))
        assertTrue(expectation.contains("gatewayActorId = expectedGatewayActorId"))
        assertTrue(expectation.contains("imageSha256 = imageJpeg.sha256Hex()"))

        val validation =
            ReportStaticSourceInspector.functionBlock(
                uploader,
                "internal fun validatedReportUploadResponseOrNull",
            )
        assertTrue(validation.contains("actualActorId != receiptExpectation.gatewayActorId"))
        assertTrue(validation.contains("receiptExpectation.traceId"))
        assertTrue(validation.contains("receiptExpectation.imageSha256"))
        val nullableReceipt =
            ReportStaticSourceInspector.functionBlock(
                uploader,
                "private fun nullableReceiptString",
            )
        assertTrue(
            Regex(
                """if\s*\(\s*!metadata\.has\(name\)\s*\|\|\s*metadata\.isNull\(name\)\s*\)\s*return null""",
            ).containsMatchIn(nullableReceipt),
        )
        assertTrue(
            Regex(
                """metadata\.opt\(name\)\s+as\?\s+String\s+\?:\s+throw ReportUploadProtocolException\(\)""",
            ).containsMatchIn(nullableReceipt),
        )
        assertTrue(
            Regex(
                """actualTraceId\s*!=\s*null\s*&&\s*actualTraceId\s*!=\s*receiptExpectation\.traceId\s*\)\s*\{\s*throw ReportUploadProtocolException\(\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(validation),
        )
        assertTrue(
            Regex(
                """actualImageSha256\s*!=\s*null\s*&&\s*actualImageSha256\s*!=\s*receiptExpectation\.imageSha256\s*\)\s*\{\s*throw ReportUploadProtocolException\(\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(validation),
        )
        assertTrue(
            appearsInOrder(
                validation,
                "val actualTraceId",
                "val actualImageSha256",
                "actualTraceId != null && actualImageSha256 != null",
                "ReportUploadReceiptOutcome.BOUND",
                "receiptExpectation.transferPurpose == ReportTransferPurpose.AUTOMATIC",
                "ReportUploadReceiptOutcome.AUTOMATIC_COOLDOWN_AMBIGUOUS",
            ),
        )
        assertTrue(
            Regex(
                """else\s*\{\s*throw ReportUploadProtocolException\(\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(validation),
        )

        val uploadCall =
            ReportStaticSourceInspector.functionBlock(
                uploader,
                "internal fun uploadCall",
            )
        assertTrue(uploadCall.contains("validatedReportUploadResponseOrNull"))
        assertTrue(uploadCall.contains("throw ReportUploadProtocolException()"))
        val protocolFailure =
            ReportStaticSourceInspector.blockAfter(
                process,
                "catch (_: ReportUploadProtocolException)",
            )
        assertTrue(protocolFailure.contains("persistTerminalReportAttemptState"))
        assertFalse(
            "The client must not claim an unverified payload hash receipt.",
            uploader.contains("payload_sha", ignoreCase = true),
        )
        assertFalse(
            "The client must not claim an idempotency contract it does not send.",
            uploader.contains("idempot", ignoreCase = true),
        )
    }

    @Test
    fun cooldownCommitPrecedesAttemptClearAndSuccessPublication() {
        val main = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val cooldownPersistence =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun persistReportCooldownForSuccess",
            )
        assertTrue(
            "Cooldown persistence must synchronously report the SharedPreferences commit result.",
            Regex("""\.commit\s*\(\s*\)""").containsMatchIn(cooldownPersistence),
        )

        val process =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun processReportCandidate",
            )
        val ambiguousStart = process.indexOf("response.receiptOutcome ==")
        assertTrue(ambiguousStart >= 0)
        val ambiguousEnd =
            process.indexOf(
                "if (response.receiptOutcome !=",
                startIndex = ambiguousStart,
            )
        assertTrue(ambiguousEnd > ambiguousStart)
        val ambiguous = process.substring(ambiguousStart, ambiguousEnd)
        assertTrue(
            Regex(
                """if\s*\(\s*!\s*persistReportCooldownForSuccess\s*\(""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(ambiguous),
        )
        assertTrue(
            appearsInOrder(
                ambiguous,
                "persistReportCooldownForSuccess",
                "reportAttemptStore.markSucceeded",
                "clearPersistedReportAttemptState",
                "reportCandidate=suppressed_ambiguous",
            ),
        )
        assertTrue(
            appearsInOrder(
                ambiguous,
                "reportAttemptStore.markSucceeded",
                "clearPersistedReportAttemptState",
                "runOnUiThread",
                "runIfReportUploadTerminalCurrent",
                "reportCandidate=suppressed_ambiguous",
            ),
        )
        val ambiguousCommitFailure =
            ReportStaticSourceInspector.blockAfter(
                ambiguous,
                "!persistReportCooldownForSuccess",
        )
        assertTrue(
            appearsInOrder(
                ambiguousCommitFailure,
                "blockReportAttemptStorageForActor",
                "persistTerminalReportAttemptState",
                "return@execute",
            ),
        )

        val boundStart =
            process.indexOf(
                "val succeededAtMs",
                startIndex = ambiguousEnd,
            )
        assertTrue(boundStart >= ambiguousEnd)
        val boundEnd =
            process.indexOf(
                "} catch (_: ReportUploadProtocolException)",
                startIndex = boundStart,
            )
        assertTrue(boundEnd > boundStart)
        val boundSuccess = process.substring(boundStart, boundEnd)
        assertTrue(
            Regex(
                """if\s*\(\s*!\s*persistReportCooldownForSuccess\s*\(""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(boundSuccess),
        )
        assertTrue(
            appearsInOrder(
                boundSuccess,
                "persistReportCooldownForSuccess",
                "reportAttemptStore.markSucceeded",
                "clearPersistedReportAttemptState",
                "runOnUiThread",
                "runIfReportUploadTerminalCurrent",
                "reportCandidate=succeeded key=",
            ),
        )
        assertTrue(
            appearsInOrder(
                boundSuccess,
                "clearPersistedReportAttemptState",
                "runIfReportUploadTerminalCurrent",
                "reportCandidate=succeeded key=",
                "speakInteraction",
            ),
        )
        val boundCommitFailure =
            ReportStaticSourceInspector.blockAfter(
                boundSuccess,
                "!persistReportCooldownForSuccess",
        )
        assertTrue(
            appearsInOrder(
                boundCommitFailure,
                "blockReportAttemptStorageForActor",
                "persistTerminalReportAttemptState",
                "return@execute",
            ),
        )
        assertTrue(
            appearsInOrder(
                process,
                "ReportUploadReceiptOutcome.AUTOMATIC_COOLDOWN_AMBIGUOUS",
                "blockReportAttemptStorageForActor",
                "persistTerminalReportAttemptState",
                "return@execute",
                "finally",
                "reportAttemptStore.release(lease)",
            ),
        )

        val terminalPersistence =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun persistTerminalReportAttemptState",
            )
        assertTrue(terminalPersistence.contains("persistReportAttemptStatesLocked()"))
    }

    @Test
    fun httpClassificationAndActorHashedV2FailureStateContainNoReportPayload() {
        val main = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val process =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun processReportCandidate",
            )
        val httpFailure =
            ReportStaticSourceInspector.blockAfter(
                process,
                "catch (error: ReportUploadHttpException)",
            )
        assertTrue(
            appearsInOrder(
                httpFailure,
                "val transientFailure =",
                "isTransientReportHttpStatus(httpStatusCode)",
            ),
        )
        assertTrue(
            appearsInOrder(
                httpFailure,
                "if (transientFailure)",
                "persistTransientReportAttemptFailure",
                "else",
                "persistTerminalReportAttemptState",
            ),
        )

        val actorScope =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun ensureReportAttemptStateActor",
            )
        assertTrue(actorScope.contains("sha256Hex(actorId.toByteArray"))
        assertTrue(actorScope.contains("payload.getString(\"actor_hash\") == actorHash"))
        assertTrue(actorScope.contains("PREF_REPORT_ATTEMPT_STATE_V2"))

        val transientPersistence =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun persistTransientReportAttemptFailure",
            )
        assertTrue(transientPersistence.contains("previous?.consecutiveFailures"))
        assertTrue(transientPersistence.contains("retryNotBeforeMs = nowMs + delayMs"))
        assertTrue(transientPersistence.contains("persistReportAttemptStatesLocked()"))

        val serializer =
            ReportStaticSourceInspector.functionBlock(
                main,
                "private fun persistReportAttemptStatesLocked",
            )
        val persistedKeys =
            Regex("""\.put\s*\(\s*"([^"]+)"""")
                .findAll(serializer)
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(
            setOf(
                "key",
                "failures",
                "retry_not_before_ms",
                "terminal_status",
                "terminal_until_ms",
                "schema",
                "actor_hash",
                "entries",
            ),
            persistedKeys,
        )
        listOf(
            "actor_id",
            "trace_id",
            "metadata",
            "image",
            "jpeg",
            "base64",
            "payload_sha",
        ).forEach { forbidden ->
            assertFalse(
                "Failure-state v2 must not persist report payload field '$forbidden'.",
                serializer.contains(forbidden, ignoreCase = true),
            )
        }
        assertTrue(
            Regex(
                """const\s+val\s+PREF_REPORT_ATTEMPT_STATE_V2\s*=\s*"report_attempt_state_v2"""",
            ).containsMatchIn(main),
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
    }
}
