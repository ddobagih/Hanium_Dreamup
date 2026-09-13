package kr.co.hanium.dreamup.walksafe.positioneval.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.nio.CharBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PrivateFileImporter(private val context: Context) {
    companion object { const val MAX_INPUT_BYTES = 128L * 1024L * 1024L }

    fun import(
        uri: Uri, maxBytes: Long = MAX_INPUT_BYTES, cancellation: AnalysisCancellation? = null,
    ): ImportedArtifact {
        require(maxBytes in 1..MAX_INPUT_BYTES)
        cancellation?.throwIfCancelled()
        val root = File(context.noBackupFilesDir, "position_evaluator/imports").apply { mkdirs() }
        val displayName = safeDisplayName(context.contentResolver, uri)
        val id = UUID.randomUUID().toString()
        val part = File(root, "$id.part")
        val destination = File(root, "$id.bin")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("선택한 파일을 열 수 없습니다.")
            input.use { source ->
                FileOutputStream(part).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        cancellation?.throwIfCancelled()
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > maxBytes) throw IllegalArgumentException("파일이 128MB를 넘습니다.")
                        digest.update(buffer, 0, read)
                        target.write(buffer, 0, read)
                    }
                    target.flush()
                    target.fd.sync()
                }
            }
            if (count == 0L) throw IllegalArgumentException("빈 파일은 사용할 수 없습니다.")
            if (!part.renameTo(destination)) throw IllegalStateException("앱 비공개 사본을 확정하지 못했습니다.")
            ImportedArtifact(displayName, destination, count, digest.digest().toHex())
        } catch (error: Throwable) {
            part.delete()
            destination.delete()
            throw error
        }
    }

    private fun safeDisplayName(resolver: ContentResolver, uri: Uri): String {
        val raw = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "선택한 파일"
        return raw.filter { !it.isISOControl() }.take(120).ifBlank { "선택한 파일" }
    }
}

class NgiiFileKeyVault(private val context: Context) {
    companion object {
        private const val KEY_ALIAS = "walksafe.position-evaluator.ngii-key.v1"
        private const val ENVELOPE_VERSION = 1
        private const val MAX_KEY_CHARS = 512
    }
    private val envelope = File(context.noBackupFilesDir, "position_evaluator/ngii-key.v1")

    fun isStored(): Boolean = envelope.isFile && envelope.length() > 0

    fun save(value: CharArray) {
        require(value.isNotEmpty() && value.size <= MAX_KEY_CHARS) { "다운로드 키 길이를 확인해 주세요." }
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(value))
        val plaintext = ByteArray(encoded.remaining()).also(encoded::get)
        val part = File(envelope.parentFile, "${envelope.name}.part")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(aad())
            val ciphertext = cipher.doFinal(plaintext)
            envelope.parentFile?.mkdirs()
            if (part.exists() && !part.delete()) throw IllegalStateException("이전 키 임시 파일을 지우지 못했습니다.")
            FileOutputStream(part).use { fileOutput ->
                val output = DataOutputStream(fileOutput)
                output.writeInt(ENVELOPE_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
                output.flush()
                fileOutput.fd.sync()
            }
            Files.move(part.toPath(), envelope.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: Throwable) {
            if (part.exists() && !part.delete()) {
                throw IllegalStateException("키 임시 파일을 삭제하지 못했습니다.", error)
            }
            throw error
        } finally {
            plaintext.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
            value.fill('\u0000')
        }
    }

    fun load(): CharArray {
        try {
            DataInputStream(FileInputStream(envelope)).use { input ->
                if (input.readInt() != ENVELOPE_VERSION) throw IllegalStateException("지원하지 않는 키 저장 형식입니다.")
                val ivLength = input.readInt()
                if (ivLength !in 12..16) throw IllegalStateException("키 암호문 IV가 잘못되었습니다.")
                val iv = ByteArray(ivLength).also(input::readFully)
                val ciphertextLength = input.readInt()
                if (ciphertextLength !in 17..4096) throw IllegalStateException("키 암호문 길이가 잘못되었습니다.")
                val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
                if (input.read() != -1) throw IllegalStateException("키 암호문 뒤에 데이터가 남습니다.")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
                cipher.updateAAD(aad())
                val plaintext = cipher.doFinal(ciphertext)
                return try { decodeUtf8(plaintext) } finally { plaintext.fill(0) }
            }
        } catch (error: Exception) {
            resetKeyMaterial()
            throw IllegalStateException("저장된 다운로드 키를 복호화할 수 없습니다. 다시 입력해 주세요.", error)
        }
    }

    fun clear() {
        if (envelope.exists() && !envelope.delete()) throw IllegalStateException("저장된 다운로드 키를 삭제하지 못했습니다.")
        deleteKeyAlias()
    }

    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        return try {
            CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            if (decoded.hasArray()) decoded.array().fill('\u0000')
        }
    }

    private fun resetKeyMaterial() {
        if (envelope.exists() && !envelope.delete()) {
            throw IllegalStateException("손상된 다운로드 키 암호문을 삭제하지 못했습니다.")
        }
        deleteKeyAlias()
    }

    private fun deleteKeyAlias() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun aad(): ByteArray = "${context.packageName}|ngii-file-key|v1".toByteArray(Charsets.UTF_8)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
