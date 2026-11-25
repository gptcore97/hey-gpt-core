package it.flubatir.coreassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private val main = Handler(Looper.getMainLooper())

/** Ascolto: usa il riconoscimento di Google (non il nostro stub, che e' solo per registrare l'assistente). */
class Listener(private val ctx: Context, private val cb: Callbacks) {
    interface Callbacks {
        fun onReady()
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
    }

    private var rec: SpeechRecognizer? = null

    private fun pick(): ComponentName? {
        val l = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.packageName != ctx.packageName }
        val s = l.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" } ?: l.firstOrNull() ?: return null
        return ComponentName(s.serviceInfo.packageName, s.serviceInfo.name)
    }

    fun available() = pick() != null || SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        stop()
        val comp = pick()
        val r = if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp) else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = cb.onReady()
            override fun onRmsChanged(rms: Float) = cb.onLevel(((rms + 2f) / 12f).coerceIn(0f, 1f))
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) cb.onPartial(it) }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) cb.onError(SpeechRecognizer.ERROR_NO_MATCH) else cb.onFinal(t)
            }
            override fun onError(e: Int) = cb.onError(e)
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        r.startListening(i)
    }

    fun stop() {
        rec?.let { try { it.cancel(); it.destroy() } catch (e: Exception) { } }
        rec = null
    }
}

/** Voce: Gemini TTS (naturale) con ripiego automatico sul sintetizzatore di Android. Singleton: continua a parlare anche se il pannello si chiude. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null
    var onLevel: ((Float) -> Unit)? = null

    private fun ensureTts(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ITALIAN
                tts?.setSpeechRate(1.05f)
                ttsReady = true
            }
        }
    }

    fun warmUp(ctx: Context) = ensureTts(ctx)

    fun stop() {
        gen++
        try { track?.stop() } catch (e: Exception) { }
        tts?.stop()
    }

    fun speak(ctx: Context, text: String, onStart: () -> Unit, onDone: () -> Unit) {
        val app = ctx.applicationContext
        ensureTts(app)
        stop()
        val my = ++gen
        val clean = text.replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) { onDone(); return }
        Thread {
            var played = false
            if (Prefs.geminiVoice(app) && Prefs.geminiKey(app).isNotBlank() && clean.length < 700) {
                try { played = geminiSpeak(app, clean, my, onStart) } catch (e: Exception) { played = false }
            }
            if (my != gen) return@Thread
            if (played) { main.post { onLevel?.invoke(0f); if (my == gen) onDone() }; return@Thread }
            main.post { systemSpeak(clean, my, onStart, onDone) }
        }.start()
    }

    private fun systemSpeak(text: String, my: Int, onStart: () -> Unit, onDone: () -> Unit) {
        if (my != gen) return
        val t = tts
        if (t == null || !ttsReady) { // sintetizzatore non ancora pronto: riprova brevemente
            main.postDelayed({ if (my == gen) { if (ttsReady) systemSpeak(text, my, onStart, onDone) else onDone() } }, 700)
            return
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { if (my == gen) onStart() } }
            override fun onDone(id: String?) { main.post { onLevel?.invoke(0f); if (my == gen) onDone() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { if (my == gen) onDone() } }
        })
        // animazione simulata mentre parla
        val anim = object : Runnable {
            var k = 0.0
            override fun run() {
                if (my != gen || !(t.isSpeaking)) return
                k += 0.9; onLevel?.invoke((0.45 + 0.4 * Math.sin(k) * Math.sin(k * 0.37)).toFloat().coerceIn(0.1f, 1f))
                main.postDelayed(this, 60)
            }
        }
        main.postDelayed(anim, 250)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "core_$my")
    }

    private fun geminiSpeak(ctx: Context, text: String, my: Int, onStart: () -> Unit): Boolean {
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "Leggi in italiano, " + Prefs.voiceStyle(ctx).ifBlank { "con tono naturale, caldo e disinvolto, a ritmo vivace" } + ": $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig",
                    JSONObject().put("voiceName", Prefs.voiceName(ctx))))))
        val c = URL(Brain.BASE + "gemini-2.5-flash-preview-tts:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", Prefs.geminiKey(ctx))
        c.connectTimeout = 8000; c.readTimeout = 20000; c.doOutput = true
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        if (c.responseCode !in 200..299) { c.disconnect(); return false }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        if (my != gen) return true
        val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        var b64 = ""
        for (i in 0 until parts.length()) parts.getJSONObject(i).optJSONObject("inlineData")?.let { b64 = it.optString("data") }
        if (b64.isEmpty()) return false
        val pcm = Base64.decode(b64, Base64.DEFAULT)
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        main.post { if (my == gen) onStart() }
        t.play()
        var off = 0; val chunk = 2400 // ~50 ms
        while (off < pcm.size && my == gen) {
            val n = minOf(chunk, pcm.size - off)
            t.write(pcm, off, n)
            var sum = 0.0; var k = 0
            while (k + 1 < n) { val s = (pcm[off + k].toInt() and 0xFF) or (pcm[off + k + 1].toInt() shl 8); sum += (s.toShort().toDouble() / 32768.0).let { it * it }; k += 2 }
            val rms = sqrt(sum / maxOf(1, n / 2)).toFloat()
            main.post { if (my == gen) onLevel?.invoke((rms * 5f).coerceIn(0.08f, 1f)) }
            off += n
        }
        if (my == gen) {
            try { t.stop() } catch (e: Exception) { }
            val frames = pcm.size / 2; var wait = 0
            while (my == gen && t.playbackHeadPosition < frames && wait < 400) { Thread.sleep(25); wait++ }
        }
        try { t.release() } catch (e: Exception) { }
        if (track === t) track = null
        return true
    }
}
