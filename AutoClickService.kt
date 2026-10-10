package com.leandro.okcopilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** Estado compartido entre la app, el servicio de voz y el de accesibilidad. */
object AutoClick {
    data class Learn(val shortcutId: Long, val pkg: String)

    @Volatile var learn: Learn? = null
    @Volatile var pending: Shortcut? = null
    @Volatile var pendingStart = 0L
    @Volatile var pendingUntil = 0L
    @Volatile var service: AutoClickService? = null

    /** Pide tocar el botón aprendido del comando cuando su app esté en pantalla. */
    fun request(sc: Shortcut) {
        if (!sc.hasClick) return
        val now = SystemClock.elapsedRealtime()
        pending = sc
        pendingStart = now
        pendingUntil = now + 12_000
        service?.startPolling()
    }
}

/**
 * Servicio de accesibilidad: aprende qué botón tocás dentro de una app y
 * después lo toca automáticamente al abrirla por voz.
 */
class AutoClickService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var coordTapped = false

    private val poll = object : Runnable {
        override fun run() {
            if (AutoClick.pending == null) return
            attempt()
            if (AutoClick.pending != null) handler.postDelayed(this, 400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutoClick.service = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutoClick.service = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutoClick.service = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    fun startPolling() {
        coordTapped = false
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val learn = AutoClick.learn
        if (learn != null && e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            e.packageName?.toString() == learn.pkg
        ) {
            learnFrom(e, learn)
            return
        }
        if (AutoClick.pending != null &&
            (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            attempt()
        }
    }

    // ---------- Ejecutar toque ----------

    private fun attempt() {
        val sc = AutoClick.pending ?: return
        val now = SystemClock.elapsedRealtime()
        if (now > AutoClick.pendingUntil) {
            AutoClick.pending = null
            return
        }
        if (now - AutoClick.pendingStart < 700) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != sc.pkg) return
        if (clickTarget(root, sc, now)) AutoClick.pending = null
    }

    private fun clickTarget(root: AccessibilityNodeInfo, sc: Shortcut, now: Long): Boolean {
        var node: AccessibilityNodeInfo? = null
        val id = sc.clickId
        if (id != null) node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
        if (node == null && (sc.clickDesc != null || sc.clickText != null)) {
            node = find(root, sc.clickDesc, sc.clickText, 0)
        }
        if (node != null) return click(node)

        // Último recurso: si solo conocemos la posición, tocamos ahí una vez.
        val onlyCoords = sc.clickId == null && sc.clickDesc == null && sc.clickText == null
        if (onlyCoords && sc.clickX >= 0 && !coordTapped && now - AutoClick.pendingStart > 2500) {
            coordTapped = true
            return tap(sc.clickX, sc.clickY)
        }
        return false
    }

    private fun find(n: AccessibilityNodeInfo, desc: String?, text: String?, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val d = n.contentDescription?.toString()
        val t = n.text?.toString()
        if (desc != null && d != null && d.equals(desc, ignoreCase = true)) return n
        if (text != null && t != null && t.equals(text, ignoreCase = true)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = find(c, desc, text, depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (!r.isEmpty) tap(r.centerX(), r.centerY()) else false
    }

    private fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    // ---------- Aprender toque ----------

    private fun firstLabel(n: AccessibilityNodeInfo, depth: Int): Pair<String?, String?> {
        val d = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val t = n.text?.toString()?.takeIf { it.isNotBlank() }
        if (d != null || t != null) return d to t
        if (depth > 5) return null to null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = firstLabel(c, depth + 1)
            if (r.first != null || r.second != null) return r
        }
        return null to null
    }

    private fun learnFrom(e: AccessibilityEvent, learn: AutoClick.Learn) {
        val sc = CommandStore.load(this).firstOrNull { it.id == learn.shortcutId }
        if (sc == null) {
            AutoClick.learn = null
            return
        }
        sc.clearClick()
        val src = e.source
        if (src != null) {
            sc.clickId = src.viewIdResourceName?.takeIf { it.isNotBlank() }
            val (d, t) = firstLabel(src, 0)
            sc.clickDesc = d
            sc.clickText = t
            val r = Rect()
            src.getBoundsInScreen(r)
            if (!r.isEmpty) {
                sc.clickX = r.centerX()
                sc.clickY = r.centerY()
            }
        } else {
            sc.clickDesc = e.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            sc.clickText = e.text.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
        }
        if (!sc.hasClick) {
            Toast.makeText(this, "No pude identificar ese botón, probá tocarlo de nuevo", Toast.LENGTH_LONG).show()
            return
        }
        CommandStore.update(this, sc)
        AutoClick.learn = null
        Toast.makeText(this, "✅ Aprendido: \"${sc.clickLabel()}\" para \"${sc.phrase}\"", Toast.LENGTH_LONG).show()
    }
}
