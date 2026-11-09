package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ✍ Forçar a legenda: quando o post é mandado pro Threads/Instagram e o campo de texto fica vazio,
 * o leitor de tela escreve a legenda lá. Só preenche o campo — quem publica é você.
 */
object CapForce {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("cap_force", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("cap_force", v).apply()

    /** kind: "fila" (Threads) ou "video"/"fotoIg" (Instagram). */
    fun arm(c: Context, kind: String, cap: String) {
        if (!on(c) || cap.isBlank()) { p(c).edit().remove("cf_cap").apply(); return }
        p(c).edit().putString("cf_cap", cap).putString("cf_pkg", if (kind == "fila") ThreadsNotif.THREADS else ThreadsNotif.INSTA)
            .putLong("cf_at", System.currentTimeMillis()).putInt("cf_try", 0).apply()
    }

    /** Legenda esperando pra entrar neste app (Threads: 2 min; Instagram: 10 min, a tela da legenda vem depois da edição). */
    fun pending(c: Context, pkg: String): String? {
        val cap = p(c).getString("cf_cap", null) ?: return null
        if (p(c).getString("cf_pkg", "") != pkg) return null
        val age = System.currentTimeMillis() - p(c).getLong("cf_at", 0)
        if (age > (if (pkg == ThreadsNotif.THREADS) 120_000L else 600_000L) || p(c).getInt("cf_try", 0) >= 3) { done(c); return null }
        return cap
    }
    fun tried(c: Context) = p(c).edit().putInt("cf_try", p(c).getInt("cf_try", 0) + 1).apply()
    fun done(c: Context) = p(c).edit().remove("cf_cap").apply()
}

/**
 * 🎓 O que você ensinou o leitor a ler. Cada regra diz "o número que aparece neste lugar é tal coisa":
 * guarda o texto vizinho, a posição na fileira de números e o tipo de tela. Vale pro 📖, pro modo manual e pra coleta.
 */
object ReaderRules {
    private fun p(c: Context) = Store.prefs(c)
    @Volatile private var cache: List<JSONObject>? = null

    fun all(c: Context): List<JSONObject> {
        cache?.let { return it }
        val l = try { val a = JSONArray(p(c).getString("reader_rules", "[]")); (0 until a.length()).map { a.getJSONObject(it) } } catch (e: Exception) { emptyList() }
        cache = l; return l
    }

    private fun save(c: Context, l: List<JSONObject>) { cache = l; p(c).edit().putString("reader_rules", JSONArray().apply { l.forEach { put(it) } }.toString()).apply() }

    /** Guarda a regra (troca a igual; no máximo 3 jeitos por métrica e tela, pra cobrir telas diferentes). */
    fun add(c: Context, r: JSONObject) {
        val same = { o: JSONObject -> o.optString("pkg") == r.optString("pkg") && o.optString("screen") == r.optString("screen") && o.optString("metric") == r.optString("metric") }
        val l = all(c).filter { !(same(it) && it.optString("prev") == r.optString("prev") && it.optInt("pos") == r.optInt("pos") && it.optInt("len") == r.optInt("len")) }.toMutableList()
        l.add(0, r.put("at", System.currentTimeMillis()))
        val group = l.filter(same)
        if (group.size > 3 && r.optString("metric") != "ignore") l.removeAll(group.drop(3))
        save(c, l.take(120))
    }

    fun count(c: Context) = all(c).size
    fun clear(c: Context) = save(c, emptyList())

    fun summary(c: Context): String {
        val l = all(c); if (l.isEmpty()) return "Nada ensinado ainda."
        return l.groupBy { (if (it.optString("pkg") == ThreadsNotif.INSTA) "Instagram" else "Threads") + (if (it.optString("screen") == "ins") " · insights" else " · post") }
            .map { (k, v) -> "$k: " + v.map { it.optString("metric") }.distinct().joinToString(", ") }.joinToString(" | ")
    }
}
