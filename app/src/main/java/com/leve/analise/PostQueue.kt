package com.leve.analise

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Fila de fotos prontas pra postar. Cada foto é redesenhada do zero (sai sem metadados),
 * ganha legendas da IA e fica esperando o "Postar".
 */
object PostQueue {
    private val lock = Any()
    const val AUTH = "com.leve.analise.img"
    const val THREADS = "com.instagram.barcelona"

    fun dir(c: Context) = File(c.filesDir, "posts").apply { mkdirs() }
    private fun file(c: Context) = File(c.filesDir, "posts_queue.json")
    fun imageFile(c: Context, id: String) = File(dir(c), "$id.jpg")
    fun stamp(c: Context): Long = file(c).lastModified()

    fun load(c: Context): MutableList<JSONObject> = synchronized(lock) { JsonFile.read(file(c)) }

    private fun save(c: Context, list: List<JSONObject>) {
        synchronized(lock) { JsonFile.write(file(c), list) }
        QuickPost.updateWidgets(c)
    }

    /** Volta pra fila fotos cujo arquivo existe mas sumiram da lista. */
    fun restore(c: Context, lost: List<Pair<String, Long>>): Int = synchronized(lock) {
        val l = load(c); val have = l.map { it.optString("id") }.toSet()
        var n = 0
        lost.filter { it.first !in have }.forEach { (id, at) ->
            l.add(JSONObject().put("id", id).put("status", "pronta").put("legendas", JSONArray(Captions.randomCaptions(c, 5)))
                .put("ia", false).put("at", at).put("usos", 0).put("recuperada", true)); n++
        }
        if (n > 0) save(c, l); n
    }

    /** Salva a foto limpa (JPEG novo, sem EXIF/GPS) e coloca na fila esperando legendas. */
    fun add(c: Context, src: Bitmap): String {
        val id = "f" + System.currentTimeMillis().toString(36) + (100..999).random()
        val max = 2560 // mantém a qualidade 2K do upscale
        val big = maxOf(src.width, src.height)
        val bmp = if (big > max) Bitmap.createScaledBitmap(src, src.width * max / big, src.height * max / big, true) else src
        FileOutputStream(imageFile(c, id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        synchronized(lock) {
            val l = load(c)
            // sem IA automática: já entra pronta com legendas do seu banco (não gasta crédito)
            val auto = Captions.aiAuto(c)
            l.add(JSONObject().put("id", id).put("status", if (auto) "gerando" else "pronta")
                .put("legendas", if (auto) JSONArray() else JSONArray(Captions.randomCaptions(c, 5))).put("ia", false)
                .put("at", System.currentTimeMillis()).put("usos", 0))
            save(c, l)
        }
        return id
    }

    fun update(c: Context, id: String, f: (JSONObject) -> Unit) = synchronized(lock) {
        val l = load(c)
        l.firstOrNull { it.optString("id") == id }?.let(f)
        save(c, l)
    }

    /** Move pro fim da fila (usado no "Usar de novo"). */
    fun requeue(c: Context, id: String) = synchronized(lock) {
        val l = load(c)
        val o = l.firstOrNull { it.optString("id") == id } ?: return@synchronized
        l.remove(o)
        o.put("status", "pronta"); o.remove("escolhida")
        l.add(o); save(c, l)
    }

    fun remove(c: Context, id: String) = synchronized(lock) {
        val o = load(c).firstOrNull { it.optString("id") == id }
        o?.optString("pasta")?.takeIf { it.isNotEmpty() }?.let { try { c.contentResolver.delete(Uri.parse(it), null, null) } catch (e: Exception) { } }
        save(c, load(c).filter { it.optString("id") != id })
        imageFile(c, id).delete()
    }

    /** Cópia visível na galeria, numa pasta só das fotos do Flow (Pictures/PostLeve Flow). */
    fun copyToFolder(c: Context, id: String) {
        if (Build.VERSION.SDK_INT < 29 || !Store.prefs(c).getBoolean("flow_copy", false)) return
        try {
            val cr = c.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "flow_$id.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PostLeve Flow")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            cr.openOutputStream(uri)?.use { out -> imageFile(c, id).inputStream().use { it.copyTo(out) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            update(c, id) { it.put("pasta", uri.toString()) }
        } catch (e: Exception) { }
    }

    fun get(c: Context, id: String) = load(c).firstOrNull { it.optString("id") == id }
    fun next(c: Context) = load(c).firstOrNull { it.optString("status") == "pronta" }
    /** Fotos esperando a IA: todas (modo automático) ou só as que você pediu. */
    fun pendingCaptions(c: Context) = load(c).filter { it.optString("status") == "gerando" && (Captions.aiAuto(c) || it.optBoolean("pedido")) }.map { it.optString("id") }

    fun captionOf(o: JSONObject): String {
        val e = o.optString("escolhida")
        if (e.isNotEmpty()) return e
        return o.optJSONArray("legendas")?.optString(0).orEmpty()
    }

    fun uri(id: String): Uri = Uri.parse("content://$AUTH/$id.jpg")

    /** Foto + legenda juntas. Vai direto pro Threads quando ele está instalado. */
    fun shareIntent(c: Context, id: String, caption: String): Intent {
        Captions.remember(c, id, caption) // o que sai agora não volta logo em seguida
        update(c, id) { it.put("sentAt", System.currentTimeMillis()); if (caption.isNotBlank()) it.put("escolhida", caption) }
        RateLater.schedulePhoto(c, id) // todo post pergunta como foi
        if (Reminder.every(c) > 0 && Burst.size(c) > 1) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Reminder.schedule(c) }, 1500)
        return VideoExport.shareTo(c, uri(id), "image/jpeg", THREADS, listOf("share", "compose", "post"), caption)
    }

    // ---------- galeria: só uma cópia, sempre a mais recente ----------
    fun galleryOn(c: Context) = Store.prefs(c).getBoolean("gallery_first", true)

    /** Coloca a foto em primeiro na galeria (pasta Pictures/PostLeve). Apaga a cópia anterior antes. */
    fun putInGallery(c: Context, id: String, force: Boolean = false): Uri? {
        cleanGallery(c, true)
        if (Build.VERSION.SDK_INT < 29 || (!galleryOn(c) && !force)) return null
        return try {
            val cr = c.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "post_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PostLeve")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis()) // mais nova de todas: 1ª na galeria do Threads
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            cr.openOutputStream(uri)?.use { out -> imageFile(c, id).inputStream().use { it.copyTo(out) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            Store.prefs(c).edit().putString("gallery_uri", uri.toString()).putLong("gallery_at", System.currentTimeMillis()).apply()
            GalleryClean.track(c, uri, 15 * 60_000L)
            uri
        } catch (e: Exception) { null }
    }

    /** Apaga a cópia da galeria feita pelo app (depois de 10 min, ou já se force). */
    fun cleanGallery(c: Context, force: Boolean) {
        val p = Store.prefs(c)
        val u = p.getString("gallery_uri", null) ?: return
        if (!force && System.currentTimeMillis() - p.getLong("gallery_at", 0L) < 10 * 60_000L) return
        try { c.contentResolver.delete(Uri.parse(u), null, null) } catch (e: Exception) { }
        p.edit().remove("gallery_uri").remove("gallery_at").apply()
    }

    /** Legenda escrita na mão: entra no topo da lista e já fica escolhida. */
    fun setManual(c: Context, id: String, text: String) = update(c, id) { o ->
        val old = o.optJSONArray("legendas") ?: JSONArray()
        val n = JSONArray().put(text)
        for (i in 0 until old.length()) if (old.optString(i) != text) n.put(old.optString(i))
        o.put("legendas", n); o.put("escolhida", text)
        if (o.optString("status") == "gerando") o.put("status", "pronta")
    }
}
