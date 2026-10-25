package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.*
import org.junit.Test

class NativeUiPublicationTest {
    @Test fun partialAccountBindingDoesNotPublishTheLoginActionOrRootBeforeItsOriginalPoint() {
        val p = NativeUiPublication()
        p.publish(NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT)
        assertTrue(p.isPublished(NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT))
        assertFalse(p.isPublished(NativeUiPublication.Target.ACCOUNT_LOGIN_BUTTON))
        assertFalse(p.isPublished(NativeUiPublication.Target.ACCOUNT_ACCESS_CONTROLS))
        p.publish(NativeUiPublication.Target.ACCOUNT_LOGIN_BUTTON)
        assertTrue(p.isPublished(NativeUiPublication.Target.ACCOUNT_LOGIN_BUTTON))
        assertFalse(p.isPublished(NativeUiPublication.Target.ACCOUNT_ACCESS_CONTROLS))
    }

    @Test fun educationAndHomeTargetsKeepTheirDistinctBindingMilestones() {
        val p = NativeUiPublication()
        p.publish(NativeUiPublication.Target.FIRST_RUN_PHONE_POSTURE_CONTROLS)
        assertFalse(p.isPublished(NativeUiPublication.Target.PRIORITY_USER_EDUCATION_AGREE_BUTTON))
        assertFalse(p.isPublished(NativeUiPublication.Target.NATIVE_HOME_RESTRICTION_TEXT))
        p.publish(NativeUiPublication.Target.PRIORITY_USER_EDUCATION_AGREE_BUTTON)
        assertTrue(p.isPublished(NativeUiPublication.Target.PRIORITY_USER_EDUCATION_AGREE_BUTTON))
        assertFalse(p.isPublished(NativeUiPublication.Target.NATIVE_HOME_RESTRICTION_TEXT))
    }

    @Test fun rebindingNeverResetsAnAlreadyInitializedTarget() {
        val p = NativeUiPublication()
        p.publish(NativeUiPublication.Target.PRIVACY_CONTROLS)
        p.publish(NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT)
        p.publish(NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT)
        assertTrue(p.isPublished(NativeUiPublication.Target.PRIVACY_CONTROLS))
        assertTrue(p.isPublished(NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT))
        assertFalse(p.isPublished(NativeUiPublication.Target.NATIVE_VOICE_CONTROLS))
    }
}
