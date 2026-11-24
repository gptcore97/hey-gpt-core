package it.flubatir.coreassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Questo servizio e' cio' che fa comparire GPT CORE tra le "App assistente digitale" di Android. */
class CoreInteractionService : VoiceInteractionService()

class CoreSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = CoreSession(this)
}

/** Pressione lunga su Home / gesto assistente / tasto power: apre il pannello. */
class CoreSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, AssistActivity::class.java))
    }
}

/** Richiesto da Android per gli assistenti; il riconoscimento reale usa quello di Google (vedi Listener). */
class CoreRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) { }
    }
    override fun onCancel(listener: RecognitionService.Callback?) {}
    override fun onStopListening(listener: RecognitionService.Callback?) {}
}

/** Ascolto continuo per la parola di attivazione "Ehi Core" (facoltativo, consuma batteria). */
class WakeService : Service(), Listener.Callbacks {
    private val h = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var running = false
    private val wake = Regex("\\b(ehi|hey|ei|ey|ehy|ok|okay|ciao)\\s*(core|cor|kor|gpt|corre)\\b(.*)")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("wake", "Parola di attivazione", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "wake")
            .setContentTitle("GPT CORE è in ascolto")
            .setContentText("Di' «Ehi Core» per chiamarmi")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(42, n)
        if (!running) { running = true; listener = Listener(applicationContext, this); loop(0) }
        return START_STICKY
    }

    private fun loop(delay: Long) {
        h.postDelayed({
            if (!running) return@postDelayed
            if (Core.busy) { loop(1500); return@postDelayed }
            try { listener?.start() } catch (e: Exception) { loop(3000) }
        }, delay)
    }

    override fun onReady() {}
    override fun onLevel(level: Float) {}
    override fun onPartial(text: String) {}
    override fun onFinal(text: String) {
        val m = wake.find(Fuzzy.norm(text))
        if (m != null) {
            listener?.stop()
            val rest = m.groupValues[3].trim()
            val i = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (rest.length > 2) i.putExtra("text", rest)
            try { startActivity(i) } catch (e: Exception) { }
            loop(4000)
        } else loop(300)
    }
    override fun onError(code: Int) { loop(if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2000 else 600) }

    override fun onDestroy() { running = false; h.removeCallbacksAndMessages(null); listener?.stop(); super.onDestroy() }
}
