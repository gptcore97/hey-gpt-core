package it.flubatir.coreassistant

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Elenco dei modelli Gemini e scelta del "piu' simile" quando quello scelto non risponde
 * (sovraccarico, quota finita, "riprova piu' tardi").
 */
object Models {
    private val EXCLUDE = listOf("tts", "image", "live", "audio", "embedding", "robotics", "computer-use", "imagen", "veo", "learnlm", "aqa", "gemma", "nano", "banana", "vision")
    private val STATIC = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.0-flash", "gemini-2.5-pro")
    private const val LIST_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"

    // modelli che hanno appena fallito: li saltiamo per un po'
    private val cooldown = HashMap<String, Long>()
    fun markBad(m: String, ms: Long) { synchronized(cooldown) { cooldown[m] = SystemClock.elapsedRealtime() + ms } }
    fun markGood(m: String) { synchronized(cooldown) { cooldown.remove(m) } }
    fun isCooling(m: String) = synchronized(cooldown) { (cooldown[m] ?: 0L) > SystemClock.elapsedRealtime() }

    private fun tier(id: String) = when {
        id.contains("lite") -> 0
        id.contains("flash") -> 1
        id.contains("pro") -> 2
        else -> 1
    }

    private fun version(id: String): Double? = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Quanto un modello assomiglia a quello scelto (piu' alto = piu' simile). */
    fun similarity(chosen: String, cand: String): Int {
        var s = 100
        s -= abs(tier(chosen) - tier(cand)) * 30
        val vc = version(chosen); val vd = version(cand)
        s -= if (vc != null && vd != null) minOf(40, (abs(vc - vd) * 12).toInt()) else 6
        if (cand.contains("preview")) s -= 8
        if (cand.contains("exp")) s -= 20
        if (cand.contains("thinking")) s -= 10
        return s
    }

    fun cached(c: Context) = Prefs.modelsCache(c)

    /** Elenco da mostrare / usare: quello scaricato, altrimenti quello statico. */
    fun available(c: Context): List<String> {
        val l = cached(c)
        return l.ifEmpty { STATIC }
    }

    /** Bloccante: scarica i modelli che supportano generateContent. Lancia eccezione se la chiave non va. */
    fun refresh(c: Context, keyOverride: String? = null): List<String> {
        val key = (keyOverride ?: Prefs.apiKey(c)).trim()
        if (key.isEmpty()) throw IllegalStateException("manca la chiave")
        val found = LinkedHashSet<String>()
        var token = ""
        for (page in 0 until 4) {
            val u = URL(LIST_URL + if (token.isEmpty()) "" else "&pageToken=$token")
            val con = u.openConnection() as HttpURLConnection
            con.setRequestProperty("x-goog-api-key", key)
            con.connectTimeout = 12000; con.readTimeout = 20000
            val code = con.responseCode
            val body = (if (code in 200..299) con.inputStream else (con.errorStream ?: con.inputStream)).bufferedReader().use { it.readText() }
            con.disconnect()
            if (code !in 200..299) {
                val msg = try { JSONObject(body).getJSONObject("error").getString("message") } catch (e: Exception) { "" }
                throw IllegalStateException(if (code == 400 || code == 403) "chiave non valida ($code)" else "errore $code $msg".trim())
            }
            val j = JSONObject(body)
            val arr = j.optJSONArray("models")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")
                var ok = false
                if (methods != null) for (k in 0 until methods.length()) if (methods.optString(k) == "generateContent") ok = true
                if (ok && id.startsWith("gemini-") && EXCLUDE.none { id.contains(it) }) found.add(id)
            }
            token = j.optString("nextPageToken", "")
            if (token.isEmpty()) break
        }
        val sorted = found.toList().sortedWith(compareByDescending<String> { version(it) ?: 99.0 }.thenByDescending { tier(it) }.thenBy { it })
        if (sorted.isNotEmpty()) Prefs.setModelsCache(c, sorted)
        return sorted
    }

    /** Ordine di prova: prima il modello scelto, poi i piu' simili (max 4 in tutto). */
    fun chain(c: Context): List<String> {
        val chosen = Prefs.model(c)
        val pool = LinkedHashSet<String>()
        pool.addAll(available(c))
        pool.addAll(Config.fallbackModels(c))
        pool.remove(chosen)
        val rest = pool.sortedByDescending { similarity(chosen, it) }
        return (listOf(chosen) + rest).take(4)
    }
}
