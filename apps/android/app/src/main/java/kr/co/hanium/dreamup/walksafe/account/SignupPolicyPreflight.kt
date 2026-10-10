package kr.co.hanium.dreamup.walksafe.account

import org.json.JSONObject
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException

/** Compare only bundled versions: fetching metadata never means reading a new document. */
internal object SignupPolicyPreflight {
    fun unavailableOptional(value: JSONObject): Set<String> {
        fun invalid(): Nothing = throw GatewayAccountHttpException(0, "invalid_signup_policy")
        if (value.opt("schema_version") != "walksafe.account-signup-policy.v1") invalid()
        val docs = value.optJSONObject("documents") ?: invalid()
        if (docs.keys().asSequence().toSet() != SIGNUP_DOCUMENT_VERSIONS.keys) invalid()
        val unavailable = mutableSetOf<String>()
        SIGNUP_DOCUMENT_VERSIONS.entries.forEachIndexed { index, (key, bundled) ->
            val doc = docs.optJSONObject(key) ?: invalid()
            if (doc.keys().asSequence().toSet() != setOf("version", "required", "equivalent_versions", "prior_versions") ||
                doc.opt("required") != (index < 3)) invalid()
            fun version(raw: Any?): String = (raw as? String)?.takeIf {
                Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}").matches(it)
            } ?: invalid()
            fun versions(name: String): Set<String> {
                val array = doc.optJSONArray(name) ?: invalid()
                return (0 until array.length()).map { version(array.opt(it)) }.toSet()
            }
            val current = version(doc.opt("version"))
            val equivalent = versions("equivalent_versions")
            val prior = versions("prior_versions")
            if (bundled != current && bundled !in equivalent) {
                if (index < 3 || bundled !in prior) {
                    throw GatewayAccountHttpException(409, "signup_policy_outdated", SignupConsentPolicyNotice.OUTDATED_CODE)
                }
                unavailable.add(key)
            }
        }
        return unavailable
    }

    fun restrict(selections: SignupConsentSelections, unavailable: Set<String>) = selections.copy(
        rawOriginal = selections.rawOriginal && "raw_original" !in unavailable,
        automaticReporting = selections.automaticReporting && "automatic_reporting" !in unavailable,
        trainingReuse = selections.trainingReuse && "training_reuse" !in unavailable,
    )
}
