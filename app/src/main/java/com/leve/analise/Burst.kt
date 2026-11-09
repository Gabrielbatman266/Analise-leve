package com.leve.analise

import android.content.Context
import org.json.JSONObject

/**
 * 👯 Modo rajada (Threads): posta em blocos — 1 por vez, dupla ou trio — com 10–15 min entre os do bloco
 * e a pausa normal entre os blocos. Os posts do mesmo bloco são escolhidos DIFERENTES (outra "cara" de foto,
 * outro tipo de legenda). Mede 1º × 2º × 3º e, no automático, decide sozinho o tamanho do bloco.
 */
object Burst {
    private fun p(c: Context) = Store.prefs(c)
    private const val JOIN = 30 * 60_000L // posts a até 30 min um do outro = mesmo bloco

    fun auto(c: Context) = p(c).getBoolean("burst_auto", false)
    fun size(c: Context) = p(c).getInt("burst_n", 1).coerceIn(1, 3)
    fun setSize(c: Context, n: Int, auto: Boolean = false, note: String? = null) {
        val e = p(c).edit()
        if (n != size(c)) e.putLong("burst_since_" + n, System.currentTimeMillis()).putLong("burst_changed", System.currentTimeMillis())
        e.putInt("burst_n", n.coerceIn(1, 3)).putBoolean("burst_auto", auto)
        note?.let { e.putString("burst_note", it) }
        e.apply()
        Reminder.schedule(c)
    }
    fun gap(c: Context) = p(c).getInt("burst_gmin", 10) to p(c).getInt("burst_gmax", 15)
    fun setGap(c: Context, a: Int, b: Int) = p(c).edit().putInt("burst_gmin", a).putInt("burst_gmax", b).apply()
    fun note(c: Context) = p(c).getString("burst_note", "") ?: ""
    fun label(n: Int) = when (n) { 2 -> "dupla"; 3 -> "trio"; else -> "1 por vez" }

    private fun threadsSends(c: Context) = SendLog.all(c).filter { it.optString("kind") == "fila" && !it.optBoolean("manual") }

    /** Posição que o PRÓXIMO post teria no bloco atual (1 = começa bloco novo). */
    fun nextPos(c: Context): Int {
        val last = threadsSends(c).firstOrNull() ?: return 1
        if (System.currentTimeMillis() - last.optLong("at") > JOIN) return 1
        val pos = last.optInt("pos", 1)
        return if (pos >= size(c)) 1 else pos + 1
    }
    fun inBlock(c: Context) = size(c) > 1 && nextPos(c) > 1

    /** Os posts já feitos no bloco atual. */
    fun currentBlock(c: Context): List<JSONObject> {
        val l = threadsSends(c); val first = l.firstOrNull() ?: return emptyList()
        if (System.currentTimeMillis() - first.optLong("at") > JOIN) return emptyList()
        val b = first.optLong("bloco", first.optLong("at"))
        return l.takeWhile { it.optLong("bloco", it.optLong("at")) == b }
    }

    /** Marca bloco e posição num envio novo (chamado pelo histórico). */
    fun stamp(c: Context, e: JSONObject, prev: JSONObject?) {
        if (e.optString("kind") != "fila" || e.optBoolean("manual")) return
        val join = prev != null && prev.optString("kind") == "fila" && e.optLong("at") - prev.optLong("at") <= JOIN && prev.optInt("pos", 1) < size(c)
        e.put("bloco", if (join) prev!!.optLong("bloco", prev.optLong("at")) else e.optLong("at"))
        e.put("pos", if (join) prev!!.optInt("pos", 1) + 1 else 1)
        e.put("rajada", size(c))
    }

    /** Quando lembrar: dentro do bloco, 10–15 min; senão, a pausa normal. */
    fun nextReminder(c: Context, now: Long): Long? {
        if (size(c) <= 1 || nextPos(c) == 1) return null
        val (a, b) = gap(c)
        val last = threadsSends(c).firstOrNull()?.optLong("at") ?: now
        return maxOf(now + 60_000L, last + (a * 60L..maxOf(a, b) * 60L).random() * 1000L)
    }

    /** Tipo da legenda (pra variar dentro do bloco). */
    fun capType(t: String) = Predictor.textFeats(t, "").filter { it.startsWith("tam:") || it == "pergunta" || it == "emoji" }.sorted().joinToString(",")

    // ---------- medir
    private fun sc(k: String) = when (k) { "top" -> 1.0; "good" -> 0.8; "meh" -> 0.45; "bad" -> 0.0; else -> -1.0 }

    /** Nota média por posição no bloco: [1º, 2º, 3º] (null = sem dados) e quantos blocos com nota. */
    fun byPos(c: Context, n: Int): Pair<List<Double?>, Int> {
        val blocks = threadsSends(c).filter { it.optInt("rajada", 1) == n && it.has("bloco") }.groupBy { it.optLong("bloco") }.values
            .filter { b -> b.size >= 2 && b.any { it.optString("nota").isNotEmpty() } }
        val res = (1..3).map { pos -> blocks.mapNotNull { b -> b.firstOrNull { it.optInt("pos") == pos }?.optString("nota")?.let(::sc)?.takeIf { it >= 0 } }.takeIf { it.isNotEmpty() }?.average() }
        return res to blocks.size
    }

    /** Posts por dia e nota média em cada modo (pra comparar). */
    fun byMode(c: Context): Map<Int, Pair<Double, Double?>> {
        val l = threadsSends(c)
        return l.groupBy { it.optInt("rajada", 1) }.mapValues { (_, v) ->
            val days = maxOf(1.0, (v.maxOf { it.optLong("at") } - v.minOf { it.optLong("at") }) / 86400_000.0)
            val notes = v.mapNotNull { sc(it.optString("nota")).takeIf { s -> s >= 0 } }
            (v.size / days) to notes.takeIf { it.size >= 3 }?.average()
        }
    }

    fun report(c: Context): String {
        val sb = StringBuilder()
        listOf(2, 3).forEach { n ->
            val (pos, nb) = byPos(c, n)
            if (nb == 0) return@forEach
            sb.append("${if (n == 2) "👯 Dupla" else "3️⃣ Trio"} ($nb bloco(s) com nota): ")
            sb.append(pos.take(n).mapIndexed { i, v -> "${i + 1}º " + (v?.let { "${(it * 100).toInt()}%" } ?: "–") }.joinToString(" · "))
            val p1 = pos[0]; val pl = pos[n - 1]
            if (p1 != null && pl != null && p1 > 0) sb.append(if (pl / p1 >= 0.85) " → o último rende quase igual: vale!" else if (pl / p1 >= 0.65) " → o último rende um pouco menos" else " → o último rende bem menos")
            sb.append("\n")
        }
        val m = byMode(c)
        if (m.size >= 2) sb.append("Por modo: " + m.entries.sortedBy { it.key }.joinToString(" · ") { (n, v) -> "${label(n)}: ${"%.1f".format(v.first)} posts/dia" + (v.second?.let { s -> ", nota ${(s * 100).toInt()}%" } ?: "") } + "\n")
        if (sb.isEmpty()) sb.append("Ainda sem dados: dê notas aos posts das duplas/trios pra comparar 1º × 2º × 3º.")
        return sb.toString().trim()
    }

    /** 🤖 Automático: diminui se o último do bloco rende bem menos; testa aumentar se rende igual. */
    fun autoTick(c: Context) {
        if (!auto(c)) return
        val n = size(c); val now = System.currentTimeMillis()
        if (now - p(c).getLong("burst_changed", 0) < 3 * 86400_000L) return // dá 3 dias pra cada tamanho
        if (n == 1) { setSize(c, 2, true, "🤖 Testando dupla (3 dias) pra comparar com 1 por vez"); return }
        val (pos, nb) = byPos(c, n)
        if (nb < 5) return
        val p1 = pos[0] ?: return; val pl = pos[n - 1] ?: return
        when {
            p1 > 0 && pl / p1 < 0.65 -> setSize(c, n - 1, true, "🤖 O ${n}º do bloco rendia ${(100 - pl / p1 * 100).toInt()}% menos: voltei pra ${label(n - 1)}")
            n < 3 && p1 > 0 && pl / p1 >= 0.9 && nb >= 8 -> setSize(c, n + 1, true, "🤖 O ${n}º rendia quase igual ao 1º: testando ${label(n + 1)}")
        }
    }
}
