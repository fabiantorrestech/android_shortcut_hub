package com.fabiantorrestech.androidshortcuthub

import androidx.compose.ui.text.font.FontFamily

internal object OverlayRuntimeCache {
    private val stateLock = Any()
    private val fontLock = Any()

    private var cachedRawState: String? = null
    private var cachedPortraitLayout: OverlayOrientationLayout? = null
    private var cachedLandscapeLayout: OverlayOrientationLayout? = null

    private val cachedFonts = HashMap<String, FontFamily?>()

    internal fun getCachedLayouts(rawState: String?): Pair<OverlayOrientationLayout, OverlayOrientationLayout?>? {
        synchronized(stateLock) {
            if (cachedPortraitLayout != null && cachedRawState == rawState) {
                return cachedPortraitLayout!! to cachedLandscapeLayout
            }
            return null
        }
    }

    internal fun updateLayouts(
        rawState: String?,
        portrait: OverlayOrientationLayout,
        landscape: OverlayOrientationLayout?,
    ) {
        synchronized(stateLock) {
            cachedRawState = rawState
            cachedPortraitLayout = portrait
            cachedLandscapeLayout = landscape
        }
    }

    internal fun invalidate() {
        synchronized(stateLock) {
            cachedRawState = null
            cachedPortraitLayout = null
            cachedLandscapeLayout = null
        }
    }

    internal fun preloadFonts(
        portraitState: OverlayUiState,
        landscapeState: OverlayUiState,
        loadFontFamily: (String?) -> FontFamily?,
    ): Map<String, FontFamily?> {
        val requiredUris = requiredFontUris(portraitState) + requiredFontUris(landscapeState)
        val loadedFonts = LinkedHashMap<String, FontFamily?>()
        requiredUris.forEach { uri ->
            val alreadyCached = synchronized(fontLock) {
                if (cachedFonts.containsKey(uri)) {
                    loadedFonts[uri] = cachedFonts[uri]
                    true
                } else false
            }
            if (!alreadyCached) {
                val loaded = loadFontFamily(uri)
                synchronized(fontLock) { cachedFonts[uri] = loaded }
                loadedFonts[uri] = loaded
            }
        }
        return loadedFonts
    }

    internal fun preloadFonts(
        state: OverlayUiState,
        loadFontFamily: (String?) -> FontFamily?,
    ): Map<String, FontFamily?> = preloadFonts(state, state, loadFontFamily)

    internal fun cachedFontsFor(
        portraitState: OverlayUiState,
        landscapeState: OverlayUiState,
    ): Map<String, FontFamily?> {
        val requiredUris = requiredFontUris(portraitState) + requiredFontUris(landscapeState)
        return synchronized(fontLock) {
            buildMap {
                requiredUris.forEach { uri ->
                    if (cachedFonts.containsKey(uri)) put(uri, cachedFonts[uri])
                }
            }
        }
    }

    internal fun cachedFontsFor(state: OverlayUiState): Map<String, FontFamily?> =
        cachedFontsFor(state, state)

    private fun requiredFontUris(state: OverlayUiState): Set<String> = buildSet {
        state.defaultFontUri?.let { add(it) }
        state.tiles.forEach { it.customFontUri?.let(::add) }
    }
}
