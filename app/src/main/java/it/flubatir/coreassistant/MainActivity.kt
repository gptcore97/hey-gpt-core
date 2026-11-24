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
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Schermata di configurazione: chiave Gemini, permessi, impostazione come assistente predefinito, voce, parola di attivazione. */
class MainActivity : Activity() {

    private lateinit var body: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var nameField: EditText
    private lateinit var modelField: EditText

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
        build()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun build() {
        body.removeAllViews()
        val title = label("GPT CORE", 34f, Color.WHITE, true)
        title.post { title.paint.shader = LinearGradient(0f, 0f, title.width.toFloat(), 0f, intArrayOf(PALETTE[0], PALETTE[1], PALETTE[2]), null, Shader.TileMode.CLAMP); title.invalidate() }
        body.addView(title)
        body.addView(label("Assistente vocale di sistema con Gemini. Tieni premuto Home (o usa il gesto assistente) e parla.", 15f, MUTED))

        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(card(label("Stato", 13f, MUTED, true), statusBox))

        // 1. chiave
        keyField = field("Chiave API Gemini (AIza…)", Prefs.apiKey(this), true)
        nameField = field("Come ti chiamo?", Prefs.userName(this))
        modelField = field("Modello", Prefs.model(this))
        body.addView(card(
            label("1 · Chiave Gemini", 16f, Color.WHITE, true),
            label("Gratuita su aistudio.google.com/apikey. Resta solo sul telefono.", 13f, MUTED),
            keyField, nameField, modelField,
            button("Salva") {
                Prefs.setApiKey(this, keyField.text.toString()); Prefs.setUserName(this, nameField.text.toString().ifBlank { "Flubatir" })
                Prefs.setModel(this, modelField.text.toString()); Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show(); refreshStatus()
            },
            button("Ottieni la chiave gratis", false) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
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
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { (v as? TextView)?.setTextColor(Color.WHITE); Prefs.setVoiceName(this@MainActivity, voices[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
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
                    .setPositiveButton("Sì") { _, _ -> Prefs.clearFacts(this); Brain.resetConversation(); build() }.setNegativeButton("No", null).show()
            }
        ))
        refreshStatus()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        if (!::statusBox.isInitialized) return
        statusBox.removeAllViews()
        fun row(ok: Boolean, t: String) = statusBox.addView(label((if (ok) "✓  " else "✗  ") + t, 14f, if (ok) 0xFF34D399.toInt() else 0xFFF87171.toInt()).apply { setPadding(0, dp(4), 0, dp(4)) })
        row(Prefs.apiKey(this).isNotBlank(), "Chiave Gemini")
        row(granted(Manifest.permission.RECORD_AUDIO), "Microfono")
        row(granted(Manifest.permission.READ_CONTACTS), "Contatti")
        row(granted(Manifest.permission.CALL_PHONE), "Chiamate dirette")
        val isDefault = try {
            (Settings.Secure.getString(contentResolver, "voice_interaction_service") ?: "").contains(packageName)
        } catch (e: Exception) { false }
        row(isDefault, "Assistente predefinito")
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
