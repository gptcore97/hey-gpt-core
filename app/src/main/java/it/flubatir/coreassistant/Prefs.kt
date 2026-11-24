package it.flubatir.coreassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Impostazioni, sessione di accesso e memoria a lungo termine dell'assistente. */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("core", Context.MODE_PRIVATE)

    fun apiKey(c: Context) = sp(c).getString("api_key", "")!!.trim()
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api_key", v.trim()).apply()

    fun model(c: Context): String {
        val v = sp(c).getString("model", "")!!.trim()
        return v.ifBlank { Config.defaultModel(c) }
    }
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v.trim()).apply()

    /** Ultimo modello che ha risposto davvero (utile quando scatta il passaggio automatico). */
    fun lastModel(c: Context) = sp(c).getString("last_model", "")!!
    fun setLastModel(c: Context, v: String) = sp(c).edit().putString("last_model", v).apply()

    /** Se il modello scelto e' sovraccarico passa da solo al piu' simile. */
    fun autoFallback(c: Context) = sp(c).getBoolean("auto_fallback", Config.fallbackDefault(c))
    fun setAutoFallback(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_fallback", v).apply()

    /** Ricerca web: auto | always | off */
    fun webMode(c: Context): String {
        val v = sp(c).getString("web_mode", "")!!
        return if (v in WEB_MODES) v else Config.webDefault(c)
    }
    fun setWebMode(c: Context, v: String) = sp(c).edit().putString("web_mode", v).apply()
    val WEB_MODES = listOf("auto", "always", "off")

    // ---- Account / sessione -------------------------------------------------
    fun loggedIn(c: Context) = sp(c).getString("profile", "")!!.isNotBlank()

    fun profile(c: Context): JSONObject? = try {
        val s = sp(c).getString("profile", "")!!
        if (s.isBlank()) null else JSONObject(s)
    } catch (e: Exception) { null }

    fun setProfile(c: Context, method: String, name: String, email: String) {
        val p = JSONObject().put("method", method).put("name", name).put("email", email).put("ts", System.currentTimeMillis())
        sp(c).edit().putString("profile", p.toString()).putString("user_name", name).apply()
    }

    fun logout(c: Context) = sp(c).edit().remove("profile").remove("user_name").apply()

    /** Nome con cui ti chiama l'assistente (preso dall'account, modificabile nell'app). */
    fun userName(c: Context) = sp(c).getString("user_name", "")!!
    fun setUserName(c: Context, v: String) {
        val n = v.trim().take(40)
        val e = sp(c).edit().putString("user_name", n)
        profile(c)?.let { e.putString("profile", it.put("name", n).toString()) }
        e.apply()
    }
    fun displayName(c: Context) = userName(c).ifBlank { "chi lo usa" }

    /** Account locali (solo su questo telefono): { "nome minuscolo": { name, salt, hash } } */
    fun accounts(c: Context): JSONObject = try { JSONObject(sp(c).getString("accounts", "{}")!!) } catch (e: Exception) { JSONObject() }
    fun saveAccounts(c: Context, o: JSONObject) = sp(c).edit().putString("accounts", o.toString()).apply()

    // Accesso Google in corso (il processo puo' essere ucciso mentre sei nel browser)
    fun setPending(c: Context, verifier: String, state: String) = sp(c).edit().putString("oauth_verifier", verifier).putString("oauth_state", state).apply()
    fun pendingVerifier(c: Context) = sp(c).getString("oauth_verifier", "")!!
    fun pendingState(c: Context) = sp(c).getString("oauth_state", "")!!
    fun clearPending(c: Context) = sp(c).edit().remove("oauth_verifier").remove("oauth_state").apply()

    // Elenco modelli scaricato da Gemini
    fun modelsCache(c: Context): List<String> {
        val a = try { JSONArray(sp(c).getString("models_cache", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getString(it) }
    }
    fun modelsCacheTs(c: Context) = sp(c).getLong("models_cache_ts", 0L)
    fun setModelsCache(c: Context, l: List<String>) = sp(c).edit().putString("models_cache", JSONArray(l).toString()).putLong("models_cache_ts", System.currentTimeMillis()).apply()
    fun clearModelsCache(c: Context) = sp(c).edit().remove("models_cache").remove("models_cache_ts").apply()

    // ---- Voce / attivazione --------------------------------------------------
    fun geminiVoice(c: Context) = sp(c).getBoolean("gemini_voice", true)
    fun setGeminiVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("gemini_voice", v).apply()

    fun voiceName(c: Context) = sp(c).getString("voice_name", "Kore")!!
    fun setVoiceName(c: Context, v: String) = sp(c).edit().putString("voice_name", v).apply()

    fun wake(c: Context) = sp(c).getBoolean("wake", false)
    fun setWake(c: Context, v: Boolean) = sp(c).edit().putBoolean("wake", v).apply()

    // Memoria: fatti che l'utente chiede di ricordare ("mamma = Maria Rossi", "la mia auto e' una Panda"...)
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
