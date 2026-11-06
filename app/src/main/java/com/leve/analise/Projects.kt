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

    fun list(c: Context): MutableList<JSONObject> = synchronized(lock) {
        try { val a = JSONArray(listFile(c).readText()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
        catch (e: Exception) { mutableListOf() }
    }

    private fun save(c: Context, l: List<JSONObject>) = synchronized(lock) {
        val a = JSONArray(); l.sortedByDescending { it.optLong("updated") }.forEach { a.put(it) }
        listFile(c).writeText(a.toString())
    }

    fun create(c: Context, photo: Boolean): String = synchronized(lock) {
        val id = "p" + System.currentTimeMillis().toString(36)
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
