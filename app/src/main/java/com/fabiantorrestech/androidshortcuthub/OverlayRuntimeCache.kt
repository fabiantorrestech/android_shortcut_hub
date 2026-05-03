package com.fabiantorrestech.androidshortcuthub

import androidx.compose.ui.text.font.FontFamily

internal object OverlayRuntimeCache {
    private val stateLock = Any()
    private val fontLock = Any()

    private var cachedRawState: String? = null
    private var cachedConfig: ShortcutHubConfig? = null
    private var cachedOverlayState: OverlayUiState? = null

    private val cachedFonts = HashMap<String, FontFamily?>()

    internal fun getOrLoadState(
        rawState: String?,
        config: ShortcutHubConfig,
        loader: () -> OverlayUiState,
    ): OverlayUiState {
        synchronized(stateLock) {
            val cached = cachedOverlayState
            if (cached != null && cachedRawState == rawState && cachedConfig == config) {
                return cached
            }
        }

        val loaded = loader()
        synchronized(stateLock) {
            cachedRawState = rawState
            cachedConfig = config
            cachedOverlayState = loaded
        }
        return loaded
    }

    internal fun updateState(
        rawState: String?,
        config: ShortcutHubConfig,
        state: OverlayUiState,
    ) {
        synchronized(stateLock) {
            cachedRawState = rawState
            cachedConfig = config
            cachedOverlayState = state
        }
    }

    internal fun preloadFonts(
        state: OverlayUiState,
        loadFontFamily: (String?) -> FontFamily?,
    ): Map<String, FontFamily?> {
        val requiredUris = buildSet<String> {
            state.defaultFontUri?.let { add(it) }
            state.tiles.forEach { it.customFontUri?.let(::add) }
        }

        val loadedFonts = LinkedHashMap<String, FontFamily?>()
        requiredUris.forEach { uri ->
            val cached = synchronized(fontLock) { cachedFonts[uri] }
            if (cached != null || synchronized(fontLock) { cachedFonts.containsKey(uri) }) {
                loadedFonts[uri] = cached
            } else {
                val loaded = loadFontFamily(uri)
                synchronized(fontLock) {
                    cachedFonts[uri] = loaded
                }
                loadedFonts[uri] = loaded
            }
        }
        return loadedFonts
    }
}
