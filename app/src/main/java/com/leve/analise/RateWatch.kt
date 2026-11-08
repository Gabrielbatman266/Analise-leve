package com.leve.analise

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File

/**
 * 🧠 Nota automática com várias fontes (não depende de uma só):
 *  1) notificações de curtida (Threads/Instagram);
 *  2) leitor de tela: quando você abre seus posts no app, lê as curtidas ao lado da legenda;
 *  3) nota relativa: compara com a SUA média (não só um número fixo);
 *  4) vigia de hora em hora: post com mais de 1 h sem nota → descobre o gargalo, conserta o que dá e te pede a nota com prévia.
 */
object Likes {
    private fun file(c: Context) = File(c.filesDir, "curtidas.json")
    fun all(c: Context) = JsonFile.read(file(c))

    /** Guarda o maior número de curtidas visto pra um post. src: "notif", "tela". */
    fun record(c: Context, kind: String, id: String, likes: Int, src: String) {
        if (likes <= 0) return
        JsonFile.edit(file(c)) { l ->
            val o = l.firstOrNull { it.optString("kind") == kind && it.optString("id") == id && System.currentTimeMillis() - it.optLong("first") < 3 * 86400_000L }
            if (o == null) l.add(0, JSONObject().put("kind", kind).put("id", id).put("likes", likes).put("first", System.currentTimeMillis()).put("at", System.currentTimeMillis()).put("src", src))
            else if (likes > o.optInt("likes")) { o.put("likes", likes); o.put("at", System.currentTimeMillis()); o.put("src", src) }
            while (l.size > 400) l.removeAt(l.size - 1)
        }
        Store.prefs(c).edit().putLong("lk_last_" + src, System.currentTimeMillis()).apply()
    }

    fun of(c: Context, kind: String, id: String) = all(c).firstOrNull { it.optString("kind") == kind && it.optString("id") == id }

    /** Sua média recente (mediana) por tipo; null = poucos dados. */
    fun median(c: Context, kind: String): Int? {
        val v = all(c).filter { it.optString("kind") == kind }.map { it.optInt("likes") }.sorted()
        return if (v.size < 5) null else v[v.size / 2]
    }

    /** Nota pelas curtidas: limite fixo OU relativo à sua mediana (o que fizer mais sentido). */
    fun verdict(c: Context, kind: String, likes: Int, ageH: Double): String? {
        val min = if (kind == "video") ThreadsNotif.igMin(c) else ThreadsNotif.min(c)
        val med = median(c, kind)
        val good = if (med != null) minOf(min, maxOf(5, (med * 1.5).toInt())) else min
        val top = if (med != null) maxOf(good * 2, med * 4) else min * 5
        return when {
            likes >= top -> "top"
            likes >= good -> "good"
            ageH >= 20 && med != null && likes < med * 0.5 -> "bad"   // depois de um dia, bem abaixo da sua média
            ageH >= 20 && med != null -> "meh"
            else -> null
        }
    }
}

object RateWatch {
    private fun p(c: Context) = Store.prefs(c)
    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 91, Intent(c, RateWatchReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context) {
        try { c.getSystemService(AlarmManager::class.java).setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 20 * 60_000L, AlarmManager.INTERVAL_HOUR, pi(c)) } catch (e: Exception) { }
    }

    fun readerOn(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { ComponentName.unflattenFromString(it)?.let { cn -> cn.packageName == c.packageName && cn.className.endsWith("ScreenReader") } == true }
    }
    fun openReader(c: Context) = try { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }

    fun diag(c: Context) = p(c).getString("rw_diag", "") ?: ""

    /** Roda de hora em hora (e ao abrir o app). Devolve quantas notas deu sozinho. */
    fun run(c: Context, notify: Boolean = true): Int {
        val now = System.currentTimeMillis()
        val sends = SendLog.all(c).filter { now - it.optLong("at") < 3 * 86400_000L && it.optString("nota").isEmpty() }
        var auto = 0
        // 1) dá nota com as curtidas que já se sabe
        sends.forEach { e ->
            val kind = if (e.optString("kind") == "video") "video" else "fila"
            val lk = Likes.of(c, kind, e.optString("id")) ?: return@forEach
            if (lk.optLong("first") < e.optLong("at") - 60_000L) return@forEach // curtidas de um post anterior
            val k = Likes.verdict(c, kind, lk.optInt("likes"), (now - e.optLong("at")) / 3600_000.0) ?: return@forEach
            try { SendLog.rate(c, e, k); auto++ } catch (x: Exception) { }
        }
        // 2) o que passou de 1 h sem nota: acha o gargalo
        val late = SendLog.all(c).filter { it.optString("nota").isEmpty() && now - it.optLong("at") in 3600_000L..(3 * 86400_000L) }
        val reasons = ArrayList<String>()
        if (late.isNotEmpty()) {
            val acc = ThreadsNotif.hasAccess(c)
            if (!acc) reasons += "🔒 o leitor de notificações não tem permissão (Ajustes › 🔕 › 1. Liberar acesso; se bloquear, ⋮ › Permitir configurações restritas)"
            else if (!ThreadsNotif.live(c)) { ThreadsNotif.rebind(c); reasons += "🔌 o leitor de notificações estava desligado: reconectei" }
            val seen = p(c).getLong("tn_seen", 0)
            if (acc && now - seen > 6 * 3600_000L) reasons += "📭 o Threads não mandou notificação nas últimas 6 h: no Threads, Configurações › Notificações › Curtidas → ligado (pode ser silencioso)"
            val log = try { org.json.JSONArray(p(c).getString("tn_log", "[]")) } catch (x: Exception) { org.json.JSONArray() }
            val logs = (0 until log.length()).map { log.getJSONObject(it) }
            if (logs.size >= 4 && logs.none { it.optInt("l") >= 0 }) reasons += "❓ chegam notificações do Threads mas não reconheço curtidas nelas (manda um print do cartão 🔕 que eu ajusto)"
            if (logs.any { it.optInt("l") in 1 until ThreadsNotif.min(c) } && logs.none { it.optInt("l") >= ThreadsNotif.min(c) })
                reasons += "📉 as curtidas não chegaram no limite (${ThreadsNotif.min(c)}): a nota relativa à sua média resolve isso sozinha depois de alguns posts"
            if (!readerOn(c)) reasons += "👀 alternativa desligada: ligue o Leitor de tela do Post Leve (ele lê as curtidas quando você abre seus posts)"
            if (reasons.isEmpty()) reasons += "⏳ ainda sem curtidas suficientes ou sem dados: te peço a nota"
        }
        p(c).edit().putString("rw_diag", if (late.isEmpty()) "✓ Nenhum post esperando nota há mais de 1 h." else "${late.size} post(s) há +1 h sem nota. Motivo provável:\n" + reasons.joinToString("\n"))
            .putLong("rw_at", now).apply()
        // 3) pede a nota com prévia (no máx. a cada 2 h)
        if (notify && late.isNotEmpty() && now - p(c).getLong("rw_ask", 0) > 2 * 3600_000L) { ask(c, late); p(c).edit().putLong("rw_ask", now).apply() }
        return auto
    }

    private fun ask(c: Context, late: List<JSONObject>) {
        val e = late.minBy { it.optLong("at") }
        val kind = e.optString("kind"); val id = e.optString("id")
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nota", "Como foi o post", NotificationManager.IMPORTANCE_DEFAULT))
        fun act(k: String, label: String) = Notification.Action.Builder(null, label, PendingIntent.getBroadcast(c, (k + e.optLong("at")).hashCode(),
            Intent(c, RateWatchReceiver::class.java).setAction("rate").putExtra("at", e.optLong("at")).putExtra("k", k)
                .setData(android.net.Uri.parse("rw://${e.optLong("at")}/$k")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val thumb = SendLog.thumb(c, if (kind == "video") "video" else "foto", id)
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
        val open = PendingIntent.getActivity(c, 92, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, "nota").setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("⭐ ${late.size} post(s) sem nota · como foi este? (${fmt.format(java.util.Date(e.optLong("at")))})")
            .setContentText(e.optString("cap").replace('\n', ' ').ifBlank { "sem legenda" }.take(80))
            .setContentIntent(open).setAutoCancel(true)
            .addAction(act("top", "💎 Viralizou")).addAction(act("good", "🔥 Bombou")).addAction(act("bad", "👎 Flopou"))
        thumb?.let { b.setLargeIcon(it); b.setStyle(Notification.BigPictureStyle().bigPicture(it).setSummaryText(e.optString("cap").replace('\n', ' ').take(100))) }
        try { nm.notify(93, b.build()) } catch (x: SecurityException) { }
    }
}

class RateWatchReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == "rate") {
            val at = i.getLongExtra("at", 0); val k = i.getStringExtra("k") ?: return
            SendLog.all(c).firstOrNull { it.optLong("at") == at }?.let { SendLog.rate(c, it, k) }
            c.getSystemService(NotificationManager::class.java).cancel(93)
            Store.prefs(c).edit().putLong("rw_ask", 0).apply()
            val pr = goAsync(); Thread { try { RateWatch.run(c) } finally { pr.finish() } }.start() // já pergunta o próximo, se tiver
            return
        }
        val pr = goAsync(); Thread { try { RateWatch.run(c) } finally { pr.finish() } }.start()
    }
}

/** 👀 Leitor de tela: só no Threads/Instagram, só lê o número de curtidas perto da legenda dos SEUS posts. */
class ScreenReader : AccessibilityService() {
    private var last = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val now = System.currentTimeMillis()
        if (now - last < 1500) return
        last = now
        val pkg = e?.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || texts.size > 600) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
        }
        try { walk(root, 0) } catch (x: Exception) { return }
        Thread { try { scan(pkg, texts) } catch (x: Exception) { } }.start()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
    private val NUM = Regex("^(\\d[\\d.,]*)\\s*(mil|k)?$", RegexOption.IGNORE_CASE)
    private val LIKEWORD = Regex("(\\d[\\d.,]*)\\s*(mil|k)?\\s*(curtidas?|likes?)", RegexOption.IGNORE_CASE)

    private fun num(s: String, m: String?): Int {
        val mult = if (m.isNullOrEmpty()) 1.0 else 1000.0
        val clean = if (mult > 1) s.replace(',', '.') else s.replace(".", "").replace(",", "")
        return ((clean.toDoubleOrNull() ?: 0.0) * mult).toInt()
    }

    private fun scan(pkg: String, texts: List<String>) {
        val video = pkg == ThreadsNotif.INSTA
        val sends = SendLog.all(this).filter { System.currentTimeMillis() - it.optLong("at") < 7 * 86400_000L && (it.optString("kind") == "video") == video }
        if (sends.isEmpty()) return
        val nt = texts.map { norm(it) }
        sends.distinctBy { it.optString("id") }.forEach { s ->
            val cap = norm(s.optString("cap"))
            if (cap.length < 8) return@forEach
            val key = cap.take(28)
            val idx = nt.indexOfFirst { it.length >= 8 && (it.startsWith(key) || key.startsWith(it.take(28)) && it.length >= 12) }
            if (idx < 0) return@forEach
            var likes: Int? = null
            for (j in idx + 1..minOf(nt.size - 1, idx + 14)) {
                LIKEWORD.find(nt[j])?.let { likes = num(it.groupValues[1], it.groupValues[2]); break }
            }
            if (likes == null) for (j in idx + 1..minOf(nt.size - 1, idx + 10)) {
                NUM.find(nt[j])?.let { likes = num(it.groupValues[1], it.groupValues[2]); break }
            }
            likes?.let { Likes.record(this, if (video) "video" else "fila", s.optString("id"), it, "tela") }
        }
    }

    override fun onInterrupt() {}
}
