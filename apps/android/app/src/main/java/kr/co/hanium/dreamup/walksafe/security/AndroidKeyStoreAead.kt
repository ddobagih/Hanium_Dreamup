package kr.co.hanium.dreamup.walksafe.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidKeyStoreAead(
    policy: AeadKeyPolicy,
) : LocalAead by VersionedLocalAead(
    policy = policy,
    keyProvider = AndroidKeyStoreKeyProvider(),
)

private class AndroidKeyStoreKeyProvider : LocalAeadKeyProvider {
    @Synchronized
    override fun getOrCreate(alias: String): SecretKey? = synchronized(KEYSTORE_LOCK) {
        val store = keyStore()
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) {
            observedAliases += alias
            ensureGenerationTombstone(store, alias)
            return@synchronized existing
        }
        if (alias in observedAliases || store.containsAlias(tombstoneAlias(alias))) {
            observedAliases += alias
            return@synchronized null
        }
        observedAliases += alias
        ensureGenerationTombstone(store, alias)
        generateKey(alias)
    }

    @Synchronized
    override fun getExisting(alias: String): SecretKey? = synchronized(KEYSTORE_LOCK) {
        observedAliases += alias
        keyStore().getKey(alias, null) as? SecretKey
    }

    @Synchronized
    override fun delete(alias: String): Boolean = synchronized(KEYSTORE_LOCK) {
        runCatching {
            observedAliases += alias
            val store = keyStore()
            ensureGenerationTombstone(store, alias)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            !store.containsAlias(alias) && store.containsAlias(tombstoneAlias(alias))
        }.getOrDefault(false)
    }

    @Synchronized
    override fun createFreshAfterVerifiedPurge(alias: String): SecretKey? =
        synchronized(KEYSTORE_LOCK) {
            observedAliases += alias
            val store = keyStore()
            (store.getKey(alias, null) as? SecretKey) ?: run {
                ensureGenerationTombstone(store, alias)
                generateKey(alias)
            }
        }

    private fun ensureGenerationTombstone(store: KeyStore, alias: String) {
        val tombstone = tombstoneAlias(alias)
        if (!store.containsAlias(tombstone)) generateKey(tombstone)
        check(store.containsAlias(tombstone))
    }

    private fun generateKey(alias: String): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        KEYSTORE_PROVIDER,
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generateKey()
    }

    private fun tombstoneAlias(alias: String): String = "$alias.generation"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val AES_KEY_SIZE_BITS = 256
        val KEYSTORE_LOCK = Any()
        val observedAliases = mutableSetOf<String>()
    }
}
