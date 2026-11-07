package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contextos dos vídeos crus (ex.: Academia, Praia, Espelho, Dança).
 * Cada cru tem um contexto; cada texto no vídeo pertence a um contexto ou ao Geral ("").
 * A descrição ajuda a IA a criar textos que combinam com aquele tipo de vídeo.
 */
object Contexts {
    const val GERAL = ""
    val EMOJIS = listOf("🏋", "🏖", "🪞", "💃", "🍹", "🌆", "🛏", "🚗", "👗", "🌿", "🎉", "☕")

    fun list(c: Context): MutableList<JSONObject> = try {
        val a = JSONArray(Store.prefs(c).getString("contextos", "[]")); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun save(c: Context, l: List<JSONObject>) { val a = JSONArray(); l.forEach { a.put(it) }; Store.prefs(c).edit().putString("contextos", a.toString()).apply() }

    fun get(c: Context, name: String): JSONObject? = if (name.isEmpty()) null else list(c).firstOrNull { it.optString("name") == name }

    fun names(c: Context) = list(c).map { it.optString("name") }

    fun add(c: Context, name0: String, desc: String = "", semTexto: Boolean? = null): String {
        val name = name0.trim().replaceFirstChar { it.uppercase() }.take(24)
        if (name.isEmpty()) return GERAL
        val l = list(c)
        l.firstOrNull { it.optString("name").equals(name, true) }?.let { return it.optString("name") }
        val o = JSONObject().put("name", name).put("desc", desc).put("emoji", if (isGeneric(name)) "🎬" else EMOJIS[l.size % EMOJIS.size])
        if (semTexto != null) o.put("semtexto", semTexto)
        l.add(o)
        save(c, l); return name
    }

    fun update(c: Context, name: String, f: (JSONObject) -> Unit) { val l = list(c); l.firstOrNull { it.optString("name") == name }?.let(f); save(c, l) }

    /** Renomeia: leva junto os vídeos crus, textos e vídeos prontos. */
    fun rename(c: Context, old: String, new0: String): String? {
        val nn = new0.trim().replaceFirstChar { it.uppercase() }.take(24)
        if (nn.isEmpty() || names(c).any { it.equals(nn, true) }) return null
        update(c, old) { it.put("name", nn) }
        Projects.list(c).filter { it.optString("ctx") == old }.forEach { p -> Projects.update(c, p.optString("id")) { it.put("ctx", nn) } }
        OverlayDB.moveCtx(c, old, nn)
        VideoExport.library(c).filter { it.optString("ctx") == old }.forEach { o -> VideoExport.updateLib(c, o.optString("id")) { it.put("ctx", nn) } }
        if (Store.prefs(c).getString("auto_ctx", null) == old) Store.prefs(c).edit().putString("auto_ctx", nn).apply()
        return nn
    }

    /** Apaga o contexto: crus e textos dele voltam pro Geral. */
    fun remove(c: Context, name: String) {
        save(c, list(c).filter { it.optString("name") != name })
        Projects.list(c).filter { it.optString("ctx") == name }.forEach { p -> Projects.update(c, p.optString("id")) { it.put("ctx", GERAL) } }
        OverlayDB.moveCtx(c, name, GERAL)
    }

    /** Categoria "sem texto": o vídeo já está pronto, o app só edita (cortes diferentes, modelo, sem metadados). */
    fun noText(c: Context, name: String): Boolean {
        if (name.isEmpty()) return false
        val o = list(c).firstOrNull { it.optString("name") == name } ?: return false
        return if (o.has("semtexto")) o.optBoolean("semtexto") else isGeneric(name) // "Genérico" já nasce sem texto
    }
    fun isGeneric(name: String) = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").startsWith("generic")
    /** Post sem legenda nenhuma. */
    fun noCaption(c: Context, name: String) = name.isNotEmpty() && list(c).firstOrNull { it.optString("name") == name }?.optBoolean("semlegenda") == true
    /** Quantas versões (cortes diferentes) de cada vídeo cru numa categoria sem texto. */
    fun versions(c: Context, name: String) = list(c).firstOrNull { it.optString("name") == name }?.optInt("versoes", 1)?.coerceIn(1, 5) ?: 1

    fun desc(c: Context, name: String) = list(c).firstOrNull { it.optString("name") == name }?.optString("desc").orEmpty()
    fun emoji(c: Context, name: String) = if (name.isEmpty()) "🌐" else list(c).firstOrNull { it.optString("name") == name }?.optString("emoji")?.ifEmpty { null } ?: "🏷"
    fun label(c: Context, name: String) = emoji(c, name) + " " + name.ifEmpty { "Geral" }
}
