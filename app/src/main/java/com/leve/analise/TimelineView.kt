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
import android.view.View
import kotlin.math.abs

/**
 * Linha do tempo estilo CapCut, com três camadas:
 *  🎬 Vídeo  – bordas cortam o início e o fim
 *  🎵 Música – arrastar o bloco sincroniza (escolhe o trecho da música); borda direita corta o fim
 *  T  Texto  – bordas escolhem quando aparece e some; arrastar o meio move
 * Tempos do vídeo são do arquivo original; música e texto são relativos ao início do corte.
 */
class TimelineView(c: Context) : View(c) {
    // estado (o editor lê e escreve)
    var dur = 0L; var cutS = 0L; var cutE = 0L
    var musicOn = false; var musicName = ""; var musicDur = 0L; var musicStart = 0L; var musicEnd = 0L
    var text = ""; var textS = 0L; var textE = 0L
    var playhead = 0L
    var thumbs: List<Bitmap> = emptyList()
    var wave: FloatArray? = null  // picos a cada 50 ms da música
    var selected = 0               // 0 vídeo, 1 música, 2 texto

    /** what: "cut", "music", "text", "seek"; final = dedo soltou */
    var onChange: (what: String, final: Boolean) -> Unit = { _, _ -> }
    var onSelect: (track: Int) -> Unit = {}

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val pad = dp(14f)
    private val rulerH = dp(18f); private val vidH = dp(46f); private val musH = dp(36f); private val txtH = dp(30f); private val gap = dp(6f)
    private val yV get() = rulerH + gap
    private val yM get() = yV + vidH + gap
    private val yT get() = yM + musH + gap
    private val totalH = (rulerH + vidH + musH + txtH + gap * 4).toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10.5f); color = 0xFF9A9EAB.toInt() }
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f); color = 0xFFECEEF3.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()

    private val outDur get() = maxOf(0L, cutE - cutS)
    private val musEndOut get() = if (!musicOn) 0L else minOf(outDur, if (musicEnd > 0) musicEnd else outDur, maxOf(0L, musicDur - musicStart))
    private val txtEndOut get() = if (textE > 0) minOf(textE, outDur) else outDur

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), totalH)

    private fun x(ms: Long) = pad + (if (dur > 0) ms.toFloat() / dur else 0f) * (width - 2 * pad)
    private fun ms(x: Float) = (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * dur).toLong()
    private fun msPerPx() = if (width > 2 * pad) dur / (width - 2 * pad) else 0f

    override fun onDraw(cv: Canvas) {
        if (dur <= 0) {
            tp.textAlign = Paint.Align.CENTER
            cv.drawText("Abra um vídeo pra ver a linha do tempo", width / 2f, height / 2f, tp); tp.textAlign = Paint.Align.LEFT
            return
        }
        // régua
        val step = when { dur > 60_000 -> 10_000L; dur > 20_000 -> 5_000L; dur > 8_000 -> 2_000L; else -> 1_000L }
        p.color = 0xFF3A3A46.toInt(); p.strokeWidth = dp(1f)
        var t = 0L
        while (t <= dur) {
            val xx = x(t); cv.drawLine(xx, rulerH - dp(5f), xx, rulerH, p)
            cv.drawText("${t / 1000}s", xx + dp(2f), rulerH - dp(6f), tp); t += step
        }

        // 🎬 vídeo: miniaturas + parte cortada escurecida
        r.set(pad, yV, width - pad, yV + vidH)
        p.color = 0xFF1C1C24.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
        if (thumbs.isNotEmpty()) {
            val cw = (width - 2 * pad) / thumbs.size
            cv.save(); cv.clipRect(r)
            thumbs.forEachIndexed { i, b ->
                val dst = RectF(pad + i * cw, yV, pad + (i + 1) * cw + 1, yV + vidH)
                val s = minOf(b.width.toFloat() / dst.width(), b.height.toFloat() / dst.height())
                val sw = (dst.width() * s).toInt(); val sh = (dst.height() * s).toInt()
                val src = Rect((b.width - sw) / 2, (b.height - sh) / 2, (b.width + sw) / 2, (b.height + sh) / 2)
                cv.drawBitmap(b, src, dst, null)
            }
            cv.restore()
        }
        p.color = 0xC0000000.toInt()
        cv.drawRect(pad, yV, x(cutS), yV + vidH, p); cv.drawRect(x(cutE), yV, width - pad, yV + vidH, p)
        frame(cv, x(cutS), yV, x(cutE), yV + vidH, 0xFFFFFFFF.toInt(), selected == 0, true, true)
        cv.drawText("🎬", x(cutS) + dp(12f), yV + dp(15f), lp)

        // 🎵 música: onda da música no trecho que toca
        r.set(pad, yM, width - pad, yM + musH)
        p.color = 0xFF15151B.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
        if (!musicOn) {
            cv.drawText("🎵  ＋ Toque pra escolher a música", x(cutS) + dp(8f), yM + musH / 2 + dp(4f), tp)
        } else {
            val l = x(cutS); val rr = x(cutS + musEndOut)
            r.set(l, yM, rr, yM + musH)
            p.color = 0xFF173A2B.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
            val w = wave
            p.color = 0xFF4CC68E.toInt(); p.strokeWidth = dp(1.5f)
            val mid = yM + musH / 2
            var xx = l + dp(2f)
            while (xx < rr - dp(2f)) {
                val songMs = musicStart + ((xx - l) * msPerPx()).toLong()
                val a = if (w != null && w.isNotEmpty()) w[((songMs / 50).toInt()).coerceIn(0, w.size - 1)] else 0.25f
                val h = maxOf(dp(1.5f), a * (musH / 2 - dp(4f)))
                cv.drawLine(xx, mid - h, xx, mid + h, p); xx += dp(3f)
            }
            frame(cv, l, yM, rr, yM + musH, 0xFF4CC68E.toInt(), selected == 1, false, true)
            cv.drawText("🎵 ${musicName.take(22)}  ·  da música: ${fmt(musicStart)}", l + dp(8f), yM + dp(13f), lp)
        }

        // T texto
        r.set(pad, yT, width - pad, yT + txtH)
        p.color = 0xFF15151B.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
        if (text.isBlank()) {
            cv.drawText("T  ＋ Toque pra escrever o texto", x(cutS) + dp(8f), yT + txtH / 2 + dp(4f), tp)
        } else {
            val l = x(cutS + textS); val rr = x(cutS + txtEndOut)
            r.set(l, yT, rr, yT + txtH)
            p.color = 0xFF4A1C2B.toInt(); cv.drawRoundRect(r, dp(6f), dp(6f), p)
            frame(cv, l, yT, rr, yT + txtH, 0xFFFF5C82.toInt(), selected == 2, true, true)
            cv.save(); cv.clipRect(l + dp(10f), yT, rr - dp(10f), yT + txtH)
            cv.drawText("T  " + text.replace('\n', ' '), l + dp(12f), yT + txtH / 2 + dp(4f), lp)
            cv.restore()
        }

        // cursor
        val px = x(playhead)
        p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = dp(2f)
        cv.drawLine(px, dp(2f), px, height.toFloat(), p)
        cv.drawCircle(px, dp(4f), dp(4f), p)
    }

    private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

    /** Borda do bloco com alças nas pontas (mais grossas quando a camada está selecionada). */
    private fun frame(cv: Canvas, l: Float, t: Float, rr: Float, b: Float, color: Int, sel: Boolean, left: Boolean, right: Boolean) {
        p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = dp(if (sel) 2.5f else 1.2f)
        r.set(l, t, rr, b); cv.drawRoundRect(r, dp(6f), dp(6f), p); p.style = Paint.Style.FILL
        val hw = dp(if (sel) 10f else 7f)
        if (left) { r.set(l, t, l + hw, b); cv.drawRoundRect(r, dp(4f), dp(4f), p); grip(cv, l + hw / 2, t, b) }
        if (right) { r.set(rr - hw, t, rr, b); cv.drawRoundRect(r, dp(4f), dp(4f), p); grip(cv, rr - hw / 2, t, b) }
    }
    private fun grip(cv: Canvas, cx: Float, t: Float, b: Float) {
        val old = p.color; p.color = 0xFF000000.toInt(); p.strokeWidth = dp(1.5f)
        cv.drawLine(cx, t + (b - t) * 0.32f, cx, b - (b - t) * 0.32f, p); p.color = old
    }

    // ------------------------------------------------------------------ toque

    private var mode = ""; private var downX = 0f; private var s0 = 0L; private var e0 = 0L
    private val grab = dp(22f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dur <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; mode = pick(e.x, e.y)
                when (mode) {
                    "musSlip" -> s0 = musicStart
                    "txtMove" -> { s0 = textS; e0 = txtEndOut }
                    "seek" -> { playhead = ms(e.x); onChange("seek", false) }
                }
                if (mode.startsWith("cut") || mode.startsWith("mus") || mode.startsWith("txt")) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> { drag(e.x); invalidate(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode.isNotEmpty() && abs(e.x - downX) > dp(4f)) drag(e.x)
                val what = when { mode.startsWith("cut") -> "cut"; mode.startsWith("mus") -> "music"; mode.startsWith("txt") -> "text"; mode == "seek" -> "seek"; else -> "" }
                if (what.isNotEmpty()) onChange(what, true)
                mode = ""; parent?.requestDisallowInterceptTouchEvent(false); invalidate(); return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun near(a: Float, b: Float) = abs(a - b) < grab

    private fun pick(px: Float, py: Float): String {
        return when {
            py < yV -> "seek"
            py < yM - gap / 2 -> { select(0); when { near(px, x(cutS)) -> "cutS"; near(px, x(cutE)) -> "cutE"; else -> "seek" } }
            py < yT - gap / 2 -> {
                select(1)
                if (!musicOn) "" else { val l = x(cutS); val rr = x(cutS + musEndOut)
                    when { near(px, rr) -> "musE"; px in l..rr -> "musSlip"; else -> "seek" } }
            }
            else -> {
                select(2)
                if (text.isBlank()) "" else { val l = x(cutS + textS); val rr = x(cutS + txtEndOut)
                    when { near(px, l) && near(px, rr) -> if (px < (l + rr) / 2) "txtS" else "txtE"
                        near(px, l) -> "txtS"; near(px, rr) -> "txtE"; px in l..rr -> "txtMove"; else -> "seek" } }
            }
        }
    }

    private fun select(t: Int) { if (selected != t) { selected = t; onSelect(t) } }

    private fun drag(px: Float) {
        val m = ms(px)
        when (mode) {
            "seek" -> { playhead = m; onChange("seek", false); return }
            "cutS" -> { cutS = m.coerceIn(0, cutE - 500); playhead = cutS; clampRel() }
            "cutE" -> { cutE = m.coerceIn(cutS + 500, dur); playhead = cutE; clampRel() }
            "musSlip" -> {
                val delta = ((px - downX) * msPerPx()).toLong()
                musicStart = (s0 - delta).coerceIn(0, maxOf(0L, musicDur - 1000))
            }
            "musE" -> { val v = (m - cutS).coerceIn(500, outDur); musicEnd = if (v >= outDur - 150) 0 else v }
            "txtS" -> { textS = (m - cutS).coerceIn(0, txtEndOut - 300); playhead = cutS + textS }
            "txtE" -> { val v = (m - cutS).coerceIn(textS + 300, outDur); textE = if (v >= outDur - 150) 0 else v; playhead = cutS + txtEndOut - 50 }
            "txtMove" -> {
                val len = e0 - s0
                val ns = (s0 + ((px - downX) * msPerPx()).toLong()).coerceIn(0, maxOf(0L, outDur - len))
                textS = ns; textE = if (ns + len >= outDur - 150) 0 else ns + len; playhead = cutS + textS
            }
            else -> return
        }
        onChange(when { mode.startsWith("cut") -> "cut"; mode.startsWith("mus") -> "music"; else -> "text" }, false)
    }

    /** Depois de mexer no corte, mantém texto e fim da música dentro do vídeo. */
    fun clampRel() {
        val od = outDur
        if (textS > od - 300) textS = maxOf(0, od - 300)
        if (textE in 1..od && textE <= textS) textE = 0
        if (textE > od) textE = 0
        if (musicEnd > od) musicEnd = 0
    }
}
