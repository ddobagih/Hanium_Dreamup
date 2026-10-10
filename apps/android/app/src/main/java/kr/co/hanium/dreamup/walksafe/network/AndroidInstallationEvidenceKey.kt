package kr.co.hanium.dreamup.walksafe.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Private keys never leave Android Keystore. Cache public signatures for byte-identical retries. */
internal class AndroidInstallationEvidenceKey(private val context: Context) {
    private fun alias(installation: String) = "walkmate.deletion-evidence." + sha256(installation)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun publicKey(installation: String): String = synchronized(lock) {
        val alias = alias(installation)
        val store = store()
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        Base64.getUrlEncoder().withoutPadding().encodeToString(store.getCertificate(alias).publicKey.encoded)
    }

    fun signIfPresent(installation: String, digest: String): String? = synchronized(lock) {
        require(Regex("[0-9a-f]{64}").matches(digest))
        val store = store()
        val alias = alias(installation)
        if (!store.containsAlias(alias)) return@synchronized null // legacy, never replace a lost signing key
        val message = ("walksafe/device-deletion-evidence-signature/v1\u0000" + digest).toByteArray(Charsets.US_ASCII)
        val publicKey = store.getCertificate(alias).publicKey
        val file = AtomicFile(File(context.noBackupFilesDir, "deletion-signature-" + sha256(installation + ":" + digest)))
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val saved = file.openRead().use { input ->
                require(input.channel.size() in 86L..100L)
                String(input.readBytes(), Charsets.US_ASCII)
            }
            check(Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey); update(message); verify(Base64.getUrlDecoder().decode(saved))
            }) { "Stored deletion signature is invalid" }
            return@synchronized saved
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(alias, null) as java.security.PrivateKey)
            update(message)
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        val output = file.startWrite()
        try { output.write(signature.toByteArray(Charsets.US_ASCII)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        signature
    }

    /** Called only by the existing confirmed-deletion reset, after evidence acknowledgement. */
    fun resetAfterConfirmedDeletion(): Boolean = synchronized(lock) {
        runCatching {
            val store = store()
            store.aliases().toList().filter { it.startsWith("walkmate.deletion-evidence.") }
                .forEach(store::deleteEntry)
            val files = context.noBackupFilesDir.listFiles() ?: return@synchronized false
            files.filter { it.name.startsWith("deletion-signature-") }.forEach {
                check(!it.isDirectory && it.delete())
            }
            true
        }.getOrDefault(false)
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object { val lock = Any() }
}
