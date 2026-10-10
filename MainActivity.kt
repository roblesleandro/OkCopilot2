package com.leandro.okcopilot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    @Volatile private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "OK Copilot"; textSize = 26f })
        status = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, pad) }
        root.addView(status)

        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        button("1. Dar permiso de micrófono y notificaciones") { requestPerms() }
        button("2. Permitir \"mostrar sobre otras apps\"") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        button("3. Descargar modelo de voz (~40 MB)") { downloadModel() }
        button("4. Quitar restricción de batería") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        button("▶ Iniciar escucha") { startListening() }
        button("■ Detener escucha") {
            stopService(Intent(this, WakeWordService::class.java))
            status.postDelayed({ refresh() }, 300)
        }
        button("Probar: abrir Copilot ahora") { CopilotLauncher.open(this) }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun notifOk() = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun mark(ok: Boolean) = if (ok) "✅" else "❌"

    private fun refresh() {
        if (downloading) return
        val pm = getSystemService(PowerManager::class.java)
        status.text = buildString {
            appendLine("${mark(granted(Manifest.permission.RECORD_AUDIO))} Micrófono")
            appendLine("${mark(notifOk())} Notificaciones")
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} Mostrar sobre otras apps")
            appendLine("${mark(ModelManager.isReady(this@MainActivity))} Modelo de voz descargado")
            appendLine("${mark(pm.isIgnoringBatteryOptimizations(packageName))} Sin restricción de batería (recomendado)")
            appendLine("${mark(CopilotLauncher.isInstalled(this@MainActivity))} App Copilot instalada (si no, abre la web)")
            appendLine()
            append(if (WakeWordService.running) "🎙 Escuchando: decí \"OK Copilot\"" else "⏸ Escucha detenida")
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        status.text = "Conectando…"
        Thread {
            try {
                ModelManager.download(this) { msg -> runOnUiThread { status.text = msg } }
                runOnUiThread { toast("Modelo listo") }
            } catch (e: Exception) {
                runOnUiThread { toast("Error al descargar: ${e.message}") }
            } finally {
                downloading = false
                runOnUiThread { refresh() }
            }
        }.start()
    }

    private fun startListening() {
        when {
            !granted(Manifest.permission.RECORD_AUDIO) -> toast("Falta el permiso de micrófono (paso 1)")
            !ModelManager.isReady(this) -> toast("Primero descargá el modelo de voz (paso 3)")
            else -> {
                startForegroundService(Intent(this, WakeWordService::class.java))
                if (!Settings.canDrawOverlays(this)) {
                    toast("Sin el paso 2, Copilot se abrirá desde una notificación")
                }
                status.postDelayed({ refresh() }, 500)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
