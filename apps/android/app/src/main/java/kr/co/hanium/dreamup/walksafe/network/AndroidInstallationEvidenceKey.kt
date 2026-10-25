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
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import org.json.JSONArray
import org.json.JSONObject

/** One installation key. Existing or lost keys are never silently replaced. Worker calls only. */
internal class AndroidInstallationEvidenceKey(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "installation-evidence")

    fun publicKey(installationId: String, gatewayOrigin: String, actorId: String, accountGeneration: Long): String = synchronized(lock) {
        val binding = binding(gatewayOrigin, actorHash(actorId), accountGeneration)
        val alias = alias(installationId)
        val keyStore = keyStore()
        val marker = readMarker(installationId)
        if (!keyStore.containsAlias(alias)) {
            check(marker == null) { "Installation evidence signing key is missing" }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val public = keyStore.getCertificate(alias)?.publicKey as? ECPublicKey
            ?: error("Installation evidence public key is unavailable")
        require(public.params.curve.field.fieldSize == 256)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(public.encoded)
        require(validInstallationEvidencePublicKey(encoded))
        check(marker == null || marker.publicKey == encoded) { "Installation evidence key changed" }
        val next = (marker ?: Marker(encoded, emptySet(), emptySet())).let {
            it.copy(preparedBindings = it.preparedBindings + binding)
        }
        writeMarker(installationId, next)
        encoded
    }

    fun markRegistered(installationId: String, gatewayOrigin: String, actorId: String, accountGeneration: Long) = synchronized(lock) {
        val binding = binding(gatewayOrigin, actorHash(actorId), accountGeneration)
        val marker = readMarker(installationId) ?: error("Installation evidence key was not prepared")
        check(binding in marker.preparedBindings)
        check(keyStore().containsAlias(alias(installationId))) { "Installation evidence signing key is missing" }
        writeMarker(installationId, marker.copy(registeredBindings = marker.registeredBindings + binding))
    }

    /** Registration knowledge is separate from a signed local deletion result. */
    fun isRegistered(installationId: String, gatewayOrigin: String, actorId: String, accountGeneration: Long): Boolean = synchronized(lock) {
        binding(gatewayOrigin, actorHash(actorId), accountGeneration) in readMarker(installationId)?.registeredBindings.orEmpty()
    }

    fun signIfPresent(journal: AccountDeletionJournal, digest: String, actorHash: String): String? = synchronized(lock) {
        val generation = requireNotNull(journal.accountGeneration).also { require(it > 0L) }
        val installation = journal.installationId
        val marker = readMarker(installation)
        val store = keyStore()
        val alias = alias(installation)
        if (!store.containsAlias(alias)) {
            check(marker == null) { "Installation evidence signing key is missing" }
            return@synchronized null // Existing key-less installations retain their deletion rights.
        }
        if (marker != null) {
            check(binding(journal.gatewayOrigin, actorHash, generation) in marker.preparedBindings) {
                "Installation evidence key belongs to another account generation"
            }
        }
        val public = store.getCertificate(alias)?.publicKey ?: error("Installation evidence public key is unavailable")
        val message = installationEvidenceSignatureMessage(digest)
        val cache = AtomicFile(File(directory, signaturePrefix(installation) + digest + ".txt"))
        if (exists(cache)) {
            val saved = cache.openRead().use { input ->
                require(input.channel.size() in 86L..100L)
                String(input.readBytes(), Charsets.US_ASCII)
            }
            check(verifyInstallationEvidenceSignature(public, digest, saved)) { "Stored deletion signature is invalid" }
            return@synchronized saved
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(alias, null) as java.security.PrivateKey)
            update(message)
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        writeAtomic(cache, signature.toByteArray(Charsets.US_ASCII))
        signature
    }

    /** Only the existing, acknowledged COMPLETED installation reset may call this. */
    fun resetAfterConfirmedDeletion(installationId: String): Boolean = synchronized(lock) {
        runCatching {
            val store = keyStore()
            val alias = alias(installationId)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            val marker = markerFile(installationId)
            marker.delete()
            val files = if (directory.exists()) directory.listFiles() ?: return@synchronized false else emptyArray()
            val prefix = signaturePrefix(installationId)
            val signaturesDeleted = files.filter { it.name.startsWith(prefix) }.map { file ->
                file.isFile && file.delete()
            }.all { it }
            signaturesDeleted && !store.containsAlias(alias) && !exists(marker) &&
                directory.listFiles().orEmpty().none { it.name.startsWith(prefix) }
        }.getOrDefault(false)
    }

    private data class Marker(val publicKey: String, val preparedBindings: Set<String>, val registeredBindings: Set<String>)
    private fun markerFile(installation: String) = AtomicFile(File(directory, "key-" + installationHash(installation) + ".json"))
    private fun readMarker(installation: String): Marker? {
        val file = markerFile(installation)
        if (!exists(file)) return null
        val body = file.openRead().use { input ->
            require(input.channel.size() in 1L..16_384L)
            String(input.readBytes(), Charsets.UTF_8)
        }
        val json = JSONObject(body)
        require(json.keys().asSequence().toSet() == setOf("public_key", "prepared", "registered"))
        fun hashes(name: String): Set<String> = json.getJSONArray(name).let { array ->
            require(array.length() <= 64)
            (0 until array.length()).map { array.getString(it).also { hash -> require(SHA256.matches(hash)) } }.toSet()
        }
        val marker = Marker(json.getString("public_key"), hashes("prepared"), hashes("registered"))
        require(validInstallationEvidencePublicKey(marker.publicKey))
        require(marker.preparedBindings.containsAll(marker.registeredBindings))
        return marker
    }
    private fun writeMarker(installation: String, marker: Marker) {
        require(marker.preparedBindings.size <= 64)
        writeAtomic(markerFile(installation), JSONObject().put("public_key", marker.publicKey)
            .put("prepared", JSONArray(marker.preparedBindings.sorted()))
            .put("registered", JSONArray(marker.registeredBindings.sorted())).toString().toByteArray(Charsets.UTF_8))
    }
    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(installation: String) = "walkmate.deletion-evidence." + installationHash(installation)
    private fun installationHash(installation: String): String {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}").matches(installation))
        return sha256(installation)
    }
    private fun signaturePrefix(installation: String) = "signature-" + installationHash(installation) + "-"
    private fun actorHash(actor: String): String {
        require(actor.isNotBlank() && actor.length <= 128)
        return sha256(actor)
    }
    private fun binding(origin: String, actorHash: String, generation: Long): String =
        installationEvidenceOwnerBindingHash(origin, actorHash, generation)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private companion object { val lock = Any(); val SHA256 = Regex("[0-9a-f]{64}") }
}

internal fun installationEvidenceSignatureMessage(digest: String): ByteArray {
    require(Regex("[0-9a-f]{64}").matches(digest))
    return ("walksafe/device-deletion-evidence-signature/v1\u0000" + digest).toByteArray(Charsets.US_ASCII)
}

internal fun verifyInstallationEvidenceSignature(public: java.security.PublicKey, digest: String, signature: String): Boolean =
    runCatching {
        val bytes = Base64.getUrlDecoder().decode(signature)
        require(signature == Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) && bytes.size in 64..75)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(public); update(installationEvidenceSignatureMessage(digest)); verify(bytes)
        }
    }.getOrDefault(false)

internal fun validInstallationEvidencePublicKey(value: String): Boolean = runCatching {
    val bytes = Base64.getUrlDecoder().decode(value)
    require(bytes.size == 91 && value == Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(bytes)) as ECPublicKey
    val curve = java.security.AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
    key.params.curve == curve.curve && key.params.order == curve.order && key.params.generator == curve.generator &&
        key.params.cofactor == curve.cofactor && bytes.contentEquals(key.encoded)
}.getOrDefault(false)

internal fun installationEvidenceOwnerBindingHash(origin: String, actorHash: String, generation: Long): String {
    require(generation > 0L && Regex("[0-9a-f]{64}").matches(actorHash))
    val uri = java.net.URI(origin)
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath in setOf("", "/"))
    return MessageDigest.getInstance("SHA-256").digest((origin + "\u0000" + actorHash + "\u0000" + generation)
        .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
