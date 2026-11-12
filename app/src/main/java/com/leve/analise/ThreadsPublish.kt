package com.leve.analise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 🤖 Publicação automática pela API OFICIAL do Threads (sem robô na tela).
 *
 * O que o Threads exige (documentação da Meta):
 *  - token da SUA conta com a permissão threads_content_publish (além da threads_basic que você já usa pra ler números);
 *  - foto num LINK PÚBLICO (JPEG/PNG até 8 MB, 320–1440 px de largura): o Threads baixa a foto desse link.
 *    Por isso o app sobe a foto num serviço de hospedagem (ImgBB, que apaga sozinho em 1 h, ou Cloudinary) — chave sua, só no celular;
 *  - post só de texto não precisa de hospedagem (máx. 500 caracteres);
 *  - limite de 250 posts por 24 h.
 * Passos: cria o "contêiner" → espera o Threads processar (status FINISHED) → publica → pega o link do post.
 * Nunca duplica: o id do contêiner é salvo antes de publicar; se cair no meio, na próxima tentativa
 * pergunta ao Threads o status — se já está PUBLISHED, só marca como publicada.
 */
object ThreadsPublish {
    // endereços e esperas ficam em variáveis só pra os testes apontarem pra um servidor falso
    @Volatile internal var BASE = "https://graph.threads.com/"
    @Volatile internal var IMGBB = "https://api.imgbb.com/1/upload"
    @Volatile internal var CLOUDINARY = "https://api.cloudinary.com/v1_1/"
    @Volatile internal var firstWaitMs = 8000L
    @Volatile internal var pollMs = 6000L
    private fun p(c: Context) = Store.prefs(c)

    fun ok(c: Context) = p(c).getBoolean("pub_ok", false)
    fun host(c: Context) = p(c).getString("pub_host", "") ?: ""
    fun imgbbKey(c: Context) = p(c).getString("pub_imgbb", "") ?: ""
    fun cldCloud(c: Context) = p(c).getString("pub_cld_cloud", "") ?: ""
    fun cldPreset(c: Context) = p(c).getString("pub_cld_preset", "") ?: ""
    fun hostReady(c: Context) = when (host(c)) { "imgbb" -> imgbbKey(c).isNotBlank(); "cloudinary" -> cldCloud(c).isNotBlank() && cldPreset(c).isNotBlank(); else -> false }

    /** Pode publicar sozinho (texto sempre; foto só com hospedagem configurada). */
    fun ready(c: Context) = ThreadsApi.on(c) && ok(c)
    fun canPhoto(c: Context) = ready(c) && hostReady(c)

    fun setHost(c: Context, kind: String, a: String, b: String = "") {
        val e = p(c).edit().putString("pub_host", kind)
        when (kind) { "imgbb" -> e.putString("pub_imgbb", a.trim()); "cloudinary" -> e.putString("pub_cld_cloud", a.trim()).putString("pub_cld_preset", b.trim()) }
        e.apply()
    }

    fun status(c: Context): String = when {
        !ThreadsApi.on(c) -> "API do Threads não conectada"
        !ok(c) -> "sem permissão de publicar testada" + (p(c).getString("pub_err", "")?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        else -> "pronta (texto" + (if (hostReady(c)) " e foto via ${host(c)}" else "; foto precisa de hospedagem") + ")" +
            p(c).getString("pub_quota", "")?.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    }

    // ---------------------------------------------------------------- http
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    class ApiError(msg: String, val code: Int, val permanent: Boolean) : Exception(msg)

    private fun call(method: String, url: String, form: Map<String, String>? = null): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 20000; con.readTimeout = 60000; con.requestMethod = method
        if (form != null) {
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            con.outputStream.use { it.write(form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }.toByteArray()) }
        }
        val code = con.responseCode
        val body = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText().orEmpty()
        val o = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
        if (code !in 200..299) {
            val err = o.optJSONObject("error")
            val msg = err?.optString("error_user_msg")?.takeIf { it.isNotEmpty() } ?: err?.optString("message")?.take(200) ?: o.optString("error", "").ifEmpty { "HTTP $code" }
            // 4xx de permissão/parâmetro = não adianta repetir; 5xx, 429 e limite = tenta de novo depois
            val perm = code in 400..499 && code != 429 && (err?.optInt("code") ?: 0) !in setOf(4, 17, 32, 613, 2, 1)
            throw ApiError(msg, code, perm)
        }
        return o
    }

    private fun uid(c: Context): String = p(c).getString("th_uid", "")?.takeIf { it.isNotEmpty() }
        ?: call("GET", BASE + "v1.0/me?fields=id&access_token=" + enc(ThreadsApi.token(c))).optString("id").also { p(c).edit().putString("th_uid", it).apply() }

    // ---------------------------------------------------------------- testes de conexão
    /** Confere se o token tem a permissão de publicar (pergunta a cota de publicação, que exige threads_content_publish). */
    fun testPermission(c: Context): String {
        if (!ThreadsApi.on(c)) return "✗ Conecte a API do Threads primeiro (aba Números → API do Threads)."
        return try {
            ThreadsApi.refreshIfNeeded(c)
            val r = call("GET", BASE + "v1.0/${uid(c)}/threads_publishing_limit?fields=quota_usage,config&access_token=" + enc(ThreadsApi.token(c)))
            val d = r.optJSONArray("data")?.optJSONObject(0)
            val used = d?.optInt("quota_usage") ?: 0; val tot = d?.optJSONObject("config")?.optInt("quota_total") ?: 250
            p(c).edit().putBoolean("pub_ok", true).putString("pub_err", "").putString("pub_quota", "$used/$tot posts nas últimas 24 h").apply()
            "✓ Permissão de publicar OK · $used de $tot posts usados nas últimas 24 h"
        } catch (e: Exception) {
            p(c).edit().putBoolean("pub_ok", false).putString("pub_err", e.message?.take(80)).apply()
            "✗ O token não pode publicar: ${e.message}\n\nComo resolver: no painel do app da Meta (developers.facebook.com → seu app → Casos de uso → Threads API → Permissões), " +
                "adicione threads_content_publish, gere um token novo marcando essa permissão e cole aqui de novo. " +
                "Enquanto isso, a agenda funciona com 🔔 lembretes."
        }
    }

    /** Sobe uma imagem de teste e confere se o link abre de fora (o que o Threads vai fazer). */
    fun testHost(c: Context): String {
        if (!hostReady(c)) return "✗ Escolha a hospedagem e cole a chave."
        return try {
            val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(Color.rgb(255, 214, 224)); drawText("Post Leve teste", 120f, 200f, Paint().apply { textSize = 28f }) }
            val f = File(c.cacheDir, "pub_test.jpg"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            val url = upload(c, f)
            val con = URL(url).openConnection() as HttpURLConnection; con.connectTimeout = 15000; con.readTimeout = 15000
            val code = con.responseCode; val type = con.contentType.orEmpty(); con.disconnect()
            if (code == 200 && type.startsWith("image")) "✓ Hospedagem OK: a foto ficou pública em $url" else "✗ Subiu, mas o link não abriu como imagem (HTTP $code, $type)"
        } catch (e: Exception) { "✗ Hospedagem falhou: ${e.message}" }
    }

    // ---------------------------------------------------------------- hospedagem da foto
    /** JPEG pronto pro Threads: no máx. 1440 px de largura e menos de 8 MB. */
    private fun prepared(c: Context, src: File): File {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(src.path, o)
        if (o.outWidth in 320..1440 && src.length() < 7_500_000 && src.extension.lowercase() in setOf("jpg", "jpeg")) return src
        var s = 1; while (o.outWidth / (s * 2) >= 1440) s *= 2
        var b = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: throw Exception("não consegui abrir a foto")
        if (b.width > 1440) b = Bitmap.createScaledBitmap(b, 1440, (b.height * 1440.0 / b.width).toInt(), true)
        val out = File(c.cacheDir, "pub_" + src.nameWithoutExtension + ".jpg")
        var q = 92
        do { out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, q, it) }; q -= 8 } while (out.length() > 7_500_000 && q > 50)
        return out
    }

    fun upload(c: Context, src: File): String {
        val f = prepared(c, src)
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        return when (host(c)) {
            "imgbb" -> call("POST", IMGBB + "?expiration=3600&key=" + enc(imgbbKey(c)), mapOf("image" to b64))
                .optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http") } ?: throw Exception("ImgBB não devolveu o link")
            "cloudinary" -> call("POST", CLOUDINARY + "${enc(cldCloud(c))}/image/upload", mapOf("file" to "data:image/jpeg;base64,$b64", "upload_preset" to cldPreset(c)))
                .optString("secure_url").takeIf { it.startsWith("http") } ?: throw Exception("Cloudinary não devolveu o link")
            else -> throw ApiError("sem hospedagem de foto configurada", 0, true)
        }
    }

    // ---------------------------------------------------------------- publicar
    /**
     * Publica todos os agendamentos em "executando". Devolve true se algum precisa tentar de novo mais tarde
     * (sem internet, Threads fora do ar, limite) — o sistema chama de novo com espera crescente.
     */
    @Synchronized fun runPending(c: Context): Boolean {
        var retry = false
        Agenda.all(c).filter { it.optString("status") == AgendaCore.EXECUTANDO }.forEach { o0 ->
            val id = o0.optString("id")
            // sem permissão ou foto sem hospedagem: não é erro, vira lembrete (um toque abre o Threads pronto)
            val photo = o0.optString("photo").isNotEmpty() || (o0.optString("content") == "auto")
            if (!ready(c) || (photo && !hostReady(c) && o0.optString("content") != "texto")) {
                Agenda.patch(c, id) { it.put("status", AgendaCore.PENDENTE); Agenda.log(it, if (!ready(c)) "publicação automática não liberada: virou lembrete" else "foto sem hospedagem configurada: virou lembrete") }
                Agenda.get(c, id)?.let { Agenda.notifyDue(c, Agenda.resolve(c, id) ?: it) }
                return@forEach
            }
            try {
                publishOne(c, id)
            } catch (e: Exception) {
                val permanent = e is ApiError && e.permanent
                val tries = o0.optInt("tries") + 1
                ErrLog.log(c, "publicar agendado", e)
                if (permanent || tries >= 5) {
                    Agenda.patch(c, id) { it.put("status", AgendaCore.ERRO).put("err", e.message?.take(200)).put("tries", tries); Agenda.log(it, "✗ não publicou: ${e.message?.take(120)}") }
                    Agenda.get(c, id)?.let { Agenda.notifyDue(c, it) } // cai pro lembrete: um toque abre o Threads pronto
                } else {
                    Agenda.patch(c, id) { it.put("tries", tries).put("err", e.message?.take(200)).put("execAt", System.currentTimeMillis()); Agenda.log(it, "tentativa $tries falhou (${e.message?.take(80)}): tento de novo") }
                    retry = true
                }
            }
        }
        return retry
    }

    private fun publishOne(c: Context, id: String) {
        var o = Agenda.resolve(c, id) ?: return
        if (o.optString("mid").isNotEmpty()) { Agenda.markPublished(c, id, "API"); return }
        if (!ready(c)) throw ApiError("publicação automática não está liberada (teste a permissão na Agenda)", 0, true)
        ThreadsApi.refreshIfNeeded(c)
        val tok = ThreadsApi.token(c); val uid = uid(c)
        // 1) já tem contêiner? pergunta ao Threads como está (evita publicar 2 vezes)
        var cid = o.optString("cid")
        if (cid.isNotEmpty()) {
            val st = try { call("GET", BASE + "v1.0/$cid?fields=status,error_message&access_token=" + enc(tok)) } catch (e: ApiError) { JSONObject().put("status", "EXPIRED") }
            when (st.optString("status")) {
                "PUBLISHED" -> { Agenda.markPublished(c, id, "API (confirmado no Threads)"); return }
                "FINISHED", "IN_PROGRESS" -> { }
                else -> { Agenda.patch(c, id) { it.remove("cid"); Agenda.log(it, "contêiner ${st.optString("status")} (${st.optString("error_message")}): refazendo") }; cid = "" }
            }
        }
        // 2) cria o contêiner
        if (cid.isEmpty()) {
            val text = o.optString("cap").let { if (it.length > 500) it.take(497) + "…" else it }
            val photo = o.optString("photo")
            val form = HashMap<String, String>()
            form["access_token"] = tok
            if (photo.isEmpty()) {
                if (text.isBlank()) throw ApiError("post sem foto e sem texto", 0, true)
                form["media_type"] = "TEXT"; form["text"] = text
            } else {
                val f = PostQueue.imageFile(c, photo)
                if (!f.exists()) throw ApiError("a foto não está mais no app", 0, true)
                if (!hostReady(c)) throw ApiError("foto precisa de hospedagem configurada (Agenda → ⚙)", 0, true)
                val url = upload(c, f)
                Agenda.patch(c, id) { Agenda.log(it, "foto hospedada") }
                form["media_type"] = "IMAGE"; form["image_url"] = url; if (text.isNotBlank()) form["text"] = text
            }
            cid = call("POST", BASE + "v1.0/$uid/threads", form).optString("id").ifEmpty { throw Exception("o Threads não devolveu o contêiner") }
            Agenda.patch(c, id) { it.put("cid", cid); Agenda.log(it, "contêiner criado") }
        }
        // 3) espera o Threads processar (≈30 s recomendado)
        val t0 = System.currentTimeMillis(); var st = ""
        while (System.currentTimeMillis() - t0 < 120_000L) {
            Thread.sleep(if (st.isEmpty()) firstWaitMs else pollMs)
            val r = call("GET", BASE + "v1.0/$cid?fields=status,error_message&access_token=" + enc(tok))
            st = r.optString("status")
            if (st == "FINISHED") break
            if (st == "PUBLISHED") { Agenda.markPublished(c, id, "API (confirmado no Threads)"); return }
            if (st == "ERROR" || st == "EXPIRED") { Agenda.patch(c, id) { it.remove("cid") }; throw ApiError("o Threads recusou a mídia: " + r.optString("error_message").ifEmpty { st }, 0, st == "ERROR") }
        }
        if (st != "FINISHED") throw Exception("o Threads ainda está processando ($st)")
        // 4) publica
        val mid = call("POST", BASE + "v1.0/$uid/threads_publish", mapOf("creation_id" to cid, "access_token" to tok)).optString("id")
        Agenda.patch(c, id) { it.put("mid", mid) }
        val link = try { call("GET", BASE + "v1.0/$mid?fields=permalink&access_token=" + enc(tok)).optString("permalink") } catch (e: Exception) { "" }
        Agenda.markPublished(c, id, "API", mid, link)
        try { testPermission(c) } catch (e: Exception) { } // atualiza a cota
        o = Agenda.get(c, id) ?: return
        Agenda.notifySimple(c, o, "✓ Publicado no Threads", o.optString("cap").take(120))
    }

    /** "Publicar agora" de um agendado (modo API). */
    fun publishNow(c: Context, id: String) {
        Agenda.patch(c, id) { it.put("status", AgendaCore.EXECUTANDO).put("execAt", System.currentTimeMillis()).put("tries", 0); Agenda.log(it, "publicar agora") }
        PublishJob.start(c)
    }
}
