package com.leve.analise

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 👩 Várias modelos no mesmo app. Cada uma tem o SEU banco: fotos, vídeos, legendas, notas, números, aprendizado,
 * fila, conta do Threads/Instagram. Trocar de modelo guarda o banco da atual numa pasta e põe o da outra no lugar
 * (o app reabre sozinho). A chave da IA, as atualizações e o que você ensinou ao leitor de tela valem pra todas.
 *
 * Entre modelos dá pra: puxar as legendas da outra (trocando o nome sozinho) e começar o aprendizado
 * a partir do que a outra já aprendeu (o modelo de previsão parte daí e vai se ajustando aos números da nova).
 */
object Profiles {
    private fun reg(c: Context): SharedPreferences = c.getSharedPreferences("perfis", Context.MODE_PRIVATE)
    private fun root(c: Context) = File(c.filesDir, "perfis").apply { mkdirs() }
    private fun dir(c: Context, id: String) = File(root(c), id)

    /** Ficam iguais pra todas as modelos. */
    private val GLOBAL = setOf("provider", "key", "model", "modelos", "img_model", "ai_day_max", "ai_day", "ai_used", "auto_update", "upd_last", "upd_notified",
        "upd_skip", "tab", "bubble_x", "bubble_y", "readbtn_y", "reader_rules", "read_btn", "reader_toast", "reader_pause", "quality", "clip_ttl", "ed_panel")
    private fun global(k: String) = k in GLOBAL || k.startsWith("fold_")

    /** Estado (histórico, filas, contas) que NÃO vai pra modelo nova quando ela copia os ajustes. */
    private val STATE = listOf("rec_", "vid_", "pick_", "lab_", "aud_", "promo_pending", "promo_first", "promo_styles", "promo_tune_last", "study_", "quar_", "repair_",
        "rerate_", "th_", "tela_", "cf_", "day_", "growth_", "health_", "hs_", "macro_last", "macro_start", "musica_score", "pares", "looks", "combo_regras",
        "orig_pending", "sat_last", "shots_total", "prints_lidos", "watch_", "vwatch_", "in_", "tn_log", "tn_seen", "tn_live", "stock_ask", "ig_handle",
        "next_post_at", "gallery_", "pred_", "sel_", "ab_", "bk_at", "upd_", "flow_ideas", "auto_var_at", "ig_last_copy", "clip_last", "mus_extra_until", "cap_extra")

    /** Arquivos que não são da modelo (ficam no lugar). */
    private val KEEP = setOf("perfis", "musicas", "erros.json", "backup_ultimo.zip")

    // ---------------------------------------------------------------- cadastro
    fun list(c: Context): List<JSONObject> {
        val a = try { JSONArray(reg(c).getString("lista", "[]")) } catch (e: Exception) { JSONArray() }
        val l = (0 until a.length()).map { a.getJSONObject(it) }
        if (l.isNotEmpty()) return l
        val first = JSONObject().put("id", "m1").put("name", "Modelo 1").put("model", "").put("prior", "").put("at", System.currentTimeMillis())
        saveList(c, listOf(first)); reg(c).edit().putString("atual", "m1").apply()
        return listOf(first)
    }
    private fun saveList(c: Context, l: List<JSONObject>) = reg(c).edit().putString("lista", JSONArray().apply { l.forEach { put(it) } }.toString()).commit()
    fun currentId(c: Context) = reg(c).getString("atual", null) ?: list(c).first().optString("id")
    fun current(c: Context): JSONObject = list(c).firstOrNull { it.optString("id") == currentId(c) } ?: list(c).first()
    fun byId(c: Context, id: String) = list(c).firstOrNull { it.optString("id") == id }
    fun others(c: Context) = list(c).filter { it.optString("id") != currentId(c) }

    /** Nome da modelo como aparece nas legendas (ex.: "Flavia"). */
    fun modelName(c: Context, id: String = currentId(c)) = byId(c, id)?.optString("model").orEmpty()
    fun update(c: Context, id: String = currentId(c), f: (JSONObject) -> Unit) { val l = list(c); l.firstOrNull { it.optString("id") == id }?.let(f); saveList(c, l) }

    fun busy() = AutoProducer.running || VideoExport.busy()

    // ---------------------------------------------------------------- trocar de modelo
    private fun prefsToJson(p: SharedPreferences, keep: (String) -> Boolean): JSONObject {
        val o = JSONObject()
        p.all.forEach { (k, v) -> if (!keep(k)) return@forEach
            o.put(k, when (v) {
                is String -> JSONObject().put("t", "s").put("v", v); is Int -> JSONObject().put("t", "i").put("v", v); is Long -> JSONObject().put("t", "l").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v); is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() })); else -> return@forEach
            })
        }
        return o
    }
    private fun jsonToPrefs(e: SharedPreferences.Editor, o: JSONObject) {
        o.keys().forEach { k -> val x = o.getJSONObject(k)
            when (x.optString("t")) {
                "s" -> e.putString(k, x.optString("v")); "i" -> e.putInt(k, x.optInt("v")); "l" -> e.putLong(k, x.optLong("v"))
                "b" -> e.putBoolean(k, x.optBoolean("v")); "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                "set" -> e.putStringSet(k, x.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
            }
        }
    }

    /** Guarda o banco da modelo atual na pasta dela e coloca o da outra no lugar. Devolve o erro, se houver. */
    @Synchronized fun switchTo(c: Context, id: String): String? {
        val cur = currentId(c); if (id == cur) return null
        if (byId(c, id) == null) return "Modelo não encontrada"
        if (busy()) return "Espere terminar a produção de vídeos"
        reg(c).edit().putString("troca", "$cur>$id").putInt("fase", 1).commit() // diário: se cair no meio, termina ou desfaz ao abrir
        phase1(c, cur)
        reg(c).edit().putInt("fase", 2).commit()
        phase2(c, id)
        return null
    }

    private fun move(from: File, to: File) { if (to.exists()) to.deleteRecursively(); if (!from.renameTo(to)) { from.copyRecursively(to, true); from.deleteRecursively() } }

    /** 1) guarda a atual na pasta dela. */
    private fun phase1(c: Context, cur: String) {
        val curFiles = File(dir(c, cur), "files").apply { mkdirs() }
        c.filesDir.listFiles().orEmpty().filter { it.name !in KEEP }.forEach { move(it, File(curFiles, it.name)) }
        File(dir(c, cur), "prefs.json").writeText(prefsToJson(Store.prefs(c)) { !global(it) }.toString())
    }

    /** 2) põe a outra no lugar. */
    private fun phase2(c: Context, id: String) {
        File(dir(c, id), "files").listFiles().orEmpty().forEach { move(it, File(c.filesDir, it.name)) }
        val p = Store.prefs(c); val e = p.edit(); p.all.keys.filter { !global(it) }.forEach { e.remove(it) }
        File(dir(c, id), "prefs.json").takeIf { it.exists() }?.let { try { jsonToPrefs(e, JSONObject(it.readText())) } catch (x: Exception) { } }
        e.commit()
        reg(c).edit().putString("atual", id).remove("troca").remove("fase").commit()
    }

    /** Ao abrir: se uma troca foi interrompida (app fechou no meio), termina (fase 2) ou desfaz (fase 1). */
    fun heal(c: Context) {
        val t = reg(c).getString("troca", null) ?: return
        val (cur, id) = t.split(">").let { (it.getOrNull(0) ?: return) to (it.getOrNull(1) ?: return) }
        try {
            if (reg(c).getInt("fase", 1) >= 2) phase2(c, id)
            else { File(dir(c, cur), "files").listFiles().orEmpty().forEach { move(it, File(c.filesDir, it.name)) }; reg(c).edit().remove("troca").remove("fase").commit() }
        } catch (e: Exception) { }
    }

    /** Cria uma modelo nova (vazia, com os mesmos ajustes se [copySettings]) e já troca pra ela. */
    fun create(c: Context, name: String, model: String, copySettings: Boolean, learnFrom: String?): String? {
        if (busy()) return "Espere terminar a produção de vídeos"
        val id = "m" + System.currentTimeMillis().toString(36)
        val d = dir(c, id); File(d, "files").mkdirs()
        val start = if (copySettings) prefsToJson(Store.prefs(c)) { k -> !global(k) && STATE.none { k.startsWith(it) } } else JSONObject()
        File(d, "prefs.json").writeText(start.toString())
        saveList(c, list(c) + JSONObject().put("id", id).put("name", name.ifBlank { "Modelo ${list(c).size + 1}" }).put("model", model.trim()).put("prior", learnFrom ?: "").put("at", System.currentTimeMillis()))
        return switchTo(c, id)
    }

    /** Apaga uma modelo (não a atual). */
    fun delete(c: Context, id: String): Boolean {
        if (id == currentId(c)) return false
        dir(c, id).deleteRecursively()
        saveList(c, list(c).filter { it.optString("id") != id }.onEach { if (it.optString("prior") == id) it.put("prior", "") })
        return true
    }

    /** Reabre o app num processo novo (tudo que estava na memória era da outra modelo). */
    fun restart(a: Activity) {
        a.startActivity(Intent(a, PhoenixActivity::class.java).putExtra("pid", android.os.Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        a.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    // ---------------------------------------------------------------- aprendizado transferido
    private fun weightsFile(c: Context) = File(c.filesDir, "pesos.json")
    fun saveWeights(c: Context, w: Map<String, Double>) = try { weightsFile(c).writeText(JSONObject(w.filterValues { Math.abs(it) > 0.002 }).toString()) } catch (e: Exception) { }

    private fun priorFile(c: Context): File? {
        val pid = current(c).optString("prior").takeIf { it.isNotEmpty() && it != currentId(c) } ?: return null
        return File(File(dir(c, pid), "files"), "pesos.json").takeIf { it.exists() }
    }
    @Volatile private var priorCache: Pair<String, Map<String, Double>>? = null
    /** O que a outra modelo aprendeu (pesos do modelo de previsão), pra esta começar daí. */
    fun prior(c: Context): Map<String, Double> {
        val f = priorFile(c) ?: return emptyMap()
        val sig = f.path + f.lastModified()
        priorCache?.takeIf { it.first == sig }?.let { return it.second }
        val m = try { val o = JSONObject(f.readText()); o.keys().asSequence().associateWith { o.optDouble(it) } } catch (e: Exception) { emptyMap() }
        priorCache = sig to m; return m
    }
    fun priorSig(c: Context) = priorFile(c)?.let { it.path + it.lastModified() } ?: ""
    fun priorName(c: Context) = current(c).optString("prior").takeIf { priorFile(c) != null }?.let { byId(c, it)?.optString("name") }.orEmpty()

    // ---------------------------------------------------------------- legendas de outra modelo
    private fun readOther(c: Context, id: String, name: String): JSONArray = try { JSONArray(File(File(dir(c, id), "files"), name).readText()) } catch (e: Exception) { JSONArray() }

    /** Puxa as legendas (posts, vídeos e divulgação) de outra modelo, trocando o nome dela pelo desta. */
    fun importCaptions(c: Context, fromId: String, swapName: Boolean): String {
        val from = modelName(c, fromId); val to = modelName(c)
        fun fix(t: String) = if (swapName && from.isNotBlank() && to.isNotBlank()) Names.swap(t, from, to) else t
        var n = 0; var w = 0
        val caps = readOther(c, fromId, "legendas_db.json")
        for (i in 0 until caps.length()) {
            val o = caps.optJSONObject(i) ?: continue; val t = fix(o.optString("t")); if (t.isBlank()) continue
            if (CaptionDB.find(c, t) == null) { CaptionDB.add(c, t, "importada"); n++
                // a que bombou lá começa com 1 voto bom aqui (só um empurrão: os números daqui decidem)
                if (o.optInt("good") > 0 && CaptionDB.mean(o) > 0.6) { CaptionDB.rate(c, t, "good"); w++ } }
        }
        val ov = readOther(c, fromId, "overlay_db.json"); var nv = 0
        for (i in 0 until ov.length()) { val o = ov.optJSONObject(i) ?: continue; val t = fix(o.optString("t")); if (t.isNotBlank() && OverlayDB.addIfNew(c, t, o.optString("ctx"))) nv++ }
        val pr = readOther(c, fromId, "divulgacao.json")
        val promo = (0 until pr.length()).mapNotNull { pr.optJSONObject(it)?.optString("t")?.let(::fix) }.filter { it.isNotBlank() }
        if (promo.isNotEmpty()) Promo.addCaps(c, promo, "importada")
        val nm = byId(c, fromId)?.optString("name") ?: "a outra"
        return "✓ De $nm: $n legenda(s) nova(s) ($w que bombaram lá começam com vantagem), $nv texto(s) de vídeo e ${promo.size} de divulgação" +
            (if (swapName && from.isNotBlank() && to.isNotBlank()) " · \"$from\" virou \"$to\"" else "")
    }
}

/** Reabre o app num processo novo depois de trocar de modelo. */
class PhoenixActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra("pid", -1)
        if (pid > 0) android.os.Process.killProcess(pid)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Runtime.getRuntime().exit(0)
    }
}

/**
 * 🔁 Trocar nome nas legendas (ex.: "Flavia marcou você" → "Valentina marcou você") e ponte com o ChatGPT:
 * copia o banco numerado em partes, você cola no ChatGPT, cola a resposta de volta e o app troca linha por linha.
 */
object Names {
    private val NL = "⏎"

    /** Troca o nome inteiro (não pedaço de palavra), mantendo maiúsculas: FLAVIA→VALENTINA, Flavia→Valentina, flavia→valentina. */
    fun swap(t: String, from: String, to: String): String {
        if (from.isBlank()) return t
        val re = Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(from.trim()) + "(?![\\p{L}\\p{N}])") // (?iu): ignora maiúscula também com acento (Flávia/FLÁVIA)
        return re.replace(t) { m ->
            val s = m.value
            when { s == s.uppercase() && s != s.lowercase() -> to.uppercase(); s.first().isUpperCase() -> to.replaceFirstChar { it.uppercase() }; else -> to.lowercase() }
        }
    }

    /** Todos os textos do banco desta modelo (legendas, fila, vídeos, divulgação), sem repetir. */
    fun bank(c: Context): List<String> {
        val s = LinkedHashSet<String>()
        CaptionDB.all(c).forEach { it.optString("t").takeIf { t -> t.isNotBlank() }?.let(s::add) }
        PostQueue.load(c).forEach { o -> o.optJSONArray("legendas")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(s::add) }; o.optString("escolhida").takeIf { it.isNotBlank() }?.let(s::add) }
        OverlayDB.all(c).forEach { it.optString("t").takeIf { t -> t.isNotBlank() }?.let(s::add) }
        Promo.caps(c).forEach { it.optString("t").takeIf { t -> t.isNotBlank() }?.let(s::add) }
        return s.toList()
    }

    /** Aplica antigo → novo em todo o banco. Devolve quantos textos mudaram. */
    fun applyMap(c: Context, map: Map<String, String>): Int {
        val m = map.filter { (a, b) -> a != b && b.isNotBlank() }; if (m.isEmpty()) return 0
        val touched = HashSet<String>()
        fun tr(t: String): String = m[t]?.also { touched += t } ?: t
        CaptionDB.mapTexts(c) { tr(it) }
        OverlayDB.mapTexts(c) { tr(it) }
        JsonFile.edit(File(c.filesDir, "posts_queue.json")) { l -> l.forEach { o ->
            o.optJSONArray("legendas")?.let { a -> val na = JSONArray(); for (i in 0 until a.length()) na.put(tr(a.optString(i))); o.put("legendas", na) }
            if (o.has("escolhida")) o.put("escolhida", tr(o.optString("escolhida")))
        } }
        JsonFile.edit(File(c.filesDir, "divulgacao.json")) { l -> l.forEach { o -> o.put("t", tr(o.optString("t"))) } }
        JsonFile.edit(File(c.filesDir, "videos.json")) { l -> l.forEach { o -> if (o.has("legenda")) o.put("legenda", tr(o.optString("legenda"))) } }
        return touched.size
    }

    /** Quantos textos têm o nome (pra mostrar antes de trocar). */
    fun countWith(c: Context, from: String) = if (from.isBlank()) 0 else bank(c).count { swap(it, from, "\u0000") != it }

    fun renameAll(c: Context, from: String, to: String): Int = applyMap(c, bank(c).associateWith { swap(it, from, to) })

    // ---------------- ChatGPT
    const val PART = 150
    private fun exportFile(c: Context) = File(c.filesDir, "chatgpt_export.json")

    /** Prepara a lista numerada (guarda a ordem pra conseguir trocar de volta). Devolve o número de partes. */
    fun prepare(c: Context): Int {
        val l = bank(c); exportFile(c).writeText(JSONArray(l).toString())
        return (l.size + PART - 1) / PART
    }
    private fun exported(c: Context): List<String> = try { val a = JSONArray(exportFile(c).readText()); (0 until a.length()).map { a.optString(it) } } catch (e: Exception) { emptyList() }
    fun parts(c: Context) = (exported(c).size + PART - 1) / PART

    fun partText(c: Context, part: Int, ask: String): String {
        val l = exported(c); val from = part * PART; val to = minOf(l.size, from + PART)
        val head = """Vou te mandar uma lista numerada de legendas de posts (parte ${part + 1} de ${parts(c)}).
Pedido: ${ask.trim()}
Regras OBRIGATÓRIAS:
- Devolva TODAS as linhas, com o MESMO número na frente, no formato: N) texto
- Uma legenda por linha. Onde tiver $NL é quebra de linha: mantenha o $NL no mesmo lugar.
- Não junte, não pule e não acrescente linhas. Mantenha o estilo, os emojis e o tamanho de cada uma.
- Responda SÓ com a lista (pode ser dentro de um bloco de código), sem comentários.

"""
        return head + (from until to).joinToString("\n") { "${it + 1}) " + l[it].replace("\r", "").replace("\n", NL) }
    }

    /** Lê a resposta colada do ChatGPT e troca no banco. Devolve (trocadas, linhas lidas, linhas que não reconheci). */
    fun importAnswer(c: Context, answer: String): Triple<Int, Int, Int> {
        val l = exported(c); if (l.isEmpty()) return Triple(0, 0, 0)
        val re = Regex("^\\s*[`*_]*(\\d{1,6})\\s*[).:\\-–—]\\s*(.*)$")
        val map = HashMap<String, String>(); var read = 0; var bad = 0
        answer.lines().forEach { line ->
            if (line.isBlank() || line.trim().startsWith("```")) return@forEach
            val m = re.find(line) ?: run { bad++; return@forEach }
            val i = m.groupValues[1].toInt() - 1
            val nt = m.groupValues[2].trim().trim('`').replace(NL, "\n").trim()
            if (i !in l.indices || nt.isEmpty()) { bad++; return@forEach }
            read++; if (nt != l[i]) map[l[i]] = nt
        }
        return Triple(applyMap(c, map), read, bad)
    }
}
