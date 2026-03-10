/*
 * ArchiveTune Project
 * Floating Lyrics Settings Toggle
 *
 * WHERE TO PLACE THIS FILE:
 * app/src/main/kotlin/moe/koiverse/imagination/lyrics/overlay/FloatingLyricsSettings.kt
 */

package moe.koiverse.imagination.lyrics.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Drop FloatingLyricsSettingItem() into your existing Settings screen composable.
 *
 * It will show a toggle row that:
 *  - Checks if SYSTEM_ALERT_WINDOW permission is granted
 *  - If not, opens Android settings to request it
 *  - If yes, starts/stops FloatingLyricsService
 *  - Remembers the user's choice across app restarts
 */
@Composable
fun FloatingLyricsSettingItem() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("floating_lyrics_prefs", Context.MODE_PRIVATE)
    }

    var hasPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var isEnabled by remember {
        mutableStateOf(prefs.getBoolean("enabled", false))
    }

    // Re-check permission every time this composable becomes visible
    LaunchedEffect(Unit) {
        hasPermission = Settings.canDrawOverlays(context)
    }

    Column {
        ListItem(
            headlineContent = { Text("Floating Lyrics") },
            supportingContent = {
                Text(
                    when {
                        !hasPermission -> "Tap to grant 'Display over other apps' permission"
                        isEnabled      -> "Lyrics panel is showing on your home screen"
                        else           -> "Show time-synced lyrics floating over your screen"
                    }
                )
            },
            trailingContent = {
                Switch(
                    checked = isEnabled && hasPermission,
                    onCheckedChange = { on ->
                        if (on && !hasPermission) {
                            // Send user to Android overlay permission screen
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        } else {
                            isEnabled = on
                            prefs.edit().putBoolean("enabled", on).apply()
                            if (on) FloatingLyricsService.start(context)
                            else    FloatingLyricsService.stop(context)
                        }
                    }
                )
            }
        )

        // Show warning banner if permission is missing
        if (!hasPermission) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text     = "Permission required",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                    }
                ) {
                    Text("Grant")
                }
            }
        }

        HorizontalDivider()
    }
}

/**
 * Call this in MusicService.onCreate() to auto-restart the overlay
 * if the user had it enabled before the app was killed.
 */
fun Context.startFloatingLyricsIfEnabled() {
    val prefs = getSharedPreferences("floating_lyrics_prefs", Context.MODE_PRIVATE)
    val enabled = prefs.getBoolean("enabled", false)
    val hasPermission = Settings.canDrawOverlays(this)
    if (enabled && hasPermission) {
        FloatingLyricsService.start(this)
    }
}
