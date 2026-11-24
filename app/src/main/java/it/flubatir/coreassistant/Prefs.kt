package it.flubatir.coreassistant

import android.content.Context
import org.json.JSONArray

/** Impostazioni e memoria a lungo termine dell'assistente. */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("core", Context.MODE_PRIVATE)

    fun apiKey(c: Context) = sp(c).getString("api_key", "")!!.trim()
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api_key", v.trim()).apply()

    fun model(c: Context) = sp(c).getString("model", "gemini-2.5-flash")!!.ifBlank { "gemini-2.5-flash" }
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v.trim()).apply()

    fun userName(c: Context) = sp(c).getString("user_name", "Flubatir")!!
    fun setUserName(c: Context, v: String) = sp(c).edit().putString("user_name", v.trim()).apply()

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
