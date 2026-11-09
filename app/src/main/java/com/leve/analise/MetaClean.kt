package com.leve.analise

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * 🧽 Remover metadados avulso: qualquer foto ou vídeo do celular → cópia limpa em Download/PostLeve Limpos,
 * histórico do que foi tirado e (opcional) o original vai pra lixeira ou é apagado.
 */
object MetaClean {
    const val FOLDER = "Download/PostLeve Limpos"
    private fun file(c: Context) = File(c.filesDir, "metadados.json")
    fun history(c: Context): List<JSONObject> = JsonFile.read(file(c))
    fun clearHistory(c: Context) = JsonFile.edit(file(c)) { it.clear() }

    /** "lixeira" (volta em 30 dias), "apagar" (de vez) ou "manter". */
    fun delMode(c: Context) = Store.prefs(c).getString("meta_del", "lixeira") ?: "lixeira"
    fun setDelMode(c: Context, v: String) = Store.prefs(c).edit().putString("meta_del", v).apply()

    class Res(val ok: Boolean, val kind: String, val name: String, val src: Uri, val out: Uri?, val removed: List<String>, val before: Long, val after: Long, val err: String?)

    fun info(c: Context, u: Uri): Pair<String, Long> = try {
        c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) (cur.getString(0) ?: "arquivo") to (if (cur.isNull(1)) -1L else cur.getLong(1)) else null
        } ?: ("arquivo" to -1L)
    } catch (e: Exception) { "arquivo" to -1L }

    fun isVideo(c: Context, u: Uri): Boolean {
        val t = try { c.contentResolver.getType(u) } catch (e: Exception) { null } ?: ""
        if (t.startsWith("video")) return true
        if (t.startsWith("image")) return false
        return info(c, u).first.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".3gp") || it.endsWith(".mkv") || it.endsWith(".webm") }
    }

    private fun has(b: ByteArray, s: String, max: Int = b.size): Boolean {
        val p = s.toByteArray(); val end = minOf(max, b.size) - p.size
        var i = 0
        while (i <= end) { var k = 0; while (k < p.size && b[i + k] == p[k]) k++; if (k == p.size) return true; i++ }
        return false
    }

    /** O que tinha no arquivo (pra mostrar no histórico). */
    private fun photoFound(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        try {
            val e = android.media.ExifInterface(b.inputStream())
            fun any(vararg t: String) = t.any { !e.getAttribute(it).isNullOrBlank() }
            if (any(android.media.ExifInterface.TAG_GPS_LATITUDE, android.media.ExifInterface.TAG_GPS_LONGITUDE)) out += "📍 localização"
            if (any(android.media.ExifInterface.TAG_MAKE, android.media.ExifInterface.TAG_MODEL)) out += "📱 aparelho"
            if (any(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, android.media.ExifInterface.TAG_DATETIME)) out += "🕒 data e hora"
            if (any(android.media.ExifInterface.TAG_SOFTWARE)) out += "🛠 programa"
            if (any(android.media.ExifInterface.TAG_ARTIST, android.media.ExifInterface.TAG_COPYRIGHT, android.media.ExifInterface.TAG_USER_COMMENT, android.media.ExifInterface.TAG_IMAGE_DESCRIPTION)) out += "✍ autor/descrição"
            if (any(android.media.ExifInterface.TAG_IMAGE_UNIQUE_ID)) out += "🆔 ID da foto"
            if (any(android.media.ExifInterface.TAG_EXPOSURE_TIME, android.media.ExifInterface.TAG_F_NUMBER, android.media.ExifInterface.TAG_ISO_SPEED_RATINGS)) out += "📷 dados da câmera"
        } catch (e: Exception) { }
        if (has(b, "http://ns.adobe.com/xap/1.0/", 2_000_000)) out += "🏷 XMP (edição/IA)"
        if (has(b, "c2pa", 4_000_000) || has(b, "jumb", 4_000_000)) out += "🤖 credencial de IA (C2PA)"
        return out
    }

    private fun videoFound(c: Context, u: Uri): List<String> {
        val out = ArrayList<String>()
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(c, u)
            fun g(k: Int) = r.extractMetadata(k)?.takeIf { it.isNotBlank() }
            if (g(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION) != null) out += "📍 localização"
            if (g(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.startsWith("1904") == false) out += "🕒 data e hora"
            if (listOf(android.media.MediaMetadataRetriever.METADATA_KEY_AUTHOR, android.media.MediaMetadataRetriever.METADATA_KEY_WRITER, android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM, android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER).any { g(it) != null }) out += "✍ título/autor"
        } catch (e: Exception) { } finally { try { r.release() } catch (e: Exception) { } }
        try {
            val head = c.contentResolver.openInputStream(u)?.use { s -> val b = ByteArray(1_500_000); var n = 0; while (n < b.size) { val k = s.read(b, n, b.size - n); if (k <= 0) break; n += k }; b.copyOf(n) }
            if (head != null) {
                if (has(head, "c2pa") || has(head, "jumb")) out += "🤖 credencial de IA (C2PA)"
                if (has(head, "http://ns.adobe.com/xap/1.0/")) out += "🏷 XMP (edição/IA)"
                if (has(head, "com.android.capture") || has(head, "com.apple.quicktime")) out += "📱 aparelho"
            }
        } catch (e: Exception) { }
        out += "🎞 dados do arquivo"
        return out.distinct()
    }

    /** JPEG sem perder qualidade: tira EXIF, XMP, C2PA, comentários e o que vem depois do fim da imagem. Mantém só o perfil de cor. */
    private fun stripJpeg(b: ByteArray): ByteArray? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        val o = ByteArrayOutputStream(b.size); o.write(0xFF); o.write(0xD8)
        var i = 2
        while (i + 3 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xFF) { i++; continue }
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            if (m == 0xDA) { // começo da imagem: copia até o 1º fim (FFD9) e descarta o resto
                var e = i + 2 + len
                while (e + 1 < b.size && !(b[e] == 0xFF.toByte() && b[e + 1] == 0xD9.toByte())) e++
                o.write(b, i, minOf(b.size, e + 2) - i)
                return o.toByteArray()
            }
            val icc = m == 0xE2 && len > 14 && String(b, i + 4, 11, Charsets.ISO_8859_1) == "ICC_PROFILE"
            val drop = (m in 0xE1..0xEF && !icc) || m == 0xFE
            if (!drop) o.write(b, i, 2 + len)
            i += 2 + len
        }
        return null
    }

    private fun save(c: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            try {
                c.contentResolver.openOutputStream(u)?.use(write) ?: throw Exception("sem saída")
                c.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null); u
            } catch (e: Exception) { try { c.contentResolver.delete(u, null, null) } catch (x: Exception) { }; null }
        } else {
            val d = File(c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "PostLeve Limpos").apply { mkdirs() }
            val f = File(d, name); f.outputStream().use(write); Uri.fromFile(f)
        }
    }

    private fun outName(name: String, ext: String): String {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_ -]"), "").trim().take(40).ifEmpty { "arquivo" }
        return "${base}_limpo_${System.currentTimeMillis() % 100000}.$ext"
    }

    fun cleanPhoto(c: Context, u: Uri): Res {
        val (name, size) = info(c, u)
        return try {
            val b = c.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw Exception("não abriu")
            val found = photoFound(b)
            val orient = try { android.media.ExifInterface(b.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } catch (e: Exception) { 1 }
            val png = b.size > 8 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()
            // JPEG em pé: tira os dados sem mexer na imagem (zero perda)
            val raw = if (orient <= 1) stripJpeg(b) else null
            val (bytes, ext, mime) = if (raw != null) Triple(raw, "jpg", "image/jpeg") else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var smp = 1; while (maxOf(o.outWidth, o.outHeight) / smp > 8000) smp *= 2
                var bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = smp }) ?: throw Exception("formato não suportado")
                val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
                if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
                val bo = ByteArrayOutputStream()
                if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, bo) else bmp.compress(Bitmap.CompressFormat.JPEG, 96, bo)
                Triple(bo.toByteArray(), if (png) "png" else "jpg", if (png) "image/png" else "image/jpeg")
            }
            val out = save(c, outName(name, ext), mime) { it.write(bytes) } ?: throw Exception("não consegui salvar em Download")
            Res(true, "foto", name, u, out, found.ifEmpty { listOf("nada sensível (já estava limpa)") }, size, bytes.size.toLong(), null)
        } catch (e: Exception) { Res(false, "foto", name, u, null, emptyList(), size, 0, e.message ?: "erro") }
    }

    /** Vídeo: cópia nova sem reprocessar a imagem, sem os dados do original. Chamar na thread principal. */
    fun cleanVideo(c: Context, u: Uri, onProgress: (Int) -> Unit, done: (Res) -> Unit) {
        val (name, size) = info(c, u)
        val found = try { videoFound(c, u) } catch (e: Exception) { emptyList() }
        VideoExport.cleanCopy(c, u, onProgress) { f, err ->
            Thread {
                val r = if (f == null) Res(false, "video", name, u, null, emptyList(), size, 0, err ?: "erro") else try {
                    val out = save(c, outName(name, "mp4"), "video/mp4") { o -> f.inputStream().use { it.copyTo(o) } } ?: throw Exception("não consegui salvar em Download")
                    Res(true, "video", name, u, out, found, size, f.length(), null)
                } catch (e: Exception) { Res(false, "video", name, u, null, emptyList(), size, 0, e.message) } finally { f.delete() }
                Handler(Looper.getMainLooper()).post { done(r) }
            }.start()
        }
    }

    fun record(c: Context, r: Res) = JsonFile.edit(file(c)) { l ->
        l.add(0, JSONObject().put("at", System.currentTimeMillis()).put("kind", r.kind).put("name", r.name).put("src", r.src.toString())
            .put("out", r.out?.toString() ?: "").put("ok", r.ok).put("err", r.err ?: "").put("before", r.before).put("after", r.after)
            .put("removed", JSONArray().apply { r.removed.forEach { put(it) } }))
        while (l.size > 300) l.removeAt(l.size - 1)
    }

    fun markOriginal(c: Context, srcs: Collection<String>, how: String) = JsonFile.edit(file(c)) { l -> l.filter { it.optString("src") in srcs }.forEach { it.put("orig", how) } }

    /** Endereço da galeria (MediaStore) de um arquivo escolhido, pra o Android poder apagar/mandar pra lixeira. */
    fun mediaUri(c: Context, u: Uri, video: Boolean): Uri? {
        if (u.authority == MediaStore.AUTHORITY) {
            if (u.pathSegments.contains("picker") || u.pathSegments.contains("picker_get_content")) {
                val id = u.lastPathSegment?.toLongOrNull() ?: return null
                return ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            return u
        }
        if (Build.VERSION.SDK_INT >= 29 && DocumentsContract.isDocumentUri(c, u)) return try { MediaStore.getMediaUri(c, u) } catch (e: Exception) { null }
        return null
    }

    fun mb(b: Long) = if (b < 0) "?" else String.format("%.1f MB", b / 1_048_576.0)
}

/**
 * Painel do 🧽 Remover metadados. Usado na aba "Limpar" do app e na tela que abre pelo "Compartilhar" da galeria.
 * A tela dona encaminha onActivityResult pra [onResult].
 */
class MetaPanel(private val a: Activity) {
    private val CARD = 0xFF121218.toInt(); private val LINE = 0xFF262630.toInt()
    private val FG = 0xFFECEEF3.toInt(); private val MUTED = 0xFF9A9EAB.toInt(); private val ACCENT = 0xFFFF5C82.toInt(); private val OK = 0xFF4CC68E.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView; private lateinit var bar: ProgressBar; private lateinit var hist: LinearLayout; private lateinit var tvCount: TextView
    companion object { const val REQ_PICK = 71; const val REQ_DEL = 72 }
    private var pendingDel: List<String> = emptyList()
    private var working = false

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun tv(t: String, s: Float, c: Int, p: LinearLayout, bold: Boolean = false) = TextView(a).apply {
        text = t; textSize = s; setTextColor(c); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(3), 0, dp(3)); p.addView(this)
    }
    private fun btn(t: String, p: LinearLayout, primary: Boolean = false, onClick: () -> Unit) = Button(a).apply {
        text = t; isAllCaps = false; textSize = 14f; setTextColor(if (primary) 0xFF1A0A10.toInt() else FG)
        background = GradientDrawable().apply { setColor(if (primary) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(12).toFloat(); if (!primary) setStroke(dp(1), LINE) }
        stateListAnimator = null; elevation = 0f
        setOnClickListener { onClick() }
        p.addView(this, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
    }
    private fun card(p: LinearLayout) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), LINE) }
        p.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) })
    }
    private fun row(p: LinearLayout) = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; p.addView(this, LinearLayout.LayoutParams(-1, -2)) }

    fun build(root: LinearLayout) {
        val c1 = card(root)
        tv("🧽 Remover metadados", 18f, FG, c1, true)
        tv("Cópia limpa salva sozinha em Download/PostLeve Limpos. Sai: localização, aparelho, data, programa, XMP e credencial de IA (C2PA). JPEG sem perder qualidade; vídeo sem reprocessar.", 12.5f, MUTED, c1)
        val r1 = row(c1)
        btn("🖼 Fotos", r1, true) { pick("image/*") }
        btn("🎬 Vídeos", r1, true) { pick("video/*") }
        btn("📁 Arquivos (fotos e vídeos)", row(c1)) { pickFiles() }
        tvStatus = tv("", 13f, OK, c1)
        bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        c1.addView(bar, LinearLayout.LayoutParams(-1, dp(8)))

        val c3 = card(root)
        tv("Depois de limpar, o original:", 14f, FG, c3, true)
        val r2 = row(c3)
        val opts = listOf("lixeira" to "🗑 Lixeira", "apagar" to "❌ Apagar", "manter" to "📌 Manter")
        val bs = ArrayList<Button>()
        fun paint() { bs.forEachIndexed { i, b -> val on = MetaClean.delMode(a) == opts[i].first
            b.setTextColor(if (on) 0xFF06140D.toInt() else FG); b.background = GradientDrawable().apply { setColor(if (on) OK else 0xFF1C1C24.toInt()); cornerRadius = dp(12).toFloat(); if (!on) setStroke(dp(1), LINE) } } }
        opts.forEach { (k, l) -> bs += btn(l, r2) { MetaClean.setDelMode(a, k); paint() } }
        paint()
        tv("Lixeira = volta em até 30 dias. O Android sempre pede confirmação antes de mexer no original.\nDica: na galeria, Compartilhar → 🧽 Limpar metadados.", 11.5f, MUTED, c3)

        val c2 = card(root)
        val hr = row(c2)
        tvCount = TextView(a).apply { textSize = 16f; setTextColor(FG); setTypeface(typeface, android.graphics.Typeface.BOLD); hr.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) }
        TextView(a).apply { text = "Limpar"; textSize = 13f; setTextColor(MUTED); setPadding(dp(8), dp(4), dp(8), dp(4)); hr.addView(this)
            setOnClickListener { AlertDialog.Builder(a).setMessage("Limpar o histórico? (os arquivos limpos continuam em Download)").setPositiveButton("Limpar") { _, _ -> MetaClean.clearHistory(a); renderHist() }.setNegativeButton("Cancelar", null).show() } }
        hist = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; c2.addView(hist)
        renderHist()
    }

    fun handleShare(i: Intent?) {
        val uris = ArrayList<Uri>()
        when (i?.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { uris += it }
        }
        if (uris.isNotEmpty()) { i?.action = null; process(uris) }
    }

    private fun pick(mime: String) {
        if (working) { toast("Espere terminar"); return }
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = mime; putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(50, MediaStore.getPickImagesMaxLimit())) }
                else Intent(Intent.ACTION_PICK, if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = mime; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        try { @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_PICK) } catch (e: Exception) { pickFiles() }
    }

    private fun pickFiles() {
        if (working) { toast("Espere terminar"); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        try { @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_PICK) } catch (e: Exception) { toast("Não abriu o seletor") }
    }

    /** true se o resultado era deste painel. */
    fun onResult(req: Int, res: Int, data: Intent?): Boolean {
        if (req == REQ_DEL) {
            if (res == Activity.RESULT_OK) { MetaClean.markOriginal(a, pendingDel, MetaClean.delMode(a)); toast(if (MetaClean.delMode(a) == "apagar") "Originais apagados" else "Originais na lixeira") }
            else toast("Originais mantidos")
            pendingDel = emptyList(); renderHist(); return true
        }
        if (req != REQ_PICK) return false
        if (res != Activity.RESULT_OK || data == null) return true
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        uris.forEach { try { a.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { } }
        if (uris.isNotEmpty()) process(uris)
        return true
    }

    /** Limpa um por um (fotos numa thread, vídeos pelo editor) e no fim cuida dos originais. */
    fun process(uris: List<Uri>) {
        if (working) { toast("Espere terminar"); return }
        working = true; a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val done = ArrayList<MetaClean.Res>()
        fun finishAll() {
            working = false; a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); bar.visibility = View.GONE
            val ok = done.filter { it.ok }; val bad = done.size - ok.size
            tvStatus.setTextColor(if (bad == 0) OK else 0xFFF5A524.toInt())
            tvStatus.text = "✓ ${ok.size} de ${done.size} limpo(s) e salvos em Download/PostLeve Limpos" + (if (bad > 0) "\n⚠ $bad não deu: " + done.filter { !it.ok }.joinToString("; ") { "${it.name}: ${it.err}" } else "")
            renderHist()
            if (ok.isNotEmpty() && MetaClean.delMode(a) != "manter") deleteOriginals(ok)
        }
        fun go(k: Int) {
            if (k >= uris.size) { finishAll(); return }
            val u = uris[k]
            tvStatus.setTextColor(FG); tvStatus.text = "Limpando ${k + 1} de ${uris.size}…"; bar.visibility = View.VISIBLE; bar.progress = (k * 100) / uris.size
            if (MetaClean.isVideo(a, u)) {
                fun start() {
                    if (VideoExport.busy() || AutoProducer.running) { tvStatus.text = "Esperando o editor terminar o que está fazendo…"; ui.postDelayed({ start() }, 3000); return }
                    MetaClean.cleanVideo(a, u, { p -> bar.progress = (k * 100 + p) / uris.size }) { r -> MetaClean.record(a, r); done += r; go(k + 1) }
                }
                start()
            } else Thread { val r = MetaClean.cleanPhoto(a, u); MetaClean.record(a, r); ui.post { done += r; go(k + 1) } }.start()
        }
        go(0)
    }

    private fun deleteOriginals(ok: List<MetaClean.Res>) {
        val media = ArrayList<Uri>(); val docs = ArrayList<Uri>()
        ok.forEach { r -> val m = MetaClean.mediaUri(a, r.src, r.kind == "video"); if (m != null) media += m else if (DocumentsContract.isDocumentUri(a, r.src)) docs += r.src }
        pendingDel = ok.map { it.src.toString() }
        if (media.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = if (MetaClean.delMode(a) == "apagar") MediaStore.createDeleteRequest(a.contentResolver, media) else MediaStore.createTrashRequest(a.contentResolver, media, true)
                @Suppress("DEPRECATION") a.startIntentSenderForResult(pi.intentSender, REQ_DEL, null, 0, 0, 0)
            } catch (e: Exception) { toast("Não consegui pedir pra apagar: ${e.message}") }
        }
        if (docs.isNotEmpty()) AlertDialog.Builder(a).setTitle("Apagar ${docs.size} original(is) de vez?")
            .setMessage("Esses vieram de Arquivos e não têm lixeira: somem pra sempre. As cópias limpas já estão em Download/PostLeve Limpos.")
            .setPositiveButton("Apagar") { _, _ ->
                var n = 0; docs.forEach { try { if (DocumentsContract.deleteDocument(a.contentResolver, it)) n++ } catch (e: Exception) { } }
                MetaClean.markOriginal(a, docs.map { it.toString() }, "apagar"); toast("$n original(is) apagado(s)"); renderHist()
            }.setNegativeButton("Manter", null).show()
        if (media.isEmpty() && docs.isEmpty()) toast("Não deu pra apagar o original por aqui: apague pela galeria")
    }

    fun renderHist() {
        if (!::hist.isInitialized) return
        hist.removeAllViews()
        val l = MetaClean.history(a)
        tvCount.text = "🕘 Histórico" + if (l.isNotEmpty()) " · ${l.count { it.optBoolean("ok") }} limpo(s)" else ""
        if (l.isEmpty()) { tv("Nada ainda. O que você limpar aparece aqui.", 12f, MUTED, hist); return }
        l.take(60).forEach { o ->
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            hist.addView(box)
            val ico = if (o.optString("kind") == "video") "🎬" else "📸"
            val dt = android.text.format.DateFormat.format("dd/MM HH:mm", o.optLong("at"))
            tv("$ico ${o.optString("name").take(38)}  ·  $dt", 13f, if (o.optBoolean("ok")) FG else 0xFFF5A524.toInt(), box, true)
            if (!o.optBoolean("ok")) { tv("⚠ " + o.optString("err"), 12f, MUTED, box); return@forEach }
            val rm = o.optJSONArray("removed")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
            tv("Tirado: " + rm.joinToString(", ") + "\n" + MetaClean.mb(o.optLong("before")) + " → " + MetaClean.mb(o.optLong("after")) +
                when (o.optString("orig")) { "lixeira" -> "  ·  🗑 original na lixeira"; "apagar" -> "  ·  ❌ original apagado"; else -> "  ·  📌 original mantido" }, 12f, MUTED, box)
            val out = o.optString("out").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) } ?: return@forEach
            val mime = if (o.optString("kind") == "video") "video/mp4" else "image/*"
            val r = row(box)
            btn("👁 Abrir", r) { try { a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(out, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { toast("O arquivo não existe mais") } }
            btn("↗ Enviar", r) { try { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, out).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Enviar")) } catch (e: Exception) { toast("Não deu pra enviar") } }
            btn("🗑 Cópia", r) {
                AlertDialog.Builder(a).setMessage("Apagar a cópia limpa de Download? Não dá pra desfazer.").setPositiveButton("Apagar") { _, _ ->
                    try { a.contentResolver.delete(out, null, null) } catch (e: Exception) { }
                    JsonFile.edit(File(a.filesDir, "metadados.json")) { ll -> ll.firstOrNull { it.optLong("at") == o.optLong("at") }?.put("out", "") }; renderHist()
                }.setNegativeButton("Cancelar", null).show()
            }
            box.addView(View(a).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6) })
        }
    }

    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()
}

/** Tela que abre pelo "Compartilhar → 🧽 Limpar metadados" da galeria. */
class MetaActivity : Activity() {
    private lateinit var panel: MetaPanel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt(), (24 * d).toInt()) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(root) })
        panel = MetaPanel(this); panel.build(root); panel.handleShare(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); panel.handleShare(intent) }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) { @Suppress("DEPRECATION") super.onActivityResult(req, res, data); panel.onResult(req, res, data) }
}
