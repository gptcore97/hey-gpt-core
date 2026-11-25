package it.flubatir.coreassistant

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Reply(val text: String, val expectsAnswer: Boolean, val cards: List<Card>, val deferred: List<() -> Unit>)

/**
 * Il "cervello" dell'assistente: usa l'API e il modello scelti nello Store dell'app
 * (qualsiasi server compatibile OpenAI: OpenAI, Gemini, OpenRouter, Groq, Mistral, DeepSeek, LM Studio...).
 * Se il modello non supporta i tool, passa da solo a un protocollo a testo.
 */
class Brain(private val ctx: Context) {

    companion object {
        private val history = JSONArray()
        private var lastTs = 0L
        private val noTools = HashSet<String>()

        fun ready(ctx: Context) = Prefs.engine(ctx) != null

        fun resetConversation() { synchronized(history) { while (history.length() > 0) history.remove(0) } }

        private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

        private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
            val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
            val params = JSONObject().put("type", "object").put("properties", p)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
        }

        private const val S = "string"
        val toolSpecs: JSONArray = JSONArray().apply {
            put(fn("open_app", "Apre un'app installata dal nome pronunciato dall'utente (anche se scritto male dal riconoscimento vocale).", mapOf("name" to prop(S, "Nome dell'app")), listOf("name")))
            put(fn("list_apps", "Elenca le app installate (opzionalmente filtrate).", mapOf("filter" to prop(S, "Filtro opzionale"))))
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
            put(fn("web_search", "Cerca informazioni aggiornate su internet (notizie, meteo, sport, prezzi, orari, fatti recenti) e restituisce risultati con testo delle pagine. Usalo ogni volta che serve un dato aggiornato, poi rispondi con parole tue.", mapOf("query" to prop(S, "Domanda di ricerca, breve e specifica")), listOf("query")))
            put(fn("open_settings", "Apre una sezione delle impostazioni (wifi, bluetooth, volume, schermo, batteria, posizione, nfc, aereo, app, hotspot).", mapOf("section" to prop(S, "Sezione"))))
            put(fn("take_photo", "Apre la fotocamera."))
            put(fn("create_event", "Crea un evento in calendario.", mapOf("title" to prop(S, "Titolo"), "start" to prop(S, "Inizio, formato AAAA-MM-GGTHH:MM ora locale"), "duration_min" to prop("integer", "Durata in minuti")), listOf("title", "start")))
            put(fn("device_status", "Batteria, rete, data e ora, modello del telefono."))
            put(fn("remember", "Memorizza per sempre un fatto sull'utente (soprannomi dei contatti come 'mamma = Maria Rossi', preferenze, app preferite). Usalo quando l'utente dice 'ricorda che...'.", mapOf("fact" to prop(S, "Fatto da ricordare, frase breve")), listOf("fact")))
        }
    }

    private val tools = Tools(ctx)

    private fun systemPrompt(proto: Boolean): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALY).format(Date())
        val facts = Prefs.facts(ctx)
        return buildString {
            append("Sei GPT CORE, l'assistente vocale personale di ${Prefs.userName(ctx)} sul suo telefono Android (${Build.MANUFACTURER} ${Build.MODEL}). Ora: $now.\n")
            append("Il tuo creatore e' Flubatir. Parla in italiano (o nella lingua dell'utente), con voce calda, sicura e naturale, come un assistente umano molto in gamba.\n")
            append("REGOLE:\n")
            append("- Le tue risposte vengono lette ad alta voce: frasi brevi, niente markdown, niente elenchi puntati, niente emoji, niente URL letti per intero. Di solito 1-2 frasi.\n")
            append("- Se la richiesta e' un'azione sul telefono, usa SUBITO lo strumento giusto senza chiedere conferme inutili; poi conferma in modo breve (\"Apro WhatsApp\", \"Chiamo Marco\").\n")
            append("- Il testo arriva da un riconoscimento vocale e puo' contenere errori sui nomi: lo strumento fa gia' la corrispondenza approssimata. Se uno strumento risponde che il nome e' ambiguo o incerto, fai UNA domanda breve elencando al massimo 3 candidati (\"Intendi Marco Rossi o Marco Bianchi?\").\n")
            append("- Chiamate: usa call_contact. Se non c'e' la SIM / modalita' aereo / il contatto non ha numero, spiega il motivo in una frase e proponi le app alternative restituite dallo strumento (es. WhatsApp, Telegram) chiedendo quale preferisce. Quando risponde con un'app, usa call_via_app.\n")
            append("- Per dati che cambiano (notizie, meteo, sport, prezzi, orari, 'chi e' ora...') usa web_search; non inventare mai.\n")
            append("- Per domande generali, conversazione, traduzioni, calcoli, consigli rispondi direttamente, in modo intelligente e concreto.\n")
            append("- Non inventare contatti, numeri o app: usa solo cio' che gli strumenti restituiscono. Se uno strumento fallisce, spiega onestamente cosa non e' andato e cosa si puo' fare.\n")
            append("- Se la tua risposta e' una domanda all'utente, terminala con un punto di domanda: il microfono si riaprira' da solo.\n")
            append("- Se l'utente dice 'ricorda che...' usa remember. Usa la memoria qui sotto per capire soprannomi e preferenze.\n")
            if (facts.isNotEmpty()) append("MEMORIA:\n" + facts.joinToString("\n") { "- $it" } + "\n")
            if (proto) {
                append("\nSTRUMENTI (questo modello non usa i tool nativi). Per usarne uno rispondi SOLO con una riga, senza altro testo:\n")
                append("[[TOOL {\"name\":\"nome_strumento\",\"args\":{\"parametro\":\"valore\"}}]]\n")
                append("Il sistema ti rispondera' con [RISULTATO nome] e potrai continuare. Strumenti disponibili:\n")
                for (i in 0 until toolSpecs.length()) {
                    val f = toolSpecs.getJSONObject(i).getJSONObject("function")
                    val props = f.getJSONObject("parameters").getJSONObject("properties")
                    val ps = props.keys().asSequence().joinToString(", ") { it + ": " + props.getJSONObject(it).optString("type") }
                    append("- ${f.getString("name")}($ps): ${f.getString("description")}\n")
                }
            }
        }
    }

    private fun errorText(code: Int, body: String): String {
        val msg = try { JSONObject(body).let { it.optJSONObject("error")?.optString("message") ?: it.optString("message") } } catch (e: Exception) { "" }
        return when {
            code == 401 || code == 403 -> "La chiave API non è valida o non ha i permessi. Controllala nello Store API dell'app."
            code == 404 -> "Il modello o l'indirizzo non è stato trovato. Controlla l'API scelta nello Store."
            code == 429 -> "Ho finito la quota di questa API per ora. Riprova tra poco o scegli un altro modello."
            code >= 500 -> "Il server dell'API è momentaneamente in difficoltà. Riprova tra un attimo."
            else -> "Errore dell'API $code. $msg".take(180)
        }
    }

    private fun trimHistory() {
        synchronized(history) {
            while (history.length() > 32) history.remove(0)
            while (history.length() > 0) {
                val f = history.getJSONObject(0)
                if (f.optString("role") == "user" && f.opt("content") is String) break
                history.remove(0)
            }
        }
    }

    /** Passando al protocollo a testo si tengono solo i messaggi semplici. */
    private fun plainHistory() {
        synchronized(history) {
            val keep = ArrayList<JSONObject>()
            for (i in 0 until history.length()) {
                val m = history.getJSONObject(i)
                val r = m.optString("role"); val c = m.opt("content")
                if ((r == "user" || r == "assistant") && c is String && c.isNotBlank() && !m.has("tool_calls")) keep.add(m)
            }
            while (history.length() > 0) history.remove(0)
            keep.forEach { history.put(it) }
        }
    }

    private fun contentText(m: JSONObject): String {
        if (m.isNull("content")) return ""
        val c = m.opt("content")
        if (c is String) return c
        if (c is JSONArray) return (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: "" }
        return ""
    }

    private fun runTool(name: String, args: JSONObject, cards: ArrayList<Card>): JSONObject {
        if (name == "web_search") {
            return try { WebSearch.run(ctx, args.optString("query")) } catch (e: Exception) { JSONObject().put("ok", false).put("error", "ricerca non riuscita: ${e.message}") }
        }
        val r = tools.execute(name, args)
        r.card?.let { cards.add(it) }
        return r.json
    }

    /** Bloccante: chiamare da un thread secondario. */
    fun ask(userText: String, onStatus: (String) -> Unit): Reply {
        val eng = Prefs.engine(ctx) ?: return Reply("Non hai ancora aggiunto nessuna API. Aprila nello Store dell'app GPT CORE.", false, emptyList(), emptyList())
        val id = eng.base + "|" + eng.model
        val now = System.currentTimeMillis()
        if (now - lastTs > 5 * 60_000L) resetConversation()
        lastTs = now
        tools.deferred.clear()
        val cards = ArrayList<Card>()
        synchronized(history) { history.put(JSONObject().put("role", "user").put("content", userText)) }
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/json"
        if (eng.key.isNotEmpty()) headers["Authorization"] = "Bearer " + eng.key
        try {
            for (round in 0 until 8) {
                val proto = noTools.contains(id)
                trimHistory()
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(proto)))
                synchronized(history) { for (i in 0 until history.length()) msgs.put(history.get(i)) }
                val payload = JSONObject().put("model", eng.model).put("messages", msgs).put("stream", false).put(eng.tokParam, 2500)
                if (!proto) payload.put("tools", toolSpecs).put("tool_choice", "auto")
                val res = Net.request(eng.base + "/chat/completions", headers, payload.toString(), 15000, 90000)
                if (res.code !in 200..299) {
                    if (!proto && res.code in 400..499 && Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(res.body)) {
                        noTools.add(id); plainHistory(); continue
                    }
                    return Reply(errorText(res.code, res.body), false, cards, emptyList())
                }
                val msg = JSONObject(res.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: return Reply("Il modello non ha risposto. Riprova o scegli un altro modello.", false, cards, tools.deferred.toList())
                val text = contentText(msg).replace(Regex("(?s)<think>.*?</think>"), "").trim()
                val calls = msg.optJSONArray("tool_calls")

                if (!proto && calls != null && calls.length() > 0) {
                    synchronized(history) { history.put(JSONObject(msg.toString())) }
                    for (i in 0 until calls.length()) {
                        val tc = calls.getJSONObject(i); val f = tc.getJSONObject("function")
                        val name = f.getString("name")
                        val args = try { JSONObject(f.optString("arguments", "{}").ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                        onStatus(statusFor(name))
                        val out = runTool(name, args, cards)
                        synchronized(history) {
                            history.put(JSONObject().put("role", "tool").put("tool_call_id", tc.optString("id")).put("name", name).put("content", out.toString()))
                        }
                    }
                    continue
                }
                if (proto) {
                    val m = Regex("\\[\\[\\s*TOOL\\s*(\\{.*?\\})\\s*\\]\\]", RegexOption.DOT_MATCHES_ALL).find(text)
                    if (m != null) {
                        val j = try { JSONObject(m.groupValues[1]) } catch (e: Exception) { null }
                        if (j != null) {
                            val name = j.optString("name")
                            onStatus(statusFor(name))
                            val out = runTool(name, j.optJSONObject("args") ?: JSONObject(), cards)
                            synchronized(history) {
                                history.put(JSONObject().put("role", "assistant").put("content", text))
                                history.put(JSONObject().put("role", "user").put("content", "[RISULTATO $name] $out"))
                            }
                            continue
                        }
                    }
                }
                val clean = text.replace("*", "").replace("#", "").trim()
                synchronized(history) { history.put(JSONObject().put("role", "assistant").put("content", clean.ifEmpty { "Fatto." })) }
                return Reply(clean.ifEmpty { "Fatto." }, clean.endsWith("?"), cards, tools.deferred.toList())
            }
            return Reply("Ho provato più volte ma non sono riuscito a completare l'operazione.", false, cards, tools.deferred.toList())
        } catch (e: java.net.UnknownHostException) {
            return Reply("Non riesco a collegarmi al server dell'API. Controlla internet o l'indirizzo.", false, cards, emptyList())
        } catch (e: java.net.SocketTimeoutException) {
            return Reply("La risposta è troppo lenta, riprova.", false, cards, emptyList())
        } catch (e: Exception) {
            return Reply("Si è verificato un errore: ${e.message}", false, cards, emptyList())
        }
    }

    private fun statusFor(n: String) = when (n) {
        "web_search" -> "Cerco sul web…"
        "open_app" -> "Cerco l'app…"
        "call_contact", "call_via_app" -> "Cerco il contatto…"
        "check_phone_status" -> "Controllo la SIM…"
        "send_message" -> "Preparo il messaggio…"
        else -> "Eseguo…"
    }
}
