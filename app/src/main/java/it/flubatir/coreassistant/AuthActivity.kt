package it.flubatir.coreassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Schermata di accesso (stesso stile di GPT CORE Mobile): Google oppure account locale con nome e password. */
class AuthActivity : Activity() {

    private lateinit var root: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    private var mode = "login"      // login | register | wait
    private var err = ""
    private var nameVal = ""
    private var nameField: EditText? = null
    private var pwField: EditText? = null
    private var pw2Field: EditText? = null

    private val BG = 0xFF131314.toInt()
    private val MUTED = 0xFF9AA0A6.toInt()
    private val BLUE = 0xFF2563EB.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun label(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.12f)
    }

    private fun gradient(t: String, size: Float) = label(t, size, Color.WHITE, true).also { v ->
        v.post { v.paint.shader = LinearGradient(0f, 0f, v.width.toFloat().coerceAtLeast(1f), 0f, intArrayOf(PALETTE[0], PALETTE[1], PALETTE[2]), null, Shader.TileMode.CLAMP); v.invalidate() }
    }

    private fun field(hint: String, value: String, secret: Boolean) = EditText(this).apply {
        setText(value); this.hint = hint; setHintTextColor(0xFF6F757A.toInt()); setTextColor(Color.WHITE); textSize = 16f
        background = bg(0xFF282A2C.toInt(), 14); setPadding(dp(14), dp(13), dp(14), dp(13)); setSingleLine(true)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    private fun button(t: String, fill: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(textColor); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        background = bg(fill, 28); setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun link(t: String, color: Int = 0xFF4C8DF6.toInt(), onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(color); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(14), dp(8), dp(6))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(64), dp(24), dp(40)) }
        scroll.addView(root)
        setContentView(scroll)
        if (!handleRedirect(intent)) render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleRedirect(intent)) render()
    }

    // Senza account non si entra: indietro chiude l'app invece di rimandare alla schermata principale.
    @Suppress("DEPRECATION")
    override fun onBackPressed() { finishAffinity() }

    /** Ritorno da Google (schema com.googleusercontent.apps.…:/oauth2redirect). */
    private fun handleRedirect(i: Intent?): Boolean {
        val d = i?.data ?: return false
        if (d.scheme != GoogleAuth.scheme(this)) return false
        setIntent(Intent())
        val code = d.getQueryParameter("code")
        val e = d.getQueryParameter("error")
        when {
            e != null -> { err = if (e == "access_denied") "Accesso annullato." else "Accesso Google non riuscito: $e"; mode = "login"; render() }
            code == null || d.getQueryParameter("state") != Prefs.pendingState(this) -> { err = "Risposta di Google non valida: riprova."; mode = "login"; render() }
            else -> {
                mode = "wait"; render()
                Thread {
                    try {
                        val u = GoogleAuth.exchange(this, code)
                        ui.post {
                            Prefs.setProfile(this, "google", u.name, u.email); Prefs.clearPending(this)
                            Toast.makeText(this, "Benvenuto, ${u.name}", Toast.LENGTH_SHORT).show(); done()
                        }
                    } catch (ex: Exception) {
                        ui.post { err = "Accesso Google non riuscito: ${ex.message}"; mode = "login"; render() }
                    }
                }.start()
            }
        }
        return true
    }

    private fun render() {
        nameVal = nameField?.text?.toString() ?: nameVal
        root.removeAllViews()
        nameField = null; pwField = null; pw2Field = null

        root.addView(label("Benvenuto in", 30f))
        root.addView(gradient("GPT CORE", 34f))
        root.addView(label(if (mode == "register") "Crea il tuo account: il nome che scegli è quello con cui ti chiamo." else "Accedi per continuare.", 16f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(26))
        })

        if (mode == "wait") {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, 0)
                addView(ProgressBar(this@AuthActivity))
                addView(label("Collegamento con Google…", 15f, MUTED).apply { setPadding(0, dp(16), 0, 0) })
            })
            return
        }

        if (err.isNotEmpty()) root.addView(label(err, 14f, 0xFFF87171.toInt()).apply {
            background = bg(0x33F87171, 12); setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        })

        // Google
        val g = SpannableString("G   Continua con Google")
        g.setSpan(ForegroundColorSpan(0xFF4285F4.toInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        root.addView(button("", Color.WHITE, 0xFF1F1F1F.toInt()) {
            err = GoogleAuth.start(this) ?: ""
            if (err.isNotEmpty()) render()
        }.also { (it as TextView).text = g })

        root.addView(label("oppure", 13f, 0xFF6F757A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) })

        // Account locale
        nameField = field("Il tuo nome", nameVal, false).also { root.addView(it) }
        pwField = field("Password", "", true).also { root.addView(it) }
        if (mode == "register") pw2Field = field("Ripeti la password", "", true).also { root.addView(it) }

        root.addView(button(if (mode == "register") "Crea account" else "Accedi", BLUE) { submit() })
        if (mode == "login") {
            root.addView(link("Non ho un account") { grab(); err = ""; mode = "register"; render() })
            root.addView(link("Password dimenticata?", MUTED) { forgot() })
        } else {
            root.addView(link("Ho già un account") { grab(); err = ""; mode = "login"; render() })
        }
        root.addView(label("L'account è salvato solo su questo telefono: non esiste nessun server.", 12f, 0xFF6F757A.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0)
        })
    }

    /** Accesso riuscito: torna alla schermata principale (anche se l'app era stata chiusa mentre eri nel browser). */
    private fun done() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun grab() { nameVal = nameField?.text?.toString() ?: nameVal }

    private fun submit() {
        val n = nameField?.text?.toString() ?: ""
        val p = pwField?.text?.toString() ?: ""
        val p2 = pw2Field?.text?.toString()
        nameVal = n
        if (mode == "register" && p != p2) { err = "Le due password non coincidono."; render(); return }
        val reg = mode == "register"
        Thread {
            val e = if (reg) AuthStore.register(this, n, p) else AuthStore.login(this, n, p)
            ui.post {
                if (e == null) { Toast.makeText(this, "Benvenuto, ${Prefs.userName(this)}", Toast.LENGTH_SHORT).show(); done() }
                else { err = e; render() }
            }
        }.start()
    }

    private fun forgot() {
        grab()
        if (nameVal.isBlank()) { err = "Scrivi il nome dell'account da reimpostare."; render(); return }
        AlertDialog.Builder(this).setTitle("Eliminare questo account?")
            .setMessage("Gli account sono salvati solo su questo telefono, quindi la password non si può recuperare. Puoi eliminarlo e crearne uno nuovo con lo stesso nome.")
            .setPositiveButton("Elimina account") { _, _ -> AuthStore.delete(this, nameVal); err = ""; mode = "register"; render(); Toast.makeText(this, "Account eliminato: ora puoi crearne uno nuovo", Toast.LENGTH_LONG).show() }
            .setNegativeButton("Annulla", null).show()
    }
}
