package com.leve.analise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * 🕘 Histórico de toda vez que você tocou em "Postar" (foto ou vídeo, por qualquer caminho).
 * Mostra se confirmou e a nota; dá pra confirmar e votar dali, mesmo dias depois.
 */
object SendLog {
    private fun file(c: Context) = File(c.filesDir, "envios.json")

    fun all(c: Context): List<JSONObject> = JsonFile.peek(file(c)) // só consulta (em cache); pra mudar: JsonFile.edit

    /** kind: "fila" (foto → Threads), "video" (→ Instagram), "fotoIg" (foto → Instagram) */
    fun add(c: Context, kind: String, id: String, cap: String, app: String, manual: Boolean = false) {
        JsonFile.edit(file(c)) { l ->
            val e = JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("id", id).put("cap", cap).put("app", app)
                .put("ok", manual).put("manual", manual)
            try { Burst.stamp(c, e, l.firstOrNull { it.optString("kind") == "fila" && !it.optBoolean("manual") }) } catch (x: Exception) { }
            l.add(0, e)
            while (l.size > 300) l.removeAt(l.size - 1)
        }
    }

    private fun latest(l: MutableList<JSONObject>, kind: String, id: String) = l.firstOrNull { it.optString("kind") == kind && it.optString("id") == id }

    fun confirm(c: Context, kind: String, id: String) = JsonFile.edit(file(c)) { l -> latest(l, kind, id)?.put("ok", true) }

    /** Grava a nota no envio (o mais recente desse item, ou o envio exato quando at > 0). */
    fun noted(c: Context, kind: String, id: String, k: String, at: Long = 0) = JsonFile.edit(file(c)) { l ->
        (if (at > 0) l.firstOrNull { it.optLong("at") == at } else latest(l, kind, id))?.apply { put("nota", k); put("ok", true) }
    }

    fun mark(c: Context, at: Long, flag: String) = JsonFile.edit(file(c)) { l -> l.firstOrNull { it.optLong("at") == at }?.put(flag, true) }

    /** Vota num envio do histórico: confirma o post e aplica a nota na foto/vídeo e na legenda usada. */
    fun rate(c: Context, e: JSONObject, k: String, manual: Boolean = false) {
        val id = e.optString("id"); val cap = e.optString("cap")
        when (e.optString("kind")) {
            "video" -> {
                // nunca confirmou: conta como postado na hora do envio
                if (VideoExport.library(c).firstOrNull { it.optString("id") == id }?.optLong("lastPost", 0L)?.let { it < e.optLong("at") - 60_000L } == true &&
                    PostConfirm.list(c).none { it.optString("kind") == "video" && it.optString("id") == id })
                    VideoExport.updateLib(c, id) { it.put("posts", it.optInt("posts") + 1); it.put("lastPost", e.optLong("at")) }
                RateLater.apply(c, id, k)
            }
            else -> RateLater.applyPhoto(c, id, k, cap)
        }
        noted(c, e.optString("kind"), id, k, e.optLong("at"))
        if (manual) mark(c, e.optLong("at"), "mn")
    }

    fun thumb(c: Context, kind: String, id: String): Bitmap? = try {
        if (kind == "video") File(VideoExport.videoDir(c), "$id.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        else PostQueue.imageFile(c, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 6 }) }
    } catch (e: Exception) { null }

    /** Envios sem confirmar nem nota (pra lembrar). */
    fun pending(c: Context) = all(c).filter { !it.optBoolean("ok") && it.optString("nota").isEmpty() && System.currentTimeMillis() - it.optLong("at") < 7 * 86400_000L }
}
