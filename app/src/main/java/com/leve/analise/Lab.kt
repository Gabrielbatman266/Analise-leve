package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 🧪 Laboratório: experimentos de verdade, um de cada vez.
 *
 * O modelo de previsão só OBSERVA (e pode se enganar: "legenda com pergunta vai bem" pode ser só porque
 * você usou pergunta nas melhores fotos). Aqui o app TESTA: escolhe uma coisa (ex.: legenda com pergunta × sem),
 * SORTEIA de que lado cada post cai — a foto é escolhida antes, sem saber do lado — e compara os resultados
 * reais. Quando a diferença fica clara (92% de certeza), vira uma LEI: entra no sorteio e nos pedidos de IA.
 * Depois ele mesmo escolhe o próximo teste, começando pelo que o modelo acha mais importante e ainda não provou.
 */
object Lab {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("lab_on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("lab_on", v).apply()

    private const val MIN = 6          // posts de cada lado antes de olhar
    private const val MAX = 36         // total: se não decidiu até aqui, "não faz diferença"
    private const val SURE = 0.92
    private const val RETEST = 45L * 86400_000L

    val FACTORS = linkedMapOf(
        "pergunta" to ("legenda com pergunta" to "sem pergunta"),
        "emoji" to ("legenda com emoji" to "sem emoji"),
        "tam:curto" to ("legenda curta (até 35 letras)" to "média ou longa"),
        "minusculas" to ("legenda toda em minúsculas" to "com maiúsculas"),
        "quebra" to ("legenda com quebra de linha" to "numa linha só"),
        "fala" to ("legenda em formato de fala (\"…\")" to "sem aspas"),
        "reticencias" to ("legenda com reticências" to "sem reticências"))

    fun has(cap: String, factor: String) = factor in Predictor.textFeats(cap, "")
    private fun key(cap: String) = cap.trim().lowercase().hashCode().toString()

    private fun obj(c: Context, k: String): JSONObject? = try { p(c).getString(k, null)?.let { JSONObject(it) } } catch (e: Exception) { null }
    fun active(c: Context) = obj(c, "lab_now")
    fun history(c: Context): List<JSONObject> = try { val a = JSONArray(p(c).getString("lab_hist", "[]")); (0 until a.length()).map { a.getJSONObject(it) } } catch (e: Exception) { emptyList() }
    /** Leis provadas: fator → diferença em pontos (positivo = com o fator rende mais). */
    fun laws(c: Context): Map<String, Double> = history(c).sortedBy { it.optLong("at") }.associate { it.optString("factor") to (if (it.optString("verdict") == "none") 0.0 else it.optDouble("lift")) }.filterValues { it != 0.0 }

    /** Escolhe o próximo teste: o fator que o modelo acha mais importante, ainda não testado (ou testado há 45+ dias), e que dá pra testar com as legendas que existem. */
    private fun next(c: Context): JSONObject? {
        val bank = CaptionDB.all(c).filter { it.optString("src") != "viral" }.map { it.optString("t") }
        val now = System.currentTimeMillis()
        val recent = history(c).filter { now - it.optLong("at") < RETEST }.map { it.optString("factor") }.toSet()
        val f = FACTORS.keys.filter { it !in recent }.filter { k -> val n = bank.count { has(it, k) }; n >= 5 && bank.size - n >= 5 }
            .maxByOrNull { Math.abs(Predictor.weight(c, "f.leg.$it")) + Math.random() * 0.01 } ?: return null
        return JSONObject().put("factor", f).put("at", now).put("caps", JSONArray()).also { p(c).edit().putString("lab_now", it.toString()).apply() }
    }

    /**
     * Chamado no sorteio, DEPOIS de a foto ser escolhida: decide o lado deste post e devolve só as legendas desse lado.
     * null = sem teste agora (segue o sorteio normal).
     */
    fun arm(c: Context, pool: List<String>): List<String>? {
        if (!on(c) || pool.size < 4) return null
        val ex = active(c) ?: next(c) ?: return null
        val f = ex.optString("factor")
        val a = pool.filter { has(it, f) }; val b = pool.filter { !has(it, f) }
        if (a.size < 2 || b.size < 2) return null
        val (na, nb) = counts(c, ex)
        val pickA = if (na == nb) Math.random() < 0.5 else na < nb // equilibra os dois lados
        return if (pickA) a else b
    }

    /** A legenda que o sorteio do teste escolheu (só ela entra no teste, e só se o post sair de verdade). */
    fun picked(c: Context, cap: String) = p(c).edit().putString("lab_pick", key(cap)).apply()

    /** O post saiu com esta legenda: se foi a sorteada pelo teste, entra na conta. */
    fun sent(c: Context, cap: String) {
        if (p(c).getString("lab_pick", "") != key(cap)) return
        p(c).edit().remove("lab_pick").apply()
        val ex = active(c) ?: return
        val a = ex.optJSONArray("caps") ?: JSONArray()
        a.put(key(cap)); ex.put("caps", a)
        p(c).edit().putString("lab_now", ex.toString()).apply()
    }

    private fun score(k: String) = when (k) { "top" -> 1.0; "good" -> 0.8; "meh" -> 0.45; "bad" -> 0.0; else -> null }

    /** Resultados dos posts do teste: (lado A, lado B). */
    private fun outcomes(c: Context, ex: JSONObject): Pair<List<Double>, List<Double>> {
        val caps = ex.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val f = ex.optString("factor"); val a = ArrayList<Double>(); val b = ArrayList<Double>()
        SendLog.all(c).filter { it.optString("kind") == "fila" && it.optLong("at") >= ex.optLong("at") && !Promo.isPromo(it.optString("id")) && key(it.optString("cap")) in caps }.forEach { e ->
            val y = ThreadsApi.targetFor(c, e.optLong("at")) ?: score(e.optString("nota")) ?: return@forEach
            if (has(e.optString("cap"), f)) a += y else b += y
        }
        return a to b
    }
    private fun counts(c: Context, ex: JSONObject): Pair<Int, Int> {
        val caps = ex.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val f = ex.optString("factor")
        val l = SendLog.all(c).filter { it.optString("kind") == "fila" && it.optLong("at") >= ex.optLong("at") && key(it.optString("cap")) in caps }
        return l.count { has(it.optString("cap"), f) } to l.count { !has(it.optString("cap"), f) }
    }

    /** Φ(z): chance de A ser melhor que B. */
    private fun phi(z: Double): Double {
        val t = 1 / (1 + 0.2316419 * Math.abs(z)); val d = 0.3989423 * Math.exp(-z * z / 2)
        val pr = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (z > 0) 1 - pr else pr
    }
    private fun chanceA(a: List<Double>, b: List<Double>): Double {
        fun v(l: List<Double>): Double { val m = l.average(); return maxOf(0.01, l.sumOf { (it - m) * (it - m) } / maxOf(1, l.size - 1)) }
        return phi((a.average() - b.average()) / Math.sqrt(v(a) / a.size + v(b) / b.size))
    }

    /** De hora em hora: confere o teste; se decidiu, vira lei, avisa e começa o próximo. */
    fun tick(c: Context) {
        if (!on(c)) return
        val ex = active(c) ?: run { next(c); return }
        val (a, b) = outcomes(c, ex)
        val old = System.currentTimeMillis() - ex.optLong("at") > 21L * 86400_000L
        if (a.size < MIN || b.size < MIN) { if (old && a.size + b.size < 4) p(c).edit().remove("lab_now").apply(); return }
        val pa = chanceA(a, b)
        val verdict = when { pa >= SURE -> "A"; pa <= 1 - SURE -> "B"; a.size + b.size >= MAX || old -> "none"; else -> return }
        val f = ex.optString("factor"); val lift = a.average() - b.average()
        val h = history(c).filter { it.optString("factor") != f } + JSONObject().put("factor", f).put("verdict", verdict).put("lift", lift)
            .put("na", a.size).put("nb", b.size).put("ma", a.average()).put("mb", b.average()).put("p", pa).put("at", System.currentTimeMillis())
        p(c).edit().putString("lab_hist", JSONArray().apply { h.takeLast(30).forEach { put(it) } }.toString()).remove("lab_now").apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(c, 59, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            nm.createNotificationChannel(NotificationChannel("estoque", "Estoque", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(60, Notification.Builder(c, "estoque").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("🧪 Teste concluído")
                .setContentText(line(h.last())).setStyle(Notification.BigTextStyle().bigText(line(h.last()) + "\nJá estou usando isso no sorteio e nos pedidos de legenda. Próximo teste começando.")).setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
        next(c)
    }

    private fun line(o: JSONObject): String {
        val (ya, nb) = FACTORS[o.optString("factor")] ?: (o.optString("factor") to "sem")
        val n = "${o.optInt("na")} × ${o.optInt("nb")} posts"
        return when (o.optString("verdict")) {
            "A" -> "✅ $ya rende MAIS que $nb: +${(o.optDouble("lift") * 100).toInt()} pts ($n, ${(o.optDouble("p") * 100).toInt()}% de certeza)"
            "B" -> "⛔ $ya rende MENOS que $nb: ${(o.optDouble("lift") * 100).toInt()} pts ($n, ${((1 - o.optDouble("p")) * 100).toInt()}% de certeza)"
            else -> "➖ $ya × $nb: não faz diferença ($n)"
        }
    }

    /** Multiplicador da legenda pelas leis provadas (+10 pts ≈ 1,5x; −10 pts ≈ 0,67x). */
    fun bonus(c: Context): (String) -> Double {
        val l = laws(c); if (l.isEmpty()) return { 1.0 }
        return { cap -> l.entries.fold(1.0) { acc, (f, lift) -> acc * Math.exp(4.0 * lift * (if (has(cap, f)) 0.5 else -0.5)) } }
    }

    fun aiText(c: Context): String {
        val h = history(c).filter { it.optString("verdict") != "none" }; if (h.isEmpty()) return ""
        return "🧪 PROVADO EM TESTE NESTE PERFIL (posts sorteados entre os dois lados; isso vale mais que qualquer palpite):\n" + h.joinToString("\n") { "- " + line(it) }
    }

    fun status(c: Context): String {
        val sb = StringBuilder()
        val ex = active(c)
        if (!on(c)) sb.append("Desligado.")
        else if (ex == null) sb.append("Sem teste agora (precisa de pelo menos 5 legendas de cada tipo no banco).")
        else {
            val (ya, nb) = FACTORS[ex.optString("factor")] ?: ("?" to "?")
            val (na, nbn) = counts(c, ex); val (a, b) = outcomes(c, ex)
            sb.append("🔬 Testando agora: $ya × $nb\nPostados: $na × $nbn · com resultado: ${a.size} × ${b.size} (olho a partir de $MIN de cada lado)")
            if (a.size >= 3 && b.size >= 3) sb.append("\nParcial: ${(a.average() * 100).toInt()} × ${(b.average() * 100).toInt()} pts · certeza até agora: ${(maxOf(chanceA(a, b), 1 - chanceA(a, b)) * 100).toInt()}%")
        }
        val h = history(c).sortedByDescending { it.optLong("at") }
        if (h.isNotEmpty()) sb.append("\n\nJá provado:\n" + h.take(7).joinToString("\n") { line(it) })
        return sb.toString()
    }
}

/**
 * 🕒 Relógio da audiência: aprende a que horas quem te segue está ativo, pelas curtidas que CHEGAM
 * (cada notificação de curtida marca a hora), descontando quantos posts seus estavam no ar naquela hora
 * — senão a hora em que você mais posta sempre pareceria a melhor. Os lembretes apertam nas horas quentes.
 */
object Audience {
    private fun p(c: Context) = Store.prefs(c)
    private fun arr(c: Context, k: String): DoubleArray = try { val a = JSONArray(p(c).getString(k, "[]")); DoubleArray(24) { a.optDouble(it, 0.0) } } catch (e: Exception) { DoubleArray(24) }
    private fun put(c: Context, k: String, d: DoubleArray) = p(c).edit().putString(k, JSONArray().apply { d.forEach { put(Math.round(it * 100) / 100.0) } }.toString()).apply()
    private fun hour(t: Long = System.currentTimeMillis()) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    /** Chegou curtida agora. */
    fun hit(c: Context) { val d = arr(c, "aud_hits"); d[hour()] += 1.0; put(c, "aud_hits", d) }

    /** De hora em hora: anota quantos posts recentes estavam "no ar" nesta hora e envelhece os dados (o hábito das pessoas muda). */
    fun tick(c: Context) {
        val now = System.currentTimeMillis()
        if (now - p(c).getLong("aud_tick", 0) < 50 * 60_000L) return
        val live = SendLog.all(c).count { it.optString("kind") == "fila" && now - it.optLong("at") < 8 * 3600_000L }
        val e = arr(c, "aud_exp"); e[hour()] += live.toDouble()
        val day = now / 86400_000L
        if (p(c).getLong("aud_day", 0) != day) { // meia-vida de ~5 semanas
            val h = arr(c, "aud_hits"); for (i in 0 until 24) { h[i] *= 0.98; e[i] *= 0.98 }; put(c, "aud_hits", h)
            p(c).edit().putLong("aud_day", day).apply()
        }
        put(c, "aud_exp", e); p(c).edit().putLong("aud_tick", now).apply()
    }

    /** Atividade por hora (0..1, a melhor = 1); null = poucos dados. Suavizada com as horas vizinhas. */
    fun curve(c: Context): DoubleArray? {
        val h = arr(c, "aud_hits"); val e = arr(c, "aud_exp")
        if (h.sum() < 40) return null
        val r = DoubleArray(24) { h[it] / (e[it] + 2.0) }
        val s = DoubleArray(24) { (r[(it + 23) % 24] + 2 * r[it] + r[(it + 1) % 24]) / 4 }
        val mx = s.maxOrNull() ?: return null
        return if (mx <= 0) null else DoubleArray(24) { s[it] / mx }
    }
    fun score(c: Context, hourOfDay: Int): Double? = curve(c)?.get(hourOfDay)

    fun text(c: Context): String {
        val cv = curve(c) ?: return ""
        val best = (0 until 24).sortedByDescending { cv[it] }.take(3).sorted()
        val worst = (7..23).sortedBy { cv[it] }.take(2).sorted()
        return "🕒 Sua audiência está mais ativa às " + best.joinToString(", ") { "${it}h" } + " (pelas curtidas que chegam) · mais parada às " + worst.joinToString(", ") { "${it}h" }
    }
}
