package com.photopipeline.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photopipeline.app.api.ApiRequestException
import com.photopipeline.app.api.FinalizeErrorMessages
import com.photopipeline.app.api.NotSignedInException
import com.photopipeline.app.core.CaptureTimeNormalizer
import com.photopipeline.app.core.ExifCaptureTime
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.data.CameraImportResult
import com.photopipeline.app.data.EstimateSource
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.data.PrepareCameraResult
import com.photopipeline.app.data.Repository
import com.photopipeline.app.data.ShootEntity
import com.photopipeline.app.data.StoredEstimate
import com.photopipeline.app.mtp.MtpObjectInfo
import com.photopipeline.app.mtp.UsbAccessState
import com.photopipeline.app.mtp.UsbCameraManager
import com.photopipeline.app.upload.UploadMetricsStore
import com.photopipeline.app.upload.UploadWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

enum class ShootFlowStep {
    PickFiles,
    Upload,
    Processing,
    Results,
}

enum class UploadStartState {
    Idle,
    Starting,
    Uploading,
    Failed,
}

enum class FinalizeState {
    Idle,
    InProgress,
    Failed,
}

data class ShootUiState(
    val step: ShootFlowStep = ShootFlowStep.PickFiles,
    val busy: Boolean = false,
    val statusLine: String = "Plug in your Canon camera — we will list files automatically.",
    val snackbar: String? = null,
    val cameraObjects: List<MtpObjectInfo> = emptyList(),
    val selectedHandles: Set<Long> = emptySet(),
    val thumbnails: Map<Long, ByteArray> = emptyMap(),
    val processingProgress: String? = null,
    val estimateIsLocal: Boolean = false,
    val backendReady: Boolean = false,
    val backendGateMessage: String = "",
    /** True when this shoot already has files queued (resume path). */
    val isResumeShoot: Boolean = false,
    val uploadStartState: UploadStartState = UploadStartState.Idle,
    val uploadFailedReason: String? = null,
    val autoConnectInFlight: Boolean = false,
    val finalizeState: FinalizeState = FinalizeState.Idle,
    val finalizeError: String? = null,
    val serverProcessingError: String? = null,
    val serverPhase: String? = null,
    val serverLaunchErrorCode: String? = null,
    val serverLastError: String? = null,
    /** False until shoot/files are read — avoids PickFiles grid flash on resume/processing. */
    val flowReady: Boolean = false,
    val uploadedElsewhereHandles: Set<Long> = emptySet(),
    val serverFileStatusBySha: Map<String, String> = emptyMap(),
    val processingStartedAtMs: Long? = null,
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
                        albumTitle = "Import ${shootId.take(8)}",
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
            val loadedShoot = repo.getShoot(shootId)
            val fileList = repo.observeFiles(shootId).first()
            val resume = fileList.isNotEmpty()
            val initialStep = computeFlowStep(loadedShoot, fileList)
            val initialUploadState = queryUploadStartState()
            val uploadActive = isUploadWorkerActive(initialUploadState)
            _ui.value = _ui.value.copy(
                flowReady = true,
                step = initialStep,
                isResumeShoot = resume,
                uploadStartState = initialUploadState,
                processingProgress = repo.processingProgressJson(shootId),
                serverFileStatusBySha = loadServerFileStatusMap(),
                processingStartedAtMs = repo.processingWorkerStartedAtMs(shootId)
                    ?: if (initialStep == ShootFlowStep.Processing) {
                        System.currentTimeMillis()
                    } else {
                        null
                    },
                statusLine = uploadStatusLine(
                    resume = resume,
                    step = initialStep,
                    uploadActive = uploadActive,
                    usbReady = usb.accessState.value == UsbAccessState.READY,
                ),
            )
            observeUploadWork()
            refreshFinalizeError()
            if (loadedShoot != null &&
                (loadedShoot.backendState != null || loadedShoot.state !in LOCAL_ONLY_STATES || loadedShoot.albumId != null)
            ) {
                startPolling()
            }
            if (fileList.isNotEmpty() && fileList.all { it.uploadState == "UPLOADED" }) {
                startPolling()
            }
            if (resume && initialStep == ShootFlowStep.Upload) {
                tryAutoResumeUpload()
            } else if (initialStep == ShootFlowStep.PickFiles) {
                autoConnectAndList()
            }
        }
        safeScope.launch {
            combine(repo.observeShoot(shootId), repo.observeFiles(shootId)) { s, f -> s to f }
                .collect { (s, f) -> recomputeStep(s, f) }
        }
    }

    val shoot: StateFlow<ShootEntity?> =
        repo.observeShoot(shootId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val files: StateFlow<List<FileEntity>> =
        repo.observeFiles(shootId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var estimate: StoredEstimate? = null
        private set

    private var pollJob: Job? = null
    private var workObserveJob: Job? = null
    private var autoResumeAttempted = false
    private var autoFinalizeScheduled = false
    private var finalizeInFlight = false
    /** Ignore status polls whose META.updatedAt is older than this retry (optimistic retry UI). */
    private var processingRetryStartedAtMs: Long? = null

    fun refreshBackendGate() {
        _ui.value = _ui.value.copy(
            backendReady = repo.canUseBackendApi(),
            backendGateMessage = repo.backendGateMessage(),
        )
    }

    fun clearSnackbar() {
        _ui.value = _ui.value.copy(snackbar = null)
    }

    fun onUsbAccessChanged(state: UsbAccessState) {
        when {
            _ui.value.isResumeShoot && _ui.value.step == ShootFlowStep.Upload -> tryAutoResumeUpload()
            !_ui.value.isResumeShoot && _ui.value.step == ShootFlowStep.PickFiles -> autoConnectAndList()
            else -> Unit
        }
        if (state == UsbAccessState.READY && _ui.value.step == ShootFlowStep.Upload) {
            ensureCameraForUpload()
        }
    }

    fun autoConnectAndList() {
        if (_ui.value.isResumeShoot || _ui.value.step != ShootFlowStep.PickFiles) return
        if (_ui.value.autoConnectInFlight || _ui.value.busy) return
        safeScope.launch {
            _ui.value = _ui.value.copy(autoConnectInFlight = true)
            usb.refreshProbe(log = true)
            when (usb.accessState.value) {
                UsbAccessState.PERMISSION_REQUIRED -> {
                    _ui.value = _ui.value.copy(
                        statusLine = "Allow USB access when Android prompts you…",
                    )
                    if (usb.requestPermissionSuspend()) {
                        connectAndListCameraInternal()
                    } else {
                        _ui.value = _ui.value.copy(
                            autoConnectInFlight = false,
                            statusLine = "USB permission denied — reconnect the cable and allow access.",
                        )
                    }
                }
                UsbAccessState.READY -> connectAndListCameraInternal()
                UsbAccessState.NO_DEVICE -> {
                    _ui.value = _ui.value.copy(
                        autoConnectInFlight = false,
                        statusLine = "Waiting for camera USB…",
                    )
                }
                else -> {
                    _ui.value = _ui.value.copy(
                        autoConnectInFlight = false,
                        statusLine = usbStatusMessage(usb.accessState.value),
                    )
                }
            }
        }
    }

    fun connectAndResumeUpload() {
        safeScope.launch {
            _ui.value = _ui.value.copy(busy = true, statusLine = "Connecting camera and rematching files…")
            usb.refreshProbe(log = true)
            if (usb.accessState.value == UsbAccessState.PERMISSION_REQUIRED) {
                if (!usb.requestPermissionSuspend()) {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        statusLine = "USB permission required to resume.",
                        snackbar = "USB permission denied",
                    )
                    return@launch
                }
            }
            when (val prep = repo.prepareCameraForUpload(shootId)) {
                is PrepareCameraResult.Error -> {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        statusLine = prep.message,
                        snackbar = prep.message,
                    )
                }
                PrepareCameraResult.Ready -> {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        statusLine = "Camera matched — starting upload…",
                    )
                    startUpload()
                }
            }
        }
    }

    private suspend fun connectAndListCameraInternal() {
        PipelineLog.d("ui", "Auto/list camera shootId=$shootId")
        _ui.value = _ui.value.copy(busy = true, statusLine = "Reading files from camera…")
        when (val result = repo.listCameraMedia()) {
            is CameraImportResult.Listed -> {
                refreshUploadedElsewhereFlags(result.objects)
                val blocked = _ui.value.uploadedElsewhereHandles
                val selectable = result.objects.filter { it.handle !in blocked }.map { it.handle }.toSet()
                _ui.value = _ui.value.copy(
                    busy = false,
                    autoConnectInFlight = false,
                    cameraObjects = result.objects,
                    selectedHandles = selectable,
                    statusLine = result.statusMessage,
                    step = ShootFlowStep.PickFiles,
                )
                loadThumbnails(result.objects)
            }
            is CameraImportResult.Error -> {
                _ui.value = _ui.value.copy(
                    busy = false,
                    autoConnectInFlight = false,
                    statusLine = result.userMessage,
                    snackbar = result.userMessage,
                )
            }
            else -> _ui.value = _ui.value.copy(busy = false, autoConnectInFlight = false)
        }
    }

    private fun loadThumbnails(objects: List<MtpObjectInfo>) {
        safeScope.launch {
            val map = mutableMapOf<Long, ByteArray>()
            var updatedObjects = objects
            for (obj in objects.take(80)) {
                runCatching {
                    repo.mtp.getThumbnail(obj)?.let { bytes ->
                        map[obj.handle] = bytes
                        persistThumbnail(obj.handle, bytes)
                        ExifCaptureTime.epochMsFromThumbnailJpeg(bytes)?.let { exifMs ->
                            val merged = CaptureTimeNormalizer.bestCaptureEpochMs(exifMs, obj.captureTimeEpochMs)
                            if (merged > 0L && merged != obj.captureTimeEpochMs) {
                                updatedObjects = updatedObjects.map {
                                    if (it.handle == obj.handle) it.copy(captureTimeEpochMs = merged) else it
                                }
                            }
                        }
                    }
                }.onFailure { PipelineLog.w("mtp", "thumbnail ${obj.filename} failed", it) }
            }
            _ui.value = _ui.value.copy(thumbnails = map, cameraObjects = updatedObjects)
        }
    }

    private fun persistThumbnail(handle: Long, bytes: ByteArray) {
        val file = thumbCacheFile(handle)
        file.parentFile?.mkdirs()
        runCatching { file.writeBytes(bytes) }
    }

    fun readCachedThumbnail(handle: Long): ByteArray? {
        _ui.value.thumbnails[handle]?.let { return it }
        val file = thumbCacheFile(handle)
        if (file.isFile) {
            runCatching { file.readBytes() }.getOrNull()?.let { return it }
        }
        files.value.firstOrNull { it.objectHandle == handle }?.localPath?.let { path ->
            val local = File(path)
            if (local.isFile && local.length() < 512_000) {
                runCatching { local.readBytes() }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun thumbCacheFile(handle: Long): File =
        File(appContext.filesDir, "thumb-cache/$shootId/$handle.jpg")

    private suspend fun refreshUploadedElsewhereFlags(objects: List<MtpObjectInfo>) {
        val blocked = mutableSetOf<Long>()
        for (obj in objects) {
            if (repo.isCameraObjectUploadedElsewhere(shootId, obj)) {
                blocked += obj.handle
            }
        }
        _ui.value = _ui.value.copy(uploadedElsewhereHandles = blocked)
    }

    fun toggleSelection(handle: Long) {
        if (handle in _ui.value.uploadedElsewhereHandles) return
        val cur = _ui.value.selectedHandles
        _ui.value = _ui.value.copy(
            selectedHandles = if (handle in cur) cur - handle else cur + handle,
        )
    }

    fun deselectAll() {
        _ui.value = _ui.value.copy(selectedHandles = emptySet())
    }

    fun selectAllSelectable() {
        val selectable = _ui.value.cameraObjects
            .filter { it.handle !in _ui.value.uploadedElsewhereHandles }
            .map { it.handle }
            .toSet()
        _ui.value = _ui.value.copy(selectedHandles = selectable)
    }

    fun selectAllForDate(dateKey: String) {
        val selectable = _ui.value.cameraObjects
            .filter { it.handle !in _ui.value.uploadedElsewhereHandles }
            .filter { captureDateKey(it.captureTimeEpochMs) == dateKey }
            .map { it.handle }
            .toSet()
        _ui.value = _ui.value.copy(selectedHandles = _ui.value.selectedHandles + selectable)
    }

    fun deselectAllForDate(dateKey: String) {
        val onDate = _ui.value.cameraObjects
            .filter { captureDateKey(it.captureTimeEpochMs) == dateKey }
            .map { it.handle }
            .toSet()
        _ui.value = _ui.value.copy(selectedHandles = _ui.value.selectedHandles - onDate)
    }

    fun selectedCountAndBytes(): Pair<Int, Long> {
        val selected = _ui.value.cameraObjects.filter { it.handle in _ui.value.selectedHandles }
        return selected.size to selected.sumOf { it.sizeBytes }
    }

    private fun loadServerFileStatusMap(): Map<String, String> =
        repo.serverFileStatuses(shootId)
            .filter { it.sha256.isNotBlank() }
            .associate { it.sha256.lowercase() to it.state }

    fun importSelected() {
        safeScope.launch {
            val selected = _ui.value.cameraObjects.filter { it.handle in _ui.value.selectedHandles }
            if (selected.isEmpty()) {
                _ui.value = _ui.value.copy(snackbar = "Select at least one file.")
                return@launch
            }
            _ui.value = _ui.value.copy(
                busy = true,
                statusLine = "Queuing ${selected.size} file(s)…",
            )
            when (
                val result = repo.registerSelectedCameraFiles(
                    shootId,
                    selected,
                    localCopy = false,
                )
            ) {
                is CameraImportResult.Imported -> {
                    runCatching { repo.fetchAndStoreEstimate(shootId) }
                        .onSuccess { estimate = it }
                    _ui.value = _ui.value.copy(
                        busy = false,
                        step = ShootFlowStep.Upload,
                        statusLine = "Files queued — starting upload…",
                    )
                    recomputeStep(shoot.value, files.value)
                    observeUploadWork()
                    if (repo.canUseBackendApi()) {
                        startUpload()
                    } else {
                        _ui.value = _ui.value.copy(
                            statusLine = "Sign in with Google to upload.",
                            snackbar = repo.backendGateMessage(),
                        )
                    }
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

    fun ensureCameraForUpload() {
        safeScope.launch {
            if (files.value.none { it.uploadState != "UPLOADED" }) return@launch
            when (val prep = repo.prepareCameraForUpload(shootId)) {
                is PrepareCameraResult.Error -> {
                    _ui.value = _ui.value.copy(statusLine = prep.message)
                }
                PrepareCameraResult.Ready -> {
                    if (_ui.value.isResumeShoot) {
                        _ui.value = _ui.value.copy(
                            statusLine = "Camera ready — ${files.value.count { it.uploadState != "UPLOADED" }} file(s) pending upload.",
                        )
                    }
                }
            }
        }
    }

    private fun tryAutoResumeUpload() {
        if (autoResumeAttempted) return
        if (!_ui.value.isResumeShoot) return
        if (_ui.value.uploadStartState == UploadStartState.Starting ||
            _ui.value.uploadStartState == UploadStartState.Uploading
        ) {
            return
        }
        if (files.value.none { it.uploadState != "UPLOADED" }) return
        if (usb.accessState.value != UsbAccessState.READY) return
        if (!repo.canUseBackendApi()) return
        autoResumeAttempted = true
        safeScope.launch {
            when (repo.prepareCameraForUpload(shootId)) {
                is PrepareCameraResult.Error -> autoResumeAttempted = false
                PrepareCameraResult.Ready -> startUpload()
            }
        }
    }

    fun startUpload() {
        refreshBackendGate()
        if (!repo.canUseBackendApi()) {
            _ui.value = _ui.value.copy(snackbar = repo.backendGateMessage())
            return
        }
        if (_ui.value.uploadStartState == UploadStartState.Starting ||
            _ui.value.uploadStartState == UploadStartState.Uploading
        ) {
            return
        }
        safeScope.launch {
            UploadMetricsStore.clear()
            _ui.value = _ui.value.copy(
                busy = true,
                uploadStartState = UploadStartState.Starting,
                uploadFailedReason = null,
                statusLine = "Starting upload…",
            )
            runCatching { repo.enqueueUploads(shootId) }
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        busy = false,
                        step = ShootFlowStep.Upload,
                        uploadStartState = UploadStartState.Uploading,
                        statusLine = "Uploading to S3 — keep USB connected.",
                    )
                }
                .onFailure { e ->
                    val msg = e.message ?: "Upload could not start"
                    PipelineLog.e("upload", "enqueue failed: $msg", e)
                    _ui.value = _ui.value.copy(
                        busy = false,
                        step = ShootFlowStep.Upload,
                        uploadStartState = UploadStartState.Failed,
                        uploadFailedReason = msg,
                        statusLine = "Failed: $msg",
                        snackbar = msg,
                    )
                }
        }
    }

    fun retryProcessing() {
        refreshBackendGate()
        if (!repo.canUseBackendApi()) {
            _ui.value = _ui.value.copy(snackbar = repo.backendGateMessage())
            return
        }
        val retryAt = System.currentTimeMillis()
        processingRetryStartedAtMs = retryAt
        repo.clearFinalizeError(shootId)
        _ui.value = _ui.value.copy(
            busy = true,
            step = ShootFlowStep.Processing,
            finalizeState = FinalizeState.InProgress,
            finalizeError = null,
            serverProcessingError = null,
            serverLastError = null,
            serverLaunchErrorCode = null,
            serverPhase = null,
            statusLine = "Starting server processing…",
        )
        safeScope.launch { runFinalizeForProcessing(userInitiated = true) }
    }

    private fun refreshFinalizeError() {
        _ui.value = _ui.value.copy(
            finalizeError = repo.getFinalizeError(shootId),
            finalizeState = if (repo.getFinalizeError(shootId) != null) {
                FinalizeState.Failed
            } else {
                _ui.value.finalizeState
            },
        )
    }

    private fun tryAutoFinalizeIfReady() {
        if (finalizeInFlight) return
        if (!repo.canUseBackendApi()) return
        val fileList = files.value
        if (fileList.isEmpty() || fileList.any { it.uploadState != "UPLOADED" }) return
        if (fileList.any { it.uploadState == "FAILED" }) return
        val s = shoot.value ?: return
        if (s.state in TERMINAL && s.state != "FAILED") return
        if (s.albumId != null) return
        if (s.backendState != null && s.state !in LOCAL_ONLY_STATES && s.state != "FAILED") return
        if (repo.getFinalizeError(shootId) != null) {
            refreshFinalizeError()
            return
        }
        if (_ui.value.uploadStartState == UploadStartState.Uploading ||
            _ui.value.uploadStartState == UploadStartState.Starting
        ) {
            return
        }
        if (autoFinalizeScheduled) return
        autoFinalizeScheduled = true
        safeScope.launch { runFinalizeForProcessing(userInitiated = false) }
    }

    private suspend fun runFinalizeForProcessing(userInitiated: Boolean) {
        if (finalizeInFlight) return
        finalizeInFlight = true
        _ui.value = _ui.value.copy(
            busy = true,
            finalizeState = FinalizeState.InProgress,
            finalizeError = null,
            statusLine = "Starting server processing…",
        )
        runCatching { repo.finalizeShootForProcessing(shootId) }
            .onSuccess {
                finalizeInFlight = false
                autoFinalizeScheduled = false
                _ui.value = _ui.value.copy(
                    busy = false,
                    finalizeState = FinalizeState.Idle,
                    finalizeError = null,
                    statusLine = "Processing on LightCraft worker.",
                    step = ShootFlowStep.Processing,
                    processingStartedAtMs = System.currentTimeMillis(),
                )
                recomputeStep(shoot.value, files.value)
                startPolling()
            }
            .onFailure { e ->
                finalizeInFlight = false
                if (!userInitiated) autoFinalizeScheduled = false
                val msg = when (e) {
                    is NotSignedInException -> repo.backendGateMessage()
                    else -> repo.getFinalizeError(shootId) ?: e.message ?: "Processing could not start"
                }
                PipelineLog.e("finalize", "finalize failed shootId=$shootId: $msg", e)
                _ui.value = _ui.value.copy(
                    busy = false,
                    finalizeState = FinalizeState.Failed,
                    finalizeError = msg,
                    statusLine = "Processing failed: $msg",
                    snackbar = if (userInitiated) msg else null,
                )
            }
    }

    fun retryFailedUploads() {
        autoResumeAttempted = false
        if (!repo.canUseBackendApi()) {
            _ui.value = _ui.value.copy(snackbar = repo.backendGateMessage())
            return
        }
        safeScope.launch {
            files.value.filter { it.uploadState == "FAILED" }.forEach { repo.retryFile(it.identityKey) }
            startUpload()
        }
    }

    fun cancelUpload() {
        safeScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            runCatching { repo.cancelUploadForShoot(shootId) }
                .onSuccess {
                    autoResumeAttempted = false
                    _ui.value = _ui.value.copy(
                        busy = false,
                        uploadStartState = UploadStartState.Idle,
                        uploadFailedReason = null,
                        statusLine = "Upload cancelled.",
                    )
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(
                        busy = false,
                        snackbar = e.message ?: "Cancel failed",
                    )
                }
        }
    }

    fun deleteShoot(onDeleted: () -> Unit) {
        safeScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            runCatching { repo.deleteShootCompletely(shootId) }
                .onSuccess {
                    _ui.value = _ui.value.copy(busy = false)
                    onDeleted()
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(
                        busy = false,
                        snackbar = e.message ?: "Delete failed",
                    )
                }
        }
    }

    private suspend fun queryUploadStartState(): UploadStartState {
        val info = WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(repo.uploadWorkName(shootId))
            .first()
            .firstOrNull()
        return when (info?.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            -> UploadStartState.Uploading
            WorkInfo.State.FAILED -> UploadStartState.Failed
            else -> UploadStartState.Idle
        }
    }

    private fun observeUploadWork() {
        workObserveJob?.cancel()
        workObserveJob = safeScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(repo.uploadWorkName(shootId))
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.RUNNING,
                        -> {
                            _ui.value = _ui.value.copy(
                                uploadStartState = UploadStartState.Uploading,
                                uploadFailedReason = null,
                                statusLine = "Uploading to S3 — keep USB connected.",
                            )
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            safeScope.launch {
                                runCatching { repo.reconcileLocalUploadsWithServer(shootId) }
                                val pending = files.value.any { it.uploadState != "UPLOADED" }
                                _ui.value = _ui.value.copy(
                                    uploadStartState = UploadStartState.Idle,
                                    statusLine = if (pending) {
                                        "Upload pass finished — tap to retry remaining files."
                                    } else {
                                        "All files uploaded — starting processing…"
                                    },
                                )
                                if (!pending) tryAutoFinalizeIfReady()
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val reason = info.outputData.getString(UploadWorker.KEY_ERROR)
                                ?: UploadMetricsStore.metrics.value.lastError
                                ?: UploadMetricsStore.metrics.value.pausedReason
                                ?: "Upload failed"
                            _ui.value = _ui.value.copy(
                                uploadStartState = UploadStartState.Failed,
                                uploadFailedReason = reason,
                                statusLine = "Failed: $reason",
                            )
                        }
                        WorkInfo.State.CANCELLED -> {
                            _ui.value = _ui.value.copy(uploadStartState = UploadStartState.Idle)
                        }
                        else -> Unit
                    }
                }
        }
    }

    fun startPolling() {
        if (!shouldPollBackend()) return
        pollJob?.cancel()
        pollJob = safeScope.launch {
            var pollIntervalMs = 10_000L
            while (isActive) {
                if (!shouldPollBackend()) break
                var stopPolling = false
                var transientFailure = false
                var statusApplied = false
                val retryGate = processingRetryStartedAtMs
                runCatching { repo.refreshBackendStatus(shootId, acceptStatusAfterMs = retryGate) }
                    .onSuccess { status ->
                        pollIntervalMs = 10_000L
                        status?.let {
                            val updatedAt = it.updatedAt ?: 0L
                            val stale = retryGate != null && updatedAt < retryGate!!
                            if (!stale && retryGate != null) {
                                processingRetryStartedAtMs = null
                            }
                            if (stale) return@onSuccess
                            statusApplied = true
                            val friendly = FinalizeErrorMessages.friendly(it.launchErrorCode, it.lastError)
                            val workerStarted = it.workerStartedAt ?: repo.processingWorkerStartedAtMs(shootId)
                            _ui.value = _ui.value.copy(
                                serverPhase = it.phase,
                                serverLaunchErrorCode = it.launchErrorCode,
                                serverLastError = it.lastError,
                                processingStartedAtMs = workerStarted ?: _ui.value.processingStartedAtMs,
                                serverProcessingError = when {
                                    it.state == "READY_TO_PROCESS" || it.state == "FAILED" -> friendly
                                    it.state == "PROCESSING" && !it.lastError.isNullOrBlank() -> friendly
                                    !it.lastError.isNullOrBlank() -> friendly
                                    else -> null
                                },
                                processingProgress = repo.processingProgressJson(shootId),
                                serverFileStatusBySha = loadServerFileStatusMap(),
                            )
                        }
                    }
                    .onFailure { e ->
                        if (isTransientPollError(e)) {
                            transientFailure = true
                            PipelineLog.d(
                                "status",
                                "poll transient network error, retry in ${pollIntervalMs}ms: ${e.message}",
                            )
                            pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(120_000L)
                        } else {
                            val detail = when (e) {
                                is ApiRequestException ->
                                    "poll failed HTTP ${e.httpCode ?: "?"}: ${e.message}"
                                else -> "poll failed: ${e.message}"
                            }
                            PipelineLog.w("status", detail, e)
                            if (e is ApiRequestException && e.httpCode == 404) stopPolling = true
                        }
                    }
                if (stopPolling) break
                if (!transientFailure && statusApplied) {
                    recomputeStep(shoot.value, files.value)
                    tryAutoFinalizeIfReady()
                    val state = shoot.value?.state
                    if (state in TERMINAL) break
                } else if (!transientFailure && processingRetryStartedAtMs != null) {
                    // Keep optimistic Processing step until a post-retry status lands.
                    if (_ui.value.step != ShootFlowStep.Processing) {
                        _ui.value = _ui.value.copy(step = ShootFlowStep.Processing)
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun isTransientPollError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                -> return true
            }
            if (t is IOException && t.message?.contains("Unable to resolve host", ignoreCase = true) == true) {
                return true
            }
            t = t.cause
        }
        return false
    }

    private fun shouldPollBackend(): Boolean {
        if (!repo.canUseBackendApi()) return false
        val s = shoot.value
        if (s != null) {
            if (s.backendState != null || s.albumId != null) return true
            if (s.state !in LOCAL_ONLY_STATES) return true
        }
        if (files.value.isNotEmpty() && files.value.all { it.uploadState == "UPLOADED" }) return true
        if (repo.isServerShootRegistered(shootId) &&
            files.value.isNotEmpty() &&
            files.value.any { it.uploadState != "UPLOADED" }
        ) {
            return true
        }
        return repo.isServerShootRegistered(shootId) &&
            files.value.isNotEmpty() &&
            files.value.all { it.uploadState == "UPLOADED" }
    }

    fun needsProcessingRetry(): Boolean {
        if (_ui.value.finalizeState == FinalizeState.InProgress) return false
        val fileList = files.value
        if (fileList.isEmpty() || fileList.any { it.uploadState != "UPLOADED" }) return false
        if (_ui.value.finalizeState == FinalizeState.Failed || repo.getFinalizeError(shootId) != null) {
            return true
        }
        val s = shoot.value ?: return false
        if (s.state == "PARTIAL") return true
        if (s.state == "DONE") {
            parseProgress(repo.processingProgressJson(shootId))?.let { (_, total, published) ->
                if (published == 0 || (total > 0 && published < total)) return true
            }
        }
        if (s.state == "FAILED" || s.state == "READY_TO_PROCESS") return true
        if (!s.backendState.isNullOrBlank() && s.backendState in setOf("FAILED", "READY_TO_PROCESS")) {
            return true
        }
        if (s.state == "PROCESSING" && !_ui.value.serverLastError.isNullOrBlank()) return true
        if (s.state == "PROCESSING" && s.backendState == "READY_TO_PROCESS") return true
        // All bytes in S3 but server never left LOCAL (e.g. finalize 409 before backend fix).
        return s.state in LOCAL_ONLY_STATES &&
            s.phase.contains("MANIFEST", ignoreCase = true) &&
            _ui.value.uploadStartState != UploadStartState.Uploading &&
            _ui.value.uploadStartState != UploadStartState.Starting
    }

    fun allFilesUploaded(): Boolean =
        files.value.isNotEmpty() && files.value.all { it.uploadState == "UPLOADED" }

    private fun recomputeStep(shootEntity: ShootEntity?, fileList: List<FileEntity>) {
        val step = computeFlowStep(shootEntity, fileList)
        val startedAt = when {
            step == ShootFlowStep.Processing && _ui.value.processingStartedAtMs == null ->
                System.currentTimeMillis()
            step != ShootFlowStep.Processing -> null
            else -> _ui.value.processingStartedAtMs
        }
        if (_ui.value.step != step || _ui.value.processingStartedAtMs != startedAt) {
            _ui.value = _ui.value.copy(step = step, processingStartedAtMs = startedAt)
        }
    }

    companion object {
        private val TERMINAL = setOf("DONE", "FAILED", "PARTIAL")
        private val LOCAL_ONLY_STATES = setOf("LOCAL", "IMPORTING")

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
        "READY_TO_PROCESS" -> "Ready to process"
        "DONE" -> "Complete"
        "FAILED" -> "Failed"
        "PARTIAL" -> "Partially complete"
        else -> shoot.state.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
    }
}

fun isUploadWorkerActive(uploadStartState: UploadStartState): Boolean =
    uploadStartState == UploadStartState.Uploading ||
        uploadStartState == UploadStartState.Starting

fun uploadStatusLine(
    resume: Boolean,
    step: ShootFlowStep,
    uploadActive: Boolean,
    usbReady: Boolean,
): String = when {
    uploadActive -> "Uploading to S3 — keep USB connected."
    resume && step == ShootFlowStep.Upload && usbReady ->
        "Resume upload — use the button below when you are ready."
    resume -> "Resume upload — connect the camera when ready."
    else -> "Plug in your Canon camera — we will list files automatically."
}

fun capProgressCounts(rendered: Int, total: Int, published: Int): Triple<Int, Int, Int> {
    val safeTotal = total.coerceAtLeast(0)
    val cap = if (safeTotal > 0) safeTotal else Int.MAX_VALUE
    return Triple(
        minOf(rendered.coerceAtLeast(0), cap),
        safeTotal,
        minOf(published.coerceAtLeast(0), cap),
    )
}

fun parseProgress(json: String?): Triple<Int, Int, Int>? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        capProgressCounts(
            o.optInt("renderedCount"),
            o.optInt("totalFiles"),
            o.optInt("publishedCount"),
        )
    }.getOrNull()
}
