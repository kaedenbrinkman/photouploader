package com.photopipeline.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photopipeline.app.data.Repository
import org.json.JSONObject

@Composable
fun PhotoPipelineNavHost(repository: Repository) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repository = repository,
                onOpenShoot = { id -> nav.navigate("shoot/$id") },
                onNewShoot = { id -> nav.navigate("shoot/$id") },
            )
        }
        composable(
            "shoot/{shootId}",
            arguments = listOf(navArgument("shootId") { type = NavType.StringType }),
        ) { entry ->
            val shootId = entry.arguments?.getString("shootId") ?: return@composable
            ShootScreen(repository = repository, shootId = shootId, onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repository: Repository, onOpenShoot: (String) -> Unit, onNewShoot: (String) -> Unit) {
    val shoots by repository.observeShoots().collectAsState(initial = emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Photo Pipeline") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onNewShoot(newShootId()) }) { Text("New shoot") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shoots) { s ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenShoot(s.shootId) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.albumTitle.ifBlank { s.shootId }, style = MaterialTheme.typography.titleMedium)
                            Text("${s.state} · ${s.phase} · ${s.imageCount} files")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShootScreen(repository: Repository, shootId: String, onBack: () -> Unit) {
    val vm: ShootViewModel = viewModel(factory = ShootViewModel.factory(repository, shootId))
    val shoot by vm.shoot.collectAsState()
    val files by vm.files.collectAsState()
    LaunchedEffect(shootId) { vm.startPolling() }

    Scaffold(topBar = { TopAppBar(title = { Text(shoot?.albumTitle ?: "Shoot") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { vm.connectCamera() }) { Text("Detect camera & import CR3") }
            Button(onClick = { vm.loadEstimate() }) { Text("Refresh cost estimate") }
            shoot?.estimateJson?.let { EstimateCard(it) }
            UploadProgressSection(files, onRetry = { vm.retryFailedUploads() })
            Button(onClick = { vm.startUpload() }) { Text("Upload new files to S3") }
            Button(onClick = { vm.submitShoot() }) { Text("Submit shoot for processing") }
            ProcessingSection(shoot)
        }
    }
}

@Composable
fun EstimateCard(json: String) {
    val o = remember(json) { JSONObject(json) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Estimated before you confirm", style = MaterialTheme.typography.titleSmall)
            Text("Processing ~${o.optDouble("processingMinutes")} min")
            Text("This shoot ~$${o.optDouble("totalShootUsd")} (compute $${o.optDouble("computeUsd")} + S3 ~$${o.optDouble("s3StandardMonthUsd")}/mo Standard)")
        }
    }
}

@Composable
fun UploadProgressSection(files: List<com.photopipeline.app.data.FileEntity>, onRetry: () -> Unit) {
    if (files.isEmpty()) return
    val done = files.count { it.uploadState == "UPLOADED" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Upload $done / ${files.size}", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                progress = { done.toFloat() / files.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            files.forEach { f ->
                val pct = if (f.sizeBytes > 0) f.uploadedBytes.toFloat() / f.sizeBytes else 0f
                Text("${f.originalName}: ${f.uploadState} ${(pct * 100).toInt()}% · ${f.uploadSpeedBps / 1024} KB/s")
                f.lastError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            }
            if (files.any { it.uploadState == "FAILED" }) {
                Button(onClick = onRetry) { Text("Retry failed uploads") }
            }
        }
    }
}

@Composable
fun ProcessingSection(shoot: com.photopipeline.app.data.ShootEntity?) {
    shoot ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Processing", style = MaterialTheme.typography.titleSmall)
            Text("State: ${shoot.state} · Phase: ${shoot.phase}")
            shoot.actualComputeJson?.let {
                val o = JSONObject(it)
                Text("Actual compute: ${o.optInt("seconds")}s · ~$${o.optDouble("usd")}")
            }
            shoot.albumId?.let { Text("Google Photos album id: $it") }
        }
    }
}
