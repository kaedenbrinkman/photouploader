package com.photopipeline.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photopipeline.app.api.NotSignedInException
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.data.CameraImportResult
import com.photopipeline.app.data.EstimateSource
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.data.Repository
import com.photopipeline.app.data.ShootEntity
import com.photopipeline.app.data.StoredEstimate
import com.photopipeline.app.mtp.MtpObjectInfo
import com.photopipeline.app.mtp.UsbAccessState
import com.photopipeline.app.mtp.UsbCameraManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ShootFlowStep {
    Connect,
    SelectFiles,
    Estimate,
    Upload,
    Processing,
    Results,
}

data class ShootUiState(
    val step: ShootFlowStep = ShootFlowStep.Connect,
    val busy: Boolean = false,
    val statusLine: String = "Connect your Canon camera via USB.",
    val snackbar: String? = null,
    val cameraObjects: List<MtpObjectInfo> = emptyList(),
    val selectedHandles: Set<Long> = emptySet(),
    val thumbnails: Map<Long, ByteArray> = emptyMap(),
    val processingProgress: String? = null,
    val estimateIsLocal: Boolean = false,
    val backendReady: Boolean = false,
    val backendGateMessage: String = "",
)

class ShootViewModel(
    private val repo: Repository,
    private val usb: UsbCameraManager,
    private val appContext: Context,
    private val shootId: String,
) : ViewModel() {
    private val importDir: File
        get() = File(appContext.filesDir, "imports/$shootId")

    private val _ui = MutableStateFlow(ShootUiState())
    val ui: StateFlow<ShootUiState> = _ui.asStateFlow()

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        PipelineLog.e("ShootViewModel", "Unhandled coroutine failure", throwable)
        _ui.value = _ui.value.copy(
            busy = false,
            snackbar = throwable.message ?: "Unexpected error",
        )
    }

    private val safeScope get() = viewModelScope + coroutineErrorHandler

    init {
        refreshBackendGate()
        safeScope.launch {
            if (repo.getShoot(shootId) == null) {
                repo.db.shootDao().upsert(
                    ShootEntity(
                        shootId = shootId,
                        albumTitle = "Shoot ${shootId.take(8)}",
                        state = "LOCAL",
                        phase = "CONNECT",
                        createdAt = System.currentTimeMillis(),
                        imageCount = 0,
                        totalBytes = 0,
                        estimateJson = null,
                        backendState = null,
                        albumId = null,
                        actualComputeJson = null,
                    ),
                )
            }
            recomputeStep()
        }
    }

    val shoot: StateFlow<ShootEntity?> =
        repo.observeShoot(shootId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val files: StateFlow<List<FileEntity>> =
        repo.observeFiles(shootId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var estimate: StoredEstimate? = null
        private set

    private var pollJob: Job? = null

    fun refreshBackendGate() {
        _ui.value = _ui.value.copy(
            backendReady = repo.canUseBackendApi(),
            backendGateMessage = repo.backendGateMessage(),
        )
    }

    fun clearSnackbar() {
        _ui.value = _ui.value.copy(snackbar = null)
    }

    fun refreshUsbProbe() {
        safeScope.launch {
            PipelineLog.d("ui", "Refresh USB scan tapped")
            usb.refreshProbe(log = true)
            val msg = usbStatusMessage(usb.accessState.value)
            _ui.value = _ui.value.copy(statusLine = msg)
        }
    }

    /** Primary detect/connect entry (logcat tag PhotoPipeline). */
    fun connectAndListCamera() {
        safeScope.launch {
            PipelineLog.d("ui", "Connect & detect camera tapped shootId=$shootId")
            _ui.value = _ui.value.copy(busy = true, statusLine = "Detecting USB devices…", snackbar = null)
            when (val result = repo.listCameraMedia()) {
                is CameraImportResult.Listed -> {
                    val handles = result.objects.map { it.handle }.toSet()
                    _ui.value = _ui.value.copy(
                        busy = false,
                        cameraObjects = result.objects,
                        selectedHandles = handles,
                        statusLine = result.statusMessage,
                        step = ShootFlowStep.SelectFiles,
                    )
                    loadThumbnails(result.objects)
                }
                is CameraImportResult.Error -> {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        statusLine = result.userMessage,
                        snackbar = "${result.stage}: ${result.userMessage}",
                    )
                }
                else -> _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    private fun loadThumbnails(objects: List<MtpObjectInfo>) {
        safeScope.launch {
            val map = mutableMapOf<Long, ByteArray>()
            for (obj in objects.take(40)) {
                runCatching {
                    repo.mtp.getThumbnail(obj)?.let { map[obj.handle] = it }
                }.onFailure { PipelineLog.w("mtp", "thumbnail ${obj.filename} failed", it) }
            }
            _ui.value = _ui.value.copy(thumbnails = map)
        }
    }

    fun toggleSelection(handle: Long) {
        val cur = _ui.value.selectedHandles
        _ui.value = _ui.value.copy(
            selectedHandles = if (handle in cur) cur - handle else cur + handle,
        )
    }

    fun importSelected() {
        safeScope.launch {
            val selected = _ui.value.cameraObjects.filter { it.handle in _ui.value.selectedHandles }
            if (selected.isEmpty()) {
                _ui.value = _ui.value.copy(snackbar = "Select at least one file to import.")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true, statusLine = "Importing ${selected.size} file(s)…")
            when (val result = repo.importFromCamera(shootId, importDir, selected)) {
                is CameraImportResult.Imported -> {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        statusLine = result.statusMessage,
                        step = ShootFlowStep.Estimate,
                    )
                    recomputeStep()
                }
                is CameraImportResult.Error -> {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        statusLine = result.userMessage,
                        snackbar = result.userMessage,
                    )
                }
                else -> _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    fun loadEstimate() {
        safeScope.launch {
            PipelineLog.d("ui", "Get estimate tapped")
            _ui.value = _ui.value.copy(busy = true, snackbar = null)
            runCatching { repo.fetchAndStoreEstimate(shootId) }
                .onSuccess { stored ->
                    estimate = stored
                    val local = stored.source == EstimateSource.LOCAL
                    _ui.value = _ui.value.copy(
                        busy = false,
                        estimateIsLocal = local,
                        step = ShootFlowStep.Upload,
                        statusLine = if (local) {
                            "On-device estimate from pricing.json — configure backend in Settings to upload."
                        } else {
                            "Estimate ready — review cost before uploading."
                        },
                        snackbar = if (local && !repo.canUseBackendApi()) repo.backendGateMessage() else null,
                    )
                    refreshBackendGate()
                }
                .onFailure { e ->
                    PipelineLog.e("estimate", "Estimate failed", e)
                    _ui.value = _ui.value.copy(
                        busy = false,
                        snackbar = "Estimate failed: ${e.message}",
                    )
                }
        }
    }

    fun startUpload() {
        refreshBackendGate()
        if (!repo.canUseBackendApi()) {
            _ui.value = _ui.value.copy(snackbar = repo.backendGateMessage())
            return
        }
        repo.enqueueUploads(shootId)
        _ui.value = _ui.value.copy(step = ShootFlowStep.Processing, statusLine = "Uploading to S3…")
        startPolling()
    }

    fun submitShoot() {
        refreshBackendGate()
        if (!repo.canUseBackendApi()) {
            _ui.value = _ui.value.copy(snackbar = repo.backendGateMessage())
            return
        }
        safeScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            runCatching { repo.submitShoot(shootId) }
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        statusLine = "Submitted for processing on LightCraft worker.",
                        step = ShootFlowStep.Processing,
                    )
                    startPolling()
                }
                .onFailure { e ->
                    val msg = when (e) {
                        is NotSignedInException -> repo.backendGateMessage()
                        else -> e.message ?: "Submit failed"
                    }
                    _ui.value = _ui.value.copy(busy = false, snackbar = msg)
                }
        }
    }

    fun retryFailedUploads() {
        if (!repo.canUseBackendApi()) {
            _ui.value = _ui.value.copy(snackbar = repo.backendGateMessage())
            return
        }
        safeScope.launch {
            files.value.filter { it.uploadState == "FAILED" }.forEach { repo.retryFile(it.identityKey) }
            repo.enqueueUploads(shootId)
        }
    }

    fun startPolling() {
        pollJob?.cancel()
        pollJob = safeScope.launch {
            while (isActive) {
                runCatching { repo.refreshBackendStatus(shootId) }
                    .onFailure { PipelineLog.w("status", "poll failed", it) }
                val progress = repo.processingProgressJson(shootId)
                _ui.value = _ui.value.copy(processingProgress = progress)
                recomputeStep()
                val state = shoot.value?.state
                if (state in TERMINAL) break
                delay(10_000)
            }
        }
    }

    private fun recomputeStep() {
        val s = shoot.value ?: return
        val fileList = files.value
        val step = when {
            s.state in TERMINAL || s.albumId != null -> ShootFlowStep.Results
            s.state !in setOf("LOCAL", "IMPORTING") && s.backendState != null -> ShootFlowStep.Processing
            fileList.any { it.uploadState == "UPLOADING" || it.uploadState == "UPLOADED" } &&
                fileList.any { it.uploadState != "UPLOADED" } -> ShootFlowStep.Upload
            s.estimateJson != null && fileList.isNotEmpty() -> ShootFlowStep.Upload
            fileList.isNotEmpty() -> ShootFlowStep.Estimate
            _ui.value.cameraObjects.isNotEmpty() -> ShootFlowStep.SelectFiles
            else -> ShootFlowStep.Connect
        }
        if (_ui.value.step != step) {
            _ui.value = _ui.value.copy(step = step)
        }
    }

    companion object {
        private val TERMINAL = setOf("DONE", "FAILED", "PARTIAL")

        fun factory(
            repo: Repository,
            usb: UsbCameraManager,
            context: Context,
            shootId: String,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShootViewModel(repo, usb, context.applicationContext, shootId) as T
        }
    }
}

fun newShootId(): String = UUID.randomUUID().toString()

fun usbStatusMessage(state: UsbAccessState): String = when (state) {
    UsbAccessState.NO_DEVICE -> "No Canon camera detected on USB."
    UsbAccessState.PERMISSION_REQUIRED -> "Camera detected — USB permission required."
    UsbAccessState.PERMISSION_DENIED -> "USB permission denied for this camera."
    UsbAccessState.OPEN_FAILED -> "Could not claim the MTP USB interface."
    UsbAccessState.READY -> "Camera ready for MTP."
}

fun humanShootState(shoot: ShootEntity?): String {
    if (shoot == null) return "Unknown"
    return when (shoot.state) {
        "LOCAL", "IMPORTING" -> "On device"
        "UPLOADING" -> "Uploading"
        "PROCESSING" -> "Processing on server"
        "DONE" -> "Complete"
        "FAILED" -> "Failed"
        "PARTIAL" -> "Partially complete"
        else -> shoot.state.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
    }
}

fun parseProgress(json: String?): Triple<Int, Int, Int>? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        Triple(
            o.optInt("renderedCount"),
            o.optInt("totalFiles"),
            o.optInt("publishedCount"),
        )
    }.getOrNull()
}
