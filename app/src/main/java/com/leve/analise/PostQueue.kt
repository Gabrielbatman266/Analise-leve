package com.leve.analise

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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

    fun load(c: Context): MutableList<JSONObject> = synchronized(lock) {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun save(c: Context, list: List<JSONObject>) = synchronized(lock) {
        val a = JSONArray(); list.forEach { a.put(it) }
        file(c).writeText(a.toString())
    }

    /** Salva a foto limpa (JPEG novo, sem EXIF/GPS) e coloca na fila esperando legendas. */
    fun add(c: Context, src: Bitmap): String {
        val id = "f" + System.currentTimeMillis().toString(36) + (100..999).random()
        val max = 1440
        val bmp = if (src.width > max) Bitmap.createScaledBitmap(src, max, src.height * max / src.width, true) else src
        FileOutputStream(imageFile(c, id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        synchronized(lock) {
            val l = load(c)
            l.add(JSONObject().put("id", id).put("status", "gerando").put("legendas", JSONArray())
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
        save(c, load(c).filter { it.optString("id") != id })
        imageFile(c, id).delete()
    }

    fun get(c: Context, id: String) = load(c).firstOrNull { it.optString("id") == id }
    fun next(c: Context) = load(c).firstOrNull { it.optString("status") == "pronta" }
    fun pendingCaptions(c: Context) = load(c).filter { it.optString("status") == "gerando" }.map { it.optString("id") }

    fun captionOf(o: JSONObject): String {
        val e = o.optString("escolhida")
        if (e.isNotEmpty()) return e
        return o.optJSONArray("legendas")?.optString(0).orEmpty()
    }

    fun uri(id: String): Uri = Uri.parse("content://$AUTH/$id.jpg")

    /** Foto + legenda juntas. Vai direto pro Threads quando ele está instalado. */
    fun shareIntent(c: Context, id: String, caption: String): Intent {
        val u = uri(id)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, u)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newUri(c.contentResolver, "foto", u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val direct = Intent(i).setPackage(THREADS)
        return if (direct.resolveActivity(c.packageManager) != null) direct
               else Intent.createChooser(i, "Postar em…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
