package com.aitermux.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

// ------------------------------------------------------------ helpers
private fun short(s: String) = if (s.length > 26) s.take(13) + "…" + s.takeLast(10) else s

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth(), singleLine = false, maxLines = 4
    )
}

@Composable
private fun GoldButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier) { Text(text) }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Gold) }
        Text(title, color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Gold.copy(alpha = 0.4f))
}

private fun openTab(ctx: android.content.Context, url: String) {
    try { CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url)) } catch (_: Exception) {}
}

// ------------------------------------------------------------ root
@Composable
fun Root(vm: AppVM, onSpeak: (String) -> Unit, onStopSpeak: () -> Unit) {
    val dir = if (vm.lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BackHandler(vm.screen != "chat") { vm.screen = "chat" }
            LaunchedEffect(vm.speakRequest) {
                vm.speakRequest?.let { onSpeak(it); vm.speakRequest = null }
            }
            when (vm.screen) {
                "library" -> LibraryScreen(vm)
                "add" -> AddScreen(vm)
                "settings" -> SettingsScreen(vm)
                else -> ChatScreen(vm, onSpeak, onStopSpeak)
            }
            vm.confirm?.let { c ->
                AlertDialog(
                    onDismissRequest = { c.result.complete(false) },
                    title = { Text(vm.t("confirm")) },
                    text = { SelectionContainer { Text(c.text) } },
                    confirmButton = { TextButton({ c.result.complete(true) }) { Text(vm.t("allow")) } },
                    dismissButton = { TextButton({ c.result.complete(false) }) { Text(vm.t("deny")) } }
                )
            }
        }
    }
}

// ------------------------------------------------------------ chat
@Composable
fun ChatScreen(vm: AppVM, onSpeak: (String) -> Unit, onStopSpeak: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showPull by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }
    var gear by remember { mutableStateOf(false) }

    val attachL = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { vm.attach(it) }
    val micL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val s = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (s != null) input = if (input.isBlank()) s else "$input $s"
    }

    Column(Modifier.fillMaxSize()) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ModelPicker(vm, Modifier.weight(1f)) { showPull = true }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.History, vm.t("history"), tint = Gold) }
            IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, vm.t("new_chat"), tint = Gold) }
            Box {
                IconButton(onClick = { gear = true }) { Icon(Icons.Default.Settings, null, tint = Gold) }
                DropdownMenu(expanded = gear, onDismissRequest = { gear = false }) {
                    DropdownMenuItem(text = { Text(vm.t("library")) }, onClick = { gear = false; vm.screen = "library" })
                    DropdownMenuItem(text = { Text(vm.t("add")) }, onClick = { gear = false; vm.screen = "add" })
                    DropdownMenuItem(text = { Text(vm.t("language")) }, onClick = { gear = false; showLang = true })
                    DropdownMenuItem(text = { Text(vm.t("settings")) }, onClick = { gear = false; vm.screen = "settings" })
                }
            }
        }
        HorizontalDivider(color = Gold.copy(alpha = 0.4f))
        if (vm.status.isNotBlank()) {
            Text(vm.status, color = Gold, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { vm.status = "" }.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        // ---- messages
        val msgs = vm.current?.msgs
        val list = rememberLazyListState()
        val lastLen = msgs?.lastOrNull()?.content?.length ?: 0
        LaunchedEffect(msgs?.size) { if ((msgs?.size ?: 0) > 0) list.scrollToItem(msgs!!.size - 1) }
        LaunchedEffect(lastLen) { if (vm.busy && (msgs?.size ?: 0) > 0) list.scrollToItem(msgs!!.size - 1) }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                detectTransformGestures { _, _, z, _ -> vm.zoom = (vm.zoom * z).coerceIn(0.6f, 3f) }
            }
        ) {
            if (msgs == null || msgs.isEmpty()) {
                Text("AI", color = Gold.copy(alpha = 0.35f), fontSize = 96.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(msgs.size) { i -> Bubble(vm, msgs[i], onSpeak, onStopSpeak) }
                }
            }
        }

        // ---- pending attachments
        if (vm.pending.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                Column {
                    vm.pending.toList().forEach { a ->
                        AssistChip(onClick = { vm.pending.remove(a) }, label = { Text("📎 " + short(a.name) + "  ✕") })
                    }
                }
            }
        }

        // ---- input
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { attachL.launch("*/*") }) { Icon(Icons.Default.AttachFile, vm.t("attach"), tint = Gold) }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(vm.t("type_msg")) }, maxLines = 5
            )
            IconButton(onClick = {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (vm.lang == "ar") "ar-EG" else "en-US")
                try { micL.launch(i) } catch (e: ActivityNotFoundException) { vm.status = "⚠ Speech service not found" }
            }) { Icon(Icons.Default.Mic, vm.t("mic"), tint = Gold) }
            if (vm.busy) {
                IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, vm.t("stop"), tint = Gold) }
            } else {
                IconButton(onClick = { vm.send(input); input = "" }) {
                    Icon(Icons.Default.Send, null, tint = Gold)
                }
            }
        }
    }

    if (showHistory) HistoryDialog(vm) { showHistory = false }
    if (showPull) PullDialog(vm) { showPull = false }
    if (showLang) {
        AlertDialog(
            onDismissRequest = { showLang = false }, title = { Text(vm.t("language")) },
            text = {
                Column {
                    TextButton({ vm.lang = "ar"; vm.save(); showLang = false }) { Text(vm.t("arabic")) }
                    TextButton({ vm.lang = "en"; vm.save(); showLang = false }) { Text(vm.t("english")) }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun Bubble(vm: AppVM, m: Msg, onSpeak: (String) -> Unit, onStop: () -> Unit) {
    val user = m.role == "user"
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Surface(
            color = if (user) Color(0xFF3A2F0B) else Panel, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (user) Gold else Color(0xFF333333))
        ) {
            Column(Modifier.padding(10.dp)) {
                m.attachments.forEach { Text("📎 " + it.name, fontSize = 12.sp, color = Gold) }
                SelectionContainer {
                    Text(m.content.ifBlank { "…" }, fontSize = (15f * vm.zoom).sp, color = Cream)
                }
                if (!user && m.content.isNotBlank()) {
                    Row {
                        IconButton(onClick = { clip.setText(AnnotatedString(m.content)) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, vm.t("copy"), tint = Gold, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { onSpeak(m.content) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.VolumeUp, vm.t("read_aloud"), tint = Gold, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Stop, vm.t("stop"), tint = Gold, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(vm: AppVM, modifier: Modifier, onDownload: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sorted = vm.sortedModels()
    val groups = sorted.groupBy { it.modelId }
    fun label(m: ModelEntry): String {
        val n = groups[m.modelId]!!
        val num = if (n.size > 1) " #${n.indexOfFirst { it.providerId == m.providerId } + 1}" else ""
        return (if (m.fromTermux) "T " else "") + short(m.modelId) + num + (if (m.vision) " 👁" else "")
    }
    val cur = sorted.find { it.providerId + "|" + it.modelId == vm.selected }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Text(cur?.let { label(it) } ?: vm.t("choose_model"), color = Gold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp))
            Icon(Icons.Default.ArrowDropDown, null, tint = Gold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = 320.dp)) {
            sorted.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label(m), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(vm.providerName(m.providerId), fontSize = 11.sp, color = Gold.copy(alpha = 0.8f))
                        }
                    },
                    onClick = { vm.selected = m.providerId + "|" + m.modelId; vm.save(); open = false }
                )
            }
            HorizontalDivider(color = Gold.copy(alpha = 0.4f))
            DropdownMenuItem(
                text = { Text("⬇ " + vm.t("download_model")) },
                onClick = { open = false; onDownload() }
            )
        }
    }
}

@Composable
private fun PullDialog(vm: AppVM, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose, title = { Text(vm.t("download_model")) },
        text = { Field(vm.t("model_name"), name, { name = it }) },
        confirmButton = { TextButton({ if (name.isNotBlank()) vm.pull(name.trim()); onClose() }) { Text(vm.t("download")) } },
        dismissButton = { TextButton(onClose) { Text(vm.t("cancel")) } }
    )
}

@Composable
private fun HistoryDialog(vm: AppVM, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var newName by remember { mutableStateOf("") }
    val filtered = vm.chats.toList().filter {
        q.isBlank() || it.title.contains(q, true) || it.msgs.any { m -> m.content.contains(q, true) }
    }
    AlertDialog(
        onDismissRequest = onClose, title = { Text(vm.t("history")) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text(vm.t("search")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered) { c ->
                        Row(Modifier.fillMaxWidth().clickable { vm.openChat(c); onClose() }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton({ renaming = c; newName = c.title }) { Icon(Icons.Default.Edit, vm.t("rename"), tint = Gold) }
                            IconButton({ vm.deleteChat(c) }) { Icon(Icons.Default.Delete, vm.t("delete"), tint = Gold) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text(vm.t("ok")) } }
    )
    renaming?.let { c ->
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text(vm.t("rename")) },
            text = { Field("", newName, { newName = it }) },
            confirmButton = { TextButton({ vm.renameChat(c, newName); renaming = null }) { Text(vm.t("ok")) } },
            dismissButton = { TextButton({ renaming = null }) { Text(vm.t("cancel")) } }
        )
    }
}

// ------------------------------------------------------------ library
@Composable
fun LibraryScreen(vm: AppVM) {
    val clip = LocalClipboardManager.current
    var wipe by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header(vm.t("library")) { vm.screen = "chat" }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.providers.toList(), key = { it.id }) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = 0.5f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(when (p.kind) { "termux" -> "T · Termux"; "site" -> "🔗 Site"; else -> "Provider" },
                            color = Gold, fontSize = 12.sp)
                        Field(vm.t("name"), p.name, { v -> vm.updateProvider(p.id) { it.copy(name = v) } })
                        Field(vm.t("url"), p.url, { v -> vm.updateProvider(p.id) { it.copy(url = v) } })
                        Field(vm.t("password"), p.key, { v -> vm.updateProvider(p.id) { it.copy(key = v) } })
                        if (p.kind == "site") Field(vm.t("email"), p.email, { v -> vm.updateProvider(p.id) { it.copy(email = v) } })
                        val ms = vm.models.filter { it.providerId == p.id }
                        if (ms.isNotEmpty()) {
                            Text("${vm.t("models")} (${ms.size})", color = Gold, fontWeight = FontWeight.Bold)
                            SelectionContainer { Text(ms.joinToString("\n") { (if (it.fromTermux) "T " else "") + it.modelId }, fontSize = 13.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ clip.setText(AnnotatedString("${p.name}\n${p.url}\n${p.key}")) }) { Text(vm.t("copy")) }
                            if (p.apiBase.isNotBlank()) OutlinedButton({ vm.refreshProvider(p.id) }) { Text(vm.t("refresh")) }
                            OutlinedButton({ vm.deleteProvider(p.id) }) { Text(vm.t("delete")) }
                        }
                    }
                }
            }
            items(vm.tools.toList(), key = { it.id }) { td ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = 0.5f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (td.kind == "program") "🧰 " + vm.t("program") else "🔧 " + vm.t("llm_tool"), color = Gold, fontSize = 12.sp)
                        Field(vm.t("name"), td.name, { v -> vm.updateTool(td.id) { it.copy(name = v) } })
                        if (td.kind == "llm") {
                            Field(vm.t("tool_desc"), td.description, { v -> vm.updateTool(td.id) { it.copy(description = v) } })
                            Field(vm.t("command"), td.command, { v -> vm.updateTool(td.id) { it.copy(command = v) } })
                            Field(vm.t("password"), td.password, { v -> vm.updateTool(td.id) { it.copy(password = v) } })
                        } else {
                            Field(vm.t("install_cmd"), td.installCmd, { v -> vm.updateTool(td.id) { it.copy(installCmd = v) } })
                            Field(vm.t("run_cmd"), td.command, { v -> vm.updateTool(td.id) { it.copy(command = v) } })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (td.kind == "program") {
                                OutlinedButton({ vm.runProgram(td.installCmd) }) { Text(vm.t("install")) }
                                OutlinedButton({ vm.runProgram(td.command) }) { Text(vm.t("run")) }
                            }
                            OutlinedButton({ clip.setText(AnnotatedString("${td.name}\n${td.command}\n${td.installCmd}")) }) { Text(vm.t("copy")) }
                            OutlinedButton({ vm.deleteTool(td.id) }) { Text(vm.t("delete")) }
                        }
                    }
                }
            }
            item {
                if (vm.providers.isEmpty() && vm.tools.isEmpty()) Text(vm.t("empty"), color = Gold)
                else OutlinedButton({ wipe = true }, Modifier.fillMaxWidth()) { Text(vm.t("delete_all")) }
            }
        }
    }
    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false }, title = { Text(vm.t("delete_all")) }, text = { Text(vm.t("sure")) },
        confirmButton = { TextButton({ vm.deleteAllLibrary(); wipe = false }) { Text(vm.t("delete")) } },
        dismissButton = { TextButton({ wipe = false }) { Text(vm.t("cancel")) } }
    )
}

// ------------------------------------------------------------ add
@Composable
fun AddScreen(vm: AppVM) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("tab_provider", "tab_termux", "tab_tools", "tab_sites")
    Column(Modifier.fillMaxSize()) {
        Header(vm.t("add")) { vm.screen = "chat" }
        TabRow(selectedTabIndex = tab, containerColor = Ink, contentColor = Gold) {
            titles.forEachIndexed { i, k -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(vm.t(k), fontSize = 12.sp, maxLines = 1) }) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> ProviderTab(vm)
                1 -> TermuxTab(vm)
                2 -> ToolsTab(vm)
                else -> SitesTab(vm)
            }
            if (vm.status.isNotBlank()) SelectionContainer { Text(vm.status, color = Gold) }
        }
    }
}

@Composable
private fun ProviderTab(vm: AppVM) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Field(vm.t("name"), name, { name = it })
    Field(vm.t("url"), url, { url = it })
    Field(vm.t("password"), key, { key = it })
    GoldButton(vm.t("test_add"), Modifier.fillMaxWidth()) {
        vm.addProvider(name, url, key, "provider"); name = ""; url = ""; key = ""
    }
}

@Composable
private fun TermuxTab(vm: AppVM) {
    var host by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Text(if (vm.ollamaUp) "🟢 " + vm.t("ollama_up") else "🔴 " + vm.t("ollama_down"), color = Gold, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GoldButton(vm.t("fetch_now")) { vm.fetchNow() }
        OutlinedButton({ vm.startOllama() }) { Text(vm.t("start_ollama")) }
    }
    Text(vm.t("hosts"), color = Gold)
    vm.hosts.toList().forEach { h ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer { Text(h, Modifier.weight(1f)) }
            IconButton({ vm.hosts.remove(h); vm.save() }) { Icon(Icons.Default.Delete, vm.t("delete"), tint = Gold) }
        }
    }
    Field("http://127.0.0.1:11434", host, { host = it })
    OutlinedButton({
        if (host.isNotBlank()) {
            vm.hosts.add(if (host.startsWith("http")) host.trim() else "http://" + host.trim()); vm.save(); host = ""
        }
    }) { Text(vm.t("add_host")) }
    HorizontalDivider(color = Gold.copy(alpha = 0.4f))
    Text(vm.t("add_new"), color = Gold)
    Field(vm.t("name"), name, { name = it })
    Field(vm.t("url"), url, { url = it })
    Field(vm.t("password"), key, { key = it })
    GoldButton(vm.t("test_add"), Modifier.fillMaxWidth()) {
        vm.addProvider(name, url, key, "termux"); name = ""; url = ""; key = ""
    }
}

@Composable
private fun ToolsTab(vm: AppVM) {
    var kind by remember { mutableStateOf("llm") }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var inst by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(kind == "llm", { kind = "llm" }, label = { Text(vm.t("llm_tool")) })
        FilterChip(kind == "program", { kind = "program" }, label = { Text(vm.t("program")) })
    }
    if (kind == "program") {
        Text(vm.t("presets"), color = Gold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple("Ollama", "pkg install -y ollama", "ollama serve"),
                Triple("Python", "pkg install -y python", "python"),
                Triple("Git", "pkg install -y git", "git --version"),
                Triple("Node.js", "pkg install -y nodejs", "node -v")
            ).forEach { (n, i, r) ->
                AssistChip({ name = n; inst = i; cmd = r }, label = { Text(n, fontSize = 12.sp) })
            }
        }
    }
    Field(vm.t("name"), name, { name = it })
    if (kind == "llm") {
        Field(vm.t("tool_desc"), desc, { desc = it })
        Field(vm.t("command"), cmd, { cmd = it })
        Field(vm.t("password"), pw, { pw = it })
    } else {
        Field(vm.t("install_cmd"), inst, { inst = it })
        Field(vm.t("run_cmd"), cmd, { cmd = it })
    }
    GoldButton(vm.t("add_new"), Modifier.fillMaxWidth()) {
        if (name.isNotBlank() || cmd.isNotBlank()) {
            vm.addTool(ToolDef(UUID.randomUUID().toString(), name.ifBlank { cmd.take(20) }, desc, kind, cmd, inst, "", pw))
            name = ""; desc = ""; cmd = ""; inst = ""; pw = ""; vm.status = "✓ " + vm.t("saved")
        }
    }
}

@Composable
private fun SitesTab(vm: AppVM) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    var mb by remember { mutableStateOf("") }
    Text(vm.t("presets"), color = Gold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({
            name = "GitHub"; url = "https://api.github.com"; mb = "https://models.github.ai/inference"
            page = "https://github.com/settings/personal-access-tokens/new"
        }, label = { Text("GitHub") })
        AssistChip({
            name = "Google Gemini"; url = "https://www.googleapis.com"
            mb = "https://generativelanguage.googleapis.com/v1beta/openai"; page = "https://aistudio.google.com/apikey"
        }, label = { Text("Google") })
        AssistChip({
            name = "Supabase"; url = "https://api.supabase.com"; mb = ""
            page = "https://supabase.com/dashboard/account/tokens"
        }, label = { Text("Supabase") })
    }
    Text(vm.t("sites_note"), fontSize = 12.sp)
    Field(vm.t("name"), name, { name = it })
    Field(vm.t("url"), url, { url = it })
    Field(vm.t("email"), email, { email = it })
    Field(vm.t("token"), token, { token = it })
    if (page.isNotBlank()) OutlinedButton({ openTab(ctx, page) }) { Text(vm.t("open_token")) }
    GoldButton(vm.t("save_link"), Modifier.fillMaxWidth()) {
        vm.addProvider(name, url, token, "site", email, page, mb)
        name = ""; url = ""; email = ""; token = ""; page = ""; mb = ""
    }
}

// ------------------------------------------------------------ settings
@Composable
fun SettingsScreen(vm: AppVM) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var perm by remember { mutableStateOf(Termux.hasPermission(ctx)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    Column(Modifier.fillMaxSize()) {
        Header(vm.t("settings")) { vm.screen = "chat" }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SwitchRow(vm.t("auto_start"), vm.autoStart) { vm.autoStart = it; vm.save() }
            SwitchRow(vm.t("speak"), vm.speak) { vm.speak = it; vm.save() }
            SwitchRow(vm.t("tools_on"), vm.toolsOn) { vm.toolsOn = it; vm.save() }
            Text("${vm.t("zoom")}: ${"%.1f".format(vm.zoom)}x", color = Gold)
            Slider(vm.zoom, { vm.zoom = it }, valueRange = 0.6f..3f, onValueChangeFinished = { vm.save() })
            HorizontalDivider(color = Gold.copy(alpha = 0.4f))
            Text(if (Termux.installed(ctx)) "✓ Termux" else "✗ " + vm.t("termux_no"), color = Gold)
            Text(if (perm) "✓ " + vm.t("perm_ok") else "✗ " + vm.t("perm_no"), color = Gold)
            if (!perm) GoldButton(vm.t("grant_perm")) { permL.launch(Termux.PERMISSION) }
            Text(vm.t("termux_help"))
            SelectionContainer { Text(Termux.ALLOW_CMD, color = Gold, fontSize = 13.sp) }
            OutlinedButton({ clip.setText(AnnotatedString(Termux.ALLOW_CMD)) }) { Text(vm.t("copy")) }
        }
    }
}

@Composable
private fun SwitchRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
