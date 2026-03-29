package dev.remodex.android.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remodex.android.R
import dev.remodex.android.feature.threads.SidebarThreadRow
import dev.remodex.android.model.ConnectionPhase
import dev.remodex.android.model.ThreadSummary
import dev.remodex.android.ui.theme.Divider
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.InkTertiary
import dev.remodex.android.ui.theme.LightBg
import dev.remodex.android.ui.theme.SidebarBg

private const val COLLAPSED_THREAD_LIMIT = 10

private data class SidebarProjectChoice(
    val projectPath: String,
    val label: String,
)

private fun projectDisplayName(projectPath: String): String {
    return projectPath
        .removePrefix("~/")
        .substringAfterLast("/")
        .ifEmpty { projectPath }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarContent(
    threads: List<ThreadSummary>,
    selectedThreadId: String?,
    connectionPhase: ConnectionPhase,
    deviceName: String,
    isCreatingThread: Boolean,
    onSelectThread: (String) -> Unit,
    onCreateThread: (String?) -> Unit,
    onRenameThread: (String, String) -> Unit,
    onArchiveThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevices: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showProjectPicker by remember { mutableStateOf(false) }
    val filteredThreads = if (searchQuery.isBlank()) {
        threads
    } else {
        threads.filter {
            it.title.contains(searchQuery, ignoreCase = true)
                || it.preview.contains(searchQuery, ignoreCase = true)
                || it.projectPath.contains(searchQuery, ignoreCase = true)
        }
    }
    val grouped = filteredThreads.groupBy { it.projectPath }
    val projectChoices = threads
        .map { it.projectPath.trim() }
        .filter { it.isNotEmpty() && it != "Unknown project" }
        .distinct()
        .map { SidebarProjectChoice(projectPath = it, label = projectDisplayName(it)) }
    val createEnabled = connectionPhase == ConnectionPhase.Ready && !isCreatingThread

    Column(modifier = modifier.fillMaxHeight()) {
        // Header: app logo + name
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.androidremodex),
                contentDescription = "Remodex logo",
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Remodex",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
        }

        // Search
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            placeholder = { Text("Search conversations", color = InkTertiary) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Black.copy(alpha = 0.04f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.03f),
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Ink,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // New Chat button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = createEnabled) {
                    if (projectChoices.isEmpty()) {
                        onCreateThread(null)
                    } else {
                        showProjectPicker = true
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .alpha(if (createEnabled || isCreatingThread) 1f else 0.42f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = 1.2.dp,
                        color = if (createEnabled || isCreatingThread) Ink else InkTertiary,
                        shape = RoundedCornerShape(5.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isCreatingThread) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.4.dp,
                        color = Ink,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Chat",
                        tint = if (createEnabled) Ink else InkTertiary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            Text(
                text = "New Chat",
                style = MaterialTheme.typography.bodyMedium,
                color = if (createEnabled || isCreatingThread) Ink else InkTertiary,
            )
        }

        if (showProjectPicker) {
            ModalBottomSheet(
                onDismissRequest = { showProjectPicker = false },
                containerColor = LightBg,
                contentColor = Ink,
                scrimColor = Color.Black.copy(alpha = 0.16f),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .size(width = 44.dp, height = 4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Divider),
                    )
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Start new chat",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    Text(
                        text = "Choose a folder for this chat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary,
                    )
                    Text(
                        text = "Folders",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTertiary,
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(projectChoices, key = { it.projectPath }) { choice ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showProjectPicker = false
                                            onCreateThread(choice.projectPath)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = InkTertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = choice.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Ink,
                                        )
                                        Text(
                                            text = choice.projectPath,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = InkTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                HorizontalDivider(color = Divider)
                            }
                        }

                        item(key = "cloud-choice") {
                            Text(
                                text = "Cloud",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkTertiary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showProjectPicker = false
                                            onCreateThread(null)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Cloud,
                                        contentDescription = null,
                                        tint = InkTertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Cloud",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Ink,
                                        )
                                        Text(
                                            text = "Start a chat without a local folder.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = InkTertiary,
                                        )
                                    }
                                }
                                HorizontalDivider(color = Divider)
                            }
                        }
                    }

                    Text(
                        text = "Chats started in a folder stay scoped to that working directory. Cloud chats stay global.",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkTertiary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }

        HorizontalDivider(
            color = Divider,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // Thread list with collapsible project groups
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            grouped.forEach { (projectPath, projectThreads) ->
                item(key = "group-$projectPath") {
                    CollapsibleProjectGroup(
                        projectPath = projectPath,
                        threads = projectThreads,
                        selectedThreadId = selectedThreadId,
                        isConnected = connectionPhase == ConnectionPhase.Ready,
                        isCreatingThread = isCreatingThread,
                        onSelectThread = onSelectThread,
                        onCreateThreadInProject = { onCreateThread(projectPath) },
                        onRenameThread = onRenameThread,
                        onArchiveThread = onArchiveThread,
                        onDeleteThread = onDeleteThread,
                    )
                }
            }
        }

        // Bottom: Connection status + Settings
        HorizontalDivider(
            color = Divider,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Settings button
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = InkTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Connection status (tap to open device manager)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = onOpenDevices != null) { onOpenDevices?.invoke() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                val statusText = when (connectionPhase) {
                    ConnectionPhase.Ready -> "Connected"
                    ConnectionPhase.Connecting -> "Connecting..."
                    ConnectionPhase.Handshaking -> "Handshaking..."
                    ConnectionPhase.Syncing -> "Syncing..."
                    ConnectionPhase.TrustedMac -> "Saved trust"
                    else -> ""
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkTertiary,
                    )
                }
                if (connectionPhase == ConnectionPhase.Ready) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsibleProjectGroup(
    projectPath: String,
    threads: List<ThreadSummary>,
    selectedThreadId: String?,
    isConnected: Boolean,
    isCreatingThread: Boolean,
    onSelectThread: (String) -> Unit,
    onCreateThreadInProject: () -> Unit,
    onRenameThread: (String, String) -> Unit,
    onArchiveThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
) {
    var isExpanded by rememberSaveable(projectPath) { mutableStateOf(true) }
    var showAll by rememberSaveable(projectPath) { mutableStateOf(false) }

    val displayName = projectDisplayName(projectPath)

    val visibleThreads = if (isExpanded) {
        if (showAll || threads.size <= COLLAPSED_THREAD_LIMIT) {
            threads
        } else {
            threads.take(COLLAPSED_THREAD_LIMIT)
        }
    } else {
        emptyList()
    }
    val hiddenCount = threads.size - COLLAPSED_THREAD_LIMIT

    Column(modifier = Modifier.fillMaxWidth()) {
        // Project header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = InkTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.05f))
                    .clickable(enabled = isConnected && !isCreatingThread, onClick = onCreateThreadInProject)
                    .alpha(if (isConnected && !isCreatingThread) 1f else 0.4f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New chat in $displayName",
                    tint = Ink,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Thread rows (animated)
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                visibleThreads.forEach { thread ->
                    SidebarThreadRow(
                        thread = thread,
                        isSelected = thread.id == selectedThreadId,
                        onClick = { onSelectThread(thread.id) },
                        onRename = { newTitle -> onRenameThread(thread.id, newTitle) },
                        onArchive = { onArchiveThread(thread.id) },
                        onDelete = { onDeleteThread(thread.id) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }

                // "Show N more" button
                if (isExpanded && !showAll && hiddenCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAll = true }
                            .padding(start = 40.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Show $hiddenCount more",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkTertiary,
                        )
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = InkTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}
