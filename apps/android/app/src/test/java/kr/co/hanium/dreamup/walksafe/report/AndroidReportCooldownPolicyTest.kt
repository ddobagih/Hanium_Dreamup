package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AndroidReportCooldownPolicyTest {
    @Test
    fun pendingDuplicateWindowIsItsOwnRuleWithTodaysTenMinuteValue() {
        val window = AndroidReportCooldownPolicy.PENDING_AUTOMATIC_DUPLICATE_WINDOW_MS
        assertEquals(10L * 60L * 1_000L, window)
        assertEquals(true, AndroidReportCooldownPolicy.pendingAutomaticDuplicateSuppressed(1_000L, 1_000L))
        assertEquals(true, AndroidReportCooldownPolicy.pendingAutomaticDuplicateSuppressed(1_000L, 1_000L + window - 1L))
        assertEquals(false, AndroidReportCooldownPolicy.pendingAutomaticDuplicateSuppressed(1_000L, 1_000L + window))
        assertEquals(true, AndroidReportCooldownPolicy.pendingAutomaticDuplicateSuppressed(1_000L, 999L))
        assertEquals(false, AndroidReportCooldownPolicy.pendingAutomaticDuplicateSuppressed(0L, 1_000L))
    }

    @Test
    fun realDistanceMatchClosesRoundedCellBoundaryBypass() {
        val leftOfCellBoundary = scope(longitude = 127.000049)
        val rightOfCellBoundary = scope(longitude = 127.000051)

        assertNotEquals(leftOfCellBoundary.storageKey, rightOfCellBoundary.storageKey)
        assertTrue(AndroidReportCooldownPolicy.isSameCooldownArea(leftOfCellBoundary, rightOfCellBoundary))
        assertTrue(
            !AndroidReportCooldownPolicy.isSameCooldownArea(
                leftOfCellBoundary,
                scope(latitude = 37.0003, longitude = 127.000049),
            ),
        )
        assertTrue(
            !AndroidReportCooldownPolicy.isSameCooldownArea(
                leftOfCellBoundary,
                scope(actorId = "field-user-2", longitude = 127.000049),
            ),
        )
    }

    @Test
    fun automaticCooldownSurvivesTrackerAndCellChangesButVoiceBypassesIt() {
        val store = AndroidReportAttemptStore()
        val first = store.acquire(scope(longitude = 127.000049), nowMs = 1_000L, bypassAutomaticCooldown = false)
            as AndroidReportAttemptResult.Allowed
        store.markSucceeded(first.lease, nowMs = 2_000L)
        store.release(first.lease)

        val automatic = store.acquire(
            scope(longitude = 127.000051),
            nowMs = 3_000L,
            bypassAutomaticCooldown = false,
        ) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, automatic.reason)
        assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS - 1_000L, automatic.remainingMs)

        val voice = store.acquire(
            scope(longitude = 127.000051),
            nowMs = 3_000L,
            bypassAutomaticCooldown = true,
        ) as AndroidReportAttemptResult.Allowed
        store.release(voice.lease)

        val afterBoundary = store.acquire(
            scope(longitude = 127.000051),
            nowMs = 2_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            bypassAutomaticCooldown = false,
        )
        assertTrue(afterBoundary is AndroidReportAttemptResult.Allowed)
    }

    @Test
    fun wallClockRollbackDoesNotExtendSameBootAutomaticCooldown() {
        val tenAmMs = 10L * 60L * 60L * 1_000L
        val nineAmMs = 9L * 60L * 60L * 1_000L
        var elapsedRealtimeMs = 1_000L
        val store = AndroidReportAttemptStore(monotonicNowMs = { elapsedRealtimeMs })
        val first = store.acquire(scope(), tenAmMs, false) as AndroidReportAttemptResult.Allowed
        store.markSucceeded(first.lease, tenAmMs)
        store.release(first.lease)

        elapsedRealtimeMs += 9L * 60L * 1_000L
        val blocked = store.acquire(scope(longitude = 127.000051), nineAmMs + 9L * 60L * 1_000L, false)
            as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, blocked.reason)
        assertEquals(60_000L, blocked.remainingMs)

        val manual = store.acquire(scope(), nineAmMs + 9L * 60L * 1_000L, true)
        assertTrue(manual is AndroidReportAttemptResult.Allowed)
        store.release((manual as AndroidReportAttemptResult.Allowed).lease)

        elapsedRealtimeMs += 2L * 60L * 1_000L
        val afterCooldown = store.acquire(scope(), nineAmMs + 11L * 60L * 1_000L, false)
        assertTrue(afterCooldown is AndroidReportAttemptResult.Allowed)
        assertTrue(store.successfulCooldowns(nineAmMs + 11L * 60L * 1_000L).isEmpty())
    }

    @Test
    fun restoredWallClockCooldownStartsConservativeMonotonicFallback() {
        val tenAmMs = 10L * 60L * 60L * 1_000L
        val nineAmMs = 9L * 60L * 60L * 1_000L
        val saved = listOf(AndroidReportSuccessfulCooldown(scope(), tenAmMs))
        var elapsedRealtimeMs = 1_000L
        val restored = AndroidReportAttemptStore(monotonicNowMs = { elapsedRealtimeMs })
        restored.restoreSuccessfulCooldowns(saved, nineAmMs)

        val initial = restored.acquire(scope(), nineAmMs, false) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS, initial.remainingMs)

        elapsedRealtimeMs += 9L * 60L * 1_000L
        val blocked = restored.acquire(scope(), nineAmMs - 51L * 60L * 1_000L, false)
            as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, blocked.reason)
        assertEquals(60_000L, blocked.remainingMs)

        elapsedRealtimeMs += 2L * 60L * 1_000L
        val allowed = restored.acquire(scope(), nineAmMs - 49L * 60L * 1_000L, false)
        assertTrue(allowed is AndroidReportAttemptResult.Allowed)
    }

    @Test
    fun stateStoreIsCappedAndExpiredEntriesArePruned() {
        assertEquals(512, AndroidReportCooldownPolicy.MAX_TRACKED_STATES)
        val store = AndroidReportAttemptStore(maxTrackedStates = 3)
        repeat(3) { index ->
            val allowed = store.acquire(
                scope(latitude = 37.0 + index * 0.001),
                nowMs = 1_000L,
                bypassAutomaticCooldown = false,
            ) as AndroidReportAttemptResult.Allowed
            store.release(allowed.lease)
        }

        val capacityBlocked = store.acquire(
            scope(latitude = 37.003),
            nowMs = 1_001L,
            bypassAutomaticCooldown = false,
        ) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.STATE_CAPACITY, capacityBlocked.reason)
        assertEquals(3, store.trackedStateCount())

        val afterExpiry = store.acquire(
            scope(latitude = 37.003),
            nowMs = 1_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            bypassAutomaticCooldown = false,
        )
        assertTrue(afterExpiry is AndroidReportAttemptResult.Allowed)
        assertEquals(1, store.trackedStateCount())
    }

    @Test
    fun successfulReportMovesRadiusCenterWithoutAllowingChainedBoundaryBypass() {
        val store = AndroidReportAttemptStore()
        val first = store.acquire(scope(latitude = 37.0), 1_000L, false) as AndroidReportAttemptResult.Allowed
        store.markSucceeded(first.lease, 1_000L)
        store.release(first.lease)

        val secondAtTwentyMeters = store.acquire(
            scope(latitude = 37.00018),
            1_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            false,
        ) as AndroidReportAttemptResult.Allowed
        store.markSucceeded(secondAtTwentyMeters.lease, 1_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS)
        store.release(secondAtTwentyMeters.lease)

        val fortyMetersFromOriginal = store.acquire(
            scope(latitude = 37.00036),
            2_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            false,
        ) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, fortyMetersFromOriginal.reason)
    }

    @Test
    fun successfulCooldownCanBeBoundedlyRestoredAfterActivityOrProcessRecreation() {
        val firstStore = AndroidReportAttemptStore(maxTrackedStates = 3)
        val first = firstStore.acquire(scope(), 1_000L, false) as AndroidReportAttemptResult.Allowed
        firstStore.markSucceeded(first.lease, 2_000L)
        firstStore.release(first.lease)
        val persisted = firstStore.successfulCooldowns(nowMs = 3_000L)

        val restoredStore = AndroidReportAttemptStore(maxTrackedStates = 3)
        restoredStore.restoreSuccessfulCooldowns(persisted, nowMs = 3_000L)
        val nearby = restoredStore.acquire(
            scope(longitude = 127.000051),
            nowMs = 4_000L,
            bypassAutomaticCooldown = false,
        ) as AndroidReportAttemptResult.Blocked

        assertEquals(1, persisted.size)
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, nearby.reason)
        assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS - 2_000L, nearby.remainingMs)
    }

    @Test
    fun restoreDropsExpiredCooldownsAndCapsFutureClockSkew() {
        val restoredStore = AndroidReportAttemptStore(maxTrackedStates = 2)
        restoredStore.restoreSuccessfulCooldowns(
            listOf(
                AndroidReportSuccessfulCooldown(scope(latitude = 37.0), lastUploadedAtMs = 1_000L),
                AndroidReportSuccessfulCooldown(scope(latitude = 37.001), lastUploadedAtMs = 99_000L),
                AndroidReportSuccessfulCooldown(scope(latitude = 37.002), lastUploadedAtMs = 99_000L),
            ),
            nowMs = 20_000L,
        )

        assertEquals(2, restoredStore.successfulCooldowns(nowMs = 20_000L).size)
        val futureRecord = restoredStore.acquire(scope(latitude = 37.001), 20_001L, false)
            as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS - 1L, futureRecord.remainingMs)
    }

    @Test
    fun queuedAutomaticMetadataRestoresExactCooldownScope() {
        val metadata = automaticMetadata()
        val payload = requireNotNull(
            FrozenReportPayload.freeze(
                "123e4567-e89b-42d3-a456-426614174000",
                metadata.toString().toByteArray(),
                reportTestJpeg(),
            ),
        )
        val report = QueuedReport(
            payload = payload,
            priority = ReportQueuePriority.AUTOMATIC,
            reporterActorId = "field-user-1",
            walkSessionId = "123e4567-e89b-42d3-a456-426614174001",
            consentReceiptSha256 = "c".repeat(64),
            createdAtEpochMs = 1_000L,
            expiresAtEpochMs = 1_000L + REPORT_QUEUE_TTL_MS,
        )

        assertEquals(scope(), report.automaticCooldownScopeOrNull())
        assertNull(
            report.copy(priority = ReportQueuePriority.EXPLICIT)
                .automaticCooldownScopeOrNull(),
        )
        assertNull(
            automaticReportCooldownScopeOrNull(
                automaticMetadata().put("trigger", AndroidReportCandidatePolicy.TRIGGER_VOICE),
            ),
        )
    }

    @Test
    fun wallClockJumpsDoNotReleaseOrExtendLiveCooldownOrRemoveItsSnapshot() {
        for (jumpMs in wallClockJumps()) {
            val uploadedAtMs = 10L * 24L * 60L * 60L * 1_000L
            var monotonicMs = 1_000L
            val store = AndroidReportAttemptStore(monotonicNowMs = { monotonicMs })
            val lease = (store.acquire(scope(), uploadedAtMs, false) as AndroidReportAttemptResult.Allowed).lease
            store.markSucceeded(lease, uploadedAtMs)
            store.release(lease)

            monotonicMs += 60_000L
            val changedWallMs = uploadedAtMs + jumpMs + 60_000L
            assertEquals(1, store.successfulCooldowns(changedWallMs).size)
            val blocked = store.acquire(scope(longitude = 127.000051), changedWallMs, false)
                as AndroidReportAttemptResult.Blocked
            assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, blocked.reason)
            assertEquals(540_000L, blocked.remainingMs)

            val manual = store.acquire(scope(), changedWallMs, true) as AndroidReportAttemptResult.Allowed
            store.release(manual.lease)
            val otherActor = store.acquire(scope(actorId = "field-user-2"), changedWallMs, false)
                as AndroidReportAttemptResult.Allowed
            store.release(otherActor.lease)
            val outsideRadius = store.acquire(scope(latitude = 37.0003), changedWallMs, false)
                as AndroidReportAttemptResult.Allowed
            store.release(outsideRadius.lease)
            assertTrue(!AndroidReportCooldownPolicy.isSameCooldownArea(scope(), scope().copy(className = "other")))

            monotonicMs = 1_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS
            assertTrue(store.successfulCooldowns(changedWallMs).isEmpty())
            assertTrue(store.acquire(scope(), changedWallMs, false) is AndroidReportAttemptResult.Allowed)
        }
    }

    @Test
    fun sameBootJsonRecreationPreservesRemainingTimeAcrossJumpsBeforeOrAfterSaving() {
        for (jumpMs in wallClockJumps()) {
            for (jumpBeforeSave in listOf(false, true)) {
                val uploadedAtMs = 10L * 24L * 60L * 60L * 1_000L
                var monotonicMs = 1_000L
                fun freshStore() = AndroidReportAttemptStore(
                    monotonicNowMs = { monotonicMs },
                    bootCountProvider = { 42 },
                )
                val original = freshStore()
                val lease = (original.acquire(scope(), uploadedAtMs, false) as AndroidReportAttemptResult.Allowed).lease
                original.markSucceeded(lease, uploadedAtMs)
                original.release(lease)
                monotonicMs += 30_000L
                val savedWallMs = uploadedAtMs + 30_000L + if (jumpBeforeSave) jumpMs else 0L
                val saved = roundTrip(original.successfulCooldowns(savedWallMs))
                assertEquals(1, saved.size)
                assertEquals(42, saved.single().bootCount)
                assertEquals(570_000L, saved.single().cooldownRemainingAtMarkMs)

                monotonicMs = 181_000L
                val changedWallMs = uploadedAtMs + jumpMs + 180_000L
                val restored = freshStore()
                restored.restoreSuccessfulCooldowns(saved, changedWallMs)
                val blocked = restored.acquire(scope(), changedWallMs, false) as AndroidReportAttemptResult.Blocked
                assertEquals(420_000L, blocked.remainingMs)

                // A second save and fresh store must not restart the ten-minute interval.
                val secondSave = roundTrip(restored.successfulCooldowns(changedWallMs))
                monotonicMs = 600_999L
                val secondRestore = freshStore()
                secondRestore.restoreSuccessfulCooldowns(secondSave, changedWallMs)
                val boundary = secondRestore.acquire(scope(), changedWallMs, false) as AndroidReportAttemptResult.Blocked
                assertEquals(1L, boundary.remainingMs)
                monotonicMs += 1L
                assertTrue(secondRestore.acquire(scope(), changedWallMs, false) is AndroidReportAttemptResult.Allowed)
                assertTrue(secondRestore.successfulCooldowns(changedWallMs).isEmpty())
                val expiredRestore = freshStore()
                expiredRestore.restoreSuccessfulCooldowns(secondSave, changedWallMs)
                assertEquals(0, expiredRestore.trackedStateCount())
            }
        }
    }

    @Test
    fun capacityCannotEvictActiveCooldownAndExpiresByMonotonicTimeAfterClockJumps() {
        for (jumpMs in wallClockJumps()) {
            var monotonicMs = 1_000L
            val wallMs = 10L * 24L * 60L * 60L * 1_000L
            val store = AndroidReportAttemptStore(maxTrackedStates = 2, monotonicNowMs = { monotonicMs })
            repeat(2) { index ->
                val lease = (store.acquire(scope(latitude = 37.0 + index * 0.001), wallMs, false)
                    as AndroidReportAttemptResult.Allowed).lease
                store.markSucceeded(lease, wallMs)
                store.release(lease)
            }
            monotonicMs += 60_000L
            val newScope = scope(latitude = 37.002)
            val atCapacity = store.acquire(newScope, wallMs + jumpMs, false) as AndroidReportAttemptResult.Blocked
            assertEquals(AndroidReportAttemptBlockReason.STATE_CAPACITY, atCapacity.reason)
            assertEquals(2, store.successfulCooldowns(wallMs + jumpMs).size)

            monotonicMs = 601_000L
            assertTrue(store.acquire(newScope, wallMs + jumpMs, false) is AndroidReportAttemptResult.Allowed)
            assertEquals(1, store.trackedStateCount())
        }
    }

    @Test
    fun restoreCapacityAndOrderingUseRemainingCooldownRatherThanWallTimestamps() {
        val wallMs = 10L * 24L * 60L * 60L * 1_000L
        val monotonicMs = 100_000L
        val records = listOf(
            AndroidReportSuccessfulCooldown(scope(), wallMs + 3_600_000L, 0L, 60_000L, 42),
            AndroidReportSuccessfulCooldown(scope(latitude = 37.001), wallMs + 1_000L, monotonicMs, 120_000L, 42),
            AndroidReportSuccessfulCooldown(scope(latitude = 37.002), wallMs - 3_600_000L, monotonicMs, 500_000L, 42),
            AndroidReportSuccessfulCooldown(scope(latitude = 37.003), wallMs - 7_200_000L, monotonicMs, 400_000L, 42),
        )
        val store = AndroidReportAttemptStore(2, { monotonicMs }, { 42 })
        store.restoreSuccessfulCooldowns(roundTrip(records), wallMs)
        val saved = store.successfulCooldowns(wallMs)
        assertEquals(listOf(scope(latitude = 37.002), scope(latitude = 37.003)), saved.map { it.scope })
        assertEquals(listOf(500_000L, 400_000L), saved.map { it.cooldownRemainingAtMarkMs })
        assertEquals(2, store.trackedStateCount())
    }

    @Test
    fun restoringOverLiveStateKeepsLongestRemainingCooldownRegardlessOfWallTimestamp() {
        val wallMs = 10L * 24L * 60L * 60L * 1_000L
        var monotonicMs = 1_000L
        val store = AndroidReportAttemptStore(monotonicNowMs = { monotonicMs }, bootCountProvider = { 42 })
        val lease = (store.acquire(scope(), wallMs, false) as AndroidReportAttemptResult.Allowed).lease
        store.markSucceeded(lease, wallMs)
        store.release(lease)
        monotonicMs += 60_000L
        store.restoreSuccessfulCooldowns(
            listOf(AndroidReportSuccessfulCooldown(scope(), wallMs + 3_600_000L, monotonicMs, 120_000L, 42)),
            wallMs + 3_600_000L,
        )
        val blocked = store.acquire(scope(), wallMs + 3_600_000L, false) as AndroidReportAttemptResult.Blocked
        assertEquals(540_000L, blocked.remainingMs)
        store.restoreSuccessfulCooldowns(
            listOf(AndroidReportSuccessfulCooldown(scope(), wallMs - 3_600_000L, monotonicMs, 590_000L, 42)),
            wallMs + 3_600_000L,
        )
        val afterLongerRestore = store.acquire(scope(), wallMs + 3_600_000L, false) as AndroidReportAttemptResult.Blocked
        assertEquals(590_000L, afterLongerRestore.remainingMs)
    }

    @Test
    fun changedUnknownOrUnreadableBootUsesBoundedWallFallbackRatherThanStoredElapsedTime() {
        val wallMs = 10L * 24L * 60L * 60L * 1_000L
        val saved = roundTrip(listOf(AndroidReportSuccessfulCooldown(scope(), wallMs, 1_000L, 600_000L, 42)))
        val bootProviders: List<() -> Int?> = listOf({ 43 }, { null }, { -1 }, { error("boot count unavailable") })
        for (bootProvider in bootProviders) {
            var monotonicMs = 2_000L
            val restored = AndroidReportAttemptStore(monotonicNowMs = { monotonicMs }, bootCountProvider = bootProvider)
            restored.restoreSuccessfulCooldowns(saved, wallMs + 120_000L)
            val blocked = restored.acquire(scope(), wallMs + 120_000L, false) as AndroidReportAttemptResult.Blocked
            assertEquals(480_000L, blocked.remainingMs)
            monotonicMs += 480_000L
            assertTrue(restored.acquire(scope(), wallMs + 120_000L, false) is AndroidReportAttemptResult.Allowed)

            val forwardJump = AndroidReportAttemptStore(monotonicNowMs = { 2_000L }, bootCountProvider = bootProvider)
            forwardJump.restoreSuccessfulCooldowns(saved, wallMs + 3_600_000L)
            assertEquals(0, forwardJump.trackedStateCount())
            val backwardJump = AndroidReportAttemptStore(monotonicNowMs = { 2_000L }, bootCountProvider = bootProvider)
            backwardJump.restoreSuccessfulCooldowns(saved, wallMs - 3_600_000L)
            assertEquals(600_000L, (backwardJump.acquire(scope(), wallMs - 3_600_000L, false)
                as AndroidReportAttemptResult.Blocked).remainingMs)
        }
    }

    @Test
    fun legacyOrInvalidMonotonicMetadataAndElapsedResetUseWallFallback() {
        val wallMs = 1_000_000L
        val valid = AndroidReportSuccessfulCooldown(scope(), wallMs, 2_000L, 600_000L, 42)
        val records = listOf(
            AndroidReportSuccessfulCooldown(scope(), wallMs),
            valid.copy(bootCount = null),
            valid.copy(cooldownMonotonicMarkMs = null),
            valid.copy(cooldownRemainingAtMarkMs = null),
            valid.copy(cooldownRemainingAtMarkMs = 600_001L),
            valid.copy(cooldownMonotonicMarkMs = -1L),
            valid, // Current elapsed time below the saved mark cannot establish continuity.
        )
        for (record in records) {
            val restored = AndroidReportAttemptStore(monotonicNowMs = { 1_000L }, bootCountProvider = { 42 })
            restored.restoreSuccessfulCooldowns(roundTrip(listOf(record)), wallMs + 120_000L)
            assertEquals(480_000L, (restored.acquire(scope(), wallMs + 120_000L, false)
                as AndroidReportAttemptResult.Blocked).remainingMs)
        }
        val legacy = roundTrip(listOf(records.first())).single()
        assertNull(legacy.bootCount)
        assertNull(legacy.cooldownMonotonicMarkMs)
        assertNull(legacy.cooldownRemainingAtMarkMs)
    }

    @Test
    fun fractionalPersistedDurationFallsBackToWallTimeWithoutReleasingCooldown() {
        val wallMs = 1_000_000L
        val encoded = AndroidReportSuccessfulCooldown(scope(), wallMs, 1_000L, 600_000L, 42)
            .toJson().put("cooldown_remaining_at_mark_ms", -0.5)
        val decoded = requireNotNull(AndroidReportSuccessfulCooldown.fromJson(JSONObject(encoded.toString())))
        val restored = AndroidReportAttemptStore(monotonicNowMs = { 2_000L }, bootCountProvider = { 42 })
        restored.restoreSuccessfulCooldowns(listOf(decoded), wallMs + 120_000L)

        val blocked = restored.acquire(scope(), wallMs + 120_000L, false) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, blocked.reason)
        assertEquals(480_000L, blocked.remainingMs)
        assertNull(decoded.cooldownRemainingAtMarkMs)
    }

    @Test
    fun overflowingPersistedBootCountCannotWrapToCurrentBootAndReleaseCooldown() {
        val wallMs = 1_000_000L
        val encoded = AndroidReportSuccessfulCooldown(scope(), wallMs, 1_000L, 60_000L, 42)
            .toJson().put("boot_count", 4_294_967_338L)
        val decoded = requireNotNull(AndroidReportSuccessfulCooldown.fromJson(JSONObject(encoded.toString())))
        val restored = AndroidReportAttemptStore(monotonicNowMs = { 100_000L }, bootCountProvider = { 42 })
        restored.restoreSuccessfulCooldowns(listOf(decoded), wallMs + 120_000L)

        val blocked = restored.acquire(scope(), wallMs + 120_000L, false) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, blocked.reason)
        assertEquals(480_000L, blocked.remainingMs)
        assertNull(decoded.bootCount)
    }

    @Test
    fun optionalClockMetadataRejectsCoercionAndOverflowWithoutChangingRequiredFields() {
        val valid = AndroidReportSuccessfulCooldown(scope(), 1_000_000L, 1_000L, 600_000L, 42)
        val keys = listOf("cooldown_monotonic_mark_ms", "cooldown_remaining_at_mark_ms", "boot_count")
        val invalidValues = listOf(-1L, 0.5f, 0.5, "42", true, JSONObject.NULL, java.math.BigInteger("9223372036854775808"))
        for (key in keys) {
            for (invalid in invalidValues) {
                val decoded = requireNotNull(AndroidReportSuccessfulCooldown.fromJson(valid.toJson().put(key, invalid)))
                assertEquals(valid.scope, decoded.scope)
                assertEquals(valid.lastUploadedAtMs, decoded.lastUploadedAtMs)
                when (key) {
                    "cooldown_monotonic_mark_ms" -> assertNull(decoded.cooldownMonotonicMarkMs)
                    "cooldown_remaining_at_mark_ms" -> assertNull(decoded.cooldownRemainingAtMarkMs)
                    "boot_count" -> assertNull(decoded.bootCount)
                }
            }
        }
        assertNull(AndroidReportSuccessfulCooldown.fromJson(valid.toJson().put("boot_count", 2_147_483_648L))!!.bootCount)
        assertNull(AndroidReportSuccessfulCooldown.fromJson(valid.toJson().put("cooldown_remaining_at_mark_ms", 600_001L))!!
            .cooldownRemainingAtMarkMs)
    }

    @Test
    fun integralClockMetadataRoundTripPreservesLongMarksAndMaximumBootCount() {
        val wallMs = 1_000_000L
        val markMs = 5_000_000_000L
        val valid = AndroidReportSuccessfulCooldown(scope(), wallMs, markMs, 600_000L, Int.MAX_VALUE)
        val saved = roundTrip(listOf(valid))
        assertEquals(valid, saved.single())
        val restored = AndroidReportAttemptStore(
            monotonicNowMs = { markMs + 1_000L },
            bootCountProvider = { Int.MAX_VALUE },
        )
        restored.restoreSuccessfulCooldowns(saved, wallMs + 3_600_000L)
        val blocked = restored.acquire(scope(), wallMs + 3_600_000L, false) as AndroidReportAttemptResult.Blocked
        assertEquals(599_000L, blocked.remainingMs)
    }

    private fun wallClockJumps(): List<Long> = listOf(-72L, -1L, 1L, 72L).map { it * 60L * 60L * 1_000L }

    private fun roundTrip(cooldowns: List<AndroidReportSuccessfulCooldown>): List<AndroidReportSuccessfulCooldown> {
        val json = org.json.JSONArray()
        cooldowns.forEach { json.put(it.toJson()) }
        val restored = org.json.JSONArray(json.toString())
        return (0 until restored.length()).map { index ->
            requireNotNull(AndroidReportSuccessfulCooldown.fromJson(restored.getJSONObject(index)))
        }
    }

    private fun scope(
        actorId: String = "field-user-1",
        latitude: Double = 37.0,
        longitude: Double = 127.0,
    ): AndroidReportSpatialScope {
        return AndroidReportCooldownPolicy.spatialScopeOrNull(
            actorId = actorId,
            className = AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK,
            location = TrustedLocation(latitude, longitude, 5f, 1_000L),
        )!!
    }

    private fun automaticMetadata(): JSONObject = JSONObject()
        .put("schema_version", "detect.v2")
        .put("trigger", AndroidReportCandidatePolicy.TRIGGER_AUTO)
        .put("auto_reported", true)
        .put("reporter_user_id", "field-user-1")
        .put("class_name", AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK)
        .put(
            "gps",
            JSONObject()
                .put("latitude", 37.0)
                .put("longitude", 127.0),
        )
}
