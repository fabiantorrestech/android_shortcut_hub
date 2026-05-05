package com.fabiantorrestech.androidshortcuthub

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
// CardDefaults is now used in OverlayPreview.kt; keeping import avoids compilation warnings in overlapping usages
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

// ── Shared constants ─────────────────────────────────────────────────────────

internal const val OVERLAY_PREFS_NAME = "shortcut_hub_overlay"
internal const val OVERLAY_PREFS_KEY_STATE = "overlay_state"
internal const val TILE_TYPE_APP = "app"
internal const val TILE_TYPE_INTENT = "intent"
internal const val TILE_TYPE_WIDGET = "widget"
internal const val TILE_TYPE_SYSTEM_SLIDER = "system_slider"
internal const val TEXT_SCALE_STEP = 0.1f
internal const val TEXT_SCALE_MIN = 0.5f
internal const val TEXT_SCALE_MAX = 3.0f
internal const val ICON_SCALE_STEP = 0.15f
internal const val ICON_SCALE_MIN = 0.5f
internal const val ICON_SCALE_MAX = 3.0f
internal const val PANEL_HANDLE_SIZE_DP = 44
internal const val DPAD_SIZE_DP = 156
internal const val DPAD_BUTTON_SIZE_DP = 40

// ── Data model ───────────────────────────────────────────────────────────────

internal data class LaunchableApp(
    val label: String,
    val componentName: ComponentName? = null,
    val launchIntentUri: String? = null,
    val launchIntentPackage: String? = null,
) {
    val packageName: String get() = componentName?.packageName ?: launchIntentPackage.orEmpty()
}

internal sealed class TileState {
    abstract val id: Int
    abstract val row: Int
    abstract val column: Int
    abstract val rowSpan: Int
    abstract val columnSpan: Int
    abstract val customLabel: String?
    abstract val customFontUri: String?
    abstract val customFontName: String?
    abstract val customTextScale: Float?
    abstract val customBoldText: Boolean?
    abstract val displayLabel: String
}

internal data class AppTileState(
    override val id: Int,
    override val row: Int,
    override val column: Int,
    override val rowSpan: Int = 1,
    override val columnSpan: Int = 1,
    val app: LaunchableApp,
    val iconConfig: AppTileIconConfig = AppTileIconConfig(),
    override val customLabel: String? = null,
    override val customFontUri: String? = null,
    override val customFontName: String? = null,
    override val customTextScale: Float? = null,
    override val customBoldText: Boolean? = null,
) : TileState() {
    override val displayLabel: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: app.label
}

internal enum class AppTileIconSource { NONE, APP, MATERIAL, CUSTOM }

internal enum class AppTileContentPlacement { TOP, LEFT, BOTTOM, RIGHT }

internal data class AppTileIconConfig(
    val source: AppTileIconSource = AppTileIconSource.NONE,
    val grayscale: Boolean = false,
    val showLabel: Boolean = true,
    val placement: AppTileContentPlacement = AppTileContentPlacement.TOP,
    val materialIconKey: String? = null,
    val customIconUri: String? = null,
    val customIconName: String? = null,
    val iconScale: Float? = null,
)

internal enum class IntentType { ACTIVITY, BROADCAST_RECEIVER, SERVICE }

internal data class IntentTileState(
    override val id: Int,
    override val row: Int,
    override val column: Int,
    override val rowSpan: Int = 1,
    override val columnSpan: Int = 1,
    val intentAction: String,
    val intentType: IntentType = IntentType.ACTIVITY,
    val intentPackage: String? = null,
    val intentComponent: String? = null,
    val intentDataUri: String? = null,
    val intentExtras: Map<String, String> = emptyMap(),
    override val customLabel: String? = null,
    override val customFontUri: String? = null,
    override val customFontName: String? = null,
    override val customTextScale: Float? = null,
    override val customBoldText: Boolean? = null,
) : TileState() {
    override val displayLabel: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: intentAction
}

internal fun TileState.copyWithLabel(label: String?): TileState = when (this) {
    is AppTileState -> copy(customLabel = label)
    is IntentTileState -> copy(customLabel = label)
    is WidgetTileState -> copy(customLabel = label)
    is SystemSliderTileState -> copy(customLabel = label)
}

internal fun TileState.copyWithFont(uri: String?, name: String?): TileState = when (this) {
    is AppTileState -> copy(customFontUri = uri, customFontName = name)
    is IntentTileState -> copy(customFontUri = uri, customFontName = name)
    is WidgetTileState -> this
    is SystemSliderTileState -> this
}

internal fun TileState.copyWithTextScale(scale: Float?): TileState = when (this) {
    is AppTileState -> copy(customTextScale = scale)
    is IntentTileState -> copy(customTextScale = scale)
    is WidgetTileState -> this
    is SystemSliderTileState -> this
}

internal fun TileState.copyWithAppTileIconConfig(transform: (AppTileIconConfig) -> AppTileIconConfig): TileState = when (this) {
    is AppTileState -> copy(iconConfig = transform(iconConfig))
    else -> this
}

internal fun TileState.copyWithBoldText(isBold: Boolean?): TileState = when (this) {
    is AppTileState -> copy(customBoldText = isBold)
    is IntentTileState -> copy(customBoldText = isBold)
    is WidgetTileState -> this
    is SystemSliderTileState -> this
}

internal fun TileState.copyWithPosition(row: Int, column: Int): TileState = when (this) {
    is AppTileState -> copy(row = row, column = column)
    is IntentTileState -> copy(row = row, column = column)
    is WidgetTileState -> copy(row = row, column = column)
    is SystemSliderTileState -> copy(row = row, column = column)
}

internal fun TileState.copyWithSpan(rowSpan: Int, columnSpan: Int): TileState = when (this) {
    is AppTileState -> copy(rowSpan = rowSpan, columnSpan = columnSpan)
    is IntentTileState -> copy(rowSpan = rowSpan, columnSpan = columnSpan)
    is WidgetTileState -> copy(rowSpan = rowSpan, columnSpan = columnSpan)
    is SystemSliderTileState -> copy(rowSpan = rowSpan, columnSpan = columnSpan)
}

internal enum class SliderType { VOLUME, BRIGHTNESS }
internal enum class StreamMode { PICKER, DEFAULT, SINGLE, ACTIVE }
internal enum class AudioStreamType { MUSIC, RING, ALARM, NOTIFICATION }
internal enum class SliderButtonPlacement { TOP, BOTTOM, SPLIT, NONE }
internal enum class SliderNotchMode { LOCK_ONLY, LOCK_AND_SLIDE, SLIDE_ONLY }

internal data class SystemSliderConfig(
    val sliderType: SliderType,
    val streamMode: StreamMode = StreamMode.DEFAULT,
    val singleStream: AudioStreamType = AudioStreamType.MUSIC,
    val buttonPlacement: SliderButtonPlacement = SliderButtonPlacement.SPLIT,
    val notchMode: SliderNotchMode = SliderNotchMode.LOCK_AND_SLIDE,
    val showNotches: Boolean = true,
    val buttonStepSize: Int = 1,
    val showOutline: Boolean = false,
    val buttonHapticsEnabled: Boolean = false,
    val notchHapticsEnabled: Boolean = false,
)

internal data class SystemSliderTileState(
    override val id: Int,
    override val row: Int,
    override val column: Int,
    override val rowSpan: Int = 3,
    override val columnSpan: Int = 1,
    val config: SystemSliderConfig,
    override val customLabel: String? = null,
) : TileState() {
    override val customFontUri: String? = null
    override val customFontName: String? = null
    override val customTextScale: Float? = null
    override val customBoldText: Boolean? = null
    override val displayLabel: String
        get() = customLabel?.takeIf { it.isNotBlank() }
            ?: when (config.sliderType) {
                SliderType.VOLUME -> "Volume"
                SliderType.BRIGHTNESS -> "Brightness"
            }
}

data class TileFontSelection(val tileId: Int, val fontUri: String, val fontName: String)

data class TileIconSelection(val tileId: Int, val iconUri: String, val iconName: String)

data class WidgetTileSelection(val appWidgetId: Int, val providerComponent: String)

internal data class MaterialIconOption(
    val key: String,
    val label: String,
    val imageVector: ImageVector,
)

internal val materialIconOptions = listOf(
    MaterialIconOption("home", "Home", Icons.Outlined.Home),
    MaterialIconOption("search", "Search", Icons.Outlined.Search),
    MaterialIconOption("settings", "Settings", Icons.Outlined.Settings),
    MaterialIconOption("favorite", "Favorite", Icons.Outlined.Favorite),
    MaterialIconOption("star", "Star", Icons.Outlined.Star),
    MaterialIconOption("bolt", "Bolt", Icons.Outlined.Bolt),
    MaterialIconOption("tune", "Tune", Icons.Outlined.Tune),
    MaterialIconOption("wifi", "Wi-Fi", Icons.Outlined.Wifi),
    MaterialIconOption("bluetooth", "Bluetooth", Icons.Outlined.Bluetooth),
    MaterialIconOption("camera", "Camera", Icons.Outlined.CameraAlt),
    MaterialIconOption("music", "Music", Icons.Outlined.MusicNote),
    MaterialIconOption("notifications", "Notifications", Icons.Outlined.Notifications),
    MaterialIconOption("alarm", "Alarm", Icons.Outlined.Alarm),
    MaterialIconOption("phone", "Phone", Icons.Outlined.PhoneAndroid),
    MaterialIconOption("chat", "Chat", Icons.Outlined.ChatBubbleOutline),
    MaterialIconOption("email", "Email", Icons.Outlined.Email),
    MaterialIconOption("work", "Work", Icons.Outlined.WorkOutline),
    MaterialIconOption("school", "School", Icons.Outlined.School),
    MaterialIconOption("cart", "Cart", Icons.Outlined.ShoppingCart),
    MaterialIconOption("car", "Car", Icons.Outlined.DirectionsCar),
    MaterialIconOption("lock", "Lock", Icons.Outlined.Lock),
    MaterialIconOption("light", "Light", Icons.Outlined.LightMode),
    MaterialIconOption("dark", "Dark", Icons.Outlined.DarkMode),
    MaterialIconOption("apps", "Apps", Icons.Outlined.Apps),
)

internal fun materialIconForKey(key: String?): MaterialIconOption? =
    materialIconOptions.firstOrNull { it.key == key }

internal fun parseAppTileIconConfig(item: org.json.JSONObject): AppTileIconConfig {
    val source = item.optString("appIconSource").takeIf { it.isNotBlank() }
        ?.let { runCatching { AppTileIconSource.valueOf(it) }.getOrDefault(AppTileIconSource.NONE) }
        ?: AppTileIconSource.NONE
    val placement = item.optString("appIconPlacement").takeIf { it.isNotBlank() }
        ?.let { runCatching { AppTileContentPlacement.valueOf(it) }.getOrDefault(AppTileContentPlacement.TOP) }
        ?: AppTileContentPlacement.TOP
    val iconScale = item.optDouble("appIconScale", Double.NaN)
        .takeUnless { it.isNaN() }
        ?.toFloat()
    return AppTileIconConfig(
        source = source,
        grayscale = item.optBoolean("appIconGrayscale", false),
        showLabel = item.optBoolean("appIconShowLabel", true),
        placement = placement,
        materialIconKey = item.optString("appMaterialIconKey").takeIf { it.isNotBlank() },
        customIconUri = item.optString("appCustomIconUri").takeIf { it.isNotBlank() },
        customIconName = item.optString("appCustomIconName").takeIf { it.isNotBlank() },
        iconScale = iconScale,
    )
}

internal fun org.json.JSONObject.putAppTileIconConfig(config: AppTileIconConfig) {
    put("appIconSource", config.source.name)
    put("appIconGrayscale", config.grayscale)
    put("appIconShowLabel", config.showLabel)
    put("appIconPlacement", config.placement.name)
    config.materialIconKey?.let { put("appMaterialIconKey", it) }
    config.customIconUri?.let { put("appCustomIconUri", it) }
    config.customIconName?.let { put("appCustomIconName", it) }
    config.iconScale?.let { put("appIconScale", it.toDouble()) }
}

internal sealed class TileInsertionEvent {
    data class WidgetAdded(val selection: WidgetTileSelection) : TileInsertionEvent()
    data class SystemSliderAdded(
        val config: SystemSliderConfig,
        val rowSpan: Int,
        val columnSpan: Int,
    ) : TileInsertionEvent()
}

internal data class WidgetTileState(
    override val id: Int,
    override val row: Int,
    override val column: Int,
    override val rowSpan: Int = 2,
    override val columnSpan: Int = 2,
    val appWidgetId: Int,
    val providerComponent: String,
    override val customLabel: String? = null,
) : TileState() {
    override val customFontUri: String? = null
    override val customFontName: String? = null
    override val customTextScale: Float? = null
    override val customBoldText: Boolean? = null
    override val displayLabel: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: "Widget"
}

internal data class OverlayUiState(
    val showGrid: Boolean = false,
    val dpadOffsetX: Float = 24f,
    val dpadOffsetY: Float = 220f,
    val topPanelOffsetX: Float = 16f,
    val topPanelOffsetY: Float = 16f,
    val nextTileId: Int = 1,
    val tiles: List<TileState> = emptyList(),
    val gridRows: Int = 8,
    val gridColumns: Int = 4,
    val defaultTextScale: Float = 1.0f,
    val defaultBoldText: Boolean = false,
    val defaultFontUri: String? = null,
    val defaultFontName: String? = null,
    val defaultTextColorMode: DefaultTextColorMode = DefaultTextColorMode.SYSTEM,
    val defaultTextColorHex: String? = null,
    val hapticFeedbackEnabled: Boolean = true,
    val panelHandleLocked: Boolean = false,
    val showPanelHandle: Boolean = true,
    val overlayBackgroundAlpha: Float = 0.33f,
    val showOverLockscreen: Boolean = false,
)

// ── Utility ──────────────────────────────────────────────────────────────────

internal fun findTile(tiles: List<TileState>, id: Int?): TileState? =
    tiles.firstOrNull { it.id == id }

// ── Overlay composable ───────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OverlayContent(
    initialState: OverlayUiState,
    preloadedFonts: Map<String, FontFamily?>,
    tileFontEvents: Flow<TileFontSelection>,
    tileIconEvents: Flow<TileIconSelection>,
    tileInsertionEvents: Flow<TileInsertionEvent>,
    loadLaunchableApps: () -> List<LaunchableApp>,
    resolveCustomPackage: (String) -> LaunchableApp?,
    loadFontFamily: (String?) -> FontFamily?,
    openTileFontPicker: (Int) -> Unit,
    openTileIconPicker: (Int) -> Unit,
    launchApp: (LaunchableApp) -> Unit,
    launchIntent: (IntentTileState) -> Unit,
    onPersist: (OverlayUiState) -> Unit,
    onDismiss: () -> Unit,
    onKeyboardInputToggle: (Boolean) -> Unit = {},
    grayscaleFrame: Flow<Bitmap?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    grayscaleConfig: GrayscaleConfig = GrayscaleConfig(),
    foregroundPackage: Flow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    // ── State ────────────────────────────────────────────────────────────────
    val tiles = remember { mutableStateListOf<TileState>().apply { addAll(initialState.tiles) } }
    var nextTileId by remember { mutableIntStateOf(initialState.nextTileId) }
    var showGrid by remember { mutableStateOf(initialState.showGrid) }
    var selectedTileId by remember { mutableStateOf<Int?>(null) }
    var dpadOffsetX by remember { mutableFloatStateOf(initialState.dpadOffsetX) }
    var dpadOffsetY by remember { mutableFloatStateOf(initialState.dpadOffsetY) }
    var topPanelOffsetX by remember { mutableFloatStateOf(initialState.topPanelOffsetX) }
    var topPanelOffsetY by remember { mutableFloatStateOf(initialState.topPanelOffsetY) }
    var isPanelVisible by remember { mutableStateOf(false) }
    var isMoveMode by remember { mutableStateOf(false) }
    var sheetVisible by remember { mutableStateOf(false) }

    // App chooser state
    var choosingAppForTileId by remember { mutableStateOf<Int?>(null) }
    var pendingNewTileCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var appSearchQuery by remember { mutableStateOf("") }
    var customPackageName by remember { mutableStateOf("") }
    var chooserError by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var hasLoadedApps by remember { mutableStateOf(false) }
    var isLoadingApps by remember { mutableStateOf(false) }
    var appsLoadError by remember { mutableStateOf<String?>(null) }

    // Intent form state
    var pendingNewIntentCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editingIntentTileId by remember { mutableStateOf<Int?>(null) }
    var intentActionDraft by remember { mutableStateOf("") }
    var intentTypeDraft by remember { mutableStateOf(IntentType.ACTIVITY) }
    var intentPackageDraft by remember { mutableStateOf("") }
    var intentComponentDraft by remember { mutableStateOf("") }
    var intentDataUriDraft by remember { mutableStateOf("") }
    val intentExtrasDraft = remember { mutableStateListOf<Pair<String, String>>() }
    var intentFormError by remember { mutableStateOf<String?>(null) }

    // Font menu state
    var fontMenuTileId by remember { mutableStateOf<Int?>(null) }
    var materialIconPickerTileId by remember { mutableStateOf<Int?>(null) }
    var tileLabelDraft by remember { mutableStateOf("") }

    // Font families
    var defaultFontFamily by remember { mutableStateOf(preloadedFonts[initialState.defaultFontUri]) }

    val gridRows = initialState.gridRows
    val gridColumns = initialState.gridColumns
    val defaultTextScale = initialState.defaultTextScale
    val defaultFontWeight = if (initialState.defaultBoldText) FontWeight.Bold else FontWeight.Normal
    val defaultTextColor = resolveDefaultTileTextColor(
        mode = initialState.defaultTextColorMode,
        hex = initialState.defaultTextColorHex,
        fallback = MaterialTheme.colorScheme.onSurface,
    )

    val isChooserVisible = choosingAppForTileId != null || pendingNewTileCell != null
    val isIntentFormVisible = pendingNewIntentCell != null || editingIntentTileId != null

    val filteredApps = remember(apps, appSearchQuery) {
        val query = appSearchQuery.trim()
        if (query.isEmpty()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun persist() {
        onPersist(
            OverlayUiState(
                showGrid = showGrid,
                dpadOffsetX = dpadOffsetX,
                dpadOffsetY = dpadOffsetY,
                topPanelOffsetX = topPanelOffsetX,
                topPanelOffsetY = topPanelOffsetY,
                nextTileId = nextTileId,
                tiles = tiles.toList(),
                gridRows = gridRows,
                gridColumns = gridColumns,
                defaultTextScale = defaultTextScale,
                defaultBoldText = initialState.defaultBoldText,
                defaultFontUri = initialState.defaultFontUri,
                defaultFontName = initialState.defaultFontName,
                defaultTextColorMode = initialState.defaultTextColorMode,
                defaultTextColorHex = initialState.defaultTextColorHex,
                hapticFeedbackEnabled = initialState.hapticFeedbackEnabled,
                panelHandleLocked = initialState.panelHandleLocked,
                showPanelHandle = initialState.showPanelHandle,
                overlayBackgroundAlpha = initialState.overlayBackgroundAlpha,
                showOverLockscreen = initialState.showOverLockscreen,
            ),
        )
    }

    fun overlapsExisting(row: Int, column: Int, rowSpan: Int, columnSpan: Int, excludedTileId: Int? = null): Boolean {
        val rowEnd = row + rowSpan
        val colEnd = column + columnSpan
        return tiles.any { tile ->
            if (tile.id == excludedTileId) return@any false
            row < tile.row + tile.rowSpan && rowEnd > tile.row &&
                column < tile.column + tile.columnSpan && colEnd > tile.column
        }
    }

    fun findFirstOpenCell(
        rowSpan: Int = 1,
        columnSpan: Int = 1,
    ): Pair<Int, Int>? {
        for (r in 0 until gridRows) {
            for (c in 0 until gridColumns) {
                if (r + rowSpan <= gridRows &&
                    c + columnSpan <= gridColumns &&
                    !overlapsExisting(r, c, rowSpan, columnSpan)
                ) {
                    return r to c
                }
            }
        }
        return null
    }

    fun moveSelected(rowDelta: Int, columnDelta: Int) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val newRow = (current.row + rowDelta).coerceIn(0, gridRows - 1)
        val newCol = (current.column + columnDelta).coerceIn(0, gridColumns - 1)
        if (newRow == current.row && newCol == current.column) return
        if (newRow + current.rowSpan > gridRows || newCol + current.columnSpan > gridColumns) return
        if (overlapsExisting(newRow, newCol, current.rowSpan, current.columnSpan, current.id)) return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithPosition(newRow, newCol); persist() }
    }

    fun resizeSelected(rowSpanDelta: Int, columnSpanDelta: Int) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val newRowSpan = (current.rowSpan + rowSpanDelta).coerceAtLeast(1)
        val newColSpan = (current.columnSpan + columnSpanDelta).coerceAtLeast(1)
        if (newRowSpan == current.rowSpan && newColSpan == current.columnSpan) return
        if (current.row + newRowSpan > gridRows || current.column + newColSpan > gridColumns) return
        if (overlapsExisting(current.row, current.column, newRowSpan, newColSpan, current.id)) return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithSpan(newRowSpan, newColSpan); persist() }
    }

    fun adjustTextScale(delta: Float) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val newScale = ((current.customTextScale ?: defaultTextScale) + delta)
            .coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithTextScale(newScale); persist() }
    }

    fun resetTextScale() {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithTextScale(null); persist() }
    }

    fun setSelectedBoldText(isBold: Boolean?) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithBoldText(isBold); persist() }
    }

    fun updateSelectedAppTileIconConfig(transform: (AppTileIconConfig) -> AppTileIconConfig) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            tiles[index] = current.copyWithAppTileIconConfig(transform)
            persist()
        }
    }

    fun adjustSelectedAppTileIconScale(delta: Float) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) as? AppTileState ?: return
        val newScale = ((current.iconConfig.iconScale ?: 1f) + delta)
            .coerceIn(ICON_SCALE_MIN, ICON_SCALE_MAX)
        updateSelectedAppTileIconConfig { it.copy(iconScale = newScale) }
    }

    fun resetSelectedAppTileIconScale() {
        updateSelectedAppTileIconConfig { it.copy(iconScale = null) }
    }

    fun closeChooser() {
        choosingAppForTileId = null
        pendingNewTileCell = null
        appSearchQuery = ""
        customPackageName = ""
        chooserError = null
        appsLoadError = null
    }

    fun applyAppSelection(app: LaunchableApp) {
        val editId = choosingAppForTileId
        if (editId != null) {
            val index = tiles.indexOfFirst { it.id == editId }
            if (index >= 0) {
                val current = tiles[index] as? AppTileState ?: return
                tiles[index] = current.copy(app = app)
                persist()
            }
        } else {
            val cell = pendingNewTileCell ?: return
            val newId = nextTileId++
            tiles += AppTileState(id = newId, row = cell.first, column = cell.second, app = app)
            selectedTileId = newId
            persist()
        }
        closeChooser()
    }

    fun resolveCustomEntry(input: String): LaunchableApp? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return apps.firstOrNull { it.packageName.equals(trimmed, ignoreCase = true) || it.label.equals(trimmed, ignoreCase = true) }
            ?: resolveCustomPackage(trimmed)
    }

    fun openIntentFormForNew() {
        val cell = findFirstOpenCell() ?: return
        pendingNewIntentCell = cell
        editingIntentTileId = null
        intentActionDraft = ""
        intentTypeDraft = IntentType.ACTIVITY
        intentPackageDraft = ""
        intentComponentDraft = ""
        intentDataUriDraft = ""
        intentExtrasDraft.clear()
        intentFormError = null
    }

    fun openIntentFormForEdit(tileId: Int) {
        val tile = findTile(tiles, tileId) as? IntentTileState ?: return
        editingIntentTileId = tileId
        pendingNewIntentCell = null
        intentActionDraft = tile.intentAction
        intentTypeDraft = tile.intentType
        intentPackageDraft = tile.intentPackage ?: ""
        intentComponentDraft = tile.intentComponent ?: ""
        intentDataUriDraft = tile.intentDataUri ?: ""
        intentExtrasDraft.clear()
        intentExtrasDraft.addAll(tile.intentExtras.entries.map { it.key to it.value })
        intentFormError = null
    }

    fun applyIntentForm() {
        val action = intentActionDraft.trim()
        if (action.isEmpty()) { intentFormError = "Action is required"; return }
        val extras = intentExtrasDraft.filter { (k, _) -> k.isNotBlank() }.toMap()

        val editId = editingIntentTileId
        if (editId != null) {
            val index = tiles.indexOfFirst { it.id == editId }
            if (index >= 0) {
                val current = tiles[index] as? IntentTileState ?: return
                tiles[index] = current.copy(
                    intentAction = action,
                    intentType = intentTypeDraft,
                    intentPackage = intentPackageDraft.trim().ifBlank { null },
                    intentComponent = intentComponentDraft.trim().ifBlank { null },
                    intentDataUri = intentDataUriDraft.trim().ifBlank { null },
                    intentExtras = extras,
                )
                persist()
            }
        } else {
            val cell = pendingNewIntentCell ?: return
            val newId = nextTileId++
            tiles += IntentTileState(
                id = newId,
                row = cell.first,
                column = cell.second,
                intentAction = action,
                intentType = intentTypeDraft,
                intentPackage = intentPackageDraft.trim().ifBlank { null },
                intentComponent = intentComponentDraft.trim().ifBlank { null },
                intentDataUri = intentDataUriDraft.trim().ifBlank { null },
                intentExtras = extras,
            )
            selectedTileId = newId
            persist()
        }
        pendingNewIntentCell = null
        editingIntentTileId = null
        intentFormError = null
    }

    fun closeIntentForm() {
        pendingNewIntentCell = null
        editingIntentTileId = null
        intentFormError = null
    }

    fun openWidgetPicker() {
        WidgetBindingCoordinator.startBinding()
        onDismiss()
        val hasVolumeSlider = tiles.any { it is SystemSliderTileState && it.config.sliderType == SliderType.VOLUME }
        val hasBrightnessSlider = tiles.any { it is SystemSliderTileState && it.config.sliderType == SliderType.BRIGHTNESS }
        context.startActivity(
            BindWidgetActivity.createIntent(context, gridRows, gridColumns, hasVolumeSlider, hasBrightnessSlider),
        )
    }

    fun orderedTiles(): List<TileState> =
        tiles.sortedWith(compareBy<TileState>({ it.row }, { it.column }, { it.id }))

    fun cycleSelectedTile(direction: Int) {
        val ordered = orderedTiles()
        if (ordered.isEmpty()) return

        val currentIndex = ordered.indexOfFirst { it.id == selectedTileId }
        val nextIndex = when {
            currentIndex < 0 -> if (direction >= 0) 0 else ordered.lastIndex
            direction >= 0 -> (currentIndex + 1) % ordered.size
            else -> (currentIndex - 1 + ordered.size) % ordered.size
        }
        selectedTileId = ordered[nextIndex].id
    }

    fun setWidgetEditMode(enabled: Boolean) {
        isMoveMode = enabled
        if (!enabled) {
            if (showGrid) {
                showGrid = false
                persist()
            }
            return
        }

        val ordered = orderedTiles()
        if (ordered.isEmpty()) {
            selectedTileId = null
            return
        }

        if (findTile(tiles, selectedTileId) == null) {
            selectedTileId = ordered.first().id
        }
    }

    fun resolveNativeWidgetSpan(
        providerInfo: AppWidgetProviderInfo,
    ): Pair<Int, Int> {
        val availableWidthDp = (configuration.screenWidthDp - 32).coerceAtLeast(1)
        val availableHeightDp = (configuration.screenHeightDp - 32).coerceAtLeast(1)
        val cellWidthDp = availableWidthDp.toFloat() / gridColumns.toFloat()
        val cellHeightDp = availableHeightDp.toFloat() / gridRows.toFloat()
        val rowSpan = ceil((providerInfo.minHeight.coerceAtLeast(1)).toFloat() / cellHeightDp)
            .toInt()
            .coerceAtLeast(1)
        val columnSpan = ceil((providerInfo.minWidth.coerceAtLeast(1)).toFloat() / cellWidthDp)
            .toInt()
            .coerceAtLeast(1)
        return rowSpan to columnSpan
    }

    // ── Effects ──────────────────────────────────────────────────────────────

    LaunchedEffect(initialState.defaultFontUri) {
        if (defaultFontFamily == null && initialState.defaultFontUri != null) {
            defaultFontFamily = withContext(Dispatchers.IO) { loadFontFamily(initialState.defaultFontUri) }
        }
    }

    LaunchedEffect(Unit) {
        tileFontEvents.collectLatest { selection ->
            val index = tiles.indexOfFirst { it.id == selection.tileId }
            if (index >= 0) {
                tiles[index] = tiles[index].copyWithFont(selection.fontUri, selection.fontName)
                persist()
            }
            fontMenuTileId = null
        }
    }

    LaunchedEffect(Unit) {
        tileIconEvents.collectLatest { selection ->
            val index = tiles.indexOfFirst { it.id == selection.tileId }
            val current = tiles.getOrNull(index) as? AppTileState
            if (current != null) {
                tiles[index] = current.copy(
                    iconConfig = current.iconConfig.copy(
                        source = AppTileIconSource.CUSTOM,
                        customIconUri = selection.iconUri,
                        customIconName = selection.iconName,
                    ),
                )
                persist()
            }
        }
    }

    LaunchedEffect(Unit) {
        tileInsertionEvents.collectLatest { }
    }

    LaunchedEffect(Unit) {
        when (val event = WidgetBindingCoordinator.consumeCompletedInsertion()) {
            null -> return@LaunchedEffect
            is TileInsertionEvent.WidgetAdded -> {
                val selection = event.selection
                val providerInfo = appWidgetManager.getAppWidgetInfo(selection.appWidgetId)
                if (providerInfo == null) {
                    ShortcutHubWidgetHost.getInstance(context).deleteAppWidgetId(selection.appWidgetId)
                    return@LaunchedEffect
                }
                val (rowSpan, columnSpan) = resolveNativeWidgetSpan(providerInfo)
                val cell = findFirstOpenCell(rowSpan = rowSpan, columnSpan = columnSpan)
                if (cell == null) {
                    ShortcutHubWidgetHost.getInstance(context).deleteAppWidgetId(selection.appWidgetId)
                    Toast.makeText(context, "not enough grid space to support this widget", Toast.LENGTH_LONG).show()
                    return@LaunchedEffect
                }
                val newId = nextTileId++
                tiles += WidgetTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = rowSpan,
                    columnSpan = columnSpan,
                    appWidgetId = selection.appWidgetId,
                    providerComponent = selection.providerComponent,
                )
                selectedTileId = newId
                persist()
            }
            is TileInsertionEvent.SystemSliderAdded -> {
                val alreadyExists = tiles.any {
                    it is SystemSliderTileState && it.config.sliderType == event.config.sliderType
                }
                if (alreadyExists) {
                    Toast.makeText(
                        context,
                        "A ${event.config.sliderType.name.lowercase()} slider is already on the grid",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@LaunchedEffect
                }
                val cell = findFirstOpenCell(rowSpan = event.rowSpan, columnSpan = event.columnSpan)
                if (cell == null) {
                    Toast.makeText(context, "Not enough grid space for this slider", Toast.LENGTH_LONG).show()
                    return@LaunchedEffect
                }
                val newId = nextTileId++
                tiles += SystemSliderTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = event.rowSpan,
                    columnSpan = event.columnSpan,
                    config = event.config,
                )
                selectedTileId = newId
                persist()
            }
        }
    }

    LaunchedEffect(isChooserVisible, hasLoadedApps) {
        if (isChooserVisible && !hasLoadedApps && !isLoadingApps) {
            isLoadingApps = true
            appsLoadError = null
            runCatching { withContext(Dispatchers.IO) { loadLaunchableApps() } }
                .onSuccess { loadedApps -> apps = loadedApps; hasLoadedApps = true }
                .onFailure { appsLoadError = "Unable to load installed apps" }
            isLoadingApps = false
        }
    }

    LaunchedEffect(selectedTileId, tiles.size) {
        tileLabelDraft = findTile(tiles, selectedTileId)?.customLabel.orEmpty()
    }

    val needsKeyboard = isChooserVisible || isIntentFormVisible || sheetVisible
    LaunchedEffect(needsKeyboard) {
        onKeyboardInputToggle(needsKeyboard)
    }

    val protectedSliderBounds = remember { mutableStateMapOf<Int, Rect>() }
    val currentProtectedSliderBounds by rememberUpdatedState(protectedSliderBounds.values.toList())

    LaunchedEffect(tiles) {
        val sliderIds = tiles.filterIsInstance<SystemSliderTileState>().map { it.id }.toSet()
        protectedSliderBounds.keys.toList().forEach { tileId ->
            if (tileId !in sliderIds) protectedSliderBounds.remove(tileId)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    val hapticFeedback = LocalHapticFeedback.current
    fun openMainApp() {
        context.startActivity(
            android.content.Intent(context, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    val grayscaleBitmap by grayscaleFrame.collectAsState(initial = null)
    val foregroundPkg by foregroundPackage.collectAsState(initial = null)
    val whitelistPackages = remember(grayscaleConfig.whitelistApps) {
        grayscaleConfig.whitelistApps.map { it.packageName }.toSet()
    }
    val blacklistPackages = remember(grayscaleConfig.blacklistApps) {
        grayscaleConfig.blacklistApps.map { it.packageName }.toSet()
    }
    val shouldShowGrayscale = grayscaleBitmap != null && when {
        foregroundPkg == null -> true
        grayscaleConfig.activeMode == GrayscaleFilterMode.WHITELIST -> foregroundPkg !in whitelistPackages
        else -> foregroundPkg in blacklistPackages
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(sheetVisible) {
                val edgeZonePx = 24.dp.toPx()
                val sliderProtectionPaddingPx = 16.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (down.position.x < edgeZonePx || down.position.x > size.width - edgeZonePx) {
                        return@awaitEachGesture
                    }
                    val inProtectedSliderZone = currentProtectedSliderBounds.any { bounds ->
                        Rect(
                            left = bounds.left - sliderProtectionPaddingPx,
                            top = bounds.top - sliderProtectionPaddingPx,
                            right = bounds.right + sliderProtectionPaddingPx,
                            bottom = bounds.bottom + sliderProtectionPaddingPx,
                        ).contains(down.position)
                    }
                    if (inProtectedSliderZone) return@awaitEachGesture
                    if (sheetVisible) { sheetVisible = false; selectedTileId = null } else onDismiss()
                }
            },
    ) {
        // Layer 1: live grayscale capture of whatever is behind the overlay
        if (shouldShowGrayscale) {
            grayscaleBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        // Layer 2: translucent tinted panel (alpha controlled in settings)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = initialState.overlayBackgroundAlpha)),
        )

        if (initialState.showPanelHandle) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(topPanelOffsetX.roundToInt(), topPanelOffsetY.roundToInt()) }
                    .wrapContentWidth()
                    .wrapContentHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PanelHandle(
                    currentOffsetX = topPanelOffsetX,
                    currentOffsetY = topPanelOffsetY,
                    locked = initialState.panelHandleLocked,
                    onOffsetChange = { x, y -> topPanelOffsetX = x; topPanelOffsetY = y },
                    onDragFinished = { persist() },
                    onToggle = { isPanelVisible = !isPanelVisible },
                )
                if (isPanelVisible) {
                    Card(
                        modifier = Modifier
                            .wrapContentWidth()
                            .wrapContentHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Shortcut Hub",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Surface(
                                    onClick = { openMainApp(); onDismiss() },
                                    modifier = Modifier.align(Alignment.CenterEnd).size(28.dp),
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "⚙",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = {
                                    val cell = findFirstOpenCell() ?: return@Button
                                    pendingNewTileCell = cell
                                    choosingAppForTileId = null
                                    appSearchQuery = ""
                                    customPackageName = ""
                                    chooserError = null
                                    appsLoadError = null
                                }) { Text("App") }
                                OutlinedButton(onClick = { openWidgetPicker() }) { Text("Widget") }
                                OutlinedButton(onClick = { openIntentFormForNew() }) { Text("Intent") }
                                OutlinedButton(onClick = onDismiss) { Text("Close") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = {
                                    val wasInMoveMode = isMoveMode
                                    setWidgetEditMode(!isMoveMode)
                                    if (wasInMoveMode) {
                                        selectedTileId = null
                                        sheetVisible = false
                                    }
                                }) { Text(if (isMoveMode) "Done" else "Edit") }
                            }
                            if (isMoveMode) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { cycleSelectedTile(-1) }) { Text("<") }
                                    OutlinedButton(onClick = { cycleSelectedTile(1) }) { Text(">") }
                                    OutlinedButton(onClick = {
                                        if (selectedTileId != null) sheetVisible = true
                                    }) { Text("Options...") }
                                    OutlinedButton(onClick = { showGrid = !showGrid; persist() }) {
                                        Text(if (showGrid) "Grid Off" else "Grid On")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Grid + tiles — delegated to OverlayGridPreview
        OverlayGridPreview(
            tiles = tiles.toList(),
            gridRows = gridRows,
            gridColumns = gridColumns,
            showGrid = showGrid,
            mode = OverlayRenderMode.Runtime,
            selectedTileId = selectedTileId,
            isMoveMode = isMoveMode,
            defaultTextScale = defaultTextScale,
            defaultFontWeight = defaultFontWeight,
            defaultFontFamily = defaultFontFamily,
            defaultTextColor = defaultTextColor,
            hapticFeedbackEnabled = initialState.hapticFeedbackEnabled,
            preloadedFonts = preloadedFonts,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            onTileSelect = { id -> selectedTileId = id; sheetVisible = true },
            onTileTap = { tile ->
                when (tile) {
                    is AppTileState -> { launchApp(tile.app); onDismiss() }
                    is IntentTileState -> { launchIntent(tile); onDismiss() }
                    else -> Unit
                }
            },
            onTileLongPress = { tile -> selectedTileId = tile.id; sheetVisible = true },
            onSliderBoundsChanged = { id, bounds -> protectedSliderBounds[id] = bounds },
            widgetContent = { tile ->
                val providerInfo = remember(tile.appWidgetId, tile.providerComponent) {
                    appWidgetManager.getAppWidgetInfo(tile.appWidgetId)
                }
                if (providerInfo != null) {
                    AndroidView(
                        factory = {
                            WidgetViewCache.getOrCreate(
                                context = context,
                                appWidgetId = tile.appWidgetId,
                                providerInfo = providerInfo,
                            ).apply {
                                isLongClickable = !isMoveMode
                                setOnLongClickListener(
                                    if (isMoveMode) {
                                        null
                                    } else {
                                        {
                                            if (initialState.hapticFeedbackEnabled) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            selectedTileId = tile.id
                                            sheetVisible = true
                                            true
                                        }
                                    },
                                )
                                isClickable = !isMoveMode
                                isEnabled = !isMoveMode
                            }
                        },
                        update = { hostView ->
                            hostView.isLongClickable = !isMoveMode
                            hostView.setOnLongClickListener(
                                if (isMoveMode) {
                                    null
                                } else {
                                    {
                                        if (initialState.hapticFeedbackEnabled) {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        selectedTileId = tile.id
                                        sheetVisible = true
                                        true
                                    }
                                },
                            )
                            hostView.isClickable = !isMoveMode
                            hostView.isEnabled = !isMoveMode
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = tile.displayLabel,
                        modifier = Modifier.align(Alignment.Center).padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = defaultTextColor,
                        fontWeight = if (tile.id == selectedTileId) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            },
        )

        // D-pad
        if (isMoveMode && selectedTileId != null) {
            DraggableDpad(
                offsetX = dpadOffsetX,
                offsetY = dpadOffsetY,
                onOffsetChange = { x, y -> dpadOffsetX = x; dpadOffsetY = y },
                onDragFinished = { persist() },
                onMoveUp = { moveSelected(-1, 0) },
                onMoveDown = { moveSelected(1, 0) },
                onMoveLeft = { moveSelected(0, -1) },
                onMoveRight = { moveSelected(0, 1) },
            )
        }

        // App chooser dialog
        if (isChooserVisible) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = RoundedCornerShape(24.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (choosingAppForTileId != null) "Choose App" else "Add App Tile",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = appSearchQuery,
                            onValueChange = { appSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search installed apps") },
                            singleLine = true,
                        )
                        Text(
                            text = "${filteredApps.size} apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            isLoadingApps -> Text("Loading installed apps...", style = MaterialTheme.typography.bodyMedium)
                            appsLoadError != null -> Text(appsLoadError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            else -> LazyColumn(
                                modifier = Modifier.height(440.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(filteredApps) { app ->
                                    Surface(
                                        onClick = { applyAppSelection(app) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(18.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = customPackageName,
                            onValueChange = { customPackageName = it; chooserError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Package or exact app name") },
                            singleLine = true,
                        )
                        OutlinedButton(onClick = {
                            val resolved = resolveCustomEntry(customPackageName)
                            if (resolved == null) chooserError = "App/package not found or not launchable"
                            else applyAppSelection(resolved)
                        }) { Text("Use App / Package") }
                        chooserError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        OutlinedButton(onClick = ::closeChooser, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                    }
                }
            }
        }

        // Intent tile form dialog
        if (isIntentFormVisible) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = RoundedCornerShape(24.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (editingIntentTileId != null) "Edit Intent Tile" else "Add Intent Tile",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Type", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IntentType.entries.forEach { type ->
                                val label = when (type) {
                                    IntentType.ACTIVITY -> "Activity"
                                    IntentType.BROADCAST_RECEIVER -> "Broadcast"
                                    IntentType.SERVICE -> "Service"
                                }
                                if (intentTypeDraft == type) {
                                    Button(onClick = {}) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { intentTypeDraft = type }) { Text(label) }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = intentActionDraft,
                            onValueChange = { intentActionDraft = it; intentFormError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Action (required)") },
                            placeholder = { Text("e.g. android.intent.action.VIEW") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = intentPackageDraft,
                            onValueChange = { intentPackageDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Package (optional)") },
                            placeholder = { Text("e.g. com.example.app") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = intentComponentDraft,
                            onValueChange = { intentComponentDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Component / Class (optional)") },
                            placeholder = { Text("pkg/com.example.Activity") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = intentDataUriDraft,
                            onValueChange = { intentDataUriDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Data URI (optional)") },
                            placeholder = { Text("e.g. https://example.com") },
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Extras", style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { intentExtrasDraft.add("" to "") }) {
                                Text("+ Add Extra")
                            }
                        }
                        intentExtrasDraft.forEachIndexed { index, (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { intentExtrasDraft[index] = it to value },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Key") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { intentExtrasDraft[index] = key to it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Value") },
                                    singleLine = true,
                                )
                                OutlinedButton(onClick = { intentExtrasDraft.removeAt(index) }) {
                                    Text("×")
                                }
                            }
                        }
                        intentFormError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { applyIntentForm() }, modifier = Modifier.weight(1f)) {
                                Text(if (editingIntentTileId != null) "Save" else "Add")
                            }
                            OutlinedButton(onClick = { closeIntentForm() }, modifier = Modifier.weight(1f)) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }

        // Tile edit bottom sheet
        val sheetTile = if (sheetVisible) findTile(tiles, selectedTileId) else null
        if (sheetTile != null) {
            TileEditSheet(
                tile = sheetTile,
                tileLabelDraft = tileLabelDraft,
                onLabelDraftChange = { tileLabelDraft = it },
                onSaveLabel = {
                    val index = tiles.indexOfFirst { it.id == sheetTile.id }
                    if (index >= 0) { tiles[index] = tiles[index].copyWithLabel(tileLabelDraft.trim().ifBlank { null }); persist() }
                },
                onResetLabel = {
                    tileLabelDraft = ""
                    val index = tiles.indexOfFirst { it.id == sheetTile.id }
                    if (index >= 0) { tiles[index] = tiles[index].copyWithLabel(null); persist() }
                },
                onResizeWidth = { delta -> resizeSelected(0, delta) },
                onResizeHeight = { delta -> resizeSelected(delta, 0) },
                onTextScaleUp = { adjustTextScale(TEXT_SCALE_STEP) },
                onTextScaleDown = { adjustTextScale(-TEXT_SCALE_STEP) },
                onTextScaleReset = { resetTextScale() },
                onIconScaleUp = { adjustSelectedAppTileIconScale(ICON_SCALE_STEP) },
                onIconScaleDown = { adjustSelectedAppTileIconScale(-ICON_SCALE_STEP) },
                onIconScaleReset = { resetSelectedAppTileIconScale() },
                onBoldOn = { setSelectedBoldText(true) },
                onBoldOff = { setSelectedBoldText(false) },
                onBoldReset = { setSelectedBoldText(null) },
                onOpenFont = { fontMenuTileId = sheetTile.id },
                onAppIconConfigChange = if (sheetTile is AppTileState) { newConfig ->
                    val idx = tiles.indexOfFirst { it.id == sheetTile.id }
                    if (idx >= 0) { tiles[idx] = (tiles[idx] as AppTileState).copy(iconConfig = newConfig); persist() }
                } else null,
                onPickCustomIcon = if (sheetTile is AppTileState) {
                    { openTileIconPicker(sheetTile.id) }
                } else null,
                onChooseMaterialIcon = if (sheetTile is AppTileState) {
                    { materialIconPickerTileId = sheetTile.id }
                } else null,
                onChangeApp = {
                    choosingAppForTileId = sheetTile.id
                    pendingNewTileCell = null
                    appSearchQuery = ""
                    customPackageName = ""
                    chooserError = null
                    appsLoadError = null
                    sheetVisible = false
                },
                onEditIntent = {
                    openIntentFormForEdit(sheetTile.id)
                    sheetVisible = false
                },
                onSliderConfigChange = if (sheetTile is SystemSliderTileState) { newConfig ->
                    val idx = tiles.indexOfFirst { it.id == sheetTile.id }
                    if (idx >= 0) { tiles[idx] = (tiles[idx] as SystemSliderTileState).copy(config = newConfig); persist() }
                } else null,
                onDelete = {
                    if (sheetTile is WidgetTileState) {
                        ShortcutHubWidgetHost.getInstance(context).deleteAppWidgetId(sheetTile.appWidgetId)
                    }
                    tiles.removeAll { it.id == sheetTile.id }
                    selectedTileId = null
                    sheetVisible = false
                    choosingAppForTileId = null
                    persist()
                },
                onDone = { sheetVisible = false; selectedTileId = null },
            )
        }

        // Font menu dialog
        fontMenuTileId?.let { tileId ->
            val tile = findTile(tiles, tileId)
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = RoundedCornerShape(24.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Tile Font", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Current font: ${tile?.customFontName ?: initialState.defaultFontName ?: "Default"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = { openTileFontPicker(tileId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Choose Font Override") }
                        OutlinedButton(
                            onClick = {
                                val index = tiles.indexOfFirst { it.id == tileId }
                                if (index >= 0) { tiles[index] = tiles[index].copyWithFont(null, null); persist() }
                                fontMenuTileId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Clear Font Override") }
                        OutlinedButton(
                            onClick = { fontMenuTileId = null },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Close") }
                    }
                }
            }
        }

        materialIconPickerTileId?.let { tileId ->
            val tile = findTile(tiles, tileId) as? AppTileState
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = RoundedCornerShape(24.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Choose Material Icon", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Current: ${materialIconForKey(tile?.iconConfig?.materialIconKey)?.label ?: "None"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.height(320.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(materialIconOptions, key = { it.key }) { option ->
                                OutlinedButton(
                                    onClick = {
                                        val idx = tiles.indexOfFirst { it.id == tileId }
                                        val current = tiles.getOrNull(idx) as? AppTileState ?: return@OutlinedButton
                                        tiles[idx] = current.copy(
                                            iconConfig = current.iconConfig.copy(
                                                source = AppTileIconSource.MATERIAL,
                                                materialIconKey = option.key,
                                            ),
                                        )
                                        persist()
                                        materialIconPickerTileId = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = option.imageVector,
                                            contentDescription = option.label,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(option.label, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { materialIconPickerTileId = null },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Close") }
                    }
                }
            }
        }
    }
}

internal fun Drawable.asBitmapPainter(): Painter? = runCatching {
    val bitmap: Bitmap = if (this is BitmapDrawable && bitmap != null) {
        bitmap
    } else {
        toBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1))
    }
    BitmapPainter(bitmap.asImageBitmap())
}.getOrNull()

@Composable
internal fun rememberAppTileAppIconPainter(packageName: String): Painter? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).asBitmapPainter() }.getOrNull()
    }
}

@Composable
internal fun rememberCustomIconPainter(uriString: String?): Painter? {
    val context = LocalContext.current
    var painter by remember(uriString) { mutableStateOf<Painter?>(null) }

    LaunchedEffect(uriString) {
        painter = withContext(Dispatchers.IO) {
            val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@withContext null
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                }
                BitmapPainter(bitmap.asImageBitmap())
            }.getOrNull()
        }
    }

    return painter
}

@Composable
internal fun AppTileContent(
    tile: AppTileState,
    defaultTextScale: Float,
    defaultFontFamily: FontFamily?,
    defaultFontWeight: FontWeight,
    defaultTextColor: Color,
    preloadedFonts: Map<String, FontFamily?>,
    loadFontFamily: (String?) -> FontFamily?,
    modifier: Modifier = Modifier,
) {
    val iconConfig = tile.iconConfig
    val hasIcon = iconConfig.source != AppTileIconSource.NONE
    val showLabel = !hasIcon || iconConfig.showLabel
    val iconPainter = when (iconConfig.source) {
        AppTileIconSource.APP -> {
            val packageName = tile.app.packageName
            if (packageName.isNotBlank()) rememberAppTileAppIconPainter(packageName) else null
        }
        AppTileIconSource.CUSTOM -> rememberCustomIconPainter(iconConfig.customIconUri)
        else -> null
    }
    val materialIcon = materialIconForKey(iconConfig.materialIconKey)
    val grayscaleFilter = remember(iconConfig.grayscale) {
        if (!iconConfig.grayscale) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    val labelFontFamily = rememberTileFontFamily(
        fontUri = tile.customFontUri,
        preloadedFonts = preloadedFonts,
        loadFontFamily = loadFontFamily,
    ) ?: defaultFontFamily

    BoxWithConstraints(modifier = modifier.padding(8.dp)) {
        val isHorizontal = iconConfig.placement == AppTileContentPlacement.LEFT || iconConfig.placement == AppTileContentPlacement.RIGHT
        val baseIconSize = minOf(maxWidth, maxHeight)
        val iconSize = baseIconSize * (0.38f * (iconConfig.iconScale ?: 1f))

        @Composable
        fun IconSlot() {
            when (iconConfig.source) {
                AppTileIconSource.APP, AppTileIconSource.CUSTOM -> {
                    if (iconPainter != null) {
                        Image(
                            painter = iconPainter,
                            contentDescription = tile.displayLabel,
                            modifier = Modifier.size(iconSize),
                            colorFilter = grayscaleFilter,
                        )
                    }
                }
                AppTileIconSource.MATERIAL -> {
                    materialIcon?.let {
                        Icon(
                            imageVector = it.imageVector,
                            contentDescription = tile.displayLabel,
                            modifier = Modifier.size(iconSize),
                            tint = defaultTextColor,
                        )
                    }
                }
                AppTileIconSource.NONE -> Unit
            }
        }

        @Composable
        fun LabelSlot() {
            if (!showLabel) return
            Text(
                text = tile.displayLabel,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize *
                        (tile.customTextScale ?: defaultTextScale),
                    fontFamily = labelFontFamily,
                ),
                color = defaultTextColor,
                fontWeight = tile.customBoldText?.let { if (it) FontWeight.Bold else FontWeight.Normal } ?: defaultFontWeight,
                textAlign = TextAlign.Center,
            )
        }

        if (!hasIcon) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LabelSlot()
            }
        } else {
            val iconFirst = iconConfig.placement == AppTileContentPlacement.TOP || iconConfig.placement == AppTileContentPlacement.LEFT
            if (isHorizontal) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (iconFirst) IconSlot()
                    if (showLabel) LabelSlot()
                    if (!iconFirst) IconSlot()
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (iconFirst) IconSlot()
                    if (showLabel) LabelSlot()
                    if (!iconFirst) IconSlot()
                }
            }
        }
    }
}

@Composable
private fun TileEditSheet(
    tile: TileState,
    tileLabelDraft: String,
    onLabelDraftChange: (String) -> Unit,
    onSaveLabel: () -> Unit,
    onResetLabel: () -> Unit,
    onResizeWidth: (Int) -> Unit,
    onResizeHeight: (Int) -> Unit,
    onTextScaleUp: () -> Unit,
    onTextScaleDown: () -> Unit,
    onTextScaleReset: () -> Unit,
    onIconScaleUp: () -> Unit,
    onIconScaleDown: () -> Unit,
    onIconScaleReset: () -> Unit,
    onBoldOn: () -> Unit,
    onBoldOff: () -> Unit,
    onBoldReset: () -> Unit,
    onOpenFont: () -> Unit,
    onAppIconConfigChange: ((AppTileIconConfig) -> Unit)?,
    onPickCustomIcon: (() -> Unit)?,
    onChooseMaterialIcon: (() -> Unit)?,
    onChangeApp: () -> Unit,
    onEditIntent: () -> Unit,
    onSliderConfigChange: ((SystemSliderConfig) -> Unit)?,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 40.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(2.dp),
                            ),
                    )

                    // Tile name + type header
                    val typeLabel = when (tile) {
                        is AppTileState -> "App • ${tile.app.label}"
                        is IntentTileState -> "Intent • ${tile.displayLabel}"
                        is WidgetTileState -> "Widget • ${tile.displayLabel}"
                        is SystemSliderTileState -> "${tile.config.sliderType.name.lowercase().replaceFirstChar { it.uppercase() }} Slider"
                    }
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Size ${tile.columnSpan}×${tile.rowSpan}" +
                            if (tile !is WidgetTileState && tile !is SystemSliderTileState) {
                                "  Text: ${tile.customTextScale?.let { "%.1f×".format(it) } ?: "default"}" +
                                    "  Bold: ${tile.customBoldText?.let { if (it) "on" else "off" } ?: "default"}"
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Rename
                    OutlinedTextField(
                        value = tileLabelDraft,
                        onValueChange = onLabelDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Rename Tile") },
                        placeholder = { Text("Use default name") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onSaveLabel) { Text("Save Name") }
                        OutlinedButton(onClick = onResetLabel) { Text("Reset Name") }
                    }

                    // Resize
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onResizeWidth(1) }) { Text("W+") }
                        OutlinedButton(onClick = { onResizeWidth(-1) }) { Text("W-") }
                        OutlinedButton(onClick = { onResizeHeight(1) }) { Text("H+") }
                        OutlinedButton(onClick = { onResizeHeight(-1) }) { Text("H-") }
                    }

                    // Text scale + bold (label/intent tiles only)
                    if (tile !is WidgetTileState && tile !is SystemSliderTileState) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onTextScaleUp) { Text("S+") }
                            OutlinedButton(onClick = onTextScaleDown) { Text("S-") }
                            OutlinedButton(onClick = onTextScaleReset) { Text("S Reset") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onBoldOn) { Text("Bold On") }
                            OutlinedButton(onClick = onBoldOff) { Text("Bold Off") }
                            OutlinedButton(onClick = onBoldReset) { Text("Bold Reset") }
                        }
                        OutlinedButton(onClick = onOpenFont, modifier = Modifier.fillMaxWidth()) { Text("Font Override") }
                    }

                    if (tile is AppTileState && onAppIconConfigChange != null) {
                        AppTileIconControls(
                            config = tile.iconConfig,
                            onConfigChange = onAppIconConfigChange,
                            onIconScaleUp = onIconScaleUp,
                            onIconScaleDown = onIconScaleDown,
                            onIconScaleReset = onIconScaleReset,
                            onPickCustomIcon = onPickCustomIcon,
                            onChooseMaterialIcon = onChooseMaterialIcon,
                        )
                    }

                    // Change app / edit intent
                    when (tile) {
                        is AppTileState -> OutlinedButton(onClick = onChangeApp, modifier = Modifier.fillMaxWidth()) { Text("Change App") }
                        is IntentTileState -> OutlinedButton(onClick = onEditIntent, modifier = Modifier.fillMaxWidth()) { Text("Edit Intent") }
                        else -> Unit
                    }

                    // Slider config (system slider tiles only)
                    if (tile is SystemSliderTileState && onSliderConfigChange != null) {
                        SliderConfigControls(
                            config = tile.config,
                            onConfigChange = onSliderConfigChange,
                        )
                    }

                    // Delete + Done
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                        Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppTileIconControls(
    config: AppTileIconConfig,
    onConfigChange: (AppTileIconConfig) -> Unit,
    onIconScaleUp: () -> Unit,
    onIconScaleDown: () -> Unit,
    onIconScaleReset: () -> Unit,
    onPickCustomIcon: (() -> Unit)?,
    onChooseMaterialIcon: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppTileIconSource.entries.forEach { source ->
                val label = when (source) {
                    AppTileIconSource.NONE -> "None"
                    AppTileIconSource.APP -> "App"
                    AppTileIconSource.MATERIAL -> "Material"
                    AppTileIconSource.CUSTOM -> "Custom"
                }
                val selected = config.source == source
                val onSelect = {
                    onConfigChange(
                        config.copy(
                            source = source,
                            materialIconKey = when (source) {
                                AppTileIconSource.MATERIAL -> config.materialIconKey ?: materialIconOptions.first().key
                                else -> config.materialIconKey
                            },
                        ),
                    )
                }
                if (selected) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = onSelect) { Text(label) }
                }
            }
        }

        if (config.source != AppTileIconSource.NONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show label", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(
                    checked = config.showLabel,
                    onCheckedChange = { onConfigChange(config.copy(showLabel = it)) },
                )
            }

            if (config.source == AppTileIconSource.APP || config.source == AppTileIconSource.CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Grayscale icon", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(
                        checked = config.grayscale,
                        onCheckedChange = { onConfigChange(config.copy(grayscale = it)) },
                    )
                }
            }

            if (config.showLabel) {
                Text("Placement", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppTileContentPlacement.entries.forEach { placement ->
                        val label = when (placement) {
                            AppTileContentPlacement.TOP -> "Top"
                            AppTileContentPlacement.LEFT -> "Left"
                            AppTileContentPlacement.BOTTOM -> "Bottom"
                            AppTileContentPlacement.RIGHT -> "Right"
                        }
                        if (config.placement == placement) {
                            Button(onClick = {}) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { onConfigChange(config.copy(placement = placement)) }) { Text(label) }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onIconScaleUp) { Text("I+") }
                OutlinedButton(onClick = onIconScaleDown) { Text("I-") }
                OutlinedButton(onClick = onIconScaleReset) { Text("I Reset") }
            }

            when (config.source) {
                AppTileIconSource.MATERIAL -> {
                    OutlinedButton(
                        onClick = { onChooseMaterialIcon?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Material Icon: ${materialIconForKey(config.materialIconKey)?.label ?: "Choose"}")
                    }
                }
                AppTileIconSource.CUSTOM -> {
                    OutlinedButton(
                        onClick = { onPickCustomIcon?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Custom PNG: ${config.customIconName ?: "Choose"}")
                    }
                }
                else -> Unit
            }
        }
    }
}

// ── Support composables ──────────────────────────────────────────────────────

@Composable
internal fun DraggableDpad(
    offsetX: Float,
    offsetY: Float,
    onOffsetChange: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    var displayOffsetX by remember { mutableFloatStateOf(offsetX) }
    var displayOffsetY by remember { mutableFloatStateOf(offsetY) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(offsetX, offsetY, isDragging) {
        if (!isDragging) { displayOffsetX = offsetX; displayOffsetY = offsetY }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(displayOffsetX.roundToInt(), displayOffsetY.roundToInt()) }
            .size(DPAD_SIZE_DP.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val buttonSize = DPAD_BUTTON_SIZE_DP.dp
                val dragBarTop = 10.dp
                val dragBarHeight = 10.dp
                val dragBarSpacing = 12.dp
                val bottomMargin = 8.dp
                val clusterTop = dragBarTop + dragBarHeight + dragBarSpacing
                val clusterHeightRaw = maxHeight - clusterTop - bottomMargin
                val clusterHeight = if (clusterHeightRaw < buttonSize) buttonSize else clusterHeightRaw
                val clusterWidth = maxWidth - 12.dp
                val crossSpan = min(clusterWidth.value, clusterHeight.value).dp
                val arm = ((crossSpan - buttonSize) / 2).coerceAtLeast(0.dp)
                val centerX = maxWidth / 2
                val centerY = clusterTop + (clusterHeight / 2)
                val buttonBaseX = centerX - (buttonSize / 2)
                val buttonBaseY = centerY - (buttonSize / 2)

                DragHandle(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = dragBarTop)
                        .size(width = maxWidth * 0.56f, height = dragBarHeight),
                    currentOffsetX = displayOffsetX,
                    currentOffsetY = displayOffsetY,
                    onDragStarted = { isDragging = true },
                    onOffsetChange = { x, y -> displayOffsetX = x; displayOffsetY = y; onOffsetChange(x, y) },
                    onDragFinished = { isDragging = false; onDragFinished() },
                )
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX, y = buttonBaseY - arm), direction = ArrowDirection.Up, onClick = onMoveUp)
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX - arm, y = buttonBaseY), direction = ArrowDirection.Left, onClick = onMoveLeft)
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX + arm, y = buttonBaseY), direction = ArrowDirection.Right, onClick = onMoveRight)
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX, y = buttonBaseY + arm), direction = ArrowDirection.Down, onClick = onMoveDown)
            }
        }
    }
}

internal enum class ArrowDirection { Up, Down, Left, Right }

@Composable
internal fun DirectionButton(modifier: Modifier = Modifier, direction: ArrowDirection, onClick: () -> Unit) {
    Surface(
        modifier = modifier.size(DPAD_BUTTON_SIZE_DP.dp).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { ArrowIcon(direction = direction) }
    }
}

@Composable
internal fun ArrowIcon(direction: ArrowDirection, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = 3.5f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val shaft = size.minDimension * 0.28f
        val head = size.minDimension * 0.18f
        val (start, end, headA, headB) = when (direction) {
            ArrowDirection.Up -> Quad(Offset(cx, cy + shaft), Offset(cx, cy - shaft), Offset(cx - head, cy - shaft + head), Offset(cx + head, cy - shaft + head))
            ArrowDirection.Down -> Quad(Offset(cx, cy - shaft), Offset(cx, cy + shaft), Offset(cx - head, cy + shaft - head), Offset(cx + head, cy + shaft - head))
            ArrowDirection.Left -> Quad(Offset(cx + shaft, cy), Offset(cx - shaft, cy), Offset(cx - shaft + head, cy - head), Offset(cx - shaft + head, cy + head))
            ArrowDirection.Right -> Quad(Offset(cx - shaft, cy), Offset(cx + shaft, cy), Offset(cx + shaft - head, cy - head), Offset(cx + shaft - head, cy + head))
        }
        drawLine(color, start, end, stroke)
        drawLine(color, end, headA, stroke)
        drawLine(color, end, headB, stroke)
    }
}

private data class Quad(val a: Offset, val b: Offset, val c: Offset, val d: Offset)

@Composable
internal fun DragHandle(
    modifier: Modifier = Modifier,
    currentOffsetX: Float,
    currentOffsetY: Float,
    onDragStarted: () -> Unit,
    onOffsetChange: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
) {
    val latestOffsetX by rememberUpdatedState(currentOffsetX)
    val latestOffsetY by rememberUpdatedState(currentOffsetY)

    Surface(
        modifier = modifier.pointerInput(Unit) {
            var startX = 0f
            var startY = 0f
            var totalX = 0f
            var totalY = 0f
            detectDragGestures(
                onDragStart = { onDragStarted(); startX = latestOffsetX; startY = latestOffsetY; totalX = 0f; totalY = 0f },
                onDragEnd = onDragFinished,
                onDragCancel = onDragFinished,
                onDrag = { change, drag ->
                    change.consume()
                    totalX += drag.x
                    totalY += drag.y
                    onOffsetChange(startX + totalX, startY + totalY)
                },
            )
        },
        color = Color.White.copy(alpha = 0.28f),
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
    ) { Box(modifier = Modifier.fillMaxSize()) }
}

@Composable
internal fun rememberTileFontFamily(
    fontUri: String?,
    preloadedFonts: Map<String, FontFamily?>,
    loadFontFamily: (String?) -> FontFamily?,
): FontFamily? {
    var fontFamily by remember(fontUri) { mutableStateOf(fontUri?.let { preloadedFonts[it] }) }

    LaunchedEffect(fontUri) {
        if (fontFamily == null && fontUri != null) {
            fontFamily = withContext(Dispatchers.IO) { loadFontFamily(fontUri) }
        }
    }

    return fontFamily
}

internal fun resolveDefaultTileTextColor(mode: DefaultTextColorMode, hex: String?, fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    when (mode) {
        DefaultTextColorMode.SYSTEM -> fallback
        DefaultTextColorMode.BLACK -> Color.Black
        DefaultTextColorMode.WHITE -> Color.White
        DefaultTextColorMode.CUSTOM -> normalizeHexColor(hex)?.let { raw ->
            runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrNull()
        } ?: fallback
    }

@Composable
internal fun PanelHandle(
    currentOffsetX: Float,
    currentOffsetY: Float,
    locked: Boolean,
    onOffsetChange: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    onToggle: () -> Unit,
) {
    val latestOffsetX by rememberUpdatedState(currentOffsetX)
    val latestOffsetY by rememberUpdatedState(currentOffsetY)

    Box(
        modifier = Modifier
            .size(PANEL_HANDLE_SIZE_DP.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    if (!locked) {
                        var startX = 0f
                        var startY = 0f
                        var totalX = 0f
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                startX = latestOffsetX
                                startY = latestOffsetY
                                totalX = 0f
                                totalY = 0f
                            },
                            onDragEnd = onDragFinished,
                            onDragCancel = onDragFinished,
                            onDrag = { change, drag ->
                                change.consume()
                                totalX += drag.x
                                totalY += drag.y
                                onOffsetChange(startX + totalX, startY + totalY)
                            },
                        )
                    }
                },
            color = Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineW = size.width * 0.5f
                val strokePx = 2.dp.toPx()
                val gap = 5.dp.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                for (i in -1..1) {
                    val y = cy + i * gap
                    drawLine(
                        color = Color.White.copy(alpha = 0.75f),
                        start = Offset(cx - lineW / 2f, y),
                        end = Offset(cx + lineW / 2f, y),
                        strokeWidth = strokePx,
                    )
                }
            }
        }
    }
}

// ── Slider config controls (used in TileEditSheet and SystemWidgetConfigScreen) ──

@Composable
internal fun SliderConfigControls(
    config: SystemSliderConfig,
    onConfigChange: (SystemSliderConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (config.sliderType == SliderType.VOLUME) {
            Text("Stream", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StreamMode.entries.forEach { mode ->
                    val selected = config.streamMode == mode
                    if (selected) {
                        Button(onClick = {}) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    } else {
                        OutlinedButton(onClick = { onConfigChange(config.copy(streamMode = mode)) }) {
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
            if (config.streamMode == StreamMode.SINGLE) {
                Text("Stream type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AudioStreamType.entries.forEach { stream ->
                        val selected = config.singleStream == stream
                        if (selected) {
                            Button(onClick = {}) { Text(stream.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        } else {
                            OutlinedButton(onClick = { onConfigChange(config.copy(singleStream = stream)) }) {
                                Text(stream.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }
        }

        Text("± Buttons", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SliderButtonPlacement.entries.forEach { placement ->
                val selected = config.buttonPlacement == placement
                val label = when (placement) {
                    SliderButtonPlacement.TOP -> "Top"
                    SliderButtonPlacement.BOTTOM -> "Bottom"
                    SliderButtonPlacement.SPLIT -> "Split"
                    SliderButtonPlacement.NONE -> "None"
                }
                if (selected) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onConfigChange(config.copy(buttonPlacement = placement)) }) { Text(label) }
                }
            }
        }

        Text("Notch behavior", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SliderNotchMode.entries.forEach { mode ->
                val selected = config.notchMode == mode
                val label = when (mode) {
                    SliderNotchMode.LOCK_ONLY -> "Lock"
                    SliderNotchMode.LOCK_AND_SLIDE -> "Lock+Slide"
                    SliderNotchMode.SLIDE_ONLY -> "Slide"
                }
                if (selected) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onConfigChange(config.copy(notchMode = mode)) }) { Text(label) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show notches", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(
                checked = config.showNotches,
                onCheckedChange = { onConfigChange(config.copy(showNotches = it)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Thin outline", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(
                checked = config.showOutline,
                onCheckedChange = { onConfigChange(config.copy(showOutline = it)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Button haptics", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(
                checked = config.buttonHapticsEnabled,
                onCheckedChange = { onConfigChange(config.copy(buttonHapticsEnabled = it)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Notch haptics", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(
                checked = config.notchHapticsEnabled,
                onCheckedChange = { onConfigChange(config.copy(notchHapticsEnabled = it)) },
            )
        }

        if (config.buttonPlacement != SliderButtonPlacement.NONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Button step size", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (config.buttonStepSize > 1) onConfigChange(config.copy(buttonStepSize = config.buttonStepSize - 1)) },
                        modifier = Modifier.size(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("-") }
                    Text("${config.buttonStepSize}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { onConfigChange(config.copy(buttonStepSize = config.buttonStepSize + 1)) },
                        modifier = Modifier.size(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("+") }
                }
            }
        }
    }
}
