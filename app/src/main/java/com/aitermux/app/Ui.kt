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
    LaunchedEffect(chat.msgs.size) { if (chat.msgs.isNotEmpty()) ls.animateScrollToItem(chat.msgs.size - 1) }

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
            val r = withContext(Dispatchers.IO) { runCatching { Api.ensure(act, e.p); Api.chat(e.p, e.m, h, imgs) } }
            val reply = r.getOrElse { "⚠ \u2066" + (it.message ?: it.toString()) + "\u2069" }
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
                DropdownMenu(menu, { menu = false }, containerColor = Sf) {
                    entries.forEach { e ->
                        val same = entries.filter { it.m == e.m }
                        DropdownMenuItem(text = {
                            G((if (e.key == Store.star) "★ " else "") + (if (e.old) "⌛ " else "") + (if (e.p.kind == "termux") "T  " else "") + short(e.m) + (if (same.size > 1) "  (${same.indexOf(e) + 1}) ${short(e.p.name)}" else "") + (if (isVision(e.m)) "  👁" else ""), 14)
                        }, trailingIcon = { Row { IconButton({ Store.star = if (Store.star == e.key) null else e.key; Store.save() }) { Icon(if (Store.star == e.key) Icons.Default.Star else Icons.Default.StarBorder, null, tint = Gold) }; IconButton({ Store.hideModel(e.p.id, e.m) }) { Icon(Icons.Default.Delete, null, tint = Gold) } } }, onClick = { selKey = e.key; menu = false })
                    }
                    DropdownMenuItem(text = { G("⬇ " + t("تنزيل نموذج جديد", "Download new model"), 14, true, Gold) }, onClick = { menu = false; dl = true })
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
                        .border(1.dp, Gold.copy(alpha = if (me) .8f else .3f), RoundedCornerShape(12.dp)).padding(10.dp)) { G(m.text) }
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

@Composable fun Library(act: MainActivity, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editP by remember { mutableStateOf<Prov?>(null) }
    var editT by remember { mutableStateOf<Tool?>(null) }
    var delP by remember { mutableStateOf<Prov?>(null) }
    var delT by remember { mutableStateOf<Tool?>(null) }
    var clear by remember { mutableStateOf(false) }
    var addP by remember { mutableStateOf(false) }
    var addT by remember { mutableStateOf(false) }
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
            GBtn(t("استيراد", "Import")) { imp.launch(arrayOf("*/*")) }
            GBtn(t("تصدير", "Export")) { exp.launch("ai-termux-library.json") }
            GBtn(t("مسح الكل", "Clear all")) { clear = true }
        }
        LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { G(t("المزودون", "Providers"), 17, true, Gold, Modifier.weight(1f)); GBtn("+ " + t("إضافة مزود خدمة", "Add provider")) { addP = true } } }
            items(Store.provs.toList()) { p ->
                Column(Modifier.fillMaxWidth().border(1.dp, Gold.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                    G(p.name + (if (p.kind == "termux") "  [T]" else ""), 16, true)
                    G(p.url, 13); G("🔑 " + p.key, 13); G(t("النماذج: ", "Models: ") + p.models.size + (if (p.old.isNotEmpty()) " (+${p.old.size} ⌛)" else ""), 12, color = Gold)
                    Row {
                        IconButton({ copy(act, "${p.name}\n${p.url}\n${p.key}") }) { Icon(Icons.Default.ContentCopy, null, tint = Gold) }
                        IconButton({ editP = p }) { Icon(Icons.Default.Edit, null, tint = Gold) }
                        IconButton({ scope.launch {
                            val ms = withContext(Dispatchers.IO) { runCatching { Api.models(p) }.getOrNull() }
                            if (ms != null) { val i = Store.provs.indexOfFirst { it.id == p.id }; if (i >= 0) Store.provs[i] = Store.merge(p, ms); Store.save() }
                            else Toast.makeText(act, "✗", Toast.LENGTH_SHORT).show() } }) { Icon(Icons.Default.Refresh, null, tint = Gold) }
                        IconButton({ delP = p }) { Icon(Icons.Default.Delete, null, tint = Gold) }
                        IconButton({ addP = true }) { Icon(Icons.Default.Add, null, tint = Gold) }
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { G(t("الأدوات", "Tools"), 17, true, Gold, Modifier.weight(1f)); GBtn("+ " + t("إضافة أداة", "Add tool")) { addT = true } } }
            items(Store.tools.toList()) { x ->
                Column(Modifier.fillMaxWidth().border(1.dp, Gold.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                    G(x.name, 16, true); G(x.url, 13); G("🔑 " + x.key, 13)
                    Row {
                        IconButton({ copy(act, "${x.name}\n${x.url}\n${x.key}") }) { Icon(Icons.Default.ContentCopy, null, tint = Gold) }
                        IconButton({ editT = x }) { Icon(Icons.Default.Edit, null, tint = Gold) }
                        IconButton({ delT = x }) { Icon(Icons.Default.Delete, null, tint = Gold) }
                        IconButton({ addT = true }) { Icon(Icons.Default.Add, null, tint = Gold) }
                    }
                }
            }
        }
    }
    if (addP) Ask(t("إضافة مزود خدمة", "Add provider"), listOf(t("الاسم", "Name") to "", "URL" to "", t("المفتاح (اختياري)", "Key (optional)") to ""),
        { v -> addP = false; scope.launch { Toast.makeText(act, addProvider(v[0], v[1], v[2]), Toast.LENGTH_LONG).show() } }, { addP = false })
    if (addT) Ask(t("إضافة أداة", "Add tool"), listOf(t("الاسم", "Name") to "", "URL" to "", t("كلمة المرور (اختياري)", "Password (optional)") to ""),
        { v -> if (v[1].isNotBlank()) { Store.tools.add(Tool(uid(), v[0].ifBlank { Store.hostOf(v[1]) }, v[1].trim(), v[2].trim())); Store.save() }; addT = false }, { addT = false })
    editP?.let { p -> Ask(t("تعديل", "Edit"), listOf(t("الاسم", "Name") to p.name, "URL" to p.url, t("المفتاح", "Key") to p.key),
        { v -> val i = Store.provs.indexOfFirst { it.id == p.id }; if (i >= 0) Store.provs[i] = p.copy(name = v[0], url = v[1], key = v[2]); Store.save(); editP = null }, { editP = null }) }
    editT?.let { x -> Ask(t("تعديل", "Edit"), listOf(t("الاسم", "Name") to x.name, "URL" to x.url, t("المفتاح", "Key") to x.key),
        { v -> val i = Store.tools.indexOfFirst { it.id == x.id }; if (i >= 0) Store.tools[i] = x.copy(name = v[0], url = v[1], key = v[2]); Store.save(); editT = null }, { editT = null }) }
    delP?.let { p -> Confirm(t("حذف ${p.name}؟", "Delete ${p.name}?"), { Store.provs.remove(p); Store.save(); delP = null }, { delP = null }) }
    delT?.let { x -> Confirm(t("حذف ${x.name}؟", "Delete ${x.name}?"), { Store.tools.remove(x); Store.save(); delT = null }, { delT = null }) }
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

@Composable fun AddScreen(act: MainActivity, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Bar(t("الإضافة", "Add"), back)
        Column(Modifier.padding(horizontal = 10.dp)) {
            // 1) Provider
            Sec(t("ربط من مزود", "Connect provider")) {
                var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }; var k by remember { mutableStateOf("") }
                F(t("الاسم", "Name"), n) { n = it }; F("URL", u) { u = it }; F(t("كلمة المرور / المفتاح (اختياري)", "Key (optional)"), k, true) { k = it }
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
                        Termux.run(act, Termux.START); delay(3000)
                        val p = Prov(uid(), "Ollama " + Store.hostOf(u), u.trim(), k.trim(), "termux", emptyList())
                        val r = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }
                        r.onSuccess { ms ->
                            val i = Store.provs.indexOfFirst { it.url == p.url }
                            if (i >= 0) Store.provs[i] = Store.merge(Store.provs[i], ms) else Store.provs.add(Store.merge(p, ms))
                            Store.save(); toast("✓ ${ms.size} " + t("نموذج", "models"))
                        }.onFailure { toast("✗ " + it.message + "\n" + t("تأكد من تفعيل allow-external-apps (الإعدادات)", "Check allow-external-apps (Settings)")) }
                    }
                }
            }
            // 3) Tools
            Sec(t("جلب أدوات", "Fetch tools")) {
                G(t("برامج Termux (تثبيت / تشغيل بزر واحد):", "Termux programs (install / run):"), 14)
                listOf("ollama" to "ollama serve", "git" to "git --version", "python" to "python --version", "nodejs" to "node --version", "curl" to "curl --version", "openssh" to "sshd").forEach { (pkg, run) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        G(pkg, 15, true, modifier = Modifier.weight(1f))
                        TextButton({ if (Store.tools.none { it.url == "termux:$pkg" }) { Store.tools.add(Tool(uid(), pkg, "termux:$pkg", "")); Store.save() }; toast(if (Termux.run(act, "pkg install -y $pkg")) t("بدأ التثبيت", "Installing…") else t("تعذّر الوصول إلى Termux", "Cannot reach Termux")) }) { Text(t("تثبيت", "Install"), color = Gold) }
                        TextButton({ toast(if (Termux.run(act, "nohup $run >/dev/null 2>&1 &")) t("تم التشغيل", "Started") else t("تعذّر الوصول إلى Termux", "Cannot reach Termux")) }) { Text(t("تشغيل", "Run"), color = Gold) }
                    }
                }
                HorizontalDivider(color = Gold.copy(alpha = .4f), modifier = Modifier.padding(vertical = 6.dp))
                G(t("أداة للنموذج (رابط):", "Model tool (URL):"), 14)
                var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }; var k by remember { mutableStateOf("") }
                F(t("الاسم", "Name"), n) { n = it }; F("URL", u) { u = it }; F(t("كلمة المرور (اختياري)", "Password (optional)"), k, true) { k = it }
                GBtn(t("إضافة", "Add")) { if (u.isNotBlank()) { Store.tools.add(Tool(uid(), n.ifBlank { Store.hostOf(u) }, u.trim(), k.trim())); Store.save(); n = ""; u = ""; k = ""; toast("✓") } }
            }
            // 4) Sites
            Sec(t("ربط المواقع", "Connect sites")) {
                G(t("سجّل تطبيقك في كل موقع للحصول على Client ID، وأضف Redirect URI:  aitermux://oauth", "Register your app on each site to get a Client ID; set Redirect URI: aitermux://oauth"), 13)
                listOf("github", "google", "supabase").forEach { s ->
                    var id by remember { mutableStateOf(Store.clientIds[s] ?: "") }
                    Column(Modifier.padding(top = 8.dp)) {
                        G(s.replaceFirstChar { it.uppercase() } + if (Store.tokens[s] != null) "  ✓" else "", 16, true)
                        F("Client ID", id) { id = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GBtn(t("تسجيل الدخول", "Sign in")) {
                                Store.clientIds[s] = id; Store.save()
                                val r = "aitermux://oauth"
                                val url = when (s) {
                                    "github" -> "https://github.com/login/oauth/authorize?client_id=$id&redirect_uri=$r&scope=repo&state=github"
                                    "google" -> "https://accounts.google.com/o/oauth2/v2/auth?client_id=$id&redirect_uri=$r&response_type=code&scope=openid%20email&state=google"
                                    else -> "https://api.supabase.com/v1/oauth/authorize?client_id=$id&redirect_uri=$r&response_type=code&state=supabase"
                                }
                                runCatching { CustomTabsIntent.Builder().build().launchUrl(act, Uri.parse(url)) }
                            }
                            if (Store.tokens[s] != null) GBtn(t("خروج", "Sign out")) { Store.tokens.remove(s); Store.save() }
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
            GBtn(t("استرجاع النماذج المحذوفة (${Store.hidden.size})", "Restore deleted models (${Store.hidden.size})")) { Store.hidden.clear(); Store.save() }
            G(t("تفعيل التحكم بـ Termux (مرة واحدة)", "Enable Termux control (once)"), 17, true, Gold, Modifier.padding(top = 16.dp))
            G(t("1) في Termux نفّذ:\nmkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings\n2) ثبّت Ollama:\npkg install ollama\n3) امنح AI+Termux صلاحية \"تشغيل أوامر Termux\" من إعدادات الأندرويد ← التطبيقات ← AI+Termux ← الأذونات.\n4) أعد تشغيل Termux مرة واحدة.",
                "1) In Termux run:\nmkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings\n2) Install Ollama:\npkg install ollama\n3) Grant AI+Termux the \"Run commands in Termux environment\" permission in Android Settings → Apps → AI+Termux → Permissions.\n4) Restart Termux once."), 13)
        }
    }
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
