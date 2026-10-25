package kr.co.hanium.dreamup.walksafe.positioneval

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.positioneval.core.AnalysisCancellation
import kr.co.hanium.dreamup.walksafe.positioneval.core.Demo5JniPpkEngine
import kr.co.hanium.dreamup.walksafe.positioneval.core.ImportedArtifact
import kr.co.hanium.dreamup.walksafe.positioneval.core.NgiiFileKeyVault
import kr.co.hanium.dreamup.walksafe.positioneval.core.NgiiPortalClient
import kr.co.hanium.dreamup.walksafe.positioneval.core.NgiiStationCatalogCache
import kr.co.hanium.dreamup.walksafe.positioneval.core.PinnedAndroidRinexConverter
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkEngineException
import kr.co.hanium.dreamup.walksafe.positioneval.core.PrivateFileImporter

internal interface AnalysisStorage {
    val root: File
    fun isKeyStored(): Boolean
    fun saveKey(value: CharArray)
    fun clearKey()
    fun import(uri: Uri, maxBytes: Long, cancellation: AnalysisCancellation?): ImportedArtifact
    fun export(uri: Uri, json: String)
}

/** Android URI and key material stay at the application composition boundary. */
internal class AndroidAnalysisStorage(context: Context) : AnalysisStorage {
    private val context = context.applicationContext
    private val vault = NgiiFileKeyVault(this.context)
    override val root = File(this.context.noBackupFilesDir, "position_evaluator")

    override fun isKeyStored(): Boolean = vault.isStored()
    override fun saveKey(value: CharArray) = vault.save(value)
    override fun clearKey() = vault.clear()
    override fun import(uri: Uri, maxBytes: Long, cancellation: AnalysisCancellation?): ImportedArtifact =
        PrivateFileImporter(context).import(uri, maxBytes, cancellation)

    override fun export(uri: Uri, json: String) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: error("OUTPUT_UNAVAILABLE")
    }

    fun pipeline() = AnalysisPipeline(
        converter = PinnedAndroidRinexConverter(),
        engine = Demo5JniPpkEngine(),
        downloadedBase = AnalysisBaseProvider { inputs, directory, cancellation ->
            val key = runCatching { vault.load() }
                .getOrElse { throw PpkEngineException("KEY_NOT_AVAILABLE", "KEY_NOT_AVAILABLE") }
            try {
                NgiiPortalClient(
                    stationCatalogCache = NgiiStationCatalogCache(File(root, "cache/ngii-stations-v1.json")),
                    cancellation = cancellation,
                ).downloadNearestCompleteBase(inputs.traceSession, key, directory)
            } finally {
                key.fill('\u0000')
            }
        },
        workspaceFactory = { DirectoryAnalysisWorkspace(File(root, "work/${UUID.randomUUID()}")) },
    )
}
