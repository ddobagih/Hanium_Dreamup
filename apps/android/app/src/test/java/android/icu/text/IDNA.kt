package android.icu.text

/** JVM tests use real ICU; device tests separately exercise Android's native ICU version. */
class IDNA private constructor(options: Int) {
    private val delegate = com.ibm.icu.text.IDNA.getUTS46Instance(options)
    fun nameToASCII(name: CharSequence, dest: StringBuilder, info: Info): StringBuilder =
        delegate.nameToASCII(name, dest, info.delegate)

    class Info {
        internal val delegate = com.ibm.icu.text.IDNA.Info()
        fun hasErrors(): Boolean = delegate.hasErrors()
    }

    companion object {
        @JvmStatic fun getUTS46Instance(options: Int): IDNA = IDNA(options)
    }
}
