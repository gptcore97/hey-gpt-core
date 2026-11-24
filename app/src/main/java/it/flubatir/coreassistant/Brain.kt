package it.flubatir.coreassistant

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Reply(val text: String, val expectsAnswer: Boolean, val cards: List<Card>, val deferred: List<() -> Unit>)

/** Il "cervello": Gemini con function calling. Capisce la richiesta, sceglie gli strumenti, li esegue e risponde a voce. */
class Brain(private val ctx: Context) {

    companion object {
        private val history = JSONArray()
        private var lastTs = 0L
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

        fun resetConversation() { synchronized(history) { while (history.length() > 0) history.remove(0) } }

        private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

        private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
            val o = JSONObject().put("name", name).put("description", desc)
            if (props.isNotEmpty()) {
                val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
                o.put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required)))
            }
            return o
        }

        private val S = "string"
        val declarations: JSONArray = JSONArray().apply {
            put(fn("open_app", "Apre un'app installata dal nome pronunciato dall'utente (anche se scritto male dal riconoscimento vocale).", mapOf("name" to prop(S, "Nome dell'app")), listOf("name")))
            put(fn("list_apps", "Elenca le app installate (opzionalmente filtrate). Usalo se l'utente chiede quali app ha.", mapOf("filter" to prop(S, "Filtro opzionale"))))
            put(fn("call_contact", "Chiama un contatto della rubrica con la linea telefonica (SIM). Controlla da solo se c'e' la SIM. Se fallisce per mancanza di SIM, proponi le alternative restituite e chiedi quale app usare.",
                mapOf("name" to prop(S, "Nome del contatto"), "number" to prop(S, "Numero, solo se detto esplicitamente"))))
            put(fn("call_via_app", "Chiama un contatto tramite un'app (WhatsApp, Telegram, Signal, Messenger...). Usalo quando l'utente sceglie l'app o non c'e' la SIM.",
                mapOf("name" to prop(S, "Nome del contatto"), "app" to prop(S, "Nome dell'app")), listOf("name", "app")))
            put(fn("send_message", "Prepara un messaggio (SMS o WhatsApp) a un contatto; l'utente poi preme invio.",
                mapOf("name" to prop(S, "Contatto"), "text" to prop(S, "Testo del messaggio"), "via" to prop(S, "sms oppure whatsapp")), listOf("name", "text")))
            put(fn("check_phone_status", "Verifica se il telefono ha SIM pronta, modalita' aereo e quali app di chiamata sono installate."))
            put(fn("flashlight", "Accende o spegne la torcia.", mapOf("on" to prop("boolean", "true accende, false spegne")), listOf("on")))
            put(fn("set_volume", "Imposta il volume (0-100).", mapOf("percent" to prop("integer", "Percentuale"), "stream" to prop(S, "media, suoneria, sveglia o chiamata")), listOf("percent")))
            put(fn("set_alarm", "Imposta una sveglia.", mapOf("hour" to prop("integer", "Ora 0-23"), "minute" to prop("integer", "Minuti"), "label" to prop(S, "Etichetta")), listOf("hour", "minute")))
            put(fn("set_timer", "Avvia un timer.", mapOf("seconds" to prop("integer", "Durata in secondi"), "label" to prop(S, "Etichetta")), listOf("seconds")))
            put(fn("navigate", "Avvia la navigazione verso un luogo.", mapOf("destination" to prop(S, "Indirizzo o luogo")), listOf("destination")))
            put(fn("play_music", "Riproduce musica (brano, artista, playlist).", mapOf("query" to prop(S, "Cosa riprodurre")), listOf("query")))
            put(fn("open_url", "Apre un sito web.", mapOf("url" to prop(S, "Indirizzo")), listOf("url")))
            put(fn("search_in_browser", "Mostra una ricerca Google nel browser (solo se l'utente vuole vedere i risultati).", mapOf("query" to prop(S, "Ricerca")), listOf("query")))
            put(fn("web_answer", "Cerca informazioni aggiornate su internet (notizie, meteo, sport, prezzi, orari, fatti recenti) e restituisce una risposta con fonti. Usalo ogni volta che serve un dato aggiornato.", mapOf("query" to prop(S, "Domanda di ricerca")), listOf("query")))
            put(fn("open_settings", "Apre una sezione delle impostazioni (wifi, bluetooth, volume, schermo, batteria, posizione, nfc, aereo, app, hotspot).", mapOf("section" to prop(S, "Sezione"))))
            put(fn("take_photo", "Apre la fotocamera."))
            put(fn("create_event", "Crea un evento in calendario.", mapOf("title" to prop(S, "Titolo"), "start" to prop(S, "Inizio, formato AAAA-MM-GGTHH:MM ora locale"), "duration_min" to prop("integer", "Durata in minuti")), listOf("title", "start")))
            put(fn("device_status", "Batteria, rete, data e ora, modello del telefono."))
            put(fn("remember", "Memorizza per sempre un fatto sull'utente (soprannomi dei contatti come 'mamma = Maria Rossi', preferenze, app preferite). Usalo quando l'utente dice 'ricorda che...' o quando impari qualcosa di utile e stabile.", mapOf("fact" to prop(S, "Fatto da ricordare, frase breve")), listOf("fact")))
        }
    }

    private val tools = Tools(ctx)

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALY).format(Date())
        val facts = Prefs.facts(ctx)
        return buildString {
            append("Sei GPT CORE, l'assistente vocale personale di ${Prefs.displayName(ctx)} sul suo telefono Android (${Build.MANUFACTURER} ${Build.MODEL}). Ora: $now.\n")
            append("Il tuo creatore e' Flubatir. Parla in italiano (o nella lingua dell'utente), con voce calda, sicura e naturale, come un assistente umano molto in gamba.\n")
            if (Prefs.userName(ctx).isNotBlank()) append("L'utente si chiama ${Prefs.userName(ctx)}: chiamalo per nome quando e' naturale, senza esagerare.\n")
            append("REGOLE:\n")
            append("- Le tue risposte vengono lette ad alta voce: frasi brevi, niente markdown, niente elenchi puntati, niente emoji, niente URL letti per intero. Di solito 1-2 frasi.\n")
            append("- Se la richiesta e' un'azione sul telefono, usa SUBITO lo strumento giusto senza chiedere conferme inutili; poi conferma in modo breve (\"Apro WhatsApp\", \"Chiamo Marco\").\n")
            append("- Il testo arriva da un riconoscimento vocale e puo' contenere errori sui nomi: lo strumento fa gia' la corrispondenza approssimata. Se uno strumento risponde che il nome e' ambiguo o incerto, fai UNA domanda breve elencando al massimo 3 candidati (\"Intendi Marco Rossi o Marco Bianchi?\").\n")
            append("- Chiamate: usa call_contact. Se non c'e' la SIM / modalita' aereo / il contatto non ha numero, spiega il motivo in una frase e proponi le app alternative restituite dallo strumento (es. WhatsApp, Telegram) chiedendo quale preferisce. Quando risponde con un'app, usa call_via_app.\n")
            append("- Per dati che cambiano (notizie, meteo, sport, prezzi, orari, 'chi e' ora...') usa web_answer; non inventare mai.\n")
            append("- Per domande generali, conversazione, traduzioni, calcoli, consigli rispondi direttamente, in modo intelligente e concreto.\n")
            append("- Non inventare contatti, numeri o app: usa solo cio' che gli strumenti restituiscono. Se uno strumento fallisce, spiega onestamente cosa non e' andato e cosa si puo' fare.\n")
            append("- Se la tua risposta e' una domanda all'utente, terminala con un punto di domanda: il microfono si riaprira' da solo.\n")
            append("- Se l'utente dice 'ricorda che...' usa remember. Usa la memoria qui sotto per capire soprannomi e preferenze.\n")
            when (Prefs.webMode(ctx)) {
                "always" -> append("- RICERCA WEB SEMPRE ATTIVA: per ogni domanda su fatti, persone, luoghi, eventi o dati usa web_answer prima di rispondere.\n")
                "off" -> append("- La ricerca web e' disattivata: non puoi cercare su internet. Se serve un dato aggiornato dillo onestamente.\n")
            }
            val extra = Config.systemPrompt(ctx)
            if (extra.isNotEmpty()) append("ISTRUZIONI DEL CREATORE:\n" + extra + "\n")
            if (facts.isNotEmpty()) append("MEMORIA:\n" + facts.joinToString("\n") { "- $it" } + "\n")
        }
    }

    private class Http(val code: Int, val body: String)
    private class Gen(val h: Http, val tried: List<String>)

    private fun post(model: String, payload: JSONObject, timeoutMs: Int = 40000): Http {
        val url = URL(BASE + model + ":generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", Prefs.apiKey(ctx))
        c.connectTimeout = 15000; c.readTimeout = timeoutMs; c.doOutput = true
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val body = stream.bufferedReader().use { it.readText() }
        c.disconnect()
        return Http(code, body)
    }

    /** Errori "passeggeri": sovraccarico, quota, "riprova piu' tardi". Conviene riprovare o cambiare modello. */
    private fun retriable(h: Http): Boolean {
        if (h.code == 429 || h.code in 500..599) return true
        val b = h.body.lowercase()
        return b.contains("overloaded") || b.contains("unavailable") || b.contains("try again later") ||
            b.contains("high demand") || b.contains("resource_exhausted") || b.contains("deadline")
    }

    /** Il modello non esiste / non supporta la richiesta: inutile riprovarlo, meglio il prossimo. */
    private fun modelProblem(h: Http): Boolean {
        if (h.code == 404) return true
        if (h.code != 400) return false
        val b = h.body.lowercase()
        if (b.contains("api key")) return false
        return b.contains("not supported") || b.contains("not found") || b.contains("unsupported") || b.contains("is not available")
    }

    private fun genConfig(model: String, thinking: Boolean): JSONObject {
        val g = JSONObject().put("temperature", 0.6)
        // niente "ragionamento" lungo: risposte piu' rapide (solo i flash 2.5 lo permettono)
        if (thinking && Regex("gemini-2\\.5-flash").containsMatchIn(model)) g.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        return g
    }

    /**
     * Chiama Gemini con ripiego automatico: ritenta una volta se e' solo occupato, poi passa al modello piu' simile
     * (stessa famiglia/versione vicina) finche' qualcuno risponde. Errori di chiave o di richiesta non fanno cambiare modello.
     */
    private fun generate(build: (String, Boolean) -> JSONObject, timeoutMs: Int, onStatus: (String) -> Unit): Gen {
        val auto = Prefs.autoFallback(ctx)
        val chain = if (auto) Models.chain(ctx) else listOf(Prefs.model(ctx))
        val ordered = chain.filter { !Models.isCooling(it) }.ifEmpty { chain }
        val deadline = SystemClock.elapsedRealtime() + 75_000L
        val tried = ArrayList<String>()
        var last: Http? = null
        var netErr: java.io.IOException? = null
        for ((i, m) in ordered.withIndex()) {
            if (i > 0 && SystemClock.elapsedRealtime() > deadline) break
            tried.add(m)
            if (i > 0) onStatus("Provo con $m…")
            var thinking = true
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val h: Http? = try {
                    post(m, build(m, thinking), if (i == 0) timeoutMs else minOf(timeoutMs, 28000))
                } catch (e: java.net.UnknownHostException) { throw e }
                catch (e: java.io.IOException) { netErr = e; null }
                if (h == null) {
                    if (attempt == 1) { Thread.sleep(400); continue }
                    Models.markBad(m, 30_000L); break
                }
                last = h
                if (h.code in 200..299) { Models.markGood(m); Prefs.setLastModel(ctx, m); return Gen(h, tried) }
                if (h.code == 400 && thinking && h.body.contains("thinking", true)) { thinking = false; attempt--; continue }
                if (retriable(h)) {
                    if (attempt == 1 && h.code != 429) { onStatus("Gemini è occupato, riprovo…"); Thread.sleep(900); continue }
                    Models.markBad(m, if (h.code == 429) 120_000L else 60_000L); break
                }
                if (modelProblem(h)) { Models.markBad(m, 600_000L); break }
                return Gen(h, tried)   // errore non legato al modello (chiave, richiesta): inutile cambiare
            }
        }
        if (last != null) return Gen(last, tried)
        throw netErr ?: java.io.IOException("nessuna risposta")
    }

    private fun errorText(g: Gen): String {
        val h = g.h
        val msg = try { JSONObject(h.body).getJSONObject("error").getString("message") } catch (e: Exception) { "" }
        return when {
            h.code == 400 && msg.contains("API key", true) -> "La chiave API di Gemini non è valida. Controllala nelle impostazioni dell'app."
            h.code == 403 -> "La chiave API non ha i permessi necessari."
            retriable(h) && g.tried.size > 1 -> "Gemini è molto carico: ho provato ${g.tried.joinToString(", ")} ma nessun modello ha risposto. Riprova tra poco."
            retriable(h) && !Prefs.autoFallback(ctx) -> "Il modello ${g.tried.firstOrNull() ?: ""} è occupato. Attiva nelle impostazioni il passaggio automatico al modello più simile, oppure riprova tra poco."
            h.code == 429 -> "Ho finito la quota gratuita di Gemini per ora. Riprova tra poco."
            h.code >= 500 -> "I server di Gemini sono momentaneamente in difficoltà. Riprova tra un attimo."
            else -> "Errore di Gemini ${h.code}. $msg".take(160)
        }
    }

    /** Ricerca web vera tramite il grounding di Google Search (con lo stesso ripiego sui modelli). */
    private fun webAnswer(query: String, onStatus: (String) -> Unit): JSONObject {
        val sys = "Rispondi in italiano in modo sintetico e preciso (massimo 4 frasi), con dati aggiornati. Oggi e' " + SimpleDateFormat("d MMMM yyyy", Locale.ITALY).format(Date()) + "."
        val g = generate({ _, _ ->
            JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", query)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }, 45000, onStatus)
        val h = g.h
        if (h.code !in 200..299) return JSONObject().put("ok", false).put("error", errorText(g))
        val j = JSONObject(h.body)
        val cand = j.optJSONArray("candidates")?.optJSONObject(0) ?: return JSONObject().put("ok", false).put("error", "nessun risultato")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        val srcs = JSONArray()
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) for (i in 0 until minOf(3, chunks.length())) srcs.put(chunks.getJSONObject(i).optJSONObject("web")?.optString("title") ?: "")
        return JSONObject().put("ok", true).put("risposta", sb.toString().trim()).put("fonti", srcs)
    }

    /** Strumenti disponibili in base alla modalita' di ricerca web scelta. */
    private fun activeDeclarations(): JSONArray {
        if (Prefs.webMode(ctx) != "off") return declarations
        val out = JSONArray()
        for (i in 0 until declarations.length()) { val d = declarations.getJSONObject(i); if (d.optString("name") != "web_answer") out.put(d) }
        return out
    }

    private fun trimHistory() {
        synchronized(history) {
            while (history.length() > 30) history.remove(0)
            // la cronologia deve iniziare con un messaggio utente di testo
            while (history.length() > 0) {
                val f = history.getJSONObject(0)
                val parts = f.optJSONArray("parts")
                val isText = f.optString("role") == "user" && parts != null && parts.length() > 0 && parts.getJSONObject(0).has("text")
                if (isText) break
                history.remove(0)
            }
        }
    }

    /** Bloccante: chiamare da un thread secondario. */
    fun ask(userText: String, onStatus: (String) -> Unit): Reply {
        if (Prefs.apiKey(ctx).isBlank()) return Reply("Manca la chiave API di Gemini. Aprila nelle impostazioni dell'app GPT CORE.", false, emptyList(), emptyList())
        val now = System.currentTimeMillis()
        if (now - lastTs > 5 * 60_000L) resetConversation()
        lastTs = now
        tools.deferred.clear()
        val cards = ArrayList<Card>()
        synchronized(history) { history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))) }
        try {
            for (round in 0 until 7) {
                trimHistory()
                val sys = systemPrompt()
                val hist = synchronized(history) { JSONArray(history.toString()) }
                val decl = activeDeclarations()
                val g = generate({ model, thinking ->
                    JSONObject()
                        .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                        .put("contents", hist)
                        .put("tools", JSONArray().put(JSONObject().put("function_declarations", decl)))
                        .put("generationConfig", genConfig(model, thinking))
                }, 40000, onStatus)
                val h = g.h
                if (h.code !in 200..299) return Reply(errorText(g), false, cards, emptyList())
                val cand = JSONObject(h.body).optJSONArray("candidates")?.optJSONObject(0)
                val content = cand?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (content == null || parts == null || parts.length() == 0) return Reply("Non sono riuscito a rispondere a questa richiesta. Puoi riformularla?", false, cards, tools.deferred.toList())
                synchronized(history) { history.put(JSONObject(content.toString()).put("role", "model")) }

                val calls = ArrayList<JSONObject>(); val text = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.has("functionCall")) calls.add(p.getJSONObject("functionCall"))
                    else if (p.has("text") && !p.optBoolean("thought", false)) text.append(p.getString("text"))
                }
                if (calls.isEmpty()) {
                    val t = text.toString().trim().replace("*", "").replace("#", "")
                    return Reply(t.ifEmpty { "Fatto." }, t.endsWith("?"), cards, tools.deferred.toList())
                }
                val responses = JSONArray()
                for (fc in calls) {
                    val name = fc.getString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
                    onStatus(statusFor(name))
                    val res: JSONObject = if (name == "web_answer") {
                        try { webAnswer(args.optString("query"), onStatus) } catch (e: Exception) { JSONObject().put("ok", false).put("error", "ricerca non riuscita: ${e.message}") }
                    } else {
                        val r = tools.execute(name, args)
                        r.card?.let { cards.add(it) }
                        r.json
                    }
                    responses.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", JSONObject().put("result", res))))
                }
                synchronized(history) { history.put(JSONObject().put("role", "user").put("parts", responses)) }
            }
            return Reply("Ho provato più volte ma non sono riuscito a completare l'operazione.", false, cards, tools.deferred.toList())
        } catch (e: java.net.UnknownHostException) {
            return Reply("Non riesco a collegarmi a internet.", false, cards, emptyList())
        } catch (e: java.net.SocketTimeoutException) {
            return Reply("La connessione è troppo lenta, riprova.", false, cards, emptyList())
        } catch (e: Exception) {
            return Reply("Si è verificato un errore: ${e.message}", false, cards, emptyList())
        }
    }

    private fun statusFor(n: String) = when (n) {
        "web_answer" -> "Cerco sul web…"
        "open_app" -> "Cerco l'app…"
        "call_contact", "call_via_app" -> "Cerco il contatto…"
        "check_phone_status" -> "Controllo la SIM…"
        "send_message" -> "Preparo il messaggio…"
        else -> "Eseguo…"
    }
}
