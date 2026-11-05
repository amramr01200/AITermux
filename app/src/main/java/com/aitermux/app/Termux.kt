package com.aitermux.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object Termux {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val START_OLLAMA = "pgrep -f 'ollama serve' >/dev/null || (nohup ollama serve >/dev/null 2>&1 &)"
    const val ALLOW_CMD = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties"

    val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val counter = AtomicInteger(1000)

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("com.termux", 0); true
    } catch (e: Exception) { false }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun run(ctx: Context, cmd: String, wait: Boolean = true, timeoutMs: Long = 90_000): String {
        val app = ctx.applicationContext
        if (!installed(app)) return "ERROR: Termux not installed"
        if (!hasPermission(app)) return "ERROR: RUN_COMMAND permission not granted"
        val rid = counter.incrementAndGet()
        val d = CompletableDeferred<String>()
        val i = Intent()
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        if (wait) {
            pending[rid] = d
            val ri = Intent(app, TermuxResultReceiver::class.java).putExtra("rid", rid)
            val pi = PendingIntent.getBroadcast(
                app, rid, ri, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        }
        try {
            app.startService(i)
        } catch (e: Exception) {
            pending.remove(rid)
            return "ERROR: ${e.message}"
        }
        if (!wait) return "OK"
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: run {
            pending.remove(rid)
            "ERROR: timeout"
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val rid = i.getIntExtra("rid", -1)
        val b = i.getBundleExtra("result")
        val out = b?.getString("stdout") ?: ""
        val err = b?.getString("stderr") ?: ""
        val code = b?.getInt("exitCode", -1) ?: -1
        val text = buildString {
            append(out)
            if (err.isNotBlank()) append("\n[stderr] ").append(err)
            append("\n[exit ").append(code).append("]")
        }
        Termux.pending.remove(rid)?.complete(text.take(6000))
    }
}
