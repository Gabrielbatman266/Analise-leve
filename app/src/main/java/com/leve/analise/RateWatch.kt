package com.leve.analise

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
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
    fun all(c: Context): List<JSONObject> = JsonFile.peek(file(c))

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

    /** Tira curtidas impossíveis: mesmo número em 3+ posts, muito acima dos seus seguidores ou 40x a sua mediana. */
    fun clean(c: Context): Int {
        var n = 0
        val fol = ThreadsApi.followersNow(c) ?: 0
        JsonFile.edit(file(c)) { l ->
            l.groupBy { it.optString("kind") }.forEach { (_, g) ->
                val vals = g.map { it.optInt("likes") }.sorted(); val med = vals.getOrNull(vals.size / 2) ?: 0
                val dup = g.filter { it.optInt("likes") >= 100 }.groupBy { it.optInt("likes") }.filterValues { it.map { o -> o.optString("id") }.distinct().size >= 3 }.keys
                g.forEach { o -> val v = o.optInt("likes")
                    if (v in dup || (fol > 0 && v > fol * 20) || (med >= 3 && vals.size >= 6 && v > med * 40)) { l.remove(o); n++ } }
            }
        }
        return n
    }

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
        val top = if (med != null) maxOf(good * 3, med * 5) else min * 6 // 💎 = fora da curva de verdade
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
        // a bolha sumiu (o celular fechou o app)? volta sozinha
        try { if (PostBar.bubbleOn(c) && FloatService.instance == null) PostBar.update(c) } catch (x: Exception) { }
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
        try { Lab.tick(c) } catch (x: Exception) { ErrLog.log(c, "laboratório", x) }
        try { TextPosts.tick(c) } catch (x: Exception) { ErrLog.log(c, "posts de texto", x) }
        try { Audience.tick(c) } catch (x: Exception) { ErrLog.log(c, "relógio da audiência", x) }
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
        val pr = goAsync(); Thread { try { try { Agenda.tick(c) } catch (e: Exception) { ErrLog.log(c, "agenda", e) }; RateWatch.run(c) } finally { pr.finish() } }.start()
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
    /** 📖 Botão "Ler agora" flutuando no Threads/Instagram. */
    fun readBtn(c: Context) = p(c).getBoolean("read_btn", true)
    fun setReadBtn(c: Context, v: Boolean) = p(c).edit().putBoolean("read_btn", v).apply()
    /** ⏸ Pausa o leitor de tela (pra testar se ele atrapalha algo no Threads). */
    fun paused(c: Context) = p(c).getLong("reader_pause", 0) > System.currentTimeMillis()
    fun pause(c: Context, ms: Long) = p(c).edit().putLong("reader_pause", if (ms > 0) System.currentTimeMillis() + ms else 0).apply()
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
    companion object { @Volatile var instance: ScreenReader? = null }
    private var last = 0L

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { instance = null; hideReadBtn(); super.onDestroy() }

    // ================= 📖 botão "Ler agora" (aparece só no Threads/Instagram) =================
    private var readBtn: android.view.View? = null
    private var readLp: android.view.WindowManager.LayoutParams? = null
    private var btnHiddenUntil = 0L
    private var forcing = false
    private var appHit = false
    private var foreignWarn = 0L
    private var lastForeign: String? = null
    private val taught = HashMap<String, Int>()
    private val fgCheck = object : Runnable { override fun run() {
        val pk = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (pk != ThreadsNotif.THREADS && pk != ThreadsNotif.INSTA) hideReadBtn() else h.postDelayed(this, 2000)
    } }

    private fun showReadBtn() {
        if (readBtn != null || running || !Macro.readBtn(this) || System.currentTimeMillis() < btnHiddenUntil) return
        try {
            fun mk(t: String, sz: Float) = android.widget.TextView(this).apply {
                text = t; textSize = sz; gravity = android.view.Gravity.CENTER; alpha = 0.55f
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xB3121216.toInt()); setStroke(dpx(1), 0x40FFFFFF) }
            }
            val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
            val v = mk("📖", 18f); val t = mk("🎓", 13f)
            box.addView(v, android.widget.LinearLayout.LayoutParams(dpx(44), dpx(44)))
            box.addView(t, android.widget.LinearLayout.LayoutParams(dpx(32), dpx(32)).apply { topMargin = dpx(6) })
            val lp = android.view.WindowManager.LayoutParams(-2, -2, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END; x = dpx(4); y = Store.prefs(this@ScreenReader).getInt("readbtn_y", (resources.displayMetrics.heightPixels * 0.42).toInt())
            }
            var sy = 0; var ty = 0f; var moved = false; var down = 0L
            v.setOnTouchListener { _, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { sy = lp.y; ty = e.rawY; moved = false; down = System.currentTimeMillis(); v.alpha = 1f }
                    android.view.MotionEvent.ACTION_MOVE -> { val dy = e.rawY - ty; if (Math.abs(dy) > dpx(8)) moved = true; if (moved) { lp.y = sy + dy.toInt(); try { wm.updateViewLayout(box, lp) } catch (x: Exception) { } } }
                    android.view.MotionEvent.ACTION_UP -> {
                        v.alpha = 0.55f
                        if (moved) Store.prefs(this).edit().putInt("readbtn_y", lp.y).apply()
                        else if (System.currentTimeMillis() - down > 700) { btnHiddenUntil = System.currentTimeMillis() + 30 * 60_000L; hideReadBtn(); show("📖 Botão escondido por 30 min"); h.postDelayed({ if (!running) clearOverlay() }, 1800) }
                        else forceRead()
                    }
                }
                true
            }
            t.setOnClickListener { teach() }
            wm.addView(box, lp); readBtn = box; readLp = lp
            h.removeCallbacks(fgCheck); h.postDelayed(fgCheck, 2000)
        } catch (e: Exception) { }
    }
    private fun hideReadBtn() { try { readBtn?.let { wm.removeView(it) } } catch (e: Exception) { }; readBtn = null; closeTeach() }

    /** Um texto da tela: como está escrito, normalizado, o "nome" interno do campo e o número (se for número). */
    class Item(val raw: String, val t: String, val vid: String, val num: Int?)
    private var curItems: List<Item> = emptyList()
    private var lastHit: JSONObject? = null

    private fun itemsOf(pkg: String, max: Int = 1500): List<Item> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<Item>()
        fun add(r: String, vid: String) { if (r.isNotEmpty()) { val t = norm(r); out += Item(r, t, vid, numOf(t)) } }
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60 || out.size > max) return
            if (pkg != ThreadsNotif.INSTA || n.isVisibleToUser) {
                val vid = n.viewIdResourceName?.substringAfterLast('/') ?: ""
                val tx = n.text?.toString()?.trim().orEmpty(); val ds = n.contentDescription?.toString()?.trim().orEmpty()
                add(tx, vid); if (ds != tx) add(ds, vid)
            }
            for (k in 0 until n.childCount) walk(n.getChild(k), d + 1)
        }
        try { walk(root, 0) } catch (e: Exception) { }
        return out
    }

    /** Todos os textos da tela (no Instagram só o visível). Guarda também os itens, pras regras ensinadas. */
    private fun collect(pkg: String): List<String> { curItems = itemsOf(pkg); return curItems.map { it.raw } }

    // ================= 🎓 o que você ensinou: reconhece o número pelo vizinho, pela posição e pelo tipo de tela =================
    private fun pat(t: String) = t.replace(Regex("\\d+"), "#")
    private fun screenOf(L: List<Item>) = if (L.any { LABELS["views"]!!.matches(it.t) }) "ins" else "post"

    private fun feat(L: List<Item>, i: Int, pkg: String, screen: String): JSONObject {
        fun stable(x: Item) = x.num == null && !TIME.matches(x.t) && x.t.length in 2..28
        val prev = (i - 1 downTo maxOf(0, i - 3)).firstOrNull { stable(L[it]) }?.let { pat(L[it].t) } ?: ""
        val next = (i + 1..minOf(L.size - 1, i + 3)).firstOrNull { stable(L[it]) }?.let { pat(L[it].t) } ?: ""
        var a = i; while (a > 0 && L[a - 1].num != null) a--
        var b = i; while (b < L.size - 1 && L[b + 1].num != null) b++
        val after = (b + 1..minOf(L.size - 1, b + 2)).firstOrNull { stable(L[it]) }?.let { pat(L[it].t) } ?: ""
        return JSONObject().put("pkg", pkg).put("screen", screen).put("vid", L[i].vid).put("prev", prev).put("next", next).put("pos", i - a).put("len", b - a + 1).put("after", after)
    }

    private fun ruleScore(r: JSONObject, f: JSONObject): Int {
        if (r.optString("pkg") != f.optString("pkg") || r.optString("screen") != f.optString("screen")) return 0
        var s = 0
        val rv = r.optString("vid"); val rp = r.optString("prev"); val rn = r.optString("next")
        if (rv.isNotEmpty() && rv == f.optString("vid")) s += 3
        if (rp.isNotEmpty() && rp == f.optString("prev")) s += 2
        if (rn.isNotEmpty() && rn == f.optString("next")) s += 2
        if (r.optInt("pos") == f.optInt("pos") && r.optInt("len") == f.optInt("len")) {
            s += if (rp.isEmpty() && rv.isEmpty()) 2 else 1
            if (r.optString("after").isNotEmpty() && r.optString("after") == f.optString("after")) s += 1
        }
        if (rp.isNotEmpty() && f.optString("prev").isNotEmpty() && rp != f.optString("prev")) s -= 2 // vizinho diferente: é outro número
        return s
    }

    /** Aplica o que foi ensinado nesta tela: métrica → número. */
    private fun learnedRead(L: List<Item>, pkg: String): Map<String, Int> {
        val rules = ReaderRules.all(this).filter { it.optString("pkg") == pkg }
        if (rules.isEmpty() || L.isEmpty()) return emptyMap()
        val screen = screenOf(L)
        val feats = L.indices.filter { L[it].num != null }.take(80).associateWith { feat(L, it, pkg, screen) }
        val ign = rules.filter { it.optString("metric") == "ignore" }
        val cand = feats.keys.filter { i -> ign.none { ruleScore(it, feats.getValue(i)) >= 3 } }
        val out = HashMap<String, Int>()
        rules.filter { it.optString("metric") != "ignore" }.groupBy { it.optString("metric") }.forEach { (metric, rs) ->
            val best = cand.maxByOrNull { i -> rs.maxOf { ruleScore(it, feats.getValue(i)) } } ?: return@forEach
            if (rs.maxOf { ruleScore(it, feats.getValue(best)) } >= 3) out[metric] = L[best].num!!
        }
        return out
    }

    private var teachView: android.view.View? = null
    private fun closeTeach() { try { teachView?.let { wm.removeView(it) } } catch (e: Exception) { }; teachView = null }
    private val METRICS = listOf("views" to "👁 Views", "likes" to "❤ Curtidas", "replies" to "💬 Respostas", "reposts" to "🔁 Reposts", "shares" to "↗ Envios",
        "saves" to "🔖 Salvos", "viewers" to "👥 Alcance", "visits" to "👤 Visitas", "fol" to "➕ Seguiram", "ignore" to "🚫 Nada")

    /** 🎓 Mostra os números desta tela; você toca no que cada um é; o leitor guarda a regra e lê de novo. */
    fun teach() {
        if (teachView != null || running) return
        val pkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (pkg != ThreadsNotif.THREADS && pkg != ThreadsNotif.INSTA) return
        val L = itemsOf(pkg); val screen = screenOf(L)
        val nums = L.indices.filter { L[it].num != null }.take(40)
        if (nums.isEmpty()) { show("🎓 Não vi nenhum número nesta tela. Abra o post ou o \"Ver insights\"."); h.postDelayed({ if (!running) clearOverlay() }, 2500); return }
        // o que o leitor acha hoje (pra você ver onde ele erra)
        val nt = L.map { it.t }
        val cur = pairs(nt, 0, nt.size).toMutableMap(); extras(nt, cur); cur.putAll(learnedRead(L, pkg)); cur["follows"]?.let { cur["fol"] = it; cur.remove("follows") }
        val guess = HashMap<Int, String>(); val choice = HashMap<Int, String>()
        cur.forEach { (k, v) -> if (METRICS.any { it.first == k }) nums.firstOrNull { L[it].num == v && it !in guess }?.let { guess[it] = k } }
        try {
            val W = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            fun tv(s: String, sz: Float, col: Int) = android.widget.TextView(this).apply { text = s; textSize = sz; setTextColor(col) }
            val panel = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setPadding(dpx(14), dpx(12), dpx(14), dpx(10))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF50E0E12.toInt()); cornerRadii = floatArrayOf(dpx(22).toFloat(), dpx(22).toFloat(), dpx(22).toFloat(), dpx(22).toFloat(), 0f, 0f, 0f, 0f); setStroke(dpx(1), 0x26FFFFFF) }
            }
            panel.addView(tv("🎓 Ensinar o leitor", 17f, 0xFFFFFFFF.toInt()).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            panel.addView(tv("Estes são os números que eu vejo nesta tela. Toque no que cada um é (só os que importam). Em cinza: o que eu acho hoje.", 12f, 0xFF9A9EAB.toInt()))
            val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val paints = ArrayList<() -> Unit>()
            nums.forEach { i ->
                val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, dpx(8), 0, dpx(4)) }
                val ctx = listOfNotNull(L.getOrNull(i - 1)?.raw?.replace('\n', ' ')?.take(22)?.let { "antes: $it" }, L.getOrNull(i + 1)?.raw?.replace('\n', ' ')?.take(22)?.let { "depois: $it" }).joinToString("  ·  ")
                val head = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                head.addView(tv(L[i].raw, 18f, 0xFFFFFFFF.toInt()).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, dpx(10), 0) })
                head.addView(tv(ctx, 11f, 0xFF9A9EAB.toInt()))
                row.addView(head)
                val chips = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                val views = METRICS.map { (k, label) ->
                    tv(label, 12f, 0xFFECEEF3.toInt()).apply {
                        setPadding(dpx(10), dpx(7), dpx(10), dpx(7))
                        setOnClickListener {
                            if (choice[i] == k) choice.remove(i) else { if (k != "ignore") choice.entries.removeAll { it.value == k }; choice[i] = k }
                            paints.forEach { it() }
                        }
                        chips.addView(this, android.widget.LinearLayout.LayoutParams(W, W).apply { rightMargin = dpx(6) })
                    } to k
                }
                paints += { views.forEach { (v, k) ->
                    val on = choice[i] == k; val g = !on && choice[i] == null && guess[i] == k
                    v.setTextColor(if (on) 0xFF06140D.toInt() else 0xFFECEEF3.toInt())
                    v.background = android.graphics.drawable.GradientDrawable().apply { setColor(if (on) 0xFF4CC68E.toInt() else if (g) 0xFF3A3A46.toInt() else 0xFF1C1C24.toInt()); cornerRadius = dpx(14).toFloat() }
                } }
                row.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
                list.addView(row)
            }
            paints.forEach { it() }
            panel.addView(android.widget.ScrollView(this).apply { addView(list) }, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            val foot = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, dpx(8), 0, 0) }
            fun fb(s: String, col: Int, fg: Int, onClick: () -> Unit) = tv(s, 14f, fg).apply {
                gravity = android.view.Gravity.CENTER; setPadding(0, dpx(12), 0, dpx(12)); setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(col); cornerRadius = dpx(12).toFloat() }
                setOnClickListener { onClick() }
            }
            foot.addView(fb("Cancelar", 0xFF1C1C24.toInt(), 0xFFECEEF3.toInt()) { closeTeach() }, android.widget.LinearLayout.LayoutParams(0, W, 1f).apply { rightMargin = dpx(8) })
            foot.addView(fb("💾 Salvar e ler de novo", 0xFFFF5C82.toInt(), 0xFF1A0A10.toInt()) {
                if (choice.isEmpty()) { closeTeach(); return@fb }
                val vidCount = nums.groupingBy { L[it].vid }.eachCount()
                choice.forEach { (i, metric) ->
                    val f = feat(L, i, pkg, screen)
                    if ((vidCount[L[i].vid] ?: 0) > 1) f.put("vid", "") // o mesmo "nome" em vários números não ajuda a distinguir
                    ReaderRules.add(this, f.put("metric", metric))
                }
                val n = choice.size
                closeTeach(); show("🎓 Aprendi $n número(s). Lendo de novo…", 0xEE15803D.toInt())
                h.postDelayed({ forceRead() }, 700)
            }, android.widget.LinearLayout.LayoutParams(0, W, 2f))
            panel.addView(foot)
            val lp = android.view.WindowManager.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.62).toInt(), android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.BOTTOM }
            wm.addView(panel, lp); teachView = panel
        } catch (e: Exception) { ErrLog.log(this, "ensinar leitor", e); closeTeach() }
    }

    // ================= ✍ legenda que não entrou: escreve no campo (não publica) =================
    private var cfLast = 0L; private var cfSeen = 0L
    private fun forceCaption(pkg: String) {
        val cap = CapForce.pending(this, pkg) ?: return
        val now = System.currentTimeMillis(); if (now - cfLast < 500) return; cfLast = now
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (root.packageName?.toString() != pkg) return
        val edits = nodes(root).filter { it.isEditable && it.isVisibleToUser }
        if (edits.isEmpty()) { cfSeen = 0; return }
        fun hintOf(n: AccessibilityNodeInfo) = (if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else "") + " " + (n.text ?: "") + " " + (n.contentDescription ?: "")
        // Instagram: só o campo de LEGENDA (não a busca, não comentário); Threads: o campo do post novo
        val target = if (pkg == ThreadsNotif.INSTA) edits.firstOrNull { Regex("legenda|caption").containsMatchIn(hintOf(it).lowercase()) } ?: return else edits.first()
        val showingHint = android.os.Build.VERSION.SDK_INT >= 26 && target.isShowingHintText
        val cur = if (showingHint) "" else (target.text ?: "").toString().trim()
        if (cur.isNotEmpty() && !(pkg == ThreadsNotif.INSTA && Regex("^(escreva uma legenda|write a caption)", RegexOption.IGNORE_CASE).containsMatchIn(cur))) { CapForce.done(this); cfSeen = 0; return } // já tem texto: não mexo
        // espera 1,2 s com o campo vazio (o app pode estar colocando a legenda sozinho)
        if (cfSeen == 0L) { cfSeen = now; h.postDelayed({ forceCaption(pkg) }, 1300); return }
        if (now - cfSeen < 1200) return
        CapForce.tried(this)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cap) })
        if (!ok) { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS); target.performAction(AccessibilityNodeInfo.ACTION_PASTE) } // a legenda já está copiada
        show("✍ Legenda colocada no post", 0xEE15803D.toInt())
        h.postDelayed({ if (!running && !forcing) clearOverlay(); forceCaption(pkg) }, 1800) // confere se entrou
    }

    /** A legenda do post na tela (o 1º texto longo antes dos números), pra guardar post feito fora do app. */
    private fun guessCap(nt: List<String>, raw: List<String>): String {
        val me = Macro.handle(this).lowercase(); val ig = Macro.igHandle(this).lowercase()
        val firstNum = nt.indexOfFirst { numOf(it) != null }.let { if (it < 0) nt.size else it }
        val bad = Regex("insights|meta ai|this one|esse post|este post|your |seus |suas |summar|resum|views over|visualiza[cç][oõ]es ao longo|seguir|follow|traduz|translat|curtido por|liked by")
        val i = (0 until firstNum).firstOrNull { k -> val t = nt[k]; t.length >= 8 && !TIME.matches(t) && t != me && t != ig && !t.startsWith("@") && !t.startsWith("http") && !bad.containsMatchIn(t) && LABELS.values.none { it.matches(t) } }
            ?: nt.indices.filter { k -> nt[k].length >= 12 && !bad.containsMatchIn(nt[k]) && nt[k] != me }.maxByOrNull { nt[it].length } ?: return ""
        return raw[i]
    }

    /** 📖 Ler esta tela agora: lê, espera carregar e lê de novo, desce 1x nos insights pra ver o resto, e guarda (post do app ou de fora). */
    fun forceRead() {
        if (forcing || running) return
        val pkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (pkg != ThreadsNotif.THREADS && pkg != ThreadsNotif.INSTA) { show("📖 Abra um post do Threads/Instagram primeiro"); h.postDelayed({ clearOverlay() }, 2000); return }
        forcing = true; capturedNow = false
        show("📖 Lendo esta tela…")
        val all = LinkedHashMap<String, Int>(); var lastTexts: List<String> = emptyList(); var cap = ""; appHit = false; lastHit = null; taught.clear()
        fun take() {
            val raw = collect(pkg); lastTexts = raw
            val nt = raw.map { norm(it) }
            val got = try { scan(pkg, raw) } catch (e: Exception) { emptyList() }
            if (got.isNotEmpty()) appHit = true
            taught.putAll(learnedRead(curItems, pkg)) // 🎓 o que você ensinou vale mais que o palpite
            got.forEach { m -> m.forEach { (k, v) -> if (v > (all[k] ?: -2)) all[k] = v } }
            // post de fora do app (ou legenda que não bateu): números da tela inteira + legenda achada na tela
            val m = pairs(nt, 0, nt.size).toMutableMap(); extras(nt, m)
            m.forEach { (k, v) -> if (v > (all[k] ?: -2)) all[k] = v }
            if (cap.isEmpty()) cap = guessCap(nt, raw)
        }
        take()
        h.postDelayed({
            take()
            val insights = lastTexts.any { LABELS["views"]!!.matches(norm(it)) }
            val sc = rootInActiveWindow?.let { r -> nodes(r).firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } } }
            if (insights && sc != null && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                h.postDelayed({ take(); try { rootInActiveWindow?.let { r -> nodes(r).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } } catch (e: Exception) { }; done(pkg, all, cap, lastTexts) }, 1300)
            } else done(pkg, all, cap, lastTexts)
        }, 1300)
    }

    private fun done(pkg: String, all0: Map<String, Int>, cap: String, texts: List<String>) {
        forcing = false
        lastForeign?.let { nm -> show("👩 Esta é a conta de $nm: troque de modelo no Post Leve pra guardar os números dela", 0xEEB45309.toInt()); h.postDelayed({ if (!running) clearOverlay() }, 4500); return }
        val all = all0.toMutableMap(); all["follows"]?.let { all["fol"] = it; all.remove("follows") }
        all.putAll(taught)
        val clean = ThreadsApi.sane(all)
        if (clean.keys.none { !it.startsWith("vs_") }) {
            dump(texts); show("⚠ Não achei números nesta tela. Abra o post (ou \"Ver insights\") e toque de novo.\nOu toque no 🎓 e me mostre o que é cada número.", 0xEEB45309.toInt())
            h.postDelayed({ if (!running) clearOverlay() }, 4500); return
        }
        val fromApp = appHit
        val kindNow = if (pkg == ThreadsNotif.INSTA) "video" else "fila"
        val force = ThreadsApi.sane(taught).keys
        if (!fromApp && core(cap).length >= 8) { ThreadsApi.upsertOrphan(this, cap, clean, "forcado", kindNow, force); if (kindNow == "fila") h.postDelayed({ harvest(cap) }, 1600) }
        else if (fromApp && force.isNotEmpty()) lastHit?.let { s -> ThreadsApi.upsertLocal(this, s.optLong("at"), s.optString("id"), s.optString("cap"), clean.filterKeys { it in force }, "ensinado", kindNow, force) }
        opened = 0; captured(clean, manual = true)
        pill?.text = (pill?.text ?: "").toString() + (if (force.isNotEmpty()) "\n🎓 usei o que você ensinou (" + force.size + ")" else "") + if (fromApp) "\n📌 post do app" else if (core(cap).length >= 8) "\n📌 post de fora: \"" + cap.replace('\n', ' ').take(30) + "…\"" else "\n⚠ não achei a legenda: abra o post"
        Thread { try { ThreadsApi.process(this) } catch (e: Exception) { } }.start()
        h.postDelayed({ if (!running) clearOverlay() }, 4500)
    }

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

    // ================= 🗓 agendamento em lote: leva direto pra tela de AGENDAR do Threads =================
    private var schedTapAt = 0L
    private var schedUntil = 0L; private var schedLabel = ""; private var schedStep = 0; private var schedLast = 0L; private var schedTries = 0
    private val SCHED = Regex("^(agendar|agendar\\.\\.\\.|agendar…|agendar publicação|programar|programar publicação|schedule|schedule\\.\\.\\.|schedule…|schedule post|schedule thread)$")
    private val MORE = Regex("^(mais|mais opções|mais opcoes|more|more options|opções|options|menu)$")

    /** O próximo post do lote vai pro Threads: quando o editor abrir, toca em ⋯ › Agendar sozinho (não publica nada). */
    private val schedTried = HashSet<String>()
    fun armSchedule(label: String) { schedUntil = System.currentTimeMillis() + 150_000L; schedLabel = label; schedStep = 0; schedTries = 0; schedTried.clear() }

    private fun trySchedule() {
        val now = System.currentTimeMillis(); if (now - schedLast < 700) return; schedLast = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != ThreadsNotif.THREADS) return
        val all = nodes(root).filter { it.isVisibleToUser }
        fun lab(n: AccessibilityNodeInfo) = norm(((n.text ?: n.contentDescription) ?: "").toString())
        // 1) a opção "Agendar" está na tela (menu aberto)? toca nela
        all.firstOrNull { SCHED.matches(lab(it)) }?.let { n ->
            if (tapNode(n) || clickable(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                schedUntil = 0; show("🗓 Agora escolha a data e a hora: $schedLabel\ne toque em Agendar", 0xEE15803D.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 9000)
            }
            return
        }
        // 2) só no editor do post novo (tem campo de texto)
        if (all.none { it.isEditable }) return
        if (schedStep == 1 && now - schedTapAt < 2500) return // menu ainda abrindo: espera
        val dm = resources.displayMetrics
        // abriu um menu SEM "Agendar" (ex.: o ⋯ de cima só tem "Add AI label")? fecha e tenta o próximo ⋯
        if (schedStep == 1 && all.any { Regex("ai label|rótulo de ia|rotulo de ia|marcar como ia|ia\\b.*rótulo").containsMatchIn(lab(it)) }) { performGlobalAction(GLOBAL_ACTION_BACK); schedStep = 0; schedTapAt = now; return }
        schedStep = 0
        // ⋯ candidatos: o da barra de ferramentas embaixo (onde fica o Agendar) primeiro, depois o de cima. Nunca o ⋯ de cima da FOTO.
        val photos = all.filter { it.className?.toString()?.contains("Image") == true }.map { rect(it) }.filter { it.width() > dm.widthPixels * 0.35 && it.height() > dpx(150) }
        val cands = all.filter { MORE.matches(lab(it)) || (it.contentDescription?.toString()?.let { d -> Regex("^(mais|more)\\b", RegexOption.IGNORE_CASE).containsMatchIn(d) } == true) }
            .filter { n -> val r = rect(n); photos.none { p -> p.contains(r) } && r.width() > 0 }
            .distinctBy { val r = rect(it); "${r.centerX() / dpx(20)}:${r.centerY() / dpx(20)}" }
            .sortedWith(compareByDescending<AccessibilityNodeInfo> { rect(it).top > dm.heightPixels * 0.4 }.thenByDescending { rect(it).left })
        val next = cands.firstOrNull { val r = rect(it); "${r.centerX() / dpx(20)}:${r.centerY() / dpx(20)}" !in schedTried }
        if (next == null || ++schedTries > 6) {
            schedUntil = 0; show("🗓 Não achei o Agendar: toque no ⋯ da barra de baixo (ao lado do ❤) › Agendar e escolha $schedLabel. Se puder, me manda um print desse menu.", 0xEEB45309.toInt())
            h.postDelayed({ if (!running && !forcing) clearOverlay() }, 9000); return
        }
        val r = rect(next); schedTried += "${r.centerX() / dpx(20)}:${r.centerY() / dpx(20)}"
        if (tapNode(next) || clickable(next)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { schedStep = 1; schedTapAt = now }
    }

    // ================= 🗓 lote pelo editor COMPLETO do Threads (é só nele que existe o Agendar) =================
    // Passos: 1) toca em novo post  2) toca em anexar foto  3) escolhe a foto mais recente (a que o app acabou de pôr na galeria)
    //         4) escreve a legenda  5) ⋯ › Agendar. Nunca toca em "Postar/Post".
    private var cPicked = false
    private var cAway = 0L
    private val cMenus = HashSet<String>(); private var cMenuOpen = ""
    /** Guarda o que tinha em cada menu aberto (pro diagnóstico). */
    private fun saveMenu(all: List<AccessibilityNodeInfo>, why: String) {
        val t = all.mapNotNull { ((it.text ?: it.contentDescription)?.toString()?.trim())?.takeIf { s -> s.isNotEmpty() }?.take(30) }.distinct().take(30).joinToString(" | ")
        val l = (Store.prefs(this).getString("agendar_menus", "") ?: "").split("\n").takeLast(6) + (android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString() + " $why: $t")
        Store.prefs(this).edit().putString("agendar_menus", l.joinToString("\n")).apply()
    }
    private var cTarget = 0L // 🤖 piloto automático: data/hora que tem que ficar escolhida no Agendar do Threads
    private val cWheel = HashMap<String, Int>()
    private var composeUntil = 0L; private var cStep = 0; private var cCap = ""; private var cLabel = ""; private var cLast = 0L; private var cTapAt = 0L; private var cTries = 0
    private val CREATE = Regex("^(create|criar|new thread|novo thread|nova thread|novo post|create thread|criar thread|compose|escrever|start a thread|comece um thread|iniciar thread)$")
    private val ATTACH = Regex("^(attach media|anexar mídia|anexar midia|anexar|add media|adicionar mídia|adicionar midia|add photos|add photos and videos|adicionar fotos|adicionar fotos e vídeos|gallery|galeria|photo library|biblioteca de fotos|attach photos|media|mídia|midia|foto e vídeo|photos and videos|fotos e vídeos)$")
    private val PICK_DONE = Regex("^(add|adicionar|next|avançar|avancar|done|concluir|ok|choose|escolher|select|selecionar)$")
    private val POSTBTN = Regex("^(post|postar|publicar|publish|share|compartilhar)$")

    /** [fromShare]: o post já abriu com a foto (Compartilhar); senão começa do "novo post". */
    fun armCompose(label: String, cap: String, fromShare: Boolean = true, target: Long = 0L) {
        cPicked = false; cTarget = target; cWheel.clear(); wLast = null; wOkSig = ""; cDoneProblems = 0; wStuck.clear(); wGesture.clear(); wNoWrap.clear(); cVerifyTries = 0
        composeUntil = System.currentTimeMillis() + 180_000L; cStep = if (fromShare) 0 else 1; cCap = cap; cLabel = label; cTries = 0; cTapAt = 0
        h.postDelayed({ if (composeUntil > System.currentTimeMillis()) composeStep() }, 1800)
        // passou do tempo e não terminou: tira o aviso (não fica preso na tela)
        val until = composeUntil
        h.postDelayed({ if (composeUntil == until && until > 0) { composeUntil = 0; if (autoRunning) stopBatchAuto("Demorou demais no post de $label: parei. Mande o 📋 diagnóstico.") else clearOverlay() } }, 182_000L)
    }

    private fun cFail(msg: String, all: List<AccessibilityNodeInfo>) {
        composeUntil = 0
        if (autoRunning) {
            saveTree(all, "falhou: $msg")
            clog("✗ falhou: $msg", all)
            AgendarTreino.add(this, false, "passo $cStep", cTarget, msg)
            // anota a falha no item; descarta o post aberto (sem salvar rascunho) e segue: tenta de novo 1x, depois pula pro próximo
            val id = autoItemId
            val l = Batch.load(this); l.firstOrNull { it.optString("id") == id && !it.optBoolean("done") }?.let { it.put("fails", it.optInt("fails") + 1).put("err", "passo $cStep: $msg"); Batch.save(this, l) }
            show("🤖 Falhou ($msg). Descartando e tentando de novo…", 0xEEB45309.toInt())
            abortCompose { h.postDelayed({ nextAuto() }, 2500) }
            return
        }
        dump(all.mapNotNull { (it.text ?: it.contentDescription)?.toString() })
        show("🗓 $msg\nFaça esse passo na mão: a foto é a mais recente da galeria e a legenda já está copiada. Depois ⋯ › Agendar › $cLabel", 0xEEB45309.toInt())
        h.postDelayed({ if (!running && !forcing) clearOverlay() }, 12000)
    }

    /** Toque de verdade no MEIO do botão (vale pra barra de baixo e de cima também). Alterna com o "clique" de acessibilidade se o 1º não funcionou. */
    private fun tapCenter(n: AccessibilityNodeInfo): Boolean {
        val r = rect(n); if (r.width() <= 0 || r.height() <= 0) return false
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return try { dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 70)).build(), null, null) } catch (e: Exception) { false }
    }
    private fun click(n: AccessibilityNodeInfo) = clickable(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    private fun tap(n: AccessibilityNodeInfo) = if (cTries % 2 == 0) (tapCenter(n) || click(n)) else (click(n) || tapCenter(n))

    /** Anota o passo (e o que tinha na tela) pro 📋 diagnóstico. */
    private fun clog(msg: String, all: List<AccessibilityNodeInfo>) {
        val labels = all.mapNotNull { n -> ((n.contentDescription ?: n.text)?.toString()?.trim()?.takeIf { it.isNotEmpty() })?.take(24)?.let { it + (if (n.isClickable) "*" else "") } }.distinct().take(40)
        val l = (Store.prefs(this).getString("agendar_log", "") ?: "").split("\n").takeLast(12) + (android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString() + " [$cStep] $msg · " + labels.joinToString(" | "))
        Store.prefs(this).edit().putString("agendar_log", l.joinToString("\n")).apply()
    }

    private fun composeStep() {
        val now = System.currentTimeMillis(); if (now - cLast < 600) return; cLast = now
        val wait = if (cStep == 6) 350L else 1500L // nas rodinhas o comando é instantâneo: relê logo
        if (now - cTapAt < wait) { h.postDelayed({ composeStep() }, wait + 50); return } // espera a tela reagir ao último toque
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != ThreadsNotif.THREADS) {
            // saiu do Threads: espera um pouco (pode ser a galeria/teclado); depois de 15 s para e tira o aviso
            if (cAway == 0L) cAway = now
            if (now - cAway > 15_000L) { composeUntil = 0; cAway = 0; if (autoRunning) autoRunning = false; clog("parei: saiu do Threads", emptyList()); h.post { clearOverlay() } }
            else h.postDelayed({ if (composeUntil > System.currentTimeMillis()) composeStep() }, 2000)
            return
        }
        cAway = 0
        val all = nodes(root).filter { it.isVisibleToUser }
        fun lab(n: AccessibilityNodeInfo) = norm(((n.contentDescription ?: n.text) ?: "").toString())
        val dm = resources.displayMetrics
        val editor = all.any { it.isEditable }
        val photosInEditor = all.filter { it.className?.toString()?.contains("Image") == true }.map { rect(it) }.filter { it.width() > dm.widthPixels * 0.35 && it.height() > dpx(150) }
        fun again() { if (++cTries > (if (cStep == 6) 250 else if (cStep >= 7) 60 else 10)) { saveTree(all, "travou no passo $cStep"); cFail(when (cStep) { 1 -> "Toquei no + mas o post novo não abriu."; 2 -> "Não consegui anexar a foto."; 3 -> "Não consegui escolher a foto."; 4 -> "A legenda não entrou."; 5 -> "Não achei o ⋯ › Agendar."; 6 -> "Não consegui acertar a data/hora no Agendar."; else -> "O post não foi agendado." }, all); return }; h.postDelayed({ if (composeUntil > System.currentTimeMillis()) composeStep() }, if (cStep == 6) 380 else if (cStep >= 7) 700 else 1500) }
        when (cStep) {
            0 -> { // veio pelo Compartilhar: a foto JÁ está no post (nunca anexa outra) → vai direto pra legenda
                if (editor) { cStep = 4; cTries = 0; composeStep() } else again()
            }
            1 -> { // novo post
                if (editor) { cStep = 2; cTries = 0; composeStep(); return }
                // 1º o botão + do meio da barra de baixo, depois o "What's new?/O que há de novo?" (abre o mesmo editor), por último qualquer "Criar"
                val bottomPlus = all.filter { val r = rect(it); r.top > dm.heightPixels * 0.82 && Math.abs(r.centerX() - dm.widthPixels / 2) < dm.widthPixels * 0.12 && (it.isClickable || CREATE.matches(lab(it)) || lab(it) == "+") }
                    .maxByOrNull { (if (CREATE.matches(lab(it))) 2 else 0) + (if (it.isClickable) 1 else 0) }
                val whatsNew = all.firstOrNull { Regex("^(what.s new\\??|o que há de novo\\??|o que ha de novo\\??|start a thread.*|comece um thread.*|inicie um thread.*)$").matches(lab(it)) }
                val cands = listOfNotNull(bottomPlus, all.firstOrNull { CREATE.matches(lab(it)) }, whatsNew)
                val b = cands.getOrNull((cTries / 3) % maxOf(1, cands.size))
                if (b == null) { clog("sem botão de novo post", all); if (cTries >= 6) cFail("Não achei o botão de novo post.", all) else again(); return }
                clog("toquei em novo post: " + lab(b) + " @" + rect(b).toShortString(), all)
                show("🗓 Abrindo um post novo…"); if (tap(b)) cTapAt = now; again()
            }
            2 -> { // anexar foto (o editor já tem foto? pula)
                if (!editor) { again(); return }
                if (photosInEditor.isNotEmpty()) { cStep = 4; cTries = 0; composeStep(); return }
                val ed = all.first { it.isEditable }; val er = rect(ed)
                val b = all.firstOrNull { ATTACH.matches(lab(it)) }
                    // sem nome: o 1º ícone da fileira de ferramentas embaixo do texto (galeria)
                    ?: all.filter { val r = rect(it); r.top > er.top && r.width() in dpx(24)..dpx(72) && r.height() in dpx(24)..dpx(72) && it.isClickable }
                        .groupBy { rect(it).centerY() / dpx(16) }.values.filter { it.size >= 3 }.minByOrNull { rect(it.first()).top }?.minByOrNull { rect(it).left }
                if (b == null) { clog("sem botão de anexar", all); if (cTries >= 5) cFail("Não achei o botão de anexar foto.", all) else again(); return }
                clog("toquei em anexar: " + lab(b), all)
                show("🗓 Anexando a foto…"); if (tap(b)) { cTapAt = now; cStep = 3; cTries = 0 }; again()
            }
            3 -> { // galeria do Threads: toca UMA vez na foto mais recente; depois só confirma e segue (nunca escolhe duas)
                if (editor && (cPicked || photosInEditor.isNotEmpty())) { cStep = 4; cTries = 0; composeStep(); return }
                if (cPicked) {
                    all.firstOrNull { PICK_DONE.matches(lab(it)) && !POSTBTN.matches(lab(it)) }?.let { b -> if (tap(b)) cTapAt = now }
                    if (++cTries >= 4) { cStep = 4; cTries = 0 }
                    again(); return
                }
                val thumbs = all.filter { it.className?.toString()?.let { c -> c.contains("Image") || c.contains("View") } == true }
                    .map { it to rect(it) }.filter { (n, r) -> r.width() in dpx(60)..(dm.widthPixels / 2) && Math.abs(r.width() - r.height()) < dpx(30) && r.top > dm.heightPixels * 0.12 && !Regex("c[aâ]mera").containsMatchIn(lab(n)) }
                    .sortedWith(compareBy({ it.second.top / dpx(30) }, { it.second.left }))
                // só quadradinhos que o Threads chama de FOTO ("Photo thumbnail, Added on…"): nunca a câmera nem vídeo
                val photoRe = Regex("^(photo thumbnail|miniatura de foto|miniatura da foto|foto|photo|imagem|image)\\b", RegexOption.IGNORE_CASE)
                val named = all.filter { photoRe.containsMatchIn(lab(it)) && !Regex("(?i)video|vídeo|c[aâ]mera|camera").containsMatchIn(lab(it)) }
                    .sortedWith(compareBy({ rect(it).top / dpx(30) }, { rect(it).left }))
                if (cTries == 0) saveTree(all, "galeria do Threads")
                // 1º: troca o álbum pra "PostLeve" (pasta do app: só tem a foto deste post). Assim print/screenshot nunca entra.
                val album = cWheel["album"] ?: 0
                if (album == 0) {
                    val sel = all.firstOrNull { Regex("^(recents|recentes|recent|gallery|galeria|all photos|todas as fotos|camera roll|rolo da câmera)( ▾| ⌄)?$").matches(lab(it)) && clickable(it) != null }
                    if (sel != null) { cWheel["album"] = 1; clog("galeria: abrindo álbuns (" + lab(sel) + ")", all); if (tap(sel)) cTapAt = now; again(); return }
                    cWheel["album"] = 9
                } else if (album in 1..3) {
                    val pl = all.firstOrNull { Regex("^postleve").containsMatchIn(lab(it).replace(" ", "")) }
                    if (pl != null) { cWheel["album"] = 5; clog("galeria: álbum PostLeve", all); if (tap(pl)) cTapAt = now; again(); return }
                    cWheel["album"] = album + 1
                    if (album == 3) { saveTree(all, "lista de álbuns sem PostLeve"); performGlobalAction(GLOBAL_ACTION_BACK); cWheel["album"] = 9 }
                    again(); return
                }
                val first = named.firstOrNull() ?: thumbs.map { it.first }.firstOrNull { n -> lab(n).isNotEmpty() && !Regex("(?i)c[aâ]mera|camera|video|vídeo|take|tirar").containsMatchIn(lab(n)) }
                if (first == null) { clog("sem fotos na galeria", all); if (cTries >= 6) cFail("Não achei as fotos da galeria do Threads.", all) else again(); return }
                clog("toquei na 1ª foto", all)
                show("🗓 Escolhendo a foto…"); if (tap(first)) { cTapAt = now; cPicked = true; cTries = 0 } else cTries++; again()
            }
            4 -> { // legenda
                val ed = all.firstOrNull { it.isEditable } ?: run { again(); return }
                // conta certa? o editor mostra "<conta> profile photo" / "foto do perfil de <conta>"
                if (cTries == 0) {
                    val want = Macro.handle(this).lowercase()
                    val acc = all.firstNotNullOfOrNull { n -> Regex("^([a-z0-9._]{2,30}) profile (photo|picture)$|^foto (do|de) perfil de ([a-z0-9._]{2,30})$").find(lab(n))?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[4] } } }
                    if (acc != null) clog("conta no post: $acc (esperada: ${want.ifEmpty { "?" }})", all)
                    if (want.isNotEmpty() && acc != null && acc != want) { cFail("Abriu a conta @$acc, não a @$want (confira o Threads duplo em ⚙).", all); return }
                }
                val HINT = Regex("^(what.s new\\??|o que há de novo\\??|o que ha de novo\\??|start a thread.*|comece um thread.*|inicie um thread.*)$", RegexOption.IGNORE_CASE)
                fun curText() = if (android.os.Build.VERSION.SDK_INT >= 26 && ed.isShowingHintText) "" else (ed.text ?: "").toString().trim().let { t -> if (HINT.matches(t)) "" else t }
                // o "What's new?" é um texto à parte em cima do campo: se sumiu, a legenda entrou (mesmo que o campo não conte o texto)
                val hintVisible = all.any { !it.isEditable && HINT.matches(lab(it)) }
                if (curText().isEmpty() && (hintVisible || cTries == 0)) {
                    // O Threads às vezes ignora os comandos de acessibilidade. 4 jeitos, um por tentativa:
                    // escrever direto · colar · segurar o dedo no campo e tocar em "Colar" (igual pessoa) · selecionar e colar
                    val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cCap) }
                    fun fresh() = rootInActiveWindow?.let { r -> nodes(r).firstOrNull { it.isEditable } } ?: ed
                    ClipSync.copy(this, cCap)
                    when (cTries % 4) {
                        0 -> { tapCenter(ed); h.postDelayed({ try { fresh().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (e: Exception) { } }, 600) }
                        1 -> { ed.performAction(AccessibilityNodeInfo.ACTION_FOCUS); h.postDelayed({ try { fresh().performAction(AccessibilityNodeInfo.ACTION_PASTE) } catch (e: Exception) { } }, 500) }
                        2 -> { // segura o dedo no campo → menu "Colar"
                            val r = rect(ed); val path = android.graphics.Path().apply { moveTo(r.left + dpx(40).toFloat(), r.top + minOf(r.height() / 2f, dpx(16).toFloat())) }
                            try { dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 900)).build(), null, null) } catch (e: Exception) { }
                            h.postDelayed({ try {
                                val wins = windows.mapNotNull { it.root }.flatMap { nodes(it) } + (rootInActiveWindow?.let { nodes(it) } ?: emptyList())
                                wins.firstOrNull { Regex("^(paste|colar)$", RegexOption.IGNORE_CASE).matches(((it.text ?: it.contentDescription) ?: "").toString().trim()) }?.let { p -> if (!click(p)) tapCenter(p) }
                            } catch (e: Exception) { } }, 1300)
                        }
                        else -> { ed.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0) }); ed.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                    clog("legenda: jeito ${cTries % 4} (texto no campo: '${curText().take(15)}', dica visível: $hintVisible)", all)
                    show("🗓 Escrevendo a legenda…"); cTapAt = now + (if (cTries % 4 == 2) 1200 else 0)
                    if (cTries >= 9) { saveTree(all, "legenda não entrou"); cFail("A legenda não entrou no post.", all); return }
                    again(); return
                }
                clog("legenda ok: '${curText().take(20)}'", all)
                cStep = 5; cTries = 0; cTapAt = now; cMenus.clear(); cMenuOpen = ""; again()
            }
            5 -> { // Agendar: está num dos ⋯ do post. Tenta cada um: o da fileira de ícones ("See more options"), o de cima ("More options") e o de baixo ("Post options")
                // menus podem abrir numa janela separada: procura em todas as janelas do Threads
                val every = try { windows.filter { it.root?.packageName?.toString() == ThreadsNotif.THREADS }.flatMap { w -> w.root?.let { nodes(it) } ?: emptyList() }.filter { it.isVisibleToUser } } catch (e: Exception) { emptyList() }
                val schedNode = (all + every).firstOrNull { val l = lab(it); l.length < 40 && (SCHED.matches(l) || Regex("^(schedul|agendar|programar)").containsMatchIn(l)) && !Regex("(?i)scheduled|agendad").containsMatchIn(l) }
                if (schedNode != null) {
                    saveMenu(all, "menu com Agendar: $cMenuOpen")
                    if (cTarget > 0) { clog("toquei em Agendar: " + lab(schedNode), all); if (tap(schedNode)) { cStep = 6; cTries = 0; cTapAt = now; show("🗓 Escolhendo $cLabel…") }; again(); return }
                    if (tap(schedNode)) { composeUntil = 0; show("🗓 Agora escolha a data e a hora: $cLabel\ne toque em Agendar", 0xEE15803D.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 10000) }
                    return
                }
                val inEditor = all.any { it.isEditable }
                if (cMenuOpen.isNotEmpty()) {
                    // abriu um menu e não tem Agendar: anota o que tinha (pro diagnóstico) e fecha
                    saveMenu((every - all.toSet()).ifEmpty { all }, "menu sem Agendar: $cMenuOpen")
                    cMenus += cMenuOpen; cMenuOpen = ""
                    if (!inEditor || all.size < 25) { performGlobalAction(GLOBAL_ACTION_BACK); cTapAt = now; again(); return }
                }
                if (!inEditor) {
                    // saiu do editor? (ex.: perguntou se quer descartar) → cancela e volta pro post
                    all.firstOrNull { Regex("^(cancel|cancelar|keep editing|continuar editando|voltar)$", RegexOption.IGNORE_CASE).matches(lab(it)) }?.let { tap(it) }
                    again(); return
                }
                val order = listOfNotNull(learnedMenuLabel()) + listOf("more options", "see more options", "mais opções", "post options", "opções do post", "opções da publicação")
                val cands = all.filter { val l = lab(it); (l in order || MORE.matches(l)) && !POSTBTN.matches(l) }
                    .sortedBy { order.indexOf(lab(it)).let { k -> if (k < 0) 99 else k } }
                val next = cands.firstOrNull { lab(it) + "@" + rect(it).top / dpx(40) !in cMenus }
                if (next == null || cTries >= 9) { clog("sem Agendar em nenhum ⋯", all); saveTree(all, "sem agendar"); cFail("Não achei o Agendar em nenhum ⋯ do post.", all); return }
                cMenuOpen = lab(next) + "@" + rect(next).top / dpx(40)
                clog("toquei no ⋯: " + cMenuOpen, all)
                show("🗓 Procurando o Agendar…"); if (tap(next)) cTapAt = now; cTries++; again()
            }
            6 -> pickerStep(all, now) { again() }
            7 -> { // depois do Done: confere a data/hora que o post mostra e toca no Schedule final
                val sheetOpen = all.any { Regex("^(schedule post|agendar post|agendar publicação)$").matches(lab(it)) }
                if (sheetOpen) {
                    if (now - cDoneAt < 1500) { again(); return }
                    // o Done não fechou a folha: relê. Se as rodinhas mudaram, volta a acertar; se estão certas, o horário não vale
                    val r2 = teachRead(all)
                    val ok2 = r2 != null && AgendaCore.wheelNext(r2, java.time.Instant.ofEpochMilli(cTarget).atZone(java.time.ZoneId.systemDefault()), wNoWrap) == null
                    AgendarTreino.add(this, false, "rodinhas", cTarget, "folha não fechou: leu ${r2 ?: "?"}" + (if (ok2) " (certo: horário recusado)" else " (rodinha mexeu depois do Done)"))
                    if (ok2) doneProblem(r2.toString()) else { clog("folha aberta e rodinha errada (${r2 ?: "?"}): acertando de novo", all); cStep = 6; cTries = 0; wOkSig = "" }
                    again(); return
                }
                if (!editor || all.none { it.isEditable }) { autoOk(); return }
                val nowZ = java.time.ZonedDateTime.now()
                val rowNode = all.firstOrNull { n -> AgendaCore.parsePostingRow(((n.text ?: n.contentDescription) ?: "").toString(), nowZ) != null }
                val shown = rowNode?.let { AgendaCore.parsePostingRow(((it.text ?: it.contentDescription) ?: "").toString(), nowZ) }
                if (shown != null && Math.abs(shown.toInstant().toEpochMilli() - cTarget) >= 60_000L) {
                    clog("o post mostra ${AgendaCore.fmt(shown.toInstant().toEpochMilli())}, alvo ${AgendaCore.fmt(cTarget)}: abrindo de novo", all)
                    AgendarTreino.add(this, false, "post", cTarget, "post mostrou ${AgendaCore.fmt(shown.toInstant().toEpochMilli())} depois do Done ($cDoneSig)")
                    if (cVerifyTries++ >= 2) { cFail("O post ficou com outra data/hora.", all); return }
                    if (tap(rowNode)) { cStep = 6; cTries = 0; cTapAt = now; wLast = null }
                    again(); return
                }
                val fin = all.filter { val l = lab(it); SCHED_FINAL.matches(l) && !POSTBTN.matches(l) }.maxByOrNull { rect(it).left }
                if (fin != null && cTries < 3) {
                    // anota ANTES de tocar: se cair aqui, o post não é agendado de novo (confere na lista no fim)
                    if (autoRunning) { val l = Batch.load(this); l.firstOrNull { it.optString("id") == autoItemId && !it.optBoolean("done") }?.let { it.put("sent", System.currentTimeMillis()); Batch.save(this, l) } }
                    clog("toquei no Schedule final (post mostra ${shown?.let { AgendaCore.fmt(it.toInstant().toEpochMilli()) } ?: "?"})", all)
                    if (cTries == 0) AgendarTreino.add(this, shown != null, "post", cTarget, if (shown != null) "post mostrou o horário certo (${rowNode?.let { ((it.text ?: it.contentDescription) ?: "").toString() }})" else "não consegui ler o horário no post: ${all.filter { r -> rect(r).top < resources.displayMetrics.heightPixels / 4 }.mapNotNull { (it.text ?: it.contentDescription)?.toString() }.take(8)}")
                    if (tap(fin)) cTapAt = now; cTries++; again(); return
                }
                if (cTries >= 8) { saveTree(all, "não fechou"); cFail("Escolhi a data, mas o post não foi agendado.", all); return }
                cTries++; again()
            }        }
    }

    // ================= 🤖 piloto automático do lote: agenda TODOS no Agendar do próprio Threads, um atrás do outro =================
    private val SCHED_FINAL = Regex("^(agendar|schedule|programar|agendar post|schedule post)$")
    private val OKBTN = Regex("^(ok|done|concluir|concluído|concluido|set|definir|salvar|save|confirmar|confirm|agendar|schedule|programar)$")
    @Volatile var autoRunning = false; private var autoDone = 0; private var autoItemAt = 0L; private var autoItemId = ""; private var cVerifyTries = 0

    /** Fim do lote: confere na lista de agendados do Threads e mostra o resultado. */
    private fun finishAuto() {
        val l = Batch.load(this)
        val sent = l.filter { it.optBoolean("done") || it.optLong("sent") > 0 }
        if (sent.isEmpty()) { autoRunning = false; val f = l.count { it.optInt("fails") >= 2 }; show("🤖 Nada agendado" + (if (f > 0) ": $f post(s) falharam 2x (veja o diagnóstico)" else ""), 0xEEB45309.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 12000); return }
        show("🤖 Conferindo na lista de agendados do Threads…")
        verifyStart()
    }

    // ================= ✅ conferência: abre os agendados do Threads e procura cada legenda =================
    private var vStep = 0; private var vTries = 0; private var vUntil = 0L; private val vSeen = LinkedHashSet<String>()
    private fun verifyStart() {
        vStep = 1; vTries = 0; vSeen.clear(); vUntil = System.currentTimeMillis() + 90_000L
        try { if (DualApp.launch(this) == "") throw Exception("sem Threads") } catch (e: Exception) { }
        h.postDelayed({ verifyStep() }, 2500)
    }
    private fun verifyStep() {
        if (System.currentTimeMillis() > vUntil || vStep == 0) { verifyEnd(); return }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != ThreadsNotif.THREADS) { h.postDelayed({ verifyStep() }, 1500); return }
        val all = nodes(root).filter { it.isVisibleToUser }
        fun lab(n: AccessibilityNodeInfo) = norm(((n.contentDescription ?: n.text) ?: "").toString())
        val dm = resources.displayMetrics
        when (vStep) {
            1 -> { // abre um post novo (o botão de rascunhos/agendados fica nele)
                val drafts = all.firstOrNull { Regex("saved drafts|rascunhos|drafts").containsMatchIn(lab(it)) && clickable(it) != null }
                if (drafts != null) { clog("conferência: abri rascunhos", all); click(drafts) || tapCenter(drafts); vStep = 2; vTries = 0; h.postDelayed({ verifyStep() }, 2000); return }
                val plus = all.filter { val r = rect(it); r.top > dm.heightPixels * 0.82 && Math.abs(r.centerX() - dm.widthPixels / 2) < dm.widthPixels * 0.12 && (it.isClickable || CREATE.matches(lab(it))) }.maxByOrNull { if (CREATE.matches(lab(it))) 1 else 0 }
                if (plus != null && vTries < 4) { tapCenter(plus) || click(plus) }
                if (++vTries > 8) { saveTree(all, "conferência: não abri os rascunhos"); verifyEnd(); return }
                h.postDelayed({ verifyStep() }, 2000)
            }
            2 -> { // aba "Scheduled/Agendados" e lê tudo (rolando)
                val tab = all.firstOrNull { Regex("^(scheduled|agendad[oa]s?|programad[oa]s?)$").matches(lab(it)) }
                if (tab != null && vTries == 0) { click(tab) || tapCenter(tab); vTries++; h.postDelayed({ verifyStep() }, 1800); return }
                all.mapNotNull { ((it.text ?: it.contentDescription) ?: "").toString().trim().takeIf { s -> s.length >= 3 } }.forEach { vSeen += it }
                if (vTries == 1 || vTries == 2) saveTree(all, "lista de agendados")
                val list = all.filter { it.isScrollable }.maxByOrNull { rect(it).height() }
                if (list != null && vTries < 6 && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { vTries++; h.postDelayed({ verifyStep() }, 1300); return }
                verifyEnd()
            }
        }
    }
    private fun verifyEnd() {
        val seen = vSeen.map { Agenda.ck(it) }
        vStep = 0
        val l = Batch.load(this); var ok = 0; var miss = 0
        l.filter { it.optBoolean("done") || it.optLong("sent") > 0 }.forEach { o ->
            val k = Agenda.ck(o.optString("cap")).take(24)
            val found = k.isNotEmpty() && seen.any { it.contains(k) || (it.length >= 12 && k.startsWith(it.take(24))) }
            if (found) { o.put("verified", true); o.put("done", true); ok++ } else if (seen.isNotEmpty()) { o.put("verified", false); miss++ }
            if (seen.isNotEmpty()) AgendarTreino.add(this, found, "lista", o.optLong("at"), (if (found) "apareceu na lista de agendados: " else "NÃO apareceu na lista: ") + o.optString("cap").take(40))
        }
        Batch.save(this, l)
        // os que foram enviados e apareceram na lista entram na Agenda (se ainda não entraram)
        l.filter { it.optBoolean("verified") }.forEach { o -> Thread { try { Agenda.addDetected(this, o.optLong("at"), o.optString("cap")) } catch (e: Exception) { } }.start() }
        val failed = l.count { it.optInt("fails") >= 2 && !it.optBoolean("done") }
        val msg = when {
            seen.isEmpty() -> "🤖 ✓ $autoDone post(s) agendados (não consegui abrir a lista de agendados pra conferir)"
            miss == 0 -> "🤖 ✓ $ok post(s) agendados e conferidos na lista do Threads"
            else -> "🤖 $ok conferido(s) na lista do Threads · ⚠ $miss não apareceram"
        } + (if (failed > 0) " · ✗ $failed falharam" else "")
        clog(msg, emptyList())
        Store.prefs(this).edit().putString("auto_result", android.text.format.DateFormat.format("dd/MM HH:mm", System.currentTimeMillis()).toString() + " " + msg).apply()
        autoRunning = false
        show(msg, if (miss == 0 && failed == 0) 0xEE15803D.toInt() else 0xEEB45309.toInt())
        // sai da lista e do post vazio, sem salvar nada
        abortCompose { }
        h.postDelayed({ if (!running && !forcing) clearOverlay() }, 15000)
    }

    /** Fecha o post aberto sem salvar rascunho (Cancel/voltar → "Não salvar"). */
    private fun abortCompose(then: () -> Unit) {
        composeUntil = 0
        var n = 0
        fun stepOut() {
            val root = rootInActiveWindow
            if (root == null || root.packageName?.toString() != ThreadsNotif.THREADS || n++ > 5) { then(); return }
            val all = nodes(root).filter { it.isVisibleToUser }
            fun lab(x: AccessibilityNodeInfo) = norm(((x.contentDescription ?: x.text) ?: "").toString())
            val dont = all.firstOrNull { Regex("^(don.t save|não salvar|nao salvar|discard|descartar)$").matches(lab(it)) }
            when {
                dont != null -> { click(dont) || tapCenter(dont); h.postDelayed({ stepOut() }, 1200) }
                all.any { it.isEditable } || all.any { Regex("drafts|rascunhos|scheduled|agendad").containsMatchIn(lab(it)) } -> { performGlobalAction(GLOBAL_ACTION_BACK); h.postDelayed({ stepOut() }, 1200) }
                else -> then()
            }
        }
        stepOut()
    }

    /** Um toque: agenda no Threads todos os posts do lote que faltam. */
    fun runBatchAuto() {
        autoRunning = true; autoDone = 0; Store.prefs(this).edit().putString("agendar_log", "").apply()
        // rodada nova: quem falhou antes ganha outra chance
        val l = Batch.load(this); l.forEach { it.remove("fails"); it.remove("err") }; Batch.save(this, l)
        nextAuto()
    }
    fun stopBatchAuto(why: String) { autoRunning = false; composeUntil = 0; show("🤖 $why", 0xEEB45309.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 10000) }

    private fun nextAuto() {
        if (!autoRunning) return
        Batch.refreshTimes(this)
        val l = Batch.load(this)
        fun pending(it: org.json.JSONObject) = !it.optBoolean("done") && it.optLong("sent") == 0L && it.optInt("fails") < 2
        val o = l.firstOrNull { pending(it) }
        if (o == null) { finishAuto(); return }
        val at = o.optLong("at"); val left = l.count { pending(it) }
        autoItemId = o.optString("id")
        try {
            ClipSync.copy(this, o.optString("cap"))
            PostQueue.putInGallery(this, o.optString("id"), force = true)
            if (DualApp.launch(this) == "") throw Exception("sem Threads")
        } catch (e: Exception) { stopBatchAuto("Não consegui abrir o Threads"); return }
        autoItemAt = at
        show("🤖 Agendando ${autoDone + 1}/${autoDone + left}: " + AgendaCore.fmt(at))
        h.postDelayed({ armCompose(AgendaCore.fmt(at), o.optString("cap"), fromShare = false, target = at) }, 1500)
    }

    /** Esse post ficou agendado: anota (agenda + lote + histórico) e vai pro próximo. */
    private fun autoOk() {
        composeUntil = 0
        val at = cTarget; val cap = cCap
        Thread { try { Agenda.addDetected(this, at, cap) } catch (e: Exception) { ErrLog.log(this, "agenda", e) } }.start()
        if (autoRunning) { val l = Batch.load(this); l.firstOrNull { it.optString("id") == autoItemId }?.let { it.put("done", true).put("at", at); Batch.save(this, l) } }
        clog("✓ agendado " + AgendaCore.fmt(at), emptyList())
        Store.prefs(this).edit().putLong("auto_ok_at", System.currentTimeMillis()).putInt("auto_ok_n", Store.prefs(this).getInt("auto_ok_n", 0) + 1).apply()
        saveTeach()
        autoDone++
        if (autoRunning) { show("🤖 ✓ Agendado " + AgendaCore.fmt(at)); h.postDelayed({ nextAuto() }, 3500) }
        else { show("🗓 ✓ Agendado no Threads: " + AgendaCore.fmt(at), 0xEE15803D.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 6000) }
    }

    /** Guarda como é a tela (pro diagnóstico): tipo, texto, descrição e o que dá pra fazer em cada coisa. */
    fun saveTree(all: List<AccessibilityNodeInfo>, why: String) {
        val lines = all.filter { it.isVisibleToUser }.mapNotNull { n ->
            val t = (n.text ?: "").toString().replace('\n', ' ').take(30); val d = (n.contentDescription ?: "").toString().replace('\n', ' ').take(30)
            val f = (if (n.isClickable) "c" else "") + (if (n.isScrollable) "s" else "") + (if (n.isEditable) "e" else "") + (if (n.isSelected || n.isChecked) "x" else "")
            if (t.isEmpty() && d.isEmpty() && f.isEmpty()) null else { val r = rect(n); "${n.className?.toString()?.substringAfterLast('.')}[$f]${if (t.isNotEmpty()) " t=$t" else ""}${if (d.isNotEmpty()) " d=$d" else ""} @${r.left},${r.top}" }
        }
        Store.prefs(this).edit().putString("agendar_tela", android.text.format.DateFormat.format("dd/MM HH:mm", System.currentTimeMillis()).toString() + " ($why)\n" + lines.take(90).joinToString("\n")).apply()
    }

    /**
     * Tela "Schedule post" do Threads: 4 rodinhas (dia "Sun, Nov 22" · hora 1–12 · minuto 00–59 · AM/PM) e o botão Done.
     * O valor escolhido é o que está na FAIXA DO MEIO. Arrasta cada rodinha devagar, linha por linha, até bater; aí toca em Done.
     * Devolve false se a tela não for essa (aí tenta os outros tipos de seletor).
     */
    private fun wheelSheet(all: List<AccessibilityNodeInfo>, now: Long, again: () -> Unit): Boolean {
        fun t(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription ?: "").toString().trim()
        val title = all.firstOrNull { Regex("^(schedule post|schedule thread|agendar post|agendar thread|agendar publicação|agendar publicacao|programar publicação)$", RegexOption.IGNORE_CASE).matches(t(it)) && !it.isClickable } ?: return false
        val done = all.filter { Regex("^(done|concluir|concluído|concluido|ok|pronto)$", RegexOption.IGNORE_CASE).matches(t(it)) }.maxByOrNull { rect(it).top } ?: return false
        val top = rect(title).bottom; val bottom = rect(done).top
        val z = java.time.Instant.ofEpochMilli(cTarget).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val items = all.filter { n -> rect(n).centerY() in top..bottom && n !== title }.map { AgendaCore.WItem(t(it), rect(it).centerX(), rect(it).centerY()) }
        val wheels = all.filter { it.isScrollable && rect(it).centerY() in top..bottom }
        // faixa do meio: centro das rodinhas que rolam (todas têm o escolhido no meio); sem elas, o meio entre o título e o Done
        val band = wheels.map { rect(it).centerY() }.sorted().let { if (it.isEmpty()) (top + bottom) / 2 else it[it.size / 2] }
        val r = AgendaCore.wheelRead(items, band, dpx(45), today)
        if (r == null) { if (cTries % 6 == 0) saveTree(all, "rodinhas: não reconheci as colunas"); return false }
        val sig = r.toString()
        // mandou mexer e não mudou nada? aquela rodinha chegou no fim (ou não dá a volta): anota pra ir pelo outro lado / outro jeito
        val last = wLast
        if (last != null) {
            val (col, before, how) = last
            if (before == sig) { wStuck[col] = (wStuck[col] ?: 0) + 1; if (how == "acao") wGesture.add(col); if (col == 1) wNoWrap.add(1) } else wStuck.remove(col)
        }
        if (wLast?.second != sig) composeUntil = maxOf(composeUntil, System.currentTimeMillis() + 90_000L)
        val next = AgendaCore.wheelNext(r, z, wNoWrap)
        clog("rodinhas: $sig · alvo " + AgendaCore.fmt(cTarget) + (next?.let { " → coluna ${it.first} ${if (it.second > 0) "+" else ""}${it.second}" } ?: " → certo"), emptyList())
        if (next == null) {
            wLast = null
            val settle = AgendarTreino.settleMs(this)
            if (wOkSig != sig || now - wOkAt < settle) {
                if (wOkSig != sig) { wOkSig = sig; wOkAt = now }
                clog("rodinhas certas: esperando parar (${settle} ms)", emptyList())
                cTapAt = now; h.postDelayed({ if (composeUntil > System.currentTimeMillis()) composeStep() }, settle - (now - wOkAt) + 60); return true
            }
            // Done desligado (o Threads apaga o botão quando o horário não vale): analisa em vez de tocar
            if (!done.isEnabled || (clickable(done)?.isEnabled == false)) { doneProblem(r.toString()); again(); return true }
            clog("rodinhas paradas e certas ($sig) → Done", emptyList())
            if (click(done) || tapCenter(done)) { cStep = 7; cTries = 0; cTapAt = now; cDoneAt = now; cDoneSig = sig }
            again(); return true
        }
        wOkSig = ""
        val (col, diff) = next
        if ((wStuck[col] ?: 0) >= 4) { saveTree(all, "rodinha $col não anda: $sig"); cFail("A rodinha ${listOf("do dia", "da hora", "do minuto", "AM/PM")[col]} não anda.", all); return true }
        val dir = if (diff > 0) 1 else -1
        val x = r.colX.getOrNull(col) ?: return false
        val wheel = wheels.filter { rect(it).left <= x && rect(it).right >= x }.minByOrNull { rect(it).width() }
        // 1º jeito: o comando "rolar" da acessibilidade (no seu celular ele anda exatamente 1 linha); se não andar, arrasta com o dedo
        // longe do alvo: vários comandos de 1 linha de uma vez (no seu celular cada um anda exatamente 1 linha); relê e corrige
        val n = minOf(Math.abs(diff), when (col) { 3 -> 1; 1 -> 6; else -> 12 })
        var how = "gesto"
        if (wheel != null && col !in wGesture) {
            var ok = true
            repeat(n) { if (ok) ok = wheel.performAction(if (dir > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
            if (ok) how = "acao"
            else { // não dá pra rolar pra esse lado: é o fim da rodinha (ex.: 12 no topo). Não arrasta; vai pelo outro lado.
                wNoWrap.add(col); clog("rodinha $col: fim pra ${if (dir > 0) "baixo" else "cima"}", emptyList())
                wLast = Triple(col, sig, "fim"); cTapAt = now - 600; again(); return true
            }
        }
        if (how == "gesto") {
            // arrasto lento, de linha em linha (pra cima = valor maior), começando na faixa do meio da coluna
            val up = Store.prefs(this).getInt("ensino_sobe_$col", Store.prefs(this).getInt("ensino_sobe", 1)) // 1 = dedo pra cima aumenta
            val g = minOf(n, 3)
            val dy = -dir * up * g * r.rowH.toFloat(); val dur = (g * 600L).coerceAtLeast(500L)
            val path = android.graphics.Path().apply { moveTo(x.toFloat(), band.toFloat()); lineTo(x.toFloat(), band + dy) }
            try { dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null) } catch (e: Exception) { }
            cTapAt = now + dur
        } else cTapAt = now // o comando é rápido: relê em ~0,35 s
        wLast = Triple(col, sig, how)
        if (cTries % 15 == 14) saveTree(all, "rodinhas: $sig")
        again(); return true
    }
    private var wLast: Triple<Int, String, String>? = null
    private var wOkSig = ""; private var wOkAt = 0L; private var cDoneAt = 0L; private var cDoneSig = ""; private var cDoneProblems = 0

    /**
     * O Done não aceitou (apagado, ou a folha não fechou com as rodinhas certas): quase sempre é horário que o Threads não aceita
     * (já passou ou está perto demais). Empurra o horário 10 min pra frente (até 3x), anota no lote e no treino, e acerta de novo.
     */
    private fun doneProblem(read: String) {
        cDoneProblems++
        AgendarTreino.add(this, false, "done", cTarget, "Done não aceitou com $read (alvo ${AgendaCore.fmt(cTarget)})")
        if (cDoneProblems > 3) { cFail("O Threads não aceita esse horário (${AgendaCore.fmt(cTarget)}).", emptyList()); return }
        val novo = maxOf(cTarget, System.currentTimeMillis() + 15 * 60_000L) + 10 * 60_000L * (cDoneProblems - 1)
        clog("Done não aceitou ($read): horário ${AgendaCore.fmt(cTarget)} → ${AgendaCore.fmt(novo)}", emptyList())
        if (autoRunning) { val l = Batch.load(this); l.firstOrNull { it.optString("id") == autoItemId && !it.optBoolean("done") }?.let { it.put("at", novo).put("movido", true); Batch.save(this, l) } }
        cTarget = novo; cLabel = AgendaCore.fmt(novo); wOkSig = ""; cStep = 6; cTries = 0
        show("🗓 O Threads não aceitou esse horário: ajustando pra $cLabel…")
    }
    private val wStuck = HashMap<Int, Int>(); private val wGesture = HashSet<Int>(); private val wNoWrap = HashSet<Int>()

    private fun textsOf(all: List<AccessibilityNodeInfo>) = all.mapNotNull { (it.text ?: it.contentDescription)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() && s.length < 80 } }

    /**
     * Tela de escolher data e hora do Agendar. Lê o que está escolhido; se já é o alvo, confirma. Senão mexe:
     * linhas de data/hora (abre os seletores), calendário (toca no dia), relógio (modo teclado ou números), rodinhas (rola).
     * Só confirma quando LÊ na tela a data e a hora certas.
     */
    private fun pickerStep(all: List<AccessibilityNodeInfo>, now: Long, again: () -> Unit) {
        val z = java.time.Instant.ofEpochMilli(cTarget).atZone(java.time.ZoneId.systemDefault())
        val tDate = z.toLocalDate(); val tTime = java.time.LocalTime.of(z.hour, z.minute)
        val today = java.time.LocalDate.now()
        fun lab(n: AccessibilityNodeInfo) = norm(((n.contentDescription ?: n.text) ?: "").toString())
        fun act(n: AccessibilityNodeInfo, what: String): Boolean { clog(what, all); val ok = tap(n); if (ok) cTapAt = now; return ok }
        if (cTries == 0) saveTree(all, "tela de agendar")
        if (wheelSheet(all, now, again)) return
        val texts = textsOf(all)
        val sel = all.filter { it.isSelected || it.isChecked || it.isEditable }.mapNotNull { (it.text ?: it.contentDescription)?.toString() }
        // ---- calendário aberto? (dias com data completa na descrição, ou grade de números 1..31 com "Mês Ano" em cima)
        val days = all.mapNotNull { n -> n.contentDescription?.toString()?.let { d -> if (AgendaCore.findTime(listOf(d)) == null) AgendaCore.findDate(listOf(d), today)?.let { n to it } else null } }.toMutableList()
        val header = all.mapNotNull { n -> Regex("^([a-zç]+)\\.?\\s+(?:de\\s+)?(\\d{4})$").find(lab(n))?.let { m -> AgendaCore.findDate(listOf("1 " + m.groupValues[1] + " " + m.groupValues[2]), today)?.let { it.withDayOfMonth(1) } } }.firstOrNull()
        if (days.size < 20 && header != null) {
            val grid = all.filter { Regex("^\\d{1,2}$").matches(lab(it)) && (it.isClickable || clickable(it) != null) }
            if (grid.size >= 20) grid.forEach { n -> lab(n).toInt().let { d -> if (d <= header.lengthOfMonth()) days += n to header.withDayOfMonth(d) } }
        }
        if (days.size >= 20) {
            val month = header ?: days.groupingBy { it.second.withDayOfMonth(1) }.eachCount().maxByOrNull { it.value }!!.key
            val tKey = tDate.toEpochDay().toInt()
            val chosen = days.firstOrNull { (n, _) -> n.isSelected || n.isChecked }?.second
            val timeHere = AgendaCore.findTime(textsOf(all))
            val okRe = Regex("^(ok|done|concluir|concluído|next|avançar|avancar|próximo|proximo|set|definir|confirmar|confirm|salvar|save)$")
            if (chosen == tDate || cWheel["day"] == tKey) {
                // dia escolhido: se a hora também está nesta tela e está errada, ajusta a hora antes (cai pro resto); senão confirma a data
                if (timeHere == null || timeHere == tTime) {
                    val ok = all.filter { okRe.matches(lab(it)) }.maxByOrNull { rect(it).top }
                    if (ok != null && (cWheel["dayOk"] ?: 0) < 2) { cWheel["dayOk"] = (cWheel["dayOk"] ?: 0) + 1; act(ok, "calendário: confirmei o dia (" + lab(ok) + ")"); again(); return }
                }
            } else if (month.year == tDate.year && month.monthValue == tDate.monthValue) {
                val hit = days.firstOrNull { it.second == tDate }
                if (hit != null && (cWheel["dayTaps"] ?: 0) < 3) { cWheel["day"] = tKey; cWheel["dayTaps"] = (cWheel["dayTaps"] ?: 0) + 1; act(hit.first, "calendário: toquei no dia ${tDate.dayOfMonth}"); again(); return }
            } else {
                val fwd = month.isBefore(tDate.withDayOfMonth(1))
                all.firstOrNull { Regex(if (fwd) "next month|próximo mês|proximo mes|mês seguinte|mes seguinte|^next$|^›$|^>$" else "previous month|mês anterior|mes anterior|^previous$|^‹$|^<$").containsMatchIn(lab(it)) }
                    ?.let { act(it, "calendário: mês " + (if (fwd) "›" else "‹")); again(); return }
                // sem botão de mês: rola a grade
                all.firstOrNull { it.isScrollable }?.let { it.performAction(if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); clog("calendário: rolei o mês", all); cTapAt = now; again(); return }
            }
            if (cTries % 8 == 7) saveTree(all, "calendário: mês $month, escolhido $chosen, alvo $tDate")
        }
        // ---- relógio do Android: passa pro modo teclado se der
        all.firstOrNull { Regex("text input|entrada de texto|modo de texto|keyboard|teclado").containsMatchIn(lab(it)) && it.isClickable }?.let { if (all.none { it.isEditable && it.className?.toString()?.contains("EditText") == true && Regex("^\\d{1,2}$").matches((it.text ?: "").toString()) }) { act(it, "relógio: modo teclado"); again(); return } }
        val twelve = all.any { Regex("^(am|pm|a\\.m\\.|p\\.m\\.)$").matches(lab(it)) }
        val hTxt = if (twelve) (if (tTime.hour % 12 == 0) 12 else tTime.hour % 12).toString() else tTime.hour.toString()
        // campos de hora/minuto editáveis (relógio em modo teclado ou seletor com texto)
        val numEd = all.filter { it.isEditable && Regex("^\\d{1,2}$").matches((it.text ?: "").toString().trim()) }.sortedBy { rect(it).left }
        if (numEd.size >= 2 && days.isEmpty() && cWheel["kb"] == null) {
            fun set(n: AccessibilityNodeInfo, v: String) = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v) })
            set(numEd[0], hTxt); set(numEd[1], "%02d".format(tTime.minute)); cWheel["kb"] = 1
            if (twelve) all.firstOrNull { lab(it).startsWith(if (tTime.hour < 12) "a" else "p") && Regex("^(am|pm|a\\.m\\.|p\\.m\\.)$").matches(lab(it)) }?.let { tap(it) }
            clog("relógio: digitei $hTxt:%02d".format(tTime.minute), all); cTapAt = now; again(); return
        }
        // ---- leitura do que está escolhido
        val readT = AgendaCore.findTime(sel + texts + texts.joinToString(" "))
        val readD = AgendaCore.findDate(sel + texts, today)
        val dialog = all.any { OKBTN.matches(lab(it)) && Regex("^(ok|set|definir|done|concluir)$").matches(lab(it)) } && all.any { Regex("^(cancel|cancelar)$").matches(lab(it)) }
        // relógio com números (modo ponteiro): toca na hora, depois no minuto
        val dial = all.filter { Regex("^\\d{1,2}$").matches(lab(it)) }
        if (dialog && dial.size >= 10) {
            val minuteMode = dial.any { lab(it) == "05" || lab(it) == "55" }
            if (!minuteMode && cWheel["dialH"] == null) { dial.firstOrNull { lab(it).toIntOrNull() == hTxt.toInt() || (lab(it) == "00" && tTime.hour == 0) }?.let { cWheel["dialH"] = 1; act(it, "relógio: hora $hTxt"); again(); return } }
            if (minuteMode && cWheel["dialM"] == null) { dial.firstOrNull { lab(it) == "%02d".format(tTime.minute) }?.let { cWheel["dialM"] = 1; act(it, "relógio: minuto"); again(); return } }
            if (twelve) all.firstOrNull { lab(it).startsWith(if (tTime.hour < 12) "a" else "p") && Regex("^(am|pm|a\\.m\\.|p\\.m\\.)$").matches(lab(it)) && !(it.isChecked || it.isSelected) }?.let { act(it, "relógio: am/pm"); again(); return }
            all.firstOrNull { Regex("^(ok|set|definir|done|concluir)$").matches(lab(it)) }?.let { cWheel.remove("dialH"); cWheel.remove("dialM"); act(it, "relógio: ok"); again(); return }
        }
        if (dialog && cWheel["kb"] != null) { all.firstOrNull { Regex("^(ok|set|definir|done|concluir)$").matches(lab(it)) }?.let { cWheel.remove("kb"); act(it, "relógio: ok"); again(); return } }
        val sig = "lido $readD $readT · alvo $tDate $tTime · dias ${days.size}"
        if (Store.prefs(this).getString("ag_pick_sig", "") != sig) { Store.prefs(this).edit().putString("ag_pick_sig", sig).apply(); clog(sig, all) }
        // ---- já está certo: confirma
        val dateOk = readD == tDate || (readD == null && tDate == today && texts.any { Regex("(?i)hoje|today").containsMatchIn(it) }) || (cWheel["dayOk"] != null && readD == null) || (days.size >= 20 && cWheel["day"] == tDate.toEpochDay().toInt())
        if (readT == tTime && dateOk) {
            val b = all.filter { val l = lab(it); OKBTN.matches(l) && !POSTBTN.matches(l) && it.isClickable || (OKBTN.matches(l) && clickable(it) != null) }.maxByOrNull { rect(it).top }
            if (b != null) { if (act(b, "confirmei " + AgendaCore.fmt(cTarget) + " (" + lab(b) + ")")) { cStep = 7; cTries = 0 }; again(); return }
        }
        // ---- rodinhas (NumberPicker ou listas que rolam): ajusta cada coluna até bater
        val wheels = all.filter { it.isScrollable && (it.className?.toString()?.contains("Picker") == true || rect(it).height() < resources.displayMetrics.heightPixels / 2) && rect(it).width() < resources.displayMetrics.widthPixels * 0.6 }.sortedBy { rect(it).left }
        if (wheels.isNotEmpty() && (readT != tTime || !dateOk)) {
            for ((i, w) in wheels.withIndex()) {
                val vals = (0 until w.childCount).mapNotNull { w.getChild(it) }.mapNotNull { c -> (c.text ?: c.contentDescription)?.toString()?.trim() }
                val cur = vals.getOrNull(vals.size / 2) ?: continue
                val want: String? = when {
                    Regex("^(am|pm|a\\.m\\.|p\\.m\\.)$", RegexOption.IGNORE_CASE).matches(cur) -> if (tTime.hour < 12) "a" else "p"
                    AgendaCore.findDate(listOf(cur), today) != null || Regex("(?i)hoje|today|amanh|tomorrow").containsMatchIn(cur) -> "D"
                    Regex("^\\d{1,2}$").matches(cur) -> if (i == wheels.indexOfFirst { ww -> (0 until ww.childCount).any { Regex("^\\d{1,2}$").matches((ww.getChild(it)?.text ?: "").toString()) } }) hTxt else "%02d".format(tTime.minute)
                    else -> null
                }
                val good = when (want) { null -> true; "a", "p" -> cur.lowercase().startsWith(want); "D" -> (AgendaCore.findDate(listOf(cur), today) ?: if (Regex("(?i)hoje|today").containsMatchIn(cur)) today else if (Regex("(?i)amanh|tomorrow").containsMatchIn(cur)) today.plusDays(1) else null) == tDate
                    else -> cur.toIntOrNull() == want.toIntOrNull() }
                if (good) continue
                val key = "w$i"
                val dir = cWheel[key] ?: run {
                    val c = cur.toIntOrNull(); val t = want?.toIntOrNull()
                    when { want == "D" -> if ((AgendaCore.findDate(listOf(cur), today) ?: today).isBefore(tDate)) 1 else -1; c != null && t != null -> if (t > c) 1 else -1; else -> 1 }
                }
                cWheel[key] = dir
                val ok = w.performAction(if (dir > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (!ok) cWheel[key] = -dir
                clog("rodinha $i: $cur → $want", all); cTapAt = now - 1000; again(); return
            }
        }
        // ---- linhas "data" / "hora" que abrem os seletores
        fun rowOf(isDate: Boolean) = all.filter { n -> val t = (n.text ?: n.contentDescription)?.toString().orEmpty()
            if (isDate) AgendaCore.findDate(listOf(t), today) != null || Regex("(?i)^(hoje|today|amanhã|amanha|tomorrow)").containsMatchIn(t) else AgendaCore.findTime(listOf(t)) != null }.mapNotNull { clickable(it) }.firstOrNull()
        if (!dateOk && cWheel["row_d"] == null) rowOf(true)?.let { cWheel["row_d"] = 1; act(it, "abri a data (lida: $readD)"); again(); return }
        if (readT != tTime && cWheel["row_t"] == null) rowOf(false)?.let { cWheel["row_t"] = 1; act(it, "abri a hora (lida: $readT)"); again(); return }
        // tentou tudo e voltou pra tela: libera abrir de novo
        if (cTries % 6 == 5) { cWheel.remove("row_d"); cWheel.remove("row_t") }
        if (cTries % 10 == 9) { saveTree(all, "tentativa $cTries: lido $readD $readT, alvo $tDate $tTime"); clog("lido $readD $readT ≠ alvo $tDate $tTime", all) }
        again()
    }

    /** Aviso por cima do app aberto (usado pelo agendamento em lote). */
    fun hint(msg: String, ms: Long) { h.post { show(msg, 0xEE15803D.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, ms) } }

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
            m["shares"]?.let { "↗ $it" }, m["saves"]?.let { "🔖 $it" }, m["visits"]?.let { "👤 $it" }, m["fol"]?.let { "👥 +$it" }, m["xviews"]?.let { "⚡${it / 10.0}x" })
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
        hideReadBtn()
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
        val texts = collect(appPkg)
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
        if (insights) extras(nt, m)
        if (m.isEmpty()) return
        m["follows"]?.let { m["fol"] = it; m.remove("follows") }
        ThreadsApi.upsertOrphan(this, currentCap, m, if (insights) "insights" else "tela")
        captured(m)
        if (!running) harvest(currentCap) // na coleta automática a tela troca rápido: guarda só a legenda depois
        else if (Harvest.shouldSave(this, currentCap)) { Harvest.saveCaption(this, currentCap); Harvest.done(this, currentCap) }
    }

    /**
     * 🏆 Post de fora que bombou: guarda a legenda e tira um "print" só da foto do post (sem os avisos do app por cima).
     * Depois procura a foto original na galeria pela impressão digital da imagem.
     */
    private fun harvest(cap: String) {
        if (!Harvest.shouldSave(this, cap)) return
        Harvest.saveCaption(this, cap)
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        val dm = resources.displayMetrics
        val img = root?.let { r -> nodes(r).filter { it.isVisibleToUser && it.className?.toString()?.contains("Image") == true }.map { rect(it) }
            .filter { it.width() >= dpx(90) && it.height() >= dpx(90) && it.width() < dm.widthPixels * 1.01 }.maxByOrNull { it.width() * it.height() } }
        fun finish(msg: String) { Harvest.done(this, cap); show("🏆 Post de fora que bombou: legenda salva no banco · $msg", 0xEE15803D.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 5000) }
        if (img == null || android.os.Build.VERSION.SDK_INT < 30) { Thread { val m = Harvest.savePhoto(this, cap, null); h.post { finish(m) } }.start(); return }
        // esconde os avisos e o botão do app pra não sair no recorte
        pill?.visibility = android.view.View.GONE; readBtn?.visibility = android.view.View.GONE
        h.postDelayed({
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(s: ScreenshotResult) {
                        val crop = try {
                            val hb = s.hardwareBuffer
                            val full = Bitmap.wrapHardwareBuffer(hb, s.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false); hb.close()
                            full?.let { f -> val r = android.graphics.Rect(img); r.intersect(0, 0, f.width, f.height); if (r.width() > 10 && r.height() > 10) Bitmap.createBitmap(f, r.left, r.top, r.width(), r.height()) else null }
                        } catch (e: Exception) { null }
                        pill?.visibility = android.view.View.VISIBLE; readBtn?.visibility = android.view.View.VISIBLE
                        Thread { val m = try { Harvest.savePhoto(this@ScreenReader, cap, crop) } catch (e: Exception) { ErrLog.log(this@ScreenReader, "salvar post de fora", e); "a foto deu erro" }; h.post { finish(m) } }.start()
                    }
                    override fun onFailure(code: Int) {
                        pill?.visibility = android.view.View.VISIBLE; readBtn?.visibility = android.view.View.VISIBLE
                        finish("a foto não deu (desligue e ligue o Leitor de tela uma vez pra liberar o print)")
                    }
                })
            } catch (e: Exception) { pill?.visibility = android.view.View.VISIBLE; readBtn?.visibility = android.view.View.VISIBLE; finish("a foto não deu") }
        }, 250)
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

    // ================= 🗓 você agendou NA MÃO no Threads: detecta sozinho e anota a data/hora =================
    private var swLast = 0L; private var swAt: java.time.ZonedDateTime? = null; private var swSeen = 0L; private var swCap = ""; private var swCapAt = 0L; private var swDone = ""; private var swPicked = false
    private val SW_WORD = Regex("schedul|agend|program", RegexOption.IGNORE_CASE)
    private val SW_CONFIRM = Regex("^(schedule|agendar|programar|done|concluir|concluído|ok|salvar|save|confirmar|confirm)$", RegexOption.IGNORE_CASE)
    private val SW_TOAST = Regex("(scheduled|agendad[oa]|programad[oa])", RegexOption.IGNORE_CASE)

    /** Lê a tela de agendar (data/hora escolhidas) e a legenda do post; confirma quando você toca em Agendar ou o Threads avisa. */
    private fun schedWatch(e: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val type = e.eventType
        // texto avisando que agendou (toast/snackbar do Threads)
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val t = e.text.joinToString(" ")
            if (SW_TOAST.containsMatchIn(t)) { AgendaCore.parseSchedule(listOf(t), java.time.ZonedDateTime.now())?.let { swAt = it; swSeen = now }; confirmDetected("aviso do Threads") ; return }
        }
        // a legenda que você está escrevendo
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) { e.text.joinToString(" ").trim().takeIf { it.length >= 3 }?.let { swCap = it; swCapAt = now }; return }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val t = (e.text.joinToString(" ").ifBlank { e.contentDescription?.toString().orEmpty() }).trim()
            if (SW_CONFIRM.matches(t)) {
                // lê a tela uma última vez (o horário pode ter mudado) e confirma se for o botão final de agendar
                val visible = readSchedScreen(now)
                // botão "Agendar" com a data na tela (ou depois de você ter escolhido a data e tocado em Concluir) = agendou.
                // O item "Agendar…" do menu (sem data na tela ainda) não conta.
                if (Regex("^(schedule|agendar|programar)$", RegexOption.IGNORE_CASE).matches(t)) { if (visible || swPicked) confirmDetected("tocou em $t") }
                else if (visible) swPicked = true
            }
            return
        }
        if (now - swLast < 700) return
        swLast = now
        readSchedScreen(now)
    }

    /** true = tem data/hora de agendamento na tela agora. */
    private fun readSchedScreen(now: Long): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != ThreadsNotif.THREADS) return false
        val all = nodes(root)
        all.firstOrNull { it.isEditable && (it.text?.length ?: 0) >= 3 && !(it.text ?: "").toString().startsWith("@") }?.let { n ->
            val t = n.text.toString().trim(); val hintT = if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
            if (t != hintT) { swCap = t; swCapAt = now }
        }
        val texts = all.mapNotNull { (it.text ?: it.contentDescription)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() && s.length < 80 } }
        if (texts.none { SW_WORD.containsMatchIn(it) }) return false
        val joined = texts.joinToString(" ")
        val z = AgendaCore.parseSchedule(texts + joined, java.time.ZonedDateTime.now()) ?: return false
        if (z.toInstant().toEpochMilli() < now - 120_000L) return false
        if (swAt != z) Store.prefs(this).edit().putString("ag_sw_seen", z.toString() + " · " + texts.filter { SW_WORD.containsMatchIn(it) || AgendaCore.findTime(listOf(it)) != null }.take(4).joinToString(" | ")).apply()
        if (swAt != z) swPicked = false
        if (Store.prefs(this).getLong("ag_tree_at", 0) < now - 6 * 3600_000L) { saveTree(all, "você agendando"); Store.prefs(this).edit().putLong("ag_tree_at", now).apply() }
        swAt = z; swSeen = now
        return true
    }

    private fun confirmDetected(why: String) {
        val now = System.currentTimeMillis()
        val z = swAt ?: return
        if (now - swSeen > 15 * 60_000L) return
        val at = z.toInstant().toEpochMilli()
        if (at < now - 120_000L) return
        val cap = if (now - swCapAt < 30 * 60_000L) swCap else ""
        val key = "$at|$cap"
        if (key == swDone) return
        swDone = key; swAt = null; swPicked = false
        Thread {
            try {
                val msg = Agenda.addDetected(this, at, cap)
                Store.prefs(this).edit().putString("ag_sw_last", AgendaCore.fmt(at) + " ($why)").apply()
                hint(msg, 6000)
                Agenda.get(this, Agenda.all(this).filter { it.optString("mode") == "threads" }.minByOrNull { Math.abs(it.optLong("at") - at) }?.optString("id").orEmpty())
                    ?.let { Agenda.notifySimple(this, it, msg, (if (cap.isNotBlank()) "“" + cap.take(80) + "”\n" else "") + "O Threads publica sozinho nessa hora. Quando ele avisar que publicou, a Agenda marca ✓.") }
            } catch (x: Exception) { ErrLog.log(this, "agenda: detectar agendamento", x) }
        }.start()
    }

    // ================= 🎓 ENSINAR: você agenda 1 post na mão e eu gravo onde tocou e como mexeu as rodinhas =================
    private var tLastRead: AgendaCore.WRead? = null
    fun teachStart() {
        Store.prefs(this).edit().putLong("ensinar_ate", System.currentTimeMillis() + 15 * 60_000L).putString("ensino_novo", "[]").apply()
        tLastRead = null
        show("🎓 Gravando: agende 1 post na mão no Threads (foto, legenda, ⋯ › Schedule…, rodinhas, Done, Schedule). Eu aprendo cada passo.", 0xEE7C3AED.toInt())
        try { if (DualApp.launch(this) == "") throw Exception("sem Threads") } catch (x: Exception) { }
    }
    private fun teachOn() = Store.prefs(this).getLong("ensinar_ate", 0) > System.currentTimeMillis()
    private fun teachAdd(o: org.json.JSONObject) {
        val a = try { org.json.JSONArray(Store.prefs(this).getString("ensino_novo", "[]")) } catch (x: Exception) { org.json.JSONArray() }
        a.put(o.put("at", System.currentTimeMillis())); while (a.length() > 120) a.remove(0)
        Store.prefs(this).edit().putString("ensino_novo", a.toString()).apply()
    }
    private fun teachCtx(all: List<AccessibilityNodeInfo>): String {
        fun l(n: AccessibilityNodeInfo) = norm(((n.contentDescription ?: n.text) ?: "").toString())
        return when {
            all.any { Regex("^(schedule post|agendar post|agendar publicação)$").matches(l(it)) } -> "folha"
            all.any { Regex("photo thumbnail|miniatura").containsMatchIn(l(it)) } -> "galeria"
            all.any { it.isEditable } -> "post"
            else -> "outro"
        }
    }
    private fun teachRead(all: List<AccessibilityNodeInfo>): AgendaCore.WRead? {
        fun l(n: AccessibilityNodeInfo) = ((n.text ?: n.contentDescription) ?: "").toString().trim()
        val title = all.firstOrNull { Regex("^(schedule post|agendar post|agendar publicação)$", RegexOption.IGNORE_CASE).matches(l(it)) } ?: return null
        val done = all.filter { Regex("^(done|concluir|ok|pronto)$", RegexOption.IGNORE_CASE).matches(l(it)) }.maxByOrNull { rect(it).top } ?: return null
        val top = rect(title).bottom; val bottom = rect(done).top
        val wheels = all.filter { it.isScrollable && rect(it).centerY() in top..bottom }
        val band = wheels.map { rect(it).centerY() }.sorted().let { if (it.isEmpty()) (top + bottom) / 2 else it[it.size / 2] }
        return AgendaCore.wheelRead(all.filter { rect(it).centerY() in top..bottom }.map { AgendaCore.WItem(l(it), rect(it).centerX(), rect(it).centerY()) }, band, dpx(45), java.time.LocalDate.now())
    }
    private fun teachEvent(e: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != ThreadsNotif.THREADS) return
        val all = nodes(root).filter { it.isVisibleToUser }
        val dm = resources.displayMetrics
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val src = e.source
                val label = (e.text.joinToString(" ").ifBlank { e.contentDescription?.toString().orEmpty() }).trim().ifEmpty { src?.let { n -> ((n.contentDescription ?: n.text) ?: "").toString() }.orEmpty() }
                val r = src?.let { rect(it) }
                teachAdd(org.json.JSONObject().put("t", "toque").put("l", label.take(40)).put("id", src?.viewIdResourceName.orEmpty()).put("c", src?.className?.toString()?.substringAfterLast('.').orEmpty())
                    .put("x", r?.let { it.centerX().toDouble() / dm.widthPixels } ?: -1.0).put("y", r?.let { it.centerY().toDouble() / dm.heightPixels } ?: -1.0).put("ctx", teachCtx(all)))
                // tocou no Schedule final (no post, depois da folha): acabou a aula
                if (Regex("^(schedule|agendar|programar)$", RegexOption.IGNORE_CASE).matches(label) && teachCtx(all) == "post" && Store.prefs(this).getString("ensino_novo", "").orEmpty().contains("\"folha\"")) {
                    val novo = Store.prefs(this).getString("ensino_novo", "[]") ?: "[]"
                    Store.prefs(this).edit().putLong("ensinar_ate", 0).putString("ensino", novo).apply()
                    saveTeach()
                    val n = try { org.json.JSONArray(novo).length() } catch (x: Exception) { 0 }
                    show("🎓 Aprendi! Gravei $n passos. Agora o 'Agendar todos' usa o que você fez.", 0xEE15803D.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 8000)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = teachRead(all) ?: return
                val before = tLastRead; tLastRead = now
                if (before == null) return
                // qual coluna mudou e pra que lado o valor foi
                val d = listOf(
                    0 to (if (before.date != null && now.date != null) java.time.temporal.ChronoUnit.DAYS.between(before.date, now.date).toInt() else 0),
                    1 to ((now.hour ?: 0) % 12 - (before.hour ?: 0) % 12), 2 to ((now.minute ?: 0) - (before.minute ?: 0)),
                    3 to (if (before.pm != null && now.pm != null && before.pm != now.pm) (if (now.pm == true) 1 else -1) else 0))
                val ch = d.firstOrNull { it.second != 0 } ?: return
                val dy = if (android.os.Build.VERSION.SDK_INT >= 28) e.scrollDeltaY else 0
                teachAdd(org.json.JSONObject().put("t", "rodinha").put("col", ch.first).put("mudou", ch.second).put("dy", dy).put("de", before.toString()).put("para", now.toString()))
                // dedo pra cima faz o conteúdo rolar pra frente (dy > 0): se o valor aumentou, "pra cima aumenta"
                if (dy != 0) {
                    val sobe = if ((dy > 0) == (ch.second > 0)) 1 else -1
                    Store.prefs(this).edit().putInt("ensino_sobe_${ch.first}", sobe).putInt("ensino_sobe", sobe).apply()
                }
            }
        }
    }

    /** 💾 Guarda a configuração que deu certo (aula + sentido das rodinhas) num arquivo: sobrevive a atualização e a limpar a aula. */
    fun saveTeach() {
        val p = Store.prefs(this)
        val o = org.json.JSONObject().put("ensino", p.getString("ensino", "[]")).put("salvo", System.currentTimeMillis())
        for (k in listOf("ensino_sobe", "ensino_sobe_0", "ensino_sobe_1", "ensino_sobe_2", "ensino_sobe_3")) if (p.contains(k)) o.put(k, p.getInt(k, 1))
        try { java.io.File(filesDir, "ensino_agendar.json").writeText(o.toString()) } catch (x: Exception) { }
        p.edit().putLong("ensino_salvo", System.currentTimeMillis()).apply()
    }
    /** Volta a configuração salva se a atual sumiu. */
    private fun loadTeach() {
        val p = Store.prefs(this)
        if ((p.getString("ensino", "") ?: "").length > 2) return
        try {
            val o = org.json.JSONObject(java.io.File(filesDir, "ensino_agendar.json").readText())
            val e = p.edit().putString("ensino", o.optString("ensino", "[]"))
            o.keys().forEach { k -> if (k.startsWith("ensino_sobe")) e.putInt(k, o.optInt(k, 1)) }
            e.apply()
        } catch (x: Exception) { }
    }

    /** O que você ensinou: o rótulo do ⋯ que abriu o menu com o Schedule (o toque antes do toque em "Schedule…"). */
    private fun learnedMenuLabel(): String? = try {
        loadTeach()
        val a = org.json.JSONArray(Store.prefs(this).getString("ensino", "[]"))
        (1 until a.length()).firstOrNull { i -> a.getJSONObject(i).optString("t") == "toque" && Regex("^(schedule|agendar|programar)").containsMatchIn(norm(a.getJSONObject(i).optString("l"))) && a.getJSONObject(i).optString("ctx") != "folha" }
            ?.let { i -> norm(a.getJSONObject(i - 1).optString("l")).takeIf { it.isNotEmpty() } }
    } catch (x: Exception) { null }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val ep = e?.packageName?.toString()
        if (ep == ThreadsNotif.THREADS && e != null && teachOn()) try { teachEvent(e) } catch (x: Exception) { ErrLog.log(this, "ensinar", x) }
        if (ep == ThreadsNotif.THREADS && e != null) try { schedWatch(e) } catch (x: Exception) { ErrLog.log(this, "agenda: leitor", x) }
        // eventos novos (toque, digitação, avisos) só servem pra agenda: o resto do leitor continua como antes
        if (e != null && e.eventType in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)) return
        if (!running && Macro.on(this) && ep == Macro.app(this)) { appPkg = ep; doneCells.clear(); startMacro() }
        if (ep != null && !running) try { forceCaption(ep) } catch (x: Exception) { }
        if (ep == ThreadsNotif.THREADS && composeUntil > System.currentTimeMillis()) try { composeStep() } catch (x: Exception) { ErrLog.log(this, "agendar: passo $cStep", x) }
        else if (ep == ThreadsNotif.THREADS && schedUntil > System.currentTimeMillis()) try { trySchedule() } catch (x: Exception) { }
        if ((ep == ThreadsNotif.THREADS || ep == ThreadsNotif.INSTA) && !running) h.post { showReadBtn() }
        if (ep == ThreadsRefs.THREADS) ThreadsRefs.threadsActive(this) // 📌 salvou mídia no Threads? já puxa pras Referências
        if (running) return // durante a coleta, quem lê é o próprio macro
        if (Macro.paused(this)) return // ⏸ pausado: não lê nada
        manualPkg = e?.packageName?.toString().orEmpty()
        val now = System.currentTimeMillis()
        // leve: só lê quando a tela troca/rola, no máximo a cada 3 s (antes lia a cada mudança, deixando o Threads pesado)
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - last < 6000) return
        if (now - last < 3000) return
        last = now
        val pkg = e?.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return
        val texts = try { collect(pkg) } catch (x: Exception) { return }
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
        "viewers" to Regex("^(viewers|espectadores|pessoas alcan[cç]adas|contas que viram|alcance)$", RegexOption.IGNORE_CASE),
        "visits" to Regex("^(profile visits|visitas ao perfil|visitas no perfil)$", RegexOption.IGNORE_CASE),
        "follows" to Regex("^(seguidores|follows|novos seguidores|seguiram|seguimentos|follows gained)$", RegexOption.IGNORE_CASE))

    private fun numOf(t: String): Int? {
        // "57m"/"5h"/"3d" são HORÁRIOS do post (57 minutos atrás), não números: só aceita mil/k/mi/milhões
        val m = Regex("^(\\d[\\d.,]*)\\s*(mil|k|mi|milh[oõ]es|mi\\.)?$", RegexOption.IGNORE_CASE).find(t.trim()) ?: return null
        val mult = when (m.groupValues[2].lowercase().trimEnd('.')) { "mil", "k" -> 1000.0; "mi", "milhões", "milhoes" -> 1_000_000.0; else -> 1.0 }
        val s = m.groupValues[1]; val clean = if (mult > 1) s.replace(',', '.') else s.replace(".", "").replace(",", "")
        return ((clean.toDoubleOrNull() ?: return null) * mult).toInt()
    }

    private val VS = Regex("^(higher|lower|typical|maior|menor|t[ií]pico|t[ií]pica|acima( do normal)?|abaixo( do normal)?|normal|mais alto|mais baixo|above|below)$", RegexOption.IGNORE_CASE)

    /**
     * Tela de insights do post: o que vai além dos rótulos.
     * - a comparação da própria Meta embaixo de cada número (Higher/Typical/Lower) → vs_views, vs_fol...
     * - o resumo da Meta AI ("16x more views") → xviews (x10)
     * - a fileira de ícones do post (❤ 57 · 💬 24 · 🔁 0 · ↗ 0), que não tem rótulo
     */
    private fun extras(nt: List<String>, m: MutableMap<String, Int>) {
        fun lab(t: String) = LABELS.entries.firstOrNull { it.value.matches(t) }?.key
        for (i in nt.indices) {
            val k = lab(nt[i]) ?: continue
            val j = (i + 1..minOf(nt.size - 1, i + 3)).firstOrNull { VS.matches(nt[it]) } ?: continue
            if ((i + 1 until j).none { numOf(nt[it]) != null }) continue
            val v = nt[j].lowercase()
            m["vs_" + (if (k == "follows") "fol" else k)] = when { Regex("high|maior|acima|mais alto|above").containsMatchIn(v) -> 1; Regex("low|menor|abaixo|mais baixo|below").containsMatchIn(v) -> -1; else -> 0 }
        }
        nt.firstNotNullOfOrNull { Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:x|vezes)\\s+(?:more|mais)").find(it) }?.let { r ->
            r.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { if (it in 0.1..500.0) m["xviews"] = (it * 10).toInt() }
        }
        if (m["likes"] == null) {
            val stop = nt.indexOfFirst { it == "summary" || it == "resumo" || lab(it) != null }.let { if (it < 0) nt.size else it }
            var run = ArrayList<Int>()
            for (i in 0 until stop) { val v = numOf(nt[i]); if (v != null) run.add(v) else if (run.size in 1..2) run = ArrayList() else if (run.size >= 3) break }
            if (run.size >= 3) listOf("likes", "replies", "reposts", "shares").forEachIndexed { k, key -> run.getOrNull(k)?.let { m.putIfAbsent(key, it) } }
        }
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
        // "Curtir, 57" / "Like 57" / "Curtidas: 57" (botão com descrição)
        for (i in from until to) Regex("^(.+?)[,:]?\\s+(\\d[\\d.,]*\\s*(?:mil|k|mi)?)$", RegexOption.IGNORE_CASE).find(nt[i])?.let { mm ->
            lab(mm.groupValues[1].trim())?.let { k -> numOf(mm.groupValues[2])?.let { v -> out.putIfAbsent(k, v) } } }
        return out
    }

    private fun scan(pkg: String, texts: List<String>): List<Map<String, Int>> {
        val got = ArrayList<Map<String, Int>>()
        val video = pkg == ThreadsNotif.INSTA
        val nt = texts.map { norm(it) }
        // 1) perfil: número de seguidores (vale pros 2 apps; guardamos só o do Threads)
        val me = Macro.handle(this).lowercase()
        fun shows(h: String) = nt.any { it == h || it == "@$h" || it.startsWith("$h ") || it.startsWith("@$h ") }
        // 👩 conta de OUTRA modelo aberta no app? Os números são dela: não guardo aqui (troque de modelo no Post Leve)
        val meIg = Macro.igHandle(this).lowercase()
        val foreign = Profiles.otherHandles(this).entries.firstOrNull { (h, _) -> h != me && h != meIg && shows(h) }
        if (foreign != null && !(me.isNotEmpty() && shows(me)) && !(meIg.isNotEmpty() && shows(meIg))) {
            lastForeign = foreign.value
            if (System.currentTimeMillis() - foreignWarn > 60_000L) { foreignWarn = System.currentTimeMillis(); show("👩 Esta é a conta de ${foreign.value}: troque de modelo no Post Leve pra guardar os números dela", 0xEEB45309.toInt()); h.postDelayed({ if (!running && !forcing) clearOverlay() }, 4000) }
            return got
        }
        lastForeign = null
        // seguidores: só quando a tela é do SEU perfil (com o seu @ na tela, se você já colocou o @)
        val ownProfile = if (me.isNotEmpty()) shows(me) else nt.any { it == "editar perfil" || it == "edit profile" || it == "compartilhar perfil" || it == "share profile" }
        // lista dos seus seguidores: "Followers" / "2,774" (rótulo e número separados)
        if (!video && ownProfile) nt.indexOfFirst { it == "followers" || it == "seguidores" }.takeIf { it >= 0 && it + 1 < nt.size }?.let { i -> numOf(nt[i + 1])?.takeIf { it > 0 }?.let { ThreadsApi.addFollowers(this, it, "lista"); try { ThreadsApi.repairNow(this) } catch (e: Exception) { } } }
        if (!video && ownProfile) nt.firstNotNullOfOrNull { FOLLOW.find(it) }?.let { m -> numOf(m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""))?.let { ThreadsApi.addFollowers(this, it, "tela") } }
        val sends = SendLog.all(this).filter { System.currentTimeMillis() - it.optLong("at") < 60 * 86400_000L && (it.optString("kind") == "video") == video }
        if (sends.isEmpty()) return got
        val insights = nt.any { LABELS["views"]!!.matches(it) } && nt.count { t -> LABELS.values.any { it.matches(t) } } >= 3
        var found = 0
        // Cada legenda da tela vai pra UM envio só. Suas legendas começam parecido ("Oiii Flavia MENCIONOU…"), então
        // escolho pelo trecho em comum mais longo e, se empatar, pela idade do post que aparece na tela (1h, 2d…).
        // Na tela de insights só existe 1 post: no máximo 1 envio recebe os números.
        val hits = HashMap<Int, ArrayList<JSONObject>>()
        sends.filter { core(it.optString("cap")).length >= 8 }.forEach { s -> val i = nt.indexOfFirst { sameCap(it, s.optString("cap")) }; if (i >= 0) hits.getOrPut(i) { ArrayList() } += s }
        val chosen = hits.mapNotNull { (i, cands) -> chooseSend(cands, nt[i], ageNear(nt, i))?.let { i to it } }.sortedBy { it.first }
            .let { l -> if (insights) l.take(1) else l }.distinctBy { it.second.optLong("at") }
        chosen.forEach { (idx, s) ->
            found++
            // 2) tela de insights do post: pega todos os rótulos da tela; 3) feed/perfil: só os botões logo depois da legenda
            val nextCap = chosen.map { it.first }.firstOrNull { it > idx } ?: nt.size
            val m = if (insights) pairs(nt, 0, nt.size) else pairs(nt, idx + 1, minOf(nt.size, idx + 12, nextCap))
            val mm = m.toMutableMap()
            if (insights) extras(nt, mm)
            if (!insights && mm["likes"] == null) { // sem rótulo: 1º número depois da legenda = curtidas
                for (j in idx + 1..minOf(nt.size - 1, idx + 8, nextCap - 1)) { numOf(nt[j])?.let { mm["likes"] = it }; if (mm["likes"] != null) break }
            }
            mm["follows"]?.let { mm["fol"] = it; mm.remove("follows") }
            val force = if (insights && curItems.size == texts.size) learnedRead(curItems, pkg).also { mm.putAll(it) }.keys else emptySet()
            if (mm.isEmpty()) return@forEach
            got += mm; lastHit = s
            if (video) {
                mm["likes"]?.let { Likes.record(this, "video", s.optString("id"), it, "tela") }
                ThreadsApi.upsertLocal(this, s.optLong("at"), s.optString("id"), s.optString("cap"), mm, if (insights) "insights" else "tela", "video", force)
            } else {
                mm["likes"]?.let { Likes.record(this, "fila", s.optString("id"), it, "tela") }
                ThreadsApi.upsertLocal(this, s.optLong("at"), s.optString("id"), s.optString("cap"), mm, if (insights) "insights" else "tela", "fila", force)
            }
        }
        if (found > 0) Store.prefs(this).edit().putLong("tela_last", System.currentTimeMillis()).putInt("tela_found", found).apply()
        return got
    }

    private fun common(a: String, b: String): Int { var i = 0; while (i < a.length && i < b.length && a[i] == b[i]) i++; return i }

    /** Idade do post (horas) escrita perto da legenda: "1h", "35 min", "2d", "3 sem", "ontem". */
    private fun ageNear(nt: List<String>, i: Int): Double? {
        for (j in (i - 1 downTo maxOf(0, i - 6)) + (i + 1..minOf(nt.size - 1, i + 2))) {
            val t = nt[j]; val m = Regex("^(\\d+)\\s*(s|min|m|h|d|sem|w)$").find(t)
            if (m != null) { val n = m.groupValues[1].toDouble(); return when (m.groupValues[2]) { "s" -> n / 3600; "min", "m" -> n / 60; "h" -> n; "d" -> n * 24; else -> n * 168 } }
            if (t == "ontem" || t == "yesterday") return 30.0
        }
        return null
    }

    /** Qual envio é esta legenda da tela. null = não dá pra ter certeza (melhor não gravar do que gravar no post errado). */
    private fun chooseSend(cands: List<JSONObject>, screen: String, ageH: Double?): JSONObject? {
        val sc = core(screen)
        val scored = cands.map { it to common(sc, core(it.optString("cap"))) }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second >= best - 2 }.map { it.first }
        val now = System.currentTimeMillis()
        if (ageH != null) {
            val tol = if (ageH < 24) maxOf(1.5, ageH * 0.6) else maxOf(30.0, ageH * 0.35)
            return top.minByOrNull { kotlin.math.abs((now - it.optLong("at")) / 3600_000.0 - ageH) }?.takeIf { kotlin.math.abs((now - it.optLong("at")) / 3600_000.0 - ageH) <= tol }
        }
        if (top.size == 1) return top[0]
        // mesma legenda postada mais de uma vez e sem idade na tela: só se for o mesmo texto e o mais recente for bem mais novo
        return null
    }

    override fun onInterrupt() {}
}
