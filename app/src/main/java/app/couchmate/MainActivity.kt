package app.couchmate

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.darkColorScheme
import app.couchmate.ui.BrowseScreen
import app.couchmate.ui.LocalFocusMemory
import app.couchmate.ui.PlayerScreen
import app.couchmate.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        I18n.configure(this, prefs.language)
        setContent {
            CouchmateTheme { App(prefs) }
        }
    }
}

/** Size of the whole interface (1.0 = original). Change it here to make everything bigger or smaller. */
const val UI_SCALE = 0.8f

@Composable
fun CouchmateTheme(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    // Scales every dp/sp at once – the video and subtitles are not affected.
    CompositionLocalProvider(LocalDensity provides Density(d.density * UI_SCALE, d.fontScale)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7C9CFF))) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                colors = SurfaceDefaults.colors(containerColor = Color(0xFF0E1015), contentColor = Color.White),
            ) { content() }
        }
    }
}

@Composable
fun App(prefs: Prefs) {
    var configured by remember { mutableStateOf(prefs.isConfigured) }
    // Counts up when the server, PIN or name changes in Settings – the room is then connected again.
    var connection by remember { mutableStateOf(0) }
    // What played last time we watched – so we do not jump into the player again after reconnecting.
    var seenMedia by remember { mutableStateOf<String?>(null) }
    if (!configured) {
        SetupScreen(prefs, onDone = { configured = true })
    } else {
        androidx.compose.runtime.key(connection) {
            Room(prefs, seenMedia = seenMedia, onSeen = { seenMedia = it }, onReconnect = { connection++ })
        }
    }
}

@Composable
fun Room(prefs: Prefs, seenMedia: String?, onSeen: (String?) -> Unit, onReconnect: () -> Unit) {
    val api = remember { Api(prefs.serverUrl, prefs.pin) }
    val sync = remember { SyncClient(api, prefs.serverUrl, prefs.pin, prefs.name, Build.MODEL ?: "Android TV") }

    // Translations of the app's own texts are fetched in the background while the app runs.
    LaunchedEffect(api) { I18n.pump(api) }

    // Connect while the app is visible, disconnect when it goes to the background
    // (otherwise the server would wait for a device nobody watches).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> sync.start()
                Lifecycle.Event.ON_STOP -> sync.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sync.stop()
        }
    }

    val state by sync.state.collectAsState()
    val mediaKey = state?.media?.key
    var inPlayer by remember { mutableStateOf(false) }

    // When someone starts something new, everyone jumps into the player.
    val hasState = state != null
    LaunchedEffect(mediaKey, hasState) {
        if (!hasState) return@LaunchedEffect // wait for the room's state from the server
        if (mediaKey == null) inPlayer = false
        else if (mediaKey != seenMedia) inPlayer = true // something new started (or we just joined)
        onSeen(mediaKey)
    }
    // Starting something that already plays (e.g. "Watch now" on the channel that is on) – go to the player anyway.
    var firstVersion by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state?.version) {
        val st = state ?: return@LaunchedEffect
        if (firstVersion == null) { firstVersion = st.version; return@LaunchedEffect } // the first state after connecting does not count
        if (st.media != null && st.action == "load" && st.changedBy == prefs.name) inPlayer = true
    }

    // The menu's state (chosen entry, group, detail page, row and scroll) is kept while the player shows,
    // so you come back to where you were.
    val saved = rememberSaveableStateHolder()
    if (inPlayer && state?.media != null) {
        PlayerScreen(sync, api, onExit = { inPlayer = false })
    } else {
        saved.SaveableStateProvider("browse") {
            val focusMemory = rememberSaveable { mutableStateOf<String?>(null) }
            CompositionLocalProvider(LocalFocusMemory provides focusMemory) {
                BrowseScreen(sync, api, prefs, onOpenPlayer = { inPlayer = true }, onReconnect = onReconnect)
            }
        }
    }
}
