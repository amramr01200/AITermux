package com.aitermux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.content.Intent
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.concurrent.TimeUnit

object Api {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    private fun builder(url: String, key: String) =
        Request.Builder().url(url).apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }

    fun get(url: String, key: String = ""): String =
        http.newCall(builder(url, key).build()).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}"); it.body!!.string()
        }

    fun post(url: String, key: String, body: String): String =
        http.newCall(builder(url, key).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use {
            val s = it.body!!.string(); if (!it.isSuccessful) error("HTTP ${it.code}: ${errMsg(s)}"); s
        }

    private fun errMsg(s: String): String = runCatching {
        val j = if (s.trim().startsWith("[")) JSONArray(s).getJSONObject(0) else JSONObject(s)
        j.getJSONObject("error").getString("message")
    }.getOrDefault(s.take(200))

    private val THINK = Regex("(?s)<think>.*?</think>\\s*")
    private fun clean(m: Msg) = if (m.role == "assistant") m.text.replace(THINK, "") else m.text
    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)

    private fun v1(b: String) = if ("/v1" in b) b else "$b/v1"
    private fun isOllama(p: Prov) = p.kind == "ollama" || p.kind == "termux"

    /** Newest first. */
    fun models(p: Prov): List<String> {
        if (p.kind == "dify") return listOf("Dify app")
        if (p.kind == "claudecode") return Store.provs.filter { it.kind == "termux" || it.kind == "ollama" }.flatMap { it.models }.distinct()
        if (p.kind == "opencode") return p.models
        if ("models.github.ai" in p.url) {
            val a = JSONArray(get("https://models.github.ai/catalog/models", p.key))
            return (0 until a.length()).map { a.getJSONObject(it).getString("id") }
        }
        val b = p.url.trim().trimEnd('/').let { if (isOllama(p)) it.removeSuffix("/v1") else it }
        return if (isOllama(p)) {
            val a = JSONObject(get("$b/api/tags", p.key)).getJSONArray("models")
            (0 until a.length()).map { a.getJSONObject(it) }.sortedByDescending { it.optString("modified_at") }.map { it.getString("name") }
        } else {
            val a = JSONObject(get("${v1(b)}/models", p.key)).getJSONArray("data")
            (0 until a.length()).map { a.getJSONObject(it) }.sortedWith(compareByDescending<JSONObject> { it.optLong("created") }.thenByDescending { it.getString("id") }).map { it.getString("id").removePrefix("models/") }
        }
    }

    /** Dify Chatbot app (blocking mode): POST {base}/chat-messages with the app API key. */
    private fun difyChat(p: Prov, h: List<Msg>): String {
        val b = p.url.trim().trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
        val msgs = h.filter { it.role != "system" }.takeLast(7)
        val query = if (msgs.size <= 1) (msgs.lastOrNull()?.text ?: "")
        else "Conversation so far:\n" + msgs.dropLast(1).joinToString("\n") { (if (it.role == "user") "User: " else "Assistant: ") + clean(it) } + "\n\nAnswer this message:\n" + msgs.last().text
        val body = JSONObject().put("inputs", JSONObject()).put("query", query).put("response_mode", "blocking").put("user", "aitermux")
        return str(JSONObject(post("$b/chat-messages", p.key, body.toString())), "answer").ifBlank { "(empty reply)" }
    }

    fun chat(p: Prov, model: String, h: List<Msg>, images: List<String>): String {
        if (p.kind == "dify") return difyChat(p, h)
        val b = p.url.trim().trimEnd('/').let { if (isOllama(p)) it.removeSuffix("/v1") else it }
        val last = h.size - 1
        if (isOllama(p)) {
            val ms = JSONArray()
            h.forEachIndexed { i, m ->
                val o = JSONObject().put("role", m.role).put("content", clean(m))
                if (i == last && images.isNotEmpty()) o.put("images", JSONArray(images))
                ms.put(o)
            }
            val r = post("$b/api/chat", p.key, JSONObject().put("model", model).put("stream", false).put("messages", ms).toString())
            return JSONObject(r).getJSONObject("message").getString("content")
        }
        val ms = JSONArray()
        h.forEachIndexed { i, m ->
            val o = JSONObject().put("role", m.role)
            if (i == last && images.isNotEmpty()) {
                val parts = JSONArray().put(JSONObject().put("type", "text").put("text", clean(m)))
                images.forEach { parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it"))) }
                o.put("content", parts)
            } else o.put("content", clean(m))
            ms.put(o)
        }
        val r = post((if ("models.github.ai" in b) b else v1(b)) + "/chat/completions", p.key, JSONObject().put("model", model).put("messages", ms).toString())
        val msg = JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val content = str(msg, "content")
        val reasoning = str(msg, "reasoning_content").ifBlank { str(msg, "reasoning") }
        return if (content.isBlank() && reasoning.isNotBlank()) reasoning else content.ifBlank { "(empty reply)" }
    }

    /** Starts Ollama through Termux on demand, so Termux need not be opened first. */
    fun aliveUrl(u: String) = runCatching { http.newCall(Request.Builder().url(u).build()).execute().use { true } }.getOrDefault(false)

    /** Starts the tool in the background through Termux (using the saved start command) if it is not answering. */
    fun ensure(c: Context, p: Prov) {
        if ("127.0.0.1" !in p.url && "localhost" !in p.url) return
        val cmd = p.start.ifBlank {
            when {
                ":20128" in p.url -> Termux.START_OMNI
                ":3001" in p.url -> Termux.START_FREE
                p.kind == "termux" || p.kind == "ollama" -> Termux.START
                else -> ""
            }
        }
        if (cmd.isBlank()) return
        val base = p.url.trim().trimEnd('/')
        val probe = if (p.kind == "termux" || p.kind == "ollama") base.removeSuffix("/v1") + "/api/tags" else "$base/models"
        if (aliveUrl(probe)) return
        Termux.run(c, cmd)
        repeat(if (":3001" in p.url) 90 else 45) { if (aliveUrl(probe)) return; Thread.sleep(1000) }
    }

    fun alive(host: String) = runCatching { get("http://$host/api/tags"); true }.getOrDefault(false)
}

object Termux {
    const val START_OMNI = "nohup omniroute > ~/omniroute.log 2>&1 &"
    const val START_FREE = "cd ~/freellmapi && nohup npm run dev > ~/freellmapi.log 2>&1 &"
    const val START = "pgrep -x ollama >/dev/null || (nohup ollama serve >/dev/null 2>&1 &)"

    /** Runs a command in Termux and reports (exitCode, output) back. Returns false if Termux can't be reached. */
    fun exec(c: Context, cmd: String, done: (Int, String) -> Unit): Boolean = runCatching {
        val action = "com.aitermux.RESULT." + uid()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                runCatching { c.unregisterReceiver(this) }
                val b = i.getBundleExtra("result")
                val out = listOf("stdout", "stderr", "errmsg").joinToString("\n") { b?.getString(it) ?: "" }.trim()
                done(b?.getInt("exitCode", -1) ?: -1, out)
            }
        }
        ContextCompat.registerReceiver(c, rcv, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(c, 0, Intent(action).setPackage(c.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val i = Intent().setClassName("com.termux", "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        c.startForegroundService(i); true
    }.getOrDefault(false)

    suspend fun execWait(c: Context, cmd: String, timeoutMs: Long = 60000): Pair<Int, String>? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Pair<Int, String>?> { cont ->
                val ok = exec(c, cmd) { code, out -> if (cont.isActive) cont.resume(code to out) }
                if (!ok && cont.isActive) cont.resume(null)
            }
        }

    /** Wraps a command so it runs inside Termux's Ubuntu (proot). */
    fun ub(cmd: String) = "proot-distro login ubuntu -- bash -c '" + cmd.replace("'", "'\\''") + "'"

    private fun promptB64(h: List<Msg>): String {
        val sb = StringBuilder("Continue this conversation and answer the last User message.\n\n")
        h.filter { it.role != "system" }.takeLast(8).forEach { sb.append(if (it.role == "user") "User: " else "Assistant: ").append(it.text).append("\n\n") }
        return android.util.Base64.encodeToString(sb.toString().takeLast(6000).toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun opencodeCmd(model: String, h: List<Msg>): String {
        val m = model.filter { it.isLetterOrDigit() || it in ".:_-/" }
        return ub("export PATH=/usr/local/bin:/root/.local/bin:\$PATH; mkdir -p ~/project; cd ~/project; opencode run -m $m \"\$(echo ${promptB64(h)} | base64 -d)\" 2>&1")
    }

    /** Command that runs Claude Code (inside Termux's Ubuntu) against local Ollama, non-interactively. */
    fun claudeCmd(model: String, h: List<Msg>): String {
        val m = model.filter { it.isLetterOrDigit() || it in ".:_-/" }
        val sb = StringBuilder("Continue this conversation and answer the last User message.\n\n")
        h.filter { it.role != "system" }.takeLast(8).forEach { sb.append(if (it.role == "user") "User: " else "Assistant: ").append(it.text).append("\n\n") }
        val prompt = sb.toString().takeLast(6000)
        val b64 = android.util.Base64.encodeToString(prompt.toByteArray(), android.util.Base64.NO_WRAP)
        return "proot-distro login ubuntu -- bash -c 'export PATH=/root/.local/bin:\$PATH; mkdir -p ~/project; cd ~/project; " +
            "ANTHROPIC_BASE_URL=http://127.0.0.1:11434 ANTHROPIC_AUTH_TOKEN=ollama ANTHROPIC_API_KEY= claude --model $m -p \"\$(echo $b64 | base64 -d)\" 2>&1'"
    }

    fun run(c: Context, cmd: String): Boolean = runCatching {
        val i = Intent().setClassName("com.termux", "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        c.startForegroundService(i); true
    }.getOrDefault(false)
}
