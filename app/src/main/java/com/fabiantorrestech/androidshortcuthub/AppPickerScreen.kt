package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen app picker.
 *
 * Replaces the chooser that used to render inline, inside the tile inspector's own scroll view, in
 * a 160dp-tall box whose list was capped at 30 entries — so with an empty search every app after
 * the thirtieth alphabetically was simply unreachable, and the list appeared *below* the whole icon
 * section rather than near the button that opened it. Given the screen to itself it can show
 * everything, and the search field stays put while the results scroll under it.
 *
 * Entered the same way the container editors are: the host sets a tile id and returns early, so the
 * picker owns the screen and inherits one level of back for free.
 */
@Composable
internal fun AppPickerScreen(
    onAppSelected: (LaunchableApp) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showManualEntry by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        runCatching { withContext(Dispatchers.IO) { loadInstalledLaunchableAppsCached(context) } }
            .onSuccess { apps = it }
        isLoading = false
    }

    // No take(N) here — the whole point of the rewrite is that every installed app is reachable.
    val filtered = remember(apps, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Choose app",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = { showManualEntry = !showManualEntry }) {
                Icon(Icons.Default.Edit, contentDescription = "Enter a package name")
            }
        }

        val focusRequester = remember { FocusRequester() }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .focusRequester(focusRequester),
            label = { Text("Search apps") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

        if (showManualEntry) {
            ManualPackageEntry(
                context = context,
                onResolved = onAppSelected,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            HorizontalDivider()
        }

        when {
            // The list loads behind the search field rather than replacing it, so typing can start
            // immediately on a cold cache.
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            filtered.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (apps.isEmpty()) "No launchable apps found." else "No apps match \"${searchQuery.trim()}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { "${it.label}|${it.packageName}" }) { app ->
                    AppRow(app = app, onClick = { onAppSelected(app) })
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: LaunchableApp, onClick: () -> Unit) {
    val context = LocalContext.current
    // Loaded per row, so only the handful of visible rows ever touch PackageManager. LazyColumn
    // disposes off-screen rows, which is what keeps this cheap on a device with hundreds of apps.
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = runCatching { withContext(Dispatchers.IO) { loadAppIcon(context, app.packageName) } }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The manual package-name escape hatch the inline chooser always showed. Kept, because it is the
 * only way to target an app the launcher enumeration misses, but moved behind a toggle so it stops
 * competing with the list for attention.
 */
@Composable
private fun ManualPackageEntry(
    context: Context,
    onResolved: (LaunchableApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Enter a package name",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Package name") },
            placeholder = { Text("com.example.app") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
        )
        OutlinedButton(
            onClick = {
                val pkg = input.trim()
                if (pkg.isEmpty()) {
                    error = "Enter a package name"
                    return@OutlinedButton
                }
                val pm = context.packageManager
                val component = pm.getLaunchIntentForPackage(pkg)?.component
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull()
                if (label == null && component == null) {
                    error = "Package not found on this device"
                    return@OutlinedButton
                }
                onResolved(LaunchableApp(label = label ?: pkg, componentName = component))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use package") }
    }
}

/**
 * Rasterises an app icon for Compose. Adaptive icons are [Drawable]s with no bitmap of their own,
 * so they have to be drawn into one rather than unwrapped.
 */
private fun loadAppIcon(context: Context, packageName: String): ImageBitmap? {
    if (packageName.isBlank()) return null
    val drawable: Drawable = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull() ?: return null

    (drawable as? BitmapDrawable)?.bitmap?.let { return it.asImageBitmap() }

    val size = APP_ICON_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/** Comfortably above the 40dp the row draws at, on every density this app supports. */
private const val APP_ICON_PX = 144
