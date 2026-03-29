package dev.remodex.android.feature.threads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.ui.theme.ErrorRed
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.SignalGreen

@Composable
fun ConversationToolbar(
    title: String,
    status: ThreadStatus,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = Ink,
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Status badge
        val badgeColor = when (status) {
            ThreadStatus.Running -> SignalGreen
            ThreadStatus.Failed -> ErrorRed
            else -> null
        }
        val badgeLabel = when (status) {
            ThreadStatus.Running -> "Running"
            ThreadStatus.Failed -> "Failed"
            else -> null
        }
        if (badgeColor != null && badgeLabel != null) {
            Surface(
                shape = CircleShape,
                color = badgeColor.copy(alpha = 0.12f),
            ) {
                Text(
                    text = badgeLabel,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                )
            }
        }
    }
}
