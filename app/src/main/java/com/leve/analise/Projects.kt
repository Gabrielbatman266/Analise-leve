package com.leve.analise

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Projetos do editor: o arquivo original + texto, estilo, música e legenda, pra editar e exportar de novo. */
object Projects {
    private val lock = Any()
    private fun root(c: Context) = File(c.filesDir, "projetos").apply { mkdirs() }
    private fun listFile(c: Context) = File(c.filesDir, "projetos.json")
    fun dir(c: Context, id: String) = File(root(c), id).apply { mkdirs() }
    fun srcFile(c: Context, id: String, photo: Boolean) = File(dir(c, id), if (photo) "src.jpg" else "src.mp4")
    fun thumb(c: Context, id: String) = File(dir(c, id), "thumb.jpg")

    fun list(c: Context): MutableList<JSONObject> = synchronized(lock) { JsonFile.read(listFile(c)) }

    private fun save(c: Context, l: List<JSONObject>) = synchronized(lock) {
        JsonFile.write(listFile(c), l.sortedByDescending { it.optLong("updated") })
    }

    /** Volta pra lista vídeos crus cuja pasta existe mas sumiram. */
    fun restore(c: Context): Int = synchronized(lock) {
        val l = list(c); val have = l.map { it.optString("id") }.toSet(); var n = 0
        root(c).listFiles()?.filter { it.isDirectory && it.name !in have }?.forEach { d ->
            val photo = File(d, "src.jpg").exists()
            if (!photo && !File(d, "src.mp4").exists()) return@forEach
            l.add(JSONObject().put("id", d.name).put("photo", photo).put("created", d.lastModified()).put("updated", d.lastModified()).put("recuperado", true)); n++
        }
        if (n > 0) save(c, l); n
    }

    fun create(c: Context, photo: Boolean): String = synchronized(lock) {
        var id = "p" + System.currentTimeMillis().toString(36)
        while (File(root(c), id).exists()) id += (0..9).random()
        val l = list(c)
        l.add(JSONObject().put("id", id).put("photo", photo).put("created", System.currentTimeMillis()).put("updated", System.currentTimeMillis()))
        save(c, l); id
    }

    fun get(c: Context, id: String) = list(c).firstOrNull { it.optString("id") == id }

    fun update(c: Context, id: String, f: (JSONObject) -> Unit) = synchronized(lock) {
        val l = list(c); l.firstOrNull { it.optString("id") == id }?.let(f); save(c, l)
    }

    fun remove(c: Context, id: String) = synchronized(lock) {
        save(c, list(c).filter { it.optString("id") != id })
        dir(c, id).deleteRecursively()
    }

    fun saveThumb(c: Context, id: String, b: Bitmap) {
        try {
            val t = Bitmap.createScaledBitmap(b, 162, 288, true)
            thumb(c, id).outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) { }
    }
}
