package com.photopipeline.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photopipeline.app.api.FinalizeErrorMessages
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.data.ShootEntity
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun ProcessingStepScreen(
    shoot: ShootEntity,
    files: List<FileEntity>,
    progressJson: String?,
    serverFileStatusBySha: Map<String, String>,
    showRetry: Boolean,
    processingError: String?,
    serverPhase: String?,
    serverLaunchErrorCode: String?,
    serverLastError: String?,
    finalizeInProgress: Boolean,
    processingStartedAtMs: Long?,
    busy: Boolean,
    thumbnailForHandle: (Long) -> ByteArray?,
    onRetryProcessing: () -> Unit,
) {
    val pipelineStep = resolveProcessingPipelineStep(shoot.state, serverPhase ?: shoot.phase, finalizeInProgress)
    val steps = ProcessingPipelineStep.entries
    val activeIndex = steps.indexOf(pipelineStep).coerceAtLeast(0)

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val elapsedMs = processingStartedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val displayError = processingError ?: serverLastError?.let {
        FinalizeErrorMessages.friendly(serverLaunchErrorCode, it)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showRetry && !displayError.isNullOrBlank()) {
            ProcessingRetryBlock(
                error = displayError,
                busy = busy,
                onRetry = onRetryProcessing,
                prominent = true,
            )
        }

        ProcessingPipelineStepper(activeIndex = activeIndex)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            elapsedMs?.let {
                Text(
                    "Elapsed ${formatDuration(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            shoot.deadlineAt?.let { deadlineMs ->
                val stopFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                Text(
                    "Auto-stops by ${stopFmt.format(Date(deadlineMs))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (finalizeInProgress) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text("Starting server…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        parseProgress(progressJson)?.let { (rendered, total, published) ->
            val safeTotal = total.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Rendered $rendered of $total", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { rendered.toFloat() / safeTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Published $published of $total", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { published.toFloat() / safeTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        shoot.actualComputeJson?.let {
            val o = JSONObject(it)
            val sec = o.optInt("seconds", 0)
            val usd = o.optDouble("usd", 0.0)
            Text(
                "Running cost · ${sec}s · ~$${"%.4f".format(usd)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text("Photos", style = MaterialTheme.typography.titleMedium)
        files.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { file ->
                    val sha = file.sha256.lowercase()
                    val serverState = when {
                        sha.isNotBlank() -> serverFileStatusBySha[sha]
                        else -> serverFileStatusBySha.values.firstOrNull()
                    }
                    val badge = fileProcessingBadge(serverState)
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Box {
                            Column(Modifier.padding(6.dp)) {
                                ShootThumbnailImage(thumbnailForHandle(file.objectHandle))
                                Text(
                                    file.originalName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            StatusBadge(
                                label = badge.label,
                                tint = badgeColor(badge),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (showRetry && displayError.isNullOrBlank()) {
            Button(onClick = onRetryProcessing, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Retry processing")
            }
        }
    }
}

@Composable
private fun ProcessingPipelineStepper(activeIndex: Int) {
    val steps = ProcessingPipelineStep.entries
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { index, step ->
            val done = index < activeIndex
            val active = index == activeIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        done -> "✓"
                        active -> "●"
                        else -> "○"
                    },
                    color = when {
                        done || active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    step.label,
                    style = if (active) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (index <= activeIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
fun StatusBadge(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        color = tint.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

private fun badgeColor(badge: FileProcessingBadge): androidx.compose.ui.graphics.Color {
    return when (badge) {
        FileProcessingBadge.Published -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
        FileProcessingBadge.Rendered -> androidx.compose.ui.graphics.Color(0xFF1565C0)
        FileProcessingBadge.Rendering -> androidx.compose.ui.graphics.Color(0xFF0277BD)
        FileProcessingBadge.Failed -> androidx.compose.ui.graphics.Color(0xFFC62828)
        FileProcessingBadge.Queued -> androidx.compose.ui.graphics.Color(0xFF757575)
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
