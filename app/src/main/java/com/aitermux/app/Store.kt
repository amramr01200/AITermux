package com.aitermux.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Store(ctx: Context) {
    private val dir = ctx.filesDir
    fun read(name: String): String? {
        val f = File(dir, name)
        return if (f.exists()) f.readText() else null
    }
    fun write(name: String, text: String) {
        File(dir, name).writeText(text)
    }
}

object J {
    fun providers(l: List<Provider>) = JSONArray().also { a ->
        l.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("key", it.key)
                    .put("kind", it.kind).put("email", it.email).put("page", it.page).put("mb", it.modelsBase)
            )
        }
    }

    fun parseProviders(a: JSONArray) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Provider(
            o.getString("id"), o.optString("name"), o.optString("url"), o.optString("key"),
            o.optString("kind", "provider"), o.optString("email"), o.optString("page"), o.optString("mb")
        )
    }

    fun models(l: List<ModelEntry>) = JSONArray().also { a ->
        l.forEach {
            a.put(
                JSONObject().put("p", it.providerId).put("m", it.modelId).put("c", it.created)
                    .put("v", it.vision).put("t", it.fromTermux)
            )
        }
    }

    fun parseModels(a: JSONArray) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        ModelEntry(o.getString("p"), o.getString("m"), o.optLong("c"), o.optBoolean("v"), o.optBoolean("t"))
    }

    fun tools(l: List<ToolDef>) = JSONArray().also { a ->
        l.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("desc", it.description).put("kind", it.kind)
                    .put("cmd", it.command).put("inst", it.installCmd).put("url", it.url).put("pw", it.password)
            )
        }
    }

    fun parseTools(a: JSONArray) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        ToolDef(
            o.getString("id"), o.optString("name"), o.optString("desc"), o.optString("kind", "llm"),
            o.optString("cmd"), o.optString("inst"), o.optString("url"), o.optString("pw")
        )
    }

    fun chats(l: List<Chat>) = JSONArray().also { a ->
        l.forEach { c ->
            val ms = JSONArray()
            c.msgs.toList().forEach { m ->
                val att = JSONArray()
                m.attachments.forEach { att.put(it.name) }
                ms.put(JSONObject().put("r", m.role).put("c", m.content).put("a", att))
            }
            a.put(JSONObject().put("id", c.id).put("t", c.title).put("u", c.updated).put("m", ms))
        }
    }

    fun parseChats(a: JSONArray): List<Chat> = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        val c = Chat(o.getString("id"), o.optString("t"), o.optLong("u"))
        val ms = o.optJSONArray("m") ?: JSONArray()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val att = m.optJSONArray("a") ?: JSONArray()
            val atts = (0 until att.length()).map { j -> Attachment(att.getString(j), "", null, emptyList()) }
            c.msgs.add(Msg(m.optString("r"), m.optString("c"), atts))
        }
        c
    }
}
