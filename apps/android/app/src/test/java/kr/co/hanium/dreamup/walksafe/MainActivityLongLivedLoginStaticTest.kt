package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLongLivedLoginStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()
    private val buildSource = File("build.gradle.kts").readText()
    private val permissionPolicySource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicy.kt",
    ).readText()
    private val readinessSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
    ).readText()
    private val coordinatorSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/network/" +
            "GatewaySessionProcessCoordinator.kt",
    ).readText()
    private val persistedLoginStateSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/network/" +
            "GatewayPersistedLoginState.kt",
    ).readText()

    @Test
    fun releaseForcesLongLivedLoginOffAndDebugRequiresOneExactOptInProperty() {
        val debugProperty = sourceSection(
            buildSource,
            "val debugLongLivedLoginEnabled =",
            "val validateWalkSafeSourceCommit",
        )
        assertTrue(
            debugProperty.contains(
                "providers.gradleProperty(\"walksafe.longLivedLoginEnabled\").orNull == \"true\"",
            ),
        )
        assertFalse(debugProperty.contains("environmentVariable("))
        assertFalse(debugProperty.contains("toBoolean"))
        assertFalse(debugProperty.contains("equals("))

        val debug = sourceSection(buildSource, "        debug {", "        release {")
        assertTrue(
            debug.contains(
                "buildConfigField(\"boolean\", \"WALKSAFE_LONG_LIVED_LOGIN_ENABLED\", " +
                    "\"\$debugLongLivedLoginEnabled\")",
            ),
        )

        val release = sourceSection(
            buildSource,
            "        release {",
            "            isMinifyEnabled",
        )
        assertTrue(
            release.contains(
                "buildConfigField(\"boolean\", \"WALKSAFE_LONG_LIVED_LOGIN_ENABLED\", \"false\")",
            ),
        )
        assertFalse(release.contains("debugLongLivedLoginEnabled"))
    }

    @Test
    fun startupFailsClosedAndRestoredLeaseNeverBecomesActiveBeforeDurableRenewal() {
        val restore = functionBlock("private fun restoreGatewaySessionFromPrefs()")

        assertInOrder(
            restore,
            "val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return",
            "permissionSessionPolicy.authenticationExpired()",
            "if (!BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED)",
        )
        assertTrue(restore.contains("gatewaySessionStore.purgeDisabled()"))
        assertTrue(restore.contains("stageRestoredGatewayRevocation(restored, operation)"))
        assertTrue(restore.contains("GatewaySessionProcessCoordinator.markStorageBlocked(operation)"))
        assertTrue(
            restore.contains(
                "GatewaySessionVerificationState.RESTORED_UNVERIFIED",
            ),
        )
        assertTrue(restore.contains("permissionSessionPolicy.rememberActor(actorId)"))
        assertFalse(restore.contains("permissionSessionPolicy.authenticated("))
        assertInOrder(
            restore,
            "GatewaySessionProcessCoordinator.publishRestoredUnverified(",
            "renewRestoredGatewaySession(restored, renewalOperation)",
        )

        val renewal = functionBlock("private fun renewRestoredGatewaySession(")
        assertInOrder(
            renewal,
            "gatewaySessionStore.reserveRenewal(",
            "gatewaySessionClient.renew(",
            "gatewaySessionStore.commitRenewal(",
            "GatewaySessionProcessCoordinator.publishVerified(",
        )
    }

    @Test
    fun loginPassesTheBuildOptInAndDelegatesOneAtomicCommit() {
        val login = functionBlock("private fun onGatewaySessionButtonClicked()")

        assertInOrder(
            login,
            "enableLongLivedSession =",
            "BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED",
        )
        assertTrue(
            login.contains(
                "val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return",
            ),
        )
        assertTrue(login.contains("commitLoggedInGatewaySession("))
        assertFalse(login.contains("gatewaySessionStore.saveInitialIfAbsent("))
        assertFalse(login.contains("GatewaySessionProcessCoordinator.publishVerified("))
    }

    @Test
    fun gatewayReadyMeansVerifiedExactActorActivePermissionAndHealthyStorage() {
        val ready = functionBlock("private fun isGatewaySessionReadyForCurrentActor(")
        assertInOrder(
            ready,
            "val processSnapshot = GatewaySessionProcessCoordinator.snapshot()",
            "!processSnapshot.storageBlocked",
            "processSnapshot.session === session",
        )
        assertTrue(ready.contains("currentReporterUserId()"))
        assertTrue(
            ready.contains(
                "GatewaySessionVerificationState.VERIFIED",
            ),
        )
        assertTrue(ready.contains("session.isUsableFor(actorId)"))
        assertTrue(ready.contains("permissionSessionPolicy.isAuthenticatedFor(actorId)"))

        val permissionCheck = functionBlock(
            permissionPolicySource,
            "fun isAuthenticatedFor(actorId: String?)",
        )
        assertTrue(permissionCheck.contains("AuthenticationState.ACTIVE"))
        assertTrue(permissionCheck.contains("current.actorId =="))

        val current = functionBlock("private fun isCurrentGatewaySession(")
        assertTrue(current.contains("isGatewaySessionReadyForCurrentActor("))
        val lookup = functionBlock("private fun gatewaySessionOrNull(")
        assertTrue(lookup.contains("isGatewaySessionReadyForCurrentActor("))
        val gatewayReadiness = sourceSection(
            source,
            "WalkSessionReadinessRequirement.GATEWAY ->",
            "WalkSessionReadinessRequirement.DEVICE_RESOURCES ->",
        )
        assertTrue(gatewayReadiness.contains("isGatewaySessionReadyForCurrentActor("))
        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")
        assertTrue(blockReason.contains("isGatewaySessionReadyForCurrentActor("))
    }

    @Test
    fun verifiedSessionDurabilityPrecedesProcessPublishAndBoundAuthentication() {
        val commit = functionBlock("private fun commitLoggedInGatewaySession(")
        assertInOrder(
            commit,
            "GatewaySessionProcessCoordinator.snapshot().inFlightOperationId",
            "operation.operationId",
            "session.verificationState != GatewaySessionVerificationState.VERIFIED",
            "!session.isUsableFor(actorId)",
            "actorId != session.actorId",
            "priorityUserOnboardingActorId != session.actorId",
        )
        assertInOrder(
            commit,
            "gatewaySessionStore.saveInitialIfAbsent(",
            "GatewaySessionProcessCoordinator.publishVerified(",
        )
        assertTrue(commit.contains("stored != GatewaySessionStoreResult.COMMITTED"))
        assertTrue(commit.contains("stored != GatewaySessionStoreResult.ALREADY_COMMITTED"))
        assertFalse(commit.contains("permissionSessionPolicy.authenticated("))

        val publish = functionBlock(coordinatorSource, "fun publishVerified(")
        val atomic = nestedBlock(publish, "synchronized(lock)")
        assertInOrder(
            atomic,
            "if (inFlightOperation != operation) return false",
            "replaceSessionLocked(session)",
            "storageBlocked = false",
            "advanceGenerationLocked()",
            "inFlightOperation = null",
            "notification = notificationLocked()",
        )
        assertInOrder(
            publish,
            "synchronized(lock)",
            "notification.deliver()",
            "return true",
        )

        val applySnapshot = functionBlock(
            "private fun onGatewayProcessSessionChanged(",
        )
        assertInOrder(
            applySnapshot,
            "!snapshot.storageBlocked",
            "session.verificationState == GatewaySessionVerificationState.VERIFIED",
            "session.isUsableFor(actorId)",
            "priorityUserOnboardingActorId == actorId",
            "permissionSessionPolicy.authenticated(actorId)",
        )
    }

    @Test
    fun clearDurablyStagesRevocationBeforeAtomicProcessCredentialRemoval() {
        val clear = functionBlock("private fun clearGatewaySession(")
        assertInOrder(
            clear,
            "val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return null",
            "gatewaySessionStore.moveActiveToPendingRevocation(",
            "GatewaySessionProcessCoordinator.publishPendingRevocation(operation)",
            "permissionSessionPolicy.authenticationExpired()",
        )
        assertTrue(clear.contains("GatewaySessionProcessCoordinator.markStorageBlocked(operation)"))
        assertTrue(clear.contains("handleGatewaySessionDowngrade("))
        assertFalse(clear.contains("gatewaySessionStore.clear()"))

        val persistedTransition = functionBlock(
            persistedLoginStateSource,
            "fun moveActiveToPendingRevocation(",
        )
        assertInOrder(
            persistedTransition,
            "current.bundle.session.version() == expectedVersion",
            "GatewayPersistedLoginState.PendingRevocation(",
            "current.bundle.pendingRevocation(operationId)",
        )

        val pendingPublish = functionBlock(
            coordinatorSource,
            "fun publishPendingRevocation(",
        )
        val pendingAtomic = nestedBlock(pendingPublish, "synchronized(lock)")
        assertInOrder(
            pendingAtomic,
            "if (inFlightOperation != operation) return null",
            "currentSession?.invalidate()",
            "currentSession = null",
            "restoredFirstRunSnapshot = null",
            "generation = advanceGenerationLocked()",
            "operationId = operation.operationId",
            "inFlightOperation = continuedOperation",
        )
    }

    @Test
    fun gatewayDowngradeRechecksPreparedWalkAndSafetyStopsAnActiveWalk() {
        val downgrade = functionBlock("private fun handleGatewaySessionDowngrade(")

        assertTrue(downgrade.contains("WalkSessionState.ACTIVE ->"))
        assertTrue(
            downgrade.contains("enterWalkSessionSafetyStopAndCancelOutputs(reason)"),
        )
        assertTrue(downgrade.contains("WalkSessionState.READY,"))
        assertTrue(downgrade.contains("WalkSessionState.PAUSED ->"))
        assertTrue(
            downgrade.contains(
                "enterWalkSessionForegroundRecheckAndCancelOutputs(reason)",
            ),
        )
        assertTrue(downgrade.contains("walkSessionResumeConfirmationToken = null"))

        val recheck = functionBlock(
            "private fun enterWalkSessionForegroundRecheckAndCancelOutputs(",
        )
        assertTrue(
            recheck.contains(
                "transitionWalkSession(WalkSessionEvent.RecheckRequested)",
            ),
        )
        assertTrue(recheck.contains("cancelWalkSessionOutputs(reason)"))
    }

    @Test
    fun remoteLogoutFailureIsNeverReportedAsConfirmedSuccess() {
        val button = functionBlock("private fun onGatewaySessionButtonClicked()")
        val clear = functionBlock("private fun clearGatewaySession(")

        assertFalse(button.contains("현장 게이트웨이에서 로그아웃했습니다."))
        assertTrue(button.contains("remote_revoke_pending"))
        assertTrue(button.contains("확인 중"))
        assertFalse(
            clear.contains(
                "runCatching { gatewaySessionClient.logout(previous) }",
            ),
        )
        assertInOrder(
            clear,
            "val confirmed = runCatching",
            "gatewaySessionClient.logout(previous)",
            "remoteLogoutResult(confirmed)",
        )
        assertTrue(source.contains("remote_revoke_confirmed"))
        assertTrue(source.contains("remote_revoke_unconfirmed"))
        assertTrue(source.contains("확인하지 못"))
    }

    @Test
    fun startingAndResumingWalkBothRequireTheGateway() {
        val start = sourceSection(
            readinessSource,
            "WalkSessionAction.START_WALK ->",
            "WalkSessionAction.RESUME_WALK ->",
        )
        val resume = sourceSection(
            readinessSource,
            "WalkSessionAction.RESUME_WALK ->",
            "WalkSessionAction.DESTINATION_SEARCH ->",
        )
        val runtimeRequirements = functionBlock(
            readinessSource,
            "private fun MutableSet<WalkSessionReadinessRequirement>.addWalkRuntimeRequirements()",
        )

        assertTrue(start.contains("addWalkRuntimeRequirements()"))
        assertTrue(resume.contains("addWalkRuntimeRequirements()"))
        assertTrue(
            runtimeRequirements.contains(
                "add(WalkSessionReadinessRequirement.GATEWAY)",
            ),
        )
    }

    private fun sourceSection(
        text: String,
        startMarker: String,
        endMarker: String,
    ): String {
        val start = text.indexOf(startMarker)
        assertTrue("missing source marker: $startMarker", start >= 0)
        val end = text.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing source marker: $endMarker", end > start)
        return text.substring(start, end)
    }

    private fun functionBlock(marker: String): String = functionBlock(source, marker)

    private fun functionBlock(text: String, marker: String): String =
        nestedBlock(text, marker, includeMarker = true)

    private fun nestedBlock(
        text: String,
        marker: String,
        includeMarker: Boolean = false,
    ): String {
        val markerStart = text.indexOf(marker)
        assertTrue("missing block marker: $marker", markerStart >= 0)
        val bodyStart = text.indexOf('{', markerStart + marker.length)
        assertTrue("missing block body: $marker", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(
                            if (includeMarker) markerStart else bodyStart,
                            index + 1,
                        )
                    }
                }
            }
        }
        throw AssertionError("unterminated block: $marker")
    }

    private fun assertInOrder(section: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = section.indexOf(token, previous + 1)
            assertTrue("missing or out-of-order token: $token", current > previous)
            previous = current
        }
    }
}
