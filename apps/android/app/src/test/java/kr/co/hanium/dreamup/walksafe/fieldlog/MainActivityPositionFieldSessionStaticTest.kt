package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPositionFieldSessionStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun recorderIsDebugOnlyPrivateAndPurposeSeparated() {
        assertTrue(source.contains("if (BuildConfig.DEBUG) initializePositionFieldRecorder()"))
        val initialize = functionBlock("private fun initializePositionFieldRecorder(")
        assertTrue(initialize.contains("if (!BuildConfig.DEBUG"))
        assertTrue(initialize.contains("noBackupRootDirectory = noBackupFilesDir"))
        assertTrue(initialize.contains("walksafe.position-field-session.aead.v"))
        assertTrue(initialize.contains("UUID.randomUUID().toString()"))
        assertTrue(initialize.contains("PREF_POSITION_FIELD_LOCAL_SCOPE_ID"))
        assertFalse(initialize.contains("reporterUserId"))
        assertFalse(initialize.contains("actorId"))
    }

    @Test
    fun explicitExactCoordinateAndChestCalibrationConsentGateStart() {
        val start = functionBlock("private fun startPositionFieldSession(")
        assertTrue(source.contains("정확한 좌표가 평문 JSONL로 내보내지는 것을 확인했습니다"))
        assertTrue(source.contains("figure-8 자력계 보정을 완료했습니다"))
        assertTrue(start.contains("positionFieldExactExportCheck.isChecked"))
        assertTrue(start.contains("positionFieldChestCalibrationCheck.isChecked"))
        assertTrue(start.contains("scenario = \"FIELD_SURVEY\""))
        assertTrue(start.contains("environment = \"MIXED\""))
        assertTrue(start.contains("direction = \"FORWARD\""))
        assertTrue(start.contains("walkEpoch = walkEpoch.recoveryGeneration"))
        assertTrue(start.contains("GatewaySessionProcessCoordinator.snapshot().generation"))
        assertFalse(start.contains("GatewayCapacityProcessState"))
        assertFalse(start.contains("RawCollection"))
    }

    @Test
    fun safExportIsBoundAndDeletesFailedOrStaleDestination() {
        val launch = functionBlock("private fun exportLastPositionFieldSession(")
        val result = functionBlock("private fun handlePositionFieldExportResult(")
        assertTrue(launch.contains("Intent.ACTION_CREATE_DOCUMENT"))
        assertTrue(launch.contains("pendingPositionFieldExportSessionId"))
        assertTrue(launch.contains("pendingPositionFieldExportGeneration"))
        assertTrue(result.contains("generation == positionFieldExportGeneration"))
        assertTrue(result.contains("sessionId == latestCompletedSessionId"))
        assertTrue(result.contains("partialDestinationMustBeDeleted"))
        assertTrue(result.contains("deleteOrTruncatePositionFieldDestination(destination)"))
        assertFalse(launch.contains("ACTION_SEND"))
        assertFalse(launch.contains("FileProvider"))
    }

    @Test
    fun recorderHooksContainOnlyPositioningAndMotionMetadata() {
        val gnss = functionBlock("private fun appendPositionFieldGnssTrace(")
        val motion = functionBlock("private fun appendPositionFieldMotionTrace(")
        assertTrue(gnss.contains("rawPosition = PositionTraceCoordinate("))
        assertTrue(gnss.contains("filteredPosition = PositionTraceCoordinate("))
        assertTrue(gnss.contains("currentAcceptedRouteMatchFor(filtered.elapsedRealtimeMs)"))
        assertTrue(gnss.contains("matchedPosition = routeMatch?.matchedPoint"))
        assertTrue(gnss.contains("PositionGnssTrace("))
        assertTrue(gnss.contains("PositionStepProfileTrace("))
        assertTrue(gnss.contains("measurementElapsedRealtimeNs = measurementElapsedRealtimeNs"))
        assertTrue(gnss.contains("measurementUtcEpochMs = measurementUtcEpochMs"))
        assertTrue(gnss.contains("source = PositionTraceSource.GNSS"))
        assertTrue(motion.contains("stepDetected = stepDetected"))
        assertTrue(motion.contains("currentPositionStationaryTrace(zuptApplied)"))
        assertTrue(motion.contains("measurementElapsedRealtimeMs: Long"))
        assertTrue(motion.contains("PositionTraceSource.SENSOR_MONOTONIC_ONLY"))
        assertTrue(motion.contains("PositionTraceSource.SENSOR_GNSS_ANCHORED"))
        val combined = gnss + motion
        assertFalse(combined.contains("accelerometer"))
        assertFalse(combined.contains("gyroscope"))
        assertFalse(combined.contains("audio"))
        assertFalse(combined.contains("image"))
        assertFalse(combined.contains("destination"))
        assertFalse(combined.contains("query"))
    }

    @Test
    fun lifecycleCheckpointGeomagneticAndChestGatesAreExplicit() {
        val lease = functionBlock("private fun currentPositionFieldLeaseOrNull(")
        val checkpoint = functionBlock("private fun markPositionFieldCheckpoint(")
        val start = functionBlock("private fun startPositionFieldSession(")
        val stop = functionBlock("private fun stopActivePositionFieldSession(")
        val location = functionBlock("private fun handleLocationUpdate(")
        val orientationReference = functionBlock("private fun updateRouteOrientationReference(")
        val chest = functionBlock("private fun chestMountedHeadingForStep(")
        assertTrue(lease.contains("isActivityForeground"))
        assertTrue(lease.contains("isWalkSessionRuntimeActive()"))
        assertTrue(lease.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(lease.contains("positionFieldLocalScopeId == positionFieldLeaseScopeId"))
        assertTrue(checkpoint.contains("POSITION_FIELD_CHECKPOINT_STATIONARY_MS"))
        assertTrue(checkpoint.contains("PositionStationaryState.STATIONARY"))
        assertTrue(checkpoint.contains("stationarySinceMs < sessionStartedAtMs"))
        assertTrue(checkpoint.contains("\"CP%03d\""))
        assertTrue(checkpoint.contains("ordinal = ordinal + 1"))
        assertTrue(checkpoint.contains("positionFieldUtcEpochMsFor(measurementElapsedRealtimeNs)"))
        assertTrue(start.contains("latestPositionStationaryState = PositionStationaryState.UNKNOWN"))
        assertTrue(start.contains("latestPositionStationarySinceMs = null"))
        assertTrue(stop.contains("latestPositionStationaryState = PositionStationaryState.UNKNOWN"))
        assertTrue(stop.contains("latestPositionStationarySinceMs = null"))
        assertTrue(location.contains("updateRouteOrientationReference(location)"))
        assertTrue(orientationReference.contains("ensureEarthOrientationForLocation()"))
        assertTrue(orientationReference.contains("earthOrientationTracker.updateGeomagneticReference("))
        assertTrue(chest.contains("PhoneMountingMethod.CHEST_FORWARD"))
        assertTrue(chest.contains("phoneMountingOutputsAllowed"))
        assertTrue(chest.contains("chestMountedHeadingAt(timestampMs, maximumAgeMs)"))
    }

    @Test
    fun logoutAndPrivacyDeletionPurgeAndRotateTheLocalScope() {
        val logout = functionBlock("private fun onAccountLogoutClicked(")
        val deletion = functionBlock("private fun applyAccountDeletionRuntimeFence(")
        val purge = functionBlock("private fun purgeAndRotatePositionFieldScope(")
        val complete = functionBlock("private fun completePendingPositionFieldPurge(")
        assertTrue(logout.contains("purgeAndRotatePositionFieldScope()"))
        assertTrue(deletion.contains("purgeAndRotatePositionFieldScope()"))
        assertTrue(purge.contains("PREF_POSITION_FIELD_PURGE_PENDING"))
        assertTrue(complete.contains("positionFieldRecorder?.purge()"))
        assertTrue(complete.contains("positionFieldStorageBlocked = true"))
        assertTrue(complete.contains("UUID.randomUUID().toString()"))
    }

    @Test
    fun persistedPurgeFenceRecoversBeforeRecorderAccessAfterRestart() {
        val initialize = functionBlock("private fun initializePositionFieldRecorder(")
        val purge = functionBlock("private fun purgeAndRotatePositionFieldScope(")
        val complete = functionBlock("private fun completePendingPositionFieldPurge(")
        val list = functionBlock("private fun exportLastPositionFieldSession(")
        val export = functionBlock("private fun handlePositionFieldExportResult(")

        assertTrue(source.contains("PREF_POSITION_FIELD_PURGE_PENDING"))
        assertTrue(initialize.contains("getBoolean(PREF_POSITION_FIELD_PURGE_PENDING, false)"))
        assertTrue(initialize.contains("positionFieldPurgeMarker().exists()"))
        assertTrue(initialize.contains("positionFieldStorageBlocked = purgePending"))
        assertTrue(initialize.contains("if (purgePending)"))
        assertTrue(initialize.contains("completePendingPositionFieldPurge("))
        assertTrue(purge.contains("putBoolean(PREF_POSITION_FIELD_PURGE_PENDING, true)"))
        assertTrue(purge.contains("persistPositionFieldPurgeMarker()"))
        assertTrue(purge.contains(".commit()"))
        assertTrue(
            purge.indexOf("putBoolean(PREF_POSITION_FIELD_PURGE_PENDING, true)") <
                purge.indexOf("completePendingPositionFieldPurge("),
        )
        assertTrue(complete.contains("val purged = positionFieldRecorder?.purge() == true"))
        assertTrue(complete.contains("putBoolean(PREF_POSITION_FIELD_PURGE_PENDING, false)"))
        assertTrue(complete.contains("positionFieldStorageBlocked = false"))
        assertTrue(complete.contains("val markerCleared = !purgeMarker.exists() || purgeMarker.delete()"))
        assertTrue(
            complete.indexOf(".commit()") < complete.indexOf("positionFieldStorageBlocked = false"),
        )
        assertTrue(list.contains("positionFieldRecorder?.list(binding)"))
        assertTrue(export.contains("positionFieldRecorder?.list(currentBinding)"))
        assertTrue(export.contains("positionFieldRecorder?.export(boundSessionId, currentBinding, output)"))
    }

    @Test
    fun stopFailureRetainsTheLeaseAndDeletionFailureBlocksStorage() {
        val stop = functionBlock("private fun stopActivePositionFieldSession(")
        val complete = functionBlock("private fun completePendingPositionFieldPurge(")
        val start = functionBlock("private fun startPositionFieldSession(")
        val export = functionBlock("private fun exportLastPositionFieldSession(")

        assertTrue(stop.contains("positionFieldRecorder?.stop(lease) != true"))
        assertTrue(stop.indexOf("return false") < stop.indexOf("positionFieldLease = null"))
        assertTrue(complete.contains("val purged = positionFieldRecorder?.purge() == true"))
        assertTrue(complete.indexOf("return false") < complete.indexOf("positionFieldLocalScopeId = replacement"))
        assertTrue(start.contains("if (positionFieldStorageBlocked)"))
        assertTrue(export.contains("if (positionFieldStorageBlocked)"))
    }

    @Test
    fun purgeFailureAbortsLogoutAndAccountDeletionTransitions() {
        val logout = functionBlock("private fun onAccountLogoutClicked(")
        val runtimeFence = functionBlock("private fun applyAccountDeletionRuntimeFence(")
        val acceptedDeletion = functionBlock("private fun applyAccountDeletionFenceAndPurgeLocal(")
        val purge = functionBlock("private fun purgeAndRotatePositionFieldScope(")
        val otp = functionBlock("private fun requestEmailAccountOtp(")
        val create = functionBlock("private fun createEmailAccount(")
        val login = functionBlock("private fun loginEmailAccount(")

        assertTrue(purge.contains("PositionFieldPurgeDurability("))
        assertTrue(purge.contains("if (!durability.mayAttemptPurge)"))
        assertTrue(logout.contains("if (!purgeAndRotatePositionFieldScope())"))
        assertTrue(logout.indexOf("return") < logout.indexOf("clearGatewaySession("))
        assertTrue(runtimeFence.contains("val positionFieldPurged = purgeAndRotatePositionFieldScope()"))
        assertTrue(runtimeFence.contains("return positionFieldPurged"))
        assertTrue(acceptedDeletion.contains("if (!applyAccountDeletionRuntimeFence())"))
        assertTrue(acceptedDeletion.indexOf("return") < acceptedDeletion.indexOf("resumeAccountDeletionFromMarker()"))
        assertTrue(source.contains("if (!applyAccountDeletionRuntimeFence())"))
        assertTrue(source.contains("accountDeletion=blocked:position_field_purge"))
        assertTrue(otp.contains("if (!positionFieldAccountTransitionAllowed()) return"))
        assertTrue(create.contains("if (!positionFieldAccountTransitionAllowed()) return"))
        assertTrue(login.contains("if (!positionFieldAccountTransitionAllowed()) return"))
    }

    @Test
    fun safCleanupChecksDeleteAndFallsBackToTruncation() {
        val cleanup = functionBlock("private fun deleteOrTruncatePositionFieldDestination(")
        assertTrue(cleanup.contains("contentResolver.delete(destination, null, null) > 0"))
        assertTrue(cleanup.contains("openOutputStream(destination, \"wt\")"))
        assertTrue(cleanup.contains("정확한 위치가 남아 있을 수 있습니다"))
    }

    @Test
    fun acceptedRouteMatchIsBoundToTimestampRevisionAndQuality() {
        val routeSource = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
        ).readText()
        assertTrue(routeSource.contains("fun currentAcceptedRouteMatchFor("))
        assertTrue(routeSource.contains("latestRouteMatchInputElapsedRealtimeMs != inputElapsedRealtimeMs"))
        assertTrue(routeSource.contains("latestRouteMatchRouteRevision != routeRevision"))
        assertTrue(routeSource.contains("!latestRouteMatchUsableForGuidance"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
