package com.photopipeline.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.photopipeline.app.mtp.UsbDebugLog

/** Shared across screens for the current app session (not persisted). */
private object DebugLogPanelSession {
    var expanded by mutableStateOf(false)
}

@Composable
fun DebugLogPanel(modifier: Modifier = Modifier) {
    val lines by UsbDebugLog.lines.collectAsState()
    val context = LocalContext.current
    val expanded = DebugLogPanelSession.expanded
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { DebugLogPanelSession.expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Debug log", style = MaterialTheme.typography.titleSmall)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse debug log" else "Expand debug log",
                )
            }
            if (expanded) {
                Text(
                    if (lines.isEmpty()) {
                        "No events yet — connect camera or sign in to Google."
                    } else {
                        lines.joinToString("\n")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                )
                OutlinedButton(
                    onClick = { copyLog(context) },
                    enabled = lines.isNotEmpty(),
                ) {
                    Text("Copy log")
                }
            }
        }
    }
}

private fun copyLog(context: Context) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Photo Pipeline USB log", UsbDebugLog.asText()))
}
