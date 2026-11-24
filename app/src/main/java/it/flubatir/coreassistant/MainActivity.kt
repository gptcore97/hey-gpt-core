package it.flubatir.coreassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Schermata principale: account, chiave e modello Gemini, ricerca web, permessi, assistente predefinito, voce, parola di attivazione. */
class MainActivity : Activity() {

    private lateinit var body: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var nameField: EditText
    private lateinit var modelBtn: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var builtKey = ""

    private val BG = 0xFF131314.toInt()
    private val CARD = 0xFF1E1F20.toInt()
    private val MUTED = 0xFF9AA0A6.toInt()
    private val BLUE = 0xFF3B6FD4.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun label(t: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.12f)
    }

    private fun gradient(t: String, size: Float) = label(t, size, Color.WHITE, true).also { v ->
        v.post { v.paint.shader = LinearGradient(0f, 0f, v.width.toFloat().coerceAtLeast(1f), 0f, intArrayOf(PALETTE[0], PALETTE[1], PALETTE[2]), null, Shader.TileMode.CLAMP); v.invalidate() }
    }

    private fun card(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = bg(CARD, 20); setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        views.forEach { addView(it) }
    }

    private fun button(t: String, primary: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        background = bg(if (primary) BLUE else 0xFF2E3032.toInt(), 24); setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; setHintTextColor(0xFF6F757A.toInt()); setTextColor(Color.WHITE); textSize = 15f
        background = bg(0xFF2A2C2E.toInt(), 14); setPadding(dp(14), dp(12), dp(14), dp(12)); setSingleLine(true)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(40), dp(18), dp(40)) }
        scroll.addView(body)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Prima l'accesso: senza account si apre la schermata di login.
        if (!Prefs.loggedIn(this)) {
            builtKey = ""
            startActivity(Intent(this, AuthActivity::class.java))
            return
        }
        val k = Prefs.userName(this)
        if (k != builtKey) { builtKey = k; build() } else refreshStatus()
    }

    private fun build() {
        body.removeAllViews()
        body.addView(gradient("GPT CORE", 34f))
        val hello = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hello.addView(label("Ciao, ", 22f, Color.WHITE))
        hello.addView(gradient(Prefs.userName(this).ifBlank { "benvenuto" }, 22f))
        body.addView(hello)
        body.addView(label("Assistente vocale di sistema con Gemini. Tieni premuto Home (o usa il gesto assistente) e parla.", 15f, MUTED).apply { setPadding(0, dp(6), 0, 0) })

        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(card(label("Stato", 13f, MUTED, true), statusBox))

        // account
        val p = Prefs.profile(this)
        val how = if (p != null && p.optString("method") == "google") "Accesso con Google" + (p.optString("email").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                  else "Account locale (solo su questo telefono)"
        nameField = field("Il tuo nome", Prefs.userName(this))
        body.addView(card(
            label("Account", 16f, Color.WHITE, true),
            label(how, 13f, MUTED),
            nameField,
            button("Salva nome", false) {
                val n = nameField.text.toString().trim()
                if (n.isEmpty()) { Toast.makeText(this, "Scrivi un nome", Toast.LENGTH_SHORT).show(); return@button }
                Prefs.setUserName(this, n); Toast.makeText(this, "Ok, ti chiamo $n", Toast.LENGTH_SHORT).show(); builtKey = ""; onResume()
            },
            button("Esci dall'account", false) {
                AlertDialog.Builder(this).setTitle("Uscire dall'account?")
                    .setMessage("Dovrai accedere di nuovo. Chiave, modello e memoria restano su questo telefono.")
                    .setPositiveButton("Esci") { _, _ -> Prefs.logout(this); Brain.resetConversation(); builtKey = ""; onResume() }
                    .setNegativeButton("Annulla", null).show()
            }
        ))

        // 1. chiave + modello + web
        keyField = field("Chiave API Gemini (AIza…)", Prefs.apiKey(this), true)
        modelBtn = button("", false) { pickModel() }
        updateModelBtn()
        val swFallback = Switch(this).apply {
            text = "Se un modello non risponde, passa al più simile"; setTextColor(Color.WHITE); isChecked = Prefs.autoFallback(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAutoFallback(this@MainActivity, c) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        val webLabels = arrayOf("Auto: decide l'assistente", "Sempre: cerca a ogni domanda", "Spenta")
        val webSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, webLabels)
            setSelection(Prefs.WEB_MODES.indexOf(Prefs.webMode(this@MainActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(pa: AdapterView<*>?, v: View?, pos: Int, id: Long) { (v as? TextView)?.setTextColor(Color.WHITE); Prefs.setWebMode(this@MainActivity, Prefs.WEB_MODES[pos]) }
                override fun onNothingSelected(pa: AdapterView<*>?) {}
            }
        }
        body.addView(card(
            label("1 · Gemini", 16f, Color.WHITE, true),
            label("Chiave gratuita su aistudio.google.com/apikey. Resta solo sul telefono.", 13f, MUTED),
            keyField,
            button("Verifica e salva la chiave") { verifyKey() },
            button("Ottieni la chiave gratis", false) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) },
            label("Modello", 13f, MUTED, true).apply { setPadding(0, dp(14), 0, 0) },
            modelBtn,
            swFallback,
            label("Quando Gemini risponde «riprova più tardi» (sovraccarico o quota), provo da solo il modello più simile, anche in mezzo a una conversazione.", 12f, MUTED).apply { setPadding(0, dp(4), 0, 0) },
            label("Ricerca web", 13f, MUTED, true).apply { setPadding(0, dp(14), 0, dp(2)) },
            webSpin
        ))

        // 2. permessi
        body.addView(card(
            label("2 · Permessi", 16f, Color.WHITE, true),
            label("Microfono (ascoltarti), Contatti e Telefono (chiamare), Notifiche.", 13f, MUTED),
            button("Concedi i permessi") { askPermissions() }
        ))

        // 3. assistente predefinito
        body.addView(card(
            label("3 · Assistente predefinito", 16f, Color.WHITE, true),
            label("Scegli «GPT CORE» come app assistente digitale: da quel momento la pressione lunga su Home (o il gesto dagli angoli, o il tasto power) apre GPT CORE al posto di Google.", 13f, MUTED),
            button("Apri impostazioni assistente") { openAssistantSettings() }
        ))

        // 4. voce
        val voices = arrayOf("Kore", "Puck", "Charon", "Aoede", "Fenrir", "Leda", "Zephyr", "Orus", "Callirrhoe", "Algenib")
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voices)
            setSelection(voices.indexOf(Prefs.voiceName(this@MainActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(pa: AdapterView<*>?, v: View?, pos: Int, id: Long) { (v as? TextView)?.setTextColor(Color.WHITE); Prefs.setVoiceName(this@MainActivity, voices[pos]) }
                override fun onNothingSelected(pa: AdapterView<*>?) {}
            }
        }
        val swVoice = Switch(this).apply {
            text = "Voce naturale Gemini"; setTextColor(Color.WHITE); isChecked = Prefs.geminiVoice(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setGeminiVoice(this@MainActivity, c) }
        }
        body.addView(card(
            label("4 · Voce", 16f, Color.WHITE, true),
            label("Con l'interruttore spento usa la voce di sistema di Android (più veloce, meno naturale).", 13f, MUTED),
            swVoice, sp,
            button("Ascolta la voce", false) { Speaker.speak(this, "Ciao ${Prefs.userName(this)}, sono GPT CORE. Dimmi pure cosa vuoi fare.", {}, {}) }
        ))

        // 5. wake word
        val swWake = Switch(this).apply {
            text = "Parola di attivazione «Ehi Core»"; setTextColor(Color.WHITE); isChecked = Prefs.wake(this@MainActivity)
            setOnCheckedChangeListener { _, c -> toggleWake(c) }
        }
        body.addView(card(
            label("5 · Ehi Core (facoltativo)", 16f, Color.WHITE, true),
            label("Resta in ascolto in background e si apre quando dici «Ehi Core» (anche con il comando: «Ehi Core, chiama Marco»). Richiede il permesso di sovrapposizione e consuma batteria.", 13f, MUTED),
            swWake,
            button("Permesso «Mostra sopra altre app»", false) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        ))

        // 6. prova
        body.addView(card(
            label("6 · Prova", 16f, Color.WHITE, true),
            label("Esempi: «Chiama Marco» · «Apri WhatsApp» · «Che tempo fa domani a Roma?» · «Svegliami alle 7» · «Portami a casa» · «Ricorda che mamma è Maria Rossi».", 13f, MUTED),
            button("Prova l'assistente ora") { startActivity(Intent(this, AssistActivity::class.java)) },
            button("Cancella la memoria (${Prefs.facts(this).size} cose ricordate)", false) {
                AlertDialog.Builder(this).setMessage("Dimenticare tutto quello che mi hai chiesto di ricordare?")
                    .setPositiveButton("Sì") { _, _ -> Prefs.clearFacts(this); Brain.resetConversation(); builtKey = ""; onResume() }.setNegativeButton("No", null).show()
            }
        ))

        // 7. config
        body.addView(card(
            label("7 · config.json", 16f, Color.WHITE, true),
            label("Letto a ogni avvio (prompt del creatore, modello, ricerca web, ripiego). Puoi importarne uno nuovo senza ricompilare; l'ID Google si cambia solo ricompilando l'app.", 13f, MUTED),
            button("Importa config.json", false) {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
                startActivityForResult(Intent.createChooser(i, "Scegli config.json"), 21)
            }
        ))
        refreshStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val txt = try { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } } catch (e: Exception) { null }
        val err = Config.importFrom(this, txt ?: "")
        Toast.makeText(this, err ?: "config.json importato", Toast.LENGTH_LONG).show()
        builtKey = ""; onResume()
    }

    // ---- Modello -----------------------------------------------------------
    private fun updateModelBtn() { modelBtn.text = Prefs.model(this) + "  ▾" }

    private fun verifyKey() {
        val k = keyField.text.toString().trim()
        if (k.isEmpty()) { Toast.makeText(this, "Incolla prima la chiave", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Verifico la chiave…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val l = Models.refresh(this, k)
                ui.post { Prefs.setApiKey(this, k); Toast.makeText(this, "Chiave verificata e salvata (${l.size} modelli disponibili)", Toast.LENGTH_LONG).show(); refreshStatus() }
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "Impossibile verificare: ${e.message}. La chiave NON è stata salvata.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun pickModel() {
        val key = keyField.text.toString().trim().ifBlank { Prefs.apiKey(this) }
        if (Models.cached(this).isEmpty() && key.isNotBlank()) {
            Toast.makeText(this, "Carico l'elenco dei modelli…", Toast.LENGTH_SHORT).show()
            Thread {
                val l = try { Models.refresh(this, key) } catch (e: Exception) { emptyList() }
                ui.post { showModelDialog(l.ifEmpty { Models.available(this) }) }
            }.start()
        } else showModelDialog(Models.available(this))
    }

    private fun showModelDialog(list: List<String>) {
        val cur = Prefs.model(this)
        val items = list.toMutableList().also { if (cur !in it) it.add(0, cur) }
        val labels = (items + "✎  Scrivi il nome a mano").toTypedArray()
        AlertDialog.Builder(this).setTitle("Scegli il modello")
            .setSingleChoiceItems(labels, items.indexOf(cur)) { d, which ->
                d.dismiss()
                if (which == items.size) typeModel() else { Prefs.setModel(this, items[which]); updateModelBtn(); refreshStatus() }
            }
            .setNeutralButton("Aggiorna elenco") { _, _ -> Prefs.clearModelsCache(this); pickModel() }
            .setNegativeButton("Annulla", null).show()
    }

    private fun typeModel() {
        val f = EditText(this).apply { setText(Prefs.model(this@MainActivity)); setSingleLine(true); setPadding(dp(20), dp(14), dp(20), dp(14)) }
        AlertDialog.Builder(this).setTitle("Nome del modello").setView(f)
            .setPositiveButton("Usa") { _, _ -> val v = f.text.toString().trim(); if (v.isNotEmpty()) { Prefs.setModel(this, v); updateModelBtn(); refreshStatus() } }
            .setNegativeButton("Annulla", null).show()
    }

    // ---- Stato / permessi ----------------------------------------------------
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        if (!::statusBox.isInitialized) return
        statusBox.removeAllViews()
        fun row(ok: Boolean, t: String) = statusBox.addView(label((if (ok) "✓  " else "✗  ") + t, 14f, if (ok) 0xFF34D399.toInt() else 0xFFF87171.toInt()).apply { setPadding(0, dp(4), 0, dp(4)) })
        row(Prefs.loggedIn(this), "Accesso: " + Prefs.userName(this).ifBlank { "non effettuato" })
        row(Prefs.apiKey(this).isNotBlank(), "Chiave Gemini")
        row(granted(Manifest.permission.RECORD_AUDIO), "Microfono")
        row(granted(Manifest.permission.READ_CONTACTS), "Contatti")
        row(granted(Manifest.permission.CALL_PHONE), "Chiamate dirette")
        val isDefault = try {
            (Settings.Secure.getString(contentResolver, "voice_interaction_service") ?: "").contains(packageName)
        } catch (e: Exception) { false }
        row(isDefault, "Assistente predefinito")
        val last = Prefs.lastModel(this)
        val info = "Modello: " + Prefs.model(this) + if (last.isNotBlank() && last != Prefs.model(this)) " · ultimo usato: $last" else ""
        statusBox.addView(label(info, 13f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun askPermissions() {
        val l = arrayListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(l.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refreshStatus()
    }

    private fun openAssistantSettings() {
        val tries = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) { try { startActivity(i); return } catch (e: Exception) { } }
    }

    private fun toggleWake(on: Boolean) {
        Prefs.setWake(this, on)
        val i = Intent(this, WakeService::class.java)
        if (on) {
            if (!granted(Manifest.permission.RECORD_AUDIO)) { askPermissions(); return }
            if (!Settings.canDrawOverlays(this)) Toast.makeText(this, "Concedi anche «Mostra sopra altre app», altrimenti Android blocca l'apertura.", Toast.LENGTH_LONG).show()
            startForegroundService(i)
        } else stopService(i)
    }
}
