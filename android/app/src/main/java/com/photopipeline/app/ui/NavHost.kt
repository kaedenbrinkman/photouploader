package com.photopipeline.app.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.data.Repository
import com.photopipeline.app.data.ShootEntity
import com.photopipeline.app.mtp.UsbAccessState
import com.photopipeline.app.mtp.UsbCameraManager
import com.photopipeline.app.ui.components.CameraConnectionCard
import com.photopipeline.app.PhotoPipelineApp
import com.photopipeline.app.ui.components.AccountAvatarButton
import com.photopipeline.app.ui.components.DebugLogPanel
import com.photopipeline.app.ui.components.GoogleAccountMenuSheet
import com.photopipeline.app.ui.theme.PhotoPipelineTheme
import com.photopipeline.app.api.FinalizeErrorMessages
import com.photopipeline.app.upload.LiveUploadMetrics
import com.photopipeline.app.upload.UploadEtaEstimator
import com.photopipeline.app.upload.UploadMetricsStore
import com.photopipeline.app.upload.UploadProgressSnapshot
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.photopipeline.app.auth.GoogleAuthCoordinator
import com.photopipeline.app.auth.GoogleSignInHelper
import com.photopipeline.app.core.AppDefaults
import androidx.compose.foundation.background
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhotoPipelineNavHost(repository: Repository, usbCameraManager: UsbCameraManager) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repository = repository,
                usb = usbCameraManager,
                onOpenShoot = { id -> nav.navigate("shoot/$id") },
                onNewShoot = { id -> nav.navigate("shoot/$id") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(repository = repository, onBack = { nav.popBackStack() })
        }
        composable(
            "shoot/{shootId}",
            arguments = listOf(navArgument("shootId") { type = NavType.StringType }),
        ) { entry ->
            val shootId = entry.arguments?.getString("shootId") ?: return@composable
            ShootFlowScreen(
                repository = repository,
                usb = usbCameraManager,
                shootId = shootId,
                onBack = { nav.popBackStack() },
                onDeleted = { nav.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: Repository,
    usb: UsbCameraManager,
    onOpenShoot: (String) -> Unit,
    onNewShoot: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val shoots by repository.observeShoots().collectAsState(initial = emptyList())
    val usbState by usb.accessState.collectAsState()
    val cameraSnapshot by usb.cameraSnapshot.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf(repository.isGoogleAccountLinked()) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf("") }
    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        GoogleAuthCoordinator.deliverActivityResult(result.resultCode, result.data)
    }
    val signInHelper = remember(activity) {
        GoogleSignInHelper(
            activity = activity,
            repository = repository,
            scope = scope,
            authorizationLauncher = googleLauncher,
            onAuthFinished = { linked = repository.isGoogleAccountLinked() },
            onAuthError = { authError = it },
        )
    }
    DisposableEffect(signInHelper) {
        signInHelper.bindCoordinator()
        onDispose { signInHelper.unbindCoordinator() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(usbState) {
        usb.ensureHomeCameraConnection()
    }
    DisposableEffect(lifecycleOwner, usb) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { usb.ensureHomeCameraConnection() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Photo Pipeline") },
                actions = {
                    AccountAvatarButton(
                        repository = repository,
                        linked = linked,
                        onClick = { accountMenuOpen = true },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        GoogleAccountMenuSheet(
            repository = repository,
            linked = linked,
            visible = accountMenuOpen,
            onDismiss = { accountMenuOpen = false },
            onOpenSettings = {
                accountMenuOpen = false
                onSettings()
            },
            onSignOut = {
                signInHelper.signOut()
                linked = false
                scope.launch { snackbarHost.showSnackbar("Signed out") }
            },
        )
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CameraConnectionCard(
                    usbState = usbState,
                    camera = cameraSnapshot,
                    permissionHint = "Allow USB access when Android prompts you.",
                    disconnectedHint = "Canon via USB — use a data-capable cable.",
                )
            }
            if (authError.isNotBlank()) {
                item {
                    Text(
                        authError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!linked) {
                item {
                    Button(
                        onClick = { signInHelper.start() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = repository.googleWebClientId().isNotBlank(),
                    ) {
                        Text("Connect with Google")
                    }
                }
            }
            item {
                Button(
                    onClick = { onNewShoot(newShootId()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import from camera")
                }
            }
            item {
                Text("Past imports", style = MaterialTheme.typography.titleMedium)
            }
            if (shoots.isEmpty()) {
                item {
                    Text(
                        "No imports yet. Connect your camera and tap Import from camera.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shoots, key = { it.shootId }) { s ->
                ShootListCard(
                    shoot = s,
                    repository = repository,
                    onClick = { onOpenShoot(s.shootId) },
                    onDeleted = { /* list refreshes via Flow */ },
                )
            }
            item { DebugLogPanel() }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    icon: @Composable () -> Unit,
    line1: String,
    line2: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(line1, style = MaterialTheme.typography.bodyLarge)
                Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShootListCard(
    shoot: ShootEntity,
    repository: Repository,
    onClick: () -> Unit,
    onDeleted: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(shoot.albumTitle.ifBlank { shoot.shootId.take(8) }, style = MaterialTheme.typography.titleMedium)
                Text(humanShootState(shoot), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${shoot.imageCount} files · ${fmt.format(Date(shoot.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Shoot actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Delete shoot") },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete shoot?") },
            text = { Text("This cancels any upload, removes files from S3 and the server, and deletes local data.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            runCatching { repository.deleteShootCompletely(shoot.shootId) }
                            onDeleted()
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShootFlowScreen(
    repository: Repository,
    usb: UsbCameraManager,
    shootId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val vm: ShootViewModel = viewModel(
        factory = ShootViewModel.factory(repository, usb, androidx.compose.ui.platform.LocalContext.current, shootId),
    )
    val shoot by vm.shoot.collectAsState()
    val files by vm.files.collectAsState()
    val ui by vm.ui.collectAsState()
    val uploadMetrics by UploadMetricsStore.metrics.collectAsState()
    val usbState by usb.accessState.collectAsState()
    val allUploaded = files.isNotEmpty() && files.all { it.uploadState == "UPLOADED" }
    val uploadWorkerActive = isUploadWorkerActive(ui.uploadStartState)
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(usbState) {
        vm.onUsbAccessChanged(usbState)
    }
    val cameraNotNeeded = allUploaded ||
        ui.step == ShootFlowStep.Processing ||
        ui.step == ShootFlowStep.Results
    DisposableEffect(cameraNotNeeded) {
        usb.setSuppressEmptyDeviceListWarnings(cameraNotNeeded)
        onDispose { usb.setSuppressEmptyDeviceListWarnings(false) }
    }
    var overflowOpen by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(ui.snackbar) {
        ui.snackbar?.let { msg ->
            snackbarHost.showSnackbar(msg)
            vm.clearSnackbar()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            ui.isResumeShoot -> shoot?.albumTitle ?: "Resume import"
                            else -> "Import from camera"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Shoot menu")
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Cancel upload") },
                            onClick = {
                                overflowOpen = false
                                confirmCancel = true
                            },
                            leadingIcon = { Icon(Icons.Default.Stop, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete shoot") },
                            onClick = {
                                overflowOpen = false
                                confirmDelete = true
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (!ui.flowReady) return@Scaffold
            when (ui.step) {
                ShootFlowStep.PickFiles -> {
                    val (selCount, selBytes) = vm.selectedCountAndBytes()
                    ImportFlowBottomBar(
                        summary = "$selCount selected · ${formatBytesShort(selBytes)}",
                        buttonText = if (selCount > 0) "Next" else "Next",
                        enabled = selCount > 0,
                        busy = ui.busy,
                        onClick = { vm.importSelected() },
                    )
                }
                ShootFlowStep.Upload -> {
                    val uploading = isUploadWorkerActive(ui.uploadStartState)
                    val failed = files.count { it.uploadState == "FAILED" }
                    val pending = files.count { it.uploadState != "UPLOADED" }
                    val uploaded = files.size - pending
                    val needsManualStart = !uploading && (
                        ui.isResumeShoot ||
                            ui.uploadStartState == UploadStartState.Failed ||
                            failed > 0 ||
                            files.any { it.uploadState == "PENDING" }
                        )
                    if (files.isEmpty()) return@Scaffold
                    val label = when {
                        uploading -> "Uploading…"
                        failed > 0 -> "Retry failed"
                        ui.isResumeShoot && usbState != UsbAccessState.READY -> "Connect & upload"
                        ui.isResumeShoot -> "Resume upload"
                        ui.uploadStartState == UploadStartState.Failed -> "Retry upload"
                        else -> "Start upload"
                    }
                    val action = when {
                        failed > 0 && !uploading -> ({ vm.retryFailedUploads() })
                        ui.isResumeShoot && !uploading -> ({ vm.connectAndResumeUpload() })
                        ui.uploadStartState == UploadStartState.Failed && !uploading -> ({ vm.retryFailedUploads() })
                        !uploading && needsManualStart -> ({ vm.startUpload() })
                        else -> null
                    }
                    ImportFlowBottomBar(
                        summary = "$uploaded / ${files.size} uploaded · ${formatBytesShort(files.sumOf { it.sizeBytes })}",
                        buttonText = label,
                        enabled = action != null,
                        busy = ui.busy || uploading,
                        onClick = { action?.invoke() },
                    )
                }
                else -> Unit
            }
        },
    ) { pad ->
        val bottomInset = if (
            ui.flowReady &&
            (ui.step == ShootFlowStep.PickFiles || ui.step == ShootFlowStep.Upload)
        ) {
            88.dp
        } else {
            0.dp
        }
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (
                ui.flowReady &&
                !allUploaded &&
                ui.step != ShootFlowStep.Processing &&
                ui.step != ShootFlowStep.Results
            ) {
                item { FlowStepper(current = ui.step) }
                val hideUploadChrome = ui.step == ShootFlowStep.Upload && uploadWorkerActive
                if (!hideUploadChrome) {
                    item {
                        Text(ui.statusLine, style = MaterialTheme.typography.bodyLarge)
                        if (ui.busy && !uploadWorkerActive) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                    if (ui.step != ShootFlowStep.Upload || !uploadWorkerActive) {
                        item {
                            Text(
                                "USB: ${usbStatusMessage(usbState)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!ui.backendReady && ui.backendGateMessage.isNotBlank()) {
                item { BackendGateCard(ui.backendGateMessage) }
            }
            if (!ui.flowReady) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Loading shoot…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
            when (ui.step) {
                ShootFlowStep.PickFiles -> item {
                    val (selCount, selBytes) = vm.selectedCountAndBytes()
                    SelectFilesStep(
                        ui = ui,
                        imported = files,
                        onToggle = { vm.toggleSelection(it) },
                        onImport = { vm.importSelected() },
                        onSelectAll = { vm.selectAllSelectable() },
                        onDeselectAll = { vm.deselectAll() },
                        onSelectAllForDate = { vm.selectAllForDate(it) },
                        onDeselectAllForDate = { vm.deselectAllForDate(it) },
                        selectedCount = selCount,
                        selectedBytes = selBytes,
                        busy = ui.busy,
                    )
                }
                ShootFlowStep.Upload -> {
                    if (!uploadWorkerActive) {
                        item {
                            if (ui.isResumeShoot) {
                                ResumeUploadIntro(
                                    files = files,
                                    cameraReady = usbState == UsbAccessState.READY,
                                )
                            } else {
                                Text(
                                    "Keep USB connected until every file finishes uploading.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    item {
                        UploadStep(
                            files = files,
                            metrics = uploadMetrics,
                            estimateJson = shoot?.estimateJson,
                            ui = ui,
                            usbState = usbState,
                            onStart = { vm.startUpload() },
                            onResume = { vm.connectAndResumeUpload() },
                            onRetry = { vm.retryFailedUploads() },
                        )
                    }
                    item {
                        val needRetry = files.count {
                            it.uploadState == "FAILED" || it.uploadState == "PENDING" ||
                                it.uploadState == "PAUSED" || it.uploadState == "UPLOADING"
                        }
                        if (needRetry > 0) {
                            Text(
                                "$needRetry file(s) need retry before processing can start",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (ui.finalizeState == FinalizeState.InProgress) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("Starting server processing…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (vm.needsProcessingRetry()) {
                            val retryError = ui.serverProcessingError
                                ?: ui.serverLastError?.let {
                                    com.photopipeline.app.api.FinalizeErrorMessages.friendly(
                                        ui.serverLaunchErrorCode,
                                        it,
                                    )
                                }
                                ?: ui.finalizeError
                            ProcessingRetryBlock(
                                error = retryError,
                                busy = ui.busy,
                                onRetry = { vm.retryProcessing() },
                            )
                        }
                    }
                }
                ShootFlowStep.Processing -> shoot?.let { s ->
                    item {
                        LaunchedEffect(Unit) {
                            vm.setProcessingScreenVisible(true)
                        }
                        DisposableEffect(Unit) {
                            onDispose { vm.setProcessingScreenVisible(false) }
                        }
                        val processingError = ui.serverProcessingError ?: ui.finalizeError
                        ProcessingStepScreen(
                            shoot = s,
                            files = files,
                            progressJson = ui.processingProgress,
                            serverFileStatusBySha = ui.serverFileStatusBySha,
                            showRetry = vm.needsProcessingRetry(),
                            processingError = processingError,
                            serverPhase = ui.serverPhase,
                            serverLaunchErrorCode = ui.serverLaunchErrorCode,
                            serverLastError = ui.serverLastError,
                            finalizeInProgress = ui.finalizeState == FinalizeState.InProgress,
                            processingStartedAtMs = ui.processingStartedAtMs,
                            workerInstanceType = ui.workerInstanceType,
                            workerSpot = ui.workerSpot,
                            ec2Pricing = repository.ec2Pricing,
                            statusLastUpdatedAtMs = ui.statusLastUpdatedAtMs,
                            statusPollError = ui.statusPollError,
                            busy = ui.busy,
                            thumbnailForHandle = { vm.readCachedThumbnail(it) },
                            onRetryProcessing = { vm.retryProcessing() },
                        )
                    }
                }
                ShootFlowStep.Results -> item {
                    ResultsStep(
                        shoot = shoot,
                        files = files,
                        progressJson = ui.processingProgress,
                        showRetry = vm.needsProcessingRetry(),
                        finalizeError = ui.finalizeError,
                        busy = ui.busy,
                        thumbnailForHandle = { vm.readCachedThumbnail(it) },
                        onRetryProcessing = { vm.retryProcessing() },
                    )
                }
            }
            }
            item { DebugLogPanel() }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel upload?") },
            text = { Text("Stops the upload worker and aborts in-progress S3 multipart uploads for this shoot.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; vm.cancelUpload() }) { Text("Cancel upload") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep uploading") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete shoot?") },
            text = { Text("Removes this shoot locally and on the server (S3 + DynamoDB).") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteShoot(onDeleted) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FlowStepper(current: ShootFlowStep) {
    val labels = listOf("Import", "Upload", "Process", "Done")
    val index = when (current) {
        ShootFlowStep.PickFiles -> 0
        ShootFlowStep.Upload -> 1
        ShootFlowStep.Processing -> 2
        ShootFlowStep.Results -> 3
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${labels[index]} · step ${index + 1} of ${labels.size}", style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { (index + 1).toFloat() / labels.size },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResumeUploadIntro(
    files: List<com.photopipeline.app.data.FileEntity>,
    cameraReady: Boolean,
) {
    val pending = files.count { it.uploadState != "UPLOADED" }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Resume import", style = MaterialTheme.typography.titleMedium)
        Text(
            if (cameraReady) {
                "$pending of ${files.size} files still need upload. Camera is connected — tap Resume below."
            } else {
                "$pending of ${files.size} files still need upload. Connect the camera via USB, then tap Resume."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EstimateStep(
    files: List<com.photopipeline.app.data.FileEntity>,
    estimateJson: String?,
    onEstimate: () -> Unit,
    busy: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${files.size} files ready on device", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onEstimate, enabled = !busy && files.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Get cost & time estimate")
        }
        estimateJson?.let { EstimateCard(it) }
    }
}

@Composable
private fun UploadStep(
    files: List<com.photopipeline.app.data.FileEntity>,
    metrics: LiveUploadMetrics,
    estimateJson: String?,
    ui: ShootUiState,
    usbState: UsbAccessState,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val uploading = ui.uploadStartState == UploadStartState.Uploading ||
        ui.uploadStartState == UploadStartState.Starting
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (files.isEmpty()) {
            Text("Queue files from the camera before uploading.", color = MaterialTheme.colorScheme.error)
        } else {
            estimateJson?.let { EstimateCard(it) }
            OverallUploadProgressCard(files = files, metrics = metrics, uploading = uploading)
            if (uploading || metrics.filesTotal > 0) {
                TransferMetricsCard(metrics, compact = true) {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
            }
            ui.uploadFailedReason?.let {
                Text("Failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            val needsManualStart = ui.isResumeShoot ||
                ui.uploadStartState == UploadStartState.Failed ||
                files.any { it.uploadState == "FAILED" }
            UploadProgressSection(
                files = files,
                onRetry = onRetry,
                showRetryButton = false,
            )
        }
    }
}

@Composable
private fun OverallUploadProgressCard(
    files: List<com.photopipeline.app.data.FileEntity>,
    metrics: LiveUploadMetrics,
    uploading: Boolean,
) {
    val progress = UploadProgressSnapshot.compute(files, metrics, uploading)
    val byteProgress = progress.bytesSent.toFloat() / progress.totalBytes
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Upload progress", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { byteProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )
            Text(
                "${formatBytes(progress.bytesSent)} / ${formatBytes(progress.totalBytes)} · " +
                    "${progress.filesDone} / ${progress.filesTotal} files",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Now ${formatMbps(progress.currentBps)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    UploadEtaEstimator.formatEta(progress.etaSeconds),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1_000_000_000 -> "%.2f GB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1f MB".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0f KB".format(n / 1_000.0)
    else -> "$n B"
}

@Composable
internal fun ProcessingRetryBlock(
    error: String?,
    busy: Boolean,
    onRetry: () -> Unit,
    prominent: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (prominent) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text("Retry processing")
            }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = if (prominent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            )
        }
        if (!prominent) {
            FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text("Retry processing")
            }
        }
        Text(
            "Files are already in S3 — no camera needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransferMetricsCard(
    metrics: LiveUploadMetrics,
    compact: Boolean = false,
    onOpenCaptivePortal: () -> Unit,
) {
    val net = metrics.network
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (compact) "Network & acceleration" else "Transfer metrics",
                style = MaterialTheme.typography.titleSmall,
            )
            net?.let {
                Text("Network: ${it.type} · validated=${it.validated} · metered=${it.metered}")
                if (it.captivePortal) {
                    Text("Captive portal detected — hotel Wi-Fi login required.", color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = onOpenCaptivePortal) { Text("Open Wi-Fi sign-in") }
                }
            }
            Text(
                "S3 Transfer Acceleration: ${if (metrics.transferAccelerationActive) "on" else "off"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Speed now ${formatMbps(metrics.currentBps)} · avg ${formatMbps(metrics.averageBps)} · peak ${formatMbps(metrics.peakBps)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!compact) {
                Text(
                    "Sent ${metrics.bytesSent}/${metrics.totalBytes} bytes · files ${metrics.filesDone}/${metrics.filesTotal} · parts ${metrics.partsDone}/${metrics.partsTotal}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            metrics.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            metrics.pausedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SpeedSparkline(metrics.speedHistoryBps)
        }
    }
}

private fun formatMbps(bps: Long): String = "%.1f Mbps".format(bps * 8.0 / 1_000_000.0)

@Composable
private fun SpeedSparkline(samples: List<Long>) {
    if (samples.isEmpty()) return
    val max = samples.max().coerceAtLeast(1).toFloat()
    Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        samples.forEach { v ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + 0.75f * (v / max))),
            )
        }
    }
}

@Composable
private fun ResultsStep(
    shoot: ShootEntity?,
    files: List<FileEntity>,
    progressJson: String?,
    showRetry: Boolean,
    finalizeError: String?,
    busy: Boolean,
    thumbnailForHandle: (Long) -> ByteArray?,
    onRetryProcessing: () -> Unit,
) {
    shoot ?: return
    val context = LocalContext.current
    val failed = shoot.state == "FAILED"
    val partial = shoot.state == "PARTIAL"
    val processingProgress = parseProcessingProgress(progressJson)
    val total = processingProgress?.photoCount?.takeIf { it > 0 }
        ?: shoot.imageCount.coerceAtLeast(1)
    val rendered = processingProgress?.renderedPhotos ?: files.size
    val published = processingProgress?.let {
        it.publishedPhotos + it.publishedVideos
    } ?: rendered
    val progressSummary = processingProgress?.let { formatProcessingProgressLine(it) }
        ?: "Rendered $rendered of $total · Published $published of $total"
    val compute = shoot.actualComputeJson?.let {
        runCatching {
            val o = JSONObject(it)
            o.optInt("seconds", 0) to o.optDouble("usd", 0.0)
        }.getOrNull()
    }
    val albumUrl = shootAlbumOpenUrl(shoot)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    failed -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
            ),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    when {
                        failed -> "Processing failed"
                        partial -> "Album partially uploaded"
                        else -> "Album ready in Google Photos"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
                if (!failed) {
                    Text(
                        shoot.albumTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    progressSummary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                compute?.let { (sec, usd) ->
                    Text(
                        "Total time ${formatResultsDuration(sec)} · Cost ~$${"%.4f".format(usd)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (albumUrl != null && !failed && published > 0) {
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(albumUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open album in Google Photos")
            }
        }

        if (files.isNotEmpty()) {
            Text("Photos", style = MaterialTheme.typography.titleMedium)
            files.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { file ->
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(Modifier.padding(6.dp)) {
                                ShootThumbnailImage(thumbnailForHandle(file.objectHandle))
                                Text(
                                    file.originalName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (showRetry) {
            ProcessingRetryBlock(error = finalizeError, busy = busy, onRetry = onRetryProcessing)
        }
    }
}

private fun shootAlbumOpenUrl(shoot: ShootEntity): String? {
    val url = shoot.albumProductUrl
    if (!url.isNullOrBlank() && url.startsWith("http")) return url
    val id = shoot.albumId ?: return null
    return "https://photos.google.com/lr/album/$id"
}

private fun formatResultsDuration(totalSeconds: Int): String {
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds == 0) "${minutes}m" else "${minutes}m ${seconds}s"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: Repository, onBack: () -> Unit) {
    val prefs = (LocalContext.current.applicationContext as PhotoPipelineApp).uploadPreferences
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var apiBase by remember { mutableStateOf(repository.apiBaseUrl()) }
    var googleClientId by remember { mutableStateOf(repository.googleWebClientId()) }
    val scope = rememberCoroutineScope()
    var authError by remember { mutableStateOf("") }
    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        GoogleAuthCoordinator.deliverActivityResult(result.resultCode, result.data)
    }
    val signInHelper = remember(activity) {
        GoogleSignInHelper(
            activity = activity,
            repository = repository,
            scope = scope,
            authorizationLauncher = googleLauncher,
            onAuthError = { authError = it },
        )
    }
    DisposableEffect(signInHelper) {
        signInHelper.bindCoordinator()
        onDispose { signInHelper.unbindCoordinator() }
    }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var allowCellular by remember { mutableStateOf(prefs.allowCellular) }
    var partSizeMb by remember { mutableStateOf((prefs.partSizeBytes / (1024 * 1024)).toString()) }
    var concurrency by remember { mutableStateOf(prefs.maxConcurrentParts.toString()) }
    var transferAcceleration by remember { mutableStateOf(prefs.transferAcceleration) }
    val snackbarHost = remember { SnackbarHostState() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(apiBase, { apiBase = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth())
            Text("Default region: ${AppDefaults.AWS_REGION}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                googleClientId,
                { googleClientId = it },
                label = { Text("Google Web OAuth client ID") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (authError.isNotBlank()) {
                Text(authError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!repository.isGoogleAccountLinked()) {
                Button(
                    onClick = { signInHelper.start() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = googleClientId.isNotBlank(),
                ) {
                    Text("Connect with Google")
                }
            } else {
                FilledTonalButton(
                    onClick = {
                        signInHelper.signOut()
                        scope.launch { snackbarHost.showSnackbar("Signed out") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out")
                }
            }
            Button(
                onClick = {
                    repository.saveSettings(apiBase, googleClientId.ifBlank { null })
                    prefs.wifiOnly = wifiOnly
                    prefs.allowCellular = allowCellular
                    prefs.partSizeBytes = (partSizeMb.toLongOrNull() ?: 8L).coerceAtLeast(5L) * 1024 * 1024
                    prefs.maxConcurrentParts = concurrency.toIntOrNull()?.coerceIn(0, 4) ?: 0
                    prefs.transferAcceleration = transferAcceleration
                    scope.launch { snackbarHost.showSnackbar("Settings saved") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
            HorizontalDivider()
            Text("Upload", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Wi-Fi only")
                Switch(wifiOnly, { wifiOnly = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Allow cellular")
                Switch(allowCellular, { allowCellular = it })
            }
            OutlinedTextField(partSizeMb, { partSizeMb = it }, label = { Text("Part size (MiB, min 5)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(concurrency, { concurrency = it }, label = { Text("Parallel parts (0=adaptive, 1-4)") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Use Transfer Acceleration (≈ +\$0.04/GB, helps on far-away/slow networks)",
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(transferAcceleration, { transferAcceleration = it })
            }
            HorizontalDivider()
            Text(
                "Sign-in uses Google Identity Services (server auth code + Web client ID). " +
                    "Android OAuth client: package com.photopipeline.app + debug SHA-1 in Google Cloud.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun BackendGateCard(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cloud upload not ready", style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun JSONObject.usd(key: String, default: Double = 0.0): Double {
    if (!has(key) || isNull(key)) return default
    val v = optDouble(key, default)
    return if (v.isNaN() || v.isInfinite()) default else v
}

@Composable
fun EstimateCard(json: String) {
    val o = remember(json) { JSONObject(json) }
    val source = o.optString("source", "LOCAL")
    val imageCount = o.optInt("imageCount", 0).coerceAtLeast(1)
    val total = o.usd("totalShootUsd")
    val perFile = o.usd("perFileUsd", total / imageCount)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (source == "BACKEND") "Cost estimate (API)" else "Cost estimate (on-device pricing.json)",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("~$%.4f per file × $imageCount files".format(perFile), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Processing (compute): ~${o.optDouble("processingMinutes")} min · $${"%.4f".format(o.usd("computeUsd"))}",
            )
            Text("S3 storage (standard / month): $${"%.4f".format(o.usd("s3StandardMonthUsd"))}")
            if (o.has("s3DeepArchiveMonthUsd")) {
                Text(
                    "S3 deep archive (reference / month): $${"%.4f".format(o.usd("s3DeepArchiveMonthUsd"))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("API & request overhead: $${"%.4f".format(o.usd("requestsUsd"))}")
            val accelUsd = o.usd("transferAccelerationUsd")
            if (accelUsd > 0) {
                Text("S3 Transfer Acceleration surcharge: $${"%.4f".format(accelUsd)}")
            }
            Text(
                "Estimated shoot total: $${"%.4f".format(total)}",
                style = MaterialTheme.typography.titleSmall,
            )
            o.optString("notes").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun UploadProgressSection(
    files: List<com.photopipeline.app.data.FileEntity>,
    onRetry: () -> Unit,
    showRetryButton: Boolean = true,
) {
    if (files.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Files", style = MaterialTheme.typography.titleSmall)
            files.forEach { f ->
                val pct = if (f.sizeBytes > 0) f.uploadedBytes.toFloat() / f.sizeBytes else 0f
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            f.originalName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            f.uploadState,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { pct.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    f.lastError?.let {
                        Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (showRetryButton && files.any { it.uploadState == "FAILED" }) {
                FilledTonalButton(onClick = onRetry) { Text("Retry failed uploads") }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreviewLight() {
    PhotoPipelineTheme(darkTheme = false, dynamicColor = false) {
        Text("Photo Pipeline", style = MaterialTheme.typography.headlineMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreviewDark() {
    PhotoPipelineTheme(darkTheme = true, dynamicColor = false) {
        Text("Photo Pipeline", style = MaterialTheme.typography.headlineMedium)
    }
}
