package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val AUTO_BACKUP_SUBFOLDER = "shortcut_hub_autobackup"
private const val AUTO_BACKUP_FILE = "shortcut_hub_autobackup.json"

// Stores auto-backup toggle + chosen directory URI, separately from ShortcutHubConfig
// so these meta-settings are never overwritten by a restore.
object BackupPrefs {
    private const val PREFS_NAME = "shortcut_hub_backup_prefs"
    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_DIRECTORY_URI = "auto_backup_directory_uri"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getDirectoryUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DIRECTORY_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setDirectoryUri(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (uri != null) putString(KEY_DIRECTORY_URI, uri.toString())
            else remove(KEY_DIRECTORY_URI)
        }.apply()
    }

    // Best-effort human-readable label for the chosen folder.
    fun getDirectoryDisplayName(context: Context): String? {
        val treeUri = getDirectoryUri(context) ?: return null
        return runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':') }.getOrNull()
    }
}

object BackupManager {

    fun buildBackupJson(context: Context): String {
        val config = ShortcutHubSettings.load(context)
        val layoutRaw = context
            .getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(OVERLAY_PREFS_KEY_STATE, null)

        val settingsJson = JSONObject().apply {
            put("gridRows", config.gridRows)
            put("gridColumns", config.gridColumns)
            put("defaultTextScale", config.defaultTextScale)
            put("defaultBoldText", config.defaultBoldText)
            config.defaultFontUri?.let { put("defaultFontUri", it) }
            config.defaultFontName?.let { put("defaultFontName", it) }
            put("defaultTextColorMode", config.defaultTextColorMode.name)
            config.defaultTextColorHex?.let { put("defaultTextColorHex", it) }
            put("hapticFeedbackEnabled", config.hapticFeedbackEnabled)
            put("panelHandleLocked", config.panelHandleLocked)
            put("showPanelHandle", config.showPanelHandle)
            put("overlayBackgroundAlpha", config.overlayBackgroundAlpha)
            put("showOverLockscreen", config.showOverLockscreen)
            put("dismissAccessibilityBanner", config.dismissAccessibilityBanner)
            put("useAccessibilityService", config.useAccessibilityService)
            put("dismissOnScreenOff", config.dismissOnScreenOff)
        }

        val layoutJson = layoutRaw
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()

        return JSONObject().apply {
            put("version", 1)
            put(
                "timestamp",
                Instant.now()
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            )
            put("settings", settingsJson)
            put("layout", layoutJson)
        }.toString(2)
    }

    fun restoreFromJson(context: Context, json: String): Boolean {
        return runCatching {
            val root = JSONObject(json)

            val s = root.optJSONObject("settings") ?: return@runCatching false
            val config = ShortcutHubConfig(
                gridRows = s.optInt("gridRows", 8).coerceIn(1, 24),
                gridColumns = s.optInt("gridColumns", 4).coerceIn(1, 16),
                defaultTextScale = s.optDouble("defaultTextScale", 1.0).toFloat().coerceIn(0.5f, 3.0f),
                defaultBoldText = s.optBoolean("defaultBoldText", false),
                defaultFontUri = s.optString("defaultFontUri", "").ifEmpty { null },
                defaultFontName = s.optString("defaultFontName", "").ifEmpty { null },
                defaultTextColorMode = s.optString("defaultTextColorMode", "")
                    .let { raw -> DefaultTextColorMode.entries.firstOrNull { it.name == raw } }
                    ?: DefaultTextColorMode.SYSTEM,
                defaultTextColorHex = normalizeHexColor(s.optString("defaultTextColorHex", "")),
                hapticFeedbackEnabled = s.optBoolean("hapticFeedbackEnabled", true),
                panelHandleLocked = s.optBoolean("panelHandleLocked", false),
                showPanelHandle = s.optBoolean("showPanelHandle", true),
                overlayBackgroundAlpha = s.optDouble("overlayBackgroundAlpha", 0.33).toFloat().coerceIn(0f, 0.9f),
                showOverLockscreen = s.optBoolean("showOverLockscreen", false),
                dismissAccessibilityBanner = s.optBoolean("dismissAccessibilityBanner", false),
                useAccessibilityService = s.optBoolean("useAccessibilityService", false),
                dismissOnScreenOff = s.optBoolean("dismissOnScreenOff", true),
            )
            ShortcutHubSettings.save(context, config)

            val layoutJson = root.optJSONObject("layout")
            if (layoutJson != null && layoutJson.length() > 0) {
                context.getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(OVERLAY_PREFS_KEY_STATE, layoutJson.toString())
                    .apply()
            }
            true
        }.getOrDefault(false)
    }

    // Called by ShortcutHubApplication on every debounced prefs change.
    fun writeAutoBackup(context: Context) {
        if (!BackupPrefs.isEnabled(context)) return
        val treeUri = BackupPrefs.getDirectoryUri(context) ?: return
        writeToTree(context, treeUri)
    }

    // Returns epoch-ms of the backup file's last modification, or null if not yet written.
    fun queryAutoBackupLastModifiedMs(context: Context): Long? {
        val treeUri = BackupPrefs.getDirectoryUri(context) ?: return null
        val resolver = context.contentResolver

        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val subfolderUri = findChildUri(resolver, treeUri, rootDocId, AUTO_BACKUP_SUBFOLDER) ?: return null
        val subfolderDocId = DocumentsContract.getDocumentId(subfolderUri)
        val fileUri = findChildUri(resolver, treeUri, subfolderDocId, AUTO_BACKUP_FILE) ?: return null

        return resolver.query(
            fileUri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun writeToTree(context: Context, treeUri: Uri) {
        val json = buildBackupJson(context)
        val resolver = context.contentResolver

        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return

        val subfolderUri = findOrCreateChild(
            resolver, treeUri, rootDocId, AUTO_BACKUP_SUBFOLDER,
            DocumentsContract.Document.MIME_TYPE_DIR,
        ) ?: return

        val subfolderDocId = DocumentsContract.getDocumentId(subfolderUri)
        val fileUri = findOrCreateChild(
            resolver, treeUri, subfolderDocId, AUTO_BACKUP_FILE,
            "application/json",
        ) ?: return

        resolver.openOutputStream(fileUri, "wt")?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    private fun findChildUri(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        displayName: String,
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun findOrCreateChild(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        displayName: String,
        mimeType: String,
    ): Uri? {
        findChildUri(resolver, treeUri, parentDocId, displayName)?.let { return it }
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        return runCatching {
            DocumentsContract.createDocument(resolver, parentDocUri, mimeType, displayName)
        }.getOrNull()
    }
}

// Called from MainActivity after the user picks a directory.
fun persistTreeUriPermission(context: Context, treeUri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
    BackupPrefs.setDirectoryUri(context, treeUri)
}
