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
    fun record(c: Context, kind: String, id: String, likes: Int, src: String, add: Boolean = false) {
        if (likes <= 0 || likes > ThreadsApi.MAXV) return
        JsonFile.edit(file(c)) { l ->
            val o = l.firstOrNull { it.optString("kind") == kind && it.optString("id") == id && System.currentTimeMillis() - it.optLong("first") < 3 * 86400_000L }
            if (o == null) l.add(0, JSONObject().put("kind", kind).put("id", id).put("likes", likes).put("singles", if (add) 1 else 0).put("first", System.currentTimeMillis()).put("at", System.currentTimeMillis()).put("src", src))
            else if (add) { val s = o.optInt("singles") + 1; o.put("singles", s); if (s > o.optInt("likes")) o.put("likes", s); o.put("at", System.currentTimeMillis()) } // curtidas avulsas: soma
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
        if (ThreadsApi.on(c)) try { ThreadsApi.sync(c) } catch (x: Exception) { ErrLog.log(c, "API Threads", x) }
        else try { ThreadsApi.process(c) } catch (x: Exception) { ErrLog.log(c, "números da tela", x) }
        val sends = SendLog.all(c).filter { now - it.optLong("at") < 3 * 86400_000L && it.optString("nota").isEmpty() }
        var auto = 0
        // 1) dá nota com as curtidas que já se sabe
        sends.forEach { e ->
            val kind = if (e.optString("kind") == "video") "video" else "fila"
            val lk = Likes.of(c, kind, e.optString("id")) ?: return@forEach
            if (lk.optLong("first") < e.optLong("at") - 60_000L) return@forEach // curtidas de um post anterior
            val k = Likes.verdict(c, kind, lk.optInt("likes"), (now - e.optLong("at")) / 3600_000.0) ?: return@forEach
            try { SendLog.rate(c, e, k); auto++ } catch (x: Exception) { ErrLog.log(c, "vigia", x) }
        }
        try { Auto.captionsTick(c) } catch (x: Exception) { ErrLog.log(c, "vigia", x) }
        try { Promo.tick(c) } catch (x: Exception) { ErrLog.log(c, "divulgação", x) }
        try { Growth.tick(c) } catch (x: Exception) { ErrLog.log(c, "vigia do crescimento", x) }
        try { Archive.tick(c)?.let { p(c).edit().putString("health_last", it).apply() } } catch (x: Exception) { ErrLog.log(c, "vigia", x) }
        try { Stock.check(c) } catch (x: Exception) { ErrLog.log(c, "vigia", x) }
        try { Burst.autoTick(c) } catch (x: Exception) { ErrLog.log(c, "rajada", x) }
        try { Saturation.tick(c) } catch (x: Exception) { ErrLog.log(c, "saturação", x) }
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
            ThreadsNotif.suggestedMin(c, "fila")?.let { s -> if (ThreadsNotif.min(c) > s * 3) reasons += "🎚 o limite do Threads (${ThreadsNotif.min(c)}) está bem acima do que seus posts costumam ter: sugiro $s (Ajustes › 🔕)" }
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
/** 🤖 Coleta automática (macro): liga/desliga e guarda o resultado. */
object Macro {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("macro_on", false)
    fun handle(c: Context) = (p(c).getString("th_handle", null) ?: ThreadsApi.user(c)).trim().removePrefix("@")
    fun setHandle(c: Context, h: String) = p(c).edit().putString("th_handle", h.trim().removePrefix("@")).apply()
    fun maxPosts(c: Context) = p(c).getInt("macro_max", 15)
    fun lastRun(c: Context) = p(c).getString("macro_last", "") ?: ""
    fun app(c: Context) = p(c).getString("macro_app", ThreadsNotif.THREADS) ?: ThreadsNotif.THREADS
    fun igHandle(c: Context) = (p(c).getString("ig_handle", null) ?: handle(c)).trim().removePrefix("@")
    fun setIgHandle(c: Context, h: String) = p(c).edit().putString("ig_handle", h.trim().removePrefix("@")).apply()
    fun manualToast(c: Context) = p(c).getBoolean("reader_toast", true)
    /** Também abre posts que você fez fora do app (guarda os números pela legenda). */
    fun readAll(c: Context) = p(c).getBoolean("macro_all", true)
    fun setReadAll(c: Context, v: Boolean) = p(c).edit().putBoolean("macro_all", v).apply()
    fun setManualToast(c: Context, v: Boolean) = p(c).edit().putBoolean("reader_toast", v).apply()

    fun startInstagram(c: Context): String {
        if (!RateWatch.readerOn(c)) return "Ligue o Leitor de tela primeiro (✅ Configuração)"
        p(c).edit().putBoolean("macro_on", true).putString("macro_app", ThreadsNotif.INSTA).putLong("macro_start", System.currentTimeMillis()).apply()
        notifyRunning(c)
        val h = igHandle(c)
        val i = if (h.isNotEmpty()) Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/$h/reels/")).setPackage(ThreadsNotif.INSTA)
                else c.packageManager.getLaunchIntentForPackage(ThreadsNotif.INSTA)
        return try { c.startActivity(i!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); if (h.isEmpty()) "Abra a aba Reels do SEU perfil: a coleta começa sozinha" else "Abrindo seus Reels… não toque na tela" }
        catch (e: Exception) { stop(c, "não consegui abrir o Instagram"); "Não consegui abrir o Instagram" }
    }

    fun start(c: Context): String {
        p(c).edit().putString("macro_app", ThreadsNotif.THREADS).apply()
        if (!RateWatch.readerOn(c)) return "Ligue o Leitor de tela primeiro (✅ Configuração)"
        p(c).edit().putBoolean("macro_on", true).putLong("macro_start", System.currentTimeMillis()).apply()
        notifyRunning(c)
        val h = handle(c)
        val i = if (h.isNotEmpty()) Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.threads.com/@$h")).setPackage(ThreadsNotif.THREADS)
                else c.packageManager.getLaunchIntentForPackage(ThreadsNotif.THREADS)
        return try { c.startActivity(i!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); if (h.isEmpty()) "Abra o SEU perfil no Threads: a coleta começa sozinha" else "Abrindo seu perfil… não toque na tela por uns minutos" }
        catch (e: Exception) { stop(c, "não consegui abrir o Threads"); "Não consegui abrir o Threads" }
    }

    fun stop(c: Context, why: String) {
        p(c).edit().putBoolean("macro_on", false).putString("macro_last", android.text.format.DateFormat.format("dd/MM HH:mm", System.currentTimeMillis()).toString() + " · " + why).apply()
        c.getSystemService(NotificationManager::class.java).cancel(95)
        try { ThreadsApi.process(c) } catch (e: Exception) { ErrLog.log(c, "coleta: processar", e) }
        val nf = ThreadsApi.withNumbers(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        val b = Notification.Builder(c, "estoque").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("🤖 Coleta terminada · 📚 banco atualizado")
            .setContentText(why).setStyle(Notification.BigTextStyle().bigText("$why\n📚 $nf foto(s) com números reais. Notas, modelo e sorteio atualizados: as fotos e legendas que rendem passam a sair mais.")).setAutoCancel(true)
        try { nm.createNotificationChannel(NotificationChannel("estoque", "Estoque", NotificationManager.IMPORTANCE_DEFAULT)); nm.notify(94, b.build()) } catch (e: Exception) { }
    }

    private fun notifyRunning(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("macro", "Coleta automática", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(c, 99, Intent(c, AdminReceiver::class.java).setAction("macro_stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, "macro").setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle(if (app(c) == ThreadsNotif.INSTA) "🤖 Coletando números dos seus Reels…" else "🤖 Coletando números do Threads…")
            .setContentText("Rolando devagar e abrindo só os seus posts. Toque em Parar quando quiser.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "⏹ Parar", stop).build()).build()
        try { nm.notify(95, n) } catch (e: Exception) { }
    }
}

class ScreenReader : AccessibilityService() {
    private var last = 0L

    // ---------- 🤖 macro: rola o perfil, abre cada post SEU, abre a atividade, lê e volta
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var running = false
    private var state = "PROFILE"
    private val done = HashSet<String>()
    private var opened = 0; private var noNew = 0; private var triedInsights = false; private var offApp = 0
    private var lastTarget: AccessibilityNodeInfo? = null
    private var currentCap = ""
    private var waited = false

    private val TIME = Regex("^\\d+\\s*(s|min|h|d|sem|w|m)$|^(ontem|yesterday)$", RegexOption.IGNORE_CASE)
    /** Outro post seu na tela (só abaixo das abas do perfil, pra nunca tocar na bio, link ou botões). */
    private fun otherPost(all: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val tab = all.firstOrNull { val t = txt(it); t == "respostas" || t == "replies" || t == "threads" }?.let { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.bottom } ?: return null
        return all.firstOrNull { n ->
            val t = (n.text ?: "").toString().trim(); val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            r.top > tab + dpx(30) && t.length >= 12 && !TIME.matches(t) && !t.contains("seguidores") && !t.startsWith("@") && !t.startsWith("http") &&
                core(t).take(24) !in done && clickable(n) != null
        }
    }
    private var appPkg = ThreadsNotif.THREADS

    // ---------- 👀 visual: selo no topo, moldura no post, flash verde quando capta
    private var pill: android.widget.TextView? = null
    private var frame: android.view.View? = null
    private val wm by lazy { getSystemService(android.view.WindowManager::class.java) }
    private fun dpx(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int) = android.view.WindowManager.LayoutParams(w, h, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT)

    private fun show(msg: String, color: Int = 0xEE1C1C24.toInt()) {
        try {
            val v = pill ?: android.widget.TextView(this).apply {
                textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding(dpx(16), dpx(10), dpx(16), dpx(10)); gravity = android.view.Gravity.CENTER
                elevation = dpx(8).toFloat()
            }.also { tv ->
                wm.addView(tv, lp(-2, -2).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; y = dpx(70) }); pill = tv
            }
            v.text = msg
            v.background = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dpx(22).toFloat(); setStroke(dpx(2), 0x55FFFFFF) }
        } catch (e: Exception) { }
    }

    /** Moldura em volta do post que vai abrir. */
    private fun mark(n: AccessibilityNodeInfo) {
        try {
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            frame?.let { wm.removeView(it) }
            val v = android.view.View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setColor(0x2236D399); cornerRadius = dpx(12).toFloat(); setStroke(dpx(3), 0xFF36D399.toInt()) } }
            wm.addView(v, lp(r.width(), r.height()).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = r.left; y = r.top })
            frame = v
            h.postDelayed({ try { if (frame === v) { wm.removeView(v); frame = null } } catch (e: Exception) { } }, 900)
        } catch (e: Exception) { }
    }

    private fun buzz() = try { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(35, 120)) } catch (e: Exception) { }

    private fun captured(m: Map<String, Int>, manual: Boolean = false) {
        val parts = listOfNotNull(m["views"]?.let { "👁 $it" }, m["likes"]?.let { "❤ $it" }, m["replies"]?.let { "💬 $it" }, m["reposts"]?.let { "🔁 $it" },
            m["shares"]?.let { "↗ $it" }, m["saves"]?.let { "🔖 $it" }, m["fol"]?.let { "👥 +$it" })
        show((if (manual) "✓ Captado  " else "✓ Post $opened/${Macro.maxPosts(this)} captado  ") + parts.joinToString("  "), 0xEE15803D.toInt())
        buzz(); capturedNow = true
    }
    private var capturedNow = false
    private var lastManual = ""
    private var manualPkg = ""

    private fun clearOverlay() {
        try { pill?.let { wm.removeView(it) } } catch (e: Exception) { }
        try { frame?.let { wm.removeView(it) } } catch (e: Exception) { }
        pill = null; frame = null
    }

    /** Toca como um dedo no TEXTO da legenda (perto do começo, longe dos botões). Só toca se estiver bem visível. */
    private fun tapNode(n: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect(); n.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        if (r.height() < dpx(12) || r.width() < dpx(40)) return false
        val top = maxOf(r.top, (dm.heightPixels * 0.14).toInt()); val bottom = minOf(r.bottom, (dm.heightPixels * 0.86).toInt())
        if (bottom - top < dpx(12)) return false
        val x = (r.left + minOf(r.width() * 0.35f, dpx(120).toFloat())); val y = top + minOf((bottom - top) / 2f, dpx(14).toFloat())
        val path = android.graphics.Path().apply { moveTo(x, y) }
        return try { dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null) } catch (e: Exception) { false }
    }
    private var sigBefore = ""
    private fun sig(all: List<AccessibilityNodeInfo>) = all.take(80).joinToString("|") { txt(it).take(20) }.hashCode().toString()
    private var retried = false

    private fun rnd(a: Long, b: Long) = (a..b).random()
    private fun next(ms: Long) { h.postDelayed({ step() }, ms) }

    private fun startMacro() {
        if (running) return
        running = true; state = "PROFILE"; done.clear(); opened = 0; noNew = 0; offApp = 0
        igPrevKey = ""; igSame = 0; igPtr = 0; igNotMine = 0; gridViews = emptyList(); igIndex = -1; igSends = null
        show("🤖 Coleta começando… não toque na tela")
        next(3500)
    }

    private fun finish(why: String) {
        running = false; h.removeCallbacksAndMessages(null)
        show("🤖 $why\n📚 Banco atualizado: o sorteio já usa esses números", 0xEE1D4ED8.toInt())
        h.postDelayed({ clearOverlay() }, 3500)
        Macro.stop(this, why)
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) { if (n == null || d > 40 || out.size > 900) return; out += n; for (k in 0 until n.childCount) walk(n.getChild(k), d + 1) }
        walk(root, 0); return out
    }
    private fun txt(n: AccessibilityNodeInfo) = norm((n.text ?: n.contentDescription ?: "").toString())
    private fun clickable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { var x = n; var d = 0; while (x != null && d < 8) { if (x.isClickable) return x; x = x.parent; d++ }; return null }

    private fun step() {
        if (!running) return
        if (!Macro.on(this)) { finish("parada por você"); return }
        val st = Store.prefs(this).getLong("macro_start", 0)
        if (System.currentTimeMillis() - st > 5 * 60_000L) { finish("tempo máximo (5 min): $opened post(s) lidos"); return }
        if (opened >= Macro.maxPosts(this)) { finish("✓ $opened post(s) lidos"); return }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != appPkg) {
            if (++offApp >= 4) { finish("saiu do " + (if (appPkg == ThreadsNotif.INSTA) "Instagram" else "Threads") + ": $opened post(s) lidos"); return }
            next(2500); return
        }
        offApp = 0
        val all = nodes(root).let { if (appPkg == ThreadsNotif.INSTA) vis(it) else it }
        // lê o que está na tela agora (números, seguidores)
        val texts = all.mapNotNull { (it.text ?: it.contentDescription)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        if (appPkg == ThreadsNotif.INSTA) { try { igStep(all, texts) } catch (x: Exception) { ErrLog.log(this, "coleta instagram", x); next(3000) }; return }
        val got = try { scan(appPkg, texts) } catch (x: Exception) { emptyList() }
        if (state != "PROFILE" && !capturedNow) got.maxByOrNull { it.size }?.let { captured(it) }
        val caps = SendLog.all(this).filter { it.optString("kind") == "fila" && System.currentTimeMillis() - it.optLong("at") < 30 * 86400_000L }
            .map { it.optString("cap") }.filter { core(it).length >= 8 }.distinctBy { core(it).take(24) }
        when (state) {
            "PROFILE" -> {
                // 1) posts do app (pela legenda)  2) qualquer outro post SEU no perfil (abaixo das abas Threads/Respostas)
                var target = all.firstOrNull { n -> val t = txt(n); caps.any { k -> sameCap(t, k) && core(k).take(24) !in done } }
                if (target == null && Macro.readAll(this)) target = otherPost(all)
                if (target != null) {
                    val t = txt(target); done += (caps.firstOrNull { k -> sameCap(t, k) }?.let { core(it).take(24) } ?: core(t).take(24))
                    currentCap = (target.text ?: target.contentDescription ?: "").toString()
                    val cl = clickable(target)
                    mark(target)
                    sigBefore = sig(all); retried = false
                    // 1º: toque de dedo no texto da legenda; se não der, clique pelo Android
                    if (tapNode(target) || cl?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        opened++; noNew = 0; state = "POST"; triedInsights = false; capturedNow = false; lastTarget = target; waited = false
                        show("🎯 Abrindo post $opened/${Macro.maxPosts(this)}…"); next(rnd(2800, 4200)); return
                    }
                }
                // nenhum post novo visível: desce um pouco
                val sc = all.firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                val ok = sc?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
                if (!ok) swipeUp()
                show("🔽 Descendo… ($opened/${Macro.maxPosts(this)} lidos)")
                if (++noNew >= 7) { finish("✓ fim dos posts recentes: $opened post(s) lidos"); return }
                next(rnd(2500, 4000))
            }
            "POST" -> {
                if (!capturedNow && !waited) { waited = true; next(1500); return } // ainda carregando: espera e lê de novo
                // a tela não mudou? o toque não abriu o post: tenta 1x pelo Android, senão pula
                if (sig(all) == sigBefore) {
                    if (!retried) { retried = true; show("↻ Não abriu, tentando de novo…", 0xEEB45309.toInt()); clickable(lastTarget)?.performAction(AccessibilityNodeInfo.ACTION_CLICK); next(rnd(2500, 3500)); return }
                    show("⚠ Não consegui abrir esse post, pulando", 0xEEB45309.toInt()); opened--; state = "PROFILE"; next(rnd(2000, 3000)); return
                }
                // abre "Ver atividade"/"Insights" se existir
                val ins = if (triedInsights) null else all.firstOrNull { n -> Regex("ver atividade|view activity|insights|ver insights|atividade da publica|post activity|estatísticas|ver estat").containsMatchIn(txt(n)) }
                triedInsights = true
                val cl = clickable(ins)
                if (cl != null) mark(cl)
                if (ins != null && (tapNode(ins) || cl?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)) { show("📊 Abrindo a atividade do post…"); state = "INSIGHTS"; next(rnd(3000, 4500)); return }
                if (!capturedNow) { orphan(texts); if (!capturedNow) { dump(texts); show("⚠ Post $opened: não achei números nesta tela", 0xEEB45309.toInt()) } }
                performGlobalAction(GLOBAL_ACTION_BACK); state = "PROFILE"; next(rnd(2500, 3800))
            }
            "INSIGHTS" -> {
                if (!capturedNow) { orphan(texts); if (!capturedNow) { dump(texts); show("⚠ Post $opened: não achei números na atividade", 0xEEB45309.toInt()) } }
                performGlobalAction(GLOBAL_ACTION_BACK); state = "POST_BACK"; next(rnd(2000, 3000))
            }
            "POST_BACK" -> { performGlobalAction(GLOBAL_ACTION_BACK); state = "PROFILE"; next(rnd(2500, 3800)) }
        }
    }

    private val doneCells = HashSet<String>()

    // ================= 📸 Instagram: abre o 1º Reel da grade e vai deslizando pros próximos =================
    private var igPrevKey = ""; private var igSame = 0; private var igPtr = 0; private var igNotMine = 0; private var igIndex = -1
    private var gridViews: List<Int?> = emptyList()
    private var igSends: List<org.json.JSONObject>? = null
    private var igGridTries = 0
    private var igCurJ = -2

    private fun rect(n: AccessibilityNodeInfo) = android.graphics.Rect().also { n.getBoundsInScreen(it) }

    /** Só o que está de fato na tela (o Instagram deixa o Reel anterior e o próximo carregados por trás). */
    private fun vis(all: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val dm = resources.displayMetrics
        return all.filter { n -> n.isVisibleToUser && rect(n).let { r -> r.height() > 0 && r.bottom > 0 && r.top < dm.heightPixels && r.right > 0 && r.left < dm.widthPixels } }
    }

    /** Envios de vídeo (mais novo primeiro) pra casar com os Reels. */
    private fun videoSends() = igSends ?: SendLog.all(this).filter { it.optString("kind") == "video" && System.currentTimeMillis() - it.optLong("at") < 45 * 86400_000L &&
        (it.optBoolean("ok") || it.optString("nota").isNotEmpty()) }.sortedByDescending { it.optLong("at") }.distinctBy { it.optString("id") + "|" + it.optLong("at") / 3_600_000L }.also { igSends = it }

    /** Células da grade de Reels: pela forma (3 colunas, mais altas que largas) ou pela descrição. */
    private fun gridCells(all: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val dm = resources.displayMetrics; val w = dm.widthPixels
        val tabs = all.filter { Regex("^(reels|publica[cç][oõ]es|posts|marca[cç][oõ]es|tagged|reels tab)$").matches(txt(it)) }.map { rect(it).bottom }.maxOrNull() ?: (dm.heightPixels * 0.15).toInt()
        val c = all.filter { n ->
            val r = rect(n); val d = (n.contentDescription ?: "").toString().lowercase()
            n.isClickable && r.top >= tabs - dpx(4) && r.bottom <= dm.heightPixels * 0.97 && r.height() > dpx(40) &&
                ((r.width() in (w * 0.24).toInt()..(w * 0.36).toInt() && r.height() >= r.width() * 0.9) || Regex("reel|v[ií]deo|video|linha|row|coluna|column").containsMatchIn(d) && r.width() < w / 2)
        }.distinctBy { val r = rect(it); "${r.left / dpx(20)}:${r.top / dpx(20)}" }
        return c.sortedWith(compareBy({ rect(it).top / dpx(40) }, { rect(it).left }))
    }

    /** Visualizações que aparecem em cima de cada célula da grade (▶ 12,3 mil). */
    private fun cellViews(cell: AccessibilityNodeInfo, all: List<AccessibilityNodeInfo>): Int? {
        val cr = rect(cell)
        Regex("(\\d[\\d.,]*\\s*(?:mil|k|mi)?)\\s*(visualiza|views|reprodu|plays)", RegexOption.IGNORE_CASE).find(norm((cell.contentDescription ?: "").toString()))?.let { return numOf(it.groupValues[1]) }
        return all.filter { n -> val r = rect(n); cr.contains(r) && n !== cell }.firstNotNullOfOrNull { numOf(txt(it)) }
    }

    private fun looksLikeReel(all: List<AccessibilityNodeInfo>) = all.any { Regex("^(curtir|like|comentar|comment|ver insights|view insights|enviar|share|compartilhar)$").matches(txt(it)) }

    /** Legenda do Reel aberto: o maior texto da metade de baixo que não é botão, número, áudio ou o @. */
    private fun igCaption(all: List<AccessibilityNodeInfo>): String {
        val dm = resources.displayMetrics; val hdl = Macro.igHandle(this).lowercase()
        val bad = Regex("[aá]udio original|original audio|seguir|follow|ver insights|view insights|turbinar|boost|adicione um coment|add a comment|ver tradu|see translation|patrocinado|sponsored|•")
        val c = all.mapNotNull { n ->
            val t = (n.text ?: return@mapNotNull null).toString().trim(); val r = rect(n); val l = norm(t)
            if (r.top < dm.heightPixels * 0.5 || r.left > dm.widthPixels * 0.7 || t.length < 8 || numOf(l) != null || TIME.matches(l) || bad.containsMatchIn(l) || l == hdl) null
            else (if (hdl.isNotEmpty() && l.startsWith("$hdl ")) t.substring(hdl.length).trim() else t)
        }
        return c.maxByOrNull { it.length } ?: ""
    }

    /** Números da tela do Reel: rótulo perto do número, botões da coluna direita na ordem, e "curtido por X e outras N pessoas". */
    private fun reelNums(all: List<AccessibilityNodeInfo>, texts: List<String>): MutableMap<String, Int> {
        val nt = texts.map { norm(it) }
        val m = pairs(nt, 0, nt.size).toMutableMap()
        nt.forEach { t ->
            Regex("(?:outras|and)\\s+(\\d[\\d.,]*\\s*(?:mil|k|mi)?)\\s+(?:pessoas|others)").find(t)?.let { mm -> numOf(mm.groupValues[1])?.let { m.putIfAbsent("likes", it + 1) } }
            Regex("^(curtir|like|curtidas?|likes?|comentar|comment|coment[aá]rios?|comments?|enviar|share|compartilhar|repostar|repost|salvar|save)[,:]?\\s+(\\d[\\d.,]*\\s*(?:mil|k|mi)?)$").find(t)?.let { mm ->
                val k = when { mm.groupValues[1].startsWith("cur") || mm.groupValues[1].startsWith("lik") -> "likes"; mm.groupValues[1].startsWith("com") -> "replies"
                    mm.groupValues[1].startsWith("rep") -> "reposts"; mm.groupValues[1].startsWith("sa") -> "saves"; else -> "shares" }
                numOf(mm.groupValues[2])?.let { m.putIfAbsent(k, it) } }
        }
        if (m["likes"] == null) { // sem rótulo: números da coluna da direita, de cima pra baixo = curtidas, comentários, compartilhamentos
            val dm = resources.displayMetrics
            val col = all.filter { rect(it).left > dm.widthPixels * 0.75 && rect(it).top > dm.heightPixels * 0.3 }.mapNotNull { n -> numOf(txt(n))?.let { rect(n).top to it } }.sortedBy { it.first }.map { it.second }
            listOf("likes", "replies", "shares").forEachIndexed { i, k -> col.getOrNull(i)?.let { m.putIfAbsent(k, it) } }
        }
        return m
    }

    private fun igStep(all: List<AccessibilityNodeInfo>, texts: List<String>) {
        when (state) {
            "PROFILE" -> igProfile(all)
            "IGREEL" -> {
                if (!waited) { waited = true; next(1400); return } // deixa o Reel carregar
                if (sig(all) == sigBefore) {
                    if (!retried) { retried = true; show("↻ Não abriu, tentando de novo…", 0xEEB45309.toInt()); clickable(lastTarget)?.performAction(AccessibilityNodeInfo.ACTION_CLICK); next(rnd(2500, 3500)); return }
                    if (igIndex < 0) { show("⚠ Não consegui abrir o Reel", 0xEEB45309.toInt()); state = "PROFILE"; next(rnd(2000, 3000)); return }
                    if (++igSame >= 2) { finish("✓ fim dos seus Reels: $opened lido(s)"); return }
                    igSwipe(); return
                }
                val cap = igCaption(all)
                val hdl = Macro.igHandle(this).lowercase()
                if (hdl.isNotEmpty() && all.none { val t = txt(it); t == hdl || t.startsWith("$hdl ") || t == "@$hdl" }) {
                    if (++igNotMine >= 2) { finish("✓ acabaram os seus Reels: $opened lido(s)"); return }
                } else igNotMine = 0
                val nums = reelNums(all, texts)
                val key = core(cap).take(24).ifEmpty { "n:" + nums.entries.sortedBy { it.key }.joinToString() }
                if (key == igPrevKey) { if (++igSame >= 2) { finish("✓ fim dos seus Reels: $opened lido(s)"); return }; igSwipe(); return }
                igSame = 0; igPrevKey = key; igIndex++; opened++; capturedNow = false; currentCap = cap; igCurJ = -2
                gridViews.getOrNull(igIndex)?.let { nums.putIfAbsent("views", it) }
                igSave(nums, "tela")
                // abre "Ver insights" (reproduções, salvos, compartilhamentos, seguidores)
                val ins = all.firstOrNull { Regex("^(ver insights|view insights|insights|ver estat[ií]sticas)$").matches(txt(it)) }
                if (ins != null && (tapNode(ins) || clickable(ins)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)) {
                    mark(ins); show("📊 Abrindo insights do Reel $opened…"); state = "IGINS"; waited = false; next(rnd(2800, 3800)); return
                }
                if (!capturedNow) { dump(texts); show("⚠ Reel $opened: não achei números", 0xEEB45309.toInt()) }
                h.postDelayed({ igSwipe() }, 1200)
            }
            "IGINS" -> {
                if (!waited) { waited = true; next(1200); return }
                val nt = texts.map { norm(it) }
                val m = pairs(nt, 0, nt.size).toMutableMap()
                if (m.isNotEmpty()) igSave(m, "insights") else if (!capturedNow) dump(texts)
                performGlobalAction(GLOBAL_ACTION_BACK); state = "IGNEXT"; next(rnd(1800, 2600))
            }
            "IGNEXT" -> igSwipe()
        }
    }

    /** Guarda os números do Reel: pela legenda; sem legenda, pela ordem dos seus envios; senão, como Reel de fora do app. */
    private fun igSave(m0: Map<String, Int>, src: String) {
        val m = m0.toMutableMap(); m["follows"]?.let { m["fol"] = it; m.remove("follows") }
        if (m.isEmpty()) return
        val sends = videoSends()
        if (igCurJ == -2) { // casa este Reel uma vez só (a tela e os insights usam o mesmo)
            igCurJ = if (core(currentCap).length >= 8) sends.indexOfFirst { sameCap(currentCap, it.optString("cap")) }
                     else igPtr.takeIf { it < sends.size && core(sends[it].optString("cap")).length < 8 } ?: -1
            if (igCurJ >= 0) igPtr = igCurJ + 1
        }
        val j = igCurJ
        if (j >= 0) {
            val s = sends[j]
            m["likes"]?.let { Likes.record(this, "video", s.optString("id"), it, src) }
            ThreadsApi.upsertLocal(this, s.optLong("at"), s.optString("id"), s.optString("cap"), m, src, "video")
        } else if (Macro.readAll(this) && core(currentCap).length >= 8) {
            ThreadsApi.upsertOrphan(this, currentCap, m, src, "video")
        } else { show("✓ Reel $opened lido, mas não sei qual é (sem legenda)", 0xEEB45309.toInt()); capturedNow = true; return }
        captured(m)
    }

    /** Desliza pro próximo Reel (lado esquerdo do vídeo, longe dos botões). */
    private fun igSwipe() {
        if (!running) return
        if (opened >= Macro.maxPosts(this)) { finish("✓ $opened Reel(s) lidos"); return }
        val dm = resources.displayMetrics
        val path = android.graphics.Path().apply { moveTo(dm.widthPixels * 0.35f, dm.heightPixels * 0.72f); lineTo(dm.widthPixels * 0.35f, dm.heightPixels * 0.22f) }
        rootInActiveWindow?.let { sigBefore = sig(vis(nodes(it))) }
        try { dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 260)).build(), null, null) } catch (e: Exception) { }
        state = "IGREEL"; waited = false; retried = true
        show("⏭ Próximo Reel… ($opened/${Macro.maxPosts(this)})"); next(rnd(2600, 3600))
    }

    /** Grade de Reels do perfil: anota as visualizações de cada célula e abre a 1ª. */
    private fun igProfile(all: List<AccessibilityNodeInfo>) {
        val cells = gridCells(all)
        if (cells.isEmpty() && looksLikeReel(all)) { // o Instagram já abriu direto num Reel
            state = "IGREEL"; waited = true; sigBefore = ""; retried = true; igStep(all, all.mapNotNull { (it.text ?: it.contentDescription)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }); return
        }
        val cell = cells.firstOrNull()
        if (cell != null) {
            gridViews = cells.map { cellViews(it, all) }
            mark(cell); sigBefore = sig(all); retried = false; lastTarget = cell; waited = false; igIndex = -1
            if (tapNode(cell) || cell.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                state = "IGREEL"; show("🎯 Abrindo seu Reel mais recente…"); next(rnd(3000, 4200)); return
            }
        }
        if (++igGridTries >= 4) { igGridTries = 0; dump(all.mapNotNull { (it.text ?: it.contentDescription)?.toString() }); finish("não achei a grade de Reels: abra a aba Reels do SEU perfil e tente de novo"); return }
        show("🔎 Procurando seus Reels…"); next(rnd(2000, 3000))
    }

    /** Post que não veio do app: guarda os números pela legenda (vale pro ranking e pra IA saber que legenda rende). */
    private fun orphan(texts: List<String>) {
        if (currentCap.length < 8 || appPkg != ThreadsNotif.THREADS) return
        val nt = texts.map { norm(it) }
        val insights = nt.any { LABELS["views"]!!.matches(it) }
        val m = pairs(nt, 0, nt.size).toMutableMap()
        if (m.isEmpty()) return
        m["follows"]?.let { m["fol"] = it; m.remove("follows") }
        ThreadsApi.upsertOrphan(this, currentCap, m, if (insights) "insights" else "tela")
        captured(m)
    }

    /** Guarda o que estava na tela quando não achou números (vai pro diagnóstico). */
    private fun dump(texts: List<String>) {
        Store.prefs(this).edit().putString("tela_dump", android.text.format.DateFormat.format("dd/MM HH:mm", System.currentTimeMillis()).toString() + " [" + state + "] " +
            texts.take(70).joinToString(" | ") { it.replace('\n', ' ').take(40) }).apply()
    }

    /** Desliza o dedo de baixo pra cima, devagar (quando a lista não aceita "rolar"). */
    private fun swipeUp() {
        val dm = resources.displayMetrics
        val path = android.graphics.Path().apply { moveTo(dm.widthPixels * 0.5f, dm.heightPixels * 0.72f); lineTo(dm.widthPixels * 0.5f, dm.heightPixels * 0.32f) }
        try { dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 650)).build(), null, null) } catch (e: Exception) { }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val ep = e?.packageName?.toString()
        if (!running && Macro.on(this) && ep == Macro.app(this)) { appPkg = ep; doneCells.clear(); startMacro() }
        if (running) return // durante a coleta, quem lê é o próprio macro
        manualPkg = e?.packageName?.toString().orEmpty()
        val now = System.currentTimeMillis()
        if (now - last < 1500) return
        last = now
        val pkg = e?.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || texts.size > 600) return
            if (pkg == ThreadsNotif.INSTA && !n.isVisibleToUser) { for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1); return }
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
        }
        try { walk(root, 0) } catch (x: Exception) { return }
        Thread {
            try {
                val got = scan(pkg, texts)
                // 👀 modo manual: você mesmo rolou/abriu → aviso verde rápido
                val best = got.maxByOrNull { it.size }
                if (best != null && Macro.manualToast(this)) {
                    val key = best.toString()
                    if (key != lastManual) { lastManual = key; h.post { captured(best, manual = true); h.postDelayed({ if (!running) clearOverlay() }, 2200) } }
                }
                if (got.isNotEmpty()) h.postDelayed({ try { ThreadsApi.process(this) } catch (x: Exception) { } }, 5000)
            } catch (x: Exception) { ErrLog.log(this, "leitor de tela", x) }
        }.start()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
    /** Só letras e números (ignora emoji, aspas, pontuação, quebras e o "…mais"). */
    private fun core(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("(\\.\\.\\.|…)\\s*(mais|more)$"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun sameCap(screen: String, cap: String): Boolean {
        val a = core(screen); val b = core(cap)
        if (a.length < 8 || b.length < 8) return false
        val k = b.take(24)
        return a.startsWith(k) || (a.length >= 10 && k.startsWith(a.take(24))) || (k.length >= 14 && a.contains(k.take(18)))
    }
    private val NUM = Regex("^(\\d[\\d.,]*)\\s*(mil|k)?$", RegexOption.IGNORE_CASE)
    private val LIKEWORD = Regex("(\\d[\\d.,]*)\\s*(mil|k)?\\s*(curtidas?|likes?)", RegexOption.IGNORE_CASE)

    private fun num(s: String, m: String?): Int {
        val mult = if (m.isNullOrEmpty()) 1.0 else 1000.0
        val clean = if (mult > 1) s.replace(',', '.') else s.replace(".", "").replace(",", "")
        return ((clean.toDoubleOrNull() ?: 0.0) * mult).toInt()
    }

    private val FOLLOW = Regex("(\\d[\\d.,]*)\\s*(mil|k|mi)?\\s*(seguidores|followers)", RegexOption.IGNORE_CASE)
    private val LABELS = mapOf(
        "views" to Regex("^(visualiza[cç][oõ]es|views|visualizações|reprodu[cç][oõ]es|plays|contas alcan[cç]adas|accounts reached)$", RegexOption.IGNORE_CASE),
        "likes" to Regex("^(curtidas?|likes?|curtir|like)$", RegexOption.IGNORE_CASE),
        "replies" to Regex("^(respostas?|replies|reply|responder|coment[aá]rios?|comments?|comentar|comment)$", RegexOption.IGNORE_CASE),
        "saves" to Regex("^(salvamentos|salvos|saves?|salvar)$", RegexOption.IGNORE_CASE),
        "reposts" to Regex("^(reposts?|repostar|republica[cç][oõ]es)$", RegexOption.IGNORE_CASE),
        "quotes" to Regex("^(cita[cç][oõ]es|quotes?|citar)$", RegexOption.IGNORE_CASE),
        "shares" to Regex("^(compartilhamentos|shares?|compartilhar|enviar|envios|sends?)$", RegexOption.IGNORE_CASE),
        "follows" to Regex("^(seguidores|follows|novos seguidores|seguiram|seguimentos|follows gained)$", RegexOption.IGNORE_CASE))

    private fun numOf(t: String): Int? {
        // "57m"/"5h"/"3d" são HORÁRIOS do post (57 minutos atrás), não números: só aceita mil/k/mi/milhões
        val m = Regex("^(\\d[\\d.,]*)\\s*(mil|k|mi|milh[oõ]es|mi\\.)?$", RegexOption.IGNORE_CASE).find(t.trim()) ?: return null
        val mult = when (m.groupValues[2].lowercase().trimEnd('.')) { "mil", "k" -> 1000.0; "mi", "milhões", "milhoes" -> 1_000_000.0; else -> 1.0 }
        val s = m.groupValues[1]; val clean = if (mult > 1) s.replace(',', '.') else s.replace(".", "").replace(",", "")
        return ((clean.toDoubleOrNull() ?: return null) * mult).toInt()
    }

    /** Número de cada rótulo. Descobre se a tela mostra "Curtidas 52" ou "52 Curtidas" e usa só esse lado. */
    private fun pairs(nt: List<String>, from: Int, to: Int): Map<String, Int> {
        val out = HashMap<String, Int>()
        fun lab(t: String) = LABELS.entries.firstOrNull { it.value.matches(t) }?.key
        val idx = (from until to).filter { lab(nt[it]) != null }
        if (idx.isEmpty()) {
            // "52 curtidas" num texto só
            for (i in from until to) Regex("^(\\d[\\d.,]*\\s*(?:mil|k|mi)?)\\s+(.+)$", RegexOption.IGNORE_CASE).find(nt[i])?.let { mm ->
                lab(mm.groupValues[2])?.let { k -> numOf(mm.groupValues[1])?.let { v -> out.putIfAbsent(k, v) } } }
            return out
        }
        val after = idx.count { it + 1 < nt.size && numOf(nt[it + 1]) != null }
        val before = idx.count { it > 0 && numOf(nt[it - 1]) != null && lab(nt.getOrElse(it - 2) { "" }) == null }
        // tela "52 Curtidas": começa com número antes do 1º rótulo e o último rótulo não tem número depois
        val first = idx.first(); val last = idx.last()
        val startsWithNum = first > 0 && numOf(nt[first - 1]) != null
        val lastAlone = last + 1 >= nt.size || numOf(nt[last + 1]) == null
        val useBefore = (startsWithNum && lastAlone) || before > after
        idx.forEach { i ->
            val k = lab(nt[i]) ?: return@forEach
            val v = if (useBefore) (if (i > 0) numOf(nt[i - 1]) else null) else (if (i + 1 < nt.size) numOf(nt[i + 1]) else null)
            v?.let { out.putIfAbsent(k, it) }
        }
        for (i in from until to) Regex("^(\\d[\\d.,]*\\s*(?:mil|k|mi)?)\\s+(.+)$", RegexOption.IGNORE_CASE).find(nt[i])?.let { mm ->
            lab(mm.groupValues[2])?.let { k -> numOf(mm.groupValues[1])?.let { v -> out.putIfAbsent(k, v) } } }
        return out
    }

    private fun scan(pkg: String, texts: List<String>): List<Map<String, Int>> {
        val got = ArrayList<Map<String, Int>>()
        val video = pkg == ThreadsNotif.INSTA
        val nt = texts.map { norm(it) }
        // 1) perfil: número de seguidores (vale pros 2 apps; guardamos só o do Threads)
        if (!video) nt.firstNotNullOfOrNull { FOLLOW.find(it) }?.let { m -> numOf(m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""))?.let { ThreadsApi.addFollowers(this, it, "tela") } }
        val sends = SendLog.all(this).filter { System.currentTimeMillis() - it.optLong("at") < 30 * 86400_000L && (it.optString("kind") == "video") == video }
        if (sends.isEmpty()) return got
        val insights = nt.any { LABELS["views"]!!.matches(it) } && nt.count { t -> LABELS.values.any { it.matches(t) } } >= 3
        var found = 0
        sends.distinctBy { it.optString("cap").take(40) }.forEach { s ->
            if (core(s.optString("cap")).length < 8) return@forEach
            val idx = nt.indexOfFirst { sameCap(it, s.optString("cap")) }
            if (idx < 0) return@forEach
            found++
            // 2) tela de insights do post: pega todos os rótulos da tela; 3) feed/perfil: só os botões logo depois da legenda
            val m = if (insights) pairs(nt, 0, nt.size) else pairs(nt, idx + 1, minOf(nt.size, idx + 16))
            val mm = m.toMutableMap()
            if (!insights && mm["likes"] == null) { // sem rótulo: 1º número depois da legenda = curtidas
                for (j in idx + 1..minOf(nt.size - 1, idx + 10)) { numOf(nt[j])?.let { mm["likes"] = it }; if (mm["likes"] != null) break }
            }
            if (mm.isEmpty()) return@forEach
            mm["follows"]?.let { mm["fol"] = it; mm.remove("follows") }
            got += mm
            if (video) {
                mm["likes"]?.let { Likes.record(this, "video", s.optString("id"), it, "tela") }
                ThreadsApi.upsertLocal(this, s.optLong("at"), s.optString("id"), s.optString("cap"), mm, if (insights) "insights" else "tela", "video")
            } else {
                mm["likes"]?.let { Likes.record(this, "fila", s.optString("id"), it, "tela") }
                ThreadsApi.upsertLocal(this, s.optLong("at"), s.optString("id"), s.optString("cap"), mm, if (insights) "insights" else "tela")
            }
        }
        if (found > 0) Store.prefs(this).edit().putLong("tela_last", System.currentTimeMillis()).putInt("tela_found", found).apply()
        return got
    }

    override fun onInterrupt() {}
}
