package com.aitermux

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            runCatching { File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(ex).take(8000)) }
            prevHandler?.uncaughtException(th, ex)
        }
        Store.init(this)
        tts = TextToSpeech(this) {}
        requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND", Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        handle(intent)
        lifecycleScope.launch { discover() }
        setContent { App(this) }
    }

    override fun onStart() { super.onStart(); Store.visible = true }
    override fun onStop() { Store.visible = false; super.onStop() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "aitermux") return
        val site = d.getQueryParameter("state") ?: d.lastPathSegment ?: return
        d.getQueryParameter("code")?.let {
            Store.tokens[site] = it; Store.save()
            Toast.makeText(this, "✓ $site", Toast.LENGTH_SHORT).show()
        }
    }

    fun say(s: String) {
        tts?.language = Locale(if (Store.lang == "ar") "ar" else "en")
        tts?.speak(s.take(3500), TextToSpeech.QUEUE_FLUSH, null, "r")
    }

    /** Auto-start Ollama (optional) and auto-discover servers on saved hosts. */
    private suspend fun discover() {
        runCatching { Store.dedupe() }
        // Essential providers (FreeLLMAPI by default) are started through Termux right away; the rest start only when used.
        lifecycleScope.launch {
            for (p in Store.provs.toList().filter { Store.essential(it) }) {
                val se = startAndWait(this@MainActivity, p)
                if (se != null) { Health.markStopped(p, se); continue }
                val ms = withContext(Dispatchers.IO) { runCatching { Api.models(p) }.getOrNull() }
                if (ms != null) {
                    val i = Store.provs.indexOfFirst { it.id == p.id }
                    if (i >= 0) Store.provs[i] = Store.merge(Store.provs[i], ms)
                    Health.fetchOk(p)
                }
                Store.save()
            }
        }
        if (Store.autoOllama) { Termux.run(this, Termux.START); delay(3000) }
        for (h in Store.hosts.toList()) {
            val url = "http://$h"
            if (Store.provs.any { it.url == url }) continue
            val ms = withContext(Dispatchers.IO) { runCatching { Api.models(Prov("", "", url, "", "ollama", emptyList())) }.getOrNull() } ?: continue
            Store.provs.add(Prov(uid(), "Ollama $h", url, "", "termux", ms)); Store.save()
        }
        // Silent refresh: new models are added, vanished ones are kept as "old".
        for (p in Store.provs.toList().filter { !Store.essential(it) }) {
            val rr = withContext(Dispatchers.IO) { runCatching { Api.models(p) } }
            val ms = rr.getOrNull()
            if (ms == null) { Health.fetchFailed(p, rr.exceptionOrNull()?.message ?: ""); continue }
            Health.fetchOk(p)
            val i = Store.provs.indexOfFirst { it.id == p.id }
            if (i >= 0) Store.provs[i] = Store.merge(Store.provs[i], ms)
        }
        runCatching { scanLocal(this) }
        Store.save()
    }
}
