package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentReportQueueDisabledStaticTest {
    @Test
    fun persistentQueueIsDisabledWithoutBlockingUnrelatedEnqueueCalls() {
        val source = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)

        assertTrue(
            "Removed legacy retry storage must remain explicitly purge-only.",
            Regex(
                """\bconst\s+val\s+PERSISTENT_REPORT_QUEUE_ENABLED\s*=\s*false\b""",
            ).containsMatchIn(source),
        )
        assertFalse(
            "MainActivity must not construct a persistent report store.",
            source.contains("AndroidPendingReportStore("),
        )
        assertFalse(
            "No persistent queue instance may remain reachable.",
            Regex("""\bpendingReportStore\b""").containsMatchIn(source),
        )

        val storeCalls =
            Regex("""\bAndroidPendingReportStore\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(
            "Opaque legacy cleanup must be the only remaining store operation.",
            listOf("purgeAllWithoutLoading"),
            storeCalls,
        )

        val removedQueueCalls =
            listOf(
                "enqueue",
                "pendingFor",
                "markTransientFailure",
                "markTerminalFailure",
                "removeAfterValidatedSuccess",
                "reserve",
                "release",
                "markSucceeded",
            )
        removedQueueCalls.forEach { call ->
            assertFalse(
                "Persistent payload operation '$call' must stay disconnected.",
                Regex(
                    """\b(?:AndroidPendingReportStore|pendingReportStore)\s*(?:\?\.|\.)\s*$call\s*\(""",
                ).containsMatchIn(source),
            )
        }

        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportCandidateController.kt").readText()
        val wiring = MainActivityRefactorSource.composition(source, "reportCandidateController")
        assertTrue(wiring.contains("reportQueueStore.enqueue("))
        assertTrue(MainActivityRefactorSource.member(source, "publishCurrentFrameReportState")
            .contains("reportCandidateController.publishCurrentFrameReportState("))
        assertTrue(MainActivityRefactorSource.member(owner, "prepareReportCandidate").contains("processReportCandidate("))
        val process = MainActivityRefactorSource.member(owner, "processReportCandidate")
        assertTrue(
            "ACTIVE report creation must only offer the frozen payload to the U5 queue.",
            process.contains("queue.enqueue("),
        )
        assertFalse(process.contains("uploadCall("))
        val queueContract = ReportStaticSourceInspector.read(REPORT_QUEUE_CONTRACT_PATH)
        val buildScript = ReportStaticSourceInspector.read(BUILD_SCRIPT_PATH)
        assertTrue(
            "The production queue must be constructed only from validated BuildConfig inputs.",
            queueContract.contains("approvedReportQueueCapacityProfile(") &&
                queueContract.contains("BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED"),
        )
        assertTrue(buildScript.contains("null, \"false\" -> false"))
        assertTrue(buildScript.contains("automatic entry limit below the total entry limit"))
        assertTrue(
            buildScript.contains("automatic capacity plus one max stored explicit entry reserve"),
        )

        val content = MainActivityRefactorSource.member(source, "buildContentView")
        val composer = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
        assertTrue(content.contains("NativeRuntimeViewComposer(this, viewStyle)"))
        assertTrue(content.contains("explicitReportButton = runtimeComposer.createReportAction("))
        assertTrue(content.contains("queueAvailable = { PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE != null }"))
        val explicitButton = MainActivityRefactorSource.member(composer, "createReportAction")
        assertTrue(explicitButton.contains("val queueAvailable = queueAvailable()"))
        assertTrue(explicitButton.contains("isEnabled = queueAvailable"))
        assertTrue(explicitButton.contains("신고 저장 기능 준비 중"))
        assertTrue(explicitButton.contains("contentDescription = if (queueAvailable)"))

        val explicitRequest = MainActivityRefactorSource.member(owner, "ensureExplicitReportAuthorityPreconditions")
        assertTrue(explicitRequest.contains("!queueEnabled"))
        assertTrue(wiring.contains("queueEnabled = PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE != null"))
        assertTrue(wiring.contains("override fun speakInteraction(message: String) { this@MainActivity.speakInteraction(message) }"))
        assertTrue(MainActivityRefactorSource.member(source, "requestExplicitReport").contains("reportCandidateController.requestExplicitReport(source)"))
        assertTrue(MainActivityRefactorSource.member(owner, "requestExplicitReport").contains("ensureExplicitReportAuthorityPreconditions("))
        assertTrue(explicitRequest.contains("reportCandidate=blocked:queue_disabled"))
        assertTrue(explicitRequest.contains("ui.speakInteraction(message)"))
    }

    @Test
    fun cleanupUsesDedicatedBoundedCoalescingExecutorAtEveryPrivacyBoundary() {
        val source = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val drainOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportQueueDrainController.kt").readText()
        val drainWiring = MainActivityRefactorSource.composition(source, "reportQueueDrainController")
        assertTrue(drainWiring.contains("ReportQueueDrainController("))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/LegacyPendingReportQueuePurgeController.kt").readText()
        val wiring = MainActivityRefactorSource.composition(source, "legacyPendingReportQueuePurgeController")
        assertTrue(wiring.contains("executor = executor"))
        assertTrue(wiring.contains("callbacks = LegacyQueuePurgeCallbacks { callback -> callbacks.post { callback() } }"))
        val ports = MainActivityRefactorSource.factory(source, "createPrivacyLegacyPurge")
        assertTrue(ports.contains("executor = { reportCleanupExecutor }"))
        assertTrue(ports.contains("AndroidPendingReportStore.purgeAllWithoutLoading(applicationContext)"))
        val authority = MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition")
        assertTrue(authority.contains("callbacks = object : AccountDeletionProgressCallbacks"))
        assertTrue(authority.contains("override fun post(callback: () -> Unit) = reportCleanupCallbackHandler.post { callback() }"))
        assertTrue(
            Regex(
                """private\s+val\s+reportQueueDrainExecutor(?:\s*:\s*ExecutorService)?\s*=\s*Executors\.newSingleThreadExecutor""",
            ).containsMatchIn(drainOwner),
        )
        assertTrue(
            Regex(
                """private\s+val\s+reportCleanupExecutor\s*=\s*Executors\.newSingleThreadExecutor""",
            ).containsMatchIn(source),
        )

        val helper =
            MainActivityRefactorSource.member(owner, "schedule")
        assertTrue(helper.contains("executor().execute"))
        assertFalse(
            "Keystore/filesystem cleanup must not share the report drain executor.",
            helper.contains("reportQueueDrainExecutor"),
        )
        assertTrue(
            "Cleanup executor must delegate to the bounded cleanup worker.",
            helper.contains("purgeOnWorker()"),
        )

        val maxAttempts =
            Regex(
                """const\s+val\s+MAX_ATTEMPTS\s*=\s*(\d+)""",
            ).find(owner)?.groupValues?.get(1)?.toInt()
                ?: error("Missing cleanup retry limit")
        assertTrue("Cleanup retries must be bounded to at most three attempts.", maxAttempts in 1..3)
        val cleanupWorker =
            MainActivityRefactorSource.member(owner, "purgeOnWorker")
        assertTrue(cleanupWorker.contains("0 until MAX_ATTEMPTS"))
        assertTrue(
            "Cleanup worker must perform the only opaque store purge.",
            cleanupWorker.contains("purgeLegacyStore()"),
        )

        assertTrue(helper.contains("if (destroyed) return false"))
        val coalescedWaiter =
            ReportStaticSourceInspector.blockAfter(
                helper,
                "if (inFlight)",
            )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                coalescedWaiter,
                "this.waiters +=",
                "activityToken = activityToken",
                "generation = this.generation",
                "callback = onComplete",
                "return false",
            ),
        )
        val completion =
            MainActivityRefactorSource.member(owner, "complete")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                completion,
                "destroyed",
                "generation != this.generation",
                "this.waiters.toList()",
                "this.waiters.clear()",
                "callbacks.post",
                "!destroyed",
                "waiter.activityToken == activityToken",
                "waiter.generation == this.generation",
                "waiter.callback(succeeded)",
            ),
        )

        val onCreate =
            ReportStaticSourceInspector.functionBlock(source, "override fun onCreate")
        assertTrue(
            "Startup must re-purge opaque legacy state.",
            onCreate.contains("scheduleLegacyPendingReportQueuePurge()"),
        )

        val consentWithdrawals =
            MainActivityRefactorSource.member(
                File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText(),
                "applyImmediateConsentWithdrawals",
            )
        val rawWithdrawal = consentWithdrawals
            .substringAfter("IntegratedConsentItem.RAW_SOURCE_COLLECTION ->")
            .substringBefore("IntegratedConsentItem.AUTOMATIC_REPORTING ->")
        val automaticWithdrawal = consentWithdrawals
            .substringAfter("IntegratedConsentItem.AUTOMATIC_REPORTING ->")
            .substringBefore("IntegratedConsentItem.MOBILE_NETWORK_TRANSFER ->")
        assertFalse(rawWithdrawal.contains("reports.scheduleLegacyPurge()"))
        assertTrue(automaticWithdrawal.contains("reports.scheduleLegacyPurge()"))

        val consent = MainActivityRefactorSource.composition(source, "integratedConsentController")
        assertTrue(consent.contains("portsFactory({ reportPrivacyConsentSession }, { legacyPendingReportQueuePurgeController }, { speechOutputStorageCoordinator })"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyConsent")
            .contains("override fun scheduleLegacyPurge() { legacyPurge().schedule() }"))
        val accountDeletion =
            MainActivityRefactorSource.function(source, "private fun scheduleAcceptedAccountDeletionLocalPurge")
        assertTrue(
            accountDeletion.contains("purge.purgeLocalDataOnWorker()"),
        )
        val accountDeletionLocalPurge =
            MainActivityRefactorSource.function(source, "private fun purgeAccountDeletionLocalDataOnCleanupThread")
        assertTrue(
            accountDeletionLocalPurge.contains(
                "legacyQueue.purgeOnWorker()",
            ),
        )

        assertTrue(MainActivityRefactorSource.composition(source, "accountDeletionProgressController")
            .contains("purge = AccountDeletionLocalDataPurge { accountDeletionLocalDataPurger.value.purgeLocalDataOnWorker() }"))
        assertTrue(MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
            .contains("legacyQueue = legacyPendingReportQueuePurgeController"))
        assertTrue(MainActivityRefactorSource.member(source, "scheduleLegacyPendingReportQueuePurge")
            .contains("legacyPendingReportQueuePurgeController.schedule(onComplete)"))
        val onDestroy =
            ReportStaticSourceInspector.functionBlock(source, "override fun onDestroy")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                onDestroy,
                "cancelReportQueueDrain()",
                "reportQueueDrainController.shutdownExecutor()",
            ),
        )
        val destruction = MainActivityRefactorSource.member(owner, "destroy")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            destruction, "destroyed = true", "generation += 1L", "inFlight = false", "waiters.clear()",
        ))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            onDestroy, "legacyPendingReportQueuePurgeController.destroy()", "reportCleanupExecutor.shutdownNow()",
        ))
        assertTrue(MainActivityRefactorSource.member(drainOwner, "shutdownExecutor").contains("reportQueueDrainExecutor.shutdownNow()"))
        assertFalse(onDestroy.contains("reportUploaderExecutor"))
    }

    @Test
    fun deviceUnsentEvidenceUsesRequestBoundActualPurgeResult() {
        val source = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
        val wiring = MainActivityRefactorSource.composition(source, "accountDeletionProgressController")
        assertTrue(wiring.contains("store = accountDeletionRecoveryStore"))
        assertTrue(wiring.contains("stages = accountDeletionWorkerStages"))
        assertTrue(wiring.contains("cleanupExecutor = { cleanupExecutor() }"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition").contains("cleanupExecutor = { accountDeletionCleanupExecutor }"))
        assertTrue(wiring.contains("purge = AccountDeletionLocalDataPurge { accountDeletionLocalDataPurger.value.purgeLocalDataOnWorker() }"))
        MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
        assertTrue(MainActivityRefactorSource.member(source, "purgeAccountDeletionLocalDataOnCleanupThread")
            .contains("accountDeletionLocalDataPurger.purgeLocalDataOnWorker()"))
        val recovery =
            MainActivityRefactorSource.function(source, "private fun scheduleAcceptedAccountDeletionLocalPurge")
        assertTrue(
            "DEVICE_UNSENT purge result must be durably bound into evidence before upload.",
            ReportStaticSourceInspector.appearsInOrder(
                recovery,
                "localPurgeInFlightRequestId = journal.requestId",
                "cleanupExecutor().execute",
                "stages.durable(workerAttempt)",
                "purge.purgeLocalDataOnWorker()",
                "result = if (purgeSucceeded) \"DELETED\" else \"FAILED\"",
                "deviceDeletionEvidenceSha256(evidenceWithoutHash)",
                "store.fallbackMarker.update(next)",
                "stateMachine().recordDeviceEvidence(",
                "stages.durable(workerAttempt)",
                "store.persistJournal(current)",
                "evidenceController.submitPendingAllowed(next, current)",
            ),
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                recovery,
                "if (!stages.allowed(workerAttempt)) return@execute",
                "markerUpdated",
                "!stages.allowed(workerAttempt)",
                "recordDeviceEvidence(",
            ),
        )
    }

    @Test
    fun cleanupStoreNeverLoadsPayloadAndSerializesOpaqueDeletion() {
        val source = ReportStaticSourceInspector.read(STORE_PATH)
        val functionNames =
            Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toSet()
        val forbiddenFunctions =
            setOf(
                "decrypt",
                "encrypt",
                "load",
                "loadQueue",
                "enqueue",
                "pending",
                "pendingFor",
                "drain",
                "retry",
                "ack",
                "acknowledge",
                "remove",
                "removeAfterValidatedSuccess",
                "markTransientFailure",
                "markTerminalFailure",
                "reserve",
                "release",
                "markSucceeded",
            )
        assertTrue(functionNames.intersect(forbiddenFunctions).isEmpty())

        listOf(
            "metadataJson",
            "imageBytes",
            "retryNotBefore",
            "consecutiveFailures",
            "terminalHttpStatus",
            "payloadSha256",
            "Cipher.getInstance",
            "readBytes(",
            "readText(",
            "inputStream(",
        ).forEach { token ->
            assertFalse("Cleanup-only store must not open payload state: $token", source.contains(token))
        }

        assertTrue(source.contains("private val PROCESS_LOCK = ReentrantLock()"))
        assertTrue(source.contains("PROCESS_LOCK.tryLock"))
        assertTrue(source.contains("PROCESS_LOCK.unlock()"))
        assertTrue(source.contains("RandomAccessFile(lockFile, \"rw\")"))
        assertTrue(Regex("""channel\.tryLock\s*\(\s*\)""").containsMatchIn(source))
        assertTrue(source.contains("fileLock.release()"))
        assertTrue(source.contains("\"pending_reports_v1.lock\""))

        assertTrue(source.contains("KeyStore.getInstance(KEYSTORE_PROVIDER)"))
        assertTrue(source.contains("\"AndroidKeyStore\""))
        assertTrue(source.contains("\"walksafe_pending_reports_v1\""))
        assertTrue(source.contains("keyStore.deleteEntry(KEY_ALIAS)"))
        assertTrue(source.contains("directory.deleteRecursively()"))

        val purge =
            ReportStaticSourceInspector.functionBlock(
                source,
                "internal fun purgeAllWithoutLoading",
            )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                purge,
                "ensurePendingMarker",
                "PROCESS_LOCK.tryLock",
                "purgeUnderProcessLock",
                "PROCESS_LOCK.unlock",
            ),
        )

        val lockedPurge =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun purgeUnderProcessLock",
            )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                lockedPurge,
                "runCatching { destroyKey() }",
                "deleteQueue(queueDirectory)",
                "if (!keyDestroyed || !queueDeleted)",
                "clearPendingMarker(markerFile)",
            ),
        )
    }

    private companion object {
        const val MAIN_ACTIVITY_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
        const val REPORT_QUEUE_CONTRACT_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/" +
                "ReportQueueContract.kt"
        const val BUILD_SCRIPT_PATH = "apps/android/app/build.gradle.kts"
        const val STORE_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/" +
                "AndroidPendingReportStore.kt"
    }
}

internal object ReportStaticSourceInspector {
    fun read(relativePath: String): String {
        var directory =
            File(System.getProperty("user.dir") ?: error("Missing user.dir system property"))
                .absoluteFile
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: error("Unable to locate source file: $relativePath")
        }
        error("Unable to locate source file: $relativePath")
    }

    fun functionBlock(source: String, signature: String): String {
        val masked = maskNonCode(source)
        val signatureOffset = masked.indexOf(signature)
        check(signatureOffset >= 0) { "Missing function: $signature" }
        val openingParenthesis = masked.indexOf('(', signatureOffset + signature.length)
        check(openingParenthesis >= 0) { "Missing parameter list: $signature" }
        var parenthesisDepth = 0
        var openingBrace = -1
        for (index in openingParenthesis until masked.length) {
            when (masked[index]) {
                '(' -> parenthesisDepth += 1
                ')' -> parenthesisDepth -= 1
                '{' -> if (parenthesisDepth == 0) {
                    openingBrace = index
                    break
                }
            }
        }
        check(openingBrace >= 0) { "Missing function body: $signature" }
        return matchingBlock(source, masked, openingBrace)
    }

    fun blockAfter(source: String, anchor: String): String {
        val masked = maskNonCode(source)
        val anchorOffset = masked.indexOf(anchor)
        check(anchorOffset >= 0) { "Missing block anchor: $anchor" }
        val openingBrace = masked.indexOf('{', anchorOffset + anchor.length)
        check(openingBrace >= 0) { "Missing block after: $anchor" }
        return matchingBlock(source, masked, openingBrace)
    }

    fun appearsInOrder(source: String, vararg tokens: String): Boolean {
        var offset = 0
        tokens.forEach { token ->
            val match = source.indexOf(token, offset)
            if (match < 0) return false
            offset = match + token.length
        }
        return true
    }

    private fun matchingBlock(source: String, masked: String, openingBrace: Int): String {
        var depth = 0
        for (index in openingBrace until masked.length) {
            when (masked[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openingBrace, index + 1)
                }
            }
        }
        error("Unclosed block after offset $openingBrace")
    }

    fun maskNonCode(source: String): String {
        val masked = source.toCharArray()
        var state = LexicalState.CODE
        var blockCommentDepth = 0
        var index = 0
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            val third = source.getOrNull(index + 2)
            when (state) {
                LexicalState.CODE -> when {
                    current == '/' && next == '/' -> {
                        blank(masked, index, 2)
                        index += 2
                        state = LexicalState.LINE_COMMENT
                    }
                    current == '/' && next == '*' -> {
                        blank(masked, index, 2)
                        index += 2
                        blockCommentDepth = 1
                        state = LexicalState.BLOCK_COMMENT
                    }
                    current == '"' && next == '"' && third == '"' -> {
                        blank(masked, index, 3)
                        index += 3
                        state = LexicalState.RAW_STRING
                    }
                    current == '"' -> {
                        blank(masked, index, 1)
                        index += 1
                        state = LexicalState.STRING
                    }
                    current == '\'' -> {
                        blank(masked, index, 1)
                        index += 1
                        state = LexicalState.CHAR
                    }
                    else -> index += 1
                }
                LexicalState.LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') {
                        state = LexicalState.CODE
                        index += 1
                    } else {
                        blank(masked, index, 1)
                        index += 1
                    }
                }
                LexicalState.BLOCK_COMMENT -> when {
                    current == '/' && next == '*' -> {
                        blank(masked, index, 2)
                        blockCommentDepth += 1
                        index += 2
                    }
                    current == '*' && next == '/' -> {
                        blank(masked, index, 2)
                        blockCommentDepth -= 1
                        index += 2
                        if (blockCommentDepth == 0) state = LexicalState.CODE
                    }
                    else -> {
                        blank(masked, index, 1)
                        index += 1
                    }
                }
                LexicalState.STRING,
                LexicalState.CHAR,
                -> {
                    val closing =
                        (state == LexicalState.STRING && current == '"') ||
                            (state == LexicalState.CHAR && current == '\'')
                    when {
                        current == '\\' && next != null -> {
                            blank(masked, index, 2)
                            index += 2
                        }
                        closing -> {
                            blank(masked, index, 1)
                            index += 1
                            state = LexicalState.CODE
                        }
                        else -> {
                            blank(masked, index, 1)
                            index += 1
                        }
                    }
                }
                LexicalState.RAW_STRING -> {
                    if (current == '"' && next == '"' && third == '"') {
                        blank(masked, index, 3)
                        index += 3
                        state = LexicalState.CODE
                    } else {
                        blank(masked, index, 1)
                        index += 1
                    }
                }
            }
        }
        return String(masked)
    }

    private fun blank(chars: CharArray, start: Int, length: Int) {
        for (index in start until minOf(chars.size, start + length)) {
            if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
        }
    }

    private enum class LexicalState {
        CODE,
        STRING,
        RAW_STRING,
        CHAR,
        LINE_COMMENT,
        BLOCK_COMMENT,
    }

    private const val MAX_PARENT_SEARCH_DEPTH = 10
}
