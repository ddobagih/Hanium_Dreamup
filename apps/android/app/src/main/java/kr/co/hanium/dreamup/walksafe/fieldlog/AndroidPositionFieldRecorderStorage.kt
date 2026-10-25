package kr.co.hanium.dreamup.walksafe.fieldlog

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead

internal class AndroidPositionFieldScopeStorage(context: Context) : PositionFieldScopeStorage {
    private val preferences by lazy {
        context.getSharedPreferences(POSITION_FIELD_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val purgeMarker by lazy { File(context.noBackupFilesDir, POSITION_FIELD_PURGE_MARKER) }

    override fun localScopeId(): String? = preferences.getString(PREF_POSITION_FIELD_LOCAL_SCOPE_ID, null)
    override fun purgePending(): Boolean = preferences.getBoolean(PREF_POSITION_FIELD_PURGE_PENDING, false)
    override fun purgeMarkerExists(): Boolean = purgeMarker.exists()

    override fun persistPurgeMarker(): Boolean = runCatching {
        FileOutputStream(purgeMarker, false).use { output ->
            output.write(1)
            output.fd.sync()
        }
        true
    }.getOrDefault(false)

    override fun storePurgePending(pending: Boolean): Boolean = preferences.edit()
        .putBoolean(PREF_POSITION_FIELD_PURGE_PENDING, pending)
        .commit()

    override fun storeScope(scope: String, clearPurgePending: Boolean): Boolean {
        val editor = preferences.edit().putString(PREF_POSITION_FIELD_LOCAL_SCOPE_ID, scope)
        if (clearPurgePending) editor.putBoolean(PREF_POSITION_FIELD_PURGE_PENDING, false)
        return editor.commit()
    }

    override fun clearPurgeMarker(): Boolean = !purgeMarker.exists() || purgeMarker.delete()

    private companion object {
        const val POSITION_FIELD_PREFERENCES = "position_field_recorder_scope_v1"
        const val PREF_POSITION_FIELD_LOCAL_SCOPE_ID = "local_account_scope_uuid"
        const val PREF_POSITION_FIELD_PURGE_PENDING = "position_field_purge_pending"
        const val POSITION_FIELD_PURGE_MARKER = "position_field_purge_pending_v1"
    }
}

internal fun createAndroidPositionFieldRecorderStore(noBackupFilesDir: File): PositionFieldRecorderStore =
    PositionFieldSessionRecorderStore(
        PositionFieldSessionRecorder(
            noBackupRootDirectory = noBackupFilesDir,
            aead = AndroidKeyStoreAead(
                AeadKeyPolicy(
                    aliasPrefix = "walksafe.position-field-session.aead.v",
                    currentVersion = 1,
                    readableVersions = setOf(1),
                ),
            ),
            elapsedRealtimeNsClock = SystemClock::elapsedRealtimeNanos,
        ),
    )

internal class AndroidPositionFieldExportDestination(
    private val contentResolver: ContentResolver,
    private val destination: Uri,
) : PositionFieldExportDestination {
    override fun write(export: (OutputStream) -> PositionFieldExportResult?): PositionFieldExportResult? =
        contentResolver.openOutputStream(destination, "wt")?.use(export)

    override fun deleteOrTruncate(): Boolean {
        val deleted = runCatching {
            contentResolver.delete(destination, null, null) > 0
        }.getOrDefault(false)
        if (deleted) return true
        return runCatching {
            contentResolver.openOutputStream(destination, "wt")?.use { output -> output.flush() } != null
        }.getOrDefault(false)
    }
}
