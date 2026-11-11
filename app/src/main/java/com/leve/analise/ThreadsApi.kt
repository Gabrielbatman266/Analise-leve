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

    /** Só pra consultar (lista em cache): quem for ALTERAR usa JsonFile.read/edit. */
    fun posts(c: Context): List<JSONObject> = JsonFile.peek(file(c))
    fun followers(c: Context): List<JSONObject> = JsonFile.peek(folFile(c))
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
            val store = JsonFile.read(file(c)).associateBy { it.optString("id") }.toMutableMap()
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
                snap(o, now)
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
        val m = m0.filter { (k, v) -> v in (if (k.startsWith("vs_")) -1 else 0)..MAXV }.toMutableMap()
        val v = m["views"]
        if (v != null && v >= 30) listOf("likes", "replies", "reposts", "quotes", "shares", "saves", "fol", "viewers", "visits").forEach { k -> m[k]?.let { if (it > v * 1.2) m.remove(k) } }
        if (v == null) m["likes"]?.let { lk -> m.filterKeys { it != "likes" }.values.maxOrNull()?.let { other -> if (other > 0 && lk > other * 400) m.remove("likes") } }
        return m
    }

    private val QK = listOf("views", "likes", "replies", "reposts", "quotes", "shares", "saves", "visits", "viewers", "fol")

    /**
     * 🧹 Quarentena (roda sempre que os números são processados): tira número que não pode ser verdade.
     *  - o MESMO valor (ou views quase iguais) em 3+ posts diferentes = leitura de uma tela gravada em vários posts;
     *  - curtidas > 35% das views, respostas > 25%, seguidores > 20%;
     *  - muito acima do perfil: curtidas > 20x seus seguidores, ou 40x a sua mediana (views: 60x).
     */
    fun quarantine(c: Context): Int {
        var n = 0
        val fol = followersNow(c) ?: 0
        JsonFile.edit(file(c)) { l ->
            l.groupBy { it.optString("kind", "fila") == "video" }.forEach { (_, g) ->
                QK.forEach { k ->
                    val withK = g.filter { it.has(k) }
                    val vals = withK.map { it.optDouble(k) }.sorted(); val med = vals.getOrNull(vals.size / 2) ?: 0.0
                    // valores repetidos (ou views a menos de 0,1% um do outro) em 3+ posts
                    val big = withK.filter { it.optDouble(k) >= (if (k == "fol") 10.0 else 200.0) }.sortedBy { it.optDouble(k) }
                    val bad = HashSet<JSONObject>()
                    var i = 0
                    while (i < big.size) {
                        var j = i; while (j + 1 < big.size && big[j + 1].optDouble(k) - big[i].optDouble(k) <= maxOf(0.0, big[i].optDouble(k) * (if (k == "views") 0.001 else 0.0))) j++
                        if (j - i + 1 >= 3) for (x in i..j) bad += big[x]
                        i = j + 1
                    }
                    withK.forEach { o ->
                        val v = o.optDouble(k); val views = o.optDouble("views", -1.0)
                        val ratioBad = views >= 200 && k != "views" && v > views * (when (k) { "likes" -> 0.35; "replies" -> 0.25; "fol" -> 0.2; "visits" -> 0.6; else -> 0.5 })
                        val tooBig = (k == "likes" && fol > 0 && v > fol * 20) || (k == "replies" && fol > 0 && v > fol * 8) ||
                            (vals.size >= 6 && med >= 3 && v > med * (if (k == "views") 60 else 40))
                        if (o in bad || ratioBad || tooBig) { o.remove(k); o.put("limpo", (o.optString("limpo") + " " + k).trim()); n++ }
                    }
                }
            }
        }
        if (n > 0) { invalidate(); p(c).edit().putInt("quar_tot", p(c).getInt("quar_tot", 0) + n).apply() }
        return n
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

    /** Uma vez: tira da série os "seguidores" que eram de OUTROS perfis (bem longe dos vizinhos). */
    fun repairFollowers(c: Context) {
        if (!p(c).getBoolean("repair_fol2", false)) { p(c).edit().putBoolean("repair_fol2", true).remove("repair_fol").apply() } // limpa de novo (havia leituras erradas)
        if (p(c).getBoolean("repair_fol", false)) return
        JsonFile.edit(folFile(c)) { l ->
            // seguidores não despencam nem dobram de uma leitura pra outra: fica a sequência coerente MAIS LONGA
            // (cada registro entre -20% e +30% do anterior); o resto era número de outra tela/perfil
            val s = l.sortedBy { it.optLong("t") }
            if (s.size < 3) return@edit
            var best: List<JSONObject> = emptyList()
            s.map { it.optInt("n") }.distinct().forEach { start ->
                val keep = ArrayList<JSONObject>(); var last = start.toDouble()
                s.forEach { o -> val n = o.optInt("n"); if (o.optString("src") == "api" || (n >= last * 0.8 && n <= last * 1.3)) { keep += o; last = n.toDouble() } }
                if (keep.size > best.size) best = keep
            }
            if (best.size >= 2) { l.clear(); l.addAll(best) }
        }
        p(c).edit().putBoolean("repair_fol", true).apply()
    }

    /** Guarda uma "foto do momento" (idade do post + números) pra aprender a curva de crescimento dos SEUS posts. */
    private fun snap(o: JSONObject, now: Long) {
        val at = o.optLong("at"); if (at <= 0 || (o.optBoolean("orphan") && !o.optBoolean("atOk"))) return
        val age = (now - at) / 3600_000.0; if (age < 0.05) return
        val a = o.optJSONArray("snaps") ?: JSONArray().also { o.put("snaps", it) }
        val last = a.optJSONObject(a.length() - 1)
        val e = if (last != null && age - last.optDouble("a") < 0.5) last else JSONObject().also { a.put(it) }
        e.put("a", Math.round(age * 10) / 10.0).put("v", o.optInt("views", -1)).put("l", o.optInt("likes", -1))
        while (a.length() > 10) a.remove(1) // mantém a primeira e as mais novas
    }

    fun upsertLocal(c: Context, sendAt: Long, photoId: String, text: String, m0: Map<String, Int>, src: String, kind: String = "fila", force: Set<String> = emptySet()) {
        val m = sane(m0)
        if (m.isEmpty()) return
        JsonFile.edit(file(c)) { l ->
            val o = l.firstOrNull { it.optLong("send") == sendAt } ?: JSONObject().put("id", "$src:$sendAt").put("send", sendAt)
                .put(if (kind == "video") "video" else "photo", photoId).put("kind", kind)
                .put("at", sendAt).put("text", text).also { l.add(0, it) }
            m.forEach { (k, v) ->
                val dupe = k in QK && v >= 200 && l.count { it !== o && it.optInt(k, -1) == v } >= 2 // mesmo número em outros posts: tela errada
                if (!dupe && (k in force || k.startsWith("vs_") || v > o.optInt(k, -1))) o.put(k, v)
            }
            o.put("upd", System.currentTimeMillis()).put("src", if (o.optString("src").isEmpty()) src else o.optString("src"))
            snap(o, System.currentTimeMillis())
            while (l.size > 400) l.removeAt(l.size - 1)
        }
        p(c).edit().putLong("loc_last_$src", System.currentTimeMillis()).apply()
    }

    /** Post feito fora do app: guarda números pela legenda (sem foto ligada). */
    fun upsertOrphan(c: Context, text: String, m0: Map<String, Int>, src: String, kind: String = "fila", force: Set<String> = emptySet(), postedAt: Long = 0) {
        val m = sane(m0)
        if (m.isEmpty() || text.length < 8) return
        val key = (if (kind == "video") "orfv:" else "orf:") + norm(text).take(60).hashCode()
        JsonFile.edit(file(c)) { l ->
            val o = l.firstOrNull { it.optString("id") == key } ?: JSONObject().put("id", key).put("kind", kind).put("text", text).put("at", System.currentTimeMillis()).put("orphan", true).also { l.add(0, it) }
            m.forEach { (k, v) -> if (k in force || k.startsWith("vs_") || v > o.optInt(k, -1)) o.put(k, v) }
            if (postedAt > 0 && (!o.optBoolean("atOk") || kotlin.math.abs(o.optLong("at") - postedAt) > 36 * 3600_000L)) o.put("at", postedAt).put("atOk", true)
            o.put("upd", System.currentTimeMillis()).put("src", src)
            snap(o, System.currentTimeMillis())
            while (l.size > 400) l.removeAt(l.size - 1)
        }
        invalidate()
    }

    /** Seguidores lidos na tela do perfil (sem API). */
    fun addFollowers(c: Context, n: Int, src: String) {
        if (n <= 0) return
        JsonFile.edit(folFile(c)) { l ->
            // número muito longe dos últimos registros = leitura de outra tela/perfil (a API sempre vale)
            if (src != "api") {
                val ref = l.filter { System.currentTimeMillis() - it.optLong("t") < 14 * 86400_000L }.map { it.optInt("n") }.sorted()
                if (ref.size >= 3) { val med = ref[ref.size / 2]; if (n > med * 1.3 || n < med * 0.75) return@edit }
            }
            val last = l.lastOrNull()
            if (last != null && last.optInt("n") == n && System.currentTimeMillis() - last.optLong("t") < 20 * 60_000L) return@edit
            l.add(JSONObject().put("t", System.currentTimeMillis()).put("n", n).put("src", src)); while (l.size > 2000) l.removeAt(0)
        }
    }

    /** Sem rede: distribui seguidores e dá as notas com o que já se sabe (API, tela ou print). */
    fun process(c: Context) {
        try { repair(c) } catch (e: Exception) { }
        try { repairFollowers(c) } catch (e: Exception) { }
        try { quarantine(c); Likes.clean(c) } catch (e: Exception) { ErrLog.log(c, "quarentena", e) }
        val ps = JsonFile.read(file(c)); if (ps.isEmpty()) return
        attributeFollowers(c, ps)
        JsonFile.write(file(c), ps)
        // uma vez: a fórmula da nota mudou e números errados foram limpos → as notas automáticas são conferidas de novo
        if (!p(c).getBoolean("rerate_v3", false)) {
            JsonFile.edit(File(c.filesDir, "envios.json")) { l -> l.forEach { if (!it.optBoolean("mn")) it.remove("apiFinal") } }
            p(c).edit().putBoolean("rerate_v3", true).apply()
            try { downgradeTops(c) } catch (e: Exception) { ErrLog.log(c, "rever 💎", e) }
        }
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

    // ---------- curva de crescimento: quanto do número final um post já tem com X horas
    private val AGES = doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 24.0, 48.0, 96.0, 168.0)
    private val DEF_T = doubleArrayOf(.10, .18, .30, .48, .68, .85, .92, .98, 1.0, 1.0)  // Threads: quase tudo no 1º dia
    private val DEF_V = doubleArrayOf(.04, .07, .12, .20, .33, .50, .60, .78, .92, 1.0)  // Reels: cauda longa
    @Volatile private var curves: Pair<List<JSONObject>, Pair<DoubleArray, DoubleArray>>? = null

    private fun isVideo(o: JSONObject) = o.optString("kind", "fila") == "video"

    private fun learnCurve(ps: List<JSONObject>, video: Boolean): DoubleArray {
        val def = if (video) DEF_V else DEF_T
        val obs = Array(AGES.size) { ArrayList<Double>() }
        ps.filter { isVideo(it) == video }.forEach { o ->
            val a = o.optJSONArray("snaps") ?: return@forEach; if (a.length() < 2) return@forEach
            val last = a.getJSONObject(a.length() - 1); if (last.optDouble("a") < (if (video) 72.0 else 30.0)) return@forEach
            val key = if (last.optInt("v", -1) > 0) "v" else "l"; val fin = last.optInt(key, -1).toDouble(); if (fin < 10) return@forEach
            for (k in 0 until a.length() - 1) {
                val sn = a.getJSONObject(k); val v = sn.optInt(key, -1); if (v < 0) continue
                val la = Math.log(maxOf(0.25, sn.optDouble("a")))
                val idx = AGES.indices.minByOrNull { kotlin.math.abs(Math.log(AGES[it]) - la) } ?: continue
                obs[idx] += (v / fin).coerceIn(0.0, 1.0)
            }
        }
        val out = DoubleArray(AGES.size) { i -> val l = obs[i].sorted(); if (l.size >= 3) (l[l.size / 2] * l.size + def[i] * 2) / (l.size + 2) else def[i] }
        for (i in 1 until out.size) out[i] = maxOf(out[i], out[i - 1])
        return out
    }

    private fun curve(ps: List<JSONObject>, video: Boolean): DoubleArray {
        val c0 = curves
        val pair = if (c0 != null && c0.first === ps) c0.second else (learnCurve(ps, false) to learnCurve(ps, true)).also { curves = ps to it }
        return if (video) pair.second else pair.first
    }

    /** Fração (0..1) do resultado final que um post costuma ter com [ageH] horas. */
    private fun grown(cv: DoubleArray, ageH: Double): Double {
        if (ageH <= AGES[0]) return cv[0] * maxOf(0.2, ageH / AGES[0])
        if (ageH >= AGES.last()) return 1.0
        val i = AGES.indexOfFirst { it >= ageH }
        val t = (Math.log(ageH) - Math.log(AGES[i - 1])) / (Math.log(AGES[i]) - Math.log(AGES[i - 1]))
        return cv[i - 1] + (cv[i] - cv[i - 1]) * t
    }

    /** Com quantas horas de vida o post estava quando foi lido pela última vez (null = não dá pra saber). */
    fun readAge(m: JSONObject): Double? {
        if (m.optBoolean("orphan") && !m.optBoolean("atOk")) return null
        val at = m.optLong("at"); val upd = m.optLong("upd"); if (at <= 0 || upd <= 0) return null
        return maxOf(0.0, (upd - at) / 3600_000.0)
    }

    /** Posts lidos cedo demais (menos de 3 h de vida) e que já têm mais de 6 h: precisam de outra leitura pra nota ficar certa. */
    fun needReread(c: Context) = posts(c).filter { it.optLong("send") > 0 && System.currentTimeMillis() - it.optLong("at") in 6 * 3600_000L..(4 * 86400_000L) && (readAge(it) ?: 99.0) < 3.0 }

    // ---------- nota pelos números exatos
    /** Posição do valor entre os seus posts (0..1). Empates ficam no meio (antes um monte de zeros puxava tudo pra baixo). */
    private fun pct(v: Double, sorted: DoubleArray): Double {
        if (sorted.isEmpty()) return 0.5
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (sorted[mid] < v - 1e-9) lo = mid + 1 else hi = mid }
        var up = lo; while (up < sorted.size && sorted[up] <= v + 1e-9) up++
        return (lo + 0.5 * (up - lo)) / sorted.size
    }

    /**
     * Pesos de cada número, seguindo o que a Meta diz que usa:
     *  - Threads prevê curtir, abrir as respostas, SEGUIR e CLICAR NO PERFIL → seguidores, visitas e respostas pesam mais que curtidas;
     *  - Reels: alcance (reproduções), envios/salvamentos e curtidas POR ALCANCE (taxa), não só o número bruto.
     */
    private val W_T = listOf("fol" to .26, "replies" to .20, "views" to .18, "likes" to .14, "visits" to .12, "inter" to .10)
    private val W_V = listOf("views" to .25, "shares" to .18, "shareRate" to .15, "likes" to .10, "likeRate" to .10, "replies" to .09, "saves" to .08, "fol" to .05)

    private class Pool(val ps: List<JSONObject>, val cols: Map<String, DoubleArray>)
    @Volatile private var pools: Pair<List<JSONObject>, Map<Boolean, Pool>>? = null

    /** Valor do número já "levado" pra idade adulta do post (um post lido com 2 h não perde pra um lido com 3 dias). */
    private fun metric(o: JSONObject, k: String, cv: DoubleArray): Double? {
        val f = readAge(o)?.let { 1.0 / maxOf(0.15, grown(cv, it)) } ?: 1.0
        fun raw(key: String) = if (o.has(key)) o.optDouble(key, 0.0) else null
        return when (k) {
            "fol" -> raw("fol")
            "inter" -> listOf("reposts", "quotes", "shares", "saves").mapNotNull { raw(it) }.takeIf { it.isNotEmpty() }?.sum()?.times(f)
            "likeRate" -> raw("views")?.takeIf { it >= 50 }?.let { v -> raw("likes")?.div(v) }
            "shareRate" -> raw("views")?.takeIf { it >= 50 }?.let { v -> listOf("shares", "saves").mapNotNull { raw(it) }.takeIf { it.isNotEmpty() }?.sum()?.div(v) }
            else -> raw(k)?.times(f)
        }
    }

    private fun pool(ps0: List<JSONObject>, video: Boolean): Pool {
        val c0 = pools
        val map = if (c0 != null && c0.first === ps0) c0.second else {
            listOf(false, true).associateWith { vid ->
                val cv = curve(ps0, vid)
                val ps = ps0.filter { isVideo(it) == vid && (readAge(it) ?: 99.0) >= 1.0 } // lido com menos de 1 h não serve de régua
                Pool(ps, (if (vid) W_V else W_T).associate { (k, _) -> k to ps.mapNotNull { metric(it, k, cv) }.sorted().toDoubleArray() })
            }.also { pools = ps0 to it }
        }
        return map.getValue(video)
    }

    /**
     * Pontuação composta (0..1, relativa aos SEUS posts do mesmo tipo). Só entram os números que o post tem
     * (e que pelo menos 3 posts têm): número que falta não derruba a nota, o peso é redistribuído.
     */
    fun composite(c: Context, m: JSONObject, ps0: List<JSONObject> = posts(c)): Double {
        val video = isVideo(m); val pl = pool(ps0, video); val cv = curve(ps0, video)
        var sum = 0.0; var ws = 0.0
        (if (video) W_V else W_T).forEach { (k, w) ->
            val v = metric(m, k, cv) ?: return@forEach
            val col = pl.cols[k] ?: return@forEach; if (col.size < 3) return@forEach
            sum += w * pct(v, col); ws += w
        }
        return if (ws < 0.2) 0.5 else sum / ws
    }

    /** Dá a nota com os números: cedo só marca o que já bomba; a nota final espera uma leitura com 12 h+ de vida do post. */
    private fun rateFromApi(c: Context) {
        val all = posts(c); if (all.size < 5) return
        val sends = SendLog.all(c).associateBy { it.optLong("at") }
        val now = System.currentTimeMillis()
        all.filter { it.optLong("send") > 0 }.forEach { m ->
            val e = sends[m.optLong("send")] ?: return@forEach
            if (e.optBoolean("mn")) return@forEach // nota que VOCÊ deu: não mexo
            val age = (now - m.optLong("at")) / 3600_000.0
            val ra = readAge(m) ?: age
            val s = composite(c, m, all)
            val final = ra >= 12.0
            val k = when {
                ra < 1.0 -> null
                ra < 3.0 -> if (s >= 0.80) "good" else null // lido cedo: no máximo 🔥 (💎 só com números maduros)
                s >= 0.88 && outlier(c, m, all) -> "top"; s >= 0.62 -> "good"
                age >= 24 && s <= 0.30 -> "bad"; age >= 24 -> "meh"; else -> null
            } ?: return@forEach
            val cur = e.optString("nota")
            if (cur == k) { if (final && !e.optBoolean("apiFinal")) SendLog.mark(c, e.optLong("at"), "apiFinal"); return@forEach }
            if (cur.isNotEmpty() && (!final || e.optBoolean("apiFinal"))) return@forEach
            try { SendLog.rate(c, e, k); if (final) SendLog.mark(c, e.optLong("at"), "apiFinal") } catch (x: Exception) { ErrLog.log(c, "números: nota", x) }
        }
    }

    /** 💎 só se o post foi MUITO além do seu normal num número que importa (3x a mediana de views, seguidores ou respostas). */
    fun outlier(c: Context, m: JSONObject, all: List<JSONObject> = posts(c)): Boolean {
        val g = all.filter { isVideo(it) == isVideo(m) && (readAge(it) ?: 99.0) >= 3.0 && it !== m }
        if (g.size < 6) return false
        val cv = curve(all, isVideo(m))
        return listOf("views", "fol", "replies", "visits", "shares").any { k ->
            val v = metric(m, k, cv) ?: return@any false
            val col = g.mapNotNull { metric(it, k, cv) }.sorted(); if (col.size < 5) return@any false
            val med = col[col.size / 2]; med > 0 && v >= med * 3 && v >= (if (k == "fol") 3.0 else 20.0)
        }
    }

    /** Uma vez: 💎 automático que não tem número limpo que justifique volta pra 🔥 (o 💎 que VOCÊ deu fica). */
    private fun downgradeTops(c: Context) {
        val all = posts(c); val bySend = all.filter { it.optLong("send") > 0 }.associateBy { it.optLong("send") }
        SendLog.all(c).filter { it.optString("nota") == "top" && !it.optBoolean("mn") }.forEach { e ->
            val m = bySend[e.optLong("at")]
            if (m == null || !outlier(c, m, all) || composite(c, m, all) < 0.88) SendLog.rate(c, e, "good")
        }
    }

    /** Valor (0..1) pro modelo de previsão, quando tem número confiável (lido com 6 h+ de vida). */
    fun targetFor(c: Context, sendAt: Long): Double? {
        val ps = posts(c); if (ps.size < 5) return null
        val m = bySend(ps)[sendAt] ?: return null
        if ((readAge(m) ?: 99.0) < 6.0) return null
        return composite(c, m, ps)
    }
    @Volatile private var sendIdx: Pair<List<JSONObject>, Map<Long, JSONObject>>? = null
    private fun bySend(ps: List<JSONObject>): Map<Long, JSONObject> {
        val c0 = sendIdx; if (c0 != null && c0.first === ps) return c0.second
        return ps.filter { it.optLong("send") > 0 }.associateBy { it.optLong("send") }.also { sendIdx = ps to it }
    }

    // ---------- números → sorteio (fotos e legendas que rendem saem mais)
    @Volatile private var cacheAt = 0L
    @Volatile private var photoMap: Map<String, Double> = emptyMap()
    @Volatile private var capMap: Map<String, Double> = emptyMap()
    @Volatile private var videoMap: Map<String, Double> = emptyMap()
    private fun capKey(s: String) = norm(s).take(30)

    private fun maps(c: Context) {
        if (System.currentTimeMillis() - cacheAt < 60_000L) return
        val all = posts(c)
        val ps = all.filter { (it.optLong("send") > 0 || it.optBoolean("orphan")) && (readAge(it) ?: 99.0) >= 1.0 }
        if (ps.size < 3) { photoMap = emptyMap(); capMap = emptyMap(); videoMap = emptyMap(); cacheAt = System.currentTimeMillis(); return }
        val sc = ps.map { it to composite(c, it, all) }
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
    fun topVideos(c: Context, n: Int = 5) = posts(c).let { all -> all.filter { it.optString("kind") == "video" && it.optString("video").isNotEmpty() }.map { it to composite(c, it, all) } }.sortedByDescending { it.second }.take(n)
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
            tf.joinToString("\n") { "- \"${it.optString("text").replace('\n', ' ').take(90)}\" → +${"%.1f".format(it.optDouble("fol"))} seguidores, ${it.optInt("views")} visualizações" + (if (it.has("visits")) ", ${it.optInt("visits")} visitas ao perfil" else "") + ", ${it.optInt("likes")} curtidas, ${it.optInt("replies")} respostas" + (if (it.optInt("xviews") > 0) " (Meta: ${it.optInt("xviews") / 10.0}x mais views que o normal)" else "") } +
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
