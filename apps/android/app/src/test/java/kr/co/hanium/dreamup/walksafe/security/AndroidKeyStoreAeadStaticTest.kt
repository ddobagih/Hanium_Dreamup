package kr.co.hanium.dreamup.walksafe.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidKeyStoreAeadStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/security/AndroidKeyStoreAead.kt")
            .readText()

    @Test
    fun usesAndroidKeyStoreAes256GcmWithRandomizedEncryption() {
        assertTrue(source.contains("AndroidKeyStore"))
        assertTrue(source.contains("KeyProperties.KEY_ALGORITHM_AES"))
        assertTrue(source.contains("KeyProperties.BLOCK_MODE_GCM"))
        assertTrue(source.contains("KeyProperties.ENCRYPTION_PADDING_NONE"))
        assertTrue(source.contains(".setKeySize(AES_KEY_SIZE_BITS)"))
        assertTrue(source.contains("const val AES_KEY_SIZE_BITS = 256"))
        assertTrue(source.contains(".setRandomizedEncryptionRequired(true)"))
    }

    @Test
    fun existingKeyLookupDoesNotCreateAKey() {
        val existingBody = source.substringAfter("override fun getExisting")
            .substringBefore("override fun delete")
        assertTrue(existingBody.contains("keyStore().getKey(alias, null)"))
        assertFalse(existingBody.contains("KeyGenerator"))
        assertFalse(existingBody.contains("generateKey"))
    }
}
