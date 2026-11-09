package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 🔕 Filtro das notificações do Threads:
 *  - curtidas abaixo do limite (ex.: 50) somem;
 *  - quando um post passa do limite, o Post Leve avisa (com som) e marca o post como 🔥 sozinho.
 * Precisa do "Acesso a notificações" liberado pro Post Leve.
 */
object ThreadsNotif {
    const val THREADS = "com.instagram.barcelona"
    const val INSTA = "com.instagram.android"
    fun igRate(c: Context) = p(c).getBoolean("ig_rate", true)
    fun setIgRate(c: Context, v: Boolean) = p(c).edit().putBoolean("ig_rate", v).apply()
    fun igSilence(c: Context) = p(c).getBoolean("ig_silence", false)
    fun setIgSilence(c: Context, v: Boolean) = p(c).edit().putBoolean("ig_silence", v).apply()
    fun igMin(c: Context) = p(c).getInt("ig_min", 100)
    fun setIgMin(c: Context, v: Int) = p(c).edit().putInt("ig_min", v).apply()

    /**
     * Curtidas no Instagram → nota do Reel. A notificação não diz qual vídeo é:
     * se só um vídeo foi postado nas últimas 48 h, marca ele; se foram vários, pergunta qual (1 toque).
     */
    fun igHit(c: Context, likes: Int, text: String) {
        val k = if (likes >= igMin(c) * 5) "top" else "good"
        val key = "ig_hit_" + text.hashCode() + k
        if (p(c).getBoolean(key, false)) return
        p(c).edit().putBoolean(key, true).apply()
        val now = System.currentTimeMillis()
        val recent = VideoExport.library(c).filter { it.optInt("posts") > 0 && now - it.optLong("lastPost") < 48 * 3600_000L }.sortedByDescending { it.optLong("lastPost") }
        if (recent.isEmpty()) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tn_hit", "Posts que bombaram (Threads)", NotificationManager.IMPORTANCE_HIGH))
        val e = if (k == "top") "💎" else "🔥"
        if (recent.size == 1) {
            val o = recent[0]; Likes.record(c, "video", o.optString("id"), likes, "notif"); val cur = if (o.optLong("notaAt") == o.optLong("lastPost")) o.optString("nota") else ""
            if (cur != k && cur != "top") RateLater.apply(c, o.optString("id"), k)
            val nb = Notification.Builder(c, "tn_hit").setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("$e Reel com $likes+ curtidas")
                .setContentText("Marquei o vídeo como $e: " + o.optString("texto").replace('\n', ' ').ifBlank { "sem texto" }.take(60)).setAutoCancel(true)
            try { nm.notify(("ig" + text).hashCode(), nb.build()) } catch (x: SecurityException) { }
            return
        }
        val nb = Notification.Builder(c, "tn_hit").setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("$e Um Reel passou de $likes curtidas — qual foi?")
            .setContentText("Toque no vídeo certo pra eu marcar $e (1º = o mais recente)").setAutoCancel(true)
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        recent.take(3).forEachIndexed { i, o ->
            val id = o.optString("id")
            val pi = PendingIntent.getBroadcast(c, ("igr" + id + k).hashCode(), Intent(c, RateReceiver::class.java).setAction(k).putExtra("id", id)
                .setData(android.net.Uri.parse("nota://ig/$id/$k")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val label = "${i + 1}º " + fmt.format(java.util.Date(o.optLong("lastPost"))) + " " + o.optString("texto").replace('\n', ' ').take(10)
            nb.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        try { nm.notify(("ig" + text).hashCode(), nb.build()) } catch (x: SecurityException) { }
    }
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("tn_on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("tn_on", v).apply()
    fun min(c: Context) = p(c).getInt("tn_min", 50)
    fun setMin(c: Context, v: Int) = p(c).edit().putInt("tn_min", v).apply()
    /** "likes" = só filtra curtidas; "all" = some tudo do Threads, menos as curtidas acima do limite. */
    fun mode(c: Context) = p(c).getString("tn_mode", "likes") ?: "likes"
    fun setMode(c: Context, v: String) = p(c).edit().putString("tn_mode", v).apply()
    fun autoRate(c: Context) = p(c).getBoolean("tn_rate", true)
    fun setAutoRate(c: Context, v: Boolean) = p(c).edit().putBoolean("tn_rate", v).apply()

    fun hasAccess(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return s.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }
    }
    fun openAccess(c: Context) = try { c.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    fun openThreadsSettings(c: Context) = try {
        c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, THREADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }

    private val LIKE = Regex("curt|like|❤|gost", RegexOption.IGNORE_CASE)
    private val OTHERS = Regex("(\\d[\\d.,]*)\\s*(mil|k)?\\s*(outras|outros|others|pessoas|people)", RegexOption.IGNORE_CASE)
    private val NUM = Regex("(\\d[\\d.,]*)\\s*(mil|k)?\\s*(curtidas|likes|curtiram)", RegexOption.IGNORE_CASE)

    private fun num(s: String, mult: String?): Int {
        val m = if (mult.isNullOrEmpty()) 1.0 else 1000.0
        val clean = if (m > 1) s.replace(',', '.') else s.replace(".", "").replace(",", "")
        return ((clean.toDoubleOrNull() ?: 0.0) * m).toInt()
    }

    /** Quantas curtidas a notificação diz (null = não é de curtida). */
    fun likes(text: String): Int? {
        if (!LIKE.containsMatchIn(text)) return null
        OTHERS.find(text)?.let { return num(it.groupValues[1], it.groupValues[2]) + 1 }
        NUM.find(text)?.let { return num(it.groupValues[1], it.groupValues[2]) }
        return 1 // "fulano curtiu sua thread"
    }

    /** Diário das últimas notificações do Threads (pra ver o que o filtro decidiu). */
    fun log(c: Context, text: String, likes: Int?, keep: Boolean) {
        val a = try { org.json.JSONArray(p(c).getString("tn_log", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        val l = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        l.add(0, org.json.JSONObject().put("at", System.currentTimeMillis()).put("t", text.replace('\n', ' ').take(90)).put("l", likes ?: -1).put("k", keep))
        p(c).edit().putString("tn_log", org.json.JSONArray(l.take(8)).toString()).putLong("tn_seen", System.currentTimeMillis()).apply()
    }
    fun logText(c: Context): String {
        val a = try { org.json.JSONArray(p(c).getString("tn_log", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        if (a.length() == 0) return "Ainda não chegou nenhuma notificação do Threads desde que o filtro ligou."
        return (0 until a.length()).map { a.getJSONObject(it) }.joinToString("\n") { o ->
            val h = android.text.format.DateFormat.format("HH:mm", o.optLong("at"))
            val l = o.optInt("l"); val k = o.optBoolean("k")
            "$h " + (if (k) "✓ ficou" else "🔕 sumiu") + (if (l >= 0) " ($l curtida(s))" else " (não é curtida)") + ": " + o.optString("t").take(60)
        }
    }
    fun live(c: Context) = p(c).getBoolean("tn_live", false)
    fun rebind(c: Context) {
        if (!hasAccess(c)) return
        try { NotificationListenerService.requestRebind(ComponentName(c, ThreadsListener::class.java)) } catch (e: Exception) { }
        // força o sistema a religar o serviço (desliga e liga o componente)
        try {
            val pm = c.packageManager; val cn = ComponentName(c, ThreadsListener::class.java)
            pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (e: Exception) { }
    }

    fun countSilenced(c: Context) {
        val today = android.text.format.DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString()
        val n = if (p(c).getString("tn_day", "") == today) p(c).getInt("tn_n", 0) + 1 else 1
        p(c).edit().putString("tn_day", today).putInt("tn_n", n).apply()
    }
    fun silencedToday(c: Context): Int {
        val today = android.text.format.DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString()
        return if (p(c).getString("tn_day", "") == today) p(c).getInt("tn_n", 0) else 0
    }

    /** Post passou do limite: avisa com som e marca 🔥 no post que bate com o trecho. */
    fun hit(c: Context, likes: Int, text: String, open: PendingIntent?) {
        val k = if (likes >= min(c) * 5) "top" else "good"
        val key = "tn_hit_" + text.hashCode() + "_" + k
        if (p(c).getBoolean(key, false)) return
        p(c).edit().putBoolean(key, true).apply()
        val e = if (k == "top") "💎" else "🔥"
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tn_hit", "Posts que bombaram (Threads)", NotificationManager.IMPORTANCE_HIGH))
        val nb = Notification.Builder(c, "tn_hit").setSmallIcon(android.R.drawable.star_on).setAutoCancel(true)
        open?.let { nb.setContentIntent(it) }
        if (!autoRate(c)) {
            nb.setContentTitle("$e Post com $likes+ curtidas no Threads").setContentText(text.replace('\n', ' ').take(100))
            try { nm.notify(("hit" + text).hashCode(), nb.build()) } catch (x: SecurityException) { }
            return
        }
        // 1) a notificação mostra um pedaço da legenda? acha o post exato
        val exact = rateMatching(c, text, k)
        exact?.let { Likes.record(c, "fila", it, likes, "notif") }
        if (exact != null) {
            nb.setContentTitle("$e Post com $likes+ curtidas no Threads").setContentText("Marquei como $e: " + text.replace('\n', ' ').take(90))
            try { nm.notify(("hit" + text).hashCode(), nb.build()) } catch (x: SecurityException) { }
            return
        }
        // 2) senão: fotos enviadas nas últimas 48 h
        val now = System.currentTimeMillis()
        val sent = { o: org.json.JSONObject -> maxOf(o.optLong("sentAt"), o.optLong("postedAt")) }
        val recent = PostQueue.load(c).filter { now - sent(it) < 48 * 3600_000L && sent(it) > 0 }.sortedByDescending { sent(it) }
        if (recent.size == 1) {
            val o = recent[0]; val id = o.optString("id")
            Likes.record(c, "fila", id, likes, "notif")
            val cur = if (o.optLong("notaAt") >= sent(o)) o.optString("nota") else ""
            if (cur != k && cur != "top") RateLater.applyPhoto(c, id, k)
            nb.setContentTitle("$e Post com $likes+ curtidas no Threads").setContentText("Marquei a foto como $e: " + PostQueue.captionOf(o).replace('\n', ' ').take(70))
            try { android.graphics.BitmapFactory.decodeFile(PostQueue.imageFile(c, id).path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.let { nb.setLargeIcon(it) } } catch (x: Exception) { }
            try { nm.notify(("hit" + text).hashCode(), nb.build()) } catch (x: SecurityException) { }
            return
        }
        nb.setContentTitle("$e Um post passou de $likes curtidas no Threads — qual foi?")
        if (recent.isEmpty()) {
            nb.setContentText(text.replace('\n', ' ').take(100))
        } else {
            nb.setContentText("Toque na foto certa pra eu marcar $e (1º = a mais recente)")
            nb.setStyle(Notification.BigTextStyle().bigText(recent.take(3).mapIndexed { i, o -> "${i + 1}º " + PostQueue.captionOf(o).replace('\n', ' ').take(50) }.joinToString("\n")))
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            recent.take(3).forEachIndexed { i, o ->
                val id = o.optString("id")
                val pi = PendingIntent.getBroadcast(c, ("thr" + id + k).hashCode(), Intent(c, RateReceiver::class.java).setAction(k).putExtra("id", id).putExtra("foto", true)
                    .setData(android.net.Uri.parse("nota://th/$id/$k")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nb.addAction(Notification.Action.Builder(null, "${i + 1}º " + fmt.format(java.util.Date(sent(o))) + " " + PostQueue.captionOf(o).replace('\n', ' ').take(8), pi).build())
            }
        }
        try { nm.notify(("hit" + text).hashCode(), nb.build()) } catch (x: SecurityException) { }
    }

    /** Acha o post da fila cuja legenda aparece no texto da notificação e dá 🔥 (uma vez por post). */
    private fun rateMatching(c: Context, text: String, k: String): String? {
        val t = text.lowercase()
        val o = PostQueue.load(c).filter { it.optString("status") == "postada" }.sortedByDescending { it.optLong("postedAt") }.firstOrNull { o ->
            val cap = PostQueue.captionOf(o).lowercase().replace('\n', ' ').trim()
            cap.length >= 6 && (t.contains(cap.take(40)) || t.contains(cap.take(18)))
        } ?: return null
        val id = o.optString("id")
        val cur = if (o.optLong("notaAt") == o.optLong("postedAt")) o.optString("nota") else ""
        if (cur == k || cur == "top") return id // já tinha essa nota: achou, não mexe
        RateLater.applyPhoto(c, id, k)
        return id
    }
}

class ThreadsListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Store.prefs(this).edit().putLong("tn_conn", System.currentTimeMillis()).putBoolean("tn_live", true).apply()
        // limpa as que já estavam na barra quando ligou
        try { activeNotifications?.filter { it.packageName == ThreadsNotif.THREADS }?.forEach { handle(it) } } catch (e: Exception) { }
    }

    override fun onListenerDisconnected() {
        Store.prefs(this).edit().putBoolean("tn_live", false).apply()
        try { requestRebind(ComponentName(this, ThreadsListener::class.java)) } catch (e: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == ThreadsNotif.INSTA) { handleIg(sbn); return }
            if (sbn.packageName != ThreadsNotif.THREADS) return
            handle(sbn)
        } catch (e: Exception) { ErrLog.log(this, "leitor de notificações", e) }
    }

    private fun handleIg(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val text = textOf(n); if (text.isEmpty()) return
        val likes = ThreadsNotif.likes(text) ?: return
        if (likes >= ThreadsNotif.igMin(this)) { if (ThreadsNotif.igRate(this)) ThreadsNotif.igHit(this, likes, text) }
        else if (ThreadsNotif.igSilence(this)) { try { cancelNotification(sbn.key) } catch (e: Exception) { }; ThreadsNotif.countSilenced(this) }
    }

    private fun textOf(n: Notification): String {
        val ex = n.extras
        val parts = ArrayList<CharSequence>()
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .forEach { k -> ex.getCharSequence(k)?.let { parts += it } }
        ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { parts.addAll(it) }
        n.tickerText?.let { parts += it }
        return parts.map { it.toString() }.distinct().joinToString(" ").trim()
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val text = textOf(n)
        if (summary) { if (ThreadsNotif.on(this)) cleanSummary(sbn); return }
        if (text.isEmpty()) return
        val likes = ThreadsNotif.likes(text)
        if (!ThreadsNotif.on(this)) { // só lê as curtidas (nota automática), sem silenciar nada
            if (likes != null && likes >= ThreadsNotif.min(this)) ThreadsNotif.hit(this, likes, text, n.contentIntent)
            ThreadsNotif.log(this, text, likes, true); return
        }
        val keep = when {
            likes != null -> likes >= ThreadsNotif.min(this)
            else -> ThreadsNotif.mode(this) != "all" // não é curtida: só some no modo "tudo"
        }
        ThreadsNotif.log(this, text, likes, keep)
        if (likes != null && keep) ThreadsNotif.hit(this, likes, text, n.contentIntent)
        if (!keep) {
            try { cancelNotification(sbn.key) } catch (e: Exception) { }
            ThreadsNotif.countSilenced(this)
            n.group?.let { g -> android.os.Handler(mainLooper).postDelayed({ cleanGroup(g) }, 400) }
        }
    }

    /** Se o grupo do Threads ficou só com o resumo ("3 notificações"), apaga o resumo também. */
    private fun cleanGroup(group: String) {
        try {
            val act = activeNotifications?.filter { it.packageName == ThreadsNotif.THREADS && it.notification.group == group } ?: return
            if (act.all { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }) act.forEach { cancelNotification(it.key) }
        } catch (e: Exception) { }
    }
    private fun cleanSummary(sbn: StatusBarNotification) {
        val g = sbn.notification.group ?: return
        android.os.Handler(mainLooper).postDelayed({ cleanGroup(g) }, 800)
    }
}
