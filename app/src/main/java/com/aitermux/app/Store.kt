package com.aitermux

import android.content.Context
import androidx.compose.runtime.*
import org.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class Prov(val id: String, val name: String, val url: String, val key: String, val kind: String, val models: List<String>, val old: List<String> = emptyList(), val onlyFree: Boolean = false)
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
            .filter { it !in fresh && it.removePrefix("models/") !in fresh && p.id + "|" + it !in hidden }.distinct())
    }

    fun init(c: Context) { f = File(c.filesDir, "data.json"); load() }

    private fun provJson(p: Prov) = JSONObject().put("id", p.id).put("name", p.name).put("url", p.url)
        .put("key", p.key).put("kind", p.kind).put("models", JSONArray(p.models)).put("old", JSONArray(p.old)).put("onlyFree", p.onlyFree)
    private fun toolJson(t: Tool) = JSONObject().put("id", t.id).put("name", t.name).put("url", t.url).put("key", t.key)

    fun exportJson(): String = JSONObject()
        .put("provs", JSONArray().also { a -> provs.forEach { a.put(provJson(it)) } })
        .put("tools", JSONArray().also { a -> tools.forEach { a.put(toolJson(it)) } }).toString(2)

    fun save() {
        val o = JSONObject(exportJson())
        o.put("star", star ?: "").put("lang", lang).put("speak", speak).put("auto", autoOllama)
        o.put("hosts", JSONArray(hosts.toList()))
        o.put("hidden", JSONArray(hidden.toList()))
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
                    p.optString("kind", "openai"), (0 until m.length()).map { m.getString(it) }, (0 until od.length()).map { od.getString(it) }, p.optBoolean("onlyFree")))
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

    fun clearLibrary() { provs.clear(); tools.clear(); save() }

    fun classify(url: String): String {
        val u = url.lowercase()
        return when {
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
