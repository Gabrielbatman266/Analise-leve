package com.leve.analise

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Edição estilo CapCut: uma trilha principal com vários clipes em sequência (vídeos e fotos)
 * e camadas por cima (textos e imagens), cada uma com início e fim no tempo do vídeo final.
 * Tempos de clipe (s, e) são do arquivo original; tempos de camada são do vídeo exportado.
 */
class Clip(
    var file: String,            // nome do arquivo dentro da pasta do projeto
    var photo: Boolean,
    var srcDur: Long,            // duração do arquivo (foto: 0)
    var s: Long,
    var e: Long,
    var crop: VideoExport.CropSpec = VideoExport.CropSpec(),
    var sound: Boolean = true,   // som original deste clipe
) {
    val len get() = maxOf(0L, e - s)
    fun copy() = Clip(file, photo, srcDur, s, e, crop, sound)
    fun json(): JSONObject = JSONObject().put("file", file).put("photo", photo).put("srcDur", srcDur).put("s", s).put("e", e).put("sound", sound)
        .also { VideoExport.putCrop(it, crop) }
    companion object {
        fun of(o: JSONObject) = Clip(o.optString("file"), o.optBoolean("photo"), o.optLong("srcDur"), o.optLong("s"), o.optLong("e"),
            VideoExport.cropOf(o), o.optBoolean("sound", true))
    }
}

class Layer(
    var kind: String,            // "text" ou "image"
    var text: String = "",
    var style: TextStyle = TextStyle(),
    var file: String = "",       // imagem (pasta do projeto)
    var s: Long = 0,
    var e: Long = 0,
    var x: Float = 0.5f, var y: Float = 0.5f, var scale: Float = 0.5f, // imagem: centro e largura (fração do quadro)
) {
    val isText get() = kind == "text"
    fun copy() = Layer(kind, text, style.copy(), file, s, e, x, y, scale)
    fun json(): JSONObject = JSONObject().put("kind", kind).put("text", text).put("style", style.json()).put("file", file)
        .put("s", s).put("e", e).put("x", x.toDouble()).put("y", y.toDouble()).put("scale", scale.toDouble())
    fun visibleAt(ms: Long) = ms >= s && ms < e
    companion object {
        fun of(o: JSONObject) = Layer(o.optString("kind", "text"), o.optString("text"), TextStyle.of(o.optJSONObject("style")), o.optString("file"),
            o.optLong("s"), o.optLong("e"), o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(), o.optDouble("scale", 0.5).toFloat())
    }
}

class EditState {
    val clips = ArrayList<Clip>()
    val layers = ArrayList<Layer>()
    var music: String = ""       // caminho completo da música
    var musicStart = 0L          // de onde a música começa (tempo da música)
    var musicEnd = 0L            // onde a música para (tempo do vídeo; 0 = até o fim)
    var musicDur = 0L            // só pra tela
    var mute = false

    fun total(): Long = clips.sumOf { it.len }
    fun startOf(k: Int): Long { var t = 0L; for (i in 0 until minOf(k, clips.size)) t += clips[i].len; return t }

    /** Qual clipe está no tempo [out] do vídeo final, e o tempo dentro do arquivo dele. */
    fun locate(out: Long): Pair<Int, Long> {
        if (clips.isEmpty()) return -1 to 0L
        var t = 0L
        clips.forEachIndexed { i, c ->
            if (out < t + c.len || i == clips.size - 1) return i to (c.s + (out - t)).coerceIn(c.s, maxOf(c.s, c.e - 1))
            t += c.len
        }
        return clips.size - 1 to clips.last().s
    }

    /** ✂ Divide o clipe no tempo [out] em dois. */
    fun splitClip(out: Long): Int? {
        val (k, local) = locate(out); if (k < 0) return null
        val c = clips[k]
        if (local - c.s < 200 || c.e - local < 200) return null
        val b = c.copy().apply { s = local }
        c.e = local
        clips.add(k + 1, b); return k + 1
    }

    /** ✂ Divide a camada no tempo [out]. */
    fun splitLayer(i: Int, out: Long): Int? {
        val l = layers.getOrNull(i) ?: return null
        if (out - l.s < 200 || l.e - out < 200) return null
        val b = l.copy().apply { s = out }
        l.e = out
        layers.add(i + 1, b); return i + 1
    }

    /** Mantém as camadas e a música dentro da duração nova. */
    fun clamp() {
        val tot = total()
        layers.forEach { l ->
            if (l.e <= 0 || l.e > tot) l.e = tot
            if (l.s > tot - 200) l.s = maxOf(0, tot - 200)
            if (l.e <= l.s) l.e = minOf(tot, l.s + 200)
        }
        if (musicEnd >= tot) musicEnd = 0
    }

    fun json(): JSONObject {
        val cs = JSONArray(); clips.forEach { cs.put(it.json()) }
        val ls = JSONArray(); layers.forEach { ls.put(it.json()) }
        return JSONObject().put("clips", cs).put("layers", ls).put("music", music).put("musicStart", musicStart).put("musicEnd", musicEnd).put("mute", mute)
    }

    fun copy(): EditState = of(json()).also { it.musicDur = musicDur }

    /** Grava a edição no projeto + os campos antigos (a produção automática e a exportação em lote leem esses). */
    fun saveTo(p: JSONObject) {
        p.put("edit", json())
        val c0 = clips.firstOrNull()
        if (c0 != null) {
            p.put("photo", c0.photo)
            if (c0.photo) p.put("secs", maxOf(1, (c0.len / 1000).toInt()))
            p.put("cutStart", if (c0.photo) 0 else c0.s); p.put("cutEnd", if (c0.photo) 0 else c0.e)
            VideoExport.putCrop(p, c0.crop)
        }
        val t0 = layers.firstOrNull { it.isText && it.text.isNotBlank() }
        val tot = total()
        p.put("text", t0?.text ?: "")
        t0?.let { p.put("style", it.style.json()) }
        p.put("textStart", t0?.s ?: 0); p.put("textEnd", t0?.let { if (it.e >= tot - 150) 0 else it.e } ?: 0)
        p.put("music", music); p.put("musicStart", musicStart); p.put("musicEnd", musicEnd); p.put("mute", mute)
    }

    /** Só um clipe, no máximo um texto e nada de imagem: dá pra exportar do jeito antigo (mais testado). */
    fun simple() = clips.size == 1 && clips[0].sound && layers.none { !it.isText } && layers.count { it.text.isNotBlank() } <= 1

    companion object {
        fun of(o: JSONObject): EditState = EditState().apply {
            o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) clips.add(Clip.of(a.getJSONObject(i))) }
            o.optJSONArray("layers")?.let { a -> for (i in 0 until a.length()) layers.add(Layer.of(a.getJSONObject(i))) }
            music = o.optString("music"); musicStart = o.optLong("musicStart"); musicEnd = o.optLong("musicEnd"); mute = o.optBoolean("mute")
        }

        /** Projeto salvo antes das camadas: vira 1 clipe + 1 texto. */
        fun fromProject(p: JSONObject, srcDur: Long): EditState {
            p.optJSONObject("edit")?.let { e -> val st = of(e); if (st.clips.isNotEmpty()) return st }
            val st = EditState()
            val photo = p.optBoolean("photo")
            val c = if (photo) Clip("src.jpg", true, 0, 0, p.optInt("secs", 8) * 1000L, VideoExport.cropOf(p))
                    else { val ce = p.optLong("cutEnd").let { if (it <= 0 || it > srcDur) srcDur else it }
                           Clip("src.mp4", false, srcDur, p.optLong("cutStart").coerceIn(0, maxOf(0, ce - 300)), ce, VideoExport.cropOf(p)) }
            st.clips.add(c)
            val txt = p.optString("text")
            if (txt.isNotBlank()) {
                val tot = st.total()
                val te = p.optLong("textEnd").let { if (it <= 0 || it > tot) tot else it }
                st.layers.add(Layer("text", txt, TextStyle.of(p.optJSONObject("style")), s = p.optLong("textStart").coerceIn(0, maxOf(0, te - 200)), e = te))
            }
            st.music = p.optString("music"); st.musicStart = p.optLong("musicStart"); st.musicEnd = p.optLong("musicEnd"); st.mute = p.optBoolean("mute")
            return st
        }
    }
}

/** Desfazer / refazer: guarda fotos do estado (JSON) antes de cada mudança. */
class Undo(private val max: Int = 40) {
    private val back = ArrayList<String>(); private val fwd = ArrayList<String>()
    fun push(st: EditState) { val j = st.json().toString(); if (back.lastOrNull() == j) return; back.add(j); if (back.size > max) back.removeAt(0); fwd.clear() }
    fun undo(cur: EditState): EditState? { val j = back.removeLastOrNull() ?: return null; fwd.add(cur.json().toString()); return EditState.of(JSONObject(j)) }
    fun redo(cur: EditState): EditState? { val j = fwd.removeLastOrNull() ?: return null; back.add(cur.json().toString()); return EditState.of(JSONObject(j)) }
    fun canUndo() = back.isNotEmpty(); fun canRedo() = fwd.isNotEmpty()
    fun clear() { back.clear(); fwd.clear() }
}

/** Desenha as camadas visíveis num quadro (prévia e exportação usam o mesmo desenho). */
object LayerArt {
    private val cache = HashMap<String, Bitmap>()

    fun image(dir: File, name: String): Bitmap? = synchronized(cache) {
        val f = File(dir, name); val key = f.path + ":" + f.lastModified()
        cache[key] ?: (if (f.exists()) BitmapFactory.decodeFile(f.path) else null)?.also { if (cache.size > 12) cache.clear(); cache[key] = it }
    }

    fun draw(cv: Canvas, layers: List<Layer>, dir: File, w: Int, h: Int) {
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        layers.forEach { l ->
            if (l.isText) { if (l.text.isNotBlank()) VideoExport.drawText(cv, l.text, l.style, w, h) }
            else image(dir, l.file)?.let { b -> cv.drawBitmap(b, null, imageRect(l, b, w, h), p) }
        }
    }

    fun imageRect(l: Layer, b: Bitmap, w: Int, h: Int): RectF {
        val iw = w * l.scale; val ih = iw * b.height / maxOf(1, b.width)
        return RectF(w * l.x - iw / 2, h * l.y - ih / 2, w * l.x + iw / 2, h * l.y + ih / 2)
    }

    fun render(layers: List<Layer>, dir: File, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), layers, dir, w, h); return out
    }

    /** Caixa da camada no quadro w x h (pra tocar e arrastar na prévia). */
    fun box(l: Layer, dir: File, w: Int, h: Int): RectF =
        if (l.isText) VideoExport.textBox(l.text, l.style, w, h) else image(dir, l.file)?.let { imageRect(l, it, w, h) } ?: RectF()
}

/**
 * Overlay da exportação: mostra as camadas certas em cada momento. Recebe as camadas já no tempo
 * DESTE clipe (deslocadas pelo início dele) e redesenha só quando o conjunto visível muda.
 */
class LayersOverlay(private val layers: List<Layer>, private val offsetMs: Long, private val dir: File, private val w: Int, private val h: Int) :
    androidx.media3.effect.BitmapOverlay() {
    private var first = Long.MIN_VALUE
    private var key = "?"
    private var bmp: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val shown = androidx.media3.effect.OverlaySettings.Builder().build()
    private val hidden = androidx.media3.effect.OverlaySettings.Builder().setAlphaScale(0f).build()
    private fun ms(t: Long): Long { if (first == Long.MIN_VALUE) first = t; return offsetMs + (t - first) / 1000 }
    private fun visible(t: Long) = layers.filter { it.visibleAt(ms(t)) }
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val v = visible(presentationTimeUs)
        val k = v.joinToString(",") { layers.indexOf(it).toString() }
        if (k != key && v.isNotEmpty()) bmp = LayerArt.render(v, dir, w, h)
        if (v.isNotEmpty()) key = k
        return bmp
    }
    override fun getOverlaySettings(presentationTimeUs: Long): androidx.media3.effect.OverlaySettings =
        if (visible(presentationTimeUs).isEmpty()) hidden else shown
}
