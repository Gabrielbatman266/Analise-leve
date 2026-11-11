package com.leve.analise

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Atalhos que ficam sempre à mão:
 *  - barra fixa na notificação: 📸 Threads · 🎬 Instagram · 🤖 Produzir
 *  - botão flutuante por cima dos apps (pisca quando é hora de postar)
 */
object PostBar {
    const val NID = 77
    private const val CH = "barra_postar"

    fun barOn(c: Context) = Store.prefs(c).getBoolean("bar_on", false)
    fun bubbleOn(c: Context) = Store.prefs(c).getBoolean("bubble_on", false)
    fun alerting(c: Context) = Store.prefs(c).getBoolean("bubble_alert", false)
    fun canBubble(c: Context) = Settings.canDrawOverlays(c)

    fun setBar(c: Context, on: Boolean) { Store.prefs(c).edit().putBoolean("bar_on", on).apply(); update(c) }
    fun setBubble(c: Context, on: Boolean) { Store.prefs(c).edit().putBoolean("bubble_on", on).apply(); update(c) }

    /** Hora de postar: o botão flutuante pisca e a barra avisa. */
    fun alert(c: Context, on: Boolean) { Store.prefs(c).edit().putBoolean("bubble_alert", on).apply(); update(c) }

    private fun pi(c: Context, req: Int, i: Intent) = PendingIntent.getActivity(c, req, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun bc(c: Context, req: Int, action: String) = PendingIntent.getBroadcast(c, req,
        Intent(c, PostBarReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun status(c: Context): String {
        // total que pode sair (as que já saíram voltam no giro) e quantas ainda são novas
        val fotos = PostQueue.load(c).filter { it.optString("status") != "arquivada" }
        val vids = VideoExport.library(c).filter { !it.optBoolean("arquivado") }
        val nNew = QuickPost.countReady(c); val vNew = vids.count { it.optInt("posts") == 0 }
        val g = Daily.goal(c)
        return "📸 ${fotos.size} fotos" + (if (nNew > 0) " ($nNew novas)" else "") + " · 🎬 ${vids.size} vídeos" + (if (vNew > 0) " ($vNew novos)" else "") +
            if (g > 0) " · hoje ${Daily.postedToday(c)}/$g" else ""
    }

    fun build(c: Context): Notification {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Barra de postar (fixa)", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val next = Reminder.nextAt(c)
        val sub = when {
            alerting(c) -> "⏰ Hora de postar!"
            next > System.currentTimeMillis() -> "Próximo lembrete do Threads: " + android.text.format.DateFormat.format("HH:mm", next)
            else -> "Toque num botão pra postar"
        }
        return Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(status(c)).setContentText(sub)
            .setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .setContentIntent(pi(c, 70, Intent(c, MainActivity::class.java)))
            .setDeleteIntent(bc(c, 74, "redraw"))
            .addAction(Notification.Action.Builder(null, "📸 Threads", bc(c, 71, "foto")).build())
            .addAction(Notification.Action.Builder(null, "🎬 Instagram", bc(c, 72, "video")).build())
            .addAction(Notification.Action.Builder(null, "🤖 Produzir", bc(c, 73, "produzir")).build())
            .build()
    }

    /** Liga/atualiza/desliga tudo conforme as escolhas. Pode chamar de qualquer lugar. */
    fun update(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (bubbleOn(c) && canBubble(c)) {
            val s = FloatService.instance
            if (s != null) android.os.Handler(android.os.Looper.getMainLooper()).post { try { s.refresh() } catch (e: Exception) { } } // a bolha só pode ser mexida na thread da tela
            else try {
                val i = Intent(c, FloatService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) { if (barOn(c)) try { nm.notify(NID, build(c)) } catch (x: Exception) { } }
            return
        }
        FloatService.instance?.stopSelf()
        if (barOn(c)) try { nm.notify(NID, build(c)) } catch (e: SecurityException) { }
        else nm.cancel(NID)
    }

    /** Executa um atalho (barra ou botão). */
    fun act(c: Context, what: String) {
        when (what) {
            "foto" -> { alert(c, false); try { c.startActivity(QuickPost.intent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }
            "video" -> { alert(c, false); try { c.startActivity(QuickPost.videoIntent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }
            "produzir" -> try {
                val i = Intent(c, ProduceService::class.java).putExtra("n", Store.prefs(c).getInt("auto_n", 5))
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) { }
            "redraw" -> update(c)
        }
    }
}

class PostBarReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // fecha a gaveta de notificações antes de abrir o app de postar
        if (i.action == "foto" || i.action == "video") try {
            @Suppress("DEPRECATION") c.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (e: Exception) { }
        PostBar.act(c, i.action ?: return)
    }
}

/** Botão flutuante: toque abre o menu (📸 / 🎬), arraste pra mudar de lugar, segure pra esconder. */
class FloatService : Service() {
    companion object { @Volatile var instance: FloatService? = null }

    private lateinit var wm: WindowManager
    private var viaAcc = false

    /** O leitor de tela ligou/desligou: refaz a bolha pela janela certa. */
    fun rebuild(acc: Boolean) {
        if (acc == viaAcc && root != null) return
        try { root?.let { wm.removeView(it) } } catch (e: Exception) { }
        root = null; addBubble()
    }
    private var root: LinearLayout? = null
    private lateinit var bubble: BubbleView
    private lateinit var badge: TextView
    private lateinit var menu: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private var pulse: ObjectAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(PostBar.NID, PostBar.build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(PostBar.NID, PostBar.build(this))
        } catch (e: Exception) { instance = null; stopSelf(); return }
        if (!PostBar.canBubble(this)) { stopSelf(); return }
        wm = getSystemService(WindowManager::class.java)
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { refresh(); return START_STICKY }

    private fun addBubble() {
        val p = Store.prefs(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        // menu (escondido)
        // menu: vidro escuro, cantos bem redondos, itens limpos
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(0xE00E0E12.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x1FFFFFFF) }
            elevation = dp(10).toFloat()
        }
        fun item(icon: String, t: String, what: String, dim: Boolean = false) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(16), dp(8))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, GradientDrawable().apply { setColor(-1); cornerRadius = dp(16).toFloat() })
            addView(TextView(this@FloatService).apply {
                text = icon; textSize = 14f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x14FFFFFF) }
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(TextView(this@FloatService).apply { text = t; textSize = 14f; setTextColor(if (dim) 0x99F2F2F5.toInt() else 0xFFF2F2F5.toInt()); setPadding(dp(12), 0, 0, 0) })
            setOnClickListener { closeMenu(); PostBar.act(this@FloatService, what) }
        }.also { menu.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        item("📸", "Postar no Threads", "foto")
        item("🎬", "Postar no Instagram", "video")
        item("🤖", "Produzir vídeos", "produzir")
        item("📖", "Ler esta tela (números)", "x").setOnClickListener {
            closeMenu(); val r = ScreenReader.instance
            if (r == null) android.widget.Toast.makeText(this, "Ligue o Leitor de tela (✅ Configuração)", android.widget.Toast.LENGTH_SHORT).show() else r.forceRead()
        }
        item("🧽", "Limpar metadados", "x").setOnClickListener {
            closeMenu(); startActivity(Intent(this, MetaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item("◻", "Abrir o Post Leve", "abrir").setOnClickListener {
            closeMenu(); startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        menu.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        item("✕", "Esconder o botão", "x", dim = true).setOnClickListener { closeMenu(); PostBar.setBubble(this, false) }
        box.addView(menu, LinearLayout.LayoutParams(dp(230), -2).apply { bottomMargin = dp(8) })

        // botão: círculo de vidro transparente com um ícone fino; fica apagadinho e acende ao tocar
        val frame = FrameLayout(this)
        bubble = BubbleView(this)
        frame.addView(bubble, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        badge = TextView(this).apply {
            textSize = 9.5f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), 0); minWidth = dp(16)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { setColor(0xFFFF4F7A.toInt()); cornerRadius = dp(8).toFloat() }
        }
        frame.addView(badge, FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP or Gravity.END))
        box.addView(frame, LinearLayout.LayoutParams(dp(52), dp(52)))
        frame.alpha = IDLE
        root = box

        // Com o leitor de tela ligado, a bolha entra pela janela dele: assim o Android NÃO mostra
        // a notificação "Post Leve está sobrepondo outros apps". Sem o leitor, usa a sobreposição normal.
        val acc = ScreenReader.instance
        wm = acc?.getSystemService(WindowManager::class.java) ?: getSystemService(WindowManager::class.java)
        viaAcc = acc != null
        lp = WindowManager.LayoutParams(-2, -2,
            if (acc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.getInt("bubble_x", resources.displayMetrics.widthPixels - dp(54)); y = p.getInt("bubble_y", resources.displayMetrics.heightPixels / 3)
        }
        dragging(frame)
        try { wm.addView(box, lp) } catch (e: Exception) { stopSelf(); return }
        refresh()
    }

    private fun dragging(v: View) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false; var down = 0L
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; down = System.currentTimeMillis(); wake(v) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) { lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); try { wm.updateViewLayout(root, lp) } catch (x: Exception) { } }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - down > 650) PostBar.setBubble(this, false) // segurar = esconder
                        else toggleMenu()
                    } else snap()
                    if (menu.visibility != View.VISIBLE) sleep(v)
                }
            }
            true
        }
    }

    private val IDLE = 0.45f
    private fun wake(v: View) { v.animate().cancel(); v.animate().alpha(1f).scaleX(0.92f).scaleY(0.92f).setDuration(90).start() }
    private fun sleep(v: View) { v.animate().cancel(); v.animate().scaleX(1f).scaleY(1f).setDuration(140).withEndAction { v.animate().alpha(if (PostBar.alerting(this)) 0.9f else IDLE).setStartDelay(1800).setDuration(400).start() }.start() }

    /** Gruda na borda mais perto. */
    private fun snap() {
        val w = resources.displayMetrics.widthPixels
        val end = if (lp.x + dp(26) < w / 2) dp(2) else w - dp(54)
        ValueAnimator.ofInt(lp.x, end).apply {
            duration = 180
            addUpdateListener { lp.x = it.animatedValue as Int; try { wm.updateViewLayout(root, lp) } catch (x: Exception) { } }
            start()
        }
        Store.prefs(this).edit().putInt("bubble_x", end).putInt("bubble_y", lp.y).apply()
    }

    private fun toggleMenu() {
        if (menu.visibility == View.VISIBLE) { closeMenu(); return }
        menu.alpha = 0f; menu.scaleX = 0.94f; menu.scaleY = 0.94f; menu.visibility = View.VISIBLE; relayout()
        menu.pivotX = if (lp.x > resources.displayMetrics.widthPixels / 2) dp(230).toFloat() else 0f; menu.pivotY = menu.height.toFloat()
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }
    private fun closeMenu() { menu.visibility = View.GONE; relayout(); (bubble.parent as? View)?.let { sleep(it) } }

    /** Menu abre pra dentro da tela, mesmo com o botão na borda direita/baixo. */
    private fun relayout() {
        val open = menu.visibility == View.VISIBLE
        val p = Store.prefs(this)
        if (open) {
            root?.measure(0, 0)
            val mw = root?.measuredWidth ?: dp(240); val mh = root?.measuredHeight ?: dp(300)
            val w = resources.displayMetrics.widthPixels
            lp.x = if (p.getInt("bubble_x", 0) > w / 2) w - mw - dp(4) else p.getInt("bubble_x", 0)
            lp.y = maxOf(0, p.getInt("bubble_y", lp.y) - (mh - dp(52)))
        } else { lp.x = p.getInt("bubble_x", lp.x); lp.y = p.getInt("bubble_y", lp.y) }
        try { wm.updateViewLayout(root, lp) } catch (e: Exception) { }
    }

    fun refresh() {
        try { getSystemService(NotificationManager::class.java).notify(PostBar.NID, PostBar.build(this)) } catch (e: Exception) { }
        if (root == null) return
        val g = Daily.goal(this)
        val left = if (g > 0) Daily.remaining(this) else QuickPost.countVideos(this)
        badge.text = left.toString(); badge.visibility = if (left > 0) View.VISIBLE else View.GONE
        // hora de postar: anel colorido respirando (sem emoji, sem pular)
        if (PostBar.alerting(this)) {
            (bubble.parent as? View)?.alpha = 0.9f
            if (pulse == null) pulse = ObjectAnimator.ofFloat(bubble, "ring", 0.25f, 1f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        } else {
            pulse?.cancel(); pulse = null; bubble.ring = 0f
        }
    }

    override fun onDestroy() {
        instance = null
        pulse?.cancel()
        try { root?.let { wm.removeView(it) } } catch (e: Exception) { }
        root = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // se a barra continua ligada, ela fica sozinha
        if (PostBar.barOn(this)) try { getSystemService(NotificationManager::class.java).notify(PostBar.NID, PostBar.build(this)) } catch (e: Exception) { }
        super.onDestroy()
    }
}

/** Botão flutuante minimalista: círculo de vidro escuro, borda fina e um avião de papel em traço. */
class BubbleView(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x99121216.toInt() }
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = d; color = 0x40FFFFFF }
    private val ic = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.8f * d; color = 0xF2FFFFFF.toInt(); strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.2f * d; color = 0xFFFF4F7A.toInt() }
    private val path = android.graphics.Path()

    /** 0 = sem anel; 0..1 = anel de "hora de postar". */
    var ring = 0f
        set(v) { field = v; invalidate() }

    init { setLayerType(LAYER_TYPE_HARDWARE, null); elevation = 4 * d; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0) } }

    override fun onDraw(cv: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2; val r = minOf(w, h) / 2 - 1.5f * d
        cv.drawCircle(cx, cy, r, fill)
        cv.drawCircle(cx, cy, r, edge)
        if (ring > 0f) { glow.alpha = (ring * 255).toInt(); cv.drawCircle(cx, cy, r - d, glow) }
        // avião de papel
        val s = r * 0.48f
        path.reset()
        path.moveTo(cx - s, cy - s * 0.05f); path.lineTo(cx + s, cy - s * 0.75f); path.lineTo(cx + s * 0.35f, cy + s * 0.85f); path.close()
        path.moveTo(cx - s * 0.05f, cy + s * 0.1f); path.lineTo(cx + s, cy - s * 0.75f)
        cv.drawPath(path, ic)
    }
}
