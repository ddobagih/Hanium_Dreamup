package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.*
import kr.co.hanium.dreamup.walksafe.startup.PreparedPrivacyStartupReset

/** Real durable stores/state authority on an isolated temporary root and controlled preference disk. */
internal class DeletionRecoveryFixture(
    operations: AndroidAccountDeletionFallbackMarker.Operations = AndroidAccountDeletionFallbackMarker.Operations(),
) : AutoCloseable {
    val root: File = Files.createTempDirectory("walksafe-deletion-recovery-").toFile()
    val events = mutableListOf<String>()
    val machine = AccountDeletionStateMachine()
    val lease = machine.attachActivity()
    val disk = Disk()
    private val policy = AeadKeyPolicy("walksafe.test.recovery.v", 1, setOf(1))
    val preferences = AndroidSensitivePreferenceStore(disk,
        SensitivePreferenceSpec("envelope", "blocked", "reset_pending", "test|recovery".toByteArray(), policy,
            setOf("account_deletion_journal_v2", "account_deletion_actor_hash_v2",
                "account_deletion_fail_closed_v1", "account_deletion_fail_closed_reason_v1")),
        VersionedLocalAead(policy, Keys()))
    private val markerAead = VersionedLocalAead(AeadKeyPolicy("walksafe.test.recovery.marker.v", 1, setOf(1)), Keys())
    val marker = AndroidAccountDeletionFallbackMarker(File(root, AndroidAccountDeletionFallbackMarker.MARKER_FILE_NAME),
        operations = operations, aead = markerAead)
    var markerFactoryCalls = 0
    var installationQueries = 0
    var callbackAllowed = true
    var origin: String? = "https://gateway.example.test"
    val actor = "actor_recovery_0001"
    val session = GatewayFieldSession.verified(requireNotNull(origin), actor, "walksafe_field_session=v2.test", Long.MAX_VALUE)
    private var intent = false
    private val plain = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, _ ->
        when (method.name) {
            "contains" -> false
            "getAll" -> emptyMap<String, Any>()
            else -> error("Unexpected plain preference access: ${method.name}")
        }
    } as SharedPreferences
    lateinit var store: AccountDeletionRecoveryStore
        private set
    private val resetStorage = AccountDeletionResetStorage({ plain }, { preferences }, { null }, { store.dualAuthority },
        { error("Reset route participant was not prepared") }, { error("Reset field participant was not prepared") })
    val resetController = AccountDeletionInstallationResetController({ machine }, { lease },
        { Executor { error("Unexpected reset executor work") } }, { error("Unexpected reset UI post") },
        { error("Unexpected reset UI work") }, resetStorage,
        object : InstallationResetStartupHandoff { override var pending = false },
        object : InstallationResetActions {
            override fun isDestroyed() = false
            override fun activityCallbackAllowed() = callbackAllowed
            override fun updateUi() { error("Unexpected reset UI update") }
            override fun keepPrivacyFenceClosed() { events += "keep-fence" }
            override fun invalidateGatewaySession() { error("Unexpected Gateway invalidation") }
            override fun captureParticipants(): PreparedPrivacyStartupReset = error("No reset participant capture in recovery fixture")
            override fun prepareFreshSpeechCollection(): PreparedResetSpeechCollection? = error("No fresh collection in recovery fixture")
            override fun resetReportPrivacyForNewEnrollment() { error("Unexpected report enrollment reset") }
            override fun resetRuntimeForNewEnrollment() { error("Unexpected runtime enrollment reset") }
            override fun finishActivity() { error("Unexpected Activity finish") }
            override fun observeStartupAuthority(snapshot: AccountDeletionAuthoritySnapshot) { store.observeAuthority(snapshot) }
            override fun blockPrivacyForResetFailure() { events += "reset-block" }
            override fun applyTerminalPersistenceFailure(reason: String) { events += "terminal-failure:$reason" }
            override fun showTerminalPersistenceFailure(reason: String) { events += "terminal-ui:$reason" }
        })
    val stages = AccountDeletionWorkerStages({ machine }, { lease }, { resetController }, { events += "keep-fence" })
    init {
        store = AccountDeletionRecoveryStore({ preferences }, { installationQueries++; "installation_0001" },
            { markerFactoryCalls++; marker }, { origin }, { machine }, { lease }, stages, { resetController },
            object : AccountDeletionRecoveryEffects {
                override fun activityCallbackAllowed() = callbackAllowed
                override fun blockSpeechOutputStorageForAccountDeletion() { events += "speech-block" }
                override fun blockPrivacyForAuthority() { events += "authority-block" }
                override fun applyRuntimeFence(): Boolean { events += "runtime-fence"; return true }
            })
        store.bindIntentFence(AccountDeletionIntentFence(read = { intent }, store = { intent = true; true }, clear = { intent = false; true }))
        store.bindFileAuthority(FileAccountDeletionIntentAuthority(File(root, "authority")))
        resetController.initialize(root)
    }
    fun prepared(): AndroidAccountDeletionFallbackMarker.Record =
        (store.prepareRecoveryMarker(requireNotNull(origin), session, actor) as AccountDeletionRecoveryMarkerPreparation.Ready).record
    override fun close() { check(root.deleteRecursively()) }

    class Disk : SensitivePreferenceStorage {
        override val processIdentity: Any = this
        val values = mutableMapOf<String, Any>()
        var commitCount = 0
        var commitsSucceed = true
        override fun contains(key: String) = key in values
        override fun keys() = values.keys.toSet()
        override fun getRaw(key: String) = values[key]
        override fun getString(key: String) = values[key] as? String
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun commit(strings: Map<String, String>, booleans: Map<String, Boolean>, removals: Set<String>): Boolean {
            commitCount++
            if (!commitsSucceed) return false
            removals.forEach(values::remove); values.putAll(strings); values.putAll(booleans)
            return true
        }
    }
    private class Keys : LocalAeadKeyProvider {
        private val values = mutableMapOf<String, SecretKey>()
        override fun getOrCreate(alias: String): SecretKey = values.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun getExisting(alias: String) = values[alias]
        override fun delete(alias: String): Boolean { values.remove(alias); return true }
        override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
    }
}
