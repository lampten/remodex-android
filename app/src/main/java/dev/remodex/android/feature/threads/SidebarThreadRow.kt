package dev.remodex.android.feature.threads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.ThreadSummary
import dev.remodex.android.ui.theme.CardBg
import dev.remodex.android.ui.theme.CopperLight
import dev.remodex.android.ui.theme.ErrorRed
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.InkTertiary
import dev.remodex.android.ui.theme.SignalGreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarThreadRow(
    thread: ThreadSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val hasContextActions = onRename != null || onArchive != null || onDelete != null

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasContextActions) {
                        { showContextMenu = true }
                    } else {
                        null
                    },
                ),
            shape = RoundedCornerShape(14.dp),
            color = if (isSelected) CopperLight else Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Status dot
                val dotColor = when (thread.status) {
                    ThreadStatus.Running -> SignalGreen
                    ThreadStatus.Failed -> ErrorRed
                    ThreadStatus.Waiting, ThreadStatus.Completed -> Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(8.dp),
                ) {
                    if (dotColor != Color.Transparent) {
                        Surface(
                            shape = CircleShape,
                            color = dotColor,
                            modifier = Modifier.size(8.dp),
                        ) {}
                    }
                }

                // Title + preview
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (thread.preview.isNotBlank()) {
                        Text(
                            text = thread.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Time
                Text(
                    text = thread.lastUpdatedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Context menu dropdown
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(Color.White),
        ) {
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        showContextMenu = false
                        showRenameDialog = true
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Ink,
                        leadingIconColor = InkSecondary,
                    ),
                )
            }
            if (onArchive != null) {
                DropdownMenuItem(
                    text = { Text("Archive") },
                    onClick = {
                        showContextMenu = false
                        onArchive()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Ink,
                        leadingIconColor = InkSecondary,
                    ),
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showContextMenu = false
                        showDeleteConfirm = true
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = ErrorRed,
                        leadingIconColor = ErrorRed,
                    ),
                )
            }
        }
    }

    // Rename dialog
    if (showRenameDialog && onRename != null) {
        var newTitle by remember { mutableStateOf(thread.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename conversation") },
            text = {
                TextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CardBg,
                        unfocusedContainerColor = CardBg,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        cursorColor = Ink,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            onRename(newTitle.trim())
                            showRenameDialog = false
                        }
                    },
                ) {
                    Text("Rename", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = InkSecondary)
                }
            },
            containerColor = Color.White,
            titleContentColor = Ink,
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete conversation?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = InkSecondary)
                }
            },
            containerColor = Color.White,
            titleContentColor = Ink,
            textContentColor = InkSecondary,
        )
    }
}
