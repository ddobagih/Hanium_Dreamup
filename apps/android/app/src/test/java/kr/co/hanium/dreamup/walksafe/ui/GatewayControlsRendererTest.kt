package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.*
import org.junit.Test

class GatewayControlsRendererTest {
    @Test fun hiddenControlClearsItsTokenBeforeTheConditionalLiveSessionLookup() {
        val f = Target()
        GatewayControlsRenderer.render(f, false, false, false, false, false) {
            f.trace += "sessionLookup"
            assertTrue(f.cleared)
            false
        }
        assertEquals(listOf("token:false", "action:false", "clear", "sessionLookup", "caption:Gateway 로그인",
            "enabled:false", "readEnabled", "tokenEnabled:false"), f.trace)
    }

    @Test fun pendingAndRecoveryCaptionsDoNotReadTheLiveSessionSupplier() {
        val cases = listOf(
            listOf(true, false, false, false) to "Gateway 처리 중",
            listOf(false, true, false, false) to "삭제 복구 로그인 완료",
            listOf(false, false, true, false) to "계정 삭제 처리 로그인 유지",
            listOf(false, false, false, true) to "삭제 복구 다시 로그인",
        )
        for ((flags, expected) in cases) {
            val f = Target()
            GatewayControlsRenderer.render(f, true, flags[0], flags[1], flags[2], flags[3]) {
                error("Session must remain unread for $expected")
            }
            assertEquals(expected, f.captionValue)
            assertFalse(f.cleared)
        }
    }

    @Test fun actionEnabledIsReadAfterTheActualSetterBeforeUpdatingTokenEnabled() {
        val f = Target()
        f.onEnabled = { f.enabled = false }
        GatewayControlsRenderer.render(f, true, false, false, false, false) { true }
        assertEquals("Gateway 로그아웃", f.captionValue)
        assertFalse(f.tokenEnabledValue)
        assertTrue(f.trace.indexOf("enabled:true") < f.trace.indexOf("readEnabled"))
        assertTrue(f.trace.indexOf("readEnabled") < f.trace.indexOf("tokenEnabled:false"))
    }

    @Test fun recoveryOnlyStillAllowsTheControlWhenNoRequestOrReservedSessionBlocksIt() {
        val f = Target()
        GatewayControlsRenderer.render(f, true, false, false, false, true) { error("unneeded") }
        assertEquals("삭제 복구 다시 로그인", f.captionValue)
        assertTrue(f.tokenEnabledValue)
    }

    private class Target : GatewayControlsRenderer.Target {
        var captionValue = ""
        var enabled = false
        var tokenEnabledValue = false
        var cleared = false
        var onEnabled: (() -> Unit)? = null
        val trace = mutableListOf<String>()
        override fun showToken(visible: Boolean) { trace += "token:$visible" }
        override fun showAction(visible: Boolean) { trace += "action:$visible" }
        override fun clearToken() { cleared = true; trace += "clear" }
        override fun setCaption(message: String) { captionValue = message; trace += "caption:$message" }
        override fun setActionEnabled(enabled: Boolean) { this.enabled = enabled; trace += "enabled:$enabled"; onEnabled?.invoke() }
        override fun actionEnabled(): Boolean { trace += "readEnabled"; return enabled }
        override fun setTokenEnabled(enabled: Boolean) { tokenEnabledValue = enabled; trace += "tokenEnabled:$enabled" }
    }
}
