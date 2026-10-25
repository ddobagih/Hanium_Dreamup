package android.icu.text

/** Host contract tests run real ICU4J. Native Android ICU remains a separate device check. */
class IDNA private constructor(options: Int) {
    private val delegate = com.ibm.icu.text.IDNA.getUTS46Instance(options)
    fun nameToASCII(name: CharSequence, destination: StringBuilder, info: Info): StringBuilder =
        delegate.nameToASCII(name, destination, info.delegate)

    class Info {
        internal val delegate = com.ibm.icu.text.IDNA.Info()
        fun hasErrors(): Boolean = delegate.hasErrors()
    }

    companion object {
        const val CHECK_BIDI = com.ibm.icu.text.IDNA.CHECK_BIDI
        const val CHECK_CONTEXTJ = com.ibm.icu.text.IDNA.CHECK_CONTEXTJ
        const val NONTRANSITIONAL_TO_ASCII = com.ibm.icu.text.IDNA.NONTRANSITIONAL_TO_ASCII
        @JvmStatic fun getUTS46Instance(options: Int): IDNA = IDNA(options)
    }
}
