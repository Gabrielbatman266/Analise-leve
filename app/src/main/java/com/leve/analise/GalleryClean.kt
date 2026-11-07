package com.leve.analise

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tudo que o app põe na galeria é temporário: cada cópia tem prazo e é apagada sozinha.
 * Assim o celular não fica cheio de fotos e vídeos repetidos — o original fica só dentro do app.
 */
object GalleryClean {
    private const val KEY = "gal_tmp"

    private fun list(c: Context): MutableList<JSONObject> = try {
        val a = JSONArray(Store.prefs(c).getString(KEY, "[]")); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }
    private fun save(c: Context, l: List<JSONObject>) { val a = JSONArray(); l.forEach { a.put(it) }; Store.prefs(c).edit().putString(KEY, a.toString()).apply() }

    /** Prazo das fotos do "Mandar pro Flow" (minutos). */
    fun flowTtl(c: Context) = Store.prefs(c).getInt("flow_ttl", 30)

    /** Marca uma cópia pra ser apagada depois de [ms]. */
    fun track(c: Context, uri: Uri, ms: Long) {
        val l = list(c); l.removeAll { it.optString("u") == uri.toString() }
        l.add(JSONObject().put("u", uri.toString()).put("until", System.currentTimeMillis() + ms))
        save(c, l); schedule(c)
    }

    /** Apaga o que venceu (ou tudo, com all=true). Devolve quantas foram apagadas. */
    fun sweep(c: Context, all: Boolean = false): Int {
        val now = System.currentTimeMillis()
        val l = list(c); var n = 0
        val keep = l.filter { o ->
            if (all || o.optLong("until") <= now) { try { n += c.contentResolver.delete(Uri.parse(o.optString("u")), null, null) } catch (e: Exception) { }; false } else true
        }
        save(c, keep); schedule(c)
        return n
    }

    private fun schedule(c: Context) {
        val next = list(c).minOfOrNull { it.optLong("until") } ?: return
        val pi = PendingIntent.getBroadcast(c, 61, Intent(c, CleanReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { c.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, maxOf(next, System.currentTimeMillis() + 60_000), pi) } catch (e: Exception) { }
    }

    /** Apaga todas as cópias que o Post Leve já deixou na galeria (pastas PostLeve…). Só mexe em arquivos criados pelo app. */
    fun purgeAll(c: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var n = sweep(c, true)
        val cols = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        cols.forEach { col ->
            try {
                n += c.contentResolver.delete(col,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?",
                    arrayOf("%/PostLeve%", c.packageName))
            } catch (e: Exception) { }
        }
        Store.prefs(c).edit().remove("ref_gallery").remove("gallery_uri").remove("ig_last_copy").apply()
        return n
    }
}

class CleanReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { GalleryClean.sweep(c) }
}
