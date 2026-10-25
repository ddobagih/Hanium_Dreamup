package kr.co.hanium.dreamup.walksafe.fieldlog

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPositionFieldSessionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val controller = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/PositionFieldRecorderController.kt",
    ).readText()
    private val platform = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/AndroidPositionFieldRecorderStorage.kt",
    ).readText()

    @Test
    fun recorderIsDebugOnlyPrivateAndPurposeSeparated() {
        assertTrue(source.contains("if (BuildConfig.DEBUG) initializePositionFieldRecorder()"))
        assertTrue(source.contains("enabled = BuildConfig.DEBUG"))
        val initialize = functionSection("private fun initializePositionFieldRecorder(", source)
        assertTrue(initialize.contains("if (!BuildConfig.DEBUG"))
        assertTrue(initialize.contains("positionFieldController.initialize()"))
        val ownerInitialize = functionSection("fun initialize(", controller)
        assertTrue(ownerInitialize.contains("if (!enabled"))
        assertTrue(platform.contains("noBackupRootDirectory = noBackupFilesDir"))
        assertTrue(platform.contains("walksafe.position-field-session.aead.v"))
        assertTrue(controller.contains("UUID.randomUUID().toString()"))
        assertTrue(platform.contains("PREF_POSITION_FIELD_LOCAL_SCOPE_ID"))
        assertFalse(ownerInitialize.contains("reporterUserId"))
        assertFalse(ownerInitialize.contains("actorId"))
        assertFalse(source.contains("private var pendingPositionFieldExport"))
        assertFalse(source.contains("private var positionFieldLease"))
    }

    @Test
    fun explicitExactCoordinateAndChestCalibrationConsentGateStart() {
        val start = functionSection("private fun startPositionFieldSession(", source)
        val ownerStart = functionSection("fun start(request:", controller)
        val composer = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
        assertTrue(source.contains("NativeRuntimeViewComposer(this, viewStyle)"))
        assertTrue(source.contains("positionFieldExactExportCheck = runtimeComposer.createPositionExportConfirmation()"))
        assertTrue(source.contains("positionFieldChestCalibrationCheck = runtimeComposer.createPositionCalibrationConfirmation()"))
        assertTrue(MainActivityRefactorSource.member(composer, "createPositionExportConfirmation").contains("정확한 좌표가 평문 JSONL로 내보내지는 것을 확인했습니다"))
        assertTrue(MainActivityRefactorSource.member(composer, "createPositionCalibrationConfirmation").contains("figure-8 자력계 보정을 완료했습니다"))
        assertTrue(start.contains("positionFieldExactExportCheck.isChecked"))
        assertTrue(start.contains("positionFieldChestCalibrationCheck.isChecked"))
        assertTrue(ownerStart.contains("!request.exactCoordinateExportConfirmed"))
        assertTrue(ownerStart.contains("!request.chestCalibrationConfirmed"))
        assertTrue(ownerStart.contains("scenario = \"FIELD_SURVEY\""))
        assertTrue(ownerStart.contains("environment = \"MIXED\""))
        assertTrue(ownerStart.contains("direction = \"FORWARD\""))
        assertTrue(ownerStart.contains("RecorderBinding(scope, walkEpoch.recoveryGeneration)"))
        val authority = functionSection("private fun currentPositionFieldAuthority(", source)
        assertTrue(authority.contains("GatewaySessionProcessCoordinator.snapshot().generation"))
        assertFalse(ownerStart.contains("GatewayCapacityProcessState"))
        assertFalse(ownerStart.contains("RawCollection"))
    }

    @Test
    fun safExportIsBoundAndDeletesFailedOrStaleDestination() {
        val launch = functionSection("private fun exportLastPositionFieldSession(", source)
        val callback = functionSection("private fun handlePositionFieldExportResult(", source)
        val begin = functionSection("fun beginExport(", controller)
        val result = functionSection("fun handleExportResult(", controller)
        assertTrue(launch.contains("Intent.ACTION_CREATE_DOCUMENT"))
        assertTrue(launch.contains("positionFieldController.beginExport("))
        assertTrue(begin.contains("++exportGeneration"))
        assertTrue(begin.contains("PendingExport(summary.sessionId, generation, binding, exportAuthority)"))
        assertTrue(callback.contains("AndroidPositionFieldExportDestination(contentResolver, it)"))
        assertTrue(callback.contains("positionFieldController.handleExportResult("))
        assertTrue(result.contains("pending.generation != exportGeneration"))
        assertTrue(result.contains("pending.sessionId != latestCompletedSessionId"))
        assertTrue(result.contains("currentAuthority != pending.authority"))
        assertTrue(result.contains("currentBinding.localAccountScopeId != currentAuthority.localScopeId"))
        assertTrue(result.contains("partialDestinationMustBeDeleted"))
        assertTrue(result.contains("cleanDestination(destination)"))
        assertFalse(launch.contains("ACTION_SEND"))
        assertFalse(launch.contains("FileProvider"))
    }

    @Test
    fun recorderHooksContainOnlyPositioningAndMotionMetadata() {
        fun functionSection(signature: String, owner: String): String =
            if (owner === source) MainActivityRefactorSource.function(source, signature)
            else MainActivityRefactorSource.member(owner, Regex("fun\\s+(\\w+)").find(signature)!!.groupValues[1])

        val gnss = functionSection("private fun appendPositionFieldGnssTrace(", source)
        val motion = functionSection("private fun appendPositionFieldMotionTrace(", source)
        assertTrue(gnss.contains("rawPosition = PositionTraceCoordinate("))
        assertTrue(gnss.contains("filteredPosition = PositionTraceCoordinate("))
        assertTrue(gnss.contains("currentAcceptedRouteMatchFor(filtered.elapsedRealtimeMs)"))
        assertTrue(gnss.contains("matchedPosition = routeMatch?.matchedPoint"))
        assertTrue(gnss.contains("PositionGnssTrace("))
        assertTrue(gnss.contains("PositionStepProfileTrace("))
        assertTrue(gnss.contains("measurementElapsedRealtimeNs = measurementElapsedRealtimeNs"))
        assertTrue(gnss.contains("measurementUtcEpochMs = measurementUtcEpochMs"))
        val gnssOwner = functionSection("fun appendGnss(", controller)
        assertTrue(gnssOwner.contains("source = PositionTraceSource.GNSS"))
        assertTrue(motion.contains("stepDetected = stepDetected"))
        assertTrue(motion.contains("currentPositionStationaryTrace(zuptApplied)"))
        assertTrue(motion.contains("measurementElapsedRealtimeMs: Long"))
        val motionOwner = functionSection("fun appendMotion(", controller)
        assertTrue(motionOwner.contains("PositionTraceSource.SENSOR_MONOTONIC_ONLY"))
        assertTrue(motionOwner.contains("PositionTraceSource.SENSOR_GNSS_ANCHORED"))
        for (hook in listOf(gnss, motion)) {
            for (excluded in listOf("accelerometer", "gyroscope", "audio", "image", "destination", "query")) {
                assertFalse("position-only hook must omit $excluded", hook.contains(excluded))
            }
        }
    }

    @Test
    fun lifecycleCheckpointGeomagneticAndChestGatesAreExplicit() {
        fun navigationInput(startMarker: String, endMarker: String): String {
            val factory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
            assertTrue(Regex(Regex.escape(startMarker)).findAll(factory).count() == 1)
            assertTrue(Regex(Regex.escape(endMarker)).findAll(factory).count() == 1)
            val start = factory.indexOf(startMarker)
            val end = factory.indexOf(endMarker, start + startMarker.length)
            assertTrue(start >= 0 && end > start)
            return factory.substring(start, end)
        }

        fun functionSection(signature: String, owner: String): String =
            if (owner === source) MainActivityRefactorSource.function(source, signature)
            else this@MainActivityPositionFieldSessionStaticTest.functionSection(signature, owner)

        val authority = functionSection("private fun currentPositionFieldAuthority(", source)
        val lease = functionSection("fun collectionBindingOrNull(", controller)
        val checkpoint = functionSection("fun markCheckpoint(\n        authority:", controller)
        val checkpointCallback = functionSection("private fun markPositionFieldCheckpoint(", source)
        val start = functionSection("private fun startPositionFieldSession(", source)
        val clearedCallback = MainActivityRefactorSource.property(source, "positionFieldController")
        val location = functionSection("private fun handleLocationUpdate(", source)
        val orientationReference = functionSection("private fun updateRouteOrientationReference(", source)
        val chest = functionSection("private fun chestMountedHeadingForStep(", source)
        val chestPolicy = functionSection("internal fun chestMountedHeadingForStep(", source)
        assertTrue(authority.contains("isActivityForeground"))
        assertTrue(authority.contains("isWalkSessionRuntimeActive()"))
        assertTrue(authority.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(lease.contains("localScopeId != active.localScopeId"))
        assertTrue(lease.contains("authority.walkEpoch != active.walkEpoch"))
        assertTrue(lease.contains("authority.gatewayGeneration != active.gatewayGeneration"))
        assertTrue(checkpoint.contains("CHECKPOINT_STATIONARY_MS"))
        assertTrue(checkpoint.contains("PositionStationaryState.STATIONARY"))
        assertTrue(checkpoint.contains("stationarySinceMs < sessionStartedAtMs"))
        assertTrue(checkpoint.contains("\"CP%03d\""))
        assertTrue(checkpoint.contains("ordinal = ordinal + 1"))
        assertTrue(checkpoint.contains("utcEpochMsFor(measurementElapsedRealtimeNs)"))
        assertTrue(checkpointCallback.contains("applyUserMotionState(positioningCoordinator.currentUserMotion(nowMs))"))
        assertTrue(start.contains("positioningSessionController.resetStationaryState()"))
        val positioning = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        val reset = MainActivityRefactorSource.member(positioning, "resetStationaryState")
        assertTrue(reset.contains("latestPositionStationaryState = PositionStationaryState.UNKNOWN"))
        assertTrue(reset.contains("latestPositionStationarySinceMs = null"))
        assertTrue(clearedCallback.contains("onSessionCleared ="))
        assertTrue(clearedCallback.contains("positioningSessionController.resetStationaryState()"))
        assertTrue(reset.contains("latestPositionStationarySinceMs = null"))
        assertTrue(location.contains("updateRouteOrientationReference(location)"))
        assertTrue(orientationReference.contains("ensureEarthOrientationForLocation()"))
        assertTrue(orientationReference.contains("sources.geomagneticReference(location)"))
        val positioningBinding = navigationInput("positions = PositioningAssemblyBindings(", "gpsBindings = GpsAssemblyBindings(")
        val positioningAssembly = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(positioningAssembly.contains("val sources = positions.sources()"))
        assertTrue(positioningAssembly.contains("PositioningSessionController(run, scope, sources"))
        assertTrue(positioningBinding.contains("sources = { createNavigationAndroidSources() }"))
        val androidSourcesBinding = MainActivityRefactorSource.factory(source, "createNavigationAndroidSources")
        assertTrue(androidSourcesBinding.contains("AndroidNavigationSources("))
        val androidSources = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        assertTrue(androidSourcesBinding.contains("orientationAvailableNow = { ::earthOrientationTracker.isInitialized }, orientation = { earthOrientationTracker }"))
        assertTrue(MainActivityRefactorSource.member(androidSources, "geomagneticReference").contains("orientation().updateGeomagneticReference("))
        assertTrue(chestPolicy.contains("PhoneMountingMethod.CHEST_FORWARD"))
        assertTrue(chest.contains("phoneMountingOutputsAllowed"))
        assertTrue(chest.contains("lookupHeading = sources::chestHeading"))
        assertTrue(MainActivityRefactorSource.member(androidSources, "chestHeading").contains("orientation().chestMountedHeadingAt(timestampMs, maximumAgeMs)"))
        assertTrue(chestPolicy.contains("return lookupHeading(timestampMs, maximumAgeMs)"))
    }

    @Test
    fun logoutAndPrivacyDeletionPurgeAndRotateTheLocalScope() {
        fun functionSection(signature: String, owner: String): String =
            if (owner === source) MainActivityRefactorSource.function(source, signature)
            else MainActivityRefactorSource.member(owner, Regex("fun\\s+(\\w+)").find(signature)!!.groupValues[1])

        val logout = functionSection("private fun onAccountLogoutClicked(", source)
        val deletion = functionSection("private fun applyAccountDeletionRuntimeFence(", source)
        val purgeDelegate = functionSection("private fun purgeAndRotatePositionFieldScope(", source)
        val purge = functionSection("fun purgeAndRotate(", controller)
        val complete = functionSection("private fun completePendingPurge(", controller)
        assertTrue(logout.contains("admission.purgeAndRotatePositionScope()"))
        val logoutBinding = MainActivityRefactorSource.factory(source, "createAccountAccessInteractionOwner")
        assertTrue(logoutBinding.contains("override fun purgeAndRotatePositionScope() = purgeAndRotatePositionFieldScope()"))
        assertTrue(deletion.contains("purgeAndRotatePosition()"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyRuntimeFence")
            .contains("purgeAndRotatePosition = ::purgeAndRotatePositionFieldScope"))
        assertTrue(purgeDelegate.contains("positionFieldController.purgeAndRotate()"))
        assertTrue(purge.contains("scopeStorage.storePurgePending(true)"))
        val fenceAssembly = MainActivityRefactorSource.composition(source, "accountDeletionRuntimeFenceController")
        assertTrue(fenceAssembly.contains("val ports = portsFactory()"))
        assertTrue(fenceAssembly.contains("purgeAndRotatePosition = ports.purgeAndRotatePosition"))
        assertTrue(complete.contains("recorder?.purge()"))
        assertTrue(complete.contains("storageBlocked = true"))
        assertTrue(complete.contains("scopeIdFactory()"))
    }

    @Test
    fun persistedPurgeFenceRecoversBeforeRecorderAccessAfterRestart() {
        val initialize = functionSection("fun initialize(", controller)
        val purge = functionSection("fun purgeAndRotate(", controller)
        val complete = functionSection("private fun completePendingPurge(", controller)
        val list = functionSection("fun beginExport(", controller)
        val export = functionSection("fun handleExportResult(", controller)
        assertTrue(platform.contains("getBoolean(PREF_POSITION_FIELD_PURGE_PENDING, false)"))
        assertTrue(initialize.contains("scopeStorage.purgePending() || scopeStorage.purgeMarkerExists()"))
        assertTrue(initialize.contains("storageBlocked = purgePending"))
        assertTrue(initialize.contains("if (purgePending)"))
        assertTrue(initialize.contains("completePendingPurge("))
        assertTrue(purge.contains("scopeStorage.storePurgePending(true)"))
        assertTrue(purge.contains("scopeStorage.persistPurgeMarker()"))
        assertTrue(platform.contains(".commit()"))
        assertTrue(purge.indexOf("scopeStorage.persistPurgeMarker()") < purge.indexOf("completePendingPurge("))
        assertTrue(purge.indexOf("scopeStorage.storePurgePending(true)") < purge.indexOf("completePendingPurge("))
        assertTrue(complete.contains("val purged = recorder?.purge() == true"))
        assertTrue(complete.contains("scopeStorage.storeScope(replacement, clearPurgePending = true)"))
        assertTrue(platform.contains("editor.putBoolean(PREF_POSITION_FIELD_PURGE_PENDING, false)"))
        assertTrue(complete.contains("storageBlocked = false"))
        assertTrue(platform.contains("!purgeMarker.exists() || purgeMarker.delete()"))
        assertTrue(complete.indexOf("scopeStorage.storeScope(") < complete.indexOf("storageBlocked = false"))
        assertTrue(list.contains("recorder?.list(binding)"))
        assertTrue(export.contains("recorder?.list(currentBinding)"))
        assertTrue(export.contains("recorder?.export(pending.sessionId, currentBinding, output)"))
    }

    @Test
    fun stopFailureRetainsTheLeaseAndDeletionFailureBlocksStorage() {
        val stop = functionSection("fun stop(): Boolean {", controller)
        val complete = functionSection("private fun completePendingPurge(", controller)
        val start = functionSection("fun start(request:", controller)
        val export = functionSection("fun beginExport(", controller)
        assertTrue(stop.contains("recorder?.stop(active.lease) != true"))
        assertTrue(stop.indexOf("return false") < stop.indexOf("clearRecording()"))
        assertTrue(complete.contains("val purged = recorder?.purge() == true"))
        assertTrue(complete.indexOf("return false") < complete.indexOf("localScopeId = replacement"))
        assertTrue(start.contains("if (storageBlocked)"))
        assertTrue(export.contains("if (storageBlocked)"))
    }

    @Test
    fun purgeFailureAbortsLogoutAndAccountDeletionTransitions() {
        fun functionSection(signature: String, owner: String): String =
            if (owner === source) MainActivityRefactorSource.function(source, signature)
            else MainActivityRefactorSource.member(owner, Regex("fun\\s+(\\w+)").find(signature)!!.groupValues[1])

        val logout = functionSection("private fun onAccountLogoutClicked(", source)
        val runtimeFence = functionSection("private fun applyAccountDeletionRuntimeFence(", source)
        val acceptedDeletion = functionSection("private fun applyAccountDeletionFenceAndPurgeLocal(", source)
        val purge = functionSection("fun purgeAndRotate(", controller)
        assertTrue(MainActivityRefactorSource.member(source, "createEmailAccountController").contains("transitionAllowed = ::positionFieldAccountTransitionAllowed"))
        val otp = MainActivityRefactorSource.function(source, "private fun requestEmailAccountOtp(")
        val create = MainActivityRefactorSource.function(source, "private fun createEmailAccount(")
        val login = MainActivityRefactorSource.function(source, "private fun loginEmailAccount(")
        assertTrue(purge.contains("PositionFieldPurgeDurability("))
        assertTrue(purge.contains("if (!durability.mayAttemptPurge)"))
        assertTrue(logout.contains("if (!admission.purgeAndRotatePositionScope())"))
        assertTrue(MainActivityRefactorSource.factory(source, "createAccountAccessInteractionOwner")
            .contains("override fun purgeAndRotatePositionScope() = purgeAndRotatePositionFieldScope()"))
        assertTrue(logout.indexOf("return") < logout.indexOf("gateway.clear("))
        assertTrue(runtimeFence.contains("val positionFieldPurged = purgeAndRotatePosition()"))
        assertTrue(runtimeFence.contains("return positionFieldPurged"))
        val fenceAssembly = MainActivityRefactorSource.composition(source, "accountDeletionRuntimeFenceController")
        assertTrue(fenceAssembly.contains("val ports = portsFactory()"))
        assertTrue(fenceAssembly.contains("purgeAndRotatePosition = ports.purgeAndRotatePosition"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyRuntimeFence")
            .contains("purgeAndRotatePosition = ::purgeAndRotatePositionFieldScope"))
        assertTrue(acceptedDeletion.contains("if (!effects.applyRuntimeFence())"))
        assertTrue(MainActivityRefactorSource.composition(source, "accountDeletionProgressController")
            .contains("override fun applyRuntimeFence() = accountDeletionRuntimeFenceController.value.apply()"))
        assertTrue(acceptedDeletion.indexOf("return") < acceptedDeletion.indexOf("resumeFromMarker()"))
        assertTrue(acceptedDeletion.contains("if (!effects.applyRuntimeFence())"))
        assertTrue(acceptedDeletion.contains("accountDeletion=blocked:position_field_purge"))
        assertTrue(otp.contains("if (!readers.transitionAllowed()) return"))
        assertTrue(create.contains("if (!readers.transitionAllowed()) return"))
        assertTrue(login.contains("if (!readers.transitionAllowed()) return"))
    }

    @Test
    fun safCleanupChecksDeleteAndFallsBackToTruncation() {
        val cleanup = functionSection("override fun deleteOrTruncate(", platform)
        assertTrue(cleanup.contains("contentResolver.delete(destination, null, null) > 0"))
        assertTrue(cleanup.contains("openOutputStream(destination, \"wt\")"))
        val checkedCleanup = functionSection("private fun cleanDestination(", controller)
        assertTrue(checkedCleanup.contains("!destination.deleteOrTruncate()"))
        assertTrue(checkedCleanup.contains("정확한 위치가 남아 있을 수 있습니다"))
    }

    @Test
    fun acceptedRouteMatchIsBoundToTimestampRevisionAndQuality() {
        val routeSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt").readText()
        assertTrue(routeSource.contains("fun currentAcceptedRouteMatchFor("))
        assertTrue(routeSource.contains("latestRouteMatchInputElapsedRealtimeMs != inputElapsedRealtimeMs"))
        assertTrue(routeSource.contains("latestRouteMatchRouteRevision != routeRevision"))
        assertTrue(routeSource.contains("!latestRouteMatchUsableForGuidance"))
    }

    private fun functionSection(signature: String, owner: String): String {
        val start = owner.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val nextFunction = Regex("(?m)^    (?:private |internal |override )?fun ").find(owner, start + signature.length)
        return owner.substring(start, nextFunction?.range?.first ?: owner.length)
    }
}
