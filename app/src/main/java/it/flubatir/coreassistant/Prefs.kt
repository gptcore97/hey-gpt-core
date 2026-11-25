package it.flubatir.coreassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Motore attivo scelto nell'app (Store API): lo usa anche l'assistente di sistema. */
data class Engine(val alias: String, val base: String, val model: String, val tokParam: String, val key: String)

/**
 * La configurazione vive nell'app web (Store API, nome, voce...). A ogni modifica l'app la copia qui
 * (syncConfig), cosi' l'assistente di sistema usa le stesse API, gli stessi modelli e la stessa voce.
 */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("core", Context.MODE_PRIVATE)

    fun setCfg(c: Context, json: String) = sp(c).edit().putString("cfg", json).apply()

    fun cfg(c: Context): JSONObject = try { JSONObject(sp(c).getString("cfg", "{}")!!) } catch (e: Exception) { JSONObject() }

    private fun keyFor(cfg: JSONObject, keyId: String, provider: String): String {
        var k = cfg.optJSONObject("api_keys")?.optString(keyId, "")?.trim().orEmpty()
        if (k.isEmpty()) {
            val legacy = when (provider) { "openrouter" -> "openrouter_key"; "gemini" -> "gemini_key"; else -> "" }
            if (legacy.isNotEmpty()) k = cfg.optString(legacy, "").trim()
        }
        return k
    }

    fun engine(c: Context): Engine? {
        val cfg = cfg(c)
        val models = cfg.optJSONObject("api_models") ?: return null
        if (models.length() == 0) return null
        var alias = cfg.optString("active", "")
        if (!models.has(alias)) alias = models.keys().next()
        val e = models.optJSONObject(alias) ?: return null
        val provider = e.optString("provider", "")
        val keyId = e.optString("key_id", provider)
        val base = e.optString("base_url", "").trimEnd('/')
        if (base.isEmpty()) return null
        return Engine(alias, base, e.optString("model", ""), e.optString("tok_param", "max_tokens").ifBlank { "max_tokens" }, keyFor(cfg, keyId, provider))
    }

    fun geminiKey(c: Context): String = keyFor(cfg(c), "gemini", "gemini")

    fun braveKey(c: Context) = cfg(c).optString("brave_key", "").trim()

    fun userName(c: Context) = cfg(c).optString("user_name", "").ifBlank { "Flubatir" }

    private fun asst(c: Context): JSONObject = cfg(c).optJSONObject("assistant") ?: JSONObject()
    fun geminiVoice(c: Context) = asst(c).optBoolean("gemini_voice", true)
    fun voiceName(c: Context) = asst(c).optString("voice", "Kore").ifBlank { "Kore" }
    fun voiceStyle(c: Context) = asst(c).optString("style", "").trim()

    fun wake(c: Context) = sp(c).getBoolean("wake", false)
    fun setWake(c: Context, v: Boolean) = sp(c).edit().putBoolean("wake", v).apply()

    // Memoria a lungo termine dell'assistente ("mamma = Maria Rossi", ...)
    fun facts(c: Context): List<String> {
        val a = try { JSONArray(sp(c).getString("facts", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getString(it) }
    }

    fun addFact(c: Context, f: String) {
        val l = facts(c).toMutableList()
        if (l.none { it.equals(f, true) }) l.add(f.trim())
        while (l.size > 60) l.removeAt(0)
        sp(c).edit().putString("facts", JSONArray(l).toString()).apply()
    }

    fun clearFacts(c: Context) = sp(c).edit().remove("facts").apply()
}
