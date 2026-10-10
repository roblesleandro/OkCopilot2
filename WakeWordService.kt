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
import android.provider.Settings
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Servicio en primer plano que escucha el micrófono (Vosk, español, offline) y:
 *  - si escucha la frase de un comando (ej. "copilot") abre esa app y, si aprendió un botón, lo toca;
 *  - si escucha "abrir <app>" abre la app instalada con ese nombre.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val CH_LISTEN = "okcopilot_listen"
        private const val CH_ALERT = "okcopilot_alert"
        private const val NID = 1
        private const val PAUSE_MS = 5000L
        private const val MATCH = 0.78
        private const val LISTENING = "Escuchando… decí \"copilot\" o \"abrir <app>\""

        private val OPEN = Regex(
            "^(?:ok |okay |oye )?(?:abrir|abri|abre|abra|abrime|abreme)\\s+" +
                "(?:la |el )?(?:app |aplicacion )?(?:de )?(.+)$"
        )

        @Volatile
        var running = false
            private set

        @Volatile
        var lastHeard = ""
            private set
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var paused = false
    private var apps: List<AppInfo> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        try {
            val n = buildNotification(LISTENING)
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
                apps = AppLauncher.listApps(this)
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
    override fun onPartialResult(hypothesis: String?) {}
    override fun onResult(hypothesis: String?) = handle(field(hypothesis, "text"))
    override fun onFinalResult(hypothesis: String?) = handle(field(hypothesis, "text"))
    override fun onError(exception: Exception?) {
        updateNotification("Error de micrófono: ${exception?.message}")
    }
    override fun onTimeout() {}

    private fun field(json: String?, key: String): String =
        try { JSONObject(json ?: "{}").optString(key) } catch (_: Exception) { "" }

    private fun handle(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || paused) return
        lastHeard = text

        // 1) Comandos configurados (frase -> app)
        val shortcuts = CommandStore.load(this)
        var best: Shortcut? = null
        var bestScore = 0.0
        for (sc in shortcuts) {
            val s = TextMatch.phraseScore(text, sc.phrase)
            if (s > bestScore) {
                bestScore = s
                best = sc
            }
        }
        if (best != null && bestScore >= MATCH) {
            launch(best.pkg, best.appLabel, best)
            return
        }

        // 2) "abrir <nombre de app>"
        val m = OPEN.find(TextMatch.normalize(text))
        if (m == null) {
            updateNotification("Escuché: \"$text\"")
            return
        }
        val name = m.groupValues[1].trim()
        if (apps.isEmpty()) apps = AppLauncher.listApps(this)
        var app = AppLauncher.findBest(apps, name)
        if (app == null) {
            apps = AppLauncher.listApps(this)
            app = AppLauncher.findBest(apps, name)
        }
        if (app == null) {
            updateNotification("No encontré una app llamada \"$name\"")
            return
        }
        launch(app.pkg, app.label, null)
    }

    private fun launch(pkg: String, label: String, sc: Shortcut?) {
        val intent = AppLauncher.launchIntent(this, pkg)
        if (intent == null) {
            updateNotification("$label no está instalada")
            return
        }
        paused = true
        speech?.setPause(true)
        updateNotification("Abriendo $label…")

        if (sc != null) AutoClick.request(sc)

        var opened = false
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
                opened = true
            } catch (_: Exception) {
            }
        }
        if (!opened) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("OK Copilot")
                .setContentText("Tocá para abrir $label")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(2, n)
        }

        handler.postDelayed({
            try { recognizer?.reset() } catch (_: Exception) {}
            speech?.setPause(false)
            paused = false
            updateNotification(LISTENING)
        }, PAUSE_MS)
    }

    // ---- Notificaciones ----
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "Escucha activa", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Abrir apps", NotificationManager.IMPORTANCE_HIGH)
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
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NID, buildNotification(text))
    }
}
