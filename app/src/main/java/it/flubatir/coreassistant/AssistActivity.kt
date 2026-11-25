package it.flubatir.coreassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Stato condiviso: mentre l'assistente e' aperto la parola di attivazione si mette in pausa (un solo microfono alla volta). */
object Core { @Volatile var busy = false }

class AssistActivity : Activity(), Listener.Callbacks {

    private lateinit var dim: View
    private lateinit var glow: GlowView
    private lateinit var panel: LinearLayout
    private lateinit var tvUser: TextView
    private lateinit var tvAnswer: TextView
    private lateinit var cards: LinearLayout
    private lateinit var cardsScroll: HorizontalScrollView
    private lateinit var wave: WaveView
    private lateinit var actionBtn: TextView
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText

    private val ui = Handler(Looper.getMainLooper())
    private val closeTask = Runnable { dismiss(false) }
    private var listener: Listener? = null
    private val brain by lazy { Brain(applicationContext) }
    private var lastPartial = ""
    private var thinking = false
    private var closing = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ UI
    private fun roundBg(color: Int, r: Float, top: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (top) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
    }

    private fun circleBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = roundBg(0xFF2A2C2E.toInt(), dp(24).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        Core.busy = true

        val root = FrameLayout(this)
        dim = View(this).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f; setOnClickListener { dismiss(true) } }
        glow = GlowView(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(0xF51E1F20.toInt(), dp(28).toFloat(), true)
            setPadding(dp(22), dp(6), dp(22), dp(30))
            isClickable = true
        }
        val grip = View(this).apply {
            background = roundBg(0xFF5A5C5E.toInt(), dp(2).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(10) }
        }
        val gripZone = FrameLayout(this).apply {
            addView(grip, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
        }
        var y0 = 0f
        gripZone.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { panel.translationY = maxOf(0f, e.rawY - y0); true }
                else -> { if (panel.translationY > dp(90)) dismiss(true) else panel.animate().translationY(0f).setDuration(180).start(); true }
            }
        }
        tvUser = TextView(this).apply { textSize = 15f; setTextColor(0xFF9AA0A6.toInt()); text = "Sto ascoltando…"; maxLines = 3 }
        tvAnswer = TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(4)); maxLines = 9
        }
        cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        cardsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(cards); visibility = View.GONE }
        actionBtn = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
            background = roundBg(0xFF3B6FD4.toInt(), dp(22).toFloat())
            setPadding(dp(18), dp(11), dp(18), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        input = EditText(this).apply {
            hint = "Scrivi a GPT CORE…"; setHintTextColor(0xFF80868B.toInt()); setTextColor(Color.WHITE); textSize = 16f
            background = roundBg(0xFF2A2C2E.toInt(), dp(24).toFloat()); setPadding(dp(18), dp(12), dp(18), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
            addView(input); addView(circleBtn("➤") { sendTyped() }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8) })
        }
        wave = WaveView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f)
            setOnClickListener { onWaveTap() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(circleBtn("⌨") { toggleKeyboard() })
            addView(wave)
            addView(circleBtn("✕") { dismiss(true) })
        }
        panel.addView(gripZone); panel.addView(tvUser); panel.addView(tvAnswer); panel.addView(cardsScroll); panel.addView(actionBtn)
        panel.addView(inputRow); panel.addView(bottom)

        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
        root.addView(glow, FrameLayout.LayoutParams(-1, dp(200), Gravity.BOTTOM))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator(1.7f)).start()
            dim.animate().alpha(1f).setDuration(300).start()
        }
        Speaker.warmUp(this)
        Speaker.onLevel = { l -> wave.setLevel(l); glow.setLevel(l) }
        listener = Listener(applicationContext, this)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val t = i?.getStringExtra("text")
        if (!t.isNullOrBlank()) process(t) else startListening()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closing = false
        ui.removeCallbacks(closeTask)
        if (!thinking) handleIntent(intent)
    }

    // ------------------------------------------------------------------ ASCOLTO
    private fun showSetup(msg: String, btn: String?, action: (() -> Unit)?) {
        wave.state = VState.IDLE; glow.active = false
        tvUser.text = ""; tvAnswer.text = msg
        if (btn != null && action != null) { actionBtn.text = btn; actionBtn.visibility = View.VISIBLE; actionBtn.setOnClickListener { action() } }
        else actionBtn.visibility = View.GONE
    }

    private fun openApp() { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); dismiss(false) }

    private fun startListening() {
        ui.removeCallbacks(closeTask)
        Speaker.stop()
        actionBtn.visibility = View.GONE
        if (!Brain.ready(this)) { showSetup("Per iniziare aggiungi una API (e un modello) dallo Store dell'app.", "Apri GPT CORE") { openApp() }; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (listener?.available() != true) { showSetup("Il riconoscimento vocale non è disponibile. Serve l'app Google installata e aggiornata.", null, null); return }
        lastPartial = ""
        tvUser.text = "Sto ascoltando…"
        wave.state = VState.LISTENING; glow.active = true
        listener?.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
            else showSetup("Senza il permesso del microfono non posso ascoltarti.", "Apri GPT CORE") { openApp() }
        }
    }

    private fun onWaveTap() {
        when (wave.state) {
            VState.LISTENING -> if (lastPartial.isNotBlank()) { listener?.stop(); process(lastPartial) } else startListening()
            VState.SPEAKING -> { Speaker.stop(); startListening() }
            VState.IDLE -> startListening()
            VState.THINKING -> {}
        }
    }

    private fun toggleKeyboard() {
        if (inputRow.visibility == View.VISIBLE) {
            inputRow.visibility = View.GONE
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            startListening()
        } else {
            listener?.stop(); Speaker.stop(); ui.removeCallbacks(closeTask)
            wave.state = VState.IDLE
            inputRow.visibility = View.VISIBLE
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        process(t)
    }

    // Listener.Callbacks
    override fun onReady() { if (!thinking) tvUser.text = "Parla pure…" }
    override fun onLevel(level: Float) { if (wave.state == VState.LISTENING) { wave.setLevel(level); glow.setLevel(level) } }
    override fun onPartial(text: String) { lastPartial = text; tvUser.text = text }
    override fun onFinal(text: String) { process(text) }
    override fun onError(code: Int) {
        if (thinking || closing) return
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (lastPartial.isNotBlank()) { process(lastPartial); return }
                wave.state = VState.IDLE; glow.active = false
                tvUser.text = "Non ti ho sentito. Tocca l'onda per riprovare."
                ui.postDelayed(closeTask, 6000)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            SpeechRecognizer.ERROR_CLIENT -> {}
            else -> showSetup("Il riconoscimento vocale ha avuto un problema (codice $code). Controlla internet e l'app Google, poi tocca l'onda per riprovare.", null, null)
        }
    }

    // ------------------------------------------------------------------ ELABORAZIONE
    private fun process(text: String) {
        if (thinking) return
        thinking = true
        ui.removeCallbacks(closeTask)
        wave.state = VState.THINKING; glow.active = true
        tvUser.text = text; tvAnswer.text = ""
        cards.removeAllViews(); cardsScroll.visibility = View.GONE; actionBtn.visibility = View.GONE
        Thread {
            val r = brain.ask(text) { s -> runOnUiThread { if (!closing) tvAnswer.text = s } }
            runOnUiThread { showReply(r) }
        }.start()
    }

    private fun addCard(c: Card) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundBg(0xFF2A2C2E.toInt(), dp(16).toFloat()); setPadding(dp(12), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        }
        if (c.pkg != null) try {
            row.addView(ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(c.pkg)); layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) } })
        } catch (e: Exception) { }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = c.title; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 })
        col.addView(TextView(this).apply { text = c.sub; setTextColor(0xFF9AA0A6.toInt()); textSize = 12f; maxLines = 2; maxWidth = dp(220) })
        row.addView(col)
        cards.addView(row)
        cardsScroll.visibility = View.VISIBLE
    }

    private fun showReply(r: Reply) {
        thinking = false
        if (closing && isFinishing) { /* l'utente ha chiuso: parla comunque, ma non aggiorniamo la UI */ }
        tvAnswer.text = r.text
        r.cards.forEach { addCard(it) }
        var ran = false
        val runDeferred = { if (!ran) { ran = true; r.deferred.forEach { try { it() } catch (e: Exception) { } } } }
        Speaker.speak(applicationContext, r.text,
            onStart = { wave.state = VState.SPEAKING; runDeferred() },
            onDone = {
                runDeferred()
                wave.state = VState.IDLE
                if (!closing && !isDestroyed) {
                    if (r.expectsAnswer) startListening() else { glow.active = false; ui.postDelayed(closeTask, if (r.deferred.isEmpty()) 5000 else 800) }
                }
            })
    }

    // ------------------------------------------------------------------ CHIUSURA
    private fun dismiss(stopVoice: Boolean) {
        if (closing) return
        closing = true
        if (stopVoice) Speaker.stop()
        listener?.stop()
        ui.removeCallbacks(closeTask)
        dim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(240).withEndAction {
            finish(); overridePendingTransition(0, 0)
        }.start()
    }

    override fun onBackPressed() = dismiss(true)

    override fun onDestroy() {
        Core.busy = false
        ui.removeCallbacksAndMessages(null)
        listener?.stop()
        Speaker.onLevel = null
        super.onDestroy()
    }
}
