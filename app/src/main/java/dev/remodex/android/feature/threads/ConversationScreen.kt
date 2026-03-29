package dev.remodex.android.feature.threads

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.remodex.android.model.AccessMode
import dev.remodex.android.model.ContextWindowUsage
import dev.remodex.android.model.ImageAttachment
import dev.remodex.android.model.ModelOption
import dev.remodex.android.model.ReasoningDisplayOption
import dev.remodex.android.model.ServiceTier
import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.model.ThreadSummary
import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import dev.remodex.android.model.modelDisplayTitle
import dev.remodex.android.ui.theme.AssistantBubble
import dev.remodex.android.ui.theme.ComposerBg
import dev.remodex.android.ui.theme.Copper
import dev.remodex.android.ui.theme.Divider
import dev.remodex.android.ui.theme.ErrorRed
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.InkTertiary
import dev.remodex.android.ui.theme.SystemBubble
import dev.remodex.android.ui.theme.WarningAmber

@Composable
fun ConversationScreen(
    summary: ThreadSummary,
    detail: ThreadDetail,
    draftMessage: String,
    isLive: Boolean,
    isSending: Boolean,
    isStopping: Boolean,
    isContinuing: Boolean,
    // Model / reasoning
    availableModels: List<ModelOption>,
    isLoadingModels: Boolean,
    selectedModelId: String?,
    selectedModelTitle: String,
    selectedReasoningTitle: String,
    currentReasoningOptions: List<ReasoningDisplayOption>,
    effectiveReasoningEffort: String,
    selectedReasoningEffort: String?,
    selectedServiceTier: ServiceTier?,
    isReasoningOverrideActive: Boolean,
    isServiceTierOverrideActive: Boolean,
    onSelectModel: (String?) -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (ServiceTier?) -> Unit,
    onUseDefaultReasoning: () -> Unit,
    onUseDefaultServiceTier: () -> Unit,
    // Secondary bar
    isPlanModeArmed: Boolean,
    onTogglePlanMode: () -> Unit,
    selectedAccessMode: AccessMode,
    onSelectAccessMode: (AccessMode) -> Unit,
    currentGitBranch: String,
    contextWindowUsage: ContextWindowUsage?,
    // Attachments
    pendingAttachments: List<ImageAttachment>,
    onRemoveAttachment: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    // Core actions
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = isSending || isStopping || isContinuing
    val timelineEntries = remember(detail.entries) { collapseThinkingEntries(detail.entries) }
    val activePlanEntry = timelineEntries.lastOrNull { entry ->
        entry.kind == TimelineEntryKind.Plan && (entry.body.isNotBlank() || entry.planSteps.isNotEmpty())
    }
    val visibleEntries = timelineEntries.filterNot { it.id == activePlanEntry?.id }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Toolbar
        ConversationToolbar(
            title = summary.title,
            status = detail.status,
            onMenuClick = onMenuClick,
        )

        // Timeline
        val listState = rememberLazyListState()
        LaunchedEffect(visibleEntries.size) {
            if (visibleEntries.isNotEmpty()) {
                listState.animateScrollToItem(visibleEntries.size - 1)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visibleEntries, key = { it.id }) { entry ->
                MessageBubble(entry = entry)
            }
        }

        activePlanEntry?.let { planEntry ->
            ActivePlanAccessoryCard(
                entry = planEntry,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Composer area
        ComposerArea(
            draftMessage = draftMessage,
            isLive = isLive,
            isBusy = isBusy,
            isSending = isSending,
            isStopping = isStopping,
            canStop = detail.canInterrupt,
            canContinue = detail.canContinue,
            isContinuing = isContinuing,
            availableModels = availableModels,
            isLoadingModels = isLoadingModels,
            selectedModelId = selectedModelId,
            selectedModelTitle = selectedModelTitle,
            selectedReasoningTitle = selectedReasoningTitle,
            currentReasoningOptions = currentReasoningOptions,
            effectiveReasoningEffort = effectiveReasoningEffort,
            selectedReasoningEffort = selectedReasoningEffort,
            selectedServiceTier = selectedServiceTier,
            isReasoningOverrideActive = isReasoningOverrideActive,
            isServiceTierOverrideActive = isServiceTierOverrideActive,
            onSelectModel = onSelectModel,
            onSelectReasoning = onSelectReasoning,
            onSelectServiceTier = onSelectServiceTier,
            onUseDefaultReasoning = onUseDefaultReasoning,
            onUseDefaultServiceTier = onUseDefaultServiceTier,
            isPlanModeArmed = isPlanModeArmed,
            onTogglePlanMode = onTogglePlanMode,
            selectedAccessMode = selectedAccessMode,
            onSelectAccessMode = onSelectAccessMode,
            currentGitBranch = currentGitBranch,
            contextWindowUsage = contextWindowUsage,
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = onRemoveAttachment,
            onPickFromGallery = onPickFromGallery,
            onTakePhoto = onTakePhoto,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onStop = onStop,
            onContinue = onContinue,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Message bubble
// ──────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(entry: TimelineEntry) {
    if (entry.kind == TimelineEntryKind.Thinking) {
        ThinkingTimelineEntry(entry = entry)
        return
    }
    if (entry.kind != TimelineEntryKind.Chat) {
        StructuredTimelineEntry(entry = entry)
        return
    }

    val isUser = entry.speaker.equals("You", ignoreCase = true)
    val isSystem = entry.speaker.equals("System", ignoreCase = true)
    // iOS uses tertiarySystemFill at 80% opacity ≈ light gray
    val userBubbleBg = Color(0xFFE8E8ED).copy(alpha = 0.8f)
    val containerColor = when {
        isUser -> userBubbleBg
        isSystem -> SystemBubble
        else -> AssistantBubble
    }
    val contentColor = Ink
    val bubbleShape = if (isUser) {
        RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth(0.92f),
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = if (isUser) BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.08f)) else null,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!isUser) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.speaker,
                            style = MaterialTheme.typography.labelMedium,
                            color = InkSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = entry.timestampLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = InkTertiary,
                        )
                    }
                }
                MarkdownBody(
                    text = entry.body,
                    textColor = contentColor,
                    bodyStyle = MaterialTheme.typography.bodyMedium,
                )
                if (entry.attachments.isNotEmpty()) {
                    TimelineAttachmentsRow(attachments = entry.attachments)
                }
                if (isUser) {
                    Text(
                        text = entry.timestampLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkTertiary,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingTimelineEntry(entry: TimelineEntry) {
    val thinkingText = remember(entry.body) { normalizeThinkingEntryBody(entry.body) }
    if (!entry.isStreaming && thinkingText.isBlank()) {
        return
    }
    val canExpand = thinkingText.contains('\n') || thinkingText.length > 140
    val collapsedPreview = remember(thinkingText) { compactPreviewText(thinkingText, maxChars = 140) }
    var isExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Thinking...",
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSecondary.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium,
                )
                if (entry.isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = InkTertiary,
                        strokeWidth = 1.5.dp,
                    )
                }
            }

            if (thinkingText.isNotBlank()) {
                Text(
                    text = if (canExpand && !isExpanded) collapsedPreview else thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkTertiary,
                    maxLines = if (canExpand && !isExpanded) 3 else Int.MAX_VALUE,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .then(
                            if (canExpand) {
                                Modifier.clickable { isExpanded = !isExpanded }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun StructuredTimelineEntry(entry: TimelineEntry) {
    val containerColor = when (entry.kind) {
        TimelineEntryKind.Thinking -> AssistantBubble
        TimelineEntryKind.FileChange -> SystemBubble
        TimelineEntryKind.CommandExecution -> Color(0xFFF3F4F6)
        TimelineEntryKind.Plan -> Color(0xFFFFF6E8)
        TimelineEntryKind.ToolActivity -> Color(0xFFF5F7FA)
        TimelineEntryKind.SubagentAction -> Color(0xFFF0F4FF)
        TimelineEntryKind.System -> SystemBubble
        TimelineEntryKind.Chat -> AssistantBubble
    }
    val canCollapse = entry.kind == TimelineEntryKind.Thinking || entry.kind == TimelineEntryKind.Plan
    var isExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val previewText = remember(entry.body) { compactPreviewText(entry.body) }
    val showExpandedBody = !canCollapse || isExpanded

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canCollapse) { isExpanded = !isExpanded },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.title ?: entry.speaker,
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = entry.timestampLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkTertiary,
                )
            }

            if (entry.body.isNotBlank()) {
                MarkdownBody(
                    text = if (showExpandedBody) entry.body else previewText,
                    textColor = Ink,
                    bodyStyle = MaterialTheme.typography.bodyMedium,
                    codeBlockBackground = Color.Black.copy(alpha = 0.05f),
                    monospaceByDefault = entry.kind == TimelineEntryKind.CommandExecution,
                )
            }

            if (entry.attachments.isNotEmpty()) {
                TimelineAttachmentsRow(attachments = entry.attachments)
            }

            if (!entry.detail.isNullOrBlank() && entry.detail != entry.body) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                )
            }

            if (entry.kind == TimelineEntryKind.Plan && entry.planSteps.isNotEmpty() && showExpandedBody) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entry.planSteps.forEachIndexed { index, step ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.06f),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkSecondary,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = step.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink,
                                )
                                step.status?.takeIf { it.isNotBlank() }?.let { status ->
                                    Text(
                                        text = status.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Copper,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (canCollapse) {
                Text(
                    text = if (isExpanded) "Tap to collapse" else "Tap to expand",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkTertiary,
                )
            }
        }
    }
}

@Composable
private fun TimelineAttachmentsRow(
    attachments: List<ImageAttachment>,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            AttachmentThumbnailTile(
                attachment = attachment,
                contentDescription = "Timeline attachment",
                renderPreference = AttachmentRenderPreference.TimelineHistory,
                modifier = Modifier.size(88.dp),
            )
        }
    }
}

@Composable
private fun ActivePlanAccessoryCard(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val summary = entry.body.ifBlank { entry.planSteps.firstOrNull()?.text.orEmpty() }
    val footer = when {
        entry.planSteps.isNotEmpty() -> "${entry.planSteps.size} steps"
        else -> "Open plan"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFF6E8),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Active plan",
                style = MaterialTheme.typography.labelMedium,
                color = Copper,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = compactPreviewText(summary),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
            )
            if (isExpanded && entry.planSteps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    entry.planSteps.forEachIndexed { index, step ->
                        Text(
                            text = "${index + 1}. ${step.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary,
                        )
                    }
                }
            }
            Text(
                text = if (isExpanded) "Hide details" else footer,
                style = MaterialTheme.typography.labelSmall,
                color = InkTertiary,
            )
        }
    }
}

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
}

@Composable
private fun MarkdownBody(
    text: String,
    textColor: Color,
    bodyStyle: TextStyle,
    modifier: Modifier = Modifier,
    codeBlockBackground: Color = Color.Black.copy(alpha = 0.04f),
    monospaceByDefault: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        markdownBlocks(text).forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = markdownAnnotatedString(
                            text = block.text,
                            defaultColor = textColor,
                            monospaceByDefault = monospaceByDefault,
                        ),
                        style = bodyStyle,
                        color = textColor,
                    )
                }

                is MarkdownBlock.Code -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = codeBlockBackground,
                    ) {
                        Text(
                            text = block.text.trimEnd(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            style = bodyStyle.copy(fontFamily = FontFamily.Monospace),
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

private fun markdownBlocks(text: String): List<MarkdownBlock> {
    if (text.isBlank()) {
        return emptyList()
    }
    val regex = Regex("```(?:[a-zA-Z0-9_-]+)?\\n(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL))
    val blocks = mutableListOf<MarkdownBlock>()
    var cursor = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > cursor) {
            val before = text.substring(cursor, match.range.first)
            appendParagraphBlocks(blocks, before)
        }
        blocks += MarkdownBlock.Code(match.groupValues[1])
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        appendParagraphBlocks(blocks, text.substring(cursor))
    }
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(text.trim())) }
}

private fun appendParagraphBlocks(
    destination: MutableList<MarkdownBlock>,
    rawText: String,
) {
    rawText
        .trim()
        .split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { destination += MarkdownBlock.Paragraph(it) }
}

private fun markdownAnnotatedString(
    text: String,
    defaultColor: Color,
    monospaceByDefault: Boolean,
): AnnotatedString {
    val regex = Regex("(\\[([^\\]]+)\\]\\(([^)]+)\\))|(`[^`]+`)|(\\*\\*[^*]+\\*\\*)")
    return buildAnnotatedString {
        var cursor = 0
        for (match in regex.findAll(text)) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("[") -> {
                    val label = match.groupValues.getOrNull(2).orEmpty().ifBlank {
                        match.groupValues.getOrNull(3).orEmpty()
                    }
                    pushStyle(
                        SpanStyle(
                            color = Copper,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    append(label)
                    pop()
                }

                token.startsWith("`") -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.Black.copy(alpha = 0.06f),
                        ),
                    )
                    append(token.removePrefix("`").removeSuffix("`"))
                    pop()
                }

                token.startsWith("**") -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(token.removePrefix("**").removeSuffix("**"))
                    pop()
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
        if (monospaceByDefault) {
            addStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, color = defaultColor),
                start = 0,
                end = length,
            )
        }
    }
}

private fun compactPreviewText(
    value: String,
    maxChars: Int = 180,
): String {
    val normalized = value
        .replace("\r\n", "\n")
        .trim()
        .replace(Regex("\\n{3,}"), "\n\n")
    if (normalized.length <= maxChars) {
        return normalized
    }
    return normalized.take(maxChars).trimEnd() + "..."
}

// ──────────────────────────────────────────────────────────────
// Composer Area (input card + bottom bar + secondary bar)
// ──────────────────────────────────────────────────────────────

@Composable
private fun ComposerArea(
    draftMessage: String,
    isLive: Boolean,
    isBusy: Boolean,
    isSending: Boolean,
    isStopping: Boolean,
    canStop: Boolean,
    canContinue: Boolean,
    isContinuing: Boolean,
    availableModels: List<ModelOption>,
    isLoadingModels: Boolean,
    selectedModelId: String?,
    selectedModelTitle: String,
    selectedReasoningTitle: String,
    currentReasoningOptions: List<ReasoningDisplayOption>,
    effectiveReasoningEffort: String,
    selectedReasoningEffort: String?,
    selectedServiceTier: ServiceTier?,
    isReasoningOverrideActive: Boolean,
    isServiceTierOverrideActive: Boolean,
    onSelectModel: (String?) -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (ServiceTier?) -> Unit,
    onUseDefaultReasoning: () -> Unit,
    onUseDefaultServiceTier: () -> Unit,
    isPlanModeArmed: Boolean,
    onTogglePlanMode: () -> Unit,
    selectedAccessMode: AccessMode,
    onSelectAccessMode: (AccessMode) -> Unit,
    currentGitBranch: String,
    contextWindowUsage: ContextWindowUsage?,
    pendingAttachments: List<ImageAttachment>,
    onRemoveAttachment: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(
        color = ComposerBg,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            // ── Composer card (input + bottom bar wrapped together) ──
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    // Attachment preview strip (inside the card)
                    if (pendingAttachments.isNotEmpty()) {
                        AttachmentPreviewStrip(
                            attachments = pendingAttachments,
                            onRemove = onRemoveAttachment,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Text input
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 32.dp, max = 120.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        if (draftMessage.isEmpty()) {
                            Text(
                                text = "Message...",
                                style = TextStyle(fontSize = 15.sp, color = InkTertiary),
                            )
                        }
                        BasicTextField(
                            value = draftMessage,
                            onValueChange = onDraftChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
                            cursorBrush = SolidColor(Ink),
                            maxLines = 4,
                        )
                    }

                    // Bottom bar: [+] [Model ▼] [Reasoning ▼]  Spacer  [Stop?] [Send]
                    ComposerBottomBar(
                        isLive = isLive,
                        isBusy = isBusy,
                        isSending = isSending,
                        isStopping = isStopping,
                        canStop = canStop,
                        canContinue = canContinue,
                        isContinuing = isContinuing,
                        draftMessage = draftMessage,
                        availableModels = availableModels,
                        isLoadingModels = isLoadingModels,
                        selectedModelId = selectedModelId,
                        selectedModelTitle = selectedModelTitle,
                        selectedReasoningTitle = selectedReasoningTitle,
                        currentReasoningOptions = currentReasoningOptions,
                        effectiveReasoningEffort = effectiveReasoningEffort,
                        selectedReasoningEffort = selectedReasoningEffort,
                        selectedServiceTier = selectedServiceTier,
                        isReasoningOverrideActive = isReasoningOverrideActive,
                        isServiceTierOverrideActive = isServiceTierOverrideActive,
                        onSelectModel = onSelectModel,
                        onSelectReasoning = onSelectReasoning,
                        onSelectServiceTier = onSelectServiceTier,
                        onUseDefaultReasoning = onUseDefaultReasoning,
                        onUseDefaultServiceTier = onUseDefaultServiceTier,
                        isPlanModeArmed = isPlanModeArmed,
                        onTogglePlanMode = onTogglePlanMode,
                        hasPendingAttachments = pendingAttachments.isNotEmpty(),
                        onPickFromGallery = onPickFromGallery,
                        onTakePhoto = onTakePhoto,
                        onSend = onSend,
                        onStop = onStop,
                        onContinue = onContinue,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Secondary bar (outside the card, like iOS) ──
            ComposerSecondaryBar(
                selectedAccessMode = selectedAccessMode,
                onSelectAccessMode = onSelectAccessMode,
                currentGitBranch = currentGitBranch,
                contextWindowUsage = contextWindowUsage,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Bottom Bar (iOS ComposerBottomBar equivalent)
// ──────────────────────────────────────────────────────────────

@Composable
private fun ComposerBottomBar(
    isLive: Boolean,
    isBusy: Boolean,
    isSending: Boolean,
    isStopping: Boolean,
    canStop: Boolean,
    canContinue: Boolean,
    isContinuing: Boolean,
    draftMessage: String,
    availableModels: List<ModelOption>,
    isLoadingModels: Boolean,
    selectedModelId: String?,
    selectedModelTitle: String,
    selectedReasoningTitle: String,
    currentReasoningOptions: List<ReasoningDisplayOption>,
    effectiveReasoningEffort: String,
    selectedReasoningEffort: String?,
    selectedServiceTier: ServiceTier?,
    isReasoningOverrideActive: Boolean,
    isServiceTierOverrideActive: Boolean,
    onSelectModel: (String?) -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (ServiceTier?) -> Unit,
    onUseDefaultReasoning: () -> Unit,
    onUseDefaultServiceTier: () -> Unit,
    isPlanModeArmed: Boolean,
    onTogglePlanMode: () -> Unit,
    hasPendingAttachments: Boolean,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
) {
    val shouldSend = draftMessage.isNotBlank() || hasPendingAttachments

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Attachment menu (+)
        AttachmentMenuButton(
            isPlanModeArmed = isPlanModeArmed,
            onTogglePlanMode = onTogglePlanMode,
            onPickFromGallery = onPickFromGallery,
            onTakePhoto = onTakePhoto,
        )

        // Model selector
        ModelSelectorLabel(
            availableModels = availableModels,
            isLoadingModels = isLoadingModels,
            selectedModelId = selectedModelId,
            selectedModelTitle = selectedModelTitle,
            selectedServiceTier = selectedServiceTier,
            onSelectModel = onSelectModel,
        )

        // Reasoning selector
        ReasoningSelectorLabel(
            selectedReasoningTitle = selectedReasoningTitle,
            currentReasoningOptions = currentReasoningOptions,
            effectiveReasoningEffort = effectiveReasoningEffort,
            selectedReasoningEffort = selectedReasoningEffort,
            selectedServiceTier = selectedServiceTier,
            isReasoningOverrideActive = isReasoningOverrideActive,
            isServiceTierOverrideActive = isServiceTierOverrideActive,
            onSelectReasoning = onSelectReasoning,
            onSelectServiceTier = onSelectServiceTier,
            onUseDefaultReasoning = onUseDefaultReasoning,
            onUseDefaultServiceTier = onUseDefaultServiceTier,
        )

        if (isPlanModeArmed) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Checklist,
                    contentDescription = null,
                    tint = Copper,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Plan",
                    style = MaterialTheme.typography.labelMedium,
                    color = Copper,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Stop button
        if (canStop && isLive) {
            IconButton(
                onClick = onStop,
                enabled = !isBusy,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Ink,
                    contentColor = Color.White,
                ),
            ) {
                if (isStopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        // Send / Continue button
        IconButton(
            onClick = if (shouldSend) onSend else onContinue,
            enabled = isLive && !isBusy && (shouldSend || canContinue),
            modifier = Modifier.size(32.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Ink,
                contentColor = Color.White,
                disabledContainerColor = Ink.copy(alpha = 0.12f),
                disabledContentColor = InkTertiary,
            ),
        ) {
            if (isSending || isContinuing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = if (shouldSend) "Send" else "Continue",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Secondary Bar (iOS TurnComposerSecondaryBar equivalent)
// ──────────────────────────────────────────────────────────────

@Composable
private fun ComposerSecondaryBar(
    selectedAccessMode: AccessMode,
    onSelectAccessMode: (AccessMode) -> Unit,
    currentGitBranch: String,
    contextWindowUsage: ContextWindowUsage?,
) {
    val metaColor = InkTertiary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Runtime picker: [laptop] Local [▼]
        RuntimePickerPill(metaColor = metaColor)

        // Access mode: [shield] [▼]
        AccessModePill(
            selectedAccessMode = selectedAccessMode,
            onSelectAccessMode = onSelectAccessMode,
            metaColor = metaColor,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Git branch: [branch icon] main [▼]
        GitBranchPill(
            currentGitBranch = currentGitBranch,
            metaColor = metaColor,
        )

        // Context window usage ring
        ContextWindowRing(
            usage = contextWindowUsage,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Attachment menu button
// ──────────────────────────────────────────────────────────────

@Composable
private fun AttachmentMenuButton(
    isPlanModeArmed: Boolean,
    onTogglePlanMode: () -> Unit,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Attach",
                tint = InkSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White),
        ) {
            DropdownMenuItem(
                text = { Text(if (isPlanModeArmed) "Disable Plan Mode" else "Plan Mode") },
                onClick = {
                    expanded = false
                    onTogglePlanMode()
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Checklist, null, Modifier.size(20.dp))
                },
                trailingIcon = if (isPlanModeArmed) {
                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Copper) }
                } else {
                    null
                },
                colors = MenuDefaults.itemColors(
                    textColor = if (isPlanModeArmed) Ink else InkSecondary,
                    leadingIconColor = if (isPlanModeArmed) Copper else InkSecondary,
                    trailingIconColor = Copper,
                ),
            )
            HorizontalDivider(color = Divider)
            DropdownMenuItem(
                text = { Text("Photo Library") },
                onClick = { expanded = false; onPickFromGallery() },
                leadingIcon = {
                    Icon(Icons.Default.Image, null, Modifier.size(20.dp))
                },
                colors = MenuDefaults.itemColors(
                    textColor = Ink,
                    leadingIconColor = InkSecondary,
                ),
            )
            DropdownMenuItem(
                text = { Text("Take a Photo") },
                onClick = { expanded = false; onTakePhoto() },
                leadingIcon = {
                    Icon(Icons.Default.CameraAlt, null, Modifier.size(20.dp))
                },
                colors = MenuDefaults.itemColors(
                    textColor = Ink,
                    leadingIconColor = InkSecondary,
                ),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Attachment preview strip
// ──────────────────────────────────────────────────────────────

@Composable
private fun AttachmentPreviewStrip(
    attachments: List<ImageAttachment>,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Box(modifier = Modifier.size(64.dp)) {
                AttachmentThumbnailTile(
                    attachment = attachment,
                    contentDescription = "Attachment",
                    renderPreference = AttachmentRenderPreference.ComposerPreview,
                    modifier = Modifier.size(64.dp),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clickable { onRemove(attachment.id) },
                    shape = CircleShape,
                    color = ErrorRed,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

private fun attachmentUiModel(
    attachment: ImageAttachment,
    renderPreference: AttachmentRenderPreference,
): Any? {
    val source = resolveRenderableAttachmentSource(
        attachment = attachment,
        preference = renderPreference,
    ) ?: return null
    return when {
        source.startsWith("content://", ignoreCase = true)
            || source.startsWith("file://", ignoreCase = true) -> Uri.parse(source)
        source.startsWith("data:image", ignoreCase = true) -> {
            // Coil cannot render data URIs as strings — decode to ByteArray.
            val commaIndex = source.indexOf(',')
            if (commaIndex >= 0) {
                android.util.Base64.decode(
                    source.substring(commaIndex + 1),
                    android.util.Base64.DEFAULT,
                )
            } else {
                source
            }
        }
        else -> source
    }
}

@Composable
private fun AttachmentThumbnailTile(
    attachment: ImageAttachment,
    contentDescription: String,
    renderPreference: AttachmentRenderPreference,
    modifier: Modifier = Modifier,
) {
    val imageModel = remember(attachment, renderPreference) {
        attachmentUiModel(
            attachment = attachment,
            renderPreference = renderPreference,
        )
    }
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFFF7F1E7))
            .border(width = 1.dp, color = Divider, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = contentDescription,
                tint = InkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Model selector label (matches iOS composerMenuLabel style)
// ──────────────────────────────────────────────────────────────

@Composable
private fun ModelSelectorLabel(
    availableModels: List<ModelOption>,
    isLoadingModels: Boolean,
    selectedModelId: String?,
    selectedModelTitle: String,
    selectedServiceTier: ServiceTier?,
    onSelectModel: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val metaColor = InkTertiary

    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Speed bolt icon when fast tier selected
            if (selectedServiceTier != null) {
                Text("⚡", fontSize = 11.sp)
            }
            Text(
                text = selectedModelTitle,
                style = MaterialTheme.typography.labelMedium,
                color = metaColor,
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = metaColor,
                modifier = Modifier.size(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White),
        ) {
            if (isLoadingModels) {
                DropdownMenuItem(
                    text = { Text("Loading models...") },
                    onClick = {},
                    colors = MenuDefaults.itemColors(textColor = InkTertiary),
                )
            } else if (availableModels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No models available") },
                    onClick = {},
                    colors = MenuDefaults.itemColors(textColor = InkTertiary),
                )
            } else {
                availableModels.forEach { model ->
                    val isSelected = model.id == selectedModelId
                    val title = modelDisplayTitle(model)
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = { onSelectModel(model.id); expanded = false },
                        trailingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = if (isSelected) Ink else InkSecondary,
                            trailingIconColor = Copper,
                        ),
                    )
                }
            }
            // "Select model" label at bottom (like iOS)
            HorizontalDivider(color = Divider)
            DropdownMenuItem(
                text = { Text("Select model", color = InkTertiary, style = MaterialTheme.typography.labelSmall) },
                onClick = {},
                enabled = false,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Reasoning selector label
// ──────────────────────────────────────────────────────────────

@Composable
private fun ReasoningSelectorLabel(
    selectedReasoningTitle: String,
    currentReasoningOptions: List<ReasoningDisplayOption>,
    effectiveReasoningEffort: String,
    selectedReasoningEffort: String?,
    selectedServiceTier: ServiceTier?,
    isReasoningOverrideActive: Boolean,
    isServiceTierOverrideActive: Boolean,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (ServiceTier?) -> Unit,
    onUseDefaultReasoning: () -> Unit,
    onUseDefaultServiceTier: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val metaColor = InkTertiary

    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = metaColor,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = selectedReasoningTitle,
                style = MaterialTheme.typography.labelMedium,
                color = metaColor,
            )
            if (isReasoningOverrideActive || isServiceTierOverrideActive) {
                Surface(
                    shape = CircleShape,
                    color = Copper.copy(alpha = 0.12f),
                ) {
                    Box(modifier = Modifier.size(8.dp))
                }
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = metaColor,
                modifier = Modifier.size(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White),
        ) {
            // Speed section first (like iOS)
            Text(
                text = "Speed",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = InkTertiary,
            )
            if (isServiceTierOverrideActive) {
                DropdownMenuItem(
                    text = { Text("Use app default") },
                    onClick = {
                        onUseDefaultServiceTier()
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(textColor = InkSecondary),
                )
            }
            val isFast = selectedServiceTier == ServiceTier.Fast
            DropdownMenuItem(
                text = { Text("Fast") },
                onClick = {
                    onSelectServiceTier(if (isFast) null else ServiceTier.Fast)
                    expanded = false
                },
                trailingIcon = if (isFast) {
                    { Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp)) }
                } else {
                    null
                },
                colors = MenuDefaults.itemColors(
                    textColor = if (isFast) Ink else InkSecondary,
                    trailingIconColor = Copper,
                ),
            )
            val isNormalSpeed = selectedServiceTier == null
            DropdownMenuItem(
                text = { Text("Normal") },
                onClick = { onSelectServiceTier(null); expanded = false },
                trailingIcon = if (isNormalSpeed) {
                    { Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp)) }
                } else {
                    null
                },
                colors = MenuDefaults.itemColors(
                    textColor = if (isNormalSpeed) Ink else InkSecondary,
                    trailingIconColor = Copper,
                ),
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Reasoning section
            Text(
                text = "Reasoning",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = InkTertiary,
            )
            if (isReasoningOverrideActive) {
                DropdownMenuItem(
                    text = { Text("Use app default") },
                    onClick = {
                        onUseDefaultReasoning()
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(textColor = InkSecondary),
                )
            }
            currentReasoningOptions.forEach { option ->
                val isSelected = (selectedReasoningEffort ?: effectiveReasoningEffort) == option.effort
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = { onSelectReasoning(option.effort); expanded = false },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = if (isSelected) Ink else InkSecondary,
                        trailingIconColor = Copper,
                    ),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Runtime picker pill (Local ▼)
// ──────────────────────────────────────────────────────────────

@Composable
private fun RuntimePickerPill(metaColor: Color) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.04f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Laptop,
                contentDescription = null,
                tint = metaColor,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Local",
                style = MaterialTheme.typography.labelMedium,
                color = metaColor,
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = metaColor,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Access mode pill (Shield ▼)
// ──────────────────────────────────────────────────────────────

@Composable
private fun AccessModePill(
    selectedAccessMode: AccessMode,
    onSelectAccessMode: (AccessMode) -> Unit,
    metaColor: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    val isFullAccess = selectedAccessMode == AccessMode.FullAccess
    val pillColor = if (isFullAccess) WarningAmber else metaColor

    Box {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.04f),
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = pillColor,
                    modifier = Modifier.size(14.dp),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = pillColor,
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White),
        ) {
            AccessMode.entries.forEach { mode ->
                val isSelected = selectedAccessMode == mode
                DropdownMenuItem(
                    text = { Text(mode.menuTitle) },
                    onClick = { onSelectAccessMode(mode); expanded = false },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = if (isSelected) Ink else InkSecondary,
                        trailingIconColor = Copper,
                    ),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Git branch pill
// ──────────────────────────────────────────────────────────────

@Composable
private fun GitBranchPill(
    currentGitBranch: String,
    metaColor: Color,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.04f),
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Git branch icon
                Icon(
                    painter = painterResource(id = dev.remodex.android.R.drawable.ic_git_branch),
                    contentDescription = null,
                    tint = metaColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = currentGitBranch,
                    style = MaterialTheme.typography.labelMedium,
                    color = metaColor,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = metaColor,
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White),
        ) {
            // Current branch row
            DropdownMenuItem(
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(currentGitBranch, fontWeight = FontWeight.Medium)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Black.copy(alpha = 0.06f),
                        ) {
                            Text(
                                "Current",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSecondary,
                            )
                        }
                    }
                },
                onClick = { expanded = false },
                trailingIcon = {
                    Icon(Icons.Default.Check, "Current", Modifier.size(16.dp))
                },
                colors = MenuDefaults.itemColors(
                    textColor = Ink,
                    trailingIconColor = Copper,
                ),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Context Window Progress Ring
// ──────────────────────────────────────────────────────────────

@Composable
private fun ContextWindowRing(
    usage: ContextWindowUsage?,
) {
    val percent = usage?.usagePercent ?: 0f
    val remaining = 1f - percent
    val ringColor = when {
        remaining > 0.35f -> InkTertiary
        remaining > 0.15f -> WarningAmber
        else -> ErrorRed
    }
    val label = if (usage != null) "${(remaining * 100).toInt()}" else "--"

    Box(
        modifier = Modifier
            .size(28.dp)
            .drawBehind {
                val strokeWidth = 2.5.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val topLeft = Offset(
                    (size.width - radius * 2) / 2,
                    (size.height - radius * 2) / 2,
                )
                // Background track
                drawArc(
                    color = Color.Black.copy(alpha = 0.06f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // Usage arc
                if (percent > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = remaining * 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = InkSecondary,
        )
    }
}
