package com.fabiantorrestech.androidshortcuthub

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class PickTileIconActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_TILE_ID = "extra_tile_id"

        fun createIntent(
            context: Context,
            tileId: Int,
        ): Intent {
            return Intent(context, PickTileIconActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TILE_ID, tileId)
            }
        }
    }

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val tileId = intent?.getIntExtra(EXTRA_TILE_ID, -1) ?: -1
        if (uri != null && tileId >= 0) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val displayName = resolveIconDisplayName(contentResolver, uri)
                ?: uri.lastPathSegment
                ?: "Selected icon"
            ShortcutHubOverlayService.dispatchTileIconPicked(
                tileId = tileId,
                uriString = uri.toString(),
                iconName = displayName,
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerLauncher.launch(arrayOf("image/png", "image/*"))
    }
}

private fun resolveIconDisplayName(
    contentResolver: ContentResolver,
    uri: Uri,
): String? {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
    }.getOrNull()
}
