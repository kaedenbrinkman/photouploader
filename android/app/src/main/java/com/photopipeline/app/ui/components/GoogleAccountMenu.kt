package com.photopipeline.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photopipeline.app.data.Repository
import kotlin.math.abs

@Composable
fun AccountAvatarButton(
    repository: Repository,
    linked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        AccountAvatar(repository = repository, linked = linked, sizeDp = 32)
    }
}

@Composable
fun AccountAvatar(
    repository: Repository,
    linked: Boolean,
    sizeDp: Int,
    modifier: Modifier = Modifier,
) {
    val size = sizeDp.dp
    if (!linked) {
        Icon(
            Icons.Default.AccountCircle,
            contentDescription = "Account",
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val name = repository.googleDisplayName()
    val email = repository.googleEmail()
    val initial = avatarInitial(name, email)
    val color = avatarColor(initial)
    val pictureModel = repository.googlePictureModel()
    if (pictureModel != null) {
        AsyncImage(
            model = pictureModel,
            contentDescription = "Google account",
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

fun avatarInitial(name: String?, email: String?): String {
    val src = name?.trim()?.takeIf { it.isNotBlank() }
        ?: email?.substringBefore("@")?.trim()
        ?: "?"
    return src.first().uppercaseChar().toString()
}

fun avatarColor(initial: String): androidx.compose.ui.graphics.Color {
    val palette = listOf(
        0xFF1A73E8, 0xFF188038, 0xFFEA4335, 0xFFFBBC04, 0xFF9334E6, 0xFF12B5CB,
    )
    val idx = abs(initial.hashCode()) % palette.size
    return androidx.compose.ui.graphics.Color(palette[idx])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleAccountMenuSheet(
    repository: Repository,
    linked: Boolean,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (!visible) return
    var confirmSignOut by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AccountAvatar(repository = repository, linked = linked, sizeDp = 48)
                Column {
                    Text(
                        if (linked) {
                            repository.googleDisplayName() ?: repository.googleEmail() ?: "Google account"
                        } else {
                            "Not signed in"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (linked) {
                        repository.googleEmail()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider()
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenSettings()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text("Settings")
            }
            if (linked) {
                TextButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You will need to connect Google again to upload or process shoots.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                    onDismiss()
                }) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }
}
