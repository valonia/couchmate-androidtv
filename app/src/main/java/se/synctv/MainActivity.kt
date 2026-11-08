package se.synctv

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.darkColorScheme
import se.synctv.ui.BrowseScreen
import se.synctv.ui.PlayerScreen
import se.synctv.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        I18n.set(prefs.language)
        setContent {
            SyncTvTheme { App(prefs) }
        }
    }
}

/** Storlek på hela gränssnittet (1.0 = original). Ändra här för att göra allt större/mindre. */
const val UI_SCALE = 0.8f

@Composable
fun SyncTvTheme(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    // Skalar alla dp/sp på en gång – videon och undertexterna påverkas inte.
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
    // Vad som spelades senast vi tittade – så att vi inte hoppar in i spelaren igen när man kommer tillbaka från inställningarna.
    var seenMedia by remember { mutableStateOf<String?>(null) }
    if (!configured) {
        // Bakåt/Avbryt går tillbaka till menyn – utom första gången, då finns inget att gå tillbaka till.
        SetupScreen(
            prefs,
            onDone = { configured = true },
            onCancel = if (prefs.isConfigured) ({ configured = true }) else null,
        )
    } else {
        Room(prefs, seenMedia = seenMedia, onSeen = { seenMedia = it }, onSettings = { configured = false })
    }
}

@Composable
fun Room(prefs: Prefs, seenMedia: String?, onSeen: (String?) -> Unit, onSettings: () -> Unit) {
    val sync = remember { SyncClient(prefs.serverUrl, prefs.pin, prefs.name, Build.MODEL ?: "Android TV") }
    val api = remember { Api(prefs.serverUrl, prefs.pin) }

    // Anslut när appen syns, koppla ner när den hamnar i bakgrunden
    // (annars skulle servern vänta på en enhet som ingen tittar på).
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

    // När någon startar något nytt hoppar alla in i spelaren automatiskt.
    val hasState = state != null
    LaunchedEffect(mediaKey, hasState) {
        if (!hasState) return@LaunchedEffect // vänta tills vi fått rummets status från servern
        if (mediaKey == null) inPlayer = false
        else if (mediaKey != seenMedia) inPlayer = true // något nytt startades (eller vi gick precis med)
        onSeen(mediaKey)
    }
    // Startar man själv något som redan spelas (t.ex. "Titta nu" på kanalen som går) – hoppa in i spelaren ändå.
    var firstVersion by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state?.version) {
        val st = state ?: return@LaunchedEffect
        if (firstVersion == null) { firstVersion = st.version; return@LaunchedEffect } // första statusen efter anslutning räknas inte
        if (st.media != null && st.action == "load" && st.changedBy == prefs.name) inPlayer = true
    }

    // Menyns läge (vald meny, grupp, serie, rad och scroll) sparas medan spelaren visas,
    // så att man kommer tillbaka dit man var.
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (inPlayer && state?.media != null) {
        PlayerScreen(sync, api, onExit = { inPlayer = false })
    } else {
        saved.SaveableStateProvider("browse") {
            val focusMemory = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
            androidx.compose.runtime.CompositionLocalProvider(se.synctv.ui.LocalFocusMemory provides focusMemory) {
                BrowseScreen(sync, api, onOpenPlayer = { inPlayer = true }, onSettings = onSettings)
            }
        }
    }
}
