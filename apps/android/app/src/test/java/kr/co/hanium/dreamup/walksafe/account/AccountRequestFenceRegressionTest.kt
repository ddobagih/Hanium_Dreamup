package kr.co.hanium.dreamup.walksafe.account

import org.junit.Assert.*
import org.junit.Test

class AccountRequestFenceRegressionTest {
    @Test fun changedInputRejectsResultButReleasesCompletedRequest() {
        val fence = AccountRequestFence().apply { enteredForeground() }
        val old = requireNotNull(fence.begin(AccountRemoteAction.PASSWORD_LOGIN, "first-input"))
        assertFalse(fence.completeIfCurrent(old, "edited-input"))
        assertFalse(fence.isInFlight())
        assertNotNull(fence.begin(AccountRemoteAction.PASSWORD_LOGIN, "edited-input"))
    }

    @Test fun cancelledResponseCannotReleaseNewRequestWithTheSameBinding() {
        val fence = AccountRequestFence().apply { enteredForeground() }
        val old = requireNotNull(fence.begin(AccountRemoteAction.PASSWORD_LOGIN, "same-input"))
        fence.cancel(old)
        val current = requireNotNull(fence.begin(AccountRemoteAction.PASSWORD_LOGIN, "same-input"))
        assertFalse(fence.completeIfCurrent(old, "same-input"))
        assertTrue(fence.isInFlight())
        assertTrue(fence.completeIfCurrent(current, "same-input"))
        assertFalse(fence.completeIfCurrent(current, "same-input"))
    }

    @Test fun foregroundReturnDoesNotReviveTheOldRequest() {
        val fence = AccountRequestFence().apply { enteredForeground() }
        val old = requireNotNull(fence.begin(AccountRemoteAction.CREATE_ACCOUNT, "enrollment"))
        fence.enteredBackground()
        fence.enteredForeground()
        assertFalse(fence.completeIfCurrent(old, "enrollment"))
        assertNotNull(fence.begin(AccountRemoteAction.CREATE_ACCOUNT, "enrollment"))
    }
}
