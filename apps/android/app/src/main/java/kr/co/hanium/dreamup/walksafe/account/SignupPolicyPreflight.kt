package kr.co.hanium.dreamup.walksafe.account

import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException
import org.json.JSONObject

/** Metadata can validate bundled documents, but can never stand in for displaying new documents. */
internal object SignupPolicyPreflight {
    private val requiredKeys = setOf("terms_of_service", "privacy_notice", "location_terms")
    private val versionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")

    fun unavailableOptional(value: JSONObject): Set<String> {
        fun invalid(): Nothing = throw GatewayAccountHttpException(0, "invalid_signup_policy")
        if (value.keys().asSequence().toSet() != setOf("schema_version", "documents") ||
            value.opt("schema_version") != "walksafe.account-signup-policy.v1") invalid()
        val documents = value.optJSONObject("documents") ?: invalid()
        if (documents.keys().asSequence().toSet() != SIGNUP_DOCUMENT_VERSIONS.keys) invalid()
        val unavailable = mutableSetOf<String>()
        SIGNUP_DOCUMENT_VERSIONS.forEach { (key, bundled) ->
            val document = documents.optJSONObject(key) ?: invalid()
            if (document.keys().asSequence().toSet() !=
                setOf("version", "required", "equivalent_versions", "prior_versions") ||
                document.opt("required") != (key in requiredKeys)) invalid()
            fun version(raw: Any?): String = (raw as? String)?.takeIf(versionPattern::matches) ?: invalid()
            fun versions(name: String): Set<String> {
                val array = document.optJSONArray(name) ?: invalid()
                if (array.length() > 8) invalid()
                val result = (0 until array.length()).map { version(array.opt(it)) }
                if (result.toSet().size != result.size) invalid()
                return result.toSet()
            }
            val current = version(document.opt("version"))
            val equivalent = versions("equivalent_versions")
            val prior = versions("prior_versions")
            if (current in equivalent || current in prior || equivalent.any { it in prior } ||
                (key in requiredKeys && prior.isNotEmpty())) invalid()
            if (bundled != current && bundled !in equivalent) {
                if (key in requiredKeys || bundled !in prior) {
                    throw GatewayAccountHttpException(409, "signup_policy_outdated", SignupConsentPolicyNotice.OUTDATED_CODE)
                }
                unavailable += key
            }
        }
        return unavailable.toSet()
    }

    fun restrict(selections: SignupConsentSelections, unavailable: Set<String>): SignupConsentSelections =
        selections.copy(
            rawOriginal = selections.rawOriginal && "raw_original" !in unavailable,
            automaticReporting = selections.automaticReporting && "automatic_reporting" !in unavailable,
            trainingReuse = selections.trainingReuse && "training_reuse" !in unavailable,
        )
}
