package com.leve.analise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONObject
import java.io.File
import kotlin.math.exp

/**
 * 📣 Divulgação do Instagram no meio dos posts do Threads.
 * - A cada N posts entra uma imagem com o seu @ do Instagram (uma das fotos que mais rendem + selo, ou suas próprias imagens).
 * - A legenda sai de um banco só de divulgação, que aprende com nota, curtidas e SEGUIDORES ganhos depois do post.
 * - As imagens de divulgação não entram na fila normal (não bagunçam o sorteio nem a saúde da fila).
 */
object Promo {
    private fun p(c: Context) = Store.prefs(c)
    private fun bank(c: Context) = File(c.filesDir, "divulgacao.json")
    fun baseDir(c: Context) = File(c.filesDir, "promo_base").apply { mkdirs() }

    fun on(c: Context) = p(c).getBoolean("promo_on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("promo_on", v).apply()
    fun every(c: Context) = p(c).getInt("promo_every", 6)
    fun setEvery(c: Context, v: Int) = p(c).edit().putInt("promo_every", v).apply()
    fun perDay(c: Context) = p(c).getInt("promo_day", 2)
    fun setPerDay(c: Context, v: Int) = p(c).edit().putInt("promo_day", v).apply()
    /** "melhores" (fotos que mais rendem + selo), "minhas" (suas imagens, como estão), "misturar". */
    fun imgMode(c: Context) = p(c).getString("promo_img", "misturar") ?: "misturar"
    fun setImgMode(c: Context, v: String) = p(c).edit().putString("promo_img", v).apply()
    /** "link" (instagram.com/voce), "arroba" (@voce), "imagem" (só na imagem). */
    fun linkMode(c: Context) = p(c).getString("promo_link", "link") ?: "link"
    fun setLinkMode(c: Context, v: String) = p(c).edit().putString("promo_link", v).apply()
    fun handle(c: Context) = Macro.igHandle(c)

    fun isPromo(id: String?) = id?.startsWith("promo_") == true
    fun bases(c: Context) = baseDir(c).listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedByDescending { it.lastModified() }

    // ---------------------------------------------------------------- legendas (banco que aprende)
    fun caps(c: Context): List<JSONObject> = JsonFile.read(bank(c))

    private val SEED = listOf(
        "tem mais de mim no insta 👀 {ig}",
        "o que não cabe aqui eu posto lá: {ig}",
        "quem chegou agora: me acha no insta {ig}",
        "lá no insta tem post todo dia ✨ {ig}",
        "se gostou daqui vai gostar mais de lá {ig}",
        "bastidores só no insta {ig}")

    fun addCaps(c: Context, l: List<String>, src: String) = JsonFile.edit(bank(c)) { b ->
        l.map { it.trim() }.filter { it.length in 6..280 }.forEach { t0 ->
            val t = if ("{ig}" in t0) t0 else "$t0 {ig}"
            if (b.none { norm(it.optString("t")) == norm(t) }) b.add(JSONObject().put("t", t).put("src", src).put("at", System.currentTimeMillis()))
        }
        while (b.size > 120) b.remove(b.filter { it.optInt("uses") == 0 }.minByOrNull { it.optLong("at") } ?: b.minByOrNull { score(it) }!!)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9à-ú{} ]"), "").replace(Regex("\\s+"), " ").trim()

    /** 0..1: nota (💎/🔥/😐/👎) + seguidores ganhos acima do normal. */
    fun score(o: JSONObject): Double {
        val n = o.optInt("top") + o.optInt("good") + o.optInt("meh") + o.optInt("bad")
        var q = (1.5 * o.optInt("top") + o.optInt("good") + 0.5 * o.optInt("meh") + 1.0) / (n + 0.5 * o.optInt("top") + 2.0)
        if (o.optInt("folN") > 0) q += (o.optDouble("fol") / o.optInt("folN")).coerceIn(-1.0, 3.0) * 0.12
        return q.coerceIn(0.0, 1.2)
    }
    fun tested(o: JSONObject) = o.optInt("top") + o.optInt("good") + o.optInt("meh") + o.optInt("bad") + o.optInt("folN")

    /** 🏆 A "legenda perfeita" de divulgação até agora. */
    fun perfect(c: Context): JSONObject? = caps(c).filter { tested(it) >= 2 }.maxByOrNull { score(it) } ?: caps(c).filter { tested(it) >= 1 }.maxByOrNull { score(it) }

    private fun pickCap(c: Context): String {
        val b = caps(c).ifEmpty { addCaps(c, SEED, "base"); caps(c) }
        val recent = SendLog.all(c).filter { isPromo(it.optString("id")) }.take(3).map { norm(it.optString("tpl")) }
        val cand = b.filter { norm(it.optString("t")) !in recent }.ifEmpty { b }
        val w = cand.map { o -> exp(5.0 * (score(o) - 0.5)) * (if (o.optInt("uses") == 0) 1.8 else 1.0) / (1.0 + 0.15 * o.optInt("uses")) }
        var r = Math.random() * w.sum(); var pick = cand.last()
        for (i in cand.indices) { r -= w[i]; if (r <= 0) { pick = cand[i]; break } }
        return pick.optString("t")
    }

    /** Troca o {ig} pelo link, pelo @ ou tira (quando o @ vai só na imagem). */
    fun fill(c: Context, tpl: String): String {
        val h = handle(c)
        val ig = when (linkMode(c)) { "arroba" -> "@$h"; "imagem" -> ""; else -> "instagram.com/$h" }
        return tpl.replace("{ig}", ig).replace(Regex("[ \\t]+"), " ").replace(Regex(" ([.,!?])"), "$1").trim().trimEnd(':').trim()
    }

    // ---------------------------------------------------------------- imagem
    private val STYLES = listOf("pilula", "faixa", "selo")
    private val CTAS = listOf("me segue no insta", "tem mais no insta", "segue lá no insta", "me acha no insta", "todo dia no insta")

    private fun styleStats(c: Context): JSONObject = try { JSONObject(p(c).getString("promo_styles", "{}")) } catch (e: Exception) { JSONObject() }
    private fun pickBy(c: Context, opts: List<String>): String {
        val s = styleStats(c)
        val w = opts.map { k -> val e = s.optJSONObject(k) ?: JSONObject(); exp(4.0 * (score(e) - 0.5)) + 0.15 }
        var r = Math.random() * w.sum(); for (i in opts.indices) { r -= w[i]; if (r <= 0) return opts[i] }
        return opts.last()
    }

    /** Desenha o selo com o @ (limpo: vidro escuro, cantos redondos, sem logo de marca). */
    fun render(c: Context, base: Bitmap, look: String, cta: String): Bitmap {
        val b = base.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(b); val w = b.width.toFloat(); val h = b.height.toFloat(); val u = w / 100f
        val at = "@" + handle(c)
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt(); typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        fun badge(cx: Float, cy: Float, r: Float) {
            dot.shader = LinearGradient(cx - r, cy + r, cx + r, cy - r, intArrayOf(0xFFFFB347.toInt(), 0xFFFF4F7A.toInt(), 0xFFB04BFF.toInt()), null, Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, r, dot)
            val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textSize = r * 1.25f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            cv.drawText("@", cx, cy + r * 0.43f, ap)
        }
        when (look) {
            "faixa" -> {
                val top = h * 0.70f
                cv.drawRect(0f, top, w, h, Paint().apply { shader = LinearGradient(0f, top, 0f, h, 0x00000000, 0xD0000000.toInt(), Shader.TileMode.CLAMP) })
                thin.textSize = 3.6f * u; bold.textSize = 6.2f * u
                val x = 7 * u; val y = h - 9 * u
                badge(x + 4.2f * u, y - 3.6f * u, 4.2f * u)
                cv.drawText(cta, x + 11 * u, y - 6.4f * u, thin)
                cv.drawText(at, x + 11 * u, y, bold)
            }
            "selo" -> {
                bold.textSize = 4.2f * u
                val tw = bold.measureText(at); val ph = 9 * u; val pw = tw + 15 * u
                val r = RectF(5 * u, 6 * u, 5 * u + pw, 6 * u + ph)
                cv.drawRoundRect(r, ph / 2, ph / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB80E0E12.toInt() })
                cv.drawRoundRect(r, ph / 2, ph / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 0.35f * u })
                badge(r.left + ph / 2, r.centerY(), ph * 0.32f)
                cv.drawText(at, r.left + ph + 1.5f * u, r.centerY() + 1.5f * u, bold)
            }
            else -> { // pílula embaixo, no centro
                thin.textSize = 3.3f * u; bold.textSize = 5.0f * u
                val tw = maxOf(bold.measureText(at), thin.measureText(cta)); val ph = 15 * u; val pw = tw + ph + 9 * u
                val r = RectF((w - pw) / 2, h - 9 * u - ph, (w + pw) / 2, h - 9 * u)
                cv.drawRoundRect(r, ph / 2, ph / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC40E0E12.toInt() })
                cv.drawRoundRect(r, ph / 2, ph / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 0.35f * u })
                badge(r.left + ph / 2, r.centerY(), ph * 0.30f)
                cv.drawText(cta, r.left + ph + 1 * u, r.centerY() - 1.0f * u, thin)
                cv.drawText(at, r.left + ph + 1 * u, r.centerY() + 4.6f * u, bold)
            }
        }
        return b
    }

    /** Foto base: uma das que mais rendem (que não saiu agora há pouco). */
    private fun bestBase(c: Context): Bitmap? {
        val rec = Captions.recentPhotos(c).take(10)
        val l = PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() && it.optString("id") !in rec }
        if (l.isEmpty()) return null
        val top = l.sortedByDescending { o -> ThreadsApi.photoScore(c, o.optString("id"))?.let { (Learn.mean(o) + 2 * it) / 3 } ?: Learn.mean(o) }.take(6)
        return BitmapFactory.decodeFile(PostQueue.imageFile(c, top.random().optString("id")).path)
    }

    class Made(val id: String, val caption: String, val tpl: String, val style: String)

    /** Monta uma divulgação (imagem salva + legenda). */
    fun make(c: Context, save: Boolean = true): Pair<Bitmap, Made>? {
        if (handle(c).isEmpty()) return null
        val own = bases(c)
        val useOwn = when (imgMode(c)) { "minhas" -> own.isNotEmpty(); "melhores" -> false; else -> own.isNotEmpty() && pickBy(c, listOf("minha", "auto")) == "minha" }
        val (bmp, style) = if (useOwn) {
            val recentBases = SendLog.all(c).filter { isPromo(it.optString("id")) }.take(3).map { it.optString("base") }
            val f = own.filter { it.name !in recentBases }.ifEmpty { own }.random()
            (BitmapFactory.decodeFile(f.path) ?: return null) to "minha:" + f.name
        } else {
            val base = bestBase(c) ?: own.randomOrNull()?.let { BitmapFactory.decodeFile(it.path) } ?: return null
            val st = pickBy(c, STYLES)
            render(c, base, st, CTAS.random()) to st
        }
        val tpl = pickCap(c)
        val cap = fill(c, tpl)
        val id = "promo_" + System.currentTimeMillis().toString(36)
        if (save) {
            PostQueue.imageFile(c, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            p(c).edit().putString("promo_pending", JSONObject().put("id", id).put("cap", cap).put("tpl", tpl).put("style", style).toString()).apply()
            cleanup(c)
        }
        return bmp to Made(id, cap, tpl, style)
    }

    private fun cleanup(c: Context) {
        val keep = SendLog.all(c).filter { isPromo(it.optString("id")) }.take(30).map { it.optString("id") }.toSet() + pending(c)?.optString("id").orEmpty()
        PostQueue.dir(c).listFiles { f -> f.name.startsWith("promo_") }.orEmpty().sortedByDescending { it.lastModified() }.drop(40).filter { it.nameWithoutExtension !in keep }.forEach { it.delete() }
    }

    private fun pending(c: Context): JSONObject? = try { p(c).getString("promo_pending", null)?.let { JSONObject(it) } } catch (e: Exception) { null }

    /** Quantos posts normais saíram desde a última divulgação. */
    fun sinceLast(c: Context): Int {
        val l = SendLog.all(c).filter { it.optString("kind") == "fila" }
        val i = l.indexOfFirst { isPromo(it.optString("id")) }
        return if (i < 0) l.size else i
    }
    fun today(c: Context): Int {
        val d0 = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        return SendLog.all(c).count { isPromo(it.optString("id")) && it.optLong("at") >= d0 }
    }
    fun due(c: Context): Boolean = on(c) && handle(c).isNotEmpty() && !Burst.inBlock(c) && today(c) < perDay(c) && sinceLast(c) >= every(c)

    /** Chamado no sorteio do post: se é hora, devolve a divulgação (a mesma até ela sair). */
    fun maybe(c: Context): Pair<String, String>? {
        if (!due(c)) return null
        return try {
            val pend = pending(c)
            if (pend != null && PostQueue.imageFile(c, pend.optString("id")).exists() && SendLog.all(c).none { it.optString("id") == pend.optString("id") })
                return pend.optString("id") to pend.optString("cap")
            make(c)?.second?.let { it.id to it.caption }
        } catch (e: Exception) { ErrLog.log(c, "divulgação", e); null }
    }

    /** Divulgação agora (botão). */
    fun now(c: Context): Pair<String, String>? = try { make(c)?.second?.let { it.id to it.caption } } catch (e: Exception) { ErrLog.log(c, "divulgação", e); null }

    /** Quando o post sai, guarda de qual modelo de legenda e de qual estilo ele veio. */
    fun onSent(c: Context, id: String) {
        val pend = pending(c)?.takeIf { it.optString("id") == id } ?: return
        JsonFile.edit(File(c.filesDir, "envios.json")) { l -> l.firstOrNull { it.optString("id") == id }?.apply {
            put("tpl", pend.optString("tpl")); put("style", pend.optString("style").substringBefore(':')); put("base", pend.optString("style").substringAfter(':', ""))
            put("fol0", ThreadsApi.followersNow(c) ?: -1)
        } }
        JsonFile.edit(bank(c)) { b -> b.firstOrNull { norm(it.optString("t")) == norm(pend.optString("tpl")) }?.let { it.put("uses", it.optInt("uses") + 1) } }
    }

    // ---------------------------------------------------------------- aprendizado (de hora em hora, no vigia)
    private fun rateInto(o: JSONObject, nota: String) { val k = if (nota in listOf("top", "good", "meh", "bad")) nota else return; o.put(k, o.optInt(k) + 1) }

    /** Ganho normal de seguidores em 24 h (pra comparar: a divulgação trouxe mais que o normal?). */
    private fun baseline(c: Context): Double {
        val f = ThreadsApi.followers(c).filter { System.currentTimeMillis() - it.optLong("t") < 21 * 86400_000L }
        if (f.size < 2) return 0.0
        val days = (f.last().optLong("t") - f.first().optLong("t")) / 86400_000.0
        return if (days < 1) 0.0 else (f.last().optInt("n") - f.first().optInt("n")) / days
    }
    private fun folAt(c: Context, t: Long): Int? = ThreadsApi.followers(c).filter { it.optLong("t") <= t }.lastOrNull()?.optInt("n")

    fun tick(c: Context) {
        val now = System.currentTimeMillis()
        val sends = SendLog.all(c).filter { isPromo(it.optString("id")) && it.optString("tpl").isNotEmpty() }
        val base = baseline(c)
        sends.forEach { e ->
            // nota (sua ou automática)
            if (e.optString("nota").isNotEmpty() && !e.optBoolean("pr")) {
                JsonFile.edit(bank(c)) { b -> b.firstOrNull { norm(it.optString("t")) == norm(e.optString("tpl")) }?.let { rateInto(it, e.optString("nota")) } }
                val s = styleStats(c); val k = if (e.optString("style") == "minha") "minha" else e.optString("style")
                val so = s.optJSONObject(k) ?: JSONObject(); rateInto(so, e.optString("nota")); s.put(k, so)
                if (k != "minha") { val a = s.optJSONObject("auto") ?: JSONObject(); rateInto(a, e.optString("nota")); s.put("auto", a) }
                p(c).edit().putString("promo_styles", s.toString()).apply()
                SendLog.mark(c, e.optLong("at"), "pr")
            }
            // seguidores 24 h depois (o que importa na divulgação)
            if (!e.optBoolean("pf") && now - e.optLong("at") > 24 * 3600_000L) {
                val fromPost = ThreadsApi.posts(c).firstOrNull { it.optLong("send") == e.optLong("at") }?.optDouble("fol", -1.0)?.takeIf { it >= 0 }
                val f0 = e.optInt("fol0", -1).takeIf { it > 0 } ?: folAt(c, e.optLong("at"))
                val f1 = folAt(c, e.optLong("at") + 26 * 3600_000L)
                val gain = fromPost ?: if (f0 != null && f1 != null && f1 >= f0) (f1 - f0) - base else null
                if (gain != null) {
                    JsonFile.edit(File(c.filesDir, "envios.json")) { l -> l.firstOrNull { it.optLong("at") == e.optLong("at") }?.put("gain", gain) }
                    val rel = if (fromPost != null) gain / maxOf(1.0, base / 3) else gain / maxOf(1.0, base)
                    JsonFile.edit(bank(c)) { b -> b.firstOrNull { norm(it.optString("t")) == norm(e.optString("tpl")) }?.let { it.put("fol", it.optDouble("fol") + rel); it.put("folN", it.optInt("folN") + 1); it.put("folRaw", it.optDouble("folRaw") + gain) } }
                }
                if (gain != null || now - e.optLong("at") > 4 * 86400_000L) SendLog.mark(c, e.optLong("at"), "pf")
            }
        }
        // banco acabando: pede legendas novas pra IA (dentro do limite diário)
        val fresh = caps(c).count { it.optInt("uses") == 0 }
        if (on(c) && fresh < 3 && Ai.key(c).isNotEmpty() && Auto.canSpend(c) && now - p(c).getLong("promo_ai", 0) > 12 * 3600_000L) {
            p(c).edit().putLong("promo_ai", now).apply()
            try { generate(c, 8) } catch (e: Exception) { ErrLog.log(c, "divulgação IA", e) }
        }
    }

    /** ✨ Legendas novas com a IA, a partir do que já funcionou (nota + seguidores). */
    fun generate(c: Context, n: Int = 8): Int {
        val b = caps(c)
        val tested = b.filter { tested(it) > 0 }.sortedByDescending { score(it) }
        val lines = tested.take(12).joinToString("\n") { o ->
            "- \"${o.optString("t")}\" → nota ${(score(o) * 100).toInt()}/100" + (if (o.optInt("folN") > 0) ", seguidores ${"%+.1f".format(o.optDouble("folRaw") / o.optInt("folN"))} vs normal" else "") + ", usada ${o.optInt("uses")}x"
        }
        val l = Ai.promoCaptions(c, n, lines, Captions.mine(c).take(10), CaptionDB.winners(c, 8).map { it.optString("t") })
        addCaps(c, l, "ia")
        return l.size
    }

    fun status(c: Context): String {
        if (handle(c).isEmpty()) return "⚠ Coloque seu @ do Instagram abaixo pra ligar."
        if (!on(c)) return "Desligada."
        val left = maxOf(0, every(c) - sinceLast(c))
        return "Ligada · 1 a cada ${every(c)} posts · " + (if (today(c) >= perDay(c)) "hoje já foram ${today(c)}/${perDay(c)}" else if (left == 0) "a próxima é AGORA" else "próxima daqui a $left post(s)") +
            " · hoje ${today(c)}/${perDay(c)}"
    }
}
