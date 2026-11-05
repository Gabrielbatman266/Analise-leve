package com.leve.analise

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Serviço em primeiro plano que:
 *  1) mantém a bolha de print por cima dos outros apps (captura de tela);
 *  2) analisa a fila de prints em segundo plano, mesmo com a tela apagada.
 */
class LeveService : Service() {

    companion object {
        const val ACT_CAPTURE = "com.leve.analise.CAPTURE"
        const val ACT_ANALYZE = "com.leve.analise.ANALYZE"
        const val ACT_SHOT = "com.leve.analise.SHOT"
        const val ACT_STOP_CAPTURE = "com.leve.analise.STOP_CAPTURE"
        const val ACT_STOP = "com.leve.analise.STOP"
        private const val CH = "leve"
        private const val NID = 7

        @Volatile var capturing = false
        @Volatile var working = false
        @Volatile var autoMode = false
        @Volatile var lastError = ""
        @Volatile var lastBatchInfo = ""
        @Volatile var stopFlag = false

        fun send(c: Context, action: String, extra: Intent? = null) {
            val i = Intent(c, LeveService::class.java).setAction(action)
            if (extra != null) i.putExtras(extra)
            if (action == ACT_SHOT || action == ACT_STOP || action == ACT_STOP_CAPTURE) c.startService(i)
            else c.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var capThread: HandlerThread
    private lateinit var cap: Handler
    private lateinit var workThread: HandlerThread
    private lateinit var work: Handler
    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var held: Image? = null
    @Volatile private var pendingShot = false
    @Volatile private var pendingAuto = false

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        wm = getSystemService(WindowManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Análise em segundo plano", NotificationManager.IMPORTANCE_LOW))
        capThread = HandlerThread("leve-print").also { it.start() }
        cap = Handler(capThread.looper)
        workThread = HandlerThread("leve-analise").also { it.start() }
        work = Handler(workThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_CAPTURE -> {
                if (!goForeground(true)) return START_NOT_STICKY
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra("data")
                val code = intent.getIntExtra("code", 0)
                if (data != null) startCapture(code, data)
                kick()
            }
            ACT_SHOT -> requestShot(false)
            ACT_STOP_CAPTURE -> { stopCapture(); if (!working) stopAll() }
            ACT_STOP -> { stopFlag = true; stopAll() }
            else -> { // ACT_ANALYZE ou reinício do sistema
                if (!goForeground(capturing)) return START_NOT_STICKY
                kick()
            }
        }
        return START_STICKY
    }

    private fun goForeground(withCapture: Boolean): Boolean = try {
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (withCapture)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NID, n, type)
        } else startForeground(NID, n)
        true
    } catch (e: Exception) {
        lastError = "O Android não deixou iniciar em segundo plano. Abra o app e tente de novo."
        stopSelf(); false
    }

    // ------------------------------------------------------------------ captura

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null } ?: return
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopCapture(); if (!working) stopAll() else updateNotif() } }
        }, main)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels; val h = dm.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ onFrame(it) }, cap)
        reader = r
        vd = p.createVirtualDisplay("leve-print", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        capturing = true
        showBubble()
        updateNotif()
    }

    /** Roda na thread de captura: guarda o quadro mais novo só quando um print foi pedido. */
    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        if (pendingShot) { held?.close(); held = img } else img.close()
    }

    private fun requestShot(auto: Boolean) {
        if (!capturing || pendingShot) return
        pendingAuto = auto
        cap.post {
            held?.close(); held = null
            pendingShot = true
            main.post { bubble?.visibility = View.INVISIBLE } // esconde a bolha pra ela não sair no print
            cap.postDelayed({ takeHeld(0) }, 220)
        }
    }

    private fun takeHeld(tries: Int) {
        val img = held
        if (img == null) {
            if (tries < 6) { cap.postDelayed({ takeHeld(tries + 1) }, 100); return }
            pendingShot = false
            main.post { bubble?.visibility = View.VISIBLE }
            return
        }
        held = null; pendingShot = false
        val bmp = try { toBitmap(img) } catch (e: Exception) { null } finally { img.close() }
        main.post { bubble?.visibility = View.VISIBLE }
        if (bmp == null) return
        val f = Store.saveShot(this, bmp, pendingAuto)
        main.post { flash(if (f != null) "✓" else "=") ; updateNotif() }
        if (f != null) kick()
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val ps = p.pixelStride; val rs = p.rowStride
        val bw = rs / ps
        val b = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        b.copyPixelsFromBuffer(p.buffer)
        return if (bw == img.width) b else Bitmap.createBitmap(b, 0, 0, img.width, img.height)
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!capturing || !autoMode) return
            requestShot(true)
            main.postDelayed(this, 1600)
        }
    }

    private fun setAuto(on: Boolean) {
        autoMode = on
        styleBubble()
        main.removeCallbacks(autoTick)
        if (on) main.postDelayed(autoTick, 800)
        Toast.makeText(this,
            if (on) "Modo automático: role o perfil devagar, eu tiro os prints sozinho" else "Modo automático desligado",
            Toast.LENGTH_SHORT).show()
        updateNotif()
    }

    private fun stopCapture() {
        main.removeCallbacks(autoTick)
        autoMode = false
        capturing = false
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        bubble = null
        try { vd?.release() } catch (e: Exception) {}
        vd = null
        cap.post { held?.close(); held = null }
        try { reader?.close() } catch (e: Exception) {}
        reader = null
        try { projection?.stop() } catch (e: Exception) {}
        projection = null
        if (working) goForeground(false)
        updateNotif()
    }

    // ------------------------------------------------------------------ bolha

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styleBubble() {
        val b = bubble ?: return
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (autoMode) 0xE61F8A5B.toInt() else 0xE6FF5C82.toInt())
            setStroke(dp(2), 0xFFFFFFFF.toInt())
        }
        b.text = if (autoMode) "▶" else "◉"
    }

    private fun flash(t: String) {
        val b = bubble ?: return
        b.text = t
        main.postDelayed({ styleBubble() }, 450)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val size = dp(54)
        val tv = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Tirar print"
        }
        bubble = tv
        styleBubble()
        val lp = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(6)
            y = resources.displayMetrics.heightPixels / 3
        }
        bubbleLp = lp

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var longDone = false
        val longPress = Runnable { longDone = true; setAuto(!autoMode) }
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longDone = false
                    main.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) { moved = true; main.removeCallbacks(longPress) }
                    if (moved) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(tv, lp) } catch (x: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (!moved && !longDone && e.action == MotionEvent.ACTION_UP) requestShot(false)
                }
            }
            true
        }
        try { wm.addView(tv, lp) } catch (e: Exception) {
            bubble = null
            lastError = "Sem permissão de sobrepor outros apps. Ative em Configurações."
        }
    }

    // ------------------------------------------------------------------ análise em segundo plano

    private fun batchSize() = Store.prefs(this).getInt("batch", 4).coerceIn(1, 8)

    private fun kick() {
        if (working) return
        stopFlag = false
        lastError = ""
        working = true
        work.post { processLoop() }
    }

    private fun processLoop() {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leve:analise")
        wl.acquire(60 * 60 * 1000L)
        var fails = 0
        var busy = 0
        var addedRun = 0
        try {
            while (!stopFlag) {
                val batch = Store.pending(this).take(batchSize())
                if (batch.isEmpty()) break
                try {
                    val posts = Ai.extract(this, batch)
                    val added = Store.addPosts(this, posts)
                    addedRun += added
                    batch.forEach { it.delete() }
                    fails = 0
                    lastError = ""
                    lastBatchInfo = "Último lote: ${posts.length()} posts lidos, $added novos"
                    val p = Store.prefs(this)
                    p.edit().putInt("prints_lidos", p.getInt("prints_lidos", 0) + batch.size).apply()
                } catch (e: AiException) {
                    lastError = "IA: ${e.message}"
                    if (e.code in listOf(400, 401, 403, 404)) break // chave ou modelo errado: não adianta insistir
                    if (e.code == 503 || e.code == 429 || e.code == 500 || e.code == 529) {
                        // IA lotada: não conta como falha, espera e tenta de novo sem desistir
                        busy++
                        val n = Ai.models(this).size
                        if (Ai.nextModel(this) && busy % n != 0) { lastError = "Modelo lotado, trocando pra ${Ai.model(this)}…"; SystemClock.sleep(2000); continue }
                        val wait = minOf(180L, 15L * ((busy + n - 1) / n))
                        lastError = "IA lotada no momento. Tentando de novo em ${wait}s (tentativa $busy)…"
                        main.post { updateNotif() }
                        var t = 0L
                        while (t < wait && !stopFlag) { SystemClock.sleep(1000); t++ }
                        continue
                    }
                    fails++
                    if (e.code == -2 && fails >= 2) break // resposta sem posts: não apaga os prints
                    SystemClock.sleep(8000L * fails)
                } catch (e: Exception) {
                    fails++
                    lastError = "Sem conexão. Tentando de novo…"
                    SystemClock.sleep(minOf(60000L, 6000L * fails))
                }
                busy = 0
                if (fails >= 8) { lastError = "Parei depois de várias falhas. Toque em Analisar fila pra tentar de novo."; break }
                main.post { updateNotif() }
            }
            // legendas das fotos da fila de posts
            for (id in PostQueue.pendingCaptions(this)) {
                if (stopFlag) break
                var tries = 0
                var done = false
                while (!done && tries < 6 && !stopFlag) {
                    try {
                        val caps = Ai.captions(this, PostQueue.imageFile(this, id))
                        PostQueue.update(this, id) { it.put("legendas", org.json.JSONArray(caps)); it.put("status", "pronta"); it.remove("aviso") }
                        done = true
                    } catch (e: AiException) {
                        if (e.code in listOf(400, 401, 403, 404)) break
                        tries++
                        lastError = "Legendas: ${e.message}"
                        main.post { updateNotif() }
                        var t = 0L; val wait = minOf(120L, 15L * tries)
                        while (t < wait && !stopFlag) { SystemClock.sleep(1000); t++ }
                    } catch (e: Exception) { tries++; SystemClock.sleep(8000) }
                }
                if (!done && !stopFlag) PostQueue.update(this, id) {
                    it.put("legendas", org.json.JSONArray(Ai.withCta(Ai.OFFLINE.shuffled().take(4))))
                    it.put("status", "pronta"); it.put("aviso", "IA indisponível: usei legendas prontas. Toque em Mais legendas depois.")
                }
            }
            // fila vazia: atualiza os padrões quando entrou coisa nova
            if (addedRun > 0 && lastError.isEmpty()) {
                val all = Store.sorted(this)
                if (all.size >= 6) try {
                    val low = all.takeLast(minOf(8, all.size / 3))
                    Store.savePatterns(this, Ai.patterns(this, all.take(10), low))
                } catch (e: Exception) { }
            }
        } finally {
            if (wl.isHeld) wl.release()
            working = false
            main.post {
                updateNotif()
                if (!capturing) stopAll()
            }
        }
    }

    // ------------------------------------------------------------------ notificação

    private fun pi(action: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, LeveService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotif(): Notification {
        val fila = Store.pending(this).size
        val posts = Store.loadPosts(this).length()
        val title = when {
            working && fila > 0 -> "Analisando: $fila print(s) na fila"
            capturing -> if (autoMode) "Print automático ligado" else "Bolha de print ligada"
            else -> "Análise Leve"
        }
        val text = buildString {
            append("$posts posts no banco")
            if (lastError.isNotEmpty()) append(" · ").append(lastError)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (capturing) {
            b.addAction(Notification.Action.Builder(null, "Print", pi(ACT_SHOT, 1)).build())
            b.addAction(Notification.Action.Builder(null, "Desligar bolha", pi(ACT_STOP_CAPTURE, 2)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Parar tudo", pi(ACT_STOP, 3)).build())
        return b.build()
    }

    private fun updateNotif() {
        if (!capturing && !working) return
        try { nm.notify(NID, buildNotif()) } catch (e: Exception) {}
    }

    private fun stopAll() {
        if (capturing) stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (capturing) stopCapture()
        capThread.quitSafely()
        workThread.quitSafely()
        super.onDestroy()
    }
}
