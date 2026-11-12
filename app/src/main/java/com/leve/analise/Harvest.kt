package com.leve.analise

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import org.json.JSONArray

/**
 * 🏆 Post feito FORA do app que bombou: o leitor salva a legenda no banco (já com voto bom) e a foto na fila.
 * A foto: primeiro procura o ORIGINAL na sua galeria (mesma imagem, qualidade cheia); se não achar, usa o recorte da tela.
 */
object Harvest {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("harvest_on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("harvest_on", v).apply()
    private fun key(cap: String) = cap.trim().lowercase().replace(Regex("\\s+"), " ").take(80).hashCode().toString()
    private fun saved(c: Context) = p(c).getStringSet("harvest_done", emptySet()) ?: emptySet()
    fun total(c: Context) = p(c).getInt("harvest_n", 0)

    /** Bombou? (pontuação alta entre os seus posts, ou 2x a sua mediana de curtidas). */
    fun winner(c: Context, cap: String): Int {
        val o = ThreadsApi.orphanOf(c, cap) ?: return 0
        val all = ThreadsApi.posts(c)
        val likes = all.filter { it.optString("kind", "fila") != "video" && it.has("likes") }.map { it.optInt("likes") }.sorted()
        val med = likes.getOrNull(likes.size / 2) ?: 0
        val s = if (all.size >= 5) ThreadsApi.composite(c, o, all) else 0.5
        val lk = o.optInt("likes")
        return when {
            s >= 0.9 || (med > 0 && lk >= med * 4 && lk >= 50) -> 2
            s >= 0.78 || (med > 0 && lk >= med * 2 && lk >= 30) -> 1
            else -> 0
        }
    }
    fun shouldSave(c: Context, cap: String) = on(c) && cap.trim().length >= 8 && key(cap) !in saved(c) && winner(c, cap) > 0

    /** A legenda vai pro banco com voto bom (💎 se bombou muito). */
    fun saveCaption(c: Context, cap: String) {
        if (CaptionDB.find(c, cap) == null) CaptionDB.add(c, cap, "fora")
        CaptionDB.rate(c, cap, "good"); if (winner(c, cap) >= 2) CaptionDB.rate(c, cap, "good")
    }

    fun done(c: Context, cap: String) = p(c).edit().putStringSet("harvest_done", (saved(c) + key(cap)).toList().takeLast(300).toSet()).putInt("harvest_n", total(c) + 1).apply()

    /** Procura o original na galeria (últimos 120 dias) pela "impressão digital" da imagem. */
    private fun fromGallery(c: Context, crop: Bitmap): Bitmap? {
        if (!FolderScan.hasPerm(c) || android.os.Build.VERSION.SDK_INT < 29) return null
        val target = Scenes.hash(crop)
        val since = System.currentTimeMillis() / 1000 - 120 * 86400L
        c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH),
            "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf(since.toString()), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
            var n = 0
            while (cur.moveToNext() && n < 600) {
                n++
                if (Regex("screenshot|captura", RegexOption.IGNORE_CASE).containsMatchIn(cur.getString(1).orEmpty())) continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0))
                val th = try { c.contentResolver.loadThumbnail(uri, android.util.Size(120, 120), null) } catch (e: Exception) { null } ?: continue
                if (Scenes.same(Scenes.hash(th), target)) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 2160) smp *= 2
                    return c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp }) }
                }
            }
        }
        return null
    }

    /** Já tem essa foto na fila? (não duplica) */
    private fun inQueue(c: Context, crop: Bitmap): Boolean {
        val t = Scenes.hash(crop)
        return PostQueue.load(c).take(400).any { o ->
            val b = try { BitmapFactory.decodeFile(PostQueue.imageFile(c, o.optString("id")).path, BitmapFactory.Options().apply { inSampleSize = 16 }) } catch (e: Exception) { null }
            b != null && Scenes.same(Scenes.hash(b), t)
        }
    }

    /** Salva a foto na fila (já com a legenda e o voto bom). Devolve o texto do resultado. */
    fun savePhoto(c: Context, cap: String, crop: Bitmap?): String {
        if (crop == null) return "legenda salva (a foto não deu pra pegar: abra o post e toque no 📖 de novo)"
        if (inQueue(c, crop)) return "legenda salva (a foto já estava na fila)"
        val orig = try { fromGallery(c, crop) } catch (e: Exception) { null }
        val id = PostQueue.add(c, orig ?: crop)
        PostQueue.update(c, id) { o ->
            o.put("legendas", JSONArray().put(cap)).put("escolhida", cap).put("status", "postada").put("postedAt", System.currentTimeMillis()).put("fora", true).put("ia", false)
        }
        RateLater.applyPhoto(c, id, if (winner(c, cap) >= 2) "top" else "good", cap)
        return if (orig != null) "foto ORIGINAL achada na galeria e salva na fila" else "foto salva na fila (recorte da tela${if (crop.width < 500) ", qualidade baixa" else ""})"
    }
}
