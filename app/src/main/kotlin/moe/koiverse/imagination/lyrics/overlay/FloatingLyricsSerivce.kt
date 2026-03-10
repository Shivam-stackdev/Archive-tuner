/*
 * ArchiveTune Project
 * Floating Lyrics Overlay — QQ Music style
 * Drop this file into:
 * app/src/main/kotlin/moe/koiverse/imagination/lyrics/overlay/FloatingLyricsService.kt
 */

package moe.koiverse.imagination.lyrics.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.platform.compose.view.ComposeView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.koiverse.imagination.R
import moe.koiverse.imagination.lyrics.LyricsEntry

class FloatingLyricsService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    // ── Lifecycle boilerplate for ComposeView inside a Service ───────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var wm: WindowManager
    private lateinit var composeView: android.view.View
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Static state bus — call from MusicService ────────────────────────────
    companion object {
        private val _lyricLine = MutableStateFlow("")
        private val _songTitle = MutableStateFlow("")
        private val _artist    = MutableStateFlow("")

        val lyricLine: StateFlow<String> = _lyricLine
        val songTitle: StateFlow<String> = _songTitle
        val artist:    StateFlow<String> = _artist

        // Call this from your MusicService tick every ~200ms
        fun updateLyric(line: String, title: String = "", artist: String = "") {
            _lyricLine.value = line
            _songTitle.value = title
            _artist.value    = artist
        }

        fun clearLyric() { _lyricLine.value = "" }

        fun start(context: Context) {
            val i = Intent(context, FloatingLyricsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i)
            else
                context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingLyricsService::class.java))
        }

        // Helper: find current lyric line from a sorted LyricsEntry list + position
        // Call this inside your MusicService tick and pass result to updateLyric()
        fun currentLine(entries: List<LyricsEntry>, positionMs: Long): String {
            if (entries.isEmpty()) return ""
            var lo = 0; var hi = entries.size - 1; var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (entries[mid].time <= positionMs) { best = mid; lo = mid + 1 }
                else hi = mid - 1
            }
            return if (best >= 0) entries[best].text else ""
        }

        private const val PREFS = "floating_lyrics_prefs"
    }

    // ── Service lifecycle ────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        wm    = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        buildOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("lyrics_fg", "Lyrics Overlay",
                    NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
        }
        startForeground(0xA801,
            NotificationCompat.Builder(this, "lyrics_fg")
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("Lyrics overlay active")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build()
        )
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        runCatching { wm.removeView(composeView) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Build floating window ────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlay() {
        val savedX      = prefs.getInt("overlay_x", 40)
        val savedY      = prefs.getInt("overlay_y", 300)
        val savedLocked = prefs.getBoolean("overlay_locked", false)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        val cv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingLyricsService)
            setViewTreeSavedStateRegistryOwner(this@FloatingLyricsService)
            setContent {
                FloatingPanel(
                    params      = params,
                    wm          = wm,
                    prefs       = prefs,
                    initLocked  = savedLocked,
                    onClose     = { stopSelf() }
                )
            }
        }
        composeView = cv
        wm.addView(cv, params)
    }
}

// ── Composable floating panel ─────────────────────────────────────────────────
@Composable
private fun FloatingPanel(
    params:    WindowManager.LayoutParams,
    wm:        WindowManager,
    prefs:     SharedPreferences,
    initLocked: Boolean,
    onClose:   () -> Unit
) {
    val lyric = FloatingLyricsService.lyricLine.collectAsState()
    val title = FloatingLyricsService.songTitle.collectAsState()
    var isLocked by remember { mutableStateOf(initLocked) }

    val dragModifier = if (!isLocked) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                    prefs.edit()
                        .putInt("overlay_x", params.x)
                        .putInt("overlay_y", params.y)
                        .apply()
                }
            ) { _, drag ->
                params.x += drag.x.toInt()
                params.y += drag.y.toInt()
                wm.updateViewLayout(null, params)
            }
        }
    } else Modifier

    Box(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 320.dp)
            .then(dragModifier)
            .background(Color(0xDD111827), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            // Top bar: song title + lock + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text     = title.value.ifEmpty { "♪ ArchiveTune" },
                    color    = Color(0xFFB0B0C8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick  = {
                        isLocked = !isLocked
                        prefs.edit().putBoolean("overlay_locked", isLocked).apply()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector        = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (isLocked) "Unlock" else "Lock",
                        tint               = if (isLocked) Color(0xFF1DB954) else Color(0xFF888888),
                        modifier           = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector        = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint               = Color(0xFF888888),
                        modifier           = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Animated lyric line
            AnimatedContent(
                targetState  = lyric.value,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 2 })
                        .togetherWith(fadeOut() + slideOutVertically { -it / 2 })
                },
                label = "lyric"
            ) { line ->
                Text(
                    text       = line.ifEmpty { "♪" },
                    color      = if (line.isNotEmpty()) Color(0xFF1DB954) else Color(0xFF444444),
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Medium,
                    fontStyle  = FontStyle.Italic,
                    textAlign  = TextAlign.Start,
                    lineHeight = 22.sp,
                    modifier   = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
