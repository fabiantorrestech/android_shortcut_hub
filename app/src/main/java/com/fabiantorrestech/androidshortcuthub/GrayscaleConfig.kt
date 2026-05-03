package com.fabiantorrestech.androidshortcuthub

data class GrayscaleAppEntry(
    val packageName: String,
    val label: String,
)

enum class GrayscaleFilterMode {
    WHITELIST,
    BLACKLIST,
}

enum class GrayscaleMode {
    /** One-shot snapshot via AccessibilityService.takeScreenshot(). No permission dialog. */
    SIMPLE,
    /** Live 30fps capture via MediaProjection. Requires screen-capture consent once per session. */
    ADVANCED,
}

data class GrayscaleConfig(
    val enabled: Boolean = false,
    val captureMode: GrayscaleMode = GrayscaleMode.SIMPLE,
    val activeMode: GrayscaleFilterMode = GrayscaleFilterMode.BLACKLIST,
    val whitelistApps: List<GrayscaleAppEntry> = emptyList(),
    val blacklistApps: List<GrayscaleAppEntry> = emptyList(),
)
