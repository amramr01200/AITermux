package com.aitermux.app

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Api {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private val quick: OkHttpClient = http.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
    private val JSON_MT = "application/json".toMediaType()

    /** يوحّد الرابط: يزيل /models و /chat/completions ويضيف /v1 إذا لم يوجد مسار */
    fun base(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.startsWith("http")) u = "http://$u"
        for (s in listOf("/chat/completions", "/models")) if (u.endsWith(s)) u = u.removeSuffix(s)
        val hu = u.toHttpUrlOrNull()
        if (hu != null && hu.encodedPath == "/") u += "/v1"
        return u
    }

    private fun req(url: String, key: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (key.isNotBlank()) b.header("Authorization", "Bearer $key")
        return b
    }

    private val visionHints = listOf(
        "vision", "llava", "-vl", "vl-", "gpt-4o", "gpt-4.1", "gpt-5", "gemini", "claude", "pixtral",
        "gemma3", "minicpm-v", "moondream", "bakllava", "llama-4", "llama4", "qwen2.5vl", "mistral-small3"
    )

    private fun parseItems(body: String): JSONArray {
        val t = body.trim()
        if (t.startsWith("[")) return JSONArray(t)
        val o = JSONObject(t)
        return o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
    }

    fun fetchModels(p: Provider, fast: Boolean = false): List<ModelEntry> {
        val b = base(p.apiBase)
        val urls = mutableListOf<String>()
        if (b.contains("models.github.ai")) urls.add("https://models.github.ai/catalog/models")
        urls.add("$b/models")
        if (p.kind == "termux") urls.add(b.removeSuffix("/v1") + "/api/tags")
        var last: Exception? = null
        for (u in urls) {
            try {
                val c = if (fast) quick else http
                c.newCall(req(u, p.key).get().build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(200)}")
                    val arr = parseItems(body)
                    val out = mutableListOf<ModelEntry>()
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i)
                        val id = if (it == null) arr.optString(i) else
                            it.optString("id").ifBlank { it.optString("name") }.ifBlank { it.optString("model") }
                        if (id.isBlank()) continue
                        val clean = id.removePrefix("models/")
                        val created = it?.optLong("created", 0L) ?: 0L
                        var vision = visionHints.any { h -> clean.lowercase().contains(h) }
                        val mod = it?.optJSONArray("supported_input_modalities")
                        if (mod != null) for (k in 0 until mod.length()) if (mod.optString(k) == "image") vision = true
                        val cap = it?.optJSONArray("capabilities")
                        if (cap != null) for (k in 0 until cap.length()) if (cap.optString(k) == "vision") vision = true
                        out.add(ModelEntry(p.id, clean, created, vision, p.kind == "termux"))
                    }
                    if (out.isNotEmpty()) return out
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No models found")
    }

    fun messageJson(m: Msg): JSONObject {
        val o = JSONObject().put("role", m.role)
        val sb = StringBuilder(m.content)
        val imgs = mutableListOf<String>()
        for (a in m.attachments) {
            if (a.text != null) sb.append("\n\n[").append(a.name).append("]\n").append(a.text)
            imgs += a.images
        }
        if (imgs.isEmpty()) {
            o.put("content", sb.toString())
        } else {
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", sb.toString()))
            imgs.forEach {
                parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", it)))
            }
            o.put("content", parts)
        }
        return o
    }

    fun streamChat(p: Provider, model: String, history: JSONArray, onCall: (Call) -> Unit, onDelta: (String) -> Unit) {
        val body = JSONObject().put("model", model).put("messages", history).put("stream", true)
        val rq = req(base(p.apiBase) + "/chat/completions", p.key)
            .post(body.toString().toRequestBody(JSON_MT)).build()
        val call = http.newCall(rq)
        onCall(call)
        call.execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string()?.take(300)}")
            val src = r.body!!.source()
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.removePrefix("data:").trim()
                if (d == "[DONE]") break
                if (d.isEmpty()) continue
                try {
                    val ch = JSONObject(d).optJSONArray("choices")
                    if (ch != null && ch.length() > 0) {
                        val delta = ch.getJSONObject(0).optJSONObject("delta")
                        val c = delta?.optString("content", "")
                        if (!c.isNullOrEmpty() && c != "null") onDelta(c)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun chatOnce(p: Provider, model: String, history: JSONArray, tools: JSONArray, onCall: (Call) -> Unit): JSONObject {
        val body = JSONObject().put("model", model).put("messages", history).put("tools", tools).put("stream", false)
        val rq = req(base(p.apiBase) + "/chat/completions", p.key)
            .post(body.toString().toRequestBody(JSON_MT)).build()
        val call = http.newCall(rq)
        onCall(call)
        call.execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${s.take(300)}")
            return JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }
    }

    fun raw(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        val m = method.uppercase()
        val rb = if (m == "GET" || m == "HEAD") null else (body ?: "").toRequestBody(JSON_MT)
        b.method(m, rb)
        http.newCall(b.build()).execute().use { r ->
            return "HTTP ${r.code}\n" + (r.body?.string().orEmpty().take(6000))
        }
    }

    fun pull(host: String, name: String): String {
        val c = http.newBuilder().readTimeout(60, TimeUnit.MINUTES).build()
        val body = JSONObject().put("name", name).put("stream", false)
        val rq = Request.Builder().url(host.trimEnd('/') + "/api/pull")
            .post(body.toString().toRequestBody(JSON_MT)).build()
        c.newCall(rq).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${s.take(200)}")
            return s.take(200)
        }
    }
}
