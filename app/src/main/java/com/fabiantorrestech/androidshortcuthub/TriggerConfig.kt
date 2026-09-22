package com.fabiantorrestech.androidshortcuthub

/**
 * Configuration for the ways the hub can be invoked.
 *
 * Deliberately stored separately from [ShortcutHubConfig]: this feature needs ~25 scalars plus
 * two app lists, and every field added to ShortcutHubConfig costs four edits there plus a fifth
 * in BackupManager. That pattern has already drifted once (launchAnimationEnabled silently fell
 * out of backups). A self-contained config with its own repository and a single JSON blob
 * means a new field costs two edits here and BackupManager never changes again.
 */

data class TriggerAppEntry(
    val packageName: String,
    val label: String,
)

enum class EdgeSide {
    LEFT,
    RIGHT,
}

enum class EdgeActivation {
    /**
     * Drag up or down *parallel* to the edge. The default, and the only mode that is immune to
     * the system Back gesture by construction: SystemUI's EdgeBackGestureHandler cancels Back as
     * soon as vertical travel dominates horizontal travel, so this motion can never be read as
     * Back and needs no gesture-exclusion rects.
     */
    DRAG_ALONG,

    /** A stationary tap. Also Back-safe — Back requires travel before it arms. */
    TAP,

    /** Either of the above. */
    DRAG_OR_TAP,

    /**
     * Swipe inward, away from the edge. The familiar sidebar-launcher motion, but this is exactly
     * the Back gesture: it works only while system gesture exclusion is honoured for our window.
     */
    SWIPE_IN,

    /** Press and hold in place. Back-safe (no travel), at the cost of the dwell time. */
    LONG_PRESS,
}

enum class EdgeVisibility {
    /** Never drawn. The band still receives touches. */
    INVISIBLE,

    /** Invisible at rest; shows on contact and fades back as soon as the finger lifts. */
    REVEAL_ON_CONTACT,

    /** Rests dim, brightens on contact, settles back as soon as the finger lifts. */
    DIM,

    /** Always drawn at the resting alpha, brightening on contact. */
    ALWAYS_VISIBLE,
}

enum class TriggerFilterMode {
    WHITELIST,
    BLACKLIST,
}

/** Which affordance fired an invocation. Used for logging and the re-fire cooldown. */
enum class TriggerSource {
    EDGE_LEFT,
    EDGE_RIGHT,
    A11Y_BUTTON,
    ASSIST,
}

data class EdgeHandleConfig(
    val side: EdgeSide,
    val enabled: Boolean = false,
    /** How far out from the edge the touch zone reaches. Wider than the drawn bar on purpose. */
    val touchDepthDp: Int = 20,
    /** How far the drawn bar protrudes from the edge. */
    val protrusionDp: Int = 4,
    /** Size of the band along the edge. Capped at 200dp to stay under the gesture-exclusion cap. */
    val lengthDp: Int = 160,
    /** 0..1 position of the band's centre down the edge. 0.62 is roughly where a thumb rests. */
    val verticalBiasFraction: Float = 0.62f,
    val activation: EdgeActivation = EdgeActivation.DRAG_ALONG,
    val activationSlopDp: Int = 32,
    val longPressMs: Int = 260,
    val visibility: EdgeVisibility = EdgeVisibility.REVEAL_ON_CONTACT,
    val restingAlpha: Float = 0.35f,
    val activeAlpha: Float = 0.85f,
    /** null = follow the theme's onSurface colour. */
    val colorHex: String? = null,
    val cornerRadiusDp: Int = 8,
) {
    /** True when this mode relies on inward travel, and therefore on gesture exclusion. */
    val usesInwardSwipe: Boolean get() = activation == EdgeActivation.SWIPE_IN

    val usesLongPress: Boolean
        get() = activation == EdgeActivation.LONG_PRESS

    val usesTap: Boolean
        get() = activation == EdgeActivation.TAP || activation == EdgeActivation.DRAG_OR_TAP

    val usesDragAlong: Boolean
        get() = activation == EdgeActivation.DRAG_ALONG || activation == EdgeActivation.DRAG_OR_TAP
}

data class TriggerConfig(
    /**
     * Master switch, off by default and deliberately so: a resident touchable window that appears
     * on upgrade without the user asking for it reads as "my phone is broken".
     */
    val masterEnabled: Boolean = false,
    val leftHandle: EdgeHandleConfig = EdgeHandleConfig(EdgeSide.LEFT),
    val rightHandle: EdgeHandleConfig = EdgeHandleConfig(EdgeSide.RIGHT, enabled = true),
    /** Applies gesture-exclusion rects so an inward swipe isn't stolen by Back. SWIPE_IN only. */
    val suppressBackGesture: Boolean = true,
    val showOnLockscreen: Boolean = true,
    /** Heuristic, hence off by default. See EdgeTriggerController. */
    val suppressWhenImmersive: Boolean = false,
    /**
     * One buzz each time the hub opens, whichever trigger opened it. Replaces the edge handles'
     * own buzz, so a handle swipe never vibrates twice. See vibrateForHubOpen.
     */
    val vibrateOnOpen: Boolean = true,
    /** Off: follow the sound mode (buzz in Ring and Vibrate, not in Silent). On: buzz regardless. */
    val vibrateInSilentMode: Boolean = false,
    val refireCooldownMs: Int = 350,
    val filterMode: TriggerFilterMode = TriggerFilterMode.BLACKLIST,
    val blockApps: List<TriggerAppEntry> = emptyList(),
    val allowApps: List<TriggerAppEntry> = emptyList(),
    val assistGestureEnabled: Boolean = false,
    /**
     * Shows the handles pinned fully opaque while the Triggers tab is open, so geometry and
     * colour changes are visible as they are made. Purely an authoring aid — it is scoped to that
     * tab being on screen and never affects normal use.
     */
    val livePreviewEnabled: Boolean = true,
) {
    fun handleFor(side: EdgeSide): EdgeHandleConfig =
        if (side == EdgeSide.LEFT) leftHandle else rightHandle

    fun withHandle(handle: EdgeHandleConfig): TriggerConfig =
        if (handle.side == EdgeSide.LEFT) copy(leftHandle = handle) else copy(rightHandle = handle)
}
