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
import androidx.compose.foundation.horizontalScroll
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
import org.json.JSONArray
import org.json.JSONObject

object UiState { var zoom by mutableFloatStateOf(1f) }
val Gold = Color(0xFFD4AF37)
val Sf = Color(0xFF151515)
val Tx = Color(0xFFF3E2A9)

fun t(ar: String, en: String) = if (Store.lang == "ar") ar else en
fun fs(v: Int) = (v * UiState.zoom).sp

@Composable fun G(s: String, size: Int = 15, bold: Boolean = false, color: Color = Tx, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(s, color = color, fontSize = fs(size), fontWeight = if (bold) FontWeight.Bold else null, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

@Composable fun GBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Button(onClick, modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) { Text(label, fontSize = fs(14), maxLines = 1) }

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
    var webUrl by remember { mutableStateOf("") }
    BackHandler(screen != "chat") { screen = if (screen == "web") "lib" else "chat" }
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
                when (screen) {
                    "chat" -> ChatScreen(act) { screen = it }
                    "lib" -> Library(act, { webUrl = it; screen = "web" }) { screen = "chat" }
                    "web" -> WebScreen(webUrl) { screen = "lib" }
                    "add" -> SelectionContainer { AddScreen(act) { screen = "chat" } }
                    "lang" -> LangScreen { screen = "chat" }
                    else -> SelectionContainer { SettingsScreen(act) { screen = "chat" } }
                }
            }
        }
    }
}

@Composable fun ChatScreen(act: MainActivity, go: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var chat by remember { mutableStateOf(Chat(uid(), "", emptyList(), System.currentTimeMillis())) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val atts = remember { mutableStateListOf<Att>() }
    var selKey by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var gear by remember { mutableStateOf(false) }
    var hist by remember { mutableStateOf(false) }
    var dl by remember { mutableStateOf(false) }
    val entries = Store.provs.reversed().flatMap { p -> p.models.map { Entry(p, it) } + p.old.map { Entry(p, it, true) } }
        .sortedBy { if (it.key == Store.star) 0 else 1 }
    val sel = entries.firstOrNull { it.key == selKey } ?: entries.firstOrNull()
    val starE = entries.firstOrNull { it.key == Store.star }
    val cur = if (chat.title.startsWith("🤖")) (starE ?: sel) else sel
    val ls = rememberLazyListState()
    val shownCount = chat.msgs.count { it.role != "system" }
    LaunchedEffect(shownCount, busy) { if (shownCount > 0) ls.scrollToItem(shownCount - 1 + (if (busy) 1 else 0)) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us ->
        us.forEach { u -> runCatching {
            val mime = act.contentResolver.getType(u) ?: ""
            val name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
            val b = act.contentResolver.openInputStream(u)!!.use { it.readBytes() }
            if (b.size <= 8_000_000) atts.add(Att(name, mime, b))
        } }
    }
    val stt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = (input + " " + it).trim() }
    }

    fun send() {
        val text = input.trim()
        if ((text.isEmpty() && atts.isEmpty()) || busy) return
        val e = cur ?: run { Toast.makeText(act, t("أضف مزوداً أولاً من الترس ← الإضافة", "Add a provider first (gear → Add)"), Toast.LENGTH_LONG).show(); return }
        var body = text
        val imgs = mutableListOf<String>()
        atts.forEach { a ->
            when {
                a.mime.startsWith("image") -> imgs.add(Base64.encodeToString(a.bytes, Base64.NO_WRAP))
                a.mime.startsWith("text") || a.name.substringAfterLast('.').lowercase() in listOf("txt", "md", "json", "csv", "kt", "py", "js", "xml", "html", "log") ->
                    body += "\n\n[${a.name}]\n" + String(a.bytes).take(20000)
                else -> body += "\n[📎 ${a.name} (${a.mime})]"
            }
        }
        val label = body + if (imgs.isNotEmpty()) "\n[🖼 ×${imgs.size}]" else ""
        val h = chat.msgs + Msg("user", label)
        chat = chat.copy(title = chat.title.ifBlank { text.take(30).ifBlank { t("محادثة", "Chat") } }, msgs = h, ts = System.currentTimeMillis())
        Store.upsert(chat)
        input = ""; atts.clear(); busy = true
        scope.launch {
            val r: Result<String> = if (e.p.kind == "claudecode" || e.p.kind == "opencode") {
                val omni = e.p.url.endsWith("omni")
                val dep = if (omni) Store.provs.firstOrNull { ":20128" in it.url } else Store.provs.firstOrNull { it.kind == "termux" }
                withContext(Dispatchers.IO) { dep?.let { Api.ensure(act, it) } }
                val x = Termux.execWait(act, if (e.p.kind == "opencode") Termux.opencodeCmd(e.m, h) else Termux.claudeCmd(e.m, h, if (omni) dep?.key else null), 900000)
                if (x == null) Result.failure(Exception(t("لا رد من Termux (تحقق من الصلاحية أو المهلة)", "No reply from Termux (check permission or timeout)")))
                else if (x.first == 0 && x.second.isNotBlank()) Result.success(x.second)
                else Result.failure(Exception(x.second.takeLast(400).ifBlank { "exit " + x.first }))
            } else withContext(Dispatchers.IO) {
                runCatching {
                    Api.ensure(act, e.p)
                    try { Api.chat(e.p, e.m, h, imgs) } catch (x: java.io.IOException) {
                        val m = (x.message ?: "").lowercase()
                        if (!(m.contains("abort") || m.contains("reset") || m.contains("unexpected end") || m.contains("closed"))) throw x
                        Thread.sleep(2500); Api.ensure(act, e.p); Api.chat(e.p, e.m, h, imgs)
                    }
                }
            }
            val reply = r.getOrElse { val m = it.message ?: it.toString(); "⚠ \u2066[" + e.p.name + "] " + m + "\u2069" + (if (m.contains("timeout", true) || m.contains("timed out", true)) "\n" + t("الخادم لا يرد: غالباً أندرويد جمّد Termux أو أنهى عمليته. اترك Termux مفتوحاً، وفعّل Wake lock، واستثنه من توفير البطارية.", "Server not responding: Android probably froze or killed Termux. Keep Termux open, enable Wake lock, exempt it from battery saving.") else "") + (if (m.contains("unsupported", true)) "\n" + t("هذا النموذج لا يدعم الدردشة النصية (قد يكون نموذج صور أو فيديو أو صوت). اختر نموذج نص من القائمة.", "This model does not support text chat (it may be an image, video or audio model). Pick a text model.") else "") }
            chat = chat.copy(msgs = chat.msgs + Msg("assistant", reply), ts = System.currentTimeMillis())
            Store.upsert(chat); busy = false
            if (Store.speak && r.isSuccess) act.say(reply)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton({ menu = true }, border = BorderStroke(1.dp, Gold)) {
                    val txt = cur?.let { e ->
                        val same = entries.filter { it.m == e.m }
                        (if (e.key == Store.star) "★ " else "") + (if (e.old) "⌛ " else "") + (if (e.p.kind == "termux") "T " else "") + short(e.m) + (if (same.size > 1) " (${same.indexOf(e) + 1})" else "") + (if (isVision(e.m)) " 👁" else "")
                    } ?: t("اختر نموذجاً", "Select model")
                    G(txt, 14, color = Gold, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, null, tint = Gold)
                }
            }
            IconButton({
                if (starE == null) Toast.makeText(act, t("اضغط ★ بجانب نموذج في القائمة ليصبح المساعد", "Tap ★ next to a model to make it the assistant"), Toast.LENGTH_LONG).show()
                else chat = Chat(uid(), "🤖 " + t("المساعد", "Assistant"), listOf(Msg("system", assistantPrompt())), System.currentTimeMillis())
            }) { Icon(Icons.Default.SmartToy, null, tint = Gold) }
            IconButton({ hist = true }) { Icon(Icons.Default.History, null, tint = Gold) }
            Box {
                IconButton({ gear = true }) { Icon(Icons.Default.Settings, null, tint = Gold) }
                DropdownMenu(gear, { gear = false }, containerColor = Sf) {
                    listOf("lib" to t("مكتبتي", "My library"), "add" to t("الإضافة", "Add"), "lang" to t("اللغة", "Language"), "set" to t("الإعدادات", "Settings")).forEach { (k, l) ->
                        DropdownMenuItem(text = { G(l) }, onClick = { gear = false; go(k) })
                    }
                }
            }
        }
        HorizontalDivider(color = Gold.copy(alpha = .4f))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), state = ls, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.msgs.none { it.role != "system" }) item {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { G("AI+", 56, true, Gold) }
            }
            items(chat.msgs.filter { it.role != "system" }) { m ->
                val me = m.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.Start else Arrangement.End) {
                    Box(Modifier.widthIn(max = 520.dp).background(if (me) Sf else Color(0xFF1E1A0C), RoundedCornerShape(12.dp))
                        .border(1.dp, Gold.copy(alpha = if (me) .8f else .3f), RoundedCornerShape(12.dp)).padding(10.dp)) { SelectionContainer { G(m.text) } }
                }
            }
            if (busy) item { G("…", 20, color = Gold) }
        }
        if (atts.isNotEmpty()) Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            atts.toList().forEach { a -> AssistChip({ atts.remove(a) }, label = { Text("📎 " + short(a.name) + " ✕", color = Tx) }, modifier = Modifier.padding(end = 4.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ pick.launch("*/*") }) { Icon(Icons.Default.AttachFile, null, tint = Gold) }
            IconButton({
                stt.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (Store.lang == "ar") "ar" else "en"))
            }) { Icon(Icons.Default.Mic, null, tint = Gold) }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), maxLines = 5,
                placeholder = { Text(t("اكتب رسالتك…", "Type a message…")) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Gold.copy(alpha = .5f), focusedTextColor = Tx, unfocusedTextColor = Tx, cursorColor = Gold))
            IconButton({ send() }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Gold) }
        }
    }

    if (menu) {
        var q by remember { mutableStateOf("") }
        val counts = remember(entries) { entries.groupingBy { it.m }.eachCount() }
        val shownE = entries.filter { q.isBlank() || it.m.contains(q, true) || it.p.name.contains(q, true) }
        AlertDialog(onDismissRequest = { menu = false }, containerColor = Sf,
            title = { G(t("اختر النموذج", "Select model") + " (${entries.size})", 18, true, Gold) },
            text = { Column {
                F(t("بحث", "Search"), q) { q = it }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shownE) { e ->
                        val n = counts[e.m] ?: 1
                        val idx = if (n > 1) entries.filter { it.m == e.m }.indexOf(e) + 1 else 0
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ selKey = e.key; menu = false }, Modifier.weight(1f)) {
                                G((if (e.key == Store.star) "★ " else "") + (if (e.old) "⌛ " else "") + (if (e.p.kind == "termux") "T  " else "") + short(e.m) + (if (n > 1) "  ($idx) ${short(e.p.name)}" else "") + (if (isVision(e.m)) "  👁" else ""), 14, maxLines = 2)
                            }
                            IconButton({ Store.star = if (Store.star == e.key) null else e.key; Store.save() }) { Icon(if (Store.star == e.key) Icons.Default.Star else Icons.Default.StarBorder, null, tint = Gold) }
                            IconButton({ Store.hideModel(e.p.id, e.m) }) { Icon(Icons.Default.Delete, null, tint = Gold) }
                        }
                    }
                }
            } },
            confirmButton = { TextButton({ menu = false; dl = true }) { Text("⬇ " + t("تنزيل نموذج جديد", "Download new model"), color = Gold) } },
            dismissButton = { TextButton({ menu = false }) { Text(t("إغلاق", "Close"), color = Gold) } })
    }

    if (dl) Ask(t("تنزيل نموذج (Ollama عبر Termux)", "Download model (Ollama via Termux)"), listOf(t("اسم النموذج مثل llama3.2", "Model e.g. llama3.2") to ""),
        { v -> dl = false; val n = v[0].trim().filter { it.isLetterOrDigit() || it in ".:_-/" }
            if (n.isNotEmpty()) Toast.makeText(act, if (Termux.run(act, Termux.START + "; sleep 2; ollama pull $n")) t("بدأ التنزيل في Termux، حدّث القائمة من الإضافة ← جلب من Termux", "Download started; refresh via Add → Termux") else t("تعذّر الوصول إلى Termux", "Cannot reach Termux"), Toast.LENGTH_LONG).show() },
        { dl = false })

    if (hist) {
        var q by remember { mutableStateOf("") }
        var ren by remember { mutableStateOf<Chat?>(null) }
        var del by remember { mutableStateOf<Chat?>(null) }
        AlertDialog(onDismissRequest = { hist = false }, containerColor = Sf, title = { G(t("سجل الدردشة", "Chat history"), 18, true, Gold) },
            text = { Column {
                F(t("بحث", "Search"), q) { q = it }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(Store.chats.sortedByDescending { it.ts }.filter { q.isBlank() || it.title.contains(q, true) || it.msgs.any { m -> m.text.contains(q, true) } }) { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ chat = c; hist = false }, Modifier.weight(1f)) { G(c.title.ifBlank { "…" }, 14) }
                            IconButton({ ren = c }) { Icon(Icons.Default.Edit, null, tint = Gold) }
                            IconButton({ del = c }) { Icon(Icons.Default.Delete, null, tint = Gold) }
                        }
                    }
                }
            } },
            confirmButton = { TextButton({ chat = Chat(uid(), "", emptyList(), System.currentTimeMillis()); hist = false }) { Text("+ " + t("محادثة جديدة", "New chat"), color = Gold) } },
            dismissButton = { TextButton({ hist = false }) { Text(t("إغلاق", "Close"), color = Gold) } })
        ren?.let { c -> Ask(t("تسمية", "Rename"), listOf(t("الاسم", "Name") to c.title), { v -> val n = c.copy(title = v[0]); Store.upsert(n); if (chat.id == c.id) chat = n; ren = null }, { ren = null }) }
        del?.let { c -> Confirm(t("حذف هذه المحادثة؟", "Delete this chat?"), { Store.chats.remove(c); Store.save(); if (chat.id == c.id) chat = Chat(uid(), "", emptyList(), System.currentTimeMillis()); del = null }, { del = null }) }
    }
}

@Composable fun Library(act: MainActivity, openWeb: (String) -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editP by remember { mutableStateOf<Prov?>(null) }
    var editT by remember { mutableStateOf<Tool?>(null) }
    var delP by remember { mutableStateOf<Prov?>(null) }
    var delT by remember { mutableStateOf<Tool?>(null) }
    var clear by remember { mutableStateOf(false) }
    var addP by remember { mutableStateOf(false) }
    var addT by remember { mutableStateOf(false) }
    var delTermuxTools by remember { mutableStateOf(false) }
    val imp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val txt = withContext(Dispatchers.IO) { runCatching { act.contentResolver.openInputStream(u)!!.use { String(it.readBytes()) } }.getOrDefault("") }
            val n = Store.importText(txt)
            Toast.makeText(act, t("تمت إضافة $n عنصراً", "$n items added"), Toast.LENGTH_SHORT).show()
            Store.provs.toList().filter { it.models.isEmpty() }.forEach { p ->
                val ms = withContext(Dispatchers.IO) { runCatching { Api.models(p) }.getOrNull() }
                if (ms != null) { val i = Store.provs.indexOfFirst { it.id == p.id }; if (i >= 0) Store.provs[i] = Store.merge(p, ms) }
            }
            Store.save()
        }
    }
    val exp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) runCatching { act.contentResolver.openOutputStream(u)!!.use { it.write(Store.exportJson().toByteArray()) } }
            .also { Toast.makeText(act, if (it.isSuccess) "✓" else "✗", Toast.LENGTH_SHORT).show() }
    }
    Column(Modifier.fillMaxSize()) {
        Bar(t("مكتبتي", "My library"), back)
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GBtn(t("استيراد", "Import"), Modifier.weight(1f)) { imp.launch(arrayOf("*/*")) }
            GBtn(t("تصدير", "Export"), Modifier.weight(1f)) { exp.launch("ai-termux-library.json") }
            GBtn(t("مسح الكل", "Clear all"), Modifier.weight(1f)) { clear = true }
        }
        LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { G(t("المزودون", "Providers"), 17, true, Gold, Modifier.weight(1f)); GBtn("+ " + t("إضافة مزود خدمة", "Add provider")) { addP = true } } }
            items(Store.provs.toList()) { p ->
                Column(Modifier.fillMaxWidth().border(1.dp, Gold.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                    SelectionContainer { Column {
                        G(p.name + (if (p.kind == "termux") "  [T]" else ""), 16, true)
                        G(p.url, 13); G("🔑 " + p.key, 13); G(t("النماذج: ", "Models: ") + p.models.size + (if (p.old.isNotEmpty()) " (+${p.old.size} ⌛)" else ""), 12, color = Gold)
                        if (p.start.isNotBlank()) G("▶ " + p.start, 12)
                    } }
                    Row {
                        IconButton({ copy(act, "${p.name}\n${p.url}\n${p.key}") }) { Icon(Icons.Default.ContentCopy, null, tint = Gold) }
                        IconButton({ editP = p }) { Icon(Icons.Default.Edit, null, tint = Gold) }
                        IconButton({ scope.launch {
                            val ms = if (p.kind == "opencode") opencodeModels(act) else withContext(Dispatchers.IO) { runCatching { Api.ensure(act, p); Api.models(p) }.getOrNull() }
                            if (ms != null) { val i = Store.provs.indexOfFirst { it.id == p.id }; if (i >= 0) Store.provs[i] = Store.merge(p, ms); Store.save() }
                            else Toast.makeText(act, "✗", Toast.LENGTH_SHORT).show() } }) { Icon(Icons.Default.Refresh, null, tint = Gold) }
                        IconButton({ delP = p }) { Icon(Icons.Default.Delete, null, tint = Gold) }
                        IconButton({ addP = true }) { Icon(Icons.Default.Add, null, tint = Gold) }
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { G(t("الأدوات", "Tools"), 17, true, Gold, Modifier.weight(1f)); GBtn("+ " + t("إضافة أداة", "Add tool")) { addT = true } } }
            item { if (Store.tools.any { it.url.startsWith("termux:") }) GBtn(t("حذف أدوات Termux المفهرسة", "Remove indexed Termux tools") + " (" + Store.tools.count { it.url.startsWith("termux:") } + ")") { delTermuxTools = true } }
            items(Store.tools.toList()) { x ->
                Column(Modifier.fillMaxWidth().border(1.dp, Gold.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                    SelectionContainer { Column { G(x.name, 16, true); G(x.url, 13); G("🔑 " + x.key, 13) } }
                    Row {
                        IconButton({ copy(act, "${x.name}\n${x.url}\n${x.key}") }) { Icon(Icons.Default.ContentCopy, null, tint = Gold) }
                        IconButton({ editT = x }) { Icon(Icons.Default.Edit, null, tint = Gold) }
                        IconButton({ delT = x }) { Icon(Icons.Default.Delete, null, tint = Gold) }
                        IconButton({ addT = true }) { Icon(Icons.Default.Add, null, tint = Gold) }
                        if (x.url.startsWith("http")) IconButton({ openWeb(x.url) }) { Icon(Icons.Default.OpenInBrowser, null, tint = Gold) }
                    }
                }
            }
        }
    }
    if (addP) Ask(t("إضافة مزود خدمة", "Add provider"), listOf(t("الاسم", "Name") to "", "URL" to "", t("المفتاح (اختياري)", "Key (optional)") to ""),
        { v -> addP = false; scope.launch { Toast.makeText(act, addProvider(v[0], v[1], v[2]), Toast.LENGTH_LONG).show() } }, { addP = false })
    if (addT) Ask(t("إضافة أداة", "Add tool"), listOf(t("الاسم", "Name") to "", "URL" to "", t("كلمة المرور (اختياري)", "Password (optional)") to ""),
        { v -> if (v[1].isNotBlank()) { Store.tools.add(Tool(uid(), v[0].ifBlank { Store.hostOf(v[1]) }, v[1].trim(), v[2].trim())); Store.save() }; addT = false }, { addT = false })
    editP?.let { p -> Ask(t("تعديل", "Edit"), listOf(t("الاسم", "Name") to p.name, "URL" to p.url, t("المفتاح", "Key") to p.key, t("أمر التشغيل (اختياري)", "Start command (optional)") to p.start),
        { v -> val i = Store.provs.indexOfFirst { it.id == p.id }; if (i >= 0) Store.provs[i] = p.copy(name = v[0], url = v[1], key = v[2], start = v[3]); Store.save(); editP = null }, { editP = null }) }
    editT?.let { x -> Ask(t("تعديل", "Edit"), listOf(t("الاسم", "Name") to x.name, "URL" to x.url, t("المفتاح", "Key") to x.key),
        { v -> val i = Store.tools.indexOfFirst { it.id == x.id }; if (i >= 0) Store.tools[i] = x.copy(name = v[0], url = v[1], key = v[2]); Store.save(); editT = null }, { editT = null }) }
    delP?.let { p -> Confirm(t("حذف ${p.name}؟", "Delete ${p.name}?"), { Store.provs.remove(p); Store.save(); delP = null }, { delP = null }) }
    delT?.let { x -> Confirm(t("حذف ${x.name}؟", "Delete ${x.name}?"), { Store.tools.remove(x); Store.save(); delT = null }, { delT = null }) }
    if (delTermuxTools) Confirm(t("حذف كل أدوات Termux المفهرسة؟", "Remove all indexed Termux tools?"), { Store.tools.removeAll { it.url.startsWith("termux:") }; Store.save(); delTermuxTools = false }, { delTermuxTools = false })
    if (clear) Confirm(t("مسح كل المزودين والأدوات؟", "Clear all providers and tools?"), { Store.clearLibrary(); clear = false }, { clear = false })
}

@Composable fun Sec(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Gold.copy(alpha = .6f), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            G(title, 17, true, Gold, Modifier.weight(1f))
            GBtn("+ " + t("إضافة جديد", "Add new")) { open = !open }
        }
        if (open) content()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun AddScreen(act: MainActivity, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    fun runCli(label: String, cmd: String, ms: Long = 1200000, native: Boolean = false) {
        toast(label + "…")
        scope.launch {
            val r = Termux.execWait(act, if (native) cmd else Termux.ub(cmd), ms)
            toast(if (r == null) t("لا رد من Termux", "No reply from Termux") else (if (r.first == 0) "✓ " else "✗ ") + r.second.takeLast(200))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Bar(t("الإضافة", "Add"), back)
        Column(Modifier.padding(horizontal = 10.dp)) {
            GBtn(t("ربط الجميع وتحديث كل النماذج", "Link all & refresh all models"), Modifier.fillMaxWidth()) {
                scope.launch {
                    toast(t("جارٍ التشغيل والتحديث… قد يستغرق دقائق", "Starting and refreshing… may take minutes"))
                    toast(linkAll(act))
                }
            }
            // 1) Provider
            Sec(t("ربط من مزود", "Connect provider")) {
                var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }; var k by remember { mutableStateOf("") }
                G(t("اختر مزوداً لتعبئة الاسم والرابط الصحيحين، ثم الصق مفتاحك:", "Pick a provider to fill the correct name and URL, then paste your key:"), 13)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "CometAPI" to "https://api.cometapi.com/v1",
                        "Dify" to "https://api.dify.ai/v1",
                        "NVIDIA" to "https://integrate.api.nvidia.com/v1",
                        "Groq" to "https://api.groq.com/openai/v1",
                        "OpenRouter" to "https://openrouter.ai/api/v1",
                        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
                        "HuggingFace" to "https://router.huggingface.co/v1",
                        "OpenAI" to "https://api.openai.com/v1",
                        "Mistral" to "https://api.mistral.ai/v1",
                    ).forEach { (pn, pu) ->
                        OutlinedButton({ n = pn; u = pu }, border = BorderStroke(1.dp, Gold), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(pn, color = Gold, fontSize = fs(13)) }
                    }
                }
                F(t("الاسم", "Name"), n) { n = it }; F("URL", u) { u = it }; F(t("كلمة المرور / المفتاح (اختياري)", "Key (optional)"), k, true) { k = it }
                GBtn(t("اختبار وإضافة (المجاني فقط)", "Test & add (free only)")) {
                    val kind = Store.classify(u).let { if (it == "tool") "openai" else it }
                    val p = Prov(uid(), n.ifBlank { Store.hostOf(u) }, u.trim(), k.trim(), kind, emptyList(), emptyList(), true)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }
                        r.onSuccess { all -> val ms = all.filter { it.endsWith(":free") }; Store.provs.add(p.copy(models = ms)); Store.save(); toast("✓ ${ms.size} " + t("نموذج مجاني", "free models")); n = ""; u = ""; k = "" }
                            .onFailure { toast("✗ " + it.message) }
                    }
                }
                GBtn(t("اختبار وإضافة", "Test & add")) {
                    val kind = Store.classify(u).let { if (it == "tool") "openai" else it }
                    val p = Prov(uid(), n.ifBlank { Store.hostOf(u) }, u.trim(), k.trim(), kind, emptyList())
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }
                        r.onSuccess { Store.provs.add(p.copy(models = it)); Store.save(); toast("✓ ${it.size} " + t("نموذج", "models")); n = ""; u = ""; k = "" }
                            .onFailure { toast("✗ " + it.message) }
                    }
                }
            }
            // 2) Termux
            Sec(t("جلب من Termux", "Fetch from Termux")) {
                var u by remember { mutableStateOf("http://127.0.0.1:11434") }; var k by remember { mutableStateOf("") }
                F("URL", u) { u = it }; F(t("كلمة المرور (اختياري)", "Password (optional)"), k, true) { k = it }
                GBtn(t("تشغيل وجلب النماذج", "Start & fetch models")) {
                    scope.launch {
                        toast(t("جارٍ التشغيل والجلب…", "Starting and fetching…"))
                        val url = u.trim().trimEnd('/')
                        val p = Prov(uid(), "Ollama " + Store.hostOf(url), url, k.trim(), "termux", emptyList())
                        withContext(Dispatchers.IO) { Api.ensure(act, p) }
                        val http = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }
                        var ms = http.getOrNull()
                        var err = http.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: ""
                        if (ms == null) {
                            val r = Termux.execWait(act, "ollama list 2>&1 | tail -n +2 | awk '{print \$1}'")
                            if (r != null && r.first == 0) ms = r.second.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }
                            else err += "\nTermux: " + (r?.second ?: t("لا رد (تحقق من الصلاحية)", "no reply (check permission)"))
                        }
                        if (ms != null) {
                            val i = Store.provs.indexOfFirst { it.url == p.url }
                            if (i >= 0) Store.provs[i] = Store.merge(Store.provs[i], ms) else Store.provs.add(Store.merge(p, ms))
                            Store.save(); toast("✓ ${ms.size} " + t("نموذج", "models"))
                        } else toast("✗ " + err + "\n" + t("تأكد من تفعيل allow-external-apps (الإعدادات)", "Check allow-external-apps (Settings)"))
                    }
                }
            }
            // 3) Tools
            Sec(t("جلب أدوات", "Fetch tools")) {
                G(t("برامج Termux (تثبيت / تشغيل بزر واحد):", "Termux programs (install / run):"), 14)
                listOf("ollama" to "ollama serve", "git" to "git --version", "python" to "python --version", "nodejs" to "node --version", "curl" to "curl --version", "openssh" to "sshd").forEach { (pkg, run) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        G(pkg, 15, true, modifier = Modifier.weight(1f))
                        TextButton({
                            toast(t("بدأ التثبيت… قد يستغرق دقائق", "Installing… may take minutes"))
                            scope.launch {
                                val r = Termux.execWait(act, "set -o pipefail; pkg install -y $pkg 2>&1 | tail -n 4", 600000)
                                if (r == null) toast(t("تعذّر الوصول إلى Termux: تأكد من allow-external-apps وصلاحية تشغيل الأوامر", "Cannot reach Termux: check allow-external-apps and the run-commands permission"))
                                else if (r.first == 0) { if (Store.tools.none { it.url == "termux:$pkg" }) { Store.tools.add(Tool(uid(), pkg, "termux:$pkg", "")); Store.save() }; toast("✓ $pkg") }
                                else toast("✗ $pkg\n" + r.second.takeLast(300))
                            }
                        }) { Text(t("تثبيت", "Install"), color = Gold) }
                        TextButton({ toast(if (Termux.run(act, "nohup $run >/dev/null 2>&1 &")) t("تم التشغيل", "Started") else t("تعذّر الوصول إلى Termux", "Cannot reach Termux")) }) { Text(t("تشغيل", "Run"), color = Gold) }
                    }
                }
                GBtn(t("جلب البرامج المثبتة في Termux", "Fetch programs installed in Termux")) {
                    scope.launch {
                        val r = Termux.execWait(act, "apt-mark showmanual 2>/dev/null")
                        if (r == null || r.first != 0) toast("✗ " + (r?.second ?: t("لا رد من Termux", "No reply from Termux")))
                        else {
                            var n = 0
                            r.second.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') && !it.startsWith("lib") && it !in BASE_PKGS }.forEach { pkg ->
                                if (Store.tools.none { it.url == "termux:$pkg" }) { Store.tools.add(Tool(uid(), pkg, "termux:$pkg", "")); n++ }
                            }
                            Store.save(); toast("✓ $n")
                        }
                    }
                }
                GBtn(t("إضافة Claude Code (عبر Ollama)", "Add Claude Code (via Ollama)")) {
                    scope.launch {
                        toast(t("جارٍ الفحص…", "Checking…"))
                        val r = Termux.execWait(act, "proot-distro login ubuntu -- /root/.local/bin/claude --version", 180000)
                        if (r != null && r.first == 0 && r.second.isNotBlank()) {
                            if (Store.provs.none { it.url == "termux:claude" })
                                Store.provs.add(Prov(uid(), "Claude Code", "termux:claude", "", "claudecode", Api.models(Prov("", "", "termux:claude", "", "claudecode", emptyList()))))
                            Store.save(); toast("✓ " + r.second.take(60))
                        } else toast("✗ " + (r?.second ?: t("لا رد من Termux", "No reply from Termux")))
                    }
                }
                HorizontalDivider(color = Gold.copy(alpha = .4f), modifier = Modifier.padding(vertical = 6.dp))
                G(t("أداة للنموذج (رابط):", "Model tool (URL):"), 14)
                var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }; var k by remember { mutableStateOf("") }
                F(t("الاسم", "Name"), n) { n = it }; F("URL", u) { u = it }; F(t("كلمة المرور (اختياري)", "Password (optional)"), k, true) { k = it }
                GBtn(t("إضافة", "Add")) { if (u.isNotBlank()) { Store.tools.add(Tool(uid(), n.ifBlank { Store.hostOf(u) }, u.trim(), k.trim())); Store.save(); n = ""; u = ""; k = ""; toast("✓") } }
            }
            // CLI tools
            Sec(t("أدوات CLI (تثبيت وتحديث وتشغيل)", "CLI tools (install, update, run)")) {
                G(t("تعمل داخل Ubuntu في Termux، وقد يستغرق التثبيت دقائق. التشغيل التفاعلي ينسخ الأمر ويفتح Termux لتلصقه.", "They run inside Ubuntu in Termux; installing may take minutes. Interactive run copies the command and opens Termux for you to paste."), 13)
                GBtn(t("تثبيت الجميع (بالتتابع)", "Install all (in order)")) {
                    scope.launch {
                        for (c in CLIS) {
                            toast(t("جارٍ تثبيت ", "Installing ") + c.name + "…")
                            val r = Termux.execWait(act, if (c.native) c.install else Termux.ub(c.install), 1800000)
                            toast(if (r == null) "✗ " + c.name + ": " + t("لا رد", "no reply") else (if (r.first == 0) "✓ " else "✗ ") + c.name + (if (r.first != 0) "\n" + r.second.takeLast(150) else ""))
                        }
                    }
                }
                CLIS.forEach { c ->
                    Column(Modifier.padding(top = 10.dp)) {
                        G(c.name + (if (c.chat) "  ✓ " + t("دردشة داخل التطبيق", "in-app chat") else if (c.gateway.isNotEmpty()) "  ⇄ " + t("بوابة: تُضاف مزوداً", "gateway: add as provider") else "  — " + t("تفاعلي فقط", "interactive only")), 15, true)
                        FlowRow(Modifier.fillMaxWidth()) {
                            TextButton({ runCli(t("جارٍ تثبيت ", "Installing ") + c.name, c.install, 1200000, c.native) }) { Text(t("تثبيت", "Install"), color = Gold) }
                            TextButton({ runCli(t("جارٍ تحديث ", "Updating ") + c.name, c.update, 1200000, c.native) }) { Text(t("تحديث", "Update"), color = Gold) }
                            TextButton({ runCli(c.name, c.version, 180000, c.native) }) { Text(t("الإصدار", "Version"), color = Gold) }
                            TextButton({ openTermuxWith(act, if (c.native) c.launch else Termux.ub("export PATH=/usr/local/bin:/root/.local/bin:\$PATH; mkdir -p ~/project; cd ~/project; " + c.launch)) }) { Text(t("تشغيل", "Run"), color = Gold) }
                            if (c.chat || c.gateway.isNotEmpty()) TextButton({ scope.launch { toast(t("جارٍ الربط…", "Linking…")); toast(linkCli(act, c)) } }) { Text(t("ربط بالتطبيق", "Link to app"), color = Gold, fontWeight = FontWeight.Bold) }
                            if (c.gateway.isNotEmpty()) {
                                TextButton({ Termux.run(act, c.start); toast(t("بدأ تشغيل الخادم… انتظر قليلاً", "Server starting… wait a moment")) }) { Text(t("تشغيل الخادم", "Start server"), color = Gold) }
                                TextButton({ runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.dash))) } }) { Text(t("لوحة التحكم", "Dashboard"), color = Gold) }
                                if (c.name == "OmniRoute") TextButton({ scope.launch { toast(t("جارٍ الربط…", "Linking…")); toast(linkClaudeOmni(act)) } }) { Text(t("Claude Code عبر OmniRoute", "Claude Code via OmniRoute"), color = Gold, fontWeight = FontWeight.Bold) }
                                TextButton({ scope.launch { toast(addGateway(act, c)) } }) { Text(t("إضافة كمزود", "Add as provider"), color = Gold) }
                            }
                            if (c.name == "OpenCode") TextButton({ scope.launch {
                                toast(t("جارٍ جلب النماذج…", "Fetching models…"))
                                val ms = opencodeModels(act)
                                if (ms == null || ms.isEmpty()) toast("✗ " + t("لا نماذج: ثبّت OpenCode ثم اربط مزوداً داخله", "No models: install OpenCode and connect a provider in it"))
                                else {
                                    val i = Store.provs.indexOfFirst { it.kind == "opencode" }
                                    if (i >= 0) Store.provs[i] = Store.merge(Store.provs[i], ms)
                                    else Store.provs.add(Store.merge(Prov(uid(), "OpenCode", "termux:opencode", "", "opencode", emptyList()), ms))
                                    Store.save(); toast("✓ ${ms.size}")
                                }
                            } }) { Text(t("جلب النماذج", "Fetch models"), color = Gold) }
                        }
                    }
                }
            }
            // 4) Sites (account-level tokens)
            Sec(t("ربط المواقع (بحسابي)", "Connect sites (my account)")) {
                G(t("الصق رمز الوصول الشخصي لحسابك (وليس لمشروع معين). يُحفظ في التطبيق ويظهر كاملاً.", "Paste your account-level personal access token (not tied to one project). Saved in the app, shown in full."), 13)
                listOf(
                    Triple("github", "GitHub (GitHub Models)", "https://github.com/settings/personal-access-tokens/new"),
                    Triple("supabase", "Supabase (" + t("كل مشاريعك", "all your projects") + ")", "https://supabase.com/dashboard/account/tokens"),
                    Triple("google", "Google (Gemini)", "https://aistudio.google.com/apikey"),
                ).forEach { (id, label, page) ->
                    var tk by remember { mutableStateOf(Store.tokens[id] ?: "") }
                    Column(Modifier.padding(top = 8.dp)) {
                        G(label + if (Store.tokens[id] != null) "  ✓" else "", 16, true)
                        F(t("رمز الحساب", "Account token"), tk) { tk = it }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GBtn(t("فتح صفحة الرمز", "Open token page")) { runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) } }
                            GBtn(t("ربط", "Link")) { scope.launch { toast(linkSite(act, id, tk.trim())) } }
                            if (Store.tokens[id] != null) GBtn(t("فصل", "Unlink")) { Store.tokens.remove(id); tk = ""; Store.save() }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun LangScreen(back: () -> Unit) = Column(Modifier.fillMaxSize()) {
    Bar(t("اللغة", "Language"), back)
    listOf("ar" to "العربية", "en" to "English").forEach { (k, l) ->
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(Store.lang == k, { Store.lang = k; Store.save() }, colors = RadioButtonDefaults.colors(selectedColor = Gold, unselectedColor = Gold))
            G(l, 18)
        }
    }
}

@Composable fun SettingsScreen(act: MainActivity, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var add by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var diag by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Bar(t("الإعدادات", "Settings"), back)
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { G(t("قراءة الردود صوتياً", "Read replies aloud"), 16, modifier = Modifier.weight(1f))
                Switch(Store.speak, { Store.speak = it; Store.save() }, colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Color.Black)) }
            Row(verticalAlignment = Alignment.CenterVertically) { G(t("تشغيل Ollama تلقائياً عند فتح التطبيق", "Auto-start Ollama on launch"), 16, modifier = Modifier.weight(1f))
                Switch(Store.autoOllama, { Store.autoOllama = it; Store.save() }, colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Color.Black)) }
            G(t("عناوين Ollama", "Ollama addresses"), 17, true, Gold, Modifier.padding(top = 12.dp))
            Store.hosts.toList().forEach { h ->
                Row(verticalAlignment = Alignment.CenterVertically) { G(h, 15, modifier = Modifier.weight(1f))
                    IconButton({ Store.hosts.remove(h); Store.save() }) { Icon(Icons.Default.Delete, null, tint = Gold) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GBtn("+ " + t("عنوان", "Address")) { add = true }
                GBtn(t("فحص", "Check")) { scope.launch { status = withContext(Dispatchers.IO) { Store.hosts.joinToString("\n") { (if (Api.alive(it)) "✓ " else "✗ ") + it } } } }
                GBtn(t("تشغيل الآن", "Start now")) { Termux.run(act, Termux.START) }
            }
            if (status.isNotBlank()) G(status, 14, modifier = Modifier.padding(top = 6.dp))
            GBtn(t("فحص الاتصال بـ Termux والأدوات", "Check Termux & tools connection")) {
                scope.launch { diag = t("جارٍ الفحص…", "Checking…"); diag = diagnose(act) }
            }
            GBtn(t("استرجاع النماذج المحذوفة (${Store.hidden.size})", "Restore deleted models (${Store.hidden.size})")) { Store.hidden.clear(); Store.save() }
            G(t("تفعيل التحكم بـ Termux (مرة واحدة)", "Enable Termux control (once)"), 17, true, Gold, Modifier.padding(top = 16.dp))
            G(t("1) في Termux نفّذ:\nmkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings\n2) ثبّت Ollama:\npkg install ollama\n3) امنح AI+Termux صلاحية \"تشغيل أوامر Termux\" من إعدادات الأندرويد ← التطبيقات ← AI+Termux ← الأذونات.\n4) أعد تشغيل Termux مرة واحدة.",
                "1) In Termux run:\nmkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings\n2) Install Ollama:\npkg install ollama\n3) Grant AI+Termux the \"Run commands in Termux environment\" permission in Android Settings → Apps → AI+Termux → Permissions.\n4) Restart Termux once."), 13)
        }
    }
    diag?.let { d -> AlertDialog(onDismissRequest = { diag = null }, containerColor = Sf,
        title = { G(t("نتيجة الفحص", "Check result"), 18, true, Gold) },
        text = { SelectionContainer { G(d, 14) } },
        confirmButton = { TextButton({ diag = null }) { Text(t("إغلاق", "Close"), color = Gold) } }) }
    if (add) Ask(t("عنوان جديد", "New address"), listOf("host:port" to "127.0.0.1:11434"), { v -> if (v[0].isNotBlank() && v[0] !in Store.hosts) { Store.hosts.add(v[0].trim()); Store.save() }; add = false }, { add = false })
}

suspend fun addProvider(n: String, u: String, k: String): String {
    val kind = Store.classify(u).let { if (it == "tool") "openai" else it }
    val p = Prov(uid(), n.ifBlank { Store.hostOf(u) }, u.trim(), k.trim(), kind, emptyList())
    val r = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }
    return r.fold({ Store.provs.add(p.copy(models = it)); Store.save(); "✓ ${it.size}" }, { "✗ " + it.message })
}

fun assistantPrompt(): String = buildString {
    append(if (Store.lang == "ar") "أنت المساعد المسؤول عن تطوير وإدارة تطبيق AI+Termux. ساعد المستخدم في تطوير التطبيق وإدارة مزوديه وأدواته ونماذجه وتشغيل Ollama في Termux. أجب بإيجاز ودقة.\n" else "You are the assistant responsible for developing and managing the AI+Termux app. Help with the app, its providers, tools, models and Ollama in Termux. Be concise and accurate.\n")
    append("Providers:\n"); Store.provs.forEach { append("- ${it.name} ${it.url} [${it.kind}] models=${it.models.take(15)} old=${it.old.size}\n") }
    append("Tools:\n"); Store.tools.forEach { append("- ${it.name} ${it.url}\n") }
}

@Composable fun WebScreen(url: String, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Bar(url.substringAfter("://").substringBefore("/"), back)
        androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = android.webkit.WebViewClient()
                loadUrl(url)
            }
        })
    }
}

val NODE = "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y nodejs npm"

data class Cli(val name: String, val install: String, val update: String, val version: String, val launch: String, val chat: Boolean, val native: Boolean = false, val gateway: String = "", val start: String = "", val dash: String = "")

val CLIS = listOf(
    Cli("OpenCode", "$NODE && npm install -g opencode-ai", "npm install -g opencode-ai@latest", "opencode --version", "opencode", true),
    Cli("Claude Code", "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y curl git ca-certificates && curl -fsSL https://claude.ai/install.sh | bash", "/root/.local/bin/claude update", "/root/.local/bin/claude --version", "/root/.local/bin/claude", true),
    Cli("Freebuff", "$NODE && npm install -g freebuff", "npm install -g freebuff@latest", "freebuff --version", "freebuff", false),
    Cli("free-pi", NODE, "npm view free-pi-cli version", "npm view free-pi-cli version", "npx free-pi-cli@latest", false),
    Cli("OmniRoute", "pkg install -y nodejs python build-essential git && npm install -g omniroute", "npm install -g omniroute@latest", "npm ls -g omniroute --depth=0", "omniroute", false,
        native = true, gateway = "http://127.0.0.1:20128/v1", start = Termux.START_OMNI, dash = "http://127.0.0.1:20128"),
    Cli("FreeLLMAPI", "pkg install -y nodejs-lts git && ( [ -d ~/freellmapi ] || git clone https://github.com/tashfeenahmed/freellmapi.git ~/freellmapi ) && cd ~/freellmapi && git fetch --tags -q && git checkout -f v0.16.21 && npm install --no-audit --no-fund",
        "cd ~/freellmapi && git fetch --tags -q && git checkout -f \$(git tag --sort=-v:refname | head -n 1) && npm install --no-audit --no-fund", "cd ~/freellmapi && git describe --tags", "cd ~/freellmapi && npm run dev", false,
        native = true, gateway = "http://127.0.0.1:3001/v1", start = Termux.START_FREE, dash = "http://127.0.0.1:5173"),
)

suspend fun opencodeModels(act: MainActivity): List<String>? {
    val r = Termux.execWait(act, Termux.ub("export PATH=/usr/local/bin:\$PATH; opencode models 2>&1"), 180000) ?: return null
    if (r.first != 0) return null
    return r.second.lines().map { it.trim() }.filter { it.contains('/') && !it.contains(' ') }
}

fun openTermuxWith(act: MainActivity, cmd: String) {
    copy(act, cmd)
    act.packageManager.getLaunchIntentForPackage("com.termux")?.let { act.startActivity(it) }
}

suspend fun addGateway(act: MainActivity, c: Cli): String {
    val p = Prov(uid(), c.name, c.gateway, "", "openai", emptyList(), emptyList(), false, c.start)
    withContext(Dispatchers.IO) { Api.ensure(act, p) }
    val r = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }
    val i = Store.provs.indexOfFirst { it.url == p.url }
    val ms = r.getOrNull()
    if (ms != null) { if (i >= 0) Store.provs[i] = Store.merge(Store.provs[i], ms) else Store.provs.add(Store.merge(p, ms)) }
    else if (i < 0) Store.provs.add(p)
    Store.save()
    return if (ms != null) "✓ ${ms.size} " + t("نموذج", "models")
    else t("أُضيف دون نماذج: ", "Added without models: ") + (r.exceptionOrNull()?.message ?: "") + "\n" + t("افتح لوحة التحكم وأنشئ مفتاحاً ثم عدّل المزود في مكتبتي وحدّث", "Open the dashboard, create a key, then edit the provider in My library and refresh")
}

suspend fun cliInstalled(act: MainActivity, c: Cli): Boolean {
    val cmd = if (c.native) c.version else Termux.ub("export PATH=/usr/local/bin:/root/.local/bin:\$PATH; " + c.version)
    val r = Termux.execWait(act, cmd, 90000)
    return r != null && r.first == 0
}

suspend fun linkAll(act: MainActivity): String {
    val out = mutableListOf<String>()
    out.addAll(scanLocal(act))
    for (c in CLIS) {
        if (!(c.chat || c.gateway.isNotEmpty())) continue
        val key = if (c.gateway.isNotEmpty()) c.gateway else if (c.name == "OpenCode") "termux:opencode" else "termux:claude"
        if (Store.provs.any { it.url == key }) continue
        out.add(if (cliInstalled(act, c)) linkCli(act, c) else "– ${c.name}: " + t("غير مثبّت", "not installed"))
    }
    for (p in Store.provs.toList().sortedBy { if (it.kind == "claudecode") 1 else 0 }) {
        val ms: List<String>? = if (p.kind == "opencode") opencodeModels(act)
        else withContext(Dispatchers.IO) { runCatching { Api.ensure(act, p); Api.models(p) }.getOrNull() }
        if (ms != null) {
            val i = Store.provs.indexOfFirst { it.id == p.id }
            if (i >= 0) Store.provs[i] = Store.merge(Store.provs[i], ms)
            out.add("✓ ${p.name}: ${ms.size}")
        } else out.add("✗ ${p.name}")
    }
    Store.save()
    return out.joinToString("\n").ifBlank { t("لا يوجد مزودون بعد", "No providers yet") }
}

suspend fun diagnose(act: MainActivity): String {
    val sb = StringBuilder()
    val r = Termux.execWait(act, "echo ok; uname -m", 25000)
    if (r != null && r.first == 0) sb.append("✓ Termux: ").append(r.second.lines().joinToString(" ")).append("\n")
    else {
        sb.append("✗ Termux: ").append(t("لا رد. فعّل allow-external-apps ومنح صلاحية تشغيل الأوامر، ثم أوقف Termux إجبارياً وافتحه.", "no reply. Enable allow-external-apps and grant the run-commands permission, then force-stop and reopen Termux.")).append("\n")
    }
    if (r != null && r.first == 0) {
        val c = Termux.execWait(act, "for c in ollama node npm git proot-distro; do command -v \$c >/dev/null && echo ✓ \$c || echo ✗ \$c; done; ls ~/freellmapi >/dev/null 2>&1 && echo ✓ freellmapi || echo ✗ freellmapi; ls ~/.local 2>/dev/null | head -1 >/dev/null", 40000)
        if (c != null) sb.append(c.second).append("\n")
        val u = Termux.execWait(act, "proot-distro list 2>&1 | grep -i ubuntu | head -3", 40000)
        sb.append("Ubuntu: ").append(u?.second?.ifBlank { "✗" } ?: "✗").append("\n")
    }
    for ((name, url) in listOf("Ollama" to "http://127.0.0.1:11434/api/tags", "OmniRoute" to "http://127.0.0.1:20128/v1/models", "FreeLLMAPI" to "http://127.0.0.1:3001/v1/models")) {
        val ok = withContext(Dispatchers.IO) { Api.aliveUrl(url) }
        sb.append(if (ok) "✓ " else "✗ ").append(name).append(" ").append(url.substringAfter("//").substringBefore("/"))
            .append(if (ok) "" else "  (" + t("لا يعمل الآن", "not running") + ")").append("\n")
    }
    return sb.toString().trim()
}

val BASE_PKGS = setOf("apt", "bash", "bzip2", "ca-certificates", "command-not-found", "coreutils", "dash", "debianutils", "dialog", "diffutils", "dos2unix", "dpkg",
    "ed", "findutils", "gawk", "gpgv", "grep", "gzip", "inetutils", "less", "ncurses", "patch", "pcre2", "procps", "psmisc", "readline", "resolv-conf", "sed", "tar",
    "termux-am", "termux-am-socket", "termux-core", "termux-exec", "termux-keyring", "termux-licenses", "termux-tools", "util-linux", "xxhash", "xz-utils", "zlib", "zstd",
    "net-tools", "lsof", "openssl", "unzip", "zip", "git-lfs", "fd", "ripgrep", "python-numpy", "python-pandas", "python-pillow")

fun upsertProv(p: Prov, ms: List<String>?) {
    val i = Store.provs.indexOfFirst { it.url == p.url }
    if (i >= 0) {
        Store.provs[i] = Store.provs[i].copy(key = p.key)
        if (ms != null) Store.provs[i] = Store.merge(Store.provs[i], ms)
    } else Store.provs.add(if (ms != null) Store.merge(p, ms) else p)
}

suspend fun linkCli(act: MainActivity, c: Cli): String {
    if (c.gateway.isNotEmpty()) return addGateway(act, c)
    if (c.name == "OpenCode") {
        val ms = opencodeModels(act) ?: return "✗ OpenCode: " + t("ثبّته أولاً أو لا توجد نماذج", "install it first or no models")
        upsertProv(Prov(uid(), "OpenCode", "termux:opencode", "", "opencode", emptyList()), ms)
        Store.save(); return "✓ OpenCode: ${ms.size} " + t("نموذج", "models")
    }
    if (c.name == "Claude Code") {
        val r = Termux.execWait(act, Termux.ub("/root/.local/bin/claude --version"), 120000)
        if (r == null || r.first != 0) return "✗ Claude Code: " + (r?.second ?: t("لا رد", "no reply"))
        val p = Prov(uid(), "Claude Code", "termux:claude", "", "claudecode", emptyList())
        if (Store.provs.none { it.url == "termux:claude" }) Store.provs.add(p.copy(models = Api.models(p)))
        Store.save(); return "✓ Claude Code " + r.second.take(30)
    }
    return ""
}

suspend fun linkSite(act: MainActivity, site: String, token: String): String {
    if (token.isBlank()) return t("الصق الرمز أولاً", "Paste the token first")
    when (site) {
        "github" -> {
            val login = withContext(Dispatchers.IO) { runCatching { JSONObject(Api.get("https://api.github.com/user", token)).getString("login") } }
                .getOrElse { return "✗ GitHub: " + (it.message ?: "") }
            val p = Prov(uid(), "GitHub Models", "https://models.github.ai/inference", token, "openai", emptyList())
            val ms = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }.getOrNull()
            Store.tokens["github"] = token
            upsertProv(p, ms); Store.save()
            return "✓ GitHub: $login\n" + (if (ms != null) "${ms.size} " + t("نموذج", "models") else t("لا نماذج: أنشئ رمزاً دقيق الصلاحيات (Fine-grained) مع Models: Read", "No models: create a fine-grained token with Models: Read"))
        }
        "supabase" -> {
            val arr = withContext(Dispatchers.IO) { runCatching { JSONArray(Api.get("https://api.supabase.com/v1/projects", token)) } }
                .getOrElse { return "✗ Supabase: " + (it.message ?: "") }
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = "https://" + o.optString("id") + ".supabase.co"
                if (Store.tools.none { it.url == url }) { Store.tools.add(Tool(uid(), "Supabase: " + o.optString("name"), url, "")); n++ }
            }
            Store.tokens["supabase"] = token; Store.save()
            return "✓ Supabase: ${arr.length()} " + t("مشروع", "projects") + " ($n " + t("جديد", "new") + ")"
        }
        "google" -> {
            val p = Prov(uid(), "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", token, "openai", emptyList())
            val ms = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }.getOrElse { return "✗ Gemini: " + (it.message ?: "") }
            Store.tokens["google"] = token
            upsertProv(p, ms); Store.save()
            return "✓ Gemini: ${ms.size} " + t("نموذج", "models")
        }
    }
    return ""
}

val SCAN_PORTS = listOf(11434, 20128, 3001, 4000, 8000, 8080, 1234, 5000)

/** Finds the command line that started the server listening on [port] (via lsof in Termux), so the app can restart it later by itself. */
suspend fun captureStart(act: MainActivity, port: Int): String? {
    val r = Termux.execWait(act, "pid=\$(lsof -nP -iTCP:$port -sTCP:LISTEN -t 2>/dev/null | head -n 1); [ -n \"\$pid\" ] && echo \"\$(readlink /proc/\$pid/cwd)|\$(tr '\\0' ' ' < /proc/\$pid/cmdline)\"", 30000) ?: return null
    if (r.first != 0) return null
    val line = r.second.trim()
    if ('|' !in line) return null
    val cwd = line.substringBefore('|').trim()
    val cmd = line.substringAfter('|').trim()
    if (cmd.isBlank()) return null
    return (if (cwd.isNotBlank()) "cd '$cwd' && " else "") + "nohup $cmd > ~/aitermux-$port.log 2>&1 &"
}

/** Saves every running local AI server (with its models and start command) into the app. */
suspend fun scanLocal(act: MainActivity): List<String> {
    val out = mutableListOf<String>()
    for (port in SCAN_PORTS) {
        val base = "http://127.0.0.1:$port"
        if (!withContext(Dispatchers.IO) { Api.aliveUrl(base) }) continue
        var url = base
        var kind = "termux"
        var ms = withContext(Dispatchers.IO) { runCatching { Api.models(Prov("", "", base, "", "ollama", emptyList())) }.getOrNull() }
        if (ms == null) {
            url = "$base/v1"; kind = "openai"
            ms = withContext(Dispatchers.IO) { runCatching { Api.models(Prov("", "", url, "", "openai", emptyList())) }.getOrNull() }
            if (ms == null && port != 20128 && port != 3001) continue
        }
        val name = when (port) { 11434 -> "Ollama 127.0.0.1:11434"; 20128 -> "OmniRoute"; 3001 -> "FreeLLMAPI"; else -> "Local :$port" }
        val i = Store.provs.indexOfFirst { it.url == url }
        val known = if (i >= 0) Store.provs[i].start else ""
        val start = known.ifBlank { captureStart(act, port) ?: when (port) { 11434 -> Termux.START; 20128 -> Termux.START_OMNI; 3001 -> Termux.START_FREE; else -> "" } }
        if (i >= 0) {
            Store.provs[i] = Store.provs[i].copy(start = start)
            if (ms != null) Store.provs[i] = Store.merge(Store.provs[i], ms)
        } else {
            val p = Prov(uid(), name, url, "", kind, emptyList(), emptyList(), false, start)
            Store.provs.add(if (ms != null) Store.merge(p, ms) else p)
        }
        out.add("✓ $name :$port — " + (ms?.size ?: 0) + " " + t("نموذج", "models") + (if (ms == null) " (" + t("يحتاج مفتاحاً", "needs a key") + ")" else ""))
    }
    Store.save()
    return out
}

suspend fun linkClaudeOmni(act: MainActivity): String {
    val omni = Store.provs.firstOrNull { ":20128" in it.url }
        ?: return t("أضف OmniRoute كمزود أولاً (زر ربط بالتطبيق)", "Add OmniRoute as a provider first (Link to app)")
    if (omni.key.isBlank()) return t("عدّل مزود OmniRoute في مكتبتي وضع مفتاح API الذي أنشأته في لوحته", "Edit the OmniRoute provider in My library and set the API key you created in its dashboard")
    val r = Termux.execWait(act, Termux.ub("/root/.local/bin/claude --version"), 120000)
    if (r == null || r.first != 0) return "✗ Claude Code: " + (r?.second ?: t("لا رد", "no reply"))
    val p = Prov(uid(), "Claude Code (OmniRoute)", "termux:claude-omni", "", "claudecode", emptyList())
    if (Store.provs.none { it.url == p.url }) Store.provs.add(p.copy(models = Api.models(p)))
    Store.save()
    return "✓ Claude Code (OmniRoute): " + Api.models(p).size + " " + t("نموذج (يبدأ بـ auto)", "models (starting with auto)")
}
