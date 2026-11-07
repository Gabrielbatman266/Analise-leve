package com.leve.analise

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Referências: links de vídeos (com prévia) e estoque de fotos (sem metadados). Tudo só no celular. */
object Refs {
    const val FLOW_URL = "https://labs.google/fx/tools/flow"
    private val lock = Any()

    // ------------------------------------------------------------------ links

    private fun linksFile(c: Context) = File(c.filesDir, "refs_links.json")
    fun thumbDir(c: Context) = File(c.filesDir, "refs_thumbs").apply { mkdirs() }

    fun links(c: Context): MutableList<JSONObject> = synchronized(lock) {
        try { val a = JSONArray(linksFile(c).readText()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
        catch (e: Exception) { mutableListOf() }
    }
    private fun saveLinks(c: Context, l: List<JSONObject>) = synchronized(lock) {
        val a = JSONArray(); l.forEach { a.put(it) }; linksFile(c).writeText(a.toString())
    }
    fun updateLink(c: Context, id: String, f: (JSONObject) -> Unit) = synchronized(lock) {
        val l = links(c); l.firstOrNull { it.optString("id") == id }?.let(f); saveLinks(c, l)
    }
    fun removeLink(c: Context, id: String) = synchronized(lock) {
        saveLinks(c, links(c).filter { it.optString("id") != id }); File(thumbDir(c), "$id.jpg").delete()
    }

    /** Acha o primeiro link num texto (o Instagram manda "Veja este reel… https://…"). */
    fun findUrl(t: String): String? = Regex("https?://[^\\s]+").find(t)?.value?.trimEnd('.', ',', ')', '"')

    fun site(url: String): String {
        val h = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return when {
            "instagram" in h -> "Instagram"; "tiktok" in h -> "TikTok"; "youtu" in h -> "YouTube"
            "pinterest" in h || "pin.it" in h -> "Pinterest"; "threads" in h -> "Threads"; "facebook" in h || "fb.watch" in h -> "Facebook"
            else -> h.removePrefix("www.").ifEmpty { "Link" }
        }
    }
    fun siteIcon(s: String) = when (s) { "Instagram" -> "📸"; "TikTok" -> "🎵"; "YouTube" -> "▶"; "Pinterest" -> "📌"; "Threads" -> "@"; "Facebook" -> "f"; else -> "🔗" }

    /** Salva o link (sem repetir) e devolve o id. A prévia é buscada depois. */
    fun addLink(c: Context, url: String, tags: List<String> = emptyList(), note: String = ""): String = synchronized(lock) {
        val l = links(c)
        l.firstOrNull { it.optString("url") == url }?.let { o -> l.remove(o); l.add(0, o); saveLinks(c, l); return o.optString("id") }
        val id = "l" + System.currentTimeMillis().toString(36)
        l.add(0, JSONObject().put("id", id).put("url", url).put("site", site(url)).put("at", System.currentTimeMillis())
            .put("tags", JSONArray(tags)).put("note", note).put("title", "").put("usado", false))
        saveLinks(c, l); id
    }

    /** Busca título e miniatura (oEmbed do YouTube/TikTok, ou a prévia que o site mostra quando o link é compartilhado). */
    fun fetchPreview(c: Context, id: String) {
        val o = links(c).firstOrNull { it.optString("id") == id } ?: return
        val url = o.optString("url")
        var title = ""; var author = ""; var thumb = ""
        try {
            val yt = Regex("(?:youtu\\.be/|v=|shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
            when {
                yt != null -> {
                    thumb = "https://i.ytimg.com/vi/$yt/hqdefault.jpg"
                    get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"))?.let { j ->
                        val js = JSONObject(j); title = js.optString("title"); author = js.optString("author_name") }
                }
                "tiktok" in url -> get("https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"))?.let { j ->
                    val js = JSONObject(j); title = js.optString("title"); author = js.optString("author_name"); thumb = js.optString("thumbnail_url") }
            }
            if (thumb.isEmpty() || title.isEmpty()) get(url, true)?.let { html ->
                fun meta(p: String) = Regex("<meta[^>]+(?:property|name)=[\"']$p[\"'][^>]+content=[\"']([^\"']*)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                    ?: Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+(?:property|name)=[\"']$p[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                if (thumb.isEmpty()) thumb = unescape(meta("og:image") ?: meta("twitter:image") ?: "")
                if (title.isEmpty()) title = unescape(meta("og:title") ?: meta("og:description") ?: meta("twitter:title") ?: "")
            }
        } catch (e: Exception) { }
        var ok = false
        if (thumb.isNotEmpty()) try {
            val b = getBytes(thumb)
            val bmp = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) {
                val w = 300; val s = Bitmap.createScaledBitmap(bmp, w, maxOf(1, bmp.height * w / bmp.width), true)
                File(thumbDir(c), "$id.jpg").outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                ok = true
            }
        } catch (e: Exception) { }
        updateLink(c, id) {
            if (title.isNotEmpty()) it.put("title", title.take(160))
            if (author.isNotEmpty()) it.put("author", author)
            it.put("thumb", ok); it.put("fetched", true)
        }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun conn(url: String, asBot: Boolean): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000; readTimeout = 15000; instanceFollowRedirects = true
        // a mesma identificação que os apps usam pra mostrar prévia de link compartilhado
        setRequestProperty("User-Agent", if (asBot) "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)" else "Mozilla/5.0 (Linux; Android 13) PostLeve")
        setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
    }
    private fun get(url: String, asBot: Boolean = false): String? = try {
        val c = conn(url, asBot)
        if (c.responseCode !in 200..299) null else c.inputStream.use { i ->
            val buf = ByteArray(400_000); var n = 0
            while (n < buf.size) { val r = i.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (e: Exception) { null }
    private fun getBytes(url: String): ByteArray? = try {
        val c = conn(url, false); if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ fotos

    fun photoDir(c: Context) = File(c.filesDir, "refs_fotos").apply { mkdirs() }
    private fun photosFile(c: Context) = File(c.filesDir, "refs_fotos.json")
    fun photoFile(c: Context, id: String) = File(photoDir(c), "$id.jpg")
    fun photoThumb(c: Context, id: String) = File(photoDir(c), "${id}_t.jpg")

    fun photos(c: Context): MutableList<JSONObject> = synchronized(lock) {
        try { val a = JSONArray(photosFile(c).readText()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
        catch (e: Exception) { mutableListOf() }
    }
    private fun savePhotos(c: Context, l: List<JSONObject>) = synchronized(lock) {
        val a = JSONArray(); l.forEach { a.put(it) }; photosFile(c).writeText(a.toString())
    }
    fun updatePhoto(c: Context, id: String, f: (JSONObject) -> Unit) = synchronized(lock) {
        val l = photos(c); l.firstOrNull { it.optString("id") == id }?.let(f); savePhotos(c, l)
    }
    fun removePhoto(c: Context, id: String) = synchronized(lock) {
        savePhotos(c, photos(c).filter { it.optString("id") != id }); photoFile(c, id).delete(); photoThumb(c, id).delete()
    }

    /** Guarda a foto como arquivo novo (sem localização nem dados do celular) + miniatura. */
    fun addPhoto(c: Context, uri: Uri, tags: List<String> = emptyList()): String? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 2048) smp *= 2
            val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp }) } ?: return null
            val deg = try { c.contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            } ?: 0f } catch (e: Exception) { 0f }
            val b = if (deg == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
            val id = "r" + System.currentTimeMillis().toString(36) + (10..99).random()
            photoFile(c, id).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val tw = 240; Bitmap.createScaledBitmap(b, tw, maxOf(1, b.height * tw / b.width), true)
                .let { t -> photoThumb(c, id).outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            synchronized(lock) {
                val l = photos(c)
                l.add(0, JSONObject().put("id", id).put("at", System.currentTimeMillis()).put("tags", JSONArray(tags)).put("note", "").put("prompt", "").put("usos", 0))
                savePhotos(c, l)
            }
            id
        } catch (e: Exception) { null }
    }

    /**
     * "Mandar pro Flow": as fotos escolhidas vão pro topo da galeria (pasta PostLeve Ref),
     * pra aparecerem primeiro quando você tocar em adicionar imagem no Flow.
     * As cópias do envio anterior são apagadas, então nada acumula.
     */
    fun sendToGallery(c: Context, ids: List<String>): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val p = Store.prefs(c)
        (p.getStringSet("ref_gallery", emptySet()) ?: emptySet()).forEach { try { c.contentResolver.delete(Uri.parse(it), null, null) } catch (e: Exception) { } }
        val out = HashSet<String>(); var n = 0
        ids.reversed().forEach { id ->  // a primeira escolhida fica por último = mais nova = topo da galeria
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ref_${System.currentTimeMillis()}_$n.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PostLeve Ref")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val u = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return@forEach
                c.contentResolver.openOutputStream(u)?.use { o -> photoFile(c, id).inputStream().use { it.copyTo(o) } }
                c.contentResolver.update(u, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                out.add(u.toString()); n++
                GalleryClean.track(c, u, GalleryClean.flowTtl(c) * 60_000L)
                updatePhoto(c, id) { it.put("usos", it.optInt("usos") + 1); it.put("lastUse", System.currentTimeMillis()) }
                Thread.sleep(15)
            } catch (e: Exception) { }
        }
        p.edit().putStringSet("ref_gallery", out).apply()
        return n
    }

    fun allTags(c: Context): List<String> =
        (links(c) + photos(c)).flatMap { o -> o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
            .filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
}
