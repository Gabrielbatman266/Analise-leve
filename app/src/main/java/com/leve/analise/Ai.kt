package com.leve.analise

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AiException(val code: Int, msg: String) : Exception(msg)

/** Conversa com a IA escolhida (Claude ou Gemini) usando a chave do próprio usuário. */
object Ai {
    const val DEFAULT_CLAUDE = "claude-haiku-5-5"
    const val DEFAULT_GEMINI = "gemini-3.8-flash"

    private const val EXTRACT = """Estes são prints de um perfil do Threads, com posts de foto e legenda curta.
Para CADA post visível, anote: a legenda exata ("" se não tiver), o que a foto mostra em poucas palavras, e os números embaixo do post (curtidas, comentários, reposts). "1,2 mil" = 1200. Se um número não aparecer, use 0. Ignore posts cortados sem números e ignore anúncios.
Responda só com JSON, sem texto antes ou depois: {"posts":[{"legenda":"","foto":"","curtidas":0,"comentarios":0,"reposts":0}]}"""

    fun provider(c: Context) = Store.prefs(c).getString("provider", "claude") ?: "claude"
    fun key(c: Context) = Store.prefs(c).getString("key", "")?.trim() ?: ""
    /** Pode ter vários modelos separados por vírgula: quando um está lotado, usa o próximo. */
    fun models(c: Context): List<String> {
        val m = Store.prefs(c).getString("model", "")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return m.ifEmpty { listOf(if (provider(c) == "gemini") DEFAULT_GEMINI else DEFAULT_CLAUDE) }
    }
    @Volatile private var rot = 0
    fun model(c: Context): String { val l = models(c); return l[rot % l.size] }
    /** Troca pro próximo modelo da lista. Retorna false se só tem um. */
    fun nextModel(c: Context): Boolean { val n = models(c).size; if (n < 2) return false; rot = (rot + 1) % n; return true }

    /** Lê um lote de prints e devolve os posts encontrados. */
    fun extract(c: Context, files: List<File>): JSONArray {
        val images = files.map { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        val txt = ask(c, EXTRACT, images)
        val arr = parseObj(txt).optJSONArray("posts")
            ?: throw AiException(-2, "Resposta que não entendi: " + txt.replace("\n", " ").take(160).ifEmpty { "(vazia)" })
        return arr
    }

    /** Compara os melhores com os piores e devolve frases curtas do que funciona. */
    fun patterns(c: Context, top: List<JSONObject>, low: List<JSONObject>): JSONArray {
        fun line(p: JSONObject) = "- \"${p.optString("legenda")}\" | foto: ${p.optString("foto")} | ${p.optLong("curtidas")} curtidas, ${p.optLong("comentarios")} comentários"
        val prompt = """Compare os posts que MAIS engajaram com os que MENOS engajaram neste perfil do Threads e diga o que faz a diferença: tipo de foto, tamanho e tom da legenda, se pergunta algo, emoji, chamada pra comentar.
Mais engajados:
${top.joinToString("\n") { line(it) }}
Menos engajados:
${low.joinToString("\n") { line(it) }}
Responda só com JSON: {"padroes":["4 a 7 frases curtas e práticas em português"]}"""
        return parseObj(ask(c, prompt, emptyList())).optJSONArray("padroes") ?: JSONArray()
    }

    private val CTAS = listOf("gostou, amor?", "e aí, aprova?", "me fala nos comentários 👇", "1 a 10, sem mentir",
        "posto mais assim?", "fala a verdade, ficou bom?", "vem conversar comigo", "me conta o que achou", "gostou ou quer mais?")
    val OFFLINE = listOf("sem filtro, sem desculpa", "hoje eu só quero paz e um bom ângulo", "era pra ser só uma foto",
        "modo sexta-feira: ativado", "fiquei pensando… vocês notaram?", "o espelho pediu e eu deixei",
        "dia bom é dia leve", "posando pra ninguém e pra todo mundo", "mais um dia, mais um look",
        "a luz tava perfeita, não resisti", "nem tudo precisa de legenda", "domingo com cara de domingo",
        "hoje acordei assim", "só passando pra deixar isso aqui", "tô com saudade de sair",
        "foto tirada no impulso", "esse ângulo eu aprovo", "vibe do dia: tranquila",
        "não planejei, só aconteceu", "um café e essa foto", "deixa eu aparecer um pouquinho",
        "sexta chegou e eu também", "às vezes o simples é tudo", "registro do dia ✨", "quase não postei")

    fun withCta(list: List<String>): List<String> {
        val pool = CTAS.shuffled()
        return list.mapIndexed { i, c -> c.trimEnd() + "\n" + pool[i % pool.size] }
    }

    /** Legendas pra uma foto da fila, no estilo do que mais engajou no banco. */
    /** Várias fotos num pedido só: devolve as legendas de cada foto, na mesma ordem. */
    fun captionsBatch(c: Context, imgs: List<File>): List<List<String>> {
        if (imgs.size == 1) return listOf(captions(c, imgs[0]))
        val base = Captions.prompt(c).substringBefore("Responda só com JSON")
        val prompt = base + """
São ${imgs.size} fotos anexadas, numeradas de 1 a ${imgs.size} na ordem. Faça 5 legendas diferentes pra CADA foto, específicas dela.
Responda só com JSON: {"fotos":[{"n":1,"legendas":["...","...","...","...","..."]}]}"""
        val b64 = imgs.map { Base64.encodeToString(shrink(it), Base64.NO_WRAP) }
        val txt = ask(c, prompt, b64)
        val arr = parseObj(txt).optJSONArray("fotos") ?: throw AiException(-2, "Resposta que não entendi: " + txt.take(120))
        val out = MutableList(imgs.size) { emptyList<String>() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optInt("n", i + 1) - 1
            if (n !in out.indices) continue
            val l = o.optJSONArray("legendas") ?: continue
            out[n] = Captions.withCta(c, (0 until l.length()).map { l.optString(it) }.filter { it.isNotBlank() }.take(6))
        }
        if (out.all { it.isEmpty() }) throw AiException(-2, "Nenhuma legenda veio")
        return out
    }

    fun captions(c: Context, img: File): List<String> {
        val prompt = Captions.prompt(c)
        val b64 = Base64.encodeToString(shrink(img), Base64.NO_WRAP)
        val txt = ask(c, prompt, listOf(b64))
        val arr = parseObj(txt).optJSONArray("legendas")
            ?: throw AiException(-2, "Resposta que não entendi: " + txt.take(120))
        val list = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.take(6)
        if (list.isEmpty()) throw AiException(-2, "Nenhuma legenda veio")
        return Captions.withCta(c, list)
    }

    private fun shrink(f: File): ByteArray {
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path, o) ?: return f.readBytes()
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    /** Pedido mínimo pra conferir chave e modelo. */
    fun test(c: Context): String = ask(c, "Responda só com JSON: {\"ok\":true}", emptyList()).trim().take(200)

    private fun ask(c: Context, prompt: String, imagesB64: List<String>): String {
        val k = key(c)
        if (k.isEmpty()) throw AiException(401, "Coloque sua chave de API nas configurações")
        return if (provider(c) == "gemini") gemini(k, model(c), prompt, imagesB64) else claude(k, model(c), prompt, imagesB64)
    }

    private fun claude(key: String, model: String, prompt: String, imgs: List<String>): String {
        val content = JSONArray()
        imgs.forEach {
            content.put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", it)))
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val res = post("https://api.anthropic.com/v1/messages", body.toString(), mapOf(
            "x-api-key" to key, "anthropic-version" to "2023-06-01"))
        val arr = JSONObject(res).optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    private fun gemini(key: String, model: String, prompt: String, imgs: List<String>): String {
        val parts = JSONArray()
        imgs.forEach { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", it))) }
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val res = post(url, body.toString(), mapOf("x-goog-api-key" to key))
        val cand = JSONObject(res).optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 20000
        con.readTimeout = 120000
        con.doOutput = true
        con.setRequestProperty("content-type", "application/json")
        headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
        con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = con.responseCode
        val stream = if (code in 200..299) con.inputStream else con.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        con.disconnect()
        if (code !in 200..299) {
            val msg = try {
                val e = JSONObject(text).opt("error")
                if (e is JSONObject) e.optString("message") else e?.toString() ?: text
            } catch (x: Exception) { text.take(200) }
            throw AiException(code, msg.ifEmpty { "Erro $code" })
        }
        return text
    }

    private fun parseObj(t: String): JSONObject {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return JSONObject()
        return try { JSONObject(t.substring(a, b + 1)) } catch (e: Exception) { JSONObject() }
    }
}
