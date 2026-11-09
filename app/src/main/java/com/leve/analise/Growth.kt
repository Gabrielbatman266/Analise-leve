package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 📈 Vigia do crescimento: quantos seguidores estamos ganhando DE VERDADE, se está crescendo ou igual,
 * o que a divulgação traz comparado com um post normal, melhores copys, melhores horários,
 * quantos posts por dia rendem mais e o que está faltando (fotos, legendas, registros).
 * Tudo vira texto pra IA usar quando cria legendas.
 */
object Growth {
    private const val DAY = 86400_000L
    private fun p(c: Context) = Store.prefs(c)

    // ---------------------------------------------------------------- série de seguidores
    private fun series(c: Context) = ThreadsApi.followers(c).map { it.optLong("t") to it.optInt("n") }.filter { it.second > 0 }.sortedBy { it.first }

    /** Seguidores num instante (liga os registros vizinhos com uma reta). null = sem registro antes/depois. */
    private fun at(s: List<Pair<Long, Int>>, t: Long, tol: Long = 18 * 3600_000L): Double? {
        if (s.isEmpty()) return null
        val i = s.indexOfFirst { it.first >= t }
        if (i == 0) return if (s[0].first - t <= tol) s[0].second.toDouble() else null
        if (i < 0) return if (t - s.last().first <= tol) s.last().second.toDouble() else null
        val a = s[i - 1]; val b = s[i]
        if (b.first - a.first > 3 * DAY) return null
        return a.second + (b.second - a.second) * (t - a.first).toDouble() / maxOf(1L, b.first - a.first)
    }
    private fun gain(s: List<Pair<Long, Int>>, from: Long, to: Long): Double? { val a = at(s, from) ?: return null; val b = at(s, to) ?: return null; return b - a }

    /** Média de seguidores/dia no período (usa o primeiro e o último registro dentro dele). */
    private fun perDay(s: List<Pair<Long, Int>>, from: Long, to: Long): Double? {
        val w = s.filter { it.first in from..to }
        if (w.size < 2) return null
        val days = (w.last().first - w.first().first) / DAY.toDouble()
        if (days < 0.8) return null
        return (w.last().second - w.first().second) / days
    }

    // ---------------------------------------------------------------- relatório
    class Report(val lines: List<String>, val recs: List<String>, val ai: String, val ok: Boolean, val promoVsNormal: Double?, val promoMeasured: Int)

    private fun block(h: Int) = when (h) { in 0..5 -> "madrugada"; in 6..8 -> "6–9h"; in 9..11 -> "9–12h"; in 12..14 -> "12–15h"; in 15..17 -> "15–18h"; in 18..20 -> "18–21h"; else -> "21–24h" }
    private fun hourOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
    private fun dayStart(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun f1(d: Double) = if (d >= 10 || d <= -10) "%+.0f".format(d) else "%+.1f".format(d)

    fun report(c: Context): Report {
        val now = System.currentTimeMillis()
        val s = series(c)
        val lines = ArrayList<String>(); val recs = ArrayList<String>(); val ai = StringBuilder()
        val sends = SendLog.all(c).filter { it.optString("kind") == "fila" }
        val recentSamples = s.count { now - it.first < 2 * DAY }

        // 1) crescimento real
        val nowN = s.lastOrNull()?.second
        val d1 = gain(s, now - DAY, now)
        val w7 = perDay(s, now - 7 * DAY, now); val w7b = perDay(s, now - 14 * DAY, now - 7 * DAY)
        if (nowN != null) lines += "👥 Agora: $nowN seguidores" + (d1?.let { " · últimas 24 h: ${f1(it)}" } ?: "")
        if (w7 != null) {
            val trend = if (w7b != null && w7b != 0.0) (w7 - w7b) / Math.abs(w7b) * 100 else null
            val verdict = when { trend == null -> ""; trend > 10 -> " · 📈 crescendo ${"%.0f".format(trend)}% mais rápido que a semana anterior"
                trend < -10 -> " · 📉 ${"%.0f".format(-trend)}% mais devagar que a semana anterior"; else -> " · ➖ igual à semana anterior" }
            lines += "📅 Média 7 dias: ${f1(w7)}/dia" + (w7b?.let { " (antes: ${f1(it)}/dia)" } ?: "") + verdict
            ai.append("📈 CRESCIMENTO REAL: ${f1(w7)} seguidores/dia nos últimos 7 dias").append(w7b?.let { " (semana anterior ${f1(it)}/dia)" } ?: "").append(".\n")
        }

        // 2) divulgação: antes x depois e por post
        val promos = sends.filter { Promo.isPromo(it.optString("id")) }
        val first = p(c).getLong("promo_first", 0).takeIf { it > 0 } ?: promos.minOfOrNull { it.optLong("at") }?.also { p(c).edit().putLong("promo_first", it).apply() }
        if (first != null) {
            val before = perDay(s, first - 14 * DAY, first); val after = perDay(s, first, now)
            if (before != null && after != null) {
                val diff = after - before
                lines += "📣 Desde que a divulgação começou: ${f1(after)}/dia (antes ${f1(before)}/dia) → " + when {
                    diff > maxOf(0.5, Math.abs(before) * 0.1) -> "✅ ganhando ${f1(diff)} seguidores/dia A MAIS"
                    diff < -maxOf(0.5, Math.abs(before) * 0.1) -> "⚠ ${f1(diff)}/dia (caiu)"; else -> "➖ praticamente igual" }
                ai.append("Divulgação no ar: ${f1(after)}/dia contra ${f1(before)}/dia antes.\n")
            }
        }
        val measured = promos.filter { it.has("gain") }
        val promoAvg = measured.map { it.optDouble("gain") }.takeIf { it.isNotEmpty() }?.average()
        // post normal: seguidores por post (pelo insights do post, ou ganho do dia ÷ posts do dia)
        val normFromPosts = ThreadsApi.posts(c).filter { it.optString("kind", "fila") != "video" && it.optLong("send") > 0 && !Promo.isPromo(it.optString("photo")) && it.has("fol") }.map { it.optDouble("fol") }
        val posts7 = sends.count { now - it.optLong("at") < 7 * DAY }
        val normalPerPost = normFromPosts.takeIf { it.size >= 3 }?.average() ?: w7?.let { if (posts7 > 0) it * 7 / posts7 else null }
        if (promos.isNotEmpty()) {
            lines += "📣 ${promos.size} divulgação(ões) · medidas: ${measured.size}" + (promoAvg?.let { " · cada uma trouxe ${f1(it)} seguidores além do normal" } ?: "") +
                (normalPerPost?.let { " · post normal: ${f1(it)}" } ?: "")
        }
        val ratio = if (promoAvg != null && normalPerPost != null && normalPerPost > 0.05) promoAvg / normalPerPost else null
        if (promoAvg != null) ai.append("Cada divulgação do Instagram trouxe em média ${f1(promoAvg)} seguidores além do normal" + (normalPerPost?.let { "; post normal traz ${f1(it)}" } ?: "") + ".\n")

        // 3) melhores copys
        val caps = Promo.caps(c).filter { Promo.tested(it) > 0 }.sortedByDescending { Promo.score(it) }
        if (caps.isNotEmpty()) {
            lines += "✍ Melhores copys: " + caps.take(3).joinToString("  |  ") { "\"" + Promo.fill(c, it.optString("t")).take(50) + "\" ${(Promo.score(it) * 100).toInt()}pts" }
            ai.append("Copys de divulgação que MAIS trouxeram seguidores: " + caps.take(3).joinToString("; ") { "\"" + it.optString("t") + "\"" } + ".\n")
            if (caps.size >= 4) ai.append("Copys que MENOS trouxeram (evite o estilo): " + caps.takeLast(2).joinToString("; ") { "\"" + it.optString("t") + "\"" } + ".\n")
        }

        // 4) melhores horários (divulgação: seguidores; posts normais: pontuação real)
        val byHour = measured.groupBy { block(hourOf(it.optLong("at"))) }.mapValues { (_, v) -> v.map { it.optDouble("gain") }.average() to v.size }.filter { it.value.second >= 1 }
        if (byHour.isNotEmpty()) {
            val best = byHour.maxByOrNull { it.value.first }!!
            lines += "⏰ Divulgação rende mais às ${best.key} (${f1(best.value.first)} seguidores, ${best.value.second} post(s))"
            ai.append("Melhor horário pra divulgar: ${best.key}.\n")
        }
        val numbered = ThreadsApi.posts(c).filter { it.optString("kind", "fila") != "video" && it.optLong("at") > 0 }
        if (numbered.size >= 6) {
            val hb = numbered.groupBy { block(hourOf(it.optLong("at"))) }.filter { it.value.size >= 2 }
                .mapValues { (_, v) -> v.map { ThreadsApi.composite(c, it, numbered) }.average() }
            hb.maxByOrNull { it.value }?.let { b -> lines += "⏰ Posts normais rendem mais às ${b.key} (${(b.value * 100).toInt()} pts)" + (hb.minByOrNull { it.value }?.takeIf { it.key != b.key }?.let { " · pior: ${it.key}" } ?: ""); ai.append("Posts normais rendem mais às ${b.key}.\n") }
        }

        // 5) ritmo: posts por dia × ganho do dia
        val days = (1..14).mapNotNull { k ->
            val d0 = dayStart(now) - k * DAY; val n = sends.count { it.optLong("at") in d0 until d0 + DAY }
            if (n == 0) null else gain(s, d0, d0 + DAY)?.let { n to it }
        }
        if (days.size >= 3) {
            fun bk(n: Int) = when { n <= 5 -> "até 5"; n <= 10 -> "6–10"; n <= 15 -> "11–15"; else -> "16+" }
            val g = days.groupBy { bk(it.first) }.mapValues { (_, v) -> v.map { it.second }.average() to v.size }
            lines += "📊 Posts por dia × seguidores: " + listOf("até 5", "6–10", "11–15", "16+").mapNotNull { k -> g[k]?.let { "$k posts → ${f1(it.first)}/dia (${it.second}d)" } }.joinToString(" · ")
            g.maxByOrNull { it.value.first }?.let { ai.append("Dias com ${it.key} posts foram os que mais trouxeram seguidores.\n") }
            val gaps = sends.filter { now - it.optLong("at") < 7 * DAY }.map { it.optLong("at") }.sortedDescending().zipWithNext { a, b -> (a - b) / 60_000L }.filter { it in 3..240 }
            if (gaps.isNotEmpty()) lines += "⏱ Intervalo médio entre posts: ${gaps.average().toInt()} min · ${"%.1f".format(posts7 / 7.0)} posts/dia" + (w7?.let { if (posts7 > 0) " · ${f1(it * 7 / posts7)} seguidor(es) por post" else "" } ?: "")
        }

        // 6) o que está faltando
        if (recentSamples < 2) recs += "⚠ Poucos registros de seguidores nas últimas 48 h: conecte a API do Threads ou rode a 🤖 Coleta 1x por dia (ou só abra seu perfil com o leitor ligado). Sem isso não dá pra medir o ganho."
        val dPh = try { Stock.daysPhotos(c) } catch (e: Exception) { 99.0 }
        if (dPh < 3) recs += "📸 As fotos da fila acabam em ~${"%.0f".format(dPh)} dia(s) no ritmo atual: adicione mais fotos."
        if (Promo.on(c) && Promo.caps(c).count { it.optInt("uses") == 0 } < 3) recs += "✍ Poucas legendas de divulgação novas: toque em ✨ Legendas (IA) no cartão 📣."
        if (Promo.on(c) && Promo.handle(c).isEmpty()) recs += "📣 A divulgação está parada: falta o seu @ do Instagram."
        caps.firstOrNull { it.optInt("uses") >= 5 && Promo.score(it) < 0.45 }?.let { recs += "🔁 A copy \"${Promo.fill(c, it.optString("t")).take(40)}\" cansou (${it.optInt("uses")} usos, nota baixa): ela vai sair menos sozinha." }
        if (ratio != null && measured.size >= 4) recs += when {
            ratio >= 1.5 -> "🚀 A divulgação traz ${"%.1f".format(ratio)}x mais seguidores que um post normal: vale divulgar mais vezes."
            ratio <= 0.8 -> "🤔 A divulgação está trazendo menos que um post normal: teste outra copy/imagem ou divulgue menos."
            else -> "➖ A divulgação traz o mesmo que um post normal."
        }
        if (Captions.recentCaps(c).size < 10 && CaptionDB.texts(c, "ia").size < 30) recs += "💬 Banco de legendas normais pequeno: gere mais legendas."
        p(c).getString("promo_tune_last", null)?.let { lines += "🤖 $it" }

        val ok = s.size >= 2
        if (!ok) lines.add(0, "Ainda sem registros de seguidores suficientes pra medir.")
        return Report(lines, recs, ai.toString().trim(), ok, ratio, measured.size)
    }

    fun aiText(c: Context): String = try { report(c).ai } catch (e: Exception) { "" }

    fun autoTune(c: Context) = p(c).getBoolean("promo_tune", true)
    fun setAutoTune(c: Context, v: Boolean) = p(c).edit().putBoolean("promo_tune", v).apply()

    /** De hora em hora (no vigia): ajusta a frequência da divulgação 1x por dia e manda o resumo da noite. */
    fun tick(c: Context) {
        val now = System.currentTimeMillis(); val today = dayStart(now)
        if (p(c).getLong("growth_day", 0) == today) return
        if (hourOf(now) < 21) return
        p(c).edit().putLong("growth_day", today).apply()
        val r = report(c)
        if (autoTune(c) && Promo.on(c) && r.promoVsNormal != null && r.promoMeasured >= 4) {
            val e = Promo.every(c)
            val ne = when { r.promoVsNormal >= 1.5 -> maxOf(4, e - 1); r.promoVsNormal <= 0.8 -> minOf(12, e + 2); else -> e }
            if (ne != e) {
                Promo.setEvery(c, ne)
                p(c).edit().putString("promo_tune_last", android.text.format.DateFormat.format("dd/MM", now).toString() + ": divulgação passou de 1 a cada $e para 1 a cada $ne posts (traz ${"%.1f".format(r.promoVsNormal)}x um post normal)").apply()
            }
        }
        if (!r.ok) return
        val nm = c.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(c, 58, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = (r.lines.take(4) + r.recs.take(2)).joinToString("\n")
        try {
            nm.createNotificationChannel(NotificationChannel("estoque", "Estoque", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(57, Notification.Builder(c, "estoque").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("📈 Vigia do crescimento")
                .setContentText(r.lines.firstOrNull()).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }
}
