package com.aitermux

import android.content.Context
import androidx.compose.runtime.*
import org.json.*
import java.io.File
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.Executors

data class Prov(val id: String, val name: String, val url: String, val key: String, val kind: String, val models: List<String>, val old: List<String> = emptyList(), val onlyFree: Boolean = false, val start: String = "")
data class Tool(val id: String, val name: String, val url: String, val key: String)
data class Msg(val role: String, val text: String)
data class Chat(val id: String, val title: String, val msgs: List<Msg>, val ts: Long)

fun uid() = UUID.randomUUID().toString().take(8)

object Store {
    private lateinit var f: File
    private val io = Executors.newSingleThreadExecutor()
    val provs = mutableStateListOf<Prov>()
    val tools = mutableStateListOf<Tool>()
    val chats = mutableStateListOf<Chat>()
    val hosts = mutableStateListOf("127.0.0.1:11434")
    val tokens = mutableStateMapOf<String, String>()
    val clientIds = mutableStateMapOf<String, String>()
    var lang by mutableStateOf("ar")
    var speak by mutableStateOf(false)
    var autoOllama by mutableStateOf(false)
    var star by mutableStateOf<String?>(null)
    val hidden = mutableStateListOf<String>()
    var current by mutableStateOf(Chat(uid(), "", emptyList(), System.currentTimeMillis()))
    var selKey: String? = null
    var design by mutableStateOf(false)
    var primary by mutableStateOf("")
    var primaryOn by mutableStateOf(false)
    val routeOff = mutableStateListOf<String>()
    val routeForce = mutableStateListOf<String>()
    val routePri = mutableStateMapOf<String, Int>()
    val routeOk = mutableStateMapOf<String, Int>()
    val routeFail = mutableStateMapOf<String, Int>()
    val cool = mutableMapOf<String, Long>()
    var lastRoute by mutableStateOf("")
    var busy by mutableStateOf(false)
    val autoIds = mutableStateListOf<String>()
    val autoOff = mutableStateListOf<String>()
    /** Essential providers are started automatically when the app opens (FreeLLMAPI on :3001 by default). */
    fun essential(p: Prov) = p.id in autoIds || (":3001" in p.url && p.id !in autoOff)
    @Volatile var visible = true
    val okAt = mutableStateMapOf<String, Long>()
    val bad = mutableStateMapOf<String, String>()
    val provState = mutableStateMapOf<String, String>()
    var job: Job? = null
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun hideModel(pid: String, m: String) {
        hidden.add("$pid|$m")
        val i = provs.indexOfFirst { it.id == pid }
        if (i >= 0) provs[i] = provs[i].copy(models = provs[i].models - m, old = provs[i].old - m)
        if (star == "$pid|$m") star = null
        save()
    }

    /** Keeps old models: anything that disappeared from the tool goes to `old` and stays usable/listed. */
    fun merge(p: Prov, all: List<String>): Prov {
        val fresh = all.filter { p.id + "|" + it !in hidden && (!p.onlyFree || it.endsWith(":free")) }
        return p.copy(models = fresh, old = (p.models + p.old)
            .filter { it !in fresh && it.removePrefix("models/") !in fresh && p.id + "|" + it !in hidden && !Api.NON_CHAT.containsMatchIn(it) }.distinct())
    }

    fun init(c: Context) { f = File(c.filesDir, "data.json"); load() }

    private fun provJson(p: Prov) = JSONObject().put("id", p.id).put("name", p.name).put("url", p.url)
        .put("key", p.key).put("kind", p.kind).put("models", JSONArray(p.models)).put("old", JSONArray(p.old)).put("onlyFree", p.onlyFree).put("start", p.start)
    private fun toolJson(t: Tool) = JSONObject().put("id", t.id).put("name", t.name).put("url", t.url).put("key", t.key)

    fun exportJson(): String = JSONObject()
        .put("provs", JSONArray().also { a -> provs.forEach { a.put(provJson(it)) } })
        .put("tools", JSONArray().also { a -> tools.forEach { a.put(toolJson(it)) } }).toString(2)

    fun save() {
        val o = JSONObject(exportJson())
        o.put("star", star ?: "").put("lang", lang).put("speak", speak).put("auto", autoOllama)
        o.put("hosts", JSONArray(hosts.toList()))
        o.put("hidden", JSONArray(hidden.toList()))
        o.put("primary", primary).put("primaryOn", primaryOn)
        o.put("autoIds", JSONArray(autoIds.toList())).put("autoOff", JSONArray(autoOff.toList()))
        o.put("okAt", JSONObject(okAt.toMap())).put("bad", JSONObject(bad.toMap())).put("provState", JSONObject(provState.toMap()))
        o.put("routeOff", JSONArray(routeOff.toList())).put("routeForce", JSONArray(routeForce.toList()))
        o.put("routePri", JSONObject(routePri.toMap())).put("routeOk", JSONObject(routeOk.toMap())).put("routeFail", JSONObject(routeFail.toMap()))
        o.put("tokens", JSONObject(tokens.toMap())).put("clientIds", JSONObject(clientIds.toMap()))
        o.put("chats", JSONArray().also { a ->
            chats.forEach { c ->
                a.put(JSONObject().put("id", c.id).put("title", c.title).put("ts", c.ts).put("msgs",
                    JSONArray().also { m -> c.msgs.forEach { x -> m.put(JSONObject().put("role", x.role).put("text", x.text)) } }))
            }
        })
        val s = o.toString()
        io.execute { runCatching { f.writeText(s) } }
    }

    private fun loadLib(o: JSONObject) {
        o.optJSONArray("provs")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                val m = p.optJSONArray("models") ?: JSONArray()
                val od = p.optJSONArray("old") ?: JSONArray()
                provs.add(Prov(p.optString("id", uid()), p.optString("name"), p.optString("url"), p.optString("key"),
                    p.optString("kind", "openai"), (0 until m.length()).map { m.getString(it) }, (0 until od.length()).map { od.getString(it) }, p.optBoolean("onlyFree"), p.optString("start")))
            }
        }
        o.optJSONArray("tools")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                tools.add(Tool(t.optString("id", uid()), t.optString("name"), t.optString("url"), t.optString("key")))
            }
        }
    }

    private fun load() {
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            star = o.optString("star").ifBlank { null }; lang = o.optString("lang", "ar"); speak = o.optBoolean("speak"); autoOllama = o.optBoolean("auto")
            o.optJSONArray("hosts")?.let { h -> hosts.clear(); for (i in 0 until h.length()) hosts.add(h.getString(i)) }
            loadLib(o)
            o.optJSONArray("hidden")?.let { h -> for (i in 0 until h.length()) hidden.add(h.getString(i)) }
            primary = o.optString("primary"); primaryOn = o.optBoolean("primaryOn")
            o.optJSONArray("autoIds")?.let { a -> for (i in 0 until a.length()) autoIds.add(a.getString(i)) }
            o.optJSONArray("autoOff")?.let { a -> for (i in 0 until a.length()) autoOff.add(a.getString(i)) }
            o.optJSONObject("okAt")?.let { m -> for (k in m.keys()) okAt[k] = m.getLong(k) }
            o.optJSONObject("bad")?.let { m -> for (k in m.keys()) bad[k] = m.getString(k) }
            o.optJSONObject("provState")?.let { m -> for (k in m.keys()) provState[k] = m.getString(k) }
            o.optJSONArray("routeOff")?.let { a -> for (i in 0 until a.length()) routeOff.add(a.getString(i)) }
            o.optJSONArray("routeForce")?.let { a -> for (i in 0 until a.length()) routeForce.add(a.getString(i)) }
            o.optJSONObject("routePri")?.let { m -> for (k in m.keys()) routePri[k] = m.getInt(k) }
            o.optJSONObject("routeOk")?.let { m -> for (k in m.keys()) routeOk[k] = m.getInt(k) }
            o.optJSONObject("routeFail")?.let { m -> for (k in m.keys()) routeFail[k] = m.getInt(k) }
            o.optJSONObject("tokens")?.let { t -> for (k in t.keys()) tokens[k] = t.getString(k) }
            o.optJSONObject("clientIds")?.let { t -> for (k in t.keys()) clientIds[k] = t.getString(k) }
            o.optJSONArray("chats")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.getJSONObject(i); val m = c.getJSONArray("msgs")
                    chats.add(Chat(c.getString("id"), c.getString("title"),
                        (0 until m.length()).map { j -> Msg(m.getJSONObject(j).getString("role"), m.getJSONObject(j).getString("text")) },
                        c.getLong("ts")))
                }
            }
        }
    }

    fun upsert(c: Chat) {
        val i = chats.indexOfFirst { it.id == c.id }
        if (i >= 0) chats[i] = c else chats.add(c)
        save()
    }

    /** Removes junk entries and merges duplicate providers (same URL + same key). Returns how many entries were removed. */
    fun dedupe(): Int {
        val before = provs.size + tools.size
        provs.removeAll { p ->
            val u = p.url.lowercase().trimEnd('/')
            (p.kind == "termux" && "127.0.0.1" !in u && "localhost" !in u) || u.endsWith("/models") || "freepi.ai" in u
        }
        fun localU(u: String) = "127.0.0.1" in u || "localhost" in u
        val groups = provs.groupBy { p ->
            val u = p.url.trim().trimEnd('/').lowercase()
            if (localU(u)) "L|" + u.substringAfter("://").substringBefore('/').replace("localhost", "127.0.0.1") else u + "|" + p.key
        }
        val merged = groups.values.map { g ->
            if (g.size == 1) g[0] else {
                val b = g.maxWithOrNull(compareBy<Prov>({ if (it.key.isNotBlank()) 1 else 0 }, { it.models.size }))!!
                val ms = g.flatMap { it.models }.distinct()
                for (o in g) if (o.id != b.id) {
                    for (hi in hidden.indices) { val h = hidden[hi]; if (h.startsWith(o.id + "|")) hidden[hi] = b.id + h.substring(o.id.length) }
                    if (star?.startsWith(o.id + "|") == true) star = b.id + star!!.substring(o.id.length)
                }
                b.copy(url = b.url.replace("localhost", "127.0.0.1"), models = ms, old = g.flatMap { it.old }.distinct().filter { it !in ms }, start = g.map { it.start }.firstOrNull { it.isNotBlank() } ?: "")
            }
        }
        provs.clear(); provs.addAll(merged)
        tools.removeAll { x -> x.name.contains('\n') || !(x.url.startsWith("http") || x.url.startsWith("termux:")) || (x.url.startsWith("termux:") && (x.name.startsWith("lib") || x.name in BASE_PKGS)) }
        val seen = HashSet<String>()
        tools.removeAll { x -> !seen.add(x.url + "|" + x.name) }
        save()
        return before - (provs.size + tools.size)
    }

    fun clearLibrary() { provs.clear(); tools.clear(); save() }

    fun classify(url: String): String {
        val u = url.lowercase()
        return when {
            "dify" in u -> "dify"
            "11434" in u || "ollama" in u -> "ollama"
            listOf("/v1", "openai", "openrouter", "groq", "together", "mistral", "deepseek", "anthropic", "generativelanguage", "/chat/completions").any { it in u } -> "openai"
            else -> "tool"
        }
    }

    fun hostOf(url: String) = url.substringAfter("://").substringBefore("/")

    /** Imports JSON (ours or any shape with url/key/password fields) or plain text lines. Returns number of items added. */
    fun importText(text: String): Int {
        var n = 0
        fun add(name: String, url: String, key: String) {
            val k = classify(url)
            if (k == "tool") tools.add(Tool(uid(), name, url, key))
            else provs.add(Prov(uid(), name, url, key, k, emptyList()))
            n++
        }
        val s = text.trim()
        val root: Any? = runCatching { if (s.startsWith("[")) JSONArray(s) else JSONObject(s) }.getOrNull()
        if (root is JSONObject && (root.has("provs") || root.has("tools"))) {
            val b = provs.size + tools.size; loadLib(root); n = provs.size + tools.size - b
        } else if (root != null) {
            fun walk(x: Any?) {
                when (x) {
                    is JSONArray -> for (i in 0 until x.length()) walk(x.get(i))
                    is JSONObject -> {
                        val url = listOf("url", "base_url", "baseUrl", "link", "endpoint").map { x.optString(it) }.firstOrNull { it.startsWith("http") } ?: ""
                        if (url.isNotEmpty()) {
                            val key = listOf("key", "api_key", "apiKey", "password", "pass", "token", "secret").map { x.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
                            add(x.optString("name").ifBlank { hostOf(url) }, url, key)
                        } else for (k in x.keys()) walk(x.get(k))
                    }
                }
            }
            walk(root)
        } else {
            text.lines().forEach { l ->
                val m = Regex("https?://[^\\s,;|\"']+").find(l) ?: return@forEach
                val rest = l.removeRange(m.range).split(Regex("[\\s,;|:=]+")).filter { it.isNotBlank() && it.length > 2 }
                add(hostOf(m.value), m.value, rest.lastOrNull() ?: "")
            }
        }
        save()
        return n
    }
}
