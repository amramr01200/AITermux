package com.aitermux

/** "Newest models only": hides models whose generation number is below the family's minimum. Models with no clear generation stay visible. */
object Fresh {
    val FAMILIES = listOf("claude", "gpt", "gemini", "qwen", "deepseek", "llama", "kimi", "glm", "mistral", "nemotron", "gemma")
    val DEFAULTS = mapOf("claude" to "5", "gpt" to "6", "gemini" to "4")

    private val NONE = Pair("", -1)
    private val cache = HashMap<String, Pair<String, Int>>()
    private val sizeTok = Regex("^\\d+(?:\\.\\d+)?[bmk]\$|^a\\d+b\$", RegexOption.IGNORE_CASE)
    private val genTok = Regex("^[a-z]?(\\d)(?:\\.\\d+)*[a-z]?\$", RegexOption.IGNORE_CASE)
    private val sepRx = Regex("[-_/:\\s]+")
    private var sig = Long.MIN_VALUE
    private var latest = HashMap<String, Int>()

    fun families(): List<String> = (FAMILIES + Store.famMin.keys).distinct()

    fun reset() { cache.clear(); sig = Long.MIN_VALUE }

    private fun compute(m: String): Pair<String, Int>? {
        val id = m.lowercase()
        var fam: String? = null
        var at = Int.MAX_VALUE
        for (f in families()) { val i = id.indexOf(f); if (i in 0 until at) { fam = f; at = i } }
        val f = fam ?: return null
        val rem = id.substring(id.lastIndexOf(f) + f.length)
        for (tk in rem.split(sepRx)) {
            if (tk.isEmpty() || sizeTok.matches(tk)) continue
            val g = genTok.matchEntire(tk) ?: continue
            return Pair(f, g.groupValues[1].toInt())
        }
        return null
    }

    /** (family, generation) or null when the family or the generation is unknown. */
    fun parse(m: String): Pair<String, Int>? {
        val r = cache.getOrPut(m) { compute(m) ?: NONE }
        return if (r.second < 0) null else r
    }

    /** Recomputes the newest generation per family when the model lists changed. Call once before filtering. */
    fun prepare() {
        val s = Store.provs.size * 1_000_003L + Store.famMin.size + Store.provs.sumOf { it.models.size * 31L + (it.models.firstOrNull()?.hashCode() ?: 0) + (it.models.lastOrNull()?.hashCode() ?: 0) }
        if (s == sig) return
        sig = s
        val cnt = HashMap<Pair<String, Int>, Int>()
        for (p in Store.provs) for (m in p.models) parse(m)?.let { cnt[it] = (cnt[it] ?: 0) + 1 }
        val strong = HashMap<String, Int>()
        val any = HashMap<String, Int>()
        for ((k, c) in cnt) {
            if (k.second > (any[k.first] ?: -1)) any[k.first] = k.second
            if (c >= 3 && k.second > (strong[k.first] ?: -1)) strong[k.first] = k.second
        }
        latest = HashMap(any); latest.putAll(strong)
    }

    fun minFor(fam: String): Int = Store.famMin[fam]?.trim()?.toIntOrNull() ?: latest[fam] ?: 0

    fun stale(m: String): Boolean {
        if (!Store.hideOld) return false
        val r = parse(m) ?: return false
        return r.second < minFor(r.first)
    }
}
