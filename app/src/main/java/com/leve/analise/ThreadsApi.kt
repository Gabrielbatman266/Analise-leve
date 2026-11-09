package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 🔗 API oficial do Threads (só leitura, a sua conta): números exatos de cada post
 * (visualizações, curtidas, respostas, reposts, citações, compartilhamentos) e seguidores da conta.
 * Seguidores por post: o app mede os seguidores de hora em hora e atribui o ganho aos posts daquele período.
 * O token fica só no celular. Renova sozinho antes de vencer (60 dias).
 */
object ThreadsApi {
    private const val BASE = "https://graph.threads.com/"
    private fun p(c: Context) = Store.prefs(c)
    private fun file(c: Context) = File(c.filesDir, "threads_api.json")
    private fun folFile(c: Context) = File(c.filesDir, "seguidores.json")

    fun token(c: Context) = p(c).getString("th_token", "") ?: ""
    fun on(c: Context) = token(c).isNotEmpty()
    fun user(c: Context) = p(c).getString("th_user", "") ?: ""
    fun lastSync(c: Context) = p(c).getLong("th_sync", 0)
    fun lastErr(c: Context) = p(c).getString("th_err", "") ?: ""
    fun expiresAt(c: Context) = p(c).getLong("th_exp", 0)

    private fun get(url: String): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 25000
        val code = con.responseCode
        val body = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText().orEmpty()
        val o = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
        if (code !in 200..299) throw Exception(o.optJSONObject("error")?.optString("message")?.take(160) ?: "HTTP $code")
        return o
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Conecta com o token (e troca por um de 60 dias se você colar o "segredo do app"). */
    fun connect(c: Context, token0: String, secret: String): String {
        var tok = token0.trim()
        var exp = System.currentTimeMillis() + 55L * 86400_000L
        if (secret.isNotBlank()) try {
            val r = get(BASE + "access_token?grant_type=th_exchange_token&client_secret=${enc(secret.trim())}&access_token=${enc(tok)}")
            r.optString("access_token").takeIf { it.isNotEmpty() }?.let { tok = it; exp = System.currentTimeMillis() + r.optLong("expires_in", 5_000_000) * 1000 }
        } catch (e: Exception) { /* já pode ser um token longo */ }
        val me = get(BASE + "v1.0/me?fields=id,username&access_token=${enc(tok)}")
        p(c).edit().putString("th_token", tok).putString("th_user", me.optString("username")).putString("th_uid", me.optString("id"))
            .putLong("th_exp", exp).putString("th_err", "").apply()
        return "Conectado como @${me.optString("username")}"
    }
    fun disconnect(c: Context) = p(c).edit().remove("th_token").remove("th_user").remove("th_uid").apply()

    /** Renova o token quando faltam menos de 15 dias (o Threads deixa a partir de 24 h de idade). */
    private fun refreshIfNeeded(c: Context) {
        if (expiresAt(c) - System.currentTimeMillis() > 15L * 86400_000L) return
        try {
            val r = get(BASE + "refresh_access_token?grant_type=th_refresh_token&access_token=${enc(token(c))}")
            r.optString("access_token").takeIf { it.isNotEmpty() }?.let {
                p(c).edit().putString("th_token", it).putLong("th_exp", System.currentTimeMillis() + r.optLong("expires_in", 5_000_000) * 1000).apply()
            }
        } catch (e: Exception) { ErrLog.log(c, "API Threads: renovar token", e) }
    }

    fun posts(c: Context) = JsonFile.read(file(c))
    fun followers(c: Context) = JsonFile.read(folFile(c))
    fun followersNow(c: Context) = followers(c).lastOrNull()?.optInt("n")

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** Sincroniza (de hora em hora): seguidores + números dos posts dos últimos 7 dias. */
    fun sync(c: Context, force: Boolean = false): String {
        if (!on(c)) return "Não conectado"
        if (!force && System.currentTimeMillis() - lastSync(c) < 50 * 60_000L) return "Sincronizado há pouco"
        return try {
            refreshIfNeeded(c)
            val tok = enc(token(c))
            // 1) seguidores agora
            val fo = get(BASE + "v1.0/me/threads_insights?metric=followers_count&access_token=$tok")
            val fc = fo.optJSONArray("data")?.optJSONObject(0)?.let { it.optJSONObject("total_value")?.optInt("value") ?: it.optJSONArray("values")?.optJSONObject(0)?.optInt("value") }
            if (fc != null) JsonFile.edit(folFile(c)) { l -> l.add(JSONObject().put("t", System.currentTimeMillis()).put("n", fc)); while (l.size > 2000) l.removeAt(0) }
            // 2) posts recentes + números
            val list = get(BASE + "v1.0/me/threads?fields=id,text,timestamp,media_type,permalink&limit=40&access_token=$tok").optJSONArray("data") ?: JSONArray()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
            val now = System.currentTimeMillis()
            var n = 0
            val store = posts(c).associateBy { it.optString("id") }.toMutableMap()
            for (i in 0 until list.length()) {
                val m = list.getJSONObject(i)
                val at = try { fmt.parse(m.optString("timestamp"))?.time ?: 0 } catch (e: Exception) { 0L }
                if (now - at > 7 * 86400_000L) continue
                val ins = try { get(BASE + "v1.0/${m.optString("id")}/insights?metric=views,likes,replies,reposts,quotes,shares&access_token=$tok").optJSONArray("data") } catch (e: Exception) { null } ?: continue
                val o = store[m.optString("id")] ?: JSONObject().put("id", m.optString("id"))
                o.put("text", m.optString("text")).put("at", at).put("type", m.optString("media_type")).put("link", m.optString("permalink")).put("upd", now)
                for (k in 0 until ins.length()) {
                    val e = ins.getJSONObject(k)
                    val v = e.optJSONObject("total_value")?.optInt("value") ?: e.optJSONArray("values")?.optJSONObject(0)?.optInt("value") ?: 0
                    o.put(e.optString("name"), v)
                }
                store[o.optString("id")] = o; n++
            }
            attachToSends(c, store.values.toList())
            attributeFollowers(c, store.values.toList())
            JsonFile.write(file(c), store.values.sortedByDescending { it.optLong("at") }.take(400))
            rateFromApi(c)
            p(c).edit().putLong("th_sync", now).putString("th_err", "").apply()
            "✓ ${fc?.let { "$it seguidores · " } ?: ""}$n post(s) atualizados"
        } catch (e: Exception) {
            p(c).edit().putString("th_err", e.message ?: "erro").apply(); ErrLog.log(c, "API Threads", e)
            "⚠ " + (e.message ?: "erro")
        }
    }

    /** Guarda números vindos da TELA ou de um PRINT (sem API). Mantém o maior valor de cada métrica. */
    /** Nenhum post seu tem milhões: acima disso é leitura errada (ex.: "57m" = 57 minutos). */
    const val MAXV = 3_000_000
    /** Bom senso: tira número impossível (curtidas > visualizações, valor gigante) antes de guardar. */
    fun sane(m0: Map<String, Int>): Map<String, Int> {
        val m = m0.filterValues { it in 0..MAXV }.toMutableMap()
        val v = m["views"]
        if (v != null && v >= 30) listOf("likes", "replies", "reposts", "quotes", "shares", "saves", "fol").forEach { k -> m[k]?.let { if (it > v * 1.2) m.remove(k) } }
        if (v == null) m["likes"]?.let { lk -> m.filterKeys { it != "likes" }.values.maxOrNull()?.let { other -> if (other > 0 && lk > other * 400) m.remove("likes") } }
        return m
    }

    /** Uma vez: limpa números impossíveis que já tinham entrado (a leitura antiga lia "57m" como 57 milhões). */
    fun repair(c: Context): Int {
        if (p(c).getBoolean("repair_mi", false)) return 0
        var n = 0
        JsonFile.edit(file(c)) { l -> l.forEach { o ->
            val keys = listOf("views", "likes", "replies", "reposts", "quotes", "shares", "saves", "fol")
            val before = keys.filter { o.has(it) }.associateWith { o.optInt(it) }
            val after = sane(before)
            (before.keys - after.keys).forEach { o.remove(it); n++ }
        } }
        JsonFile.edit(File(c.filesDir, "curtidas.json")) { l -> n += l.count { it.optInt("likes") > MAXV }; l.removeAll { it.optInt("likes") > MAXV } }
        p(c).edit().putBoolean("repair_mi", true).apply(); invalidate()
        return n
    }

    fun upsertLocal(c: Context, sendAt: Long, photoId: String, text: String, m0: Map<String, Int>, src: String, kind: String = "fila") {
        val m = sane(m0)
        if (m.isEmpty()) return
        JsonFile.edit(file(c)) { l ->
            val o = l.firstOrNull { it.optLong("send") == sendAt } ?: JSONObject().put("id", "$src:$sendAt").put("send", sendAt)
                .put(if (kind == "video") "video" else "photo", photoId).put("kind", kind)
                .put("at", sendAt).put("text", text).also { l.add(0, it) }
            m.forEach { (k, v) -> if (v > o.optInt(k, -1)) o.put(k, v) }
            o.put("upd", System.currentTimeMillis()).put("src", if (o.optString("src").isEmpty()) src else o.optString("src"))
            while (l.size > 400) l.removeAt(l.size - 1)
        }
        p(c).edit().putLong("loc_last_$src", System.currentTimeMillis()).apply()
    }

    /** Post feito fora do app: guarda números pela legenda (sem foto ligada). */
    fun upsertOrphan(c: Context, text: String, m0: Map<String, Int>, src: String, kind: String = "fila") {
        val m = sane(m0)
        if (m.isEmpty() || text.length < 8) return
        val key = (if (kind == "video") "orfv:" else "orf:") + norm(text).take(60).hashCode()
        JsonFile.edit(file(c)) { l ->
            val o = l.firstOrNull { it.optString("id") == key } ?: JSONObject().put("id", key).put("kind", kind).put("text", text).put("at", System.currentTimeMillis()).put("orphan", true).also { l.add(0, it) }
            m.forEach { (k, v) -> if (v > o.optInt(k, -1)) o.put(k, v) }
            o.put("upd", System.currentTimeMillis()).put("src", src)
            while (l.size > 400) l.removeAt(l.size - 1)
        }
        invalidate()
    }

    /** Seguidores lidos na tela do perfil (sem API). */
    fun addFollowers(c: Context, n: Int, src: String) {
        if (n <= 0) return
        JsonFile.edit(folFile(c)) { l ->
            val last = l.lastOrNull()
            if (last != null && last.optInt("n") == n && System.currentTimeMillis() - last.optLong("t") < 20 * 60_000L) return@edit
            l.add(JSONObject().put("t", System.currentTimeMillis()).put("n", n).put("src", src)); while (l.size > 2000) l.removeAt(0)
        }
    }

    /** Sem rede: distribui seguidores e dá as notas com o que já se sabe (API, tela ou print). */
    fun process(c: Context) {
        try { repair(c) } catch (e: Exception) { }
        val ps = posts(c); if (ps.isEmpty()) return
        attributeFollowers(c, ps)
        JsonFile.write(file(c), ps)
        rateFromApi(c)
        invalidate()
        try { Predictor.train(c, force = true) } catch (e: Exception) { ErrLog.log(c, "modelo", e) }
    }

    /** Liga cada post da API ao envio do app (mesma legenda, horário próximo). */
    private fun attachToSends(c: Context, ps: List<JSONObject>) {
        val sends = SendLog.all(c).filter { it.optString("kind") == "fila" }
        ps.filter { it.optString("send").isEmpty() }.forEach { m ->
            val t = norm(m.optString("text"))
            val e = sends.filter { kotlin.math.abs(m.optLong("at") - it.optLong("at")) < 6 * 3600_000L && m.optLong("at") >= it.optLong("at") - 10 * 60_000L }
                .firstOrNull { s -> val cap = norm(s.optString("cap")); cap.isNotEmpty() && (t.startsWith(cap.take(25)) || cap.startsWith(t.take(25)) && t.length >= 6) }
                ?: return@forEach
            m.put("send", e.optLong("at")).put("photo", e.optString("id"))
        }
    }

    /** Seguidores ganhos em cada post: ganho entre o post e o próximo (máx. 6 h), dividido pelos posts do período (pelo tanto de visualizações). */
    private fun attributeFollowers(c: Context, ps0: List<JSONObject>) {
        val f = followers(c); if (f.size < 2) return
        val ps = ps0.filter { it.optString("kind", "fila").ifEmpty { "fila" } == "fila" } // seguidores do Threads só pros posts do Threads
        fun at(t: Long) = f.minByOrNull { kotlin.math.abs(it.optLong("t") - t) }?.takeIf { kotlin.math.abs(it.optLong("t") - t) < 90 * 60_000L }?.optInt("n")
        val sorted = ps.sortedBy { it.optLong("at") }
        // agrupa posts próximos (bloco), mede o ganho do bloco
        var i = 0
        while (i < sorted.size) {
            val start = sorted[i].optLong("at"); var j = i
            while (j + 1 < sorted.size && sorted[j + 1].optLong("at") - sorted[j].optLong("at") < 45 * 60_000L) j++
            val end = minOf(sorted.getOrNull(j + 1)?.optLong("at") ?: Long.MAX_VALUE, sorted[j].optLong("at") + 6 * 3600_000L)
            if (end <= System.currentTimeMillis()) {
                val a = at(start); val b = at(end)
                if (a != null && b != null) {
                    val grp = sorted.subList(i, j + 1); val tv = grp.sumOf { maxOf(1, it.optInt("views")) }.toDouble()
                    grp.forEach { it.put("fol", ((b - a) * maxOf(1, it.optInt("views")) / tv)) }
                }
            }
            i = j + 1
        }
    }

    // ---------- nota pelos números exatos
    private fun pct(v: Double, all: List<Double>) = if (all.isEmpty()) 0.5 else all.count { it < v }.toDouble() / all.size

    /** Pontuação composta (0..1, relativa aos seus posts): curtidas, respostas, reposts/citações/compart., visualizações e seguidores. */
    fun composite(c: Context, m: JSONObject, ps0: List<JSONObject> = posts(c)): Double {
        val kind = m.optString("kind", "fila").ifEmpty { "fila" }
        val ps = ps0.filter { it.optString("kind", "fila").ifEmpty { "fila" } == kind }.ifEmpty { ps0 } // vídeo compara com vídeo, foto com foto
        fun col(k: String) = ps.map { it.optDouble(k, 0.0) }
        val inter = { o: JSONObject -> o.optDouble("reposts", 0.0) + o.optDouble("quotes", 0.0) + o.optDouble("shares", 0.0) + o.optDouble("saves", 0.0) }
        return 0.25 * pct(m.optDouble("likes", 0.0), col("likes")) +
            0.20 * pct(m.optDouble("replies", 0.0), col("replies")) +
            0.10 * pct(inter(m), ps.map(inter)) +
            0.20 * pct(m.optDouble("views", 0.0), col("views")) +
            0.25 * pct(m.optDouble("fol", 0.0), col("fol"))
    }

    /** Dá a nota com os números exatos: com 6 h já marca os que bombam; com 24 h dá a nota final (pode corrigir notas automáticas). */
    private fun rateFromApi(c: Context) {
        val ps = posts(c).filter { it.optLong("send") > 0 }
        if (posts(c).size < 5) return
        val sends = SendLog.all(c).associateBy { it.optLong("at") }
        ps.forEach { m ->
            val e = sends[m.optLong("send")] ?: return@forEach
            val age = (System.currentTimeMillis() - m.optLong("at")) / 3600_000.0
            val s = composite(c, m)
            val k = when { s >= 0.85 -> "top"; s >= 0.62 -> "good"; age >= 24 && s <= 0.3 -> "bad"; age >= 24 -> "meh"; else -> null } ?: return@forEach
            val cur = e.optString("nota")
            val final = age >= 24
            if (e.optBoolean("mn")) return@forEach // nota que VOCÊ deu: não mexo
            if (cur == k || (cur.isNotEmpty() && !final)) return@forEach
            if (cur.isNotEmpty() && e.optBoolean("apiFinal")) return@forEach
            try { SendLog.rate(c, e, k); if (final) SendLog.mark(c, e.optLong("at"), "apiFinal") } catch (x: Exception) { ErrLog.log(c, "API Threads: nota", x) }
        }
    }

    /** Valor (0..1) pro modelo de previsão, quando tem número exato. */
    fun targetFor(c: Context, sendAt: Long): Double? {
        val ps = posts(c); val m = ps.firstOrNull { it.optLong("send") == sendAt } ?: return null
        if (System.currentTimeMillis() - m.optLong("at") < 12 * 3600_000L || ps.size < 5) return null
        return composite(c, m, ps)
    }

    // ---------- números → sorteio (fotos e legendas que rendem saem mais)
    @Volatile private var cacheAt = 0L
    @Volatile private var photoMap: Map<String, Double> = emptyMap()
    @Volatile private var capMap: Map<String, Double> = emptyMap()
    @Volatile private var videoMap: Map<String, Double> = emptyMap()
    private fun capKey(s: String) = norm(s).take(30)

    private fun maps(c: Context) {
        if (System.currentTimeMillis() - cacheAt < 60_000L) return
        val ps = posts(c).filter { (it.optLong("send") > 0 || it.optBoolean("orphan")) && System.currentTimeMillis() - it.optLong("at") > 3 * 3600_000L }
        if (ps.size < 3) { photoMap = emptyMap(); capMap = emptyMap(); cacheAt = System.currentTimeMillis(); return }
        val sc = ps.map { it to composite(c, it, ps) }
        videoMap = sc.filter { it.first.optString("video").isNotEmpty() }.groupBy { it.first.optString("video") }.mapValues { (_, v) -> v.map { it.second }.average() }
        photoMap = sc.filter { it.first.optString("photo").isNotEmpty() }.groupBy { it.first.optString("photo") }.mapValues { (_, v) -> v.map { it.second }.average() }
        capMap = sc.filter { it.first.optString("kind", "fila") != "video" }.groupBy { capKey(it.first.optString("text")) }.filterKeys { it.length >= 6 }.mapValues { (_, v) -> v.map { it.second }.average() }
        cacheAt = System.currentTimeMillis()
    }
    fun invalidate() { cacheAt = 0 }

    /** Pontuação real (0..1) da foto pelos números dos posts dela; null = sem números. */
    fun photoScore(c: Context, id: String): Double? { maps(c); return photoMap[id] }
    /** Pontuação real da legenda (em qualquer foto). */
    fun captionScore(c: Context, cap: String): Double? { maps(c); return capMap[capKey(cap)] }
    fun withNumbers(c: Context): Int { maps(c); return photoMap.size + videoMap.size }
    /** Pontuação real do vídeo (Instagram). */
    fun videoScore(c: Context, id: String): Double? { maps(c); return videoMap[id] }
    fun topVideos(c: Context, n: Int = 5) = posts(c).filter { it.optString("kind") == "video" && it.optString("video").isNotEmpty() }.map { it to composite(c, it) }.sortedByDescending { it.second }.take(n)
    fun videoAiText(c: Context): String {
        val t = topVideos(c, 5); if (t.isEmpty()) return ""
        val lib = VideoExport.library(c).associateBy { it.optString("id") }
        return "🎬 REELS QUE MAIS RENDERAM (números reais):\n" + t.joinToString("\n") { (m, s) ->
            val v = lib[m.optString("video")]
            "- texto no vídeo \"${v?.optString("texto")?.replace('\n', ' ')?.take(60) ?: "?"}\" · categoria ${v?.optString("ctx") ?: "?"} → ${m.optInt("views")} reproduções, ${m.optInt("likes")} curtidas, ${m.optInt("replies")} comentários, ${m.optInt("shares")} compart., ${m.optInt("saves")} salvos, +${m.optInt("fol")} seguidores (${(s * 100).toInt()} pts)"
        } + "\nFaça mais do que trouxe SEGUIDORES e compartilhamentos."
    }

    /** Posts que mais trouxeram seguidores (pra IA e pra tela). */
    fun topFollowers(c: Context, n: Int = 5) = posts(c).filter { it.optDouble("fol", 0.0) > 0 }.sortedByDescending { it.optDouble("fol") }.take(n)

    fun aiText(c: Context): String {
        val tf = topFollowers(c, 5); if (tf.isEmpty()) return ""
        return "👥 POSTS QUE MAIS TROUXERAM SEGUIDORES (números exatos da API):\n" +
            tf.joinToString("\n") { "- \"${it.optString("text").replace('\n', ' ').take(90)}\" → +${"%.1f".format(it.optDouble("fol"))} seguidores, ${it.optInt("views")} visualizações, ${it.optInt("likes")} curtidas, ${it.optInt("replies")} respostas" } +
            "\nPriorize o tipo de legenda/conteúdo que traz SEGUIDORES, não só curtidas."
    }

    fun summary(c: Context): String {
        if (!on(c)) return "Não conectado (sem problema: o leitor de tela e os prints também trazem os números)."
        val f = followers(c)
        val now = f.lastOrNull()?.optInt("n")
        val day = f.lastOrNull { it.optLong("t") < System.currentTimeMillis() - 86400_000L }?.optInt("n")
        val ps = posts(c)
        return "Conectado como @${user(c)} · token vence em ${maxOf(0, ((expiresAt(c) - System.currentTimeMillis()) / 86400_000L).toInt())} dias (renova sozinho)\n" +
            (now?.let { "👥 $it seguidores" + (day?.let { d -> " (${if (now - d >= 0) "+" else ""}${now - d} em 24 h)" } ?: "") + "\n" } ?: "") +
            "${ps.size} post(s) com números · ${ps.count { it.optLong("send") > 0 }} ligados aos envios do app" +
            (lastSync(c).takeIf { it > 0 }?.let { "\nÚltima sincronização: " + android.text.format.DateFormat.format("dd/MM HH:mm", it) } ?: "") +
            lastErr(c).takeIf { it.isNotBlank() }?.let { "\n⚠ $it" }.orEmpty()
    }
}
