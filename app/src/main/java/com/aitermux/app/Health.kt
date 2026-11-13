package com.aitermux

/** Learns which models/providers work from real results, and tells the user why something does not. */
object Health {
    private val KNOWN = mapOf(
        "openrouter.ai" to "https://openrouter.ai/credits",
        "api.groq.com" to "https://console.groq.com/keys",
        "generativelanguage.googleapis.com" to "https://aistudio.google.com/apikey",
        "integrate.api.nvidia.com" to "https://build.nvidia.com/settings/api-keys",
        "api.cometapi.com" to "https://www.cometapi.com/console",
        "router.huggingface.co" to "https://huggingface.co/settings/tokens",
        "api.deepseek.com" to "https://platform.deepseek.com/api_keys",
        "api.openai.com" to "https://platform.openai.com/account/billing",
        "api.mistral.ai" to "https://console.mistral.ai/",
        "models.github.ai" to "https://github.com/settings/personal-access-tokens",
        "api.experientiallabs.ai" to "https://platform.experientiallabs.ai/credits",
    )

    private val vc = HashMap<String, List<Int>>()
    private fun isLocal(p: Prov) = "127.0.0.1" in p.url || "localhost" in p.url
    private val urlRx = Regex("https?://[^\\s\"')\\]]+")
    private val numRx = Regex("(\\d+(?:\\.\\d+)*)")

    fun verOf(m: String): List<Int> = vc.getOrPut(m) { numRx.find(m)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: listOf(0) }

    fun kindOf(msg: String): String {
        val s = msg.lowercase()
        return when {
            "purchase" in s || "insufficient" in s || "balance" in s || "payment" in s || "locked" in s || "402" in s || ("credit" in s && "429" in s) -> "purchase"
            "401" in s || "403" in s || "unauthorized" in s || "incorrect api key" in s || "invalid api key" in s || ("invalid" in s && "key" in s) || "expired" in s -> "key"
            "404" in s || "not found" in s || "does not exist" in s -> "notfound"
            "unsupported" in s || "not support" in s -> "unsupported"
            "429" in s || "rate" in s || "quota" in s || "limit" in s || "exceed" in s -> "quota"
            "http 5" in s || "invalid character" in s || "bad gateway" in s || "overloaded" in s -> "server"
            "timeout" in s || "timed out" in s || "abort" in s || "refused" in s || "failed to connect" in s || "unable to resolve" in s || "reset" in s -> "offline"
            else -> "other"
        }
    }

    fun cooldown(kind: String): Long = when (kind) {
        "purchase", "notfound" -> 24 * 3600_000L
        "unsupported" -> 7 * 24 * 3600_000L
        "key" -> 12 * 3600_000L
        "quota" -> 15 * 60_000L
        "server" -> 5 * 60_000L
        else -> 2 * 60_000L
    }

    fun label(kind: String): String = when (kind) {
        "purchase" -> t("يتطلب شراء أو رصيداً", "Needs purchase / credits")
        "quota" -> t("وصل حد الاستخدام", "Rate / quota limit")
        "key" -> t("المفتاح غير صالح أو منتهٍ", "Key invalid or expired")
        "notfound" -> t("الرابط أو النموذج غير موجود", "URL or model not found")
        "unsupported" -> t("غير مدعوم للدردشة النصية", "Not supported for text chat")
        "server" -> t("عطل عند المزود", "Provider-side failure")
        "offline" -> t("غير متصل", "Not reachable")
        "stopped" -> t("الخادم المحلي متوقف", "Local server stopped")
        else -> t("خطأ", "Error")
    }

    fun advice(kind: String): String = when (kind) {
        "purchase" -> t("أضف رصيداً أو خطة من صفحة المزود، أو استخدم نموذجاً آخر.", "Add credits or a plan on the provider page, or use another model.")
        "quota" -> t("انتظر قليلاً أو استخدم مزوداً آخر.", "Wait a bit or use another provider.")
        "key" -> t("افتح صفحة المفاتيح وأنشئ مفتاحاً جديداً، ثم عدّله في مكتبتي.", "Open the keys page, create a new key, then edit it in My library.")
        "notfound" -> t("تأكد من الرابط (ينتهي بـ /v1 غالباً) واسم النموذج.", "Check the URL (usually ends with /v1) and the model name.")
        "unsupported" -> t("اختر نموذج نص بدل نموذج الصور أو الصوت.", "Pick a text model instead of an image/audio model.")
        "server" -> t("عطل مؤقت عند المزود. أعد المحاولة لاحقاً.", "Temporary provider failure. Retry later.")
        "offline" -> t("تحقق من الإنترنت.", "Check the internet.")
        "stopped" -> t("يُشغَّل تلقائياً عند الاستخدام، أو اضغط «إعادة فحص» لتشغيله الآن.", "It starts automatically when used, or press Re-check to start it now.")
        else -> t("راجع نص الخطأ.", "Check the error text.")
    }

    fun linkFor(p: Prov, msg: String): String {
        urlRx.find(msg)?.value?.trimEnd('.', ',', ';')?.let { return it }
        val host = p.url.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host == "127.0.0.1" || host == "localhost") {
            return when {
                ":3001" in p.url -> "http://127.0.0.1:5173"
                ":20128" in p.url -> "http://127.0.0.1:20128"
                else -> ""
            }
        }
        if (p.url.startsWith("termux:")) return ""
        KNOWN[host]?.let { return it }
        return "https://" + host.removePrefix("api.")
    }

    /** 0 = worked before, 1 = untested, 2 = failing now (in cooldown). */
    fun tier(key: String): Int {
        val now = System.currentTimeMillis()
        val b = Store.bad[key]
        if (b != null && (b.substringBefore('|').toLongOrNull() ?: 0L) > now) return 2
        val ps = Store.provState[key.substringBefore('|')]
        if (ps != null) {
            val k = ps.substringBefore('|')
            val ts = ps.substringAfterLast('|').toLongOrNull() ?: 0L
            if ((k == "key" || k == "server" || k == "offline") && now - ts < 6 * 3600_000L) return 2
        }
        return if (Store.okAt.containsKey(key)) 0 else 1
    }

    fun reasonShort(key: String): String {
        val b = Store.bad[key]
        if (b != null) {
            val p = b.split('|', limit = 4)
            return label(p.getOrElse(1) { "other" }) + (p.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { ": " + it.take(70) } ?: "")
        }
        val ps = (Store.provState[key.substringBefore('|')] ?: return "").split('|', limit = 4)
        return label(ps.getOrElse(0) { "other" }) + (ps.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ": " + it.take(70) } ?: "")
    }

    fun record(p: Prov, m: String, res: Result<String>) {
        val key = p.id + "|" + m
        val now = System.currentTimeMillis()
        if (res.isSuccess) {
            Store.okAt[key] = now; Store.bad.remove(key); Store.provState.remove(p.id)
        } else {
            val msg = res.exceptionOrNull()?.message ?: ""
            if (msg.contains("canceled", true)) return
            val kind = kindOf(msg).let { if (it == "offline" && isLocal(p)) "stopped" else it }
            val link = linkFor(p, msg)
            val reason = msg.replace("|", " ").replace("\n", " ").take(160)
            if (kind == "key" || kind == "offline" || kind == "server" || kind == "stopped") Store.provState[p.id] = "$kind|$reason|$link|$now"
            Store.bad[key] = "${now + cooldown(kind)}|$kind|$reason|$link"
        }
        Store.save()
    }

    fun markStopped(p: Prov, msg: String) {
        Store.provState[p.id] = "stopped|${msg.replace("|", " ").replace("\n", " ").take(200)}|${linkFor(p, "")}|${System.currentTimeMillis()}"
    }

    fun fetchFailed(p: Prov, msg: String) {
        val kind = kindOf(msg).let { if (it == "offline" && isLocal(p)) "stopped" else it }
        Store.provState[p.id] = "$kind|${msg.replace("|", " ").replace("\n", " ").take(160)}|${linkFor(p, msg)}|${System.currentTimeMillis()}"
    }

    fun fetchOk(p: Prov) { Store.provState.remove(p.id) }
}
