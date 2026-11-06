package com.aitermux

import android.content.Context
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
            val s = it.body!!.string(); if (!it.isSuccessful) error("HTTP ${it.code}: ${s.take(200)}"); s
        }

    private fun v1(b: String) = if ("/v1" in b) b else "$b/v1"
    private fun isOllama(p: Prov) = p.kind == "ollama" || p.kind == "termux"

    /** Newest first. */
    fun models(p: Prov): List<String> {
        val b = p.url.trim().trimEnd('/')
        return if (isOllama(p)) {
            val a = JSONObject(get("$b/api/tags", p.key)).getJSONArray("models")
            (0 until a.length()).map { a.getJSONObject(it) }.sortedByDescending { it.optString("modified_at") }.map { it.getString("name") }
        } else {
            val a = JSONObject(get("${v1(b)}/models", p.key)).getJSONArray("data")
            (0 until a.length()).map { a.getJSONObject(it) }.sortedByDescending { it.optLong("created") }.map { it.getString("id") }
        }
    }

    fun chat(p: Prov, model: String, h: List<Msg>, images: List<String>): String {
        val b = p.url.trim().trimEnd('/')
        val last = h.size - 1
        if (isOllama(p)) {
            val ms = JSONArray()
            h.forEachIndexed { i, m ->
                val o = JSONObject().put("role", m.role).put("content", m.text)
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
                val parts = JSONArray().put(JSONObject().put("type", "text").put("text", m.text))
                images.forEach { parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it"))) }
                o.put("content", parts)
            } else o.put("content", m.text)
            ms.put(o)
        }
        val r = post("${v1(b)}/chat/completions", p.key, JSONObject().put("model", model).put("messages", ms).toString())
        return JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    /** Starts Ollama through Termux on demand, so Termux need not be opened first. */
    fun ensure(c: Context, p: Prov) {
        if (p.kind != "termux" && p.kind != "ollama") return
        if ("127.0.0.1" !in p.url && "localhost" !in p.url) return
        val host = p.url.substringAfter("://").substringBefore("/")
        if (alive(host)) return
        Termux.run(c, Termux.START)
        repeat(25) { if (alive(host)) return; Thread.sleep(1000) }
    }

    fun alive(host: String) = runCatching { get("http://$host/api/tags"); true }.getOrDefault(false)
}

object Termux {
    const val START = "pgrep -x ollama >/dev/null || (nohup ollama serve >/dev/null 2>&1 &)"

    fun run(c: Context, cmd: String): Boolean = runCatching {
        val i = Intent().setClassName("com.termux", "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        c.startForegroundService(i); true
    }.getOrDefault(false)
}
