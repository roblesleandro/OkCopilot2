package com.leandro.okcopilot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var cmdBox: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var downloading = false
    private var pad = 0

    private val ticker = object : Runnable {
        override fun run() {
            updateHeard()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "OK Copilot"; textSize = 26f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        heard = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.ITALIC) }
        root.addView(heard)

        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        section(root, "Configuración (una sola vez)")
        button("1. Permiso de micrófono y notificaciones") { requestPerms() }
        button("2. Permitir \"mostrar sobre otras apps\"") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("3. Descargar modelo de voz en español (~40 MB)") { downloadModel() }
        button("4. Quitar restricción de batería") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        button("5. Activar accesibilidad (para tocar botones solo)") {
            toast("Buscá \"OK Copilot\" en Apps instaladas y activalo")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Si el paso 5 aparece bloqueado: abrir Info de la app") {
            toast("Tocá ⋮ (arriba a la derecha) → \"Permitir ajustes restringidos\"")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        section(root, "Escucha")
        button("▶ Iniciar escucha") { startListening() }
        button("■ Detener escucha") {
            stopService(Intent(this, WakeWordService::class.java))
            ui.postDelayed({ refresh() }, 300)
        }

        section(root, "Comandos de voz")
        root.addView(TextView(this).apply {
            textSize = 14f
            text = "Decí la frase de un comando para abrir su app. También podés decir " +
                "\"abrir WhatsApp\", \"abrir cámara\", etc. para cualquier app instalada.\n" +
                "\"Aprender toque\": se abre la app y tocás el botón que querés que se apriete solo " +
                "(por ejemplo, el botón de voz de Copilot)."
        })
        cmdBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(cmdBox)
        button("+ Agregar comando") { addCommand() }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun notifOk() = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
    private fun mark(ok: Boolean) = if (ok) "✅" else "❌"

    private fun a11yOn(): Boolean {
        if (AutoClick.service != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return s?.contains("$packageName/") == true
    }

    private fun updateHeard() {
        heard.text = if (WakeWordService.running) {
            val h = WakeWordService.lastHeard
            if (h.isEmpty()) "🎙 Escuchando…" else "🎙 Último escuchado: \"$h\""
        } else "⏸ Escucha detenida"
    }

    private fun refresh() {
        if (!downloading) {
            val pm = getSystemService(PowerManager::class.java)
            status.text = buildString {
                appendLine("${mark(granted(Manifest.permission.RECORD_AUDIO))} Micrófono")
                appendLine("${mark(notifOk())} Notificaciones")
                appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} Mostrar sobre otras apps")
                appendLine("${mark(ModelManager.isReady(this@MainActivity))} Modelo de voz (español)")
                appendLine("${mark(pm.isIgnoringBatteryOptimizations(packageName))} Sin restricción de batería")
                append("${mark(a11yOn())} Accesibilidad (toques automáticos)")
            }
        }
        updateHeard()
        rebuildCommands()
    }

    // ---------- Comandos ----------

    private fun rebuildCommands() {
        cmdBox.removeAllViews()
        for (sc in CommandStore.load(this)) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            card.addView(TextView(this).apply {
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                text = "\"${sc.phrase}\" → ${sc.appLabel}"
            })
            card.addView(TextView(this).apply {
                textSize = 13f
                text = if (sc.hasClick) "Toca solo: ${sc.clickLabel()}" else "Sin toque automático"
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun small(label: String, action: () -> Unit) {
                row.addView(Button(this).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { action() }
                })
            }
            small("Probar") { test(sc) }
            small("Aprender toque") { learn(sc) }
            if (sc.hasClick) small("Quitar toque") {
                sc.clearClick(); CommandStore.update(this, sc); refresh()
            }
            small("Frase") {
                askPhrase(sc.phrase) { p -> sc.phrase = p; CommandStore.update(this, sc); refresh() }
            }
            small("App") {
                pickApp { a ->
                    sc.pkg = a.pkg; sc.appLabel = a.label; sc.clearClick()
                    CommandStore.update(this, sc); refresh()
                }
            }
            small("Borrar") {
                AlertDialog.Builder(this)
                    .setMessage("¿Borrar el comando \"${sc.phrase}\"?")
                    .setPositiveButton("Borrar") { _, _ -> CommandStore.delete(this, sc.id); refresh() }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            card.addView(HorizontalScrollView(this).apply { addView(row) })
            cmdBox.addView(card)
        }
    }

    private fun addCommand() {
        askPhrase("") { p ->
            pickApp { a ->
                CommandStore.update(this, Shortcut(System.currentTimeMillis(), p, a.pkg, a.label))
                refresh()
            }
        }
    }

    private fun askPhrase(initial: String, onOk: (String) -> Unit) {
        val edit = EditText(this).apply { setText(initial); hint = "ej: copilot, correo, guasap" }
        AlertDialog.Builder(this)
            .setTitle("¿Qué vas a decir?")
            .setMessage("Usá la palabra tal como aparece en \"Último escuchado\" cuando la decís.")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) onOk(t)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickApp(onPick: (AppInfo) -> Unit) {
        val apps = AppLauncher.listApps(this)
        AlertDialog.Builder(this)
            .setTitle("Elegí la app")
            .setItems(apps.map { it.label }.toTypedArray()) { _, i -> onPick(apps[i]) }
            .show()
    }

    private fun test(sc: Shortcut) {
        val i = AppLauncher.launchIntent(this, sc.pkg)
        if (i == null) {
            toast("${sc.appLabel} no está instalada")
            return
        }
        AutoClick.request(sc)
        startActivity(i)
    }

    private fun learn(sc: Shortcut) {
        if (!a11yOn()) {
            toast("Primero activá la accesibilidad (paso 5)")
            return
        }
        val i = AppLauncher.launchIntent(this, sc.pkg)
        if (i == null) {
            toast("${sc.appLabel} no está instalada")
            return
        }
        AutoClick.learn = AutoClick.Learn(sc.id, sc.pkg)
        toast("Ahora tocá en ${sc.appLabel} el botón que querés que se apriete solo")
        startActivity(i)
    }

    // ---------- Permisos / modelo / servicio ----------

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
                if (WakeWordService.running) stopService(Intent(this, WakeWordService::class.java))
                ui.postDelayed({
                    startForegroundService(Intent(this, WakeWordService::class.java))
                    ui.postDelayed({ refresh() }, 500)
                }, 300)
                if (!Settings.canDrawOverlays(this)) {
                    toast("Sin el paso 2, las apps se abrirán desde una notificación")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
