package it.flubatir.coreassistant

import android.Manifest
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppInfo(val label: String, val pkg: String)
data class ContactInfo(val id: Long, val name: String)
/** Scheda mostrata nel pannello (icona app + testo). */
data class Card(val title: String, val sub: String, val pkg: String? = null)
class ToolResult(val json: JSONObject, val card: Card? = null)

/** Tutte le azioni che l'assistente puo' fare sul telefono. */
class Tools(private val ctx: Context) {

    private val pm = ctx.packageManager

    /** Azioni che aprono altre app: partono quando l'assistente inizia a parlare. */
    val deferred = mutableListOf<() -> Unit>()

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        deferred.add { try { ctx.startActivity(i) } catch (e: Exception) { /* app non disponibile */ } }
    }

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun ok(vararg kv: Pair<String, Any?>) = JSONObject().put("ok", true).also { j -> kv.forEach { j.put(it.first, it.second) } }
    private fun fail(msg: String, vararg kv: Pair<String, Any?>) = JSONObject().put("ok", false).put("error", msg).also { j -> kv.forEach { j.put(it.first, it.second) } }

    // ------------------------------------------------------------------ APP
    private val aliases = mapOf(
        "fotocamera" to listOf("camera", "fotocamera"), "camera" to listOf("camera", "fotocamera"),
        "galleria" to listOf("galleria", "gallery", "foto", "photos"), "foto" to listOf("foto", "photos", "galleria", "gallery"),
        "impostazioni" to listOf("impostazioni", "settings"), "messaggi" to listOf("messaggi", "messages", "sms"),
        "telefono" to listOf("telefono", "phone", "dialer"), "calcolatrice" to listOf("calcolatrice", "calculator"),
        "orologio" to listOf("orologio", "clock", "sveglia"), "mappe" to listOf("maps", "mappe"),
        "browser" to listOf("chrome", "browser", "firefox", "edge"), "internet" to listOf("chrome", "browser", "internet"),
        "store" to listOf("play store", "store"), "musica" to listOf("spotify", "musica", "music", "youtube music"),
        "posta" to listOf("gmail", "posta", "mail", "outlook"), "mail" to listOf("gmail", "posta", "mail", "outlook"),
        "calendario" to listOf("calendario", "calendar"), "contatti" to listOf("contatti", "contacts"),
        "file" to listOf("file", "files", "gestione file"), "note" to listOf("note", "keep", "notes"),
    )

    fun apps(): List<AppInfo> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
    }

    private fun appNames(a: AppInfo): List<String> = listOf(a.label)

    fun findApps(query: String): List<Pair<AppInfo, Double>> {
        val all = apps()
        val q = Fuzzy.norm(query)
        val direct = Fuzzy.rank(query, all) { appNames(it) }.toMutableList()
        val extra = aliases[q]
        if (extra != null) for (w in extra) for ((a, s) in Fuzzy.rank(w, all) { appNames(it) }) {
            if (direct.none { it.first.pkg == a.pkg }) direct.add(a to (s * 0.92))
        }
        return direct.sortedByDescending { it.second }
    }

    fun openApp(name: String): ToolResult {
        val found = findApps(name)
        if (found.isEmpty()) return ToolResult(fail("nessuna app trovata con quel nome", "richiesta" to name))
        val (top, s1) = found[0]
        val s2 = if (found.size > 1) found[1].second else 0.0
        if (s1 >= 0.74 && (s1 - s2 >= 0.1 || s2 < 0.6)) {
            val li = pm.getLaunchIntentForPackage(top.pkg) ?: return ToolResult(fail("app non avviabile"))
            launch(li)
            return ToolResult(ok("app" to top.label), Card(top.label, "Apro l'app", top.pkg))
        }
        val c = JSONArray(); found.take(4).forEach { c.put(it.first.label) }
        return ToolResult(fail("nome ambiguo o incerto: chiedi all'utente quale app intende", "candidati" to c))
    }

    fun listApps(filter: String?): ToolResult {
        val l = if (filter.isNullOrBlank()) apps() else findApps(filter).map { it.first }
        val a = JSONArray(); l.sortedBy { it.label.lowercase() }.take(80).forEach { a.put(it.label) }
        return ToolResult(ok("app_installate" to a))
    }

    // ------------------------------------------------------------------ CONTATTI
    private fun contacts(): List<ContactInfo> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<ContactInfo>()
        ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                out.add(ContactInfo(c.getLong(0), n))
            }
        }
        return out
    }

    private class Resolved(val contact: ContactInfo?, val candidates: List<ContactInfo>, val needPerm: Boolean)

    private fun resolveContact(name: String): Resolved {
        if (!has(Manifest.permission.READ_CONTACTS)) return Resolved(null, emptyList(), true)
        val ranked = Fuzzy.rank(name, contacts()) { listOf(it.name) }
        if (ranked.isEmpty()) return Resolved(null, emptyList(), false)
        val (top, s1) = ranked[0]
        val s2 = if (ranked.size > 1) ranked[1].second else 0.0
        // piu' contatti con lo stesso nome identico = ambiguo
        val same = ranked.filter { it.second >= s1 - 0.02 }
        if (s1 >= 0.72 && same.size == 1 && (s1 - s2 >= 0.08 || s2 < 0.6)) return Resolved(top, emptyList(), false)
        return Resolved(null, ranked.take(4).map { it.first }, false)
    }

    private fun numbers(id: Long): List<String> {
        val res = ArrayList<Pair<String, Int>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                var w = 0
                if (c.getInt(2) != 0) w += 10
                if (c.getInt(1) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) w += 5
                res.add(n to w)
            }
        }
        return res.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    private fun contactError(name: String, r: Resolved): ToolResult {
        if (r.needPerm) return ToolResult(fail("manca il permesso per leggere i contatti: l'utente deve concederlo dall'app GPT CORE"))
        if (r.candidates.isNotEmpty()) {
            val c = JSONArray(); r.candidates.forEach { c.put(it.name) }
            return ToolResult(fail("contatto ambiguo: chiedi all'utente quale intende", "candidati" to c))
        }
        return ToolResult(fail("nessun contatto trovato con quel nome", "richiesta" to name))
    }

    // ------------------------------------------------------------------ SIM / CHIAMATE
    @Suppress("DEPRECATION")
    fun phoneState(): JSONObject {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val tel = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        var ready = false
        if (tel) {
            val slots = maxOf(1, tm.phoneCount)
            for (s in 0 until slots) if (tm.getSimState(s) == TelephonyManager.SIM_STATE_READY) ready = true
        }
        val air = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        return JSONObject().put("telefonia", tel).put("sim_pronta", ready).put("modalita_aereo", air)
    }

    private val callApps = listOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "org.telegram.messenger" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal", "com.facebook.orca" to "Messenger", "com.google.android.apps.tachyon" to "Google Meet",
        "com.skype.raider" to "Skype", "com.viber.voip" to "Viber", "us.zoom.videomeetings" to "Zoom",
        "com.discord" to "Discord", "com.instagram.android" to "Instagram", "com.microsoft.teams" to "Teams",
    )

    private fun installedCallApps(): List<Pair<String, String>> = callApps.filter {
        try { pm.getPackageInfo(it.first, 0); true } catch (e: Exception) { false }
    }

    /** Righe dati "chiamata" (WhatsApp, Telegram...) legate a un contatto: pacchetto -> (id riga, mimetype) */
    private fun voipRows(contactId: Long): Map<String, Pair<Long, String>> {
        val out = LinkedHashMap<String, Pair<Long, String>>()
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE),
            ContactsContract.Data.CONTACT_ID + "=? AND (" + ContactsContract.Data.MIMETYPE + " LIKE '%call%' OR " + ContactsContract.Data.MIMETYPE + " LIKE '%voip%')",
            arrayOf(contactId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val mt = c.getString(1) ?: continue
                val app = callApps.firstOrNull { mt.contains(it.first) } ?: continue
                if (!out.containsKey(app.first)) out[app.first] = c.getLong(0) to mt
            }
        }
        return out
    }

    fun callContact(name: String?, number: String?): ToolResult {
        val st = phoneState()
        var target = number?.trim().orEmpty()
        var shown = name ?: target
        var contact: ContactInfo? = null
        if (target.isEmpty()) {
            if (name.isNullOrBlank()) return ToolResult(fail("serve un nome o un numero"))
            val r = resolveContact(name)
            contact = r.contact ?: return contactError(name, r)
            shown = contact.name
            val nums = numbers(contact.id)
            val alt = voipRows(contact.id).keys.mapNotNull { k -> callApps.firstOrNull { it.first == k }?.second }
            if (nums.isEmpty() && alt.isEmpty()) return ToolResult(fail("il contatto ${contact.name} non ha numeri di telefono"))
            if (nums.isEmpty() || !st.getBoolean("sim_pronta") || st.getBoolean("modalita_aereo")) {
                val a = JSONArray(); alt.forEach { a.put(it) }
                val others = JSONArray(); installedCallApps().forEach { if (!alt.contains(it.second)) others.put(it.second) }
                val why = when {
                    !st.getBoolean("telefonia") -> "questo dispositivo non ha telefonia"
                    st.getBoolean("modalita_aereo") -> "modalita' aereo attiva"
                    nums.isEmpty() -> "il contatto non ha un numero"
                    else -> "nessuna SIM pronta nel telefono"
                }
                return ToolResult(fail("impossibile chiamare con la linea telefonica: $why",
                    "contatto" to contact.name, "app_con_questo_contatto" to a, "altre_app_per_chiamare_installate" to others),
                    Card(contact.name, "Chiamata non possibile: $why"))
            }
            target = nums[0]
        } else if (!st.getBoolean("sim_pronta") || st.getBoolean("modalita_aereo")) {
            val others = JSONArray(); installedCallApps().forEach { others.put(it.second) }
            return ToolResult(fail("nessuna SIM pronta o modalita' aereo attiva", "altre_app_per_chiamare_installate" to others))
        }
        val uri = Uri.fromParts("tel", target, null)
        launch(Intent(if (has(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        return ToolResult(ok("chiamando" to shown, "numero" to target), Card(shown, "Chiamata in corso · $target"))
    }

    fun callViaApp(name: String, appName: String): ToolResult {
        val r = resolveContact(name)
        val c = r.contact ?: return contactError(name, r)
        val rows = voipRows(c.id)
        val wanted = callApps.filter { Fuzzy.score(appName, it.second) >= 0.7 || it.second.lowercase().contains(Fuzzy.norm(appName)) }
        val hit = wanted.firstOrNull { rows.containsKey(it.first) }
        if (hit == null) {
            val avail = JSONArray(); rows.keys.forEach { k -> callApps.firstOrNull { it.first == k }?.let { avail.put(it.second) } }
            val open = findApps(appName).firstOrNull()?.first
            if (open != null && wanted.isNotEmpty()) {
                pm.getLaunchIntentForPackage(open.pkg)?.let { launch(it) }
                return ToolResult(ok("nota" to "contatto non collegato a ${open.label}: ho aperto l'app, la chiamata va avviata a mano",
                    "app_disponibili_per_contatto" to avail), Card(open.label, "Apro l'app per chiamare ${c.name}", open.pkg))
            }
            return ToolResult(fail("il contatto non e' raggiungibile con quell'app", "app_disponibili_per_contatto" to avail))
        }
        val (rowId, mime) = rows[hit.first]!!
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId), mime).setPackage(hit.first)
        launch(i)
        return ToolResult(ok("chiamando" to c.name, "via" to hit.second), Card(c.name, "Chiamata ${hit.second}", hit.first))
    }

    fun sendMessage(name: String, text: String, via: String?): ToolResult {
        val r = resolveContact(name)
        val c = r.contact ?: return contactError(name, r)
        val num = numbers(c.id).firstOrNull() ?: return ToolResult(fail("il contatto non ha numeri"))
        val clean = num.replace(Regex("[^0-9+]"), "")
        val wa = via?.lowercase()?.contains("whats") == true
        if (wa) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + clean.removePrefix("+") + "?text=" + Uri.encode(text)))
            launch(i)
            return ToolResult(ok("a" to c.name, "via" to "WhatsApp", "nota" to "messaggio preparato, l'utente deve premere invio"), Card(c.name, "WhatsApp pronto da inviare", "com.whatsapp"))
        }
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean")).putExtra("sms_body", text))
        return ToolResult(ok("a" to c.name, "via" to "SMS", "nota" to "messaggio preparato, l'utente deve premere invio"), Card(c.name, "SMS pronto da inviare"))
    }

    // ------------------------------------------------------------------ DISPOSITIVO
    fun flashlight(on: Boolean): ToolResult {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return ToolResult(fail("flash non disponibile"))
            cm.setTorchMode(id, on)
            ToolResult(ok("torcia" to if (on) "accesa" else "spenta"), Card("Torcia", if (on) "Accesa" else "Spenta"))
        } catch (e: Exception) { ToolResult(fail("torcia non controllabile: ${e.message}")) }
    }

    fun setVolume(percent: Int, stream: String?): ToolResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val st = when (stream?.lowercase()) {
            "suoneria", "ring" -> AudioManager.STREAM_RING
            "sveglia", "alarm" -> AudioManager.STREAM_ALARM
            "chiamata", "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(st)
        val v = (percent.coerceIn(0, 100) * max / 100.0).toInt()
        return try {
            am.setStreamVolume(st, v, AudioManager.FLAG_SHOW_UI)
            ToolResult(ok("volume_percento" to percent.coerceIn(0, 100)), Card("Volume", "${percent.coerceIn(0, 100)}%"))
        } catch (e: Exception) { ToolResult(fail("non posso cambiare il volume ora: ${e.message}")) }
    }

    fun setAlarm(hour: Int, minute: Int, label: String?): ToolResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "GPT CORE").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        launch(i)
        return ToolResult(ok("sveglia" to String.format(Locale.ITALY, "%02d:%02d", hour, minute)), Card("Sveglia", String.format(Locale.ITALY, "%02d:%02d", hour, minute)))
    }

    fun setTimer(seconds: Int, label: String?): ToolResult {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Timer").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        launch(i)
        val m = seconds / 60; val s = seconds % 60
        return ToolResult(ok("timer_secondi" to seconds), Card("Timer", if (m > 0) "$m min" + (if (s > 0) " $s s" else "") else "$s s"))
    }

    fun navigate(dest: String): ToolResult {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
        val maps = "com.google.android.apps.maps"
        if (try { pm.getPackageInfo(maps, 0); true } catch (e: Exception) { false }) i.setPackage(maps)
        else i.data = Uri.parse("geo:0,0?q=" + Uri.encode(dest))
        launch(i)
        return ToolResult(ok("destinazione" to dest), Card(dest, "Navigazione", maps))
    }

    fun playMusic(query: String): ToolResult {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        return if (i.resolveActivity(pm) != null) { launch(i); ToolResult(ok("riproduco" to query), Card(query, "Musica")) }
        else { launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))); ToolResult(ok("riproduco" to query, "nota" to "aperta la ricerca su YouTube"), Card(query, "YouTube", "com.google.android.youtube")) }
    }

    fun openUrl(url: String): ToolResult {
        val u = if (url.startsWith("http")) url else "https://$url"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return ToolResult(ok("aperto" to u), Card(u.removePrefix("https://"), "Browser"))
    }

    fun webSearchBrowser(q: String): ToolResult {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        return ToolResult(ok("cerco" to q), Card(q, "Ricerca sul browser"))
    }

    fun openSettings(section: String?): ToolResult {
        val s = section?.lowercase().orEmpty()
        val i = when {
            s.contains("wifi") || s.contains("wi-fi") || s.contains("internet") || s.contains("rete") ->
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS)
            s.contains("blue") -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            s.contains("volume") || s.contains("audio") || s.contains("suon") -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_VOLUME) else Intent(Settings.ACTION_SOUND_SETTINGS)
            s.contains("schermo") || s.contains("display") || s.contains("lumin") -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            s.contains("batter") -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            s.contains("posizion") || s.contains("gps") || s.contains("location") -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            s.contains("nfc") -> Intent(Settings.ACTION_NFC_SETTINGS)
            s.contains("aere") || s.contains("airplane") -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            s.contains("app") -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            s.contains("hotspot") || s.contains("tether") || s.contains("condivi") -> Intent("android.settings.TETHER_SETTINGS")
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        launch(i)
        return ToolResult(ok("impostazioni" to (section ?: "generali")), Card("Impostazioni", section ?: "Generali", "com.android.settings"))
    }

    fun takePhoto(): ToolResult {
        launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        return ToolResult(ok("fotocamera" to "aperta"), Card("Fotocamera", "Aperta"))
    }

    fun createEvent(title: String, startIso: String, durationMin: Int): ToolResult {
        val fmts = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss")
        var d: Date? = null
        for (f in fmts) { try { d = SimpleDateFormat(f, Locale.ITALY).parse(startIso); if (d != null) break } catch (e: Exception) { } }
        if (d == null) return ToolResult(fail("data non valida, usa il formato AAAA-MM-GGTHH:MM"))
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, d.time)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, d.time + durationMin * 60_000L)
        launch(i)
        return ToolResult(ok("evento" to title, "nota" to "l'utente deve confermare il salvataggio nel calendario"),
            Card(title, SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALY).format(d), null))
    }

    fun deviceStatus(): ToolResult {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dati mobili"
            else -> "connesso"
        }
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALY).format(Date())
        val j = ok("batteria_percento" to lvl, "in_carica" to charging, "rete" to net, "data_ora" to now, "telefono" to phoneState(), "modello" to (Build.MANUFACTURER + " " + Build.MODEL))
        return ToolResult(j, Card("Batteria $lvl%", (if (charging) "In carica · " else "") + net))
    }

    // ------------------------------------------------------------------ DISPATCH
    fun execute(name: String, a: JSONObject): ToolResult = try {
        when (name) {
            "open_app" -> openApp(a.optString("name"))
            "list_apps" -> listApps(a.optString("filter"))
            "call_contact" -> callContact(a.optString("name").ifBlank { null }, a.optString("number").ifBlank { null })
            "call_via_app" -> callViaApp(a.optString("name"), a.optString("app"))
            "send_message" -> sendMessage(a.optString("name"), a.optString("text"), a.optString("via"))
            "check_phone_status" -> ToolResult(ok("stato" to phoneState(), "app_per_chiamare_installate" to JSONArray(installedCallApps().map { it.second })))
            "flashlight" -> flashlight(a.optBoolean("on", true))
            "set_volume" -> setVolume(a.optInt("percent", 50), a.optString("stream"))
            "set_alarm" -> setAlarm(a.optInt("hour"), a.optInt("minute"), a.optString("label").ifBlank { null })
            "set_timer" -> setTimer(a.optInt("seconds", 60), a.optString("label").ifBlank { null })
            "navigate" -> navigate(a.optString("destination"))
            "play_music" -> playMusic(a.optString("query"))
            "open_url" -> openUrl(a.optString("url"))
            "search_in_browser" -> webSearchBrowser(a.optString("query"))
            "open_settings" -> openSettings(a.optString("section"))
            "take_photo" -> takePhoto()
            "create_event" -> createEvent(a.optString("title"), a.optString("start"), a.optInt("duration_min", 60))
            "device_status" -> deviceStatus()
            "remember" -> { Prefs.addFact(ctx, a.optString("fact")); ToolResult(ok("memorizzato" to a.optString("fact")), Card("Memorizzato", a.optString("fact"))) }
            else -> ToolResult(fail("strumento sconosciuto: $name"))
        }
    } catch (e: Exception) { ToolResult(fail("errore: ${e.message}")) }
}
