package com.photopipeline.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.mtp.MtpObjectInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectFilesStep(
    ui: ShootUiState,
    imported: List<FileEntity>,
    onToggle: (Long) -> Unit,
    onImport: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onSelectAllForDate: (String) -> Unit,
    onDeselectAllForDate: (String) -> Unit,
    selectedCount: Int,
    selectedBytes: Long,
    busy: Boolean,
) {
    val importedThisShoot = imported.map { it.originalName to it.sizeBytes }.toSet()
    val grouped = ui.cameraObjects.groupBy { captureDateKey(it.captureTimeEpochMs) }
        .toList()
        .sortedByDescending { it.first }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onSelectAll, modifier = Modifier.weight(1f)) {
                Text("Select all")
            }
            FilledTonalButton(onClick = onDeselectAll, modifier = Modifier.weight(1f)) {
                Text("Deselect all")
            }
        }
        Text(
            "$selectedCount selected · ${formatBytesShort(selectedBytes)}",
            style = MaterialTheme.typography.titleSmall,
        )
        grouped.forEach { (dateKey, objects) ->
            val selectableOnDate = objects.filter { obj ->
                obj.handle !in ui.uploadedElsewhereHandles &&
                    (obj.filename to obj.sizeBytes) !in importedThisShoot
            }
            val allSelected = selectableOnDate.isNotEmpty() &&
                selectableOnDate.all { it.handle in ui.selectedHandles }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        captureDateHeading(objects.first().captureTimeEpochMs),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Select date", style = MaterialTheme.typography.labelMedium)
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                if (checked) onSelectAllForDate(dateKey) else onDeselectAllForDate(dateKey)
                            },
                            enabled = selectableOnDate.isNotEmpty(),
                        )
                    }
                }
                objects.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { obj ->
                            val uploadedElsewhere = obj.handle in ui.uploadedElsewhereHandles
                            val inThisShoot = (obj.filename to obj.sizeBytes) in importedThisShoot
                            val blocked = uploadedElsewhere || inThisShoot
                            val selected = obj.handle in ui.selectedHandles
                            Card(
                                onClick = { if (!blocked) onToggle(obj.handle) },
                                modifier = Modifier
                                    .weight(1f)
                                    .alpha(if (blocked) 0.45f else 1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        blocked -> MaterialTheme.colorScheme.surfaceContainerHighest
                                        selected -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                ),
                            ) {
                                Column(Modifier.padding(6.dp)) {
                                    ShootThumbnailImage(ui.thumbnails[obj.handle])
                                    Text(
                                        obj.filename,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    when {
                                        uploadedElsewhere || inThisShoot -> {
                                            StatusBadge(
                                                label = "Uploaded",
                                                tint = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                        selected -> {
                                            Text(
                                                "Selected",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        repeat(3 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Button(
            onClick = onImport,
            enabled = !busy && selectedCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text("Upload $selectedCount selected files")
        }
    }
}

fun formatBytesShort(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
