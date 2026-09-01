package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityInitialPermissionStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun firstEntryRequestsEveryMissingRuntimePermissionInOneBatchOnlyOnce() {
        val resume = functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
        val request = functionBlock("private fun requestInitialAppEntryPermissionsIfNeeded()")
        val required = functionBlock("private fun requiredPostLoginDeviceCheckPermissions()")

        assertTrue(resume.contains("requestInitialAppEntryPermissionsIfNeeded()"))
        assertTrue(request.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED"))
        assertTrue(request.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED"))
        assertTrue(
            request.indexOf("permissionRequestLeases.values.any") <
                request.indexOf("PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED"),
        )
        assertTrue(request.contains("if (requestCode == null)"))
        assertTrue(request.contains(".remove(PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED)"))
        assertTrue(request.contains("requiredPostLoginDeviceCheckPermissions()"))
        assertTrue(request.contains("missing.toTypedArray()"))
        assertTrue(request.contains("PermissionRequestPurpose.INITIAL_APP_ENTRY"))
        assertTrue(required.contains("Manifest.permission.CAMERA"))
        assertTrue(required.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(required.contains("Manifest.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(required.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(required.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(required.contains("Manifest.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun initialDenialIsRememberedAndExplainsOnlyRelatedFeatureLimits() {
        val result = functionBlock("private fun handleInitialAppEntryPermissionResult()")
        val limitations = functionBlock("private fun showInitialAppPermissionLimitations(")
        val postLoginRequest = functionBlock("private fun requestedPostLoginDeviceCheckPermissions()")

        assertTrue(result.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED"))
        assertTrue(result.contains(".remove(PREF_INITIAL_APP_PERMISSION_REQUEST_STARTED)"))
        assertTrue(result.contains("showInitialAppPermissionLimitations"))
        assertTrue(limitations.contains("거부한 권한과 관련된 기능만 제한됩니다"))
        assertTrue(limitations.contains("나머지 기능은 계속 사용할 수 있습니다"))
        assertTrue(limitations.contains("permissionDenialSettingsButton.visibility = View.GONE"))
        assertFalse(limitations.contains("enterPermissionRecoveryBarrier("))
        assertTrue(postLoginRequest.contains("emptyList()"))
        assertTrue(postLoginRequest.contains("PREF_INITIAL_APP_PERMISSION_REQUEST_COMPLETED"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth += 1
                    opened = true
                }
                '}' -> {
                    depth -= 1
                    if (opened && depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }
}
