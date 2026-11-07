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
        val n = QuickPost.countReady(c); val nv = QuickPost.countVideos(c)
        val g = Daily.goal(c)
        return "📸 $n fotos · 🎬 $nv vídeos" + if (g > 0) " · hoje ${Daily.postedToday(c)}/$g" else ""
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
            if (s != null) s.refresh()
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
    private var root: LinearLayout? = null
    private lateinit var bubble: TextView
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
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(0xF0141418.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xFF2A2A33.toInt()) }
        }
        fun item(t: String, what: String) = TextView(this).apply {
            text = t; textSize = 15f; setTextColor(0xFFF2F2F5.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { closeMenu(); PostBar.act(this@FloatService, what) }
        }.also { menu.addView(it) }
        item("📸  Postar no Threads", "foto")
        item("🎬  Postar no Instagram", "video")
        item("🤖  Produzir mais vídeos", "produzir")
        item("🏠  Abrir o Post Leve", "abrir").setOnClickListener {
            closeMenu(); startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item("✕  Esconder o botão", "x").setOnClickListener { closeMenu(); PostBar.setBubble(this, false) }
        box.addView(menu, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })

        val frame = FrameLayout(this)
        bubble = TextView(this).apply {
            text = "📮"; textSize = 24f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF4F7A.toInt()); setStroke(dp(2), 0x55FFFFFF) }
            elevation = dp(6).toFloat()
        }
        frame.addView(bubble, FrameLayout.LayoutParams(dp(56), dp(56)))
        badge = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF1A0A10.toInt()); gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(10).toFloat() }
            elevation = dp(7).toFloat()
        }
        frame.addView(badge, FrameLayout.LayoutParams(-2, dp(20), Gravity.TOP or Gravity.END))
        box.addView(frame, LinearLayout.LayoutParams(dp(60), dp(60)))
        root = box

        lp = WindowManager.LayoutParams(-2, -2,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.getInt("bubble_x", resources.displayMetrics.widthPixels - dp(66)); y = p.getInt("bubble_y", resources.displayMetrics.heightPixels / 3)
        }
        dragging(frame)
        try { wm.addView(box, lp) } catch (e: Exception) { stopSelf(); return }
        refresh()
    }

    private fun dragging(v: View) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false; var down = 0L
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; down = System.currentTimeMillis() }
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
                }
            }
            true
        }
    }

    /** Gruda na borda mais perto. */
    private fun snap() {
        val w = resources.displayMetrics.widthPixels
        val end = if (lp.x + dp(30) < w / 2) 0 else w - dp(62)
        ValueAnimator.ofInt(lp.x, end).apply {
            duration = 180
            addUpdateListener { lp.x = it.animatedValue as Int; try { wm.updateViewLayout(root, lp) } catch (x: Exception) { } }
            start()
        }
        Store.prefs(this).edit().putInt("bubble_x", end).putInt("bubble_y", lp.y).apply()
    }

    private fun toggleMenu() { if (menu.visibility == View.VISIBLE) closeMenu() else { menu.visibility = View.VISIBLE; relayout() } }
    private fun closeMenu() { menu.visibility = View.GONE; relayout() }

    /** Menu abre pra dentro da tela, mesmo com o botão na borda direita/baixo. */
    private fun relayout() {
        val open = menu.visibility == View.VISIBLE
        val p = Store.prefs(this)
        if (open) {
            root?.measure(0, 0)
            val mw = root?.measuredWidth ?: dp(240); val mh = root?.measuredHeight ?: dp(300)
            val w = resources.displayMetrics.widthPixels
            lp.x = if (p.getInt("bubble_x", 0) > w / 2) w - mw - dp(4) else p.getInt("bubble_x", 0)
            lp.y = maxOf(0, p.getInt("bubble_y", lp.y) - (mh - dp(60)))
        } else { lp.x = p.getInt("bubble_x", lp.x); lp.y = p.getInt("bubble_y", lp.y) }
        try { wm.updateViewLayout(root, lp) } catch (e: Exception) { }
    }

    fun refresh() {
        try { getSystemService(NotificationManager::class.java).notify(PostBar.NID, PostBar.build(this)) } catch (e: Exception) { }
        if (root == null) return
        val g = Daily.goal(this)
        val left = if (g > 0) Daily.remaining(this) else QuickPost.countVideos(this)
        badge.text = left.toString(); badge.visibility = if (left > 0) View.VISIBLE else View.GONE
        if (PostBar.alerting(this)) {
            bubble.text = "⏰"
            if (pulse == null) pulse = ObjectAnimator.ofFloat(bubble, View.SCALE_X, 1f, 1.18f).apply {
                duration = 520; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { bubble.scaleY = bubble.scaleX }
                start()
            }
        } else {
            bubble.text = "📮"; pulse?.cancel(); pulse = null; bubble.scaleX = 1f; bubble.scaleY = 1f
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
