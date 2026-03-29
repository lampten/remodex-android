package dev.remodex.android.app

import android.app.Activity
import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.remodex.android.ui.theme.Divider
import dev.remodex.android.ui.theme.SidebarBg
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val expandedSidebarWidth = 320.dp
private val compactSidebarWidth = 300.dp
private val dragHandleWidth = 12.dp
private val sidebarCollapseThreshold = 100.dp

private const val PREF_SPLIT_SIDEBAR_VISIBLE = "split_sidebar_visible"

private fun readSplitSidebarPreference(context: Context): Boolean {
    val prefs = context.getSharedPreferences("dev.remodex.android.layout", Context.MODE_PRIVATE)
    return prefs.getBoolean(PREF_SPLIT_SIDEBAR_VISIBLE, true)
}

private fun writeSplitSidebarPreference(context: Context, visible: Boolean) {
    val prefs = context.getSharedPreferences("dev.remodex.android.layout", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(PREF_SPLIT_SIDEBAR_VISIBLE, visible).apply()
}

/**
 * Adaptive breakpoint aligned with Android WindowSizeClass guidance:
 * - Compact: < 600dp
 * - Medium: 600dp–839dp
 * - Expanded: >= 840dp
 *
 * The vivo foldable inner display is ~676dp, which falls in the Medium range.
 * We use 600dp as the breakpoint so the vivo inner display gets the expanded
 * side-by-side layout, consistent with the existing behavior and the research
 * finding that 676dp is wide enough for a sidebar + content split.
 */
private val expandedWindowBreakpoint = 600.dp

@Composable
fun AppDrawerLayout(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // Observe folding features for hinge/fold awareness
    val context = LocalContext.current
    val activity = context as? Activity
    val foldingFeatures by remember(activity) {
        if (activity != null) {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .map { layoutInfo ->
                    layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>()
                }
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    // Detect if we have a separating fold in half-opened state (tabletop/book posture)
    val separatingFold = foldingFeatures.firstOrNull { fold ->
        fold.isSeparating
    }

    val systemBarInsets = WindowInsets.systemBars.asPaddingValues()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= expandedWindowBreakpoint
        val shouldForceSidebarVisible = vm.currentScreen != AppScreen.Home || vm.selectedThreadId == null

        LaunchedEffect(isExpanded) {
            vm.updateDrawerVisibility(isExpanded)
        }

        if (isExpanded) {
            // Gesture-controlled split: user can drag the divider to collapse/restore sidebar
            var isSplitSidebarVisible by rememberSaveable {
                mutableStateOf(readSplitSidebarPreference(context))
            }
            val showExpandedSidebar = isSplitSidebarVisible || shouldForceSidebarVisible

            // Sync ViewModel drawer state with local split state
            LaunchedEffect(showExpandedSidebar) {
                vm.updateDrawerVisibility(showExpandedSidebar)
            }

            // If there's a vertical separating fold, adjust sidebar width to avoid
            // placing content across the fold/hinge area.
            val baseSidebarWidth = if (separatingFold != null &&
                separatingFold.orientation == FoldingFeature.Orientation.VERTICAL
            ) {
                val foldLeftDp = with(LocalDensity.current) {
                    separatingFold.bounds.left.toDp()
                }
                foldLeftDp.coerceIn(240.dp, 400.dp)
            } else {
                expandedSidebarWidth
            }

            // Track drag offset for the sidebar divider
            val density = LocalDensity.current
            var dragAccumulatorDp by remember { mutableStateOf(0.dp) }
            val targetSidebarWidth: Dp = if (showExpandedSidebar) baseSidebarWidth else 0.dp
            val animatedSidebarWidth by animateDpAsState(
                targetValue = targetSidebarWidth,
                animationSpec = tween(durationMillis = 250),
                label = "sidebarWidth",
            )

            Row(modifier = Modifier.fillMaxSize()) {
                if (animatedSidebarWidth > 0.dp) {
                    Row(
                        modifier = Modifier
                            .width(animatedSidebarWidth + dragHandleWidth)
                            .fillMaxHeight(),
                    ) {
                        SidebarContent(
                            threads = vm.threadSummaries,
                            selectedThreadId = vm.selectedThreadId,
                            connectionPhase = vm.bridge.phase,
                            deviceName = vm.bridge.deviceName,
                            isCreatingThread = vm.isCreatingThread,
                            onSelectThread = { vm.selectThread(it) },
                            onCreateThread = { preferredProjectPath ->
                                vm.startNewThread(preferredProjectPath)
                            },
                            onRenameThread = { id, title -> vm.renameThread(id, title) },
                            onArchiveThread = { vm.archiveThread(it) },
                            onDeleteThread = { vm.deleteThread(it) },
                            onOpenSettings = { vm.openSettings() },
                            onOpenDevices = { vm.openDevices() },
                            modifier = Modifier
                                .width(animatedSidebarWidth)
                                .background(SidebarBg)
                                .padding(
                                    top = systemBarInsets.calculateTopPadding(),
                                    bottom = systemBarInsets.calculateBottomPadding(),
                                ),
                        )
                        // Draggable divider handle
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .width(dragHandleWidth)
                                .fillMaxHeight()
                                .background(Divider.copy(alpha = 0.5f))
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragAccumulatorDp = 0.dp },
                                        onDragEnd = {
                                            if (dragAccumulatorDp < -(sidebarCollapseThreshold)) {
                                                isSplitSidebarVisible = false
                                                writeSplitSidebarPreference(context, false)
                                            }
                                            dragAccumulatorDp = 0.dp
                                        },
                                        onHorizontalDrag = { _, dragAmount ->
                                            dragAccumulatorDp += with(density) { dragAmount.toDp() }
                                        },
                                    )
                                },
                        )
                    }
                }

                MainContent(
                    vm = vm,
                    onOpenDrawer = {
                        if (!showExpandedSidebar) {
                            isSplitSidebarVisible = true
                            writeSplitSidebarPreference(context, true)
                        } else {
                            isSplitSidebarVisible = false
                            writeSplitSidebarPreference(context, false)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(showExpandedSidebar) {
                            if (!showExpandedSidebar) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragAccumulatorDp = 0.dp },
                                    onDragEnd = {
                                        if (dragAccumulatorDp > sidebarCollapseThreshold) {
                                            isSplitSidebarVisible = true
                                            writeSplitSidebarPreference(context, true)
                                        }
                                        dragAccumulatorDp = 0.dp
                                    },
                                    onHorizontalDrag = { _, dragAmount ->
                                        dragAccumulatorDp += with(density) { dragAmount.toDp() }
                                    },
                                )
                            }
                        }
                        .padding(
                            top = systemBarInsets.calculateTopPadding(),
                            bottom = systemBarInsets.calculateBottomPadding(),
                        ),
                )
            }
        } else {
            // Modal drawer for compact layouts
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

            LaunchedEffect(vm.isDrawerOpen) {
                if (vm.isDrawerOpen) drawerState.open() else drawerState.close()
            }
            LaunchedEffect(drawerState.currentValue) {
                vm.updateDrawerVisibility(drawerState.currentValue == DrawerValue.Open)
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(compactSidebarWidth),
                        drawerContainerColor = SidebarBg,
                        windowInsets = WindowInsets(0),
                    ) {
                        SidebarContent(
                            threads = vm.threadSummaries,
                            selectedThreadId = vm.selectedThreadId,
                            connectionPhase = vm.bridge.phase,
                            deviceName = vm.bridge.deviceName,
                            isCreatingThread = vm.isCreatingThread,
                            onSelectThread = {
                                vm.selectThread(it)
                                vm.updateDrawerVisibility(false)
                            },
                            onCreateThread = { preferredProjectPath ->
                                vm.startNewThread(preferredProjectPath)
                                vm.updateDrawerVisibility(false)
                            },
                            onRenameThread = { id, title -> vm.renameThread(id, title) },
                            onArchiveThread = { vm.archiveThread(it) },
                            onDeleteThread = { vm.deleteThread(it) },
                            onOpenSettings = {
                                vm.openSettings()
                                vm.updateDrawerVisibility(false)
                            },
                            onOpenDevices = {
                                vm.openDevices()
                                vm.updateDrawerVisibility(false)
                            },
                            modifier = Modifier.padding(
                                top = systemBarInsets.calculateTopPadding(),
                                bottom = systemBarInsets.calculateBottomPadding(),
                            ),
                        )
                    }
                },
            ) {
                MainContent(
                    vm = vm,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                        vm.updateDrawerVisibility(true)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = systemBarInsets.calculateTopPadding(),
                            bottom = systemBarInsets.calculateBottomPadding(),
                        ),
                )
            }
        }
    }
}
