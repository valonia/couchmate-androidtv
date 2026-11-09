@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import app.couchmate.APP_NAME
import app.couchmate.Api
import app.couchmate.I18n
import app.couchmate.Prefs
import app.couchmate.normalizeUrl
import app.couchmate.tr

/**
 * The first start: logo on the left, the settings form on the right. After that, Settings opens as a popup
 * on top of the menu instead ([SettingsDialog]), so the app stays connected to the room.
 */
@Composable
fun SetupScreen(prefs: Prefs, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxSize().background(Palette.Bg).padding(horizontal = 56.dp, vertical = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Row {
                Text("Couch", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                Text("mate", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
            }
            Spacer(Modifier.height(6.dp))
            Text(tr("Settings"), fontSize = 20.sp, color = Palette.Dim)
            Spacer(Modifier.height(28.dp))
            Text(
                tr("Watch together with your friends, perfectly in sync. Everyone connected to the same server ends up in the same room."),
                fontSize = 15.sp, color = Palette.Dim, lineHeight = 22.sp,
            )
        }
        SettingsForm(
            prefs, onSaved = { onDone() }, onCancel = null,
            modifier = Modifier.weight(1f).fillMaxHeight().background(Palette.Panel, RoundedCornerShape(16.dp)),
        )
    }
}

/**
 * Settings as a popup on top of the menu. Changing the server, PIN or name reconnects ([onReconnect]);
 * the language changes straight away. Back or Cancel closes it.
 */
@Composable
fun SettingsDialog(prefs: Prefs, onClose: () -> Unit, onReconnect: () -> Unit, onLanguage: () -> Unit) {
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        SettingsForm(
            prefs,
            onSaved = { changed -> if (changed) onReconnect() else onClose() },
            onCancel = onClose,
            onLanguage = onLanguage,
            title = tr("Settings"),
            modifier = Modifier.width(900.dp).heightIn(max = 640.dp)
                .background(Palette.Panel, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.Line, RoundedCornerShape(18.dp)),
        )
    }
}

/** The form itself: server, PIN, name and language. [onSaved] gets whether the connection changed. */
@Composable
private fun SettingsForm(
    prefs: Prefs,
    onSaved: (connectionChanged: Boolean) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLanguage: () -> Unit = {},
    title: String? = null,
) {
    var server by remember { mutableStateOf(prefs.serverUrl) }
    var pin by remember { mutableStateOf(prefs.pin) }
    var name by remember { mutableStateOf(prefs.name) }
    var language by remember { mutableStateOf(prefs.language) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // Start on "Save" if everything is filled in (so the keyboard does not pop up), otherwise on the server address.
    LaunchedEffect(Unit) { delay(120); runCatching { firstFocus.requestFocus() } }

    fun save() {
        val url = normalizeUrl(server)
        val changed = url != prefs.serverUrl || pin.trim() != prefs.pin || name.trim() != prefs.name
        if (!changed && prefs.isConfigured) { onSaved(false); return }
        testing = true
        status = null
        scope.launch {
            try {
                val api = Api(url, pin.trim())
                val info = api.info()
                if (!info.ready) throw Exception(tr("The server is not set up yet. Set a PIN code in the admin panel."))
                api.auth()
                prefs.serverUrl = url
                prefs.pin = pin.trim()
                prefs.name = name.trim()
                onSaved(true)
            } catch (e: Exception) {
                status = tr("Could not connect: {message}", "message" to e.message)
            } finally {
                testing = false
            }
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (title != null) Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)

        SettingsSection(
            tr("Connection"),
            tr("The address of your {app} server and the PIN code you were given.", "app" to APP_NAME),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field(
                    tr("Server address"), server, tr("e.g. 192.168.1.10:8787"),
                    KeyboardType.Uri, Modifier.weight(2f).then(if (!prefs.isConfigured) Modifier.focusRequester(firstFocus) else Modifier),
                ) { server = it }
                Field(tr("PIN code"), pin, "••••", KeyboardType.Password, Modifier.weight(1f), password = true) { pin = it }
            }
        }

        SettingsSection(tr("Profile"), tr("The name the others see when you join.")) {
            Field(tr("Your name"), name, tr("e.g. Bob"), KeyboardType.Text, Modifier.fillMaxWidth(0.6f)) { name = it }
        }

        SettingsSection(tr("Language"), tr("Menus and buttons in the TV's language, translated by the server. Applies only on this TV.")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val system = java.util.Locale.getDefault().let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.uppercase(it) } }
                listOf(
                    "auto" to tr("Same as the TV ({language})", "language" to system),
                    "en" to "English",
                ).forEach { (code, label) ->
                    Pill(label, selected = language == code, onClick = {
                        language = code
                        prefs.language = code
                        I18n.configure(context, code)
                        onLanguage()
                    })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(
                    text = if (testing) tr("Connecting…") else if (prefs.isConfigured) tr("Save") else tr("Connect"),
                    primary = true,
                    enabled = !testing && server.isNotBlank() && name.isNotBlank(),
                    onClick = { save() },
                    modifier = if (prefs.isConfigured) Modifier.focusRequester(firstFocus) else Modifier,
                )
                onCancel?.let { ActionButton(tr("Cancel"), primary = false, onClick = it) }
            }
            status?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
        }
    }
}

@Composable
private fun SettingsSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text(description, fontSize = 14.sp, color = Palette.Faint)
        }
        content()
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Palette.Accent else Palette.PanelHi,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Palette.PanelHi,
            disabledContentColor = Palette.Faint,
        ),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (focused) Palette.Text else Palette.Dim)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp),
            cursorBrush = SolidColor(Palette.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                // Up/down/left/right on the remote move between the fields (otherwise you get stuck in the text field).
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionRight -> if (value.isEmpty()) focusManager.moveFocus(FocusDirection.Right) else false
                        Key.DirectionLeft -> if (value.isEmpty()) focusManager.moveFocus(FocusDirection.Left) else false
                        else -> false
                    }
                }
                .background(if (focused) Palette.PanelHi else Palette.Bg, RoundedCornerShape(10.dp))
                .border(2.dp, if (focused) Color.White else Palette.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(hint, color = Palette.Faint, fontSize = 18.sp)
                    inner()
                }
            },
        )
    }
}
