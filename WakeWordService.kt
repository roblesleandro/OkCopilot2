package com.leandro.okcopilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Servicio en primer plano que escucha el micrófono continuamente y,
 * al detectar "OK Copilot", abre la app de Copilot.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val CH_LISTEN = "okcopilot_listen"
        private const val CH_ALERT = "okcopilot_alert"
        private const val NID = 1
        private const val COOLDOWN_MS = 4000L

        // Acepta "copilot", "co pilot", "ok/okay ... pilot" (el modelo a veces separa la palabra)
        private val WAKE = Regex("""(co ?pilot|\b(ok|okay|o k)\s+(\w+\s+)?pilot)""")

        @Volatile
        var running = false
            private set
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        try {
            val n = buildNotification("Escuchando \"OK Copilot\"…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }
        running = true

        Thread {
            try {
                val m = Model(ModelManager.modelDir(this).absolutePath)
                val r = Recognizer(m, 16000f)
                val s = SpeechService(r, 16000f)
                model = m
                recognizer = r
                speech = s
                s.startListening(this)
            } catch (e: Exception) {
                handler.post {
                    updateNotification("Error: ${e.message}")
                    stopSelf()
                }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (_: Exception) {
        }
        try {
            recognizer?.close()
            model?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---- RecognitionListener ----
    override fun onPartialResult(hypothesis: String?) = check(field(hypothesis, "partial"))
    override fun onResult(hypothesis: String?) = check(field(hypothesis, "text"))
    override fun onFinalResult(hypothesis: String?) = check(field(hypothesis, "text"))
    override fun onError(exception: Exception?) {
        updateNotification("Error de micrófono: ${exception?.message}")
    }
    override fun onTimeout() {}

    private fun field(json: String?, key: String): String =
        try { JSONObject(json ?: "{}").optString(key) } catch (_: Exception) { "" }

    private fun check(text: String) {
        if (text.isBlank()) return
        if (!WAKE.containsMatchIn(text.lowercase())) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        lastTrigger = now

        // Pausamos mientras se abre Copilot y limpiamos el buffer antes de seguir.
        speech?.setPause(true)
        launchCopilot()
        handler.postDelayed({
            try { recognizer?.reset() } catch (_: Exception) {}
            speech?.setPause(false)
        }, COOLDOWN_MS)
    }

    private fun launchCopilot() {
        val intent = CopilotLauncher.intent(this)
        // Con el permiso "mostrar sobre otras apps" Android permite abrir actividades desde segundo plano.
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        // Plan B: notificación para abrir Copilot con un toque.
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("OK Copilot")
            .setContentText("Tocá para abrir Copilot")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    // ---- Notificaciones ----
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "Escucha activa", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Abrir Copilot", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_LISTEN)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("OK Copilot")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NID, buildNotification(text))
    }
}
