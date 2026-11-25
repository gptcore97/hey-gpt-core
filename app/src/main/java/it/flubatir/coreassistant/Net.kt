package it.flubatir.coreassistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Rete nativa: nessun limite CORS. */
object Net {
    class Res(val code: Int, val body: String)

    fun headersOf(o: JSONObject?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        o?.keys()?.forEach { m[it] = o.optString(it) }
        return m
    }

    fun request(url: String, headers: Map<String, String> = emptyMap(), body: String? = null,
                connectMs: Int = 15000, readMs: Int = 40000, method: String = if (body != null) "POST" else "GET"): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs; c.readTimeout = readMs
            c.instanceFollowRedirects = true
            if (!headers.keys.any { it.equals("User-Agent", true) })
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null && method != "GET") { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val s = if (code < 400) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = s.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return Res(code, text)
        } finally { try { c.disconnect() } catch (e: Exception) { } }
    }
}
