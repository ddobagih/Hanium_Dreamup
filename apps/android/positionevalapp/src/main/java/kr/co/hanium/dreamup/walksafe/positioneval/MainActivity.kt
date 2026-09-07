package kr.co.hanium.dreamup.walksafe.positioneval

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_TRACE = 10
        private const val REQUEST_GNSS = 11
        private const val REQUEST_EXPORT = 12
    }

    private lateinit var keyInput: EditText
    private lateinit var keyState: TextView
    private lateinit var traceState: TextView
    private lateinit var gnssState: TextView
    private lateinit var status: TextView
    private lateinit var saveKeyButton: Button
    private lateinit var selectTraceButton: Button
    private lateinit var selectGnssButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var exportButton: Button
    private lateinit var clearButton: Button
    private var lastAnnouncedStatus: String? = null
    private val observer: (AnalysisSnapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keyInput = findViewById(R.id.fileKeyInput)
        keyState = findViewById(R.id.keyState)
        traceState = findViewById(R.id.traceState)
        gnssState = findViewById(R.id.gnssState)
        status = findViewById(R.id.status)
        saveKeyButton = findViewById(R.id.saveKeyButton)
        selectTraceButton = findViewById(R.id.selectTraceButton)
        selectGnssButton = findViewById(R.id.selectGnssButton)
        analyzeButton = findViewById(R.id.analyzeButton)
        cancelButton = findViewById(R.id.cancelButton)
        exportButton = findViewById(R.id.exportButton)
        clearButton = findViewById(R.id.clearButton)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        AnalysisCoordinator.initialize(applicationContext)

        saveKeyButton.setOnClickListener { saveKey() }
        selectTraceButton.setOnClickListener { openDocument(REQUEST_TRACE) }
        selectGnssButton.setOnClickListener { openDocument(REQUEST_GNSS) }
        analyzeButton.setOnClickListener { AnalysisCoordinator.startAnalysis() }
        cancelButton.setOnClickListener { AnalysisCoordinator.cancelAnalysis() }
        exportButton.setOnClickListener { createResultDocument() }
        clearButton.setOnClickListener { AnalysisCoordinator.clearPrivateData() }
    }

    override fun onStart() {
        super.onStart()
        AnalysisCoordinator.attach(observer)
    }

    override fun onStop() {
        AnalysisCoordinator.detach(observer)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            AnalysisCoordinator.cancelAnalysis()
        }
        super.onDestroy()
    }

    private fun saveKey() {
        val editable = keyInput.text
        val value = CharArray(editable?.length ?: 0) { index -> editable[index] }
        editable?.clear()
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (value.isEmpty()) {
            renderStatus("입력 확인 필요\n국토지리정보원 다운로드 키를 입력해 주세요.")
            return
        }
        AnalysisCoordinator.saveKey(value)
    }

    private fun openDocument(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (requestCode == REQUEST_GNSS) "text/plain" else "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/x-ndjson", "text/plain", "*/*"))
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Activity result API requires an AndroidX dependency that this isolated module does not need.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_TRACE -> AnalysisCoordinator.importDocument(data.data!!, ArtifactKind.TRACE)
            REQUEST_GNSS -> AnalysisCoordinator.importDocument(data.data!!, ArtifactKind.GNSS)
            REQUEST_EXPORT -> AnalysisCoordinator.exportResult(data.data!!)
        }
    }

    private fun createResultDocument() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, AnalysisCoordinator.resultFileName())
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun render(snapshot: AnalysisSnapshot) {
        keyState.text = if (snapshot.keyStored) {
            "다운로드 키: 등록됨 (내용은 다시 표시하지 않습니다)"
        } else {
            getString(R.string.key_hint)
        }
        traceState.text = snapshot.traceSummary ?: getString(R.string.trace_empty)
        gnssState.text = snapshot.gnssSummary ?: getString(R.string.gnss_empty)
        saveKeyButton.isEnabled = !snapshot.busy && !snapshot.cancelling
        selectTraceButton.isEnabled = !snapshot.busy && !snapshot.cancelling
        selectGnssButton.isEnabled = !snapshot.busy && !snapshot.cancelling
        analyzeButton.isEnabled = snapshot.canAnalyze
        cancelButton.isEnabled = snapshot.analysisRunning
        exportButton.isEnabled = snapshot.hasResult && !snapshot.busy
        clearButton.isEnabled = !snapshot.busy && !snapshot.cancelling
        renderStatus(snapshot.status)
    }

    private fun renderStatus(message: String) {
        status.text = message
        if (message != lastAnnouncedStatus) {
            lastAnnouncedStatus = message
            status.announceForAccessibility(message)
        }
    }
}
