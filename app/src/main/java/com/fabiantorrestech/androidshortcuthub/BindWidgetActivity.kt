package com.fabiantorrestech.androidshortcuthub

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.fabiantorrestech.androidshortcuthub.ui.theme.ShortcutHubTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class BindWidgetActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_GRID_ROWS = "grid_rows"
        private const val EXTRA_GRID_COLUMNS = "grid_columns"
        private const val EXTRA_HAS_VOLUME_SLIDER = "has_volume_slider"
        private const val EXTRA_HAS_BRIGHTNESS_SLIDER = "has_brightness_slider"
        private const val EXTRA_AUTO_TOGGLE_OVERLAY = "auto_toggle_overlay"

        private val tileInsertionResults = MutableSharedFlow<TileInsertionEvent>(extraBufferCapacity = 1)

        fun createIntent(
            context: Context,
            gridRows: Int = 8,
            gridColumns: Int = 4,
            hasVolumeSlider: Boolean = false,
            hasBrightnessSlider: Boolean = false,
            autoToggleOverlay: Boolean = true,
        ): Intent {
            return Intent(context, BindWidgetActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_GRID_ROWS, gridRows)
                putExtra(EXTRA_GRID_COLUMNS, gridColumns)
                putExtra(EXTRA_HAS_VOLUME_SLIDER, hasVolumeSlider)
                putExtra(EXTRA_HAS_BRIGHTNESS_SLIDER, hasBrightnessSlider)
                putExtra(EXTRA_AUTO_TOGGLE_OVERLAY, autoToggleOverlay)
            }
        }

        internal fun tileInsertionEvents(): MutableSharedFlow<TileInsertionEvent> = tileInsertionResults
    }

    private val widgetHost by lazy { ShortcutHubWidgetHost.getInstance(this) }
    private val appWidgetManager by lazy { AppWidgetManager.getInstance(this) }

    private var pendingAppWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var hasCompletedFlow = false
    private val autoToggleOverlay by lazy { intent.getBooleanExtra(EXTRA_AUTO_TOGGLE_OVERLAY, true) }

    private val bindWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val appWidgetId = extractAppWidgetId(result.data)
        if (result.resultCode == Activity.RESULT_OK && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            openConfigureIfNeeded(appWidgetId)
        } else {
            cleanupAndFinish(appWidgetId)
        }
    }

    private val configureWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val appWidgetId = extractAppWidgetId(result.data)
        if (result.resultCode == Activity.RESULT_OK && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            emitResultAndFinish(appWidgetId)
        } else {
            cleanupAndFinish(appWidgetId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gridRows = intent.getIntExtra(EXTRA_GRID_ROWS, 8)
        val gridColumns = intent.getIntExtra(EXTRA_GRID_COLUMNS, 4)
        val hasVolumeSlider = intent.getBooleanExtra(EXTRA_HAS_VOLUME_SLIDER, false)
        val hasBrightnessSlider = intent.getBooleanExtra(EXTRA_HAS_BRIGHTNESS_SLIDER, false)
        setContent {
            ShortcutHubTheme {
                WidgetPickerScreen(
                    providers = loadInstalledProviders(),
                    gridRows = gridRows,
                    gridColumns = gridColumns,
                    hasVolumeSlider = hasVolumeSlider,
                    hasBrightnessSlider = hasBrightnessSlider,
                    onProviderSelected = ::startBindingFlow,
                    onSystemSliderConfirmed = ::emitSystemSliderAndFinish,
                    onCancel = ::cancelAndFinish,
                )
            }
        }
    }

    override fun onDestroy() {
        if (!hasCompletedFlow && pendingAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            cleanupAppWidgetId(pendingAppWidgetId)
        }
        super.onDestroy()
    }

    private fun loadInstalledProviders(): List<AppWidgetProviderInfo> =
        appWidgetManager.installedProviders
            .sortedWith(
                compareBy<AppWidgetProviderInfo> { it.loadLabel(packageManager).orEmpty().lowercase() }
                    .thenBy { it.provider.flattenToShortString() },
            )

    private fun startBindingFlow(providerInfo: AppWidgetProviderInfo) {
        cleanupAppWidgetId(pendingAppWidgetId)
        pendingAppWidgetId = widgetHost.allocateAppWidgetId()

        val provider = providerInfo.provider
        val didBind = appWidgetManager.bindAppWidgetIdIfAllowed(pendingAppWidgetId, provider)
        if (didBind) {
            openConfigureIfNeeded(pendingAppWidgetId)
            return
        }

        bindWidgetLauncher.launch(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingAppWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            },
        )
    }

    private fun openConfigureIfNeeded(appWidgetId: Int) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (providerInfo == null) { cleanupAndFinish(appWidgetId); return }
        val configureComponent = providerInfo.configure
        if (configureComponent == null) { emitResultAndFinish(appWidgetId); return }
        configureWidgetLauncher.launch(
            Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = configureComponent
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            },
        )
    }

    private fun emitResultAndFinish(appWidgetId: Int) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        val providerComponent = providerInfo?.provider?.flattenToString()
        if (providerComponent == null) { cleanupAndFinish(appWidgetId); return }
        val event = TileInsertionEvent.WidgetAdded(
            WidgetTileSelection(appWidgetId = appWidgetId, providerComponent = providerComponent),
        )
        WidgetBindingCoordinator.completeInsertion(event)
        tileInsertionResults.tryEmit(event)
        hasCompletedFlow = true
        pendingAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (autoToggleOverlay) routeShortcutHubToggle(this)
        finish()
    }

    private fun emitSystemSliderAndFinish(config: SystemSliderConfig, rowSpan: Int, columnSpan: Int) {
        val event = TileInsertionEvent.SystemSliderAdded(config = config, rowSpan = rowSpan, columnSpan = columnSpan)
        WidgetBindingCoordinator.completeInsertion(event)
        tileInsertionResults.tryEmit(event)
        hasCompletedFlow = true
        pendingAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (autoToggleOverlay) routeShortcutHubToggle(this)
        finish()
    }

    private fun cancelAndFinish() {
        WidgetBindingCoordinator.clear()
        cleanupAndFinish(pendingAppWidgetId)
    }

    private fun cleanupAndFinish(appWidgetId: Int) {
        cleanupAppWidgetId(appWidgetId)
        hasCompletedFlow = true
        pendingAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        WidgetBindingCoordinator.clear()
        finish()
    }

    private fun cleanupAppWidgetId(appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        WidgetViewCache.remove(appWidgetId)
        widgetHost.deleteAppWidgetId(appWidgetId)
    }

    private fun extractAppWidgetId(intent: Intent?): Int =
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingAppWidgetId) ?: pendingAppWidgetId
}

// ── Data ─────────────────────────────────────────────────────────────────────

private data class WidgetGroup(
    val appLabel: String,
    val packageName: String,
    val providers: List<AppWidgetProviderInfo>,
)

// ── Image helpers ─────────────────────────────────────────────────────────────

@Composable
private fun rememberAppIcon(packageName: String): Painter? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).asBitmapPainter() }.getOrNull()
    }
}

@Composable
private fun rememberWidgetPreview(provider: AppWidgetProviderInfo): Painter? {
    val context = LocalContext.current
    return remember(provider.provider.flattenToString()) {
        if (provider.previewImage == 0) return@remember null
        runCatching {
            context.packageManager.getDrawable(
                provider.provider.packageName,
                provider.previewImage,
                null,
            )?.asBitmapPainter()
        }.getOrNull()
    }
}

// ── Composables ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetPickerScreen(
    providers: List<AppWidgetProviderInfo>,
    gridRows: Int,
    gridColumns: Int,
    hasVolumeSlider: Boolean,
    hasBrightnessSlider: Boolean,
    onProviderSelected: (AppWidgetProviderInfo) -> Unit,
    onSystemSliderConfirmed: (SystemSliderConfig, Int, Int) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var configuringSliderType by remember { mutableStateOf<SliderType?>(null) }

    val pendingSliderType = configuringSliderType
    if (pendingSliderType != null) {
        SystemWidgetConfigScreen(
            sliderType = pendingSliderType,
            gridRows = gridRows,
            gridColumns = gridColumns,
            onConfirm = { config, rowSpan, colSpan -> onSystemSliderConfirmed(config, rowSpan, colSpan) },
            onBack = { configuringSliderType = null },
        )
        return
    }

    val context = LocalContext.current
    val packageManager = context.packageManager
    var searchQuery by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf<ComponentName?>(null) }

    val groups: List<WidgetGroup> = remember(providers) {
        providers
            .groupBy { it.provider.packageName }
            .map { (pkg, provs) ->
                val appLabel = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0),
                    ).toString()
                }.getOrDefault(pkg)
                WidgetGroup(
                    appLabel = appLabel,
                    packageName = pkg,
                    providers = provs.sortedBy { it.loadLabel(packageManager).orEmpty().lowercase() },
                )
            }
            .sortedBy { it.appLabel.lowercase() }
    }

    val filteredGroups: List<WidgetGroup> = remember(groups, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) return@remember groups
        groups.mapNotNull { group ->
            val matched = group.providers.filter { provider ->
                group.appLabel.contains(query, ignoreCase = true) ||
                    provider.loadLabel(packageManager).orEmpty().contains(query, ignoreCase = true) ||
                    group.packageName.contains(query, ignoreCase = true)
            }
            if (matched.isEmpty()) null else group.copy(providers = matched)
        }
    }

    val listState = rememberLazyListState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Add Tile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }

            // Tab row
            androidx.compose.material3.TabRow(selectedTabIndex = selectedTab) {
                androidx.compose.material3.Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("App Widgets") },
                )
                androidx.compose.material3.Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("System Widgets") },
                )
            }

            when (selectedTab) {
                0 -> {
                    // App widgets tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search widgets") },
                            singleLine = true,
                        )
                        Text(
                            text = "${filteredGroups.sumOf { it.providers.size }} widgets",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(end = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                filteredGroups.forEach { group ->
                                    stickyHeader(key = "header_${group.packageName}") {
                                        AppGroupHeader(
                                            appLabel = group.appLabel,
                                            packageName = group.packageName,
                                        )
                                    }
                                    items(
                                        items = group.providers,
                                        key = { it.provider.flattenToString() },
                                    ) { provider ->
                                        WidgetProviderItem(
                                            provider = provider,
                                            isSelected = selectedProvider == provider.provider,
                                            onClick = {
                                                selectedProvider = provider.provider
                                                onProviderSelected(provider)
                                            },
                                        )
                                    }
                                }
                            }
                            VerticalScrollbar(
                                state = listState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                1 -> {
                    SystemWidgetsTab(
                        hasVolumeSlider = hasVolumeSlider,
                        hasBrightnessSlider = hasBrightnessSlider,
                        onSelect = { sliderType -> configuringSliderType = sliderType },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemWidgetsTab(
    hasVolumeSlider: Boolean,
    hasBrightnessSlider: Boolean,
    onSelect: (SliderType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "System controls built into this app",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SystemWidgetItem(
            title = "Volume Slider",
            subtitle = "Control media, ring, alarm, or notification volume",
            disabled = hasVolumeSlider,
            disabledReason = "Already on grid",
            onClick = { onSelect(SliderType.VOLUME) },
        )
        SystemWidgetItem(
            title = "Brightness Slider",
            subtitle = "Control screen brightness",
            disabled = hasBrightnessSlider,
            disabledReason = "Already on grid",
            onClick = { onSelect(SliderType.BRIGHTNESS) },
        )
    }
}

@Composable
private fun SystemWidgetItem(
    title: String,
    subtitle: String,
    disabled: Boolean,
    disabledReason: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (!disabled) it.clickable(onClick = onClick) else it },
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (disabled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (disabled) disabledReason else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (disabled) 0.6f else 1f),
                )
            }
            if (!disabled) {
                Text(
                    text = "→",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SystemWidgetConfigScreen(
    sliderType: SliderType,
    gridRows: Int,
    gridColumns: Int,
    onConfirm: (SystemSliderConfig, Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    var streamMode by remember { mutableStateOf(StreamMode.DEFAULT) }
    var singleStream by remember { mutableStateOf(AudioStreamType.MUSIC) }
    var buttonPlacement by remember { mutableStateOf(SliderButtonPlacement.SPLIT) }
    var notchMode by remember { mutableStateOf(SliderNotchMode.LOCK_AND_SLIDE) }
    var showNotches by remember { mutableStateOf(true) }
    var showOutline by remember { mutableStateOf(false) }
    var buttonHapticsEnabled by remember { mutableStateOf(false) }
    var notchHapticsEnabled by remember { mutableStateOf(false) }
    var rowSpan by remember { mutableIntStateOf(3.coerceAtMost(gridRows)) }
    var columnSpan by remember { mutableIntStateOf(1.coerceAtMost(gridColumns)) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("← Back") }
                Text(
                    text = if (sliderType == SliderType.VOLUME) "Volume Slider" else "Brightness Slider",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            SliderConfigControls(
                config = SystemSliderConfig(
                    sliderType = sliderType,
                    streamMode = streamMode,
                    singleStream = singleStream,
                    buttonPlacement = buttonPlacement,
                    notchMode = notchMode,
                    showNotches = showNotches,
                    showOutline = showOutline,
                    buttonHapticsEnabled = buttonHapticsEnabled,
                    notchHapticsEnabled = notchHapticsEnabled,
                ),
                onConfigChange = { updated ->
                    streamMode = updated.streamMode
                    singleStream = updated.singleStream
                    buttonPlacement = updated.buttonPlacement
                    notchMode = updated.notchMode
                    showNotches = updated.showNotches
                    showOutline = updated.showOutline
                    buttonHapticsEnabled = updated.buttonHapticsEnabled
                    notchHapticsEnabled = updated.notchHapticsEnabled
                },
            )

            // Size picker
            Text("Size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rows: $rowSpan", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { if (rowSpan > 1) rowSpan-- }) { Text("-") }
                OutlinedButton(onClick = { if (rowSpan < gridRows) rowSpan++ }) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Columns: $columnSpan", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { if (columnSpan > 1) columnSpan-- }) { Text("-") }
                OutlinedButton(onClick = { if (columnSpan < gridColumns) columnSpan++ }) { Text("+") }
            }

            androidx.compose.material3.Button(
                onClick = {
                    onConfirm(
                        SystemSliderConfig(
                            sliderType = sliderType,
                            streamMode = streamMode,
                            singleStream = singleStream,
                            buttonPlacement = buttonPlacement,
                            notchMode = notchMode,
                            showNotches = showNotches,
                            showOutline = showOutline,
                            buttonHapticsEnabled = buttonHapticsEnabled,
                            notchHapticsEnabled = notchHapticsEnabled,
                        ),
                        rowSpan,
                        columnSpan,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add ${if (sliderType == SliderType.VOLUME) "Volume" else "Brightness"} Slider")
            }
        }
    }
}

@Composable
private fun AppGroupHeader(appLabel: String, packageName: String) {
    val icon = rememberAppIcon(packageName = packageName)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Image(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                text = appLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun WidgetProviderItem(
    provider: AppWidgetProviderInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val preview = rememberWidgetPreview(provider = provider)
    val context = LocalContext.current
    val label = provider.loadLabel(context.packageManager).orEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        painter = preview,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            ),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label.ifBlank { provider.provider.className.substringAfterLast('.') },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    text = provider.provider.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                provider.configure?.let { configureComponent ->
                    Text(
                        text = "Config: ${configureComponent.className.substringAfterLast('.')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    // Recompute only when layout changes, not on every recomposition
    val thumbState by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val totalItems = info.totalItemsCount
            val visibleItems = info.visibleItemsInfo
            if (totalItems == 0 || visibleItems.isEmpty()) return@derivedStateOf null
            val viewportSize = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
            val estimatedTotal = avgItemSize * totalItems
            val sizeFraction = (viewportSize / estimatedTotal).coerceIn(0.06f, 1f)
            if (sizeFraction >= 1f) return@derivedStateOf null
            val firstFraction = (state.firstVisibleItemIndex +
                state.firstVisibleItemScrollOffset / avgItemSize) / totalItems
            val offsetFraction = (firstFraction * (1f - sizeFraction)).coerceIn(0f, 1f - sizeFraction)
            offsetFraction to sizeFraction
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .width(6.dp)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun jumpTo(y: Float) {
                        val totalItems = state.layoutInfo.totalItemsCount
                        if (totalItems == 0) return
                        val fraction = (y / size.height.toFloat()).coerceIn(0f, 1f)
                        val idx = (fraction * totalItems).toInt().coerceIn(0, totalItems - 1)
                        coroutineScope.launch { state.scrollToItem(idx) }
                    }
                    jumpTo(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        jumpTo(change.position.y)
                    }
                }
            },
    ) {
        val data = thumbState
        if (data != null) {
            val (offsetFraction, sizeFraction) = data
            // Track
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        RoundedCornerShape(3.dp),
                    ),
            )
            // Thumb
            Box(
                modifier = Modifier
                    .offset(y = maxHeight * offsetFraction)
                    .width(6.dp)
                    .height(maxHeight * sizeFraction)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}
