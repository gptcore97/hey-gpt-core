package it.flubatir.coreassistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Ponte tra l'app web (window.Native) e Android: rete senza CORS, streaming, voce, permessi, assistente. */
class Bridge(private val act: MainActivity, private val wv: WebView) {

    private val ui = Handler(Looper.getMainLooper())
    private val streams = ConcurrentHashMap<String, HttpURLConnection>()
    private var listener: Listener? = null

    /** Chiama window.__nat.<fn>(args) nell'app web. */
    fun js(fn: String, vararg a: Any?) {
        val args = a.joinToString(",") { when (it) { null -> "null"; is String -> JSONObject.quote(it); else -> it.toString() } }
        ui.post { wv.evaluateJavascript("window.__nat&&window.__nat.$fn($args)", null) }
    }

    @JavascriptInterface fun isNative() = true

    // ------------------------------------------------------------------ RETE
    @JavascriptInterface fun http(id: String, req: String) {
        thread {
            try {
                val r = JSONObject(req)
                val body = if (r.has("body") && !r.isNull("body")) r.optString("body") else null
                val res = Net.request(r.getString("url"), Net.headersOf(r.optJSONObject("headers")), body,
                    r.optInt("timeout", 20000), r.optInt("timeout", 20000), r.optString("method", "GET"))
                js("onHttp", id, res.code, res.body)
            } catch (e: Exception) { js("onHttpErr", id, e.message ?: e.javaClass.simpleName) }
        }
    }

    @JavascriptInterface fun streamStart(id: String, req: String) {
        thread {
            var c: HttpURLConnection? = null
            try {
                val r = JSONObject(req)
                c = URL(r.getString("url")).openConnection() as HttpURLConnection
                c.requestMethod = r.optString("method", "POST")
                c.connectTimeout = 20000; c.readTimeout = 180000
                Net.headersOf(r.optJSONObject("headers")).forEach { (k, v) -> c.setRequestProperty(k, v) }
                streams[id] = c
                val body = r.optString("body", "")
                if (body.isNotEmpty()) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
                val code = c.responseCode
                js("onStream", id, "status", code.toString())
                val ins = if (code < 400) c.inputStream else (c.errorStream ?: c.inputStream)
                val rd = InputStreamReader(ins, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = rd.read(buf)
                    if (n < 0) break
                    js("onStream", id, "chunk", String(buf, 0, n))
                }
                js("onStream", id, "end", "")
            } catch (e: Exception) {
                if (streams.containsKey(id)) js("onStream", id, "error", e.message ?: e.javaClass.simpleName) else js("onStream", id, "end", "")
            } finally {
                streams.remove(id)
                try { c?.disconnect() } catch (e: Exception) { }
            }
        }
    }

    @JavascriptInterface fun streamAbort(id: String) {
        try { streams.remove(id)?.disconnect() } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ RICONOSCIMENTO VOCALE
    @JavascriptInterface fun sttStart(lang: String): Boolean {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ui.post { act.askPermissions() }
            return false
        }
        ui.post {
            listener?.stop()
            Core.busy = true
            listener = Listener(act.applicationContext, object : Listener.Callbacks {
                override fun onReady() = js("onStt", "ready", "")
                override fun onLevel(level: Float) = js("onStt", "level", level.toString())
                override fun onPartial(text: String) = js("onStt", "partial", text)
                override fun onFinal(text: String) { js("onStt", "final", text); js("onStt", "end", ""); Core.busy = false }
                override fun onError(code: Int) { js("onStt", "error", code.toString()); js("onStt", "end", ""); Core.busy = false }
            })
            if (listener?.available() != true) { js("onStt", "error", "unsupported"); js("onStt", "end", ""); Core.busy = false; return@post }
            listener?.start()
        }
        return true
    }

    @JavascriptInterface fun sttStop() {
        ui.post { listener?.stop(); listener = null; Core.busy = false; js("onStt", "end", "") }
    }

    // ------------------------------------------------------------------ VOCE
    @JavascriptInterface fun speak(id: String, text: String) {
        ui.post { Speaker.speak(act, text, { js("onTts", id, "start") }, { js("onTts", id, "end") }) }
    }

    @JavascriptInterface fun speakStop() { ui.post { Speaker.stop() } }

    // ------------------------------------------------------------------ CONFIG / STATO
    @JavascriptInterface fun syncConfig(json: String) { Prefs.setCfg(act, json) }

    private fun granted(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface fun getState(): String {
        val cr = act.contentResolver
        val isDefault = try {
            val a = Settings.Secure.getString(cr, "voice_interaction_service") ?: ""
            val b = Settings.Secure.getString(cr, "assistant") ?: ""
            a.contains(act.packageName) || b.contains(act.packageName)
        } catch (e: Exception) { false }
        return JSONObject()
            .put("isDefault", isDefault)
            .put("mic", granted(Manifest.permission.RECORD_AUDIO))
            .put("contacts", granted(Manifest.permission.READ_CONTACTS))
            .put("call", granted(Manifest.permission.CALL_PHONE))
            .put("overlay", Settings.canDrawOverlays(act))
            .put("wake", Prefs.wake(act))
            .put("facts", JSONArray(Prefs.facts(act)))
            .toString()
    }

    @JavascriptInterface fun requestPermissions() { ui.post { act.askPermissions() } }

    @JavascriptInterface fun openAssistantSettings() {
        ui.post {
            val tries = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            for (i in tries) { try { act.startActivity(i); break } catch (e: Exception) { } }
        }
    }

    @JavascriptInterface fun openOverlaySettings() {
        ui.post { try { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName))) } catch (e: Exception) { } }
    }

    @JavascriptInterface fun setWake(on: Boolean) {
        ui.post {
            Prefs.setWake(act, on)
            val i = Intent(act, WakeService::class.java)
            try { if (on) act.startForegroundService(i) else act.stopService(i) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface fun openAssistant() {
        ui.post { act.startActivity(Intent(act, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
    }

    @JavascriptInterface fun openExternal(url: String) {
        ui.post { try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }
    }

    @JavascriptInterface fun clearFacts() { Prefs.clearFacts(act); Brain.resetConversation() }

    @JavascriptInterface fun copy(text: String) {
        ui.post {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GPT CORE", text))
        }
    }
}
