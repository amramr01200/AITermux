package com.aitermux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

object UiState { var zoom by mutableFloatStateOf(1f) }
val Gold = Color(0xFFD4AF37)
val Sf = Color(0xFF151515)
val Tx = Color(0xFFF3E2A9)

fun t(ar: String, en: String) = if (Store.lang == "ar") ar else en
fun fs(v: Int) = (v * UiState.zoom).sp

@Composable fun G(s: String, size: Int = 15, bold: Boolean = false, color: Color = Tx, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(s, color = color, fontSize = fs(size), fontWeight = if (bold) FontWeight.Bold else null, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

@Composable fun GBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Button(onClick, modifier, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) { Text(label, fontSize = fs(14)) }

@Composable fun F(label: String, v: String, pw: Boolean = false, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        visualTransformation = VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Gold.copy(alpha = .5f),
            focusedTextColor = Tx, unfocusedTextColor = Tx, focusedLabelColor = Gold, unfocusedLabelColor = Gold.copy(alpha = .7f), cursorColor = Gold))

@Composable fun Ask(title: String, fields: List<Pair<String, String>>, onOk: (List<String>) -> Unit, onNo: () -> Unit) {
    val v = remember { mutableStateListOf(*fields.map { it.second }.toTypedArray()) }
    AlertDialog(onDismissRequest = onNo, containerColor = Sf,
        title = { G(title, 18, true, Gold) },
        text = { Column { fields.forEachIndexed { i, f -> F(f.first, v[i]) { v[i] = it } } } },
        confirmButton = { TextButton({ onOk(v.toList()) }) { Text(t("حفظ", "Save"), color = Gold) } },
        dismissButton = { TextButton(onNo) { Text(t("إلغاء", "Cancel"), color = Gold) } })
}

@Composable fun Confirm(msg: String, onYes: () -> Unit, onNo: () -> Unit) =
    AlertDialog(onDismissRequest = onNo, containerColor = Sf, text = { G(msg) },
        confirmButton = { TextButton(onYes) { Text(t("تأكيد", "Confirm"), color = Gold) } },
        dismissButton = { TextButton(onNo) { Text(t("إلغاء", "Cancel"), color = Gold) } })

@Composable fun Bar(title: String, back: () -> Unit) = Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Gold) }
    G(title, 20, true, Gold)
}

fun copy(c: Context, s: String) {
    (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", s))
    Toast.makeText(c, "✓", Toast.LENGTH_SHORT).show()
}

data class Entry(val p: Prov, val m: String, val old: Boolean = false) { val key get() = p.id + "|" + m }
data class Att(val name: String, val mime: String, val bytes: ByteArray)

fun short(n: String) = if (n.length > 22) n.take(12) + "…" + n.takeLast(8) else n
fun isVision(m: String) = Regex("llava|vision|vl\\b|-vl|gemma3|gpt-4o|gpt-4\\.1|gpt-5|claude|gemini|pixtral|minicpm|moondream|bakllava|qwen2\\.5vl|llama4", RegexOption.IGNORE_CASE).containsMatchIn(m)

@Composable fun App(act: MainActivity) {
    val dir = if (Store.lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    var screen by remember { mutableStateOf("chat") }
    BackHandler(screen != "chat") { screen = "chat" }
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Color.Black, background = Color.Black, surface = Sf,
        onSurface = Tx, onBackground = Tx, surfaceVariant = Sf, onSurfaceVariant = Tx)) {
        CompositionLocalProvider(LocalLayoutDirection provides dir) {
            Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.size >= 2) {
                            val z = ev.calculateZoom()
                            if (z != 1f) { UiState.zoom = (UiState.zoom * z).coerceIn(0.6f, 3f); ev.changes.forEach { it.consume() } }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }) {
                SelectionContainer {
                    when (screen) {
                        "chat" -> ChatScreen(act) { screen = it }
                        "lib" -> Library(act) { screen = "chat" }
                        "add" -> AddScreen(act) { screen = "chat" }
                        "lang" -> LangScreen { screen = "chat" }
                        else -> SettingsScreen(act) { screen = "chat" }
                    }
                }
            }
        }
    }
