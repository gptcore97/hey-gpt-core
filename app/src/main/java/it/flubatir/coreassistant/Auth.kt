package it.flubatir.coreassistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Account locali: nome + password, salvati solo su questo telefono (password con hash PBKDF2, mai in chiaro). */
object AuthStore {
    private const val ITER = 120_000

    private fun key(n: String) = n.trim().lowercase()
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun hash(pw: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pw.toCharArray(), salt, ITER, 256)
        return b64(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    /** Restituisce un messaggio d'errore oppure null se l'account e' stato creato (e sei dentro). */
    fun register(c: Context, name: String, pw: String): String? {
        val n = name.trim()
        if (n.length < 2) return "Scrivi il tuo nome (almeno 2 lettere)."
        if (n.length > 40) return "Nome troppo lungo (max 40 caratteri)."
        if (pw.length < 6) return "La password deve avere almeno 6 caratteri."
        val all = Prefs.accounts(c)
        if (all.has(key(n))) return "Esiste già un account con questo nome: accedi."
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        all.put(key(n), JSONObject().put("name", n).put("salt", b64(salt)).put("hash", hash(pw, salt)))
        Prefs.saveAccounts(c, all)
        Prefs.setProfile(c, "local", n, "")
        return null
    }

    fun login(c: Context, name: String, pw: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Scrivi il nome dell'account."
        val acc = Prefs.accounts(c).optJSONObject(key(n)) ?: return "Nessun account con questo nome: creane uno."
        val salt = Base64.decode(acc.optString("salt"), Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(pw, salt).toByteArray(), acc.optString("hash").toByteArray())
        if (!ok) return "Password errata."
        Prefs.setProfile(c, "local", acc.optString("name", n), "")
        return null
    }

    /** Le password locali non si possono recuperare: l'unica via e' eliminare l'account e crearne uno nuovo. */
    fun delete(c: Context, name: String) {
        val all = Prefs.accounts(c); all.remove(key(name)); Prefs.saveAccounts(c, all)
    }
}

class GUser(val name: String, val email: String)

/** Accesso con Google: OAuth con PKCE nel browser di sistema (come GPT CORE Mobile). I token non vengono salvati. */
object GoogleAuth {
    fun clientId(c: Context) = Config.googleClientId(c)
    fun configured(c: Context) = clientId(c).isNotBlank()
    fun scheme(c: Context) = "com.googleusercontent.apps." + clientId(c).removeSuffix(".apps.googleusercontent.com")
    private fun redirect(c: Context) = scheme(c) + ":/oauth2redirect"

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun random(n: Int) = b64url(ByteArray(n).also { SecureRandom().nextBytes(it) })
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Apre il browser sulla pagina di accesso Google. Restituisce un errore oppure null. */
    fun start(a: Activity): String? {
        if (!configured(a)) return "Manca google.ios_client_id nel config.json."
        val verifier = random(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = random(16)
        Prefs.setPending(a, verifier, state)
        val uri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", clientId(a))
            .appendQueryParameter("redirect_uri", redirect(a))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid email profile")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("prompt", "select_account")
            .build()
        return try { a.startActivity(Intent(Intent.ACTION_VIEW, uri)); null } catch (e: Exception) { "Nessun browser disponibile per l'accesso." }
    }

    private fun readJson(con: HttpURLConnection): JSONObject {
        val code = con.responseCode
        val body = (if (code in 200..299) con.inputStream else (con.errorStream ?: con.inputStream)).bufferedReader().use { it.readText() }
        con.disconnect()
        return try { JSONObject(body) } catch (e: Exception) { JSONObject().put("error", "risposta non valida ($code)") }
    }

    /** Bloccante: scambia il codice ricevuto con le informazioni dell'utente. Lancia eccezione se Google rifiuta. */
    fun exchange(c: Context, code: String): GUser {
        val form = "client_id=" + enc(clientId(c)) + "&code=" + enc(code) + "&code_verifier=" + enc(Prefs.pendingVerifier(c)) +
            "&grant_type=authorization_code&redirect_uri=" + enc(redirect(c))
        val t = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
        t.requestMethod = "POST"; t.doOutput = true; t.connectTimeout = 15000; t.readTimeout = 20000
        t.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        t.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val tj = readJson(t)
        val at = tj.optString("access_token")
        if (at.isBlank()) throw IllegalStateException(tj.optString("error_description", tj.optString("error", "token mancante")))
        val u = URL("https://openidconnect.googleapis.com/v1/userinfo").openConnection() as HttpURLConnection
        u.connectTimeout = 15000; u.readTimeout = 20000
        u.setRequestProperty("Authorization", "Bearer $at")
        val uj = readJson(u)
        val email = uj.optString("email").lowercase()
        val name = uj.optString("given_name").ifBlank { uj.optString("name") }.ifBlank { email.substringBefore("@") }.ifBlank { "Utente" }
        return GUser(name.take(40), email)
    }
}
