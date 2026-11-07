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

    fun names(c: Context) = list(c).map { it.optString("name") }

    fun add(c: Context, name0: String, desc: String = ""): String {
        val name = name0.trim().replaceFirstChar { it.uppercase() }.take(24)
        if (name.isEmpty()) return GERAL
        val l = list(c)
        l.firstOrNull { it.optString("name").equals(name, true) }?.let { return it.optString("name") }
        l.add(JSONObject().put("name", name).put("desc", desc).put("emoji", EMOJIS[l.size % EMOJIS.size]))
        save(c, l); return name
    }

    fun update(c: Context, name: String, f: (JSONObject) -> Unit) { val l = list(c); l.firstOrNull { it.optString("name") == name }?.let(f); save(c, l) }

    /** Apaga o contexto: crus e textos dele voltam pro Geral. */
    fun remove(c: Context, name: String) {
        save(c, list(c).filter { it.optString("name") != name })
        Projects.list(c).filter { it.optString("ctx") == name }.forEach { p -> Projects.update(c, p.optString("id")) { it.put("ctx", GERAL) } }
        OverlayDB.moveCtx(c, name, GERAL)
    }

    fun desc(c: Context, name: String) = list(c).firstOrNull { it.optString("name") == name }?.optString("desc").orEmpty()
    fun emoji(c: Context, name: String) = if (name.isEmpty()) "🌐" else list(c).firstOrNull { it.optString("name") == name }?.optString("emoji")?.ifEmpty { null } ?: "🏷"
    fun label(c: Context, name: String) = emoji(c, name) + " " + name.ifEmpty { "Geral" }
}
