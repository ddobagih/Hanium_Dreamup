package kr.co.hanium.dreamup.walksafe.security

import android.content.SharedPreferences
import java.util.IdentityHashMap
import org.json.JSONObject

internal data class SensitivePreferenceSpec(
    val storageKey: String,
    val blockedKey: String,
    val resetPendingKey: String,
    val domainAad: ByteArray,
    val keyPolicy: AeadKeyPolicy,
    val exactKeys: Set<String>,
    val keyPrefixes: Set<String> = emptySet(),
) {
    init {
        require(storageKey.isNotBlank())
        require(blockedKey.isNotBlank())
        require(resetPendingKey.isNotBlank())
        require(setOf(storageKey, blockedKey, resetPendingKey).size == 3)
        require(domainAad.isNotEmpty())
        require(exactKeys.none { it == storageKey || it == blockedKey || it == resetPendingKey })
        require(
            keyPrefixes.none {
                storageKey.startsWith(it) || blockedKey.startsWith(it) ||
                    resetPendingKey.startsWith(it)
            },
        )
    }

    fun allows(key: String): Boolean =
        key in exactKeys || keyPrefixes.any(key::startsWith)
}

/**
 * Stores one purpose-scoped logical preference map as one authenticated ciphertext snapshot.
 *
 * The raw SharedPreferences contains only the ciphertext envelope and an identity-free fail-closed
 * marker. Existing plaintext values are removed and block the domain; they are never accepted as a
 * runtime fallback or silently migrated.
 */
internal class AndroidSensitivePreferenceStore private constructor(
    private val storage: SensitivePreferenceStorage,
    private val spec: SensitivePreferenceSpec,
    private val aead: LocalAead,
    private val initiallyBlockedForExternalReset: Boolean,
) {
    constructor(
        preferences: SharedPreferences,
        spec: SensitivePreferenceSpec,
        aead: LocalAead = AndroidKeyStoreAead(spec.keyPolicy),
        initiallyBlockedForExternalReset: Boolean = false,
    ) : this(
        storage = SharedPreferencesSensitiveStorage(preferences),
        spec = spec,
        aead = aead,
        initiallyBlockedForExternalReset = initiallyBlockedForExternalReset,
    )

    internal constructor(
        storage: SensitivePreferenceStorage,
        spec: SensitivePreferenceSpec,
        aead: LocalAead,
        testOnly: Unit = Unit,
        initiallyBlockedForExternalReset: Boolean = false,
    ) : this(storage, spec, aead, initiallyBlockedForExternalReset)

    private var values = mutableMapOf<String, StoredValue>()
    private var blocked = false
    private var observedResetEpoch = 0L
    private var observedOwnerEpoch = 0L

    init {
        synchronized(PROCESS_LOCK) {
            claimOwnerEpochLocked()
            observedResetEpoch = currentResetEpochLocked()
            if (initiallyBlockedForExternalReset || isResetPendingLocked()) {
                blocked = true
            } else {
                initialize()
            }
        }
    }

    @Synchronized
    fun isBlocked(): Boolean = synchronized(PROCESS_LOCK) {
        blocked || isStaleLocked()
    }

    @Synchronized
    fun contains(key: String): Boolean {
        requireAllowed(key)
        return synchronized(PROCESS_LOCK) {
            !blocked && !isStaleLocked() && key in values
        }
    }

    @Synchronized
    fun keys(): Set<String> = synchronized(PROCESS_LOCK) {
        if (blocked || isStaleLocked()) emptySet() else values.keys.toSet()
    }

    @Synchronized
    fun getString(key: String, defaultValue: String?): String? {
        requireAllowed(key)
        return synchronized(PROCESS_LOCK) {
            if (blocked || isStaleLocked()) defaultValue
            else (values[key] as? StoredValue.StringValue)?.value ?: defaultValue
        }
    }

    @Synchronized
    fun getLong(key: String, defaultValue: Long): Long {
        requireAllowed(key)
        return synchronized(PROCESS_LOCK) {
            if (blocked || isStaleLocked()) defaultValue
            else (values[key] as? StoredValue.LongValue)?.value ?: defaultValue
        }
    }

    @Synchronized
    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        requireAllowed(key)
        return synchronized(PROCESS_LOCK) {
            if (blocked || isStaleLocked()) defaultValue
            else (values[key] as? StoredValue.BooleanValue)?.value ?: defaultValue
        }
    }

    @Synchronized
    fun edit(): Editor = Editor()

    @Synchronized
    fun failClosed() {
        synchronized(PROCESS_LOCK) {
            if (isStaleLocked()) blocked = true else markBlocked()
        }
    }

    /** Explicit account-deletion/new-enrollment recovery path; normal callers cannot clear Blocked. */
    @Synchronized
    fun destroyAndClear(): Boolean = synchronized(PROCESS_LOCK) {
        if (!ownerIsCurrentLocked()) {
            blocked = true
            return@synchronized false
        }
        val observedKeys = storage.keys() ?: run {
            markBlocked()
            return@synchronized false
        }
        val keysToRemove = observedKeys.filterTo(mutableSetOf()) { spec.allows(it) }
        keysToRemove += spec.storageKey
        keysToRemove += spec.blockedKey
        beginResetEpochLocked(adoptEpoch = true)
        val staged = storage.commit(
            strings = mapOf(spec.resetPendingKey to RESET_PENDING_VALUE),
            removals = keysToRemove,
        )
        if (!staged) {
            markBlocked()
            return@synchronized false
        }
        reconcilePendingReset() == ResetReconcileResult.COMPLETED
    }

    inner class Editor internal constructor() {
        private val updates = mutableMapOf<String, StoredValue?>()
        private var clearRequested = false

        fun putString(key: String, value: String?): Editor = apply {
            requireAllowed(key)
            updates[key] = value?.let(StoredValue::StringValue)
        }

        fun putLong(key: String, value: Long): Editor = apply {
            requireAllowed(key)
            updates[key] = StoredValue.LongValue(value)
        }

        fun putBoolean(key: String, value: Boolean): Editor = apply {
            requireAllowed(key)
            updates[key] = StoredValue.BooleanValue(value)
        }

        fun remove(key: String): Editor = apply {
            requireAllowed(key)
            updates[key] = null
        }

        fun clear(): Editor = apply { clearRequested = true }

        fun commit(): Boolean = commitEditor(clearRequested, updates)
    }

    private fun commitEditor(
        clearRequested: Boolean,
        updates: Map<String, StoredValue?>,
    ): Boolean = synchronized(this) {
        synchronized(PROCESS_LOCK) {
            if (blocked || isStaleLocked()) return@synchronized false
            val next = if (clearRequested) mutableMapOf() else values.toMutableMap()
            updates.forEach { (key, value) ->
                if (value == null) next.remove(key) else next[key] = value
            }
            persist(next)
        }
    }

    private fun initialize() {
        when (reconcilePendingReset()) {
            ResetReconcileResult.COMPLETED -> return
            ResetReconcileResult.BLOCKED -> {
                markBlocked()
                return
            }
            ResetReconcileResult.ABSENT -> Unit
        }
        if (storage.getBoolean(spec.blockedKey, false)) {
            blocked = true
            return
        }
        val observedKeys = storage.keys()
        if (observedKeys == null) {
            markBlocked()
            return
        }
        val plaintextKeys = observedKeys.filter(spec::allows).toSet()
        if (plaintextKeys.isNotEmpty()) {
            blocked = true
            storage.commit(
                booleans = mapOf(spec.blockedKey to true),
                removals = plaintextKeys,
            )
            return
        }
        if (!storage.contains(spec.storageKey)) return
        val envelope = storage.getString(spec.storageKey)
        if (envelope == null) {
            markBlocked()
            return
        }
        when (val opened = aead.open(envelope, spec.domainAad, SNAPSHOT_LIMITS)) {
            is AeadOpenResult.Blocked -> markBlocked()
            is AeadOpenResult.Opened -> {
                val decoded = decodeSnapshot(opened.plaintext)
                opened.plaintext.fill(0)
                if (decoded == null) {
                    markBlocked()
                    return
                }
                values = decoded
                if (opened.needsRewrap && !persist(decoded)) markBlocked()
            }
        }
    }

    private fun persist(next: MutableMap<String, StoredValue>): Boolean {
        val payload = encodeSnapshot(next).toByteArray(Charsets.UTF_8)
        val sealed = aead.seal(payload, spec.domainAad, SNAPSHOT_LIMITS)
        payload.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope
            ?: run {
                markBlocked()
                return false
            }
        val committed = storage.commit(
            strings = mapOf(spec.storageKey to envelope),
            removals = setOf(spec.blockedKey),
        )
        if (committed) {
            values = next
            blocked = false
            advanceOwnerEpochLocked()
        } else {
            markBlocked()
        }
        return committed
    }

    private fun markBlocked() {
        blocked = true
        if (!ownerIsCurrentLocked()) return
        if (storage.commit(booleans = mapOf(spec.blockedKey to true))) {
            advanceOwnerEpochLocked()
        }
    }

    private fun reconcilePendingReset(): ResetReconcileResult {
        if (!storage.contains(spec.resetPendingKey)) return ResetReconcileResult.ABSENT
        beginResetEpochLocked(adoptEpoch = true)
        if (storage.getRaw(spec.resetPendingKey) != RESET_PENDING_VALUE) {
            blocked = true
            return ResetReconcileResult.BLOCKED
        }
        val observedKeys = storage.keys() ?: run {
            blocked = true
            return ResetReconcileResult.BLOCKED
        }
        val storageIsClear = spec.storageKey !in observedKeys &&
            spec.blockedKey !in observedKeys &&
            observedKeys.none(spec::allows)
        if (!storageIsClear || !aead.destroyKnownVersions()) {
            blocked = true
            return ResetReconcileResult.BLOCKED
        }
        if (!aead.createFreshAfterVerifiedPurge()) {
            blocked = true
            return ResetReconcileResult.BLOCKED
        }
        val markerCleared = storage.commit(removals = setOf(spec.resetPendingKey)) &&
            !storage.contains(spec.resetPendingKey)
        if (!markerCleared) {
            blocked = true
            return ResetReconcileResult.BLOCKED
        }
        values.clear()
        blocked = false
        finishResetEpochLocked()
        advanceOwnerEpochLocked()
        return ResetReconcileResult.COMPLETED
    }

    private fun currentResetEpochLocked(): Long =
        RESET_EPOCHS[storage.processIdentity]?.get(spec.storageKey) ?: 0L

    private fun isStaleLocked(): Boolean {
        if (!ownerIsCurrentLocked()) return true
        if (observedResetEpoch != currentResetEpochLocked() || isResetPendingLocked()) {
            return true
        }
        if (!storage.contains(spec.resetPendingKey)) return false
        beginResetEpochLocked(adoptEpoch = false)
        return true
    }

    private fun beginResetEpochLocked(adoptEpoch: Boolean) {
        val pendingScopes = RESET_PENDING_SCOPES.getOrPut(storage.processIdentity) {
            mutableSetOf()
        }
        val epochs = RESET_EPOCHS.getOrPut(storage.processIdentity) { mutableMapOf() }
        if (spec.storageKey !in pendingScopes) {
            val next = (epochs[spec.storageKey] ?: 0L) + 1L
            check(next > 0L) { "sensitive preference reset epoch exhausted" }
            epochs[spec.storageKey] = next
            pendingScopes += spec.storageKey
        }
        if (adoptEpoch) observedResetEpoch = epochs.getValue(spec.storageKey)
    }

    private fun finishResetEpochLocked() {
        RESET_PENDING_SCOPES[storage.processIdentity]?.let { pendingScopes ->
            pendingScopes -= spec.storageKey
            if (pendingScopes.isEmpty()) RESET_PENDING_SCOPES.remove(storage.processIdentity)
        }
    }

    private fun isResetPendingLocked(): Boolean =
        spec.storageKey in (RESET_PENDING_SCOPES[storage.processIdentity] ?: emptySet())

    private fun claimOwnerEpochLocked() {
        val epochs = OWNER_EPOCHS.getOrPut(storage.processIdentity) { mutableMapOf() }
        val next = (epochs[spec.storageKey] ?: 0L) + 1L
        check(next > 0L) { "sensitive preference owner epoch exhausted" }
        epochs[spec.storageKey] = next
        observedOwnerEpoch = next
    }

    private fun advanceOwnerEpochLocked() {
        check(ownerIsCurrentLocked()) { "stale sensitive preference owner cannot publish" }
        claimOwnerEpochLocked()
    }

    private fun ownerIsCurrentLocked(): Boolean =
        observedOwnerEpoch ==
            (OWNER_EPOCHS[storage.processIdentity]?.get(spec.storageKey) ?: 0L)

    private fun encodeSnapshot(snapshot: Map<String, StoredValue>): String {
        val encodedValues = JSONObject()
        snapshot.toSortedMap().forEach { (key, value) ->
            requireAllowed(key)
            encodedValues.put(
                key,
                when (value) {
                    is StoredValue.StringValue -> JSONObject()
                        .put("type", TYPE_STRING)
                        .put("value", value.value)
                    is StoredValue.LongValue -> JSONObject()
                        .put("type", TYPE_LONG)
                        .put("value", value.value)
                    is StoredValue.BooleanValue -> JSONObject()
                        .put("type", TYPE_BOOLEAN)
                        .put("value", value.value)
                },
            )
        }
        return JSONObject()
            .put("snapshot_version", SNAPSHOT_VERSION)
            .put("values", encodedValues)
            .toString()
    }

    private fun decodeSnapshot(payload: ByteArray): MutableMap<String, StoredValue>? =
        runCatching {
            require(payload.size <= SNAPSHOT_LIMITS.maxPlaintextBytes)
            val root = JSONObject(String(payload, Charsets.UTF_8))
            require(root.keysAsSet() == ROOT_FIELDS)
            require(root.getInt("snapshot_version") == SNAPSHOT_VERSION)
            val encodedValues = root.getJSONObject("values")
            require(encodedValues.length() <= MAX_ENTRY_COUNT)
            buildMap {
                encodedValues.keys().forEach { key ->
                    requireAllowed(key)
                    val encoded = encodedValues.getJSONObject(key)
                    require(encoded.keysAsSet() == VALUE_FIELDS)
                    val value = when (encoded.getString("type")) {
                        TYPE_STRING -> StoredValue.StringValue(encoded.getString("value"))
                        TYPE_LONG -> StoredValue.LongValue(encoded.getLong("value"))
                        TYPE_BOOLEAN -> StoredValue.BooleanValue(encoded.getBoolean("value"))
                        else -> error("unsupported sensitive preference type")
                    }
                    put(key, value)
                }
            }.toMutableMap()
        }.getOrNull()

    private fun requireAllowed(key: String) {
        require(spec.allows(key)) { "key is outside the sensitive preference domain" }
    }

    private sealed interface StoredValue {
        data class StringValue(val value: String) : StoredValue
        data class LongValue(val value: Long) : StoredValue
        data class BooleanValue(val value: Boolean) : StoredValue
    }

    private enum class ResetReconcileResult {
        ABSENT,
        COMPLETED,
        BLOCKED,
    }

    private companion object {
        const val SNAPSHOT_VERSION = 1
        const val TYPE_STRING = "string"
        const val TYPE_LONG = "long"
        const val TYPE_BOOLEAN = "boolean"
        const val RESET_PENDING_VALUE = "RESET_PENDING_V1"
        const val MAX_ENTRY_COUNT = 256
        val ROOT_FIELDS = setOf("snapshot_version", "values")
        val VALUE_FIELDS = setOf("type", "value")
        val SNAPSHOT_LIMITS = AeadLimits(
            maxPlaintextBytes = 64 * 1_024,
            maxCiphertextBytes = 64 * 1_024 + 16,
            maxEnvelopeChars = 96 * 1_024,
        )
        val PROCESS_LOCK = Any()
        val OWNER_EPOCHS = IdentityHashMap<Any, MutableMap<String, Long>>()
        val RESET_EPOCHS = IdentityHashMap<Any, MutableMap<String, Long>>()
        val RESET_PENDING_SCOPES = IdentityHashMap<Any, MutableSet<String>>()
    }
}

internal interface SensitivePreferenceStorage {
    val processIdentity: Any
    fun contains(key: String): Boolean
    fun keys(): Set<String>?
    fun getRaw(key: String): Any?
    fun getString(key: String): String?
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun commit(
        strings: Map<String, String> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
        removals: Set<String> = emptySet(),
    ): Boolean
}

private class SharedPreferencesSensitiveStorage(
    private val preferences: SharedPreferences,
) : SensitivePreferenceStorage {
    override val processIdentity: Any = preferences
    override fun contains(key: String): Boolean =
        runCatching { preferences.contains(key) }.getOrDefault(true)

    override fun keys(): Set<String>? =
        runCatching { preferences.all.keys }.getOrNull()

    override fun getRaw(key: String): Any? =
        runCatching { preferences.all[key] }.getOrNull()

    override fun getString(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, defaultValue) }.getOrDefault(true)

    override fun commit(
        strings: Map<String, String>,
        booleans: Map<String, Boolean>,
        removals: Set<String>,
    ): Boolean = runCatching {
        preferences.edit().also { editor ->
            strings.forEach(editor::putString)
            booleans.forEach(editor::putBoolean)
            removals.forEach(editor::remove)
        }.commit()
    }.getOrDefault(false)
}

private fun JSONObject.keysAsSet(): Set<String> = buildSet {
    keys().forEach(::add)
}
