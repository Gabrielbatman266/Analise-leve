package com.leve.analise

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Estilo do texto no vídeo, no jeito do CapCut. */
data class TextStyle(
    var size: Int = 52,          // 20..100 (milésimos da largura)
    var color: String = "Branco",
    var bg: String = "Faixa escura",
    var pos: String = "Baixo",
    var font: String = "Negrito",
    var align: String = "Centro",
) {
    fun save(c: Context) = Store.prefs(c).edit().putString("txt_style",
        JSONObject().put("size", size).put("color", color).put("bg", bg).put("pos", pos).put("font", font).put("align", align).toString()).apply()

    companion object {
        val COLORS = linkedMapOf("Branco" to Color.WHITE, "Preto" to Color.BLACK, "Amarelo" to 0xFFFFD43B.toInt(),
            "Rosa" to 0xFFFF5C82.toInt(), "Vermelho" to 0xFFFF3B3B.toInt(), "Azul" to 0xFF4DA3FF.toInt())
        val BGS = listOf("Sem fundo", "Faixa escura", "Faixa clara", "Contorno")
        val POS = listOf("Cima", "Meio", "Baixo")
        val FONTS = listOf("Negrito", "Fina", "Serifa", "Manuscrita", "Condensada")
        val ALIGNS = listOf("Esquerda", "Centro", "Direita")
        fun load(c: Context): TextStyle = try {
            val o = JSONObject(Store.prefs(c).getString("txt_style", "{}") ?: "{}")
            TextStyle(o.optInt("size", 52), o.optString("color", "Branco"), o.optString("bg", "Faixa escura"),
                o.optString("pos", "Baixo"), o.optString("font", "Negrito"), o.optString("align", "Centro"))
        } catch (e: Exception) { TextStyle() }
    }
}

/** Texto que só aparece entre início e fim (tempo contado do começo do vídeo exportado). */
class TimedOverlay(private val bmp: Bitmap, private val startUs: Long, private val endUs: Long) : BitmapOverlay() {
    private var first = Long.MIN_VALUE
    private val shown = androidx.media3.effect.OverlaySettings.Builder().build()
    private val hidden = androidx.media3.effect.OverlaySettings.Builder().setAlphaScale(0f).build()
    private fun base(t: Long): Long { if (first == Long.MIN_VALUE) first = t; return first }
    override fun getBitmap(presentationTimeUs: Long): Bitmap { base(presentationTimeUs); return bmp }
    override fun getOverlaySettings(presentationTimeUs: Long): androidx.media3.effect.OverlaySettings {
        val rel = presentationTimeUs - base(presentationTimeUs)
        return if (rel >= startUs && (endUs <= 0 || rel < endUs)) shown else hidden
    }
}

/** Grava o MP4 sem localização, data de criação nem outros dados do original (só mantém a rotação). */
class CleanMuxerFactory(private val d: androidx.media3.muxer.Muxer.Factory = androidx.media3.transformer.DefaultMuxer.Factory()) :
    androidx.media3.muxer.Muxer.Factory by d {
    override fun create(path: String): androidx.media3.muxer.Muxer {
        val m = d.create(path)
        return object : androidx.media3.muxer.Muxer by m {
            override fun addMetadataEntry(metadataEntry: androidx.media3.common.Metadata.Entry) {
                if (metadataEntry.javaClass.simpleName.contains("Orientation")) m.addMetadataEntry(metadataEntry)
            }
        }
    }
}

/** Edição e exportação dos SEUS vídeos e fotos: 1080x1920, texto, música de fundo, biblioteca. */
object VideoExport {
    const val INSTAGRAM = "com.instagram.android"
    const val W = 1080
    const val H = 1920
    private var current: Transformer? = null

    // ------------------------------------------------------------------ texto

    fun typeface(f: String): Typeface = when (f) {
        "Fina" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        "Serifa" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "Manuscrita" -> Typeface.create("cursive", Typeface.BOLD)
        "Condensada" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /** Camada transparente do tamanho do quadro com o texto (aceita parágrafos). */
    fun textBitmap(text: String, st: TextStyle, w: Int = W, h: Int = H): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (text.isBlank()) return bmp
        val cv = Canvas(bmp)
        val color = TextStyle.COLORS[st.color] ?: Color.WHITE
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = w * st.size / 1000f; typeface = typeface(st.font)
            if (st.bg == "Sem fundo") setShadowLayer(w * 0.008f, 0f, w * 0.003f, 0xB0000000.toInt())
        }
        val align = when (st.align) { "Esquerda" -> Layout.Alignment.ALIGN_NORMAL; "Direita" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        val maxW = (w * 0.84f).toInt()
        @Suppress("DEPRECATION")
        val sl = StaticLayout(text.trimEnd(), tp, maxW, align, 1.12f, 0f, false)
        val left = (w - maxW) / 2f
        val top = when (st.pos) { "Cima" -> h * 0.12f; "Meio" -> (h - sl.height) / 2f; else -> h * 0.80f - sl.height }
        if (st.bg == "Faixa escura" || st.bg == "Faixa clara") {
            var minL = maxW.toFloat(); var maxR = 0f
            for (i in 0 until sl.lineCount) { minL = minOf(minL, sl.getLineLeft(i)); maxR = maxOf(maxR, sl.getLineRight(i)) }
            val padX = w * 0.035f; val padY = w * 0.022f
            val box = RectF(left + minL - padX, top - padY, left + maxR + padX, top + sl.height + padY)
            val bgc = if (st.bg == "Faixa escura") 0x99000000.toInt() else 0xE6FFFFFF.toInt()
            cv.drawRoundRect(box, w * 0.03f, w * 0.03f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = bgc })
        }
        cv.save(); cv.translate(left, top)
        if (st.bg == "Contorno") {
            val stroke = TextPaint(tp).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.006f; this.color = if (color == Color.BLACK) Color.WHITE else Color.BLACK }
            @Suppress("DEPRECATION")
            StaticLayout(text.trimEnd(), stroke, maxW, align, 1.12f, 0f, false).draw(cv)
        }
        sl.draw(cv); cv.restore()
        return bmp
    }

    // ------------------------------------------------------------------ prévia

    /** Primeiro quadro (vídeo) ou a foto, encaixado em 9:16 com fundo preto. */
    fun durationMs(c: Context, src: Uri): Long = try {
        val r = MediaMetadataRetriever(); r.setDataSource(c, src)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release(); d
    } catch (e: Exception) { 0L }

    fun durationMs(f: File): Long = try {
        val r = MediaMetadataRetriever(); r.setDataSource(f.path)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release(); d
    } catch (e: Exception) { 0L }

    fun frameAt(c: Context, src: Uri, ms: Long): Bitmap? = try {
        val r = MediaMetadataRetriever(); r.setDataSource(c, src)
        val b = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release(); b?.let { fit(it, W / 2, H / 2) }
    } catch (e: Exception) { null }

    fun baseFrame(c: Context, src: Uri, isPhoto: Boolean): Bitmap? = try {
        val raw: Bitmap? = if (isPhoto) {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            c.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, o) }
        } else {
            val r = MediaMetadataRetriever()
            r.setDataSource(c, src)
            val b = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release(); b
        }
        raw?.let { fit(it, W / 2, H / 2) }
    } catch (e: Exception) { null }

    private fun fit(b: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(Color.BLACK)
        val k = minOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val dw = (b.width * k).toInt(); val dh = (b.height * k).toInt()
        cv.drawBitmap(b, null, Rect((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun preview(base: Bitmap, text: String, st: TextStyle): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(textBitmap(text, st, out.width, out.height), 0f, 0f, null)
        return out
    }

    // ------------------------------------------------------------------ exportar

    class Job(val src: Uri, val isPhoto: Boolean, val photoSeconds: Int, val text: String, val style: TextStyle,
              val music: File?, val mute: Boolean, val quality: String = "1080p",
              val startMs: Long = 0, val endMs: Long = 0, val musicStartMs: Long = 0,
              val textStartMs: Long = 0, val textEndMs: Long = 0, val musicEndMs: Long = 0,
              val crop: CropSpec = CropSpec())

    // ------------------------------------------------------------------ recorte / zoom
    /** zoom ≥ 1; px/py de -1 a 1 (posição do recorte); fill = preenche 9:16 cortando as sobras (vídeo deitado). */
    data class CropSpec(val zoom: Float = 1f, val px: Float = 0f, val py: Float = 0f, val fill: Boolean = false) {
        val active get() = zoom > 1.01f || fill
    }
    fun cropOf(o: JSONObject?) = if (o == null) CropSpec() else
        CropSpec(o.optDouble("cropZoom", 1.0).toFloat(), o.optDouble("cropX", 0.0).toFloat(), o.optDouble("cropY", 0.0).toFloat(), o.optBoolean("cropFill"))
    fun putCrop(o: JSONObject, s: CropSpec) { o.put("cropZoom", s.zoom.toDouble()); o.put("cropX", s.px.toDouble()); o.put("cropY", s.py.toDouble()); o.put("cropFill", s.fill) }

    /** Recorte em coordenadas -1..1: [esquerda, direita, baixo, cima]. */
    fun cropNdc(w: Int, h: Int, s: CropSpec): FloatArray {
        val ar = w.toFloat() / maxOf(1, h); val t = 9f / 16f
        var hw = 1f; var hh = 1f
        if (s.fill) { if (ar > t) hw = t / ar else hh = ar / t }
        hw /= s.zoom; hh /= s.zoom
        val cx = s.px.coerceIn(-1f, 1f) * (1f - hw); val cy = -s.py.coerceIn(-1f, 1f) * (1f - hh)
        return floatArrayOf(cx - hw, cx + hw, cy - hh, cy + hh)
    }

    fun cropBitmap(b: Bitmap, s: CropSpec): Bitmap {
        if (!s.active) return b
        val n = cropNdc(b.width, b.height, s)
        val l = ((n[0] + 1f) / 2f * b.width).toInt().coerceIn(0, b.width - 1)
        val r = ((n[1] + 1f) / 2f * b.width).toInt().coerceIn(l + 1, b.width)
        val top = ((1f - n[3]) / 2f * b.height).toInt().coerceIn(0, b.height - 1)
        val bot = ((1f - n[2]) / 2f * b.height).toInt().coerceIn(top + 1, b.height)
        return Bitmap.createBitmap(b, l, top, r - l, bot - top)
    }

    /** Põe a imagem numa tela 9:16 (igual ao vídeo exportado). */
    fun fit916(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(0xFF000000.toInt())
        val sc = minOf(540f / b.width, 960f / b.height)
        val w = b.width * sc; val h = b.height * sc
        cv.drawBitmap(b, null, android.graphics.RectF((540 - w) / 2, (960 - h) / 2, (540 + w) / 2, (960 + h) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Tamanho como aparece na tela (vídeo girado troca largura/altura). */
    fun displaySize(c: Context, src: Uri, photo: Boolean): Pair<Int, Int> = try {
        if (photo) {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(src)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            o.outWidth to o.outHeight
        } else {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(c, src)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            r.release()
            if (rot == 90 || rot == 270) h to w else w to h
        }
    } catch (e: Exception) { 1080 to 1920 }

    /** Tamanho (9:16), bitrate e codec por qualidade. 2K/4K usam HEVC: mesma qualidade com arquivo bem menor. */
    data class Q(val w: Int, val h: Int, val bitrate: Int, val mime: String)
    fun q(name: String) = when (name) {
        "2K" -> Q(1440, 2560, 22_000_000, MimeTypes.VIDEO_H265)
        "4K" -> Q(2160, 3840, 40_000_000, MimeTypes.VIDEO_H265)
        else -> Q(1080, 1920, 16_000_000, MimeTypes.VIDEO_H264)
    }

    /**
     * Roda na thread principal (o Transformer precisa de Looper).
     * Vídeo pronto vai pra biblioteca do app e pra galeria (Movies/PostLeve).
     */
    fun export(c: Context, job: Job, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit) {
        val out = File(c.cacheDir, "exp_${System.currentTimeMillis()}.mp4")
        val qq = q(job.quality)
        try {
            val fx = ArrayList<androidx.media3.common.Effect>()
            if (job.crop.active) {
                val (sw, sh) = displaySize(c, job.src, job.isPhoto)
                val n = cropNdc(sw, sh, job.crop)
                fx.add(Crop(n[0], n[1], n[2], n[3]))
            }
            fx.add(Presentation.createForWidthAndHeight(qq.w, qq.h, Presentation.LAYOUT_SCALE_TO_FIT))
            if (job.text.isNotBlank()) {
                val tb = textBitmap(job.text, job.style, qq.w, qq.h)
                val ov = if (job.textStartMs > 0 || job.textEndMs > 0) TimedOverlay(tb, job.textStartMs * 1000, job.textEndMs * 1000)
                         else BitmapOverlay.createStaticBitmapOverlay(tb)
                fx.add(OverlayEffect(ImmutableList.of(ov)))
            }
            val media = if (job.isPhoto) MediaItem.Builder().setUri(job.src).setMimeType(MimeTypes.IMAGE_JPEG).build()
                else if (job.startMs > 0 || job.endMs > 0) {
                    val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(job.startMs)
                    if (job.endMs > job.startMs) clip.setEndPositionMs(job.endMs)
                    MediaItem.Builder().setUri(job.src).setClippingConfiguration(clip.build()).build()
                } else MediaItem.fromUri(job.src)
            val vb = EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), fx))
            if (job.isPhoto) {
                @Suppress("DEPRECATION")
                vb.setDurationUs(job.photoSeconds * 1_000_000L)
                vb.setFrameRate(30)
            }
            if (job.music != null || job.mute) vb.setRemoveAudio(true)
            val seqs = ArrayList<EditedMediaItemSequence>()
            seqs.add(EditedMediaItemSequence(vb.build()))
            if (job.music != null) {
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(job.musicStartMs)
                if (job.musicEndMs > 0) clip.setEndPositionMs(job.musicStartMs + job.musicEndMs)
                val mi = MediaItem.Builder().setUri(Uri.fromFile(job.music)).setClippingConfiguration(clip.build()).build()
                // com corte no fim a música toca uma vez só; sem corte ela repete até o vídeo acabar
                seqs.add(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(mi).build()), job.musicEndMs <= 0))
            }
            val comp = Composition.Builder(seqs).build()
            val enc = DefaultEncoderFactory.Builder(c)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(qq.bitrate).build())
                .setEnableFallback(true).build()
            val t = Transformer.Builder(c)
                .setVideoMimeType(qq.mime).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(enc)
                .setMuxerFactory(CleanMuxerFactory())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { current = null; onDone(out, null) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        current = null; out.delete(); onDone(null, exportException.message ?: "Erro ao exportar")
                    }
                }).build()
            current = t
            t.start(comp, out.path)
            pollProgress(onProgress)
        } catch (e: Exception) { onDone(null, e.message) }
    }

    /** Cópia nova de um vídeo já pronto: mesma imagem e som (sem reprocessar), sem os dados do original. */
    fun cleanCopy(c: Context, src: Uri, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit) {
        val out = File(c.cacheDir, "imp_${System.currentTimeMillis()}.mp4")
        try {
            val t = Transformer.Builder(c)
                .setMuxerFactory(CleanMuxerFactory())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { current = null; onDone(out, null) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        current = null; out.delete(); onDone(null, exportException.message ?: "Erro ao importar")
                    }
                }).build()
            current = t
            t.start(EditedMediaItem.Builder(MediaItem.fromUri(src)).build(), out.path)
            pollProgress(onProgress)
        } catch (e: Exception) { onDone(null, e.message) }
    }

    // ---------- rodada aleatória: cada vídeo sai uma vez; quando todos saem, recomeça
    private fun cycleSet(c: Context): MutableSet<String> = HashSet(Store.prefs(c).getStringSet("vid_cycle", emptySet()) ?: emptySet())

    /** Legenda pra postar um vídeo pronto: nenhuma se ele for de categoria sem legenda. */
    fun captionFor(c: Context, o: JSONObject?, fresh: Boolean): String {
        if (o != null && (o.optBoolean("semleg") || Contexts.noCaption(c, o.optString("ctx")))) return ""
        val old = o?.optString("legenda").orEmpty()
        val cap = if (!fresh && old.isNotBlank() && o?.optInt("posts") == 0 && old.trim() !in Captions.recentCaps(c).take(6)) old else Captions.randomCaption(c, listOf(old))
        Captions.remember(c, null, cap)
        return cap
    }

    /** Origem de um vídeo pronto: o vídeo cru de onde ele saiu (vídeos importados contam como origem própria). */
    fun origin(o: JSONObject): String = o.optString("proj").ifBlank { "solo:" + o.optString("id") }

    fun recentIds(c: Context) = recent(c, "vid_rec")
    fun recentOrigins(c: Context) = recent(c, "vid_last_orig")

    private fun recent(c: Context, key: String): List<String> =
        Store.prefs(c).getString(key, "")!!.split('\u0001').filter { it.isNotBlank() }

    /**
     * Próximo da fila. Regras: cada vídeo sai uma vez por rodada; NUNCA o mesmo vídeo cru em seguida
     * (evita as últimas origens enquanto houver opção); texto diferente do último; entre as opções,
     * a origem que está há mais tempo sem sair.
     */
    private fun pushRec(c: Context, key: String, v: String, max: Int) {
        val l = (listOf(v) + recent(c, key).filter { it != v }).take(max)
        Store.prefs(c).edit().putString(key, l.joinToString("\u0001")).apply()
    }

    /** Peso de um vídeo: bombou ≈ 3x a 8x; nunca postado 2x; vídeo cru que costuma bombar ganha bônus. */
    private fun weight(c: Context, o: JSONObject, rec: List<String>, projs: Map<String, JSONObject>): Double {
        val idx = rec.indexOf(o.optString("id"))
        val pj = projs[o.optString("proj")]
        val origin = if (pj == null) 1.0 else Math.exp(2.0 * (Learn.mean(pj) - 0.5))
        return Math.exp(5.0 * (Learn.chanceMean(c, o) - 0.5)) * (1.0 + 0.6 * o.optInt("top")) * (if (o.optInt("posts") == 0) 2.0 else 1.0) * (if (idx < 0) 1.5 else 1.0 + idx / 10.0) * origin
    }

    /** Candidatos agora: o que saiu há pouco espera (o que bombou espera só 2 posts) e o mesmo vídeo cru não sai em seguida. */
    private fun candidates(c: Context, lib: List<JSONObject>, rec: List<String>, recentO: List<String>): List<JSONObject> {
        val k = minOf(lib.size - 1, maxOf(minOf(3, lib.size - 1), (lib.size * 0.6).toInt()), 20)
        var pool = lib.filter { o ->
            val idx = rec.indexOf(o.optString("id"))
            idx < 0 || idx >= (if (Learn.isWin(o)) minOf(2, k) else k)
        }.ifEmpty { lib.filter { it.optString("id") != rec.firstOrNull() }.ifEmpty { lib } }
        val origins = lib.map { origin(it) }.distinct().size
        val avoid = recentO.take(minOf(3, maxOf(0, origins - 1)))
        for (n in avoid.size downTo 1) { val f = pool.filter { origin(it) !in avoid.take(n) }; if (f.isNotEmpty()) { pool = f; break } }
        val lastTxt = recent(c, "vid_last_txt").take(3)
        return pool.filter { it.optString("texto").trim().lowercase() !in lastTxt || it.optString("texto").isBlank() }.ifEmpty { pool }
    }

    /**
     * Próximo vídeo: valoriza MUITO o que bombou, mas nunca repete em seguida
     * (nem o mesmo vídeo, nem o mesmo vídeo cru, nem o mesmo texto).
     */
    fun cycleNext(c: Context): String? {
        val lib = library(c).filter { File(videoDir(c), "${it.optString("id")}.mp4").exists() }
        if (lib.isEmpty()) return null
        val rec = recent(c, "vid_rec")
        val recentO = recent(c, "vid_last_orig")
        val projs = Projects.list(c).associateBy { it.optString("id") }
        val byId = lib.associateBy { it.optString("id") }
        Health.takeForced(c, "force_vids") { id -> byId[id]?.let { o -> id !in rec.take(2) && origin(o) != recentO.firstOrNull() } == true }?.let { fid ->
            pushRec(c, "vid_rec", fid, 40); pushRec(c, "vid_last_orig", origin(byId.getValue(fid)), 20); return fid
        }
        val cand = candidates(c, lib, rec, recentO)
        val w = cand.map { weight(c, it, rec, projs) }
        var r = Math.random() * w.sum(); var pick = cand.last()
        for (i in cand.indices) { r -= w[i]; if (r <= 0) { pick = cand[i]; break } }
        pushRec(c, "vid_rec", pick.optString("id"), 40)
        pushRec(c, "vid_last_orig", origin(pick), 20)
        return pick.optString("id")
    }

    fun markPosted(c: Context, id: String) {
        val lib = library(c)
        val ids = lib.map { it.optString("id") }.toSet()
        val s = cycleSet(c); s.add(id); s.retainAll(ids)
        pushRec(c, "vid_rec", id, 40)
        val e = Store.prefs(c).edit().putString("vid_last", id)
        lib.firstOrNull { it.optString("id") == id }?.let { o ->
            val ro = (listOf(origin(o)) + recent(c, "vid_last_orig").filter { it != origin(o) }).take(20)
            e.putString("vid_last_orig", ro.joinToString("\u0001"))
            val t = o.optString("texto").trim().lowercase()
            if (t.isNotEmpty()) e.putString("vid_last_txt", (listOf(t) + recent(c, "vid_last_txt")).take(5).joinToString("\u0001"))
        }
        if (ids.isNotEmpty() && s.containsAll(ids)) { e.remove("vid_cycle"); e.putInt("vid_rounds", Store.prefs(c).getInt("vid_rounds", 0) + 1) }
        else e.putStringSet("vid_cycle", s)
        e.apply()
    }

    /** Ordem prevista dos próximos [n] (simulação das mesmas regras, sem sorteio). */
    fun upcoming(c: Context, n: Int): List<JSONObject> {
        val lib = library(c).filter { File(videoDir(c), "${it.optString("id")}.mp4").exists() }
        val rec = recent(c, "vid_rec").toMutableList()
        val recentO = recent(c, "vid_last_orig").toMutableList()
        val projs = Projects.list(c).associateBy { it.optString("id") }
        val out = ArrayList<JSONObject>()
        repeat(minOf(n, lib.size)) {
            val cand = candidates(c, lib, rec, recentO).filter { it !in out }.ifEmpty { return out }
            val pick = cand.maxBy { weight(c, it, rec, projs) }
            out.add(pick)
            rec.remove(pick.optString("id")); rec.add(0, pick.optString("id"))
            recentO.remove(origin(pick)); recentO.add(0, origin(pick))
        }
        return out
    }

    /** (postados nesta rodada, total, rodadas completas) */
    fun cycleStatus(c: Context): Triple<Int, Int, Int> {
        val ids = library(c).map { it.optString("id") }.toSet()
        return Triple(cycleSet(c).count { it in ids }, ids.size, Store.prefs(c).getInt("vid_rounds", 0))
    }

    /** Só o áudio de um vídeo seu, guardado na biblioteca de músicas. */
    fun extractAudio(c: Context, src: Uri, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit, name: String? = null) {
        val base = name?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.substringBeforeLast('.')?.take(50)?.ifBlank { null } ?: "audio_${System.currentTimeMillis()}"
        var out = File(musicDir(c), "$base.m4a")
        var n = 2
        while (out.exists()) { out = File(musicDir(c), "$base ($n).m4a"); n++ }
        try {
            val t = Transformer.Builder(c).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        current = null
                        if (Store.prefs(c).getBoolean("save_gallery", false)) saveToGallery(c, out, true)
                        onDone(out, null)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        current = null; out.delete(); onDone(null, exportException.message ?: "Erro ao extrair")
                    }
                }).build()
            current = t
            t.start(EditedMediaItem.Builder(MediaItem.fromUri(src)).setRemoveVideo(true).build(), out.path)
            pollProgress(onProgress)
        } catch (e: Exception) { onDone(null, e.message) }
    }

    private fun pollProgress(onProgress: (Int) -> Unit) {
        val ph = ProgressHolder()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                val tr = current ?: return
                if (tr.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress)
                h.postDelayed(this, 400)
            }
        })
    }

    fun busy() = current != null
    fun cancel() { try { current?.cancel() } catch (e: Exception) { }; current = null }

    // ------------------------------------------------------------------ músicas

    fun musicDir(c: Context) = File(c.filesDir, "musicas").apply { mkdirs() }
    fun musics(c: Context): List<File> = (musicDir(c).listFiles() ?: emptyArray()).filter { it.length() > 0 }.sortedByDescending { it.lastModified() }

    fun importMusic(c: Context, uri: Uri): File? = try {
        var name = "musica_${System.currentTimeMillis()}"
        c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0)?.let { n -> name = n } }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60)
        val f = File(musicDir(c), safe)
        c.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        f
    } catch (e: Exception) { null }

    fun nice(f: File) = f.nameWithoutExtension.replace('_', ' ')

    fun displayName(c: Context, u: Uri): String? = try {
        c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ biblioteca de vídeos

    fun videoDir(c: Context) = File(c.filesDir, "videos").apply { mkdirs() }
    private fun libFile(c: Context) = File(c.filesDir, "videos.json")

    fun library(c: Context): MutableList<JSONObject> = JsonFile.read(libFile(c))

    private fun saveLib(c: Context, l: List<JSONObject>) = JsonFile.write(libFile(c), l)

    /** Volta pra fila vídeos cujo arquivo existe mas sumiram da lista. */
    fun restore(c: Context, lost: List<Pair<String, Long>>): Int = JsonFile.edit(libFile(c)) { l ->
        val have = l.map { it.optString("id") }.toSet(); var n = 0
        lost.filter { it.first !in have }.forEach { (id, at) ->
            l.add(JSONObject().put("id", id).put("legenda", "").put("at", at).put("posts", 0).put("recuperado", true)); n++
        }
        n
    }

    /** Guarda o vídeo pronto no app (com miniatura e legenda) e uma cópia na galeria. */
    fun addToLibrary(c: Context, tmp: File, caption: String, imported: Boolean = false): String {
        var id = "v" + System.currentTimeMillis().toString(36)
        while (File(videoDir(c), "$id.mp4").exists()) id += (0..9).random()
        val f = File(videoDir(c), "$id.mp4")
        tmp.copyTo(f, true); tmp.delete()
        try {
            val r = MediaMetadataRetriever(); r.setDataSource(f.path)
            r.getFrameAtTime(500_000)?.let { b ->
                val t = Bitmap.createScaledBitmap(b, 216, 384, true)
                File(videoDir(c), "$id.jpg").outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
            r.release()
        } catch (e: Exception) { }
        if (Store.prefs(c).getBoolean("save_gallery", false)) saveToGallery(c, f, false)
        JsonFile.edit(libFile(c)) { l -> l.add(0, JSONObject().put("id", id).put("legenda", caption).put("at", System.currentTimeMillis()).put("posts", 0).put("importado", imported)) }
        return id
    }

    fun updateLib(c: Context, id: String, f: (JSONObject) -> Unit) { JsonFile.edit(libFile(c)) { l -> l.firstOrNull { it.optString("id") == id }?.let(f) } }
    fun removeLib(c: Context, id: String) {
        JsonFile.edit(libFile(c)) { l -> l.removeAll { it.optString("id") == id } }
        File(videoDir(c), "$id.mp4").delete(); File(videoDir(c), "$id.jpg").delete()
    }

    /** Cópia nova (arquivo novo, sem dados antigos) só pra postar; a anterior é apagada. */
    fun freshCopyForPost(c: Context, id: String): Uri? {
        val p = Store.prefs(c)
        p.getString("ig_last_copy", null)?.let { try { c.contentResolver.delete(Uri.parse(it), null, null) } catch (e: Exception) { } }
        val u = saveToGallery(c, File(videoDir(c), "$id.mp4"), false, "post_") ?: return null
        p.edit().putString("ig_last_copy", u.toString()).apply()
        return u
    }

    fun saveToGallery(c: Context, f: File, audio: Boolean, prefix: String = ""): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cr = c.contentResolver
            val col = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, prefix + (if (audio) "audio_" else "video_") + System.currentTimeMillis() + if (audio) ".m4a" else ".mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/PostLeve")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(col, cv) ?: return null
            cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) { null }
    }

    /** Abre o Instagram com o arquivo; a legenda vai pra área de transferência pra colar. */
    /**
     * Abre o Instagram já na tela certa (Reels pra vídeo, Feed pra foto) com o arquivo.
     * Libera a leitura do arquivo pro Instagram inteiro antes, porque na primeira abertura
     * (app fechado) ele lê o arquivo depois, em outra tela, e sem isso chegava vazio.
     */
    fun shareToInstagram(c: Context, uri: Uri, mime: String): Intent = shareTo(c, uri, mime, INSTAGRAM,
        if (mime.startsWith("video")) listOf("reel", "clips") else listOf("sharehandler"))

    fun shareTo(c: Context, uri: Uri, mime: String, pkg: String, prefer: List<String>, text: String? = null): Intent {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newUri(c.contentResolver, "post", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { c.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        val acts = try { c.packageManager.queryIntentActivities(Intent(i).setPackage(pkg), 0) } catch (e: Exception) { emptyList() }
        if (acts.isEmpty()) return Intent.createChooser(i, "Postar em…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val names = acts.map { it.activityInfo.name }
        val bad = listOf("direct", "story", "stories", "thread", "message", "chat")
        val pick = prefer.firstNotNullOfOrNull { w -> names.firstOrNull { n -> n.lowercase().contains(w) && bad.none { n.lowercase().contains(it) } } }
            ?: names.firstOrNull { n -> bad.none { n.lowercase().contains(it) } } ?: names.first()
        return i.setClassName(pkg, pick)
    }
}
