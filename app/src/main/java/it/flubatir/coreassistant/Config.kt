package it.flubatir.coreassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * config.json dell'assistente (stessa idea del config.json di GPT CORE Mobile).
 * Si legge a ogni avvio da app/src/main/assets/config.json; se hai importato un file dall'app,
 * quello (in filesDir) ha la precedenza chiave per chiave.
 * Chiavi: system_prompt (testo o lista), model, auto_fallback, fallback_models, web_search (auto|always|off), google.
 */
object Config {
    @Volatile private var cache: JSONObject? = null

    private fun assetText(c: Context): String? = try {
        c.applicationContext.assets.open("config.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) { null }

    private fun load(c: Context): JSONObject {
        cache?.let { return it }
        val o = JSONObject()
        fun merge(txt: String?) {
            if (txt == null) return
            try { val j = JSONObject(txt); val it = j.keys(); while (it.hasNext()) { val k = it.next(); o.put(k, j.get(k)) } } catch (e: Exception) { }
        }
        merge(assetText(c))
        val f = File(c.applicationContext.filesDir, "config.json")
        if (f.exists()) merge(try { f.readText() } catch (e: Exception) { null })
        cache = o
        return o
    }

    fun reload() { cache = null }

    /** Istruzioni aggiuntive del creatore, aggiunte al system prompt. */
    fun systemPrompt(c: Context): String {
        val v = load(c).opt("system_prompt")
        return when (v) {
            is JSONArray -> (0 until v.length()).joinToString("\n") { v.optString(it) }
            is String -> v
            else -> ""
        }.trim()
    }

    fun defaultModel(c: Context) = load(c).optString("model", "").trim().ifBlank { "gemini-2.5-flash" }
    fun fallbackDefault(c: Context) = load(c).optBoolean("auto_fallback", true)
    fun webDefault(c: Context): String { val v = load(c).optString("web_search", "auto"); return if (v in Prefs.WEB_MODES) v else "auto" }

    fun fallbackModels(c: Context): List<String> {
        val a = load(c).optJSONArray("fallback_models") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    /** L'ID cliente Google si legge SOLO dal file incluso nell'APK: lo schema di ritorno e' fissato in fase di build. */
    fun googleClientId(c: Context): String = try {
        JSONObject(assetText(c) ?: "{}").optJSONObject("google")?.optString("ios_client_id", "")?.trim() ?: ""
    } catch (e: Exception) { "" }

    /** Importa un config.json scelto dall'utente. Restituisce un messaggio d'errore oppure null se ok. */
    fun importFrom(c: Context, text: String): String? {
        val j = try { JSONObject(text) } catch (e: Exception) { return "config.json non valido" }
        try { File(c.applicationContext.filesDir, "config.json").writeText(j.toString(2)) } catch (e: Exception) { return "Impossibile salvare il file" }
        reload()
        j.optString("model", "").trim().takeIf { it.isNotEmpty() }?.let { Prefs.setModel(c, it) }
        j.optString("web_search", "").takeIf { it in Prefs.WEB_MODES }?.let { Prefs.setWebMode(c, it) }
        if (j.has("auto_fallback")) Prefs.setAutoFallback(c, j.optBoolean("auto_fallback", true))
        val key = j.optString("api_key", "").ifBlank { j.optJSONObject("api_keys")?.optString("gemini", "") ?: "" }.trim()
        if (key.isNotEmpty()) Prefs.setApiKey(c, key)
        return null
    }
}
