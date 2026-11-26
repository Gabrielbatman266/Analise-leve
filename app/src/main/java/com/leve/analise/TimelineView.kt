package com.leve.analise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Linha do tempo estilo CapCut.
 *  - O cursor branco fica fixo no meio; arrastar pro lado anda no vídeo. Pinça = zoom.
 *  - Trilha 🎬: clipes em sequência (toque seleciona; as alças brancas cortam início/fim do clipe). "＋" no fim adiciona.
 *  - Uma linha por camada (T texto, 🖼 imagem): bordas escolhem quando aparece/some, arrastar o meio move.
 *  - Trilha 🎵: arrastar a onda sincroniza a música; borda direita corta o fim.
 * A view mexe direto no [st] e avisa pelo [onChange].
 */
class TimelineView(c: Context) : View(c) {
    var st = EditState()
    var playhead = 0L
    /** Seleção: "clip", "layer", "music" ou "" (nada). */
    var selKind = ""; var selIdx = -1
    var thumbs: (Clip) -> List<Bitmap>? = { null }
    var wave: FloatArray? = null

    /** what: "seek", "clip", "layer", "music"; final = dedo soltou. [before] = antes da 1ª mudança (pra desfazer). */
    var onChange: (what: String, final: Boolean) -> Unit = { _, _ -> }
    var onBeforeEdit: () -> Unit = {}
    var onSelect: () -> Unit = {}
    var onAddClip: () -> Unit = {}
    var onAddMusic: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val rulerH = dp(18f); private val vidH = dp(50f); private val layH = dp(28f); private val musH = dp(34f); private val gap = dp(5f)
    private val yV get() = rulerH + gap
    private fun yL(i: Int) = yV + vidH + gap + i * (layH + gap)
    private val yM get() = yL(st.layers.size)
    private fun totalH() = (yM + musH + gap).toInt()

    private var pxPerMs = 0f
    private var userZoom = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10.5f); color = 0xFF9A9EAB.toInt() }
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f); color = 0xFFECEEF3.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()

    fun relayout() { requestLayout(); invalidate() }
    fun fit() { userZoom = false; pxPerMs = 0f; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), totalH())

    private fun ensureZoom() {
        val tot = st.total()
        val fitZ = if (tot > 0) width * 0.45f / tot else 0.05f
        if (!userZoom || pxPerMs <= 0f) pxPerMs = fitZ
        pxPerMs = pxPerMs.coerceIn(minOf(fitZ, width * 0.3f / maxOf(1L, tot)), dp(60f) / 100f) // até ~60dp por 100 ms
    }

    private fun x(ms: Long) = width / 2f + (ms - playhead) * pxPerMs
    private fun msAt(px: Float) = (playhead + (px - width / 2f) / pxPerMs).toLong()

    override fun onDraw(cv: Canvas) {
        val tot = st.total()
        if (st.clips.isEmpty() || tot <= 0) {
            tp.textAlign = Paint.Align.CENTER
            cv.drawText("Abra um vídeo pra ver a linha do tempo", width / 2f, height / 2f, tp); tp.textAlign = Paint.Align.LEFT
            return
        }
        ensureZoom()
        // régua
        val stepMs = listOf(500L, 1000L, 2000L, 5000L, 10_000L, 30_000L, 60_000L).firstOrNull { it * pxPerMs >= dp(48f) } ?: 60_000L
        p.color = 0xFF3A3A46.toInt(); p.strokeWidth = dp(1f)
        var t = maxOf(0L, (msAt(0f) / stepMs) * stepMs)
        while (t <= tot && x(t) < width) {
            val xx = x(t); cv.drawLine(xx, rulerH - dp(5f), xx, rulerH, p)
            cv.drawText(if (stepMs < 1000) "%.1fs".format(t / 1000f) else "${t / 1000}s", xx + dp(2f), rulerH - dp(6f), tp); t += stepMs
        }

        // 🎬 clipes
        var start = 0L
        st.clips.forEachIndexed { i, c ->
            val l = x(start); val rr = x(start + c.len)
            if (rr > 0 && l < width) drawClip(cv, c, l, rr, selKind == "clip" && selIdx == i, i)
            start += c.len
        }
        // ＋ adicionar clipe
        val ax = x(tot) + dp(8f)
        r.set(ax, yV + dp(8f), ax + vidH - dp(16f), yV + vidH - dp(8f))
        p.color = 0xFFFFFFFF.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
        lp.color = 0xFF000000.toInt(); lp.textAlign = Paint.Align.CENTER; lp.textSize = dp(20f)
        cv.drawText("＋", r.centerX(), r.centerY() + dp(7f), lp)
        lp.color = 0xFFECEEF3.toInt(); lp.textAlign = Paint.Align.LEFT; lp.textSize = dp(11.5f)

        // camadas
        st.layers.forEachIndexed { i, l ->
            val y = yL(i)
            val sel = selKind == "layer" && selIdx == i
            val color = if (l.isText) 0xFFFF5C82.toInt() else 0xFFB18CFF.toInt()
            val fill = if (l.isText) 0xFF4A1C2B.toInt() else 0xFF2E2346.toInt()
            val xl = x(l.s); val xr = x(minOf(l.e, tot))
            r.set(xl, y, xr, y + layH); p.color = fill; cv.drawRoundRect(r, dp(6f), dp(6f), p)
            frame(cv, xl, y, xr, y + layH, color, sel, sel, sel)
            cv.save(); cv.clipRect(xl + dp(10f), y, xr - dp(10f), y + layH)
            val label = if (l.isText) "T  " + l.text.replace('\n', ' ').ifBlank { "(texto vazio)" } else "🖼  Imagem"
            cv.drawText(label, maxOf(xl, 0f) + dp(12f), y + layH / 2 + dp(4f), lp)
            cv.restore()
        }

        // 🎵 música
        val y = yM
        val ml = x(0); val mr = x(tot)
        r.set(ml, y, mr, y + musH); p.color = 0xFF15151B.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
        if (st.music.isEmpty()) {
            cv.drawText("🎵  ＋ Adicionar música", maxOf(ml, 0f) + dp(8f), y + musH / 2 + dp(4f), tp)
        } else {
            val endOut = musEndOut()
            val rr = x(endOut)
            r.set(ml, y, rr, y + musH)
            p.color = 0xFF173A2B.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
            val w = wave
            p.color = 0xFF4CC68E.toInt(); p.strokeWidth = dp(1.5f)
            val mid = y + musH / 2
            var xx = maxOf(ml, 0f) + dp(2f)
            while (xx < minOf(rr, width.toFloat()) - dp(2f)) {
                val songMs = st.musicStart + msAt(xx)
                val a = if (w != null && w.isNotEmpty()) w[((songMs / 50).toInt()).coerceIn(0, w.size - 1)] else 0.25f
                val h = maxOf(dp(1.5f), a * (musH / 2 - dp(4f)))
                cv.drawLine(xx, mid - h, xx, mid + h, p); xx += dp(3f)
            }
            val sel = selKind == "music"
            frame(cv, ml, y, rr, y + musH, 0xFF4CC68E.toInt(), sel, false, sel)
            cv.drawText("🎵 " + VideoExport.nice(java.io.File(st.music)).take(22) + "  ·  da música: ${fmt(st.musicStart)}", maxOf(ml, 0f) + dp(8f), y + dp(13f), lp)
        }

        // cursor fixo no meio
        val px = width / 2f
        p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = dp(2f)
        cv.drawLine(px, dp(2f), px, height.toFloat(), p)
        cv.drawCircle(px, dp(4f), dp(4f), p)
        lp.textAlign = Paint.Align.CENTER
        r.set(px - dp(34f), 0f, px + dp(34f), rulerH - dp(1f)); p.color = 0xE0000000.toInt(); cv.drawRoundRect(r, dp(5f), dp(5f), p)
        cv.drawText("${fmt(playhead)} / ${fmt(tot)}", px, rulerH - dp(5f), lp); lp.textAlign = Paint.Align.LEFT
    }

    private fun musEndOut(): Long {
        val tot = st.total()
        return minOf(tot, if (st.musicEnd > 0) st.musicEnd else tot, if (st.musicDur > 0) maxOf(0L, st.musicDur - st.musicStart) else tot)
    }

    private fun drawClip(cv: Canvas, c: Clip, l: Float, rr: Float, sel: Boolean, i: Int) {
        r.set(l + dp(1f), yV, rr - dp(1f), yV + vidH)
        p.color = 0xFF1C1C24.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
        val th = thumbs(c)
        if (!th.isNullOrEmpty()) {
            cv.save(); cv.clipRect(r)
            val tw = vidH * 9f / 16f
            var xx = maxOf(l, -tw)
            while (xx < minOf(rr, width.toFloat())) {
                val frac = if (c.photo || c.srcDur <= 0) 0f else (c.s + (xx - l + tw / 2) / pxPerMs) / c.srcDur
                val b = th[(frac * th.size).toInt().coerceIn(0, th.size - 1)]
                val dst = RectF(xx, yV, xx + tw + 1, yV + vidH)
                val s = minOf(b.width / dst.width(), b.height / dst.height())
                val sw = (dst.width() * s).toInt(); val sh = (dst.height() * s).toInt()
                cv.drawBitmap(b, Rect((b.width - sw) / 2, (b.height - sh) / 2, (b.width + sw) / 2, (b.height + sh) / 2), dst, null)
                xx += tw
            }
            cv.restore()
        }
        frame(cv, l + dp(1f), yV, rr - dp(1f), yV + vidH, if (sel) 0xFFFFFFFF.toInt() else 0x66FFFFFF, sel, sel, sel)
        val tag = (if (c.photo) "📷 " else "") + fmt(c.len) + (if (!c.sound) " 🔇" else "")
        if (rr - l > dp(46f)) {
            p.color = 0x99000000.toInt(); r.set(l + dp(12f), yV + vidH - dp(17f), l + dp(16f) + lp.measureText(tag), yV + vidH - dp(3f)); cv.drawRoundRect(r, dp(4f), dp(4f), p)
            cv.drawText(tag, l + dp(14f), yV + vidH - dp(6f), lp)
        }
    }

    private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60) + if (ms < 10_000) ",${(ms % 1000) / 100}" else ""

    /** Borda do bloco com alças nas pontas (só quando selecionado). */
    private fun frame(cv: Canvas, l: Float, t: Float, rr: Float, b: Float, color: Int, sel: Boolean, left: Boolean, right: Boolean) {
        p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = dp(if (sel) 2.5f else 1.2f)
        r.set(l, t, rr, b); cv.drawRoundRect(r, dp(6f), dp(6f), p); p.style = Paint.Style.FILL
        val hw = dp(11f)
        if (left) { r.set(l - hw / 2, t, l + hw / 2, b); cv.drawRoundRect(r, dp(4f), dp(4f), p); grip(cv, l, t, b) }
        if (right) { r.set(rr - hw / 2, t, rr + hw / 2, b); cv.drawRoundRect(r, dp(4f), dp(4f), p); grip(cv, rr, t, b) }
    }
    private fun grip(cv: Canvas, cx: Float, t: Float, b: Float) {
        val old = p.color; p.color = 0xFF000000.toInt(); p.strokeWidth = dp(1.5f)
        cv.drawLine(cx, t + (b - t) * 0.32f, cx, b - (b - t) * 0.32f, p); p.color = old
    }

    // ------------------------------------------------------------------ toque

    private var mode = ""; private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var v0 = 0L; private var v1 = 0L; private var moved = false; private var edited = false
    private val grab = dp(22f)
    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            userZoom = true; pxPerMs *= det.scaleFactor; mode = "zoom"; invalidate(); return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (st.clips.isEmpty()) return false
        scaler.onTouchEvent(e)
        if (e.pointerCount > 1 || mode == "zoom") {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { mode = ""; parent?.requestDisallowInterceptTouchEvent(false) }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; moved = false; edited = false
                mode = pickHandle(e.x, e.y)
                if (mode != "scroll") performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(e.x - downX) < dp(6f)) return true
                if (!moved && mode == "scroll" && abs(e.y - downY) > abs(e.x - downX) * 1.5f) { parent?.requestDisallowInterceptTouchEvent(false); mode = ""; return false }
                moved = true; drag(e.x); lastX = e.x; invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) tap(e.x, e.y)
                else if (moved) onChange(when { mode == "scroll" -> "seek"; mode.startsWith("clip") -> "clip"; mode.startsWith("lay") -> "layer"; mode.startsWith("mus") -> "music"; else -> "seek" }, true)
                mode = ""; invalidate(); return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun near(a: Float, b: Float) = abs(a - b) < grab

    /** Só pega alça do que já está selecionado; o resto arrasta a linha do tempo. */
    private fun pickHandle(px: Float, py: Float): String {
        when (selKind) {
            "clip" -> if (py in yV..(yV + vidH) && selIdx in st.clips.indices) {
                val l = x(st.startOf(selIdx)); val rr = x(st.startOf(selIdx) + st.clips[selIdx].len)
                val c = st.clips[selIdx]; v0 = c.s; v1 = c.e
                if (near(px, l)) return "clipS"; if (near(px, rr)) return "clipE"
            }
            "layer" -> if (selIdx in st.layers.indices && py in yL(selIdx)..(yL(selIdx) + layH)) {
                val l = st.layers[selIdx]; v0 = l.s; v1 = l.e
                val xl = x(l.s); val xr = x(l.e)
                if (near(px, xl) && near(px, xr)) return if (px < (xl + xr) / 2) "layS" else "layE"
                if (near(px, xl)) return "layS"; if (near(px, xr)) return "layE"
                if (px in xl..xr) return "layMove"
            }
            "music" -> if (st.music.isNotEmpty() && py in yM..(yM + musH)) {
                v0 = st.musicStart; v1 = st.musicEnd
                if (near(px, x(musEndOut()))) return "musE"
                if (px in x(0)..x(musEndOut())) return "musSlip"
            }
        }
        return "scroll"
    }

    private fun tap(px: Float, py: Float) {
        val tot = st.total()
        val ms = msAt(px)
        val old = selKind to selIdx
        when {
            py < yV -> { playhead = ms.coerceIn(0, tot); onChange("seek", true); return }
            py < yV + vidH + gap / 2 -> {
                val ax = x(tot) + dp(8f)
                if (px >= ax - dp(4f) && px <= ax + vidH) { onAddClip(); return }
                if (ms in 0 until tot) { selKind = "clip"; selIdx = st.locate(ms).first } else { selKind = ""; selIdx = -1 }
            }
            py < yM - gap / 2 -> {
                val i = ((py - yL(0)) / (layH + gap)).toInt()
                val l = st.layers.getOrNull(i)
                if (l != null && ms >= l.s && ms < l.e) { selKind = "layer"; selIdx = i } else { selKind = ""; selIdx = -1 }
            }
            else -> { if (st.music.isEmpty()) { onAddMusic(); return }; selKind = "music"; selIdx = 0 }
        }
        if (old != (selKind to selIdx)) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onSelect() }
        invalidate()
    }

    private fun before() { if (!edited) { edited = true; onBeforeEdit() } }

    private fun drag(px: Float) {
        val dms = ((px - downX) / pxPerMs).toLong()
        val tot = st.total()
        when (mode) {
            "scroll" -> { playhead = (playhead - ((px - lastX) / pxPerMs).toLong()).coerceIn(0, tot); onChange("seek", false); return }
            "clipS" -> { before(); val c = st.clips[selIdx]; c.s = (v0 + dms).coerceIn(0, c.e - 300); playhead = st.startOf(selIdx) }
            "clipE" -> {
                before(); val c = st.clips[selIdx]
                c.e = (v1 + dms).coerceIn(c.s + 300, if (c.photo) 60_000L else c.srcDur)
                playhead = st.startOf(selIdx) + c.len - 1
            }
            "layS" -> { before(); val l = st.layers[selIdx]; l.s = (v0 + dms).coerceIn(0, l.e - 200); playhead = l.s }
            "layE" -> { before(); val l = st.layers[selIdx]; l.e = (v1 + dms).coerceIn(l.s + 200, tot); playhead = l.e - 1 }
            "layMove" -> {
                before(); val l = st.layers[selIdx]; val len = v1 - v0
                l.s = (v0 + dms).coerceIn(0, maxOf(0L, tot - len)); l.e = l.s + len; playhead = l.s
            }
            "musSlip" -> { before(); st.musicStart = (v0 - dms).coerceIn(0, maxOf(0L, st.musicDur - 1000)) }
            "musE" -> { before(); val v = msAt(px).coerceIn(500, tot); st.musicEnd = if (v >= tot - 150) 0 else v }
            else -> return
        }
        if (mode.startsWith("clip")) st.clamp()
        onChange(when { mode.startsWith("clip") -> "clip"; mode.startsWith("lay") -> "layer"; else -> "music" }, false)
    }
}
