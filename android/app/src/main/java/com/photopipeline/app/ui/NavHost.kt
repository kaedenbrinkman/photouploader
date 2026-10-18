package com.photopipeline.app.ui

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.photopipeline.app.data.Repository
import com.photopipeline.app.data.ShootEntity
import com.photopipeline.app.mtp.UsbAccessState
import com.photopipeline.app.mtp.UsbCameraManager
import com.photopipeline.app.PhotoPipelineApp
import com.photopipeline.app.ui.components.DebugLogPanel
import com.photopipeline.app.ui.theme.PhotoPipelineTheme
import com.photopipeline.app.upload.LiveUploadMetrics
import com.photopipeline.app.upload.UploadMetricsStore
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import com.photopipeline.app.auth.GoogleSignInHelper
import com.photopipeline.app.core.AppDefaults
import androidx.compose.foundation.background
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
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
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf(repository.isGoogleAccountLinked()) }
    var signInHelperRef by remember { mutableStateOf<GoogleSignInHelper?>(null) }
    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        signInHelperRef?.onAuthorizationResult(result.data)
    }
    val signInHelper = remember(activity) {
        GoogleSignInHelper(activity, repository, scope, googleLauncher) {
            linked = repository.isGoogleAccountLinked()
        }.also { signInHelperRef = it }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Photo Pipeline") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    title = "Camera",
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    line1 = usbStatusMessage(usbState),
                    line2 = if (usbState == UsbAccessState.PERMISSION_REQUIRED) {
                        "Open a shoot and tap Connect to grant USB access."
                    } else {
                        "Canon R8 via USB — use a data-capable cable."
                    },
                )
            }
            item {
                StatusCard(
                    title = "Google account",
                    icon = { Icon(Icons.Default.Cloud, null) },
                    line1 = if (linked) {
                        repository.googleEmail()?.let { "Signed in as $it" }
                            ?: "Signed in (${repository.googleUserId()})"
                    } else {
                        "Not connected"
                    },
                    line2 = if (linked) {
                        "Photos upload uses your linked Google account on the server."
                    } else {
                        "Connect once to upload RAW and publish finished JPEGs to Google Photos."
                    },
                )
            }
            item {
                if (linked) {
                    FilledTonalButton(
                        onClick = {
                            repository.signOutGoogle()
                            linked = false
                            scope.launch { snackbarHost.showSnackbar("Signed out") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign out")
                    }
                } else {
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
                    Text("Start new shoot")
                }
            }
            item {
                Text("Past shoots", style = MaterialTheme.typography.titleMedium)
            }
            if (shoots.isEmpty()) {
                item {
                    Text(
                        "No shoots yet. Connect your camera and start a new shoot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shoots, key = { it.shootId }) { s ->
                ShootListCard(shoot = s, onClick = { onOpenShoot(s.shootId) })
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
private fun ShootListCard(shoot: ShootEntity, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shoot.albumTitle.ifBlank { shoot.shootId.take(8) }, style = MaterialTheme.typography.titleMedium)
            Text(humanShootState(shoot), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${shoot.imageCount} files · ${fmt.format(Date(shoot.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShootFlowScreen(
    repository: Repository,
    usb: UsbCameraManager,
    shootId: String,
    onBack: () -> Unit,
) {
    val vm: ShootViewModel = viewModel(
        factory = ShootViewModel.factory(repository, usb, androidx.compose.ui.platform.LocalContext.current, shootId),
    )
    val shoot by vm.shoot.collectAsState()
    val files by vm.files.collectAsState()
    val ui by vm.ui.collectAsState()
    val uploadMetrics by UploadMetricsStore.metrics.collectAsState()
    val usbState by usb.accessState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(shootId) { vm.startPolling() }
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
                title = { Text(shoot?.albumTitle ?: "Shoot") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { FlowStepper(current = ui.step) }
            item {
                Text(ui.statusLine, style = MaterialTheme.typography.bodyLarge)
                if (ui.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            item {
                Text(
                    "USB: ${usbStatusMessage(usbState)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ui.backendReady && ui.backendGateMessage.isNotBlank()) {
                item { BackendGateCard(ui.backendGateMessage) }
            }
            when (ui.step) {
                ShootFlowStep.Connect -> item {
                    ConnectStep(
                        onRefresh = { vm.refreshUsbProbe() },
                        onConnect = { vm.connectAndListCamera() },
                        busy = ui.busy,
                    )
                }
                ShootFlowStep.SelectFiles -> item {
                    SelectFilesStep(
                        ui = ui,
                        imported = files,
                        onToggle = { vm.toggleSelection(it) },
                        onImport = { vm.importSelected() },
                        busy = ui.busy,
                    )
                }
                ShootFlowStep.Estimate -> item {
                    EstimateStep(
                        files = files,
                        estimateJson = shoot?.estimateJson,
                        onEstimate = { vm.loadEstimate() },
                        busy = ui.busy,
                    )
                }
                ShootFlowStep.Upload -> {
                    item {
                        Text(
                            "Keep the camera USB connected until every file finishes uploading.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    item {
                        UploadStep(
                            files = files,
                            metrics = uploadMetrics,
                            onUpload = { vm.startUpload() },
                            onRetry = { vm.retryFailedUploads() },
                        )
                    }
                    item {
                        FilledTonalButton(
                            onClick = { vm.submitShoot() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = files.isNotEmpty() && files.all { it.uploadState == "UPLOADED" },
                        ) {
                            Text("Submit for processing")
                        }
                    }
                }
                ShootFlowStep.Processing -> item {
                    ProcessingStep(shoot = shoot, progressJson = ui.processingProgress)
                }
                ShootFlowStep.Results -> item {
                    ResultsStep(shoot = shoot)
                }
            }
            item { DebugLogPanel() }
        }
    }
}

@Composable
private fun FlowStepper(current: ShootFlowStep) {
    val labels = listOf("Connect", "Files", "Estimate", "Upload", "Process", "Done")
    val index = current.ordinal.coerceIn(0, labels.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Step ${index + 1} of ${labels.size}: ${labels[index]}", style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { (index + 1).toFloat() / labels.size },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConnectStep(onRefresh: () -> Unit, onConnect: () -> Unit, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Plug in the camera, then connect. If Android shows “View files”, choose Photo Pipeline when asked.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FilledTonalButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh USB scan")
        }
        Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text("Connect & list files")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectFilesStep(
    ui: ShootUiState,
    imported: List<com.photopipeline.app.data.FileEntity>,
    onToggle: (Long) -> Unit,
    onImport: () -> Unit,
    busy: Boolean,
) {
    val uploadedNames = imported.map { it.originalName }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${ui.selectedHandles.size} selected on camera", style = MaterialTheme.typography.titleSmall)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.height(280.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.cameraObjects, key = { it.handle }) { obj ->
                val selected = obj.handle in ui.selectedHandles
                val already = obj.filename in uploadedNames
                Card(
                    onClick = { if (!already) onToggle(obj.handle) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        ThumbnailOrPlaceholder(ui.thumbnails[obj.handle])
                        Text(
                            obj.filename,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            when {
                                already -> "On phone"
                                selected -> "New · selected"
                                else -> "New"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ui.selectedHandles.size == ui.cameraObjects.size, onCheckedChange = null)
            Text("Use selection above")
        }
        Button(onClick = onImport, enabled = !busy && ui.selectedHandles.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Queue selected for upload")
        }
    }
}

@Composable
private fun ThumbnailOrPlaceholder(bytes: ByteArray?) {
    if (bytes != null) {
        val bmp = remember(bytes) {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    Box(
        Modifier.fillMaxWidth().height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null)
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
    onUpload: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (files.isEmpty()) {
            Text("Queue files from the camera before uploading.", color = MaterialTheme.colorScheme.error)
        } else {
            TransferMetricsCard(metrics) {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            Button(onClick = onUpload, modifier = Modifier.fillMaxWidth()) {
                Text("Start camera → S3 upload")
            }
            UploadProgressSection(files, onRetry)
        }
    }
}

@Composable
private fun TransferMetricsCard(metrics: LiveUploadMetrics, onOpenCaptivePortal: () -> Unit) {
    val net = metrics.network
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Transfer metrics", style = MaterialTheme.typography.titleSmall)
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
            Text(
                "Sent ${metrics.bytesSent}/${metrics.totalBytes} bytes · files ${metrics.filesDone}/${metrics.filesTotal} · parts ${metrics.partsDone}/${metrics.partsTotal}",
                style = MaterialTheme.typography.bodySmall,
            )
            metrics.etaSeconds?.let { Text("ETA ~${it / 60}m ${it % 60}s", style = MaterialTheme.typography.bodySmall) }
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
private fun ProcessingStep(shoot: ShootEntity?, progressJson: String?) {
    shoot ?: return
    val phases = listOf("IMPORT", "ESTIMATE", "UPLOAD", "MANIFEST", "RENDER", "PUBLISH", "DONE")
    val phase = shoot.phase.uppercase()
    val idx = phases.indexOfFirst { phase.contains(it, ignoreCase = true) }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Processing on server", style = MaterialTheme.typography.titleMedium)
        Text(humanShootState(shoot))
        LinearProgressIndicator(progress = { (idx + 1).toFloat() / phases.size }, modifier = Modifier.fillMaxWidth())
        parseProgress(progressJson)?.let { (rendered, total, published) ->
            Text("Rendered $rendered / $total · Published $published / $total")
        }
        shoot.actualComputeJson?.let {
            val o = JSONObject(it)
            Text("Compute: ${o.optInt("seconds")}s · ~$${o.optDouble("usd")}")
        }
    }
}

@Composable
private fun ResultsStep(shoot: ShootEntity?) {
    shoot ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Shoot ${humanShootState(shoot).lowercase()}", style = MaterialTheme.typography.titleMedium)
            shoot.albumId?.let {
                Text("Google Photos album id: $it")
                Text("Open Google Photos and search your library for this album.", style = MaterialTheme.typography.bodySmall)
            } ?: Text("Album link will appear when processing finishes.")
        }
    }
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
    var signInHelperRef by remember { mutableStateOf<GoogleSignInHelper?>(null) }
    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        signInHelperRef?.onAuthorizationResult(result.data)
    }
    val signInHelper = remember(activity) {
        GoogleSignInHelper(activity, repository, scope, googleLauncher).also { signInHelperRef = it }
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
                        repository.signOutGoogle()
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

@Composable
fun EstimateCard(json: String) {
    val o = remember(json) { JSONObject(json) }
    val source = o.optString("source", "LOCAL")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (source == "BACKEND") "Estimated before you confirm (API)" else "On-device estimate (pricing.json)",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("Processing ~${o.optDouble("processingMinutes")} min")
            val accelUsd = o.optDouble("transferAccelerationUsd", 0.0)
            Text(
                buildString {
                    append(
                        "This shoot ~$${o.optDouble("totalShootUsd")} " +
                            "(compute $${o.optDouble("computeUsd")} + S3 ~$${o.optDouble("s3StandardMonthUsd")}/mo",
                    )
                    if (accelUsd > 0) append(" + accel ~$$accelUsd")
                    append(")")
                },
            )
            o.optString("notes").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun UploadProgressSection(files: List<com.photopipeline.app.data.FileEntity>, onRetry: () -> Unit) {
    if (files.isEmpty()) return
    val done = files.count { it.uploadState == "UPLOADED" }
    val totalBytes = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
    val uploadedBytes = files.sumOf { it.uploadedBytes }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Upload $done / ${files.size}", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                progress = { uploadedBytes.toFloat() / totalBytes },
                modifier = Modifier.fillMaxWidth(),
            )
            files.forEach { f ->
                val pct = if (f.sizeBytes > 0) f.uploadedBytes.toFloat() / f.sizeBytes else 0f
                Text(
                    "${f.originalName}: ${f.uploadState} ${(pct * 100).toInt()}% · ${f.uploadSpeedBps / 1024} KB/s",
                    style = MaterialTheme.typography.bodySmall,
                )
                f.lastError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (files.any { it.uploadState == "FAILED" }) {
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
