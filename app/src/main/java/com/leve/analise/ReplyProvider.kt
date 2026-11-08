package com.leve.analise

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 💬 Respostas pra comentários, pedidas pelo Leve Teclado (só ele pode chamar).
 * A IA escreve no seu jeito (legendas e textos que você escreveu); sem chave/limite, usa respostas prontas.
 * O teclado só sugere: quem toca e envia é você.
 */
class ReplyProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "sugerir") return null
        if (callingPackage != "com.leve.teclado") return Bundle().apply { putString("erro", "não autorizado") }
        val c = context ?: return null
        val out = Bundle()
        val r = try {
            if (Ai.key(c).isNotEmpty() && Auto.aiUsedToday(c) < Auto.aiMax(c) + 10) Ai.replies(c, arg.orEmpty(), extras?.getString("app").orEmpty()) else null
        } catch (e: Exception) { null }
        out.putStringArray("r", (r?.takeIf { it.isNotEmpty() } ?: offline(arg.orEmpty())).take(3).toTypedArray())
        return out
    }

    private fun offline(ctx: String): List<String> {
        val base = listOf("obrigada, amor 🥰", "ai que fofo 🙈", "me conta mais 👀", "você é um amor", "hahaha para 😂", "gostou mesmo? 😏",
            "fico feliz que curtiu ❤️", "tô sem graça agora 🫣", "vem mais vezes então 😘")
        return base.shuffled().take(3)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
