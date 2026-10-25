package kr.co.hanium.dreamup.walksafe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import kr.co.hanium.dreamup.walksafe.ui.AccountAccessPanel
import kr.co.hanium.dreamup.walksafe.ui.AccountAccessPresenter
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.testing.syntheticBoundaryContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountEmailDeviceContractTest {
    /** Actual Android Views/presenter only; Main enrollment dispatch and server consent are separate. */
    @Test
    fun nativePanelKeepsDisplayedVersionsAndUnavailableOptionalChoices() {
        syntheticBoundaryContext()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val style = WalkSafeViewStyle(activity)
                lateinit var presenter: AccountAccessPresenter
                val panel = AccountAccessPanel(activity, style, AccountAccessPanel.Actions(
                    onInputChanged = {}, onConsentChanged = { presenter.refreshAccountConsentSummary() },
                    onListenConsent = {}, onConsentContinue = { presenter.continueToDetails() },
                    onRequestOtp = {}, onCreateAccount = {}, onLogin = {}, onLogout = {},
                    onSignupToggle = {}, onDisclosureToggle = {},
                ))
                presenter = AccountAccessPresenter(style, AccountAccessPresenter.Actions(
                    isRequestBusy = { false }, isGatewayRequestInFlight = { false },
                    refreshActionAvailability = {}, refreshAccessUi = {}, showAccountMessage = {},
                ))
                presenter.bindPanel(panel)
                assertNull("Unattached clauses cannot authorize a displayed document map", presenter.displayedSignupDocumentVersions())
                panel.accountAccessControls.visibility = View.VISIBLE
                panel.accountSignupControls.visibility = View.VISIBLE
                panel.accountConsentStepControls.visibility = View.VISIBLE
                panel.accountDetailsStepControls.visibility = View.GONE
                activity.setContentView(panel.accountAccessControls)
                assertEquals(SIGNUP_DOCUMENT_VERSIONS, presenter.displayedSignupDocumentVersions())
                presenter.applyAccountConsentSelections(SignupConsentSelections(true, true, true, true, true, true))
                presenter.showUnavailableOptionalSignupDocuments(setOf("raw_original"))
                val raw = panel.accountConsentChecks.getValue("raw_original")
                assertFalse(raw.isChecked)
                assertFalse(raw.isEnabled)
                panel.accountConsentAllCheck.performClick()
                panel.accountConsentAllCheck.performClick()
                assertFalse("All selection cannot restore unavailable optional consent", raw.isChecked)
                assertTrue("All selection must grant the other currently available choices",
                    panel.accountConsentChecks.filterKeys { it != "raw_original" }.values.all { it.isChecked })
                presenter.applyAccountConsentSelections(SignupConsentSelections(true, true, true, true, true, true))
                assertFalse("Restored form choices cannot restore unavailable consent", raw.isChecked)
                assertFalse(presenter.currentAccountConsentSelections().rawOriginal)
                presenter.continueToDetails()
                panel.accountConsentStepControls.visibility = View.GONE
                assertEquals(AccountAccessPresenter.AccountSignupStep.DETAILS, presenter.accountSignupStep)
                assertEquals("The transition retains the actual bundled visible map", SIGNUP_DOCUMENT_VERSIONS,
                    presenter.displayedSignupDocumentVersions())
                presenter.showUnavailableOptionalSignupDocuments(emptySet())
                assertTrue("Normal policy recovery re-enables the optional checkbox", raw.isEnabled)
                assertFalse("Recovery alone never grants consent", raw.isChecked)
            }
        }
    }

    @Test
    fun platformIcuMatchesTheSharedGatewayVectors() {
        syntheticBoundaryContext()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("account-email.json").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Shared contract corpus identity", "719675ab70ac96024e023d4a934993fd75500dc3ae6aee28b4c37c81bd01fd38", hash)
        val vectors = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("vectors")
        assertEquals(53, vectors.length())
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            assertEquals("Native ICU vector $index", if (vector.isNull("canonical")) null else vector.getString("canonical"),
                GatewayAccountInputPolicy.canonicalEmailOrNull(vector.getString("input")))
        }
    }
}
