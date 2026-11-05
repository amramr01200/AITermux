package com.aitermux.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AppVM(private val app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    val providers = mutableStateListOf<Provider>()
    val models = mutableStateListOf<ModelEntry>()
    val tools = mutableStateListOf<ToolDef>()
    val chats = mutableStateListOf<Chat>()
    val hosts = mutableStateListOf("http://127.0.0.1:11434")
    val pending = mutableStateListOf<Attachment>()

    var current by mutableStateOf<Chat?>(null)
    var selected by mutableStateOf<String?>(null)
    var lang by mutableStateOf("ar")
    var autoStart by mutableStateOf(false)
    var speak by mutableStateOf(false)
    var toolsOn by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var ollamaUp by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var screen by mutableStateOf("chat")
    var confirm by mutableStateOf<ConfirmReq?>(null)
    var speakRequest by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var call: Call? = null
    private var startedOnce = false

    fun t(k: String): String = (if (lang == "ar") Str.ar else Str.en)[k] ?: k

    init {
        load()
        startWatcher()
    }

    // ---------- storage ----------
    private fun load() {
        runCatching {
            store.read("library.json")?.let {
                val o = JSONObject(it)
                providers.addAll(J.parseProviders(o.optJSONArray("providers") ?: JSONArray()))
                models.addAll(J.parseModels(o.optJSONArray("models") ?: JSONArray()))
                tools.addAll(J.parseTools(o.optJSONArray("tools") ?: JSONArray()))
            }
        }
        runCatching {
            store.read("chats.json")?.let { chats.addAll(J.parseChats(JSONArray(it))) }
        }
        runCatching {
            store.read("settings.json")?.let {
                val o = JSONObject(it)
                lang = o.optString("lang", "ar")
                autoStart = o.optBoolean("autoStart")
                speak = o.optBoolean("speak")
                toolsOn = o.optBoolean("toolsOn")
                zoom = o.optDouble("zoom", 1.0).toFloat()
                selected = o.optString("selected").ifBlank { null }
                val h = o.optJSONArray("hosts")
                if (h != null && h.length() > 0) {
                    hosts.clear()
                    for (i in 0 until h.length()) hosts.add(h.getString(i))
                }
            }
        }
    }

    @Synchronized
    fun save() {
        runCatching {
            store.write(
                "library.json",
                JSONObject().put("providers", J.providers(providers.toList()))
                    .put("models", J.models(models.toList())).put("tools", J.tools(tools.toList())).toString()
            )
            store.write("chats.json", J.chats(chats.toList()).toString())
            store.write(
                "settings.json",
                JSONObject().put("lang", lang).put("autoStart", autoStart).put("speak", speak)
                    .put("toolsOn", toolsOn).put("zoom", zoom.toDouble()).put("selected", selected ?: "")
                    .put("hosts", JSONArray(hosts.toList())).toString()
            )
        }
    }

    // ---------- models ----------
    fun sortedModels(): List<ModelEntry> =
        models.toList().sortedWith(compareByDescending<ModelEntry> { it.created }.thenBy { it.modelId })

    fun providerName(id: String) = providers.find { it.id == id }?.name ?: ""

    private fun upsert(p: Provider, ms: List<ModelEntry>) {
        val i = providers.indexOfFirst { it.id == p.id }
        if (i < 0) providers.add(p) else if (p.kind != "termux") providers[i] = p
        val old = models.filter { it.providerId == p.id }.map { it.modelId }.toSet()
        if (i < 0 || old != ms.map { it.modelId }.toSet()) {
            models.removeAll { it.providerId == p.id }
            models.addAll(ms)
            if (selected == null && ms.isNotEmpty()) selected = ms.first().providerId + "|" + ms.first().modelId
            save()
        }
    }

    fun addProvider(name: String, url: String, key: String, kind: String = "provider",
                    email: String = "", page: String = "", mb: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val host = url.ifBlank { mb }
            val nm = name.ifBlank { host.removePrefix("https://").removePrefix("http://").substringBefore("/") }
            val p = Provider(UUID.randomUUID().toString(), nm, url.trim(), key.trim(), kind, email.trim(), page, mb)
            if (kind == "site" && mb.isBlank()) {
                providers.add(p); save(); status = "✓ ${p.name}"
                return@launch
            }
            status = t("testing")
            try {
                val ms = Api.fetchModels(p)
                upsert(p, ms)
                status = "✓ ${p.name}: ${ms.size}"
            } catch (e: Exception) {
                status = "⚠ ${e.message}"
            }
        }
    }

    fun updateProvider(id: String, f: (Provider) -> Provider) {
        val i = providers.indexOfFirst { it.id == id }
        if (i >= 0) { providers[i] = f(providers[i]); save() }
    }

    fun refreshProvider(id: String) {
        val p = providers.find { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ms = Api.fetchModels(p)
                models.removeAll { it.providerId == id }; models.addAll(ms); save()
                status = "✓ ${p.name}: ${ms.size}"
            } catch (e: Exception) { status = "⚠ ${e.message}" }
        }
    }

    fun deleteProvider(id: String) {
        providers.removeAll { it.id == id }
        models.removeAll { it.providerId == id }
        if (selected?.startsWith("$id|") == true) selected = null
        save()
    }

    fun deleteAllLibrary() {
        providers.clear(); models.clear(); tools.clear(); selected = null; save()
    }

    // ---------- tools ----------
    fun addTool(t: ToolDef) { tools.add(t); save() }
    fun updateTool(id: String, f: (ToolDef) -> ToolDef) {
        val i = tools.indexOfFirst { it.id == id }
        if (i >= 0) { tools[i] = f(tools[i]); save() }
    }
    fun deleteTool(id: String) { tools.removeAll { it.id == id }; save() }

    fun runProgram(cmd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            status = "…"
            val r = Termux.run(app, cmd, wait = true, timeoutMs = 600_000)
            status = r.take(300)
        }
    }

    // ---------- Termux / Ollama ----------
    private fun startWatcher() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(1500)
            while (true) {
                probeAll()
                delay(10_000)
            }
        }
    }

    suspend fun probeAll(): Boolean {
        var found = false
        for (h in hosts.toList()) {
            val p = Provider("termux-${h.hashCode()}", "Ollama · $h", h, "", "termux")
            try {
                val ms = Api.fetchModels(p, fast = true)
                upsert(p, ms)
                found = true
            } catch (_: Exception) {
            }
        }
        ollamaUp = found
        if (!found && autoStart && !startedOnce && Termux.installed(app) && Termux.hasPermission(app)) {
            startedOnce = true
            status = t("starting")
            Termux.run(app, Termux.START_OLLAMA, wait = false)
            delay(6000)
            return probeAll()
        }
        return found
    }

    fun fetchNow() { viewModelScope.launch(Dispatchers.IO) { status = t("testing"); val ok = probeAll(); status = if (ok) "✓ Ollama" else "⚠ ${t("ollama_down")}" } }

    fun startOllama() {
        viewModelScope.launch(Dispatchers.IO) {
            status = t("starting")
            val r = Termux.run(app, Termux.START_OLLAMA, wait = false)
            if (r.startsWith("ERROR")) { status = "⚠ $r"; return@launch }
            delay(6000)
            probeAll()
            status = if (ollamaUp) "✓ Ollama" else "⚠ ${t("ollama_down")}"
        }
    }

    fun pull(name: String) {
        val h = hosts.firstOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            status = "⬇ $name …"
            try { Api.pull(h, name); status = "✓ $name"; probeAll() } catch (e: Exception) { status = "⚠ ${e.message}" }
        }
    }

    // ---------- chats ----------
    fun newChat() { current = null }
    fun openChat(c: Chat) { current = c }
    fun deleteChat(c: Chat) { chats.remove(c); if (current === c) current = null; save() }
    fun renameChat(c: Chat, title: String) { c.title = title; save() }

    fun attach(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) { uris.forEach { pending.add(Files.read(app, it)) } }
    }

    fun stop() { call?.cancel(); job?.cancel(); busy = false }

    fun send(text: String) {
        if (busy || (text.isBlank() && pending.isEmpty())) return
        val sel = selected ?: run { status = t("no_model"); return }
        val pid = sel.substringBefore("|")
        val mid = sel.substringAfter("|")
        val p = providers.find { it.id == pid } ?: run { status = t("no_model"); return }
        val chat = current ?: Chat(UUID.randomUUID().toString(), t("new_chat"), System.currentTimeMillis())
            .also { current = it; chats.add(0, it) }
        chat.msgs.add(Msg("user", text, pending.toList()))
        pending.clear()
        if (chat.msgs.size == 1) chat.title = text.take(40).ifBlank { chat.msgs[0].attachments.firstOrNull()?.name ?: "…" }
        val reply = Msg("assistant", "")
        chat.msgs.add(reply)
        busy = true
        status = ""
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = JSONArray()
                chat.msgs.toList().dropLast(1).forEach { history.put(Api.messageJson(it)) }
                if (toolsOn) {
                    try {
                        runWithTools(p, mid, history, reply)
                    } catch (e: java.io.IOException) {
                        if (reply.content.isBlank() && e.message?.contains("tool", true) == true) {
                            Api.streamChat(p, mid, history, { call = it }) { reply.content += it }
                        } else throw e
                    }
                } else {
                    Api.streamChat(p, mid, history, { call = it }) { reply.content += it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (call?.isCanceled() != true) reply.content += "\n⚠ ${e.message}"
            } finally {
                busy = false
                chat.updated = System.currentTimeMillis()
                save()
                if (speak && reply.content.isNotBlank()) speakRequest = reply.content
            }
        }
    }

    // ---------- tool calling ----------
    private fun sanitize(s: String) = "tool_" + s.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun toolSpecs(): JSONArray {
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        fun fn(name: String, desc: String, props: JSONObject, req: List<String>) =
            JSONObject().put("type", "function").put(
                "function", JSONObject().put("name", name).put("description", desc).put(
                    "parameters", JSONObject().put("type", "object").put("properties", props)
                        .put("required", JSONArray(req))
                )
            )
        val a = JSONArray()
        a.put(fn("run_termux", "Run a shell command in Termux on the user's phone and return its output.",
            JSONObject().put("command", s("shell command")), listOf("command")))
        a.put(fn("http_get", "HTTP GET a URL and return the response body.",
            JSONObject().put("url", s("full URL")), listOf("url")))
        val sites = providers.filter { it.kind == "site" }.joinToString { it.name }
        if (sites.isNotBlank()) {
            a.put(fn("site_request", "Call a REST API of a linked site using its saved token. Linked sites: $sites",
                JSONObject().put("site", s("site name")).put("method", s("GET/POST/PUT/PATCH/DELETE"))
                    .put("path", s("path starting with /")).put("body", s("JSON body or empty")),
                listOf("site", "method", "path")))
        }
        tools.filter { it.kind == "llm" }.forEach {
            a.put(fn(sanitize(it.name), it.description.ifBlank { it.name },
                JSONObject().put("input", s("input")), emptyList()))
        }
        return a
    }

    private suspend fun ask(text: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        confirm = ConfirmReq(text, d)
        val r = d.await()
        confirm = null
        return r
    }

    private suspend fun execTool(name: String, args: JSONObject): String {
        try {
            when (name) {
                "run_termux" -> {
                    val c = args.optString("command")
                    if (!ask("Termux:\n$c")) return "denied by user"
                    return Termux.run(app, c)
                }
                "http_get" -> return Api.raw("GET", args.optString("url"), emptyMap(), null)
                "site_request" -> {
                    val site = providers.find { it.kind == "site" && it.name.equals(args.optString("site"), true) }
                        ?: return "unknown site"
                    val method = args.optString("method", "GET").uppercase()
                    if (method != "GET" && !ask("$method ${site.name}${args.optString("path")}\n${args.optString("body")}"))
                        return "denied by user"
                    val h = mutableMapOf("Accept" to "application/json")
                    if (site.key.isNotBlank()) { h["Authorization"] = "Bearer ${site.key}"; h["apikey"] = site.key }
                    if (site.url.contains("github")) h["X-GitHub-Api-Version"] = "2022-11-28"
                    return Api.raw(method, site.url.trimEnd('/') + args.optString("path"), h, args.optString("body"))
                }
            }
            val td = tools.find { it.kind == "llm" && sanitize(it.name) == name } ?: return "unknown tool"
            val input = args.optString("input")
            val cmd = td.command.replace("{input}", input)
            if (cmd.startsWith("http")) {
                val h = if (td.password.isNotBlank()) mapOf("Authorization" to "Bearer ${td.password}") else emptyMap()
                return Api.raw("GET", cmd, h, null)
            }
            if (!ask("${td.name}:\n$cmd")) return "denied by user"
            return Termux.run(app, cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        }
    }

    private suspend fun runWithTools(p: Provider, model: String, history: JSONArray, reply: Msg) {
        val specs = toolSpecs()
        var rounds = 0
        while (rounds++ < 6) {
            val msg = Api.chatOnce(p, model, history, specs) { call = it }
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                if (!msg.isNull("content")) reply.content += msg.getString("content")
                return
            }
            history.put(msg)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val a = fn.opt("arguments")
                val args = when (a) {
                    is JSONObject -> a
                    else -> try { JSONObject(a?.toString()?.ifBlank { "{}" } ?: "{}") } catch (_: Exception) { JSONObject() }
                }
                val name = fn.getString("name")
                reply.content += "🔧 $name\n"
                val result = execTool(name, args)
                history.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", result))
            }
        }
    }
}
