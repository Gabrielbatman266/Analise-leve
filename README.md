# Análise Leve

App Android leve (sem bibliotecas extras) que tira prints do Threads e analisa o engajamento em segundo plano.

## Como funciona

1. **Bolha de print** flutuando por cima do Threads.
   - Toque = 1 print (a bolha some na hora pra não aparecer na imagem).
   - Segure = **modo automático**: você rola o perfil devagar e ele tira prints sozinho, pulando os repetidos.
2. Cada print entra numa **fila** no celular e é lido pela IA **em segundo plano**, com notificação, mesmo com a tela apagada.
3. A IA anota legenda, o que a foto mostra, curtidas, comentários e reposts. O app junta tudo num **banco** sem repetir posts.
4. Quando a fila esvazia, ele compara os melhores com os piores e gera os **padrões do que funciona**.
5. **Copiar banco** → cole em *Importar do Análise Leve* no Post Leve. As top legendas e os padrões vão pro gerador.

Também dá pra **compartilhar prints da galeria** direto pro app (Compartilhar › Análise Leve).

## Instalar

Baixe o `AnaliseLeve.apk` em **Releases** (gerado automaticamente a cada atualização).

## Configurar

- **IA**: Claude (chave em console.anthropic.com) ou Gemini (chave em aistudio.google.com, tem cota grátis).
- O modelo é editável; vazio usa o padrão.
- **Xiaomi**: Economia de bateria › *Sem restrições*, e ative *Exibir janelas pop-up enquanto em segundo plano* e *Início automático*.

## Assinatura fixa (opcional, recomendado)

Pra cada APK novo instalar por cima do antigo pra sempre, crie dois segredos em
*Settings › Secrets and variables › Actions*: `LEVE_KEYSTORE_B64` (o arquivo .jks em base64) e `LEVE_KEYSTORE_PASS`.
Sem eles, o build guarda uma chave no cache do GitHub Actions, que expira se ficar 7 dias sem build.

## Estrutura

- `LeveService.kt`: bolha, captura de tela e fila de análise (serviço em primeiro plano).
- `Ai.kt`: chamadas ao Claude/Gemini.
- `Store.kt`: fila de prints e banco de posts (`posts.json`).
- `MainActivity.kt`: tela única.
