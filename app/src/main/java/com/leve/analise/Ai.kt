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

    private fun small(f: File): ByteArray {
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path, o) ?: return shrink(f)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray()
    }

    /** ✨ Variações novas das legendas que bombaram. */
    fun variations(c: Context, wins: List<String>, flops: List<String>, mine: List<String>, n: Int): List<String> {
        val prompt = """Você escreve legendas para posts de foto no Threads de uma criadora de conteúdo, em português do Brasil.
Estas legendas BOMBARAM no perfil dela:
${wins.joinToString("\n") { "- " + it.replace('\n', ' ') }}
${if (flops.isNotEmpty()) "Estas FLOPARAM (evite esse tom/assunto):\n" + flops.joinToString("\n") { "- " + it.replace('\n', ' ') } else ""}
${if (mine.isNotEmpty()) "Jeito dela de escrever:\n" + mine.joinToString("\n") { "- " + it.replace('\n', ' ') } else ""}
${Captions.learnedText(c)}
Crie $n legendas NOVAS que sejam variações das que bombaram: mesmo tema, mesma estrutura, mesmo tipo de gancho e tamanho, com outras palavras. Nada copiado igual.
Regras: naturais, no máximo 1 emoji, sem hashtags, nada explícito, nada que finja ser outra pessoa real.
Responda só com JSON: {"legendas":["..."]}"""
        val arr = parseObj(ask(c, prompt, emptyList())).optJSONArray("legendas") ?: throw AiException(-2, "Resposta que não entendi")
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(n)
    }

    /**
     * 🧠 Analisa COMBINAÇÕES: posts avaliados (foto + legenda + nota) → regras do que combina com o quê.
     * pairs: (foto, legenda, "good"/"meh"/"bad")
     */
    fun learnCombos(c: Context, pairs: List<Triple<File, String, String>>, extra: String = ""): List<String> {
        val list = pairs.mapIndexed { i, (_, cap, k) ->
            "${i + 1}. nota: " + (when (k) { "top" -> "💎 VIRALIZOU"; "good" -> "🔥 BOMBOU"; "bad" -> "👎 FLOPOU"; else -> "😐 normal" }) + " — legenda: \"" + cap.replace('\n', ' ') + "\""
        }.joinToString("\n")
        val prompt = """Você é analista de conteúdo do Threads. Estão anexadas ${pairs.size} fotos de posts de uma criadora, na ordem, cada uma com a legenda usada e como foi:
$list

$extra
IMPORTANTE: a mesma foto pode ir mal numa postagem e bem em outra (horário, dia, algoritmo). Use o histórico pra separar o que é do
CONTEÚDO do que é do HORÁRIO/SORTE, e diga se vale repostar o que flopou e em que horário.

Compare as que bombaram com as que floparam e descubra o que funciona na COMBINAÇÃO foto + legenda:
tipo de foto (pose, enquadramento, cenário, roupa, luz, clima — sem descrever rosto nem identidade) × tipo de legenda (tom, tamanho, gancho, pergunta, humor…).
Escreva de 4 a 8 regras curtas, práticas e específicas, no formato "foto X + legenda Y = bomba/flopa", e regras gerais se tiver.
Se houver poucos dados, diga o que parece mais provável, sem inventar certeza.
Responda só com JSON: {"regras":["...","..."]}"""
        val imgs = pairs.map { Base64.encodeToString(small(it.first), Base64.NO_WRAP) }
        val arr = parseObj(ask(c, prompt, imgs)).optJSONArray("regras") ?: throw AiException(-2, "Resposta que não entendi")
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(10)
    }

    fun imageModel(c: Context) = Store.prefs(c).getString("img_model", "gemini-2.5-flash-image")?.trim().orEmpty().ifEmpty { "gemini-2.5-flash-image" }

    /** 🧽 Edita uma foto com o Gemini (remover objeto etc.). Devolve o JPG/PNG novo. */
    fun editImage(c: Context, img: File, instruction: String): ByteArray {
        if (provider(c) != "gemini") throw AiException(-4, "Remover objeto precisa da IA do Google (Gemini) nos Ajustes.")
        val k = key(c); if (k.isEmpty()) throw AiException(401, "Coloque sua chave de API nas configurações")
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
        var bmp = android.graphics.BitmapFactory.decodeFile(img.path, o) ?: throw AiException(-2, "Não consegui abrir a foto")
        if (maxOf(bmp.width, bmp.height) > 2048) { val sc = 2048f / maxOf(bmp.width, bmp.height); bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) }
        val bo = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bo)
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP))))
            .put(JSONObject().put("text", instruction))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(imageModel(c), "UTF-8") + ":generateContent"
        Auto.countAi(c)
        val res = post(url, body.toString(), mapOf("x-goog-api-key" to k))
        val ps = JSONObject(res).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(-2, "A IA não devolveu imagem (talvez ela tenha recusado essa edição)")
        for (i in 0 until ps.length()) {
            val d = ps.getJSONObject(i).let { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") } ?: continue
            val data = d.optString("data"); if (data.isNotEmpty()) {
                // regrava como JPG limpo (sem metadados)
                val raw = Base64.decode(data, Base64.DEFAULT)
                val b = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
                val out = java.io.ByteArrayOutputStream(); b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                return out.toByteArray()
            }
        }
        throw AiException(-2, "A IA não devolveu imagem (talvez ela tenha recusado essa edição)")
    }

    /** 📸 Lê um PRINT do Threads (insights de um post ou o perfil) e devolve os números. */
    fun readInsights(c: Context, img: File): JSONObject {
        val prompt = """Este é um print do app Threads (tela de insights/atividade de um post, ou o perfil).
Extraia os números exatamente como aparecem (converta "1,2 mil" pra 1200). Se não houver, use null.
Também copie o começo do texto/legenda do post, se aparecer.
Responda só com JSON: {"texto":"...","views":0,"likes":0,"replies":0,"reposts":0,"quotes":0,"shares":0,"follows":0,"seguidores_perfil":0}"""
        return parseObj(ask(c, prompt, listOf(Base64.encodeToString(shrink(img), Base64.NO_WRAP))))
    }

    /** 💬 3 respostas curtas pra um comentário, no jeito da criadora. */
    fun replies(c: Context, ctx: String, app: String): List<String> {
        val mine = (Captions.mine(c).take(10) + OverlayDB.all(c, null).filter { it.optString("src") == "meu" }.take(8).map { it.optString("t") })
        val prompt = """Você ajuda uma criadora de conteúdo a responder comentários no ${if (app.contains("barcelona")) "Threads" else "Instagram"}, em português do Brasil.
Contexto da caixa de resposta (pode estar vazio):
$ctx
${if (mine.isNotEmpty()) "Jeito dela de escrever (imite tom, tamanho e emojis):\n" + mine.joinToString("\n") { "- " + it.replace('\n', ' ') } else ""}
Escreva 3 respostas curtas e diferentes (até 12 palavras), simpáticas e que puxem conversa/engajamento. Nada explícito, sem promessas, sem links.
Se não houver comentário no contexto, escreva respostas que sirvam pra qualquer elogio.
Responda só com JSON: {"respostas":["...","...","..."]}"""
        val arr = parseObj(ask(c, prompt, emptyList())).optJSONArray("respostas") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(3)
    }

    /** 💡 Prompts pro Google Flow no estilo do que bombou (as primeiras [nPhotos] imagens são fotos; o resto, vídeos). */
    fun flowIdeas(c: Context, imgs: List<File>, nPhotos: Int, rules: List<String>): List<String> {
        val prompt = """Estas são imagens de posts que BOMBARAM no perfil de uma criadora (as $nPhotos primeiras são fotos; as outras, quadros de vídeos curtos).
${if (rules.isNotEmpty()) "O que já se sabe que funciona:\n" + rules.joinToString("\n") { "- $it" } else ""}
Escreva 6 prompts em inglês pro Google Flow (gerador de imagem/vídeo) pra criar conteúdo NOVO no mesmo estilo:
3 de foto e 3 de vídeo curto vertical (9:16). Varie cenário, roupa, pose, luz e ação, mantendo o clima do que bombou.
Regras: chame a pessoa só de "the subject"; NÃO descreva rosto, traços, tatuagens, cabelo ou identidade (a referência de rosto é colocada no Flow);
nada explícito; cada prompt em 1 a 3 frases, começando com "PHOTO:" ou "VIDEO:".
Responda só com JSON: {"prompts":["...","..."]}"""
        val arr = parseObj(ask(c, prompt, imgs.map { Base64.encodeToString(small(it), Base64.NO_WRAP) })).optJSONArray("prompts") ?: throw AiException(-2, "Resposta que não entendi")
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(8)
    }

    /** 🧠 Vídeos: quadro de cada vídeo + o que tinha nele + como foi → regras. */
    fun learnVideos(c: Context, items: List<Triple<File, String, String>>, extra: String = ""): List<String> {
        val list = items.mapIndexed { i, (_, d, k) ->
            "${i + 1}. " + (when (k) { "top" -> "💎 VIRALIZOU"; "good" -> "🔥 BOMBOU"; "bad" -> "👎 FLOPOU"; else -> "😐 normal" }) + " — " + d
        }.joinToString("\n")
        val prompt = """Você é analista de Reels/Instagram. Estão anexados ${items.size} quadros (um de cada vídeo postado por uma criadora), na ordem, com o que tinha em cada vídeo e como foi:
$list

$extra
IMPORTANTE: o mesmo vídeo pode ir mal numa postagem e bem em outra (horário, dia, momento do algoritmo). Use o histórico de cada um
pra separar o que é do CONTEÚDO do que é do HORÁRIO/SORTE, e diga se vale repostar o que flopou e em que horário.

Compare os que bombaram com os que floparam e descubra o que funciona na COMBINAÇÃO:
tipo de cena (o que ela está fazendo, enquadramento, cenário, roupa, luz — sem descrever rosto nem identidade) × texto no vídeo (tom, tamanho, gancho) × categoria × música × edição × legenda.
Escreva de 4 a 8 regras curtas, práticas e específicas (ex.: "cena X + texto curto provocante = bomba"; "sem texto em vídeo Y flopa").
Se houver poucos dados, diga o que parece mais provável, sem inventar certeza.
Responda só com JSON: {"regras":["...","..."]}"""
        val imgs = items.map { Base64.encodeToString(small(it.first), Base64.NO_WRAP) }
        val arr = parseObj(ask(c, prompt, imgs)).optJSONArray("regras") ?: throw AiException(-2, "Resposta que não entendi")
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(10)
    }

    /** Prompt pro Flow a partir de uma foto de referência: pose, roupa, cenário, luz e câmera, sem identificar a pessoa. */
    fun flowPrompt(c: Context, img: File): String {
        val prompt = """Look at this reference photo and write ONE prompt in English for an AI image/video generator (Google Flow).
Describe only: the pose and body position, camera angle and framing, outfit and accessories, setting/background, lighting, colors and mood.
Do NOT describe the face, identity, ethnicity or any identifying traits of the person, and do not name anyone; call them "the subject".
Max 70 words, one paragraph, no quotes, no lists.
Reply only with JSON: {"prompt":"..."}"""
        val txt = ask(c, prompt, listOf(Base64.encodeToString(shrink(img), Base64.NO_WRAP)))
        return parseObj(txt).optString("prompt").ifBlank { throw AiException(-2, "A IA não mandou o prompt") }
    }

    /** Textos curtos pra aparecer DENTRO do vídeo (Reels), aprendendo com os que bombaram e os que floparam. */
    fun overlayTexts(c: Context, n: Int, good: List<String>, bad: List<String>, mine: List<String>, ctxName: String = "", ctxDesc: String = "", frame: File? = null, style: List<String> = emptyList()): List<String> {
        val ctxLine = if (ctxName.isEmpty()) "Os textos devem servir pra qualquer vídeo (genéricos)."
            else "TODOS os textos são pra vídeos do contexto \"$ctxName\"" + (if (ctxDesc.isNotBlank()) ": $ctxDesc" else "") + ". O texto tem que combinar com esse tipo de cena."
        val prompt = """Crie $n textos curtos pra aparecer ESCRITOS DENTRO de vídeos curtos (Reels/Threads) de uma criadora de conteúdo, em português do Brasil.
${Learn.videoRulesText(c)}
${Predictor.insightsText(c, true)}
$ctxLine
Estilo viral: gancho, "pov:", frase de efeito, curiosidade, pergunta que faz comentar. De 3 a 12 palavras. Pode quebrar em 2 linhas com \n. No máximo 1 emoji. Sem hashtags.
Nada explícito, nada que finja ser outra pessoa real, nada enganoso.
${if (style.isNotEmpty()) "TEXTOS QUE EU ESCREVI — gere no MESMO estilo deles (mesmo tamanho, jeito de falar, gírias, emojis e quebras de linha), como se fosse eu escrevendo, sem copiar:\n" + style.joinToString("\n") { "- " + it.replace('\n', '/') } else ""}
${if (good.isNotEmpty()) "Textos que BOMBARAM (siga o ritmo e o tipo de gancho, sem copiar):\n" + good.joinToString("\n") { "- " + it.replace('\n', ' ') } else ""}
${if (bad.isNotEmpty()) "Textos que FLOPARAM (evite esse estilo):\n" + bad.joinToString("\n") { "- " + it.replace('\n', ' ') } else ""}
${if (mine.isNotEmpty()) "Outros já usados (não repita):\n" + mine.take(20).joinToString("\n") { "- " + it.replace('\n', ' ') } else ""}
Responda só com JSON: {"textos":["...","..."]}"""
        val imgs = if (frame != null && frame.exists()) listOf(Base64.encodeToString(shrink(frame), Base64.NO_WRAP)) else emptyList()
        val full = if (imgs.isEmpty()) prompt else prompt + "\nA imagem anexada é um quadro do vídeo: os textos têm que combinar com o que acontece nele (não descreva a pessoa)."
        val arr = parseObj(ask(c, full, imgs)).optJSONArray("textos") ?: throw AiException(-2, "A IA não mandou os textos")
        return (0 until arr.length()).map { arr.optString(it).replace("\\n", "\n").trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Separa vídeos crus por contexto olhando um quadro de cada um (várias imagens num pedido só).
     * Devolve o contexto de cada imagem, na ordem; pode sugerir um contexto novo curto.
     */
    fun classify(c: Context, frames: List<File>, contexts: List<Pair<String, String>>): List<String> {
        val opts = if (contexts.isEmpty()) "(nenhum ainda)" else contexts.joinToString("\n") { "- ${it.first}" + if (it.second.isNotBlank()) ": ${it.second}" else "" }
        val prompt = """São ${frames.size} quadros de vídeos curtos, numerados de 1 a ${frames.size} na ordem.
Pra cada um, escolha o CONTEXTO da cena (o lugar ou a situação: academia, praia, espelho, quarto, dança, carro, festa…), não a pessoa.
Contextos que já existem (prefira estes):
$opts
Se nenhum servir, invente um nome curto novo (1 ou 2 palavras, em português).
Responda só com JSON: {"r":[{"n":1,"ctx":"..."}]}"""
        val txt = ask(c, prompt, frames.map { Base64.encodeToString(shrink(it), Base64.NO_WRAP) })
        val arr = parseObj(txt).optJSONArray("r") ?: throw AiException(-2, "A IA não separou")
        val out = MutableList(frames.size) { "" }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optInt("n", i + 1) - 1
            if (k in out.indices) out[k] = o.optString("ctx").trim()
        }
        return out
    }

    /** Pedido mínimo pra conferir chave e modelo. */
    fun test(c: Context): String = ask(c, "Responda só com JSON: {\"ok\":true}", emptyList()).trim().take(200)

    private fun ask(c: Context, prompt: String, imagesB64: List<String>): String {
        val k = key(c)
        if (k.isEmpty()) throw AiException(401, "Coloque sua chave de API nas configurações")
        Auto.countAi(c)
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
