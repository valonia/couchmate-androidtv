@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

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
import se.synctv.Api
import se.synctv.I18n
import se.synctv.Prefs
import se.synctv.normalizeUrl
import se.synctv.t

/**
 * Inställningar: server, PIN, namn och språk.
 * [onCancel] = null första gången (inget att gå tillbaka till), annars tar Bakåt/Avbryt dig till menyn.
 */
@Composable
fun SetupScreen(prefs: Prefs, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    var server by remember { mutableStateOf(prefs.serverUrl) }
    var pin by remember { mutableStateOf(prefs.pin) }
    var name by remember { mutableStateOf(prefs.name) }
    var language by remember { mutableStateOf(prefs.language) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }

    // Börja på "Spara" om allt redan är ifyllt (så att tangentbordet inte hoppar upp), annars på serveradressen.
    LaunchedEffect(Unit) { delay(120); runCatching { firstFocus.requestFocus() } }

    fun save() {
        testing = true
        status = null
        scope.launch {
            val url = normalizeUrl(server)
            try {
                Api(url, pin.trim()).categories("live")
                prefs.serverUrl = url
                prefs.pin = pin.trim()
                prefs.name = name.trim()
                onDone()
            } catch (e: Exception) {
                status = t("Kunde inte ansluta: ${e.message}", "Couldn't connect: ${e.message}")
            } finally {
                testing = false
            }
        }
    }

    Row(
        Modifier.fillMaxSize().background(Palette.Bg).padding(horizontal = 56.dp, vertical = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        // ---------------------------------------------------------- vänster: logga + status
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Row {
                Text("Couch", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                Text("mates", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
            }
            Spacer(Modifier.height(6.dp))
            Text(t("Inställningar", "Settings"), fontSize = 20.sp, color = Palette.Dim)
            Spacer(Modifier.height(28.dp))
            Text(
                t(
                    "Titta på samma kanal, film eller serie som dina vänner, helt i synk. Alla som ansluter till samma server hamnar i samma rum.",
                    "Watch the same channel, movie or series as your friends, perfectly in sync. Everyone connected to the same server ends up in the same room.",
                ),
                fontSize = 15.sp, color = Palette.Dim, lineHeight = 22.sp,
            )
            Spacer(Modifier.weight(1f))
            if (prefs.isConfigured) {
                Column(
                    Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFF3DD68C), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(t("Sparad server", "Saved server"), fontSize = 13.sp, color = Palette.Faint, fontWeight = FontWeight.Bold)
                    }
                    Text(prefs.serverUrl.removePrefix("http://").removePrefix("https://"), fontSize = 15.sp, color = Palette.Text)
                    Text(t("Inloggad som ${prefs.name}", "Signed in as ${prefs.name}"), fontSize = 14.sp, color = Palette.Dim)
                }
                Spacer(Modifier.height(12.dp))
                Text(t("Tillbaka  –  till menyn utan att spara", "Back  –  return to the menu without saving"), fontSize = 13.sp, color = Palette.Faint)
            }
        }

        // ---------------------------------------------------------- höger: formuläret
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Palette.Panel, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 36.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            SettingsSection(
                t("Anslutning", "Connection"),
                t("Adressen till er Couchmates-server och PIN-koden ni fått.", "The address of your Couchmates server and the PIN you were given."),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Field(
                        t("Serveradress", "Server address"), server, t("t.ex. 192.168.1.10:8787", "e.g. 192.168.1.10:8787"),
                        KeyboardType.Uri, Modifier.weight(2f).then(if (!prefs.isConfigured) Modifier.focusRequester(firstFocus) else Modifier),
                    ) { server = it }
                    Field(t("PIN-kod", "PIN code"), pin, "••••", KeyboardType.Password, Modifier.weight(1f), password = true) { pin = it }
                }
            }

            SettingsSection(t("Profil", "Profile"), t("Namnet som de andra ser när du är med.", "The name the others see when you join.")) {
                Field(t("Ditt namn", "Your name"), name, t("t.ex. Bob", "e.g. Bob"), KeyboardType.Text, Modifier.fillMaxWidth(0.6f)) { name = it }
            }

            SettingsSection(t("Språk", "Language"), t("Gäller direkt och bara på den här TV:n.", "Applies right away and only on this TV.")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "auto" to t("Automatiskt (som TV:n)", "Automatic (same as TV)"),
                        "sv" to "Svenska",
                        "en" to "English",
                    ).forEach { (code, label) ->
                        Pill(label, selected = language == code, onClick = {
                            language = code
                            prefs.language = code
                            I18n.set(code)
                        })
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        text = if (testing) t("Ansluter…", "Connecting…") else if (prefs.isConfigured) t("Spara", "Save") else t("Anslut", "Connect"),
                        primary = true,
                        enabled = !testing && server.isNotBlank() && name.isNotBlank(),
                        onClick = { save() },
                        modifier = if (prefs.isConfigured) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                    onCancel?.let { ActionButton(t("Avbryt", "Cancel"), primary = false, onClick = it) }
                }
                status?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
            }
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
                // Upp/ner/vänster/höger på fjärren flyttar mellan fälten (annars fastnar man i textfältet).
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
