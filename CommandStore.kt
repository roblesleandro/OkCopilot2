package com.leandro.okcopilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un comando de voz: frase -> app (+ botón opcional que se toca solo al abrirla). */
data class Shortcut(
    val id: Long,
    var phrase: String,
    var pkg: String,
    var appLabel: String,
    var clickId: String? = null,
    var clickDesc: String? = null,
    var clickText: String? = null,
    var clickX: Int = -1,
    var clickY: Int = -1,
) {
    val hasClick: Boolean
        get() = clickId != null || clickDesc != null || clickText != null || clickX >= 0

    fun clearClick() {
        clickId = null; clickDesc = null; clickText = null; clickX = -1; clickY = -1
    }

    fun clickLabel(): String = clickDesc ?: clickText ?: clickId?.substringAfterLast('/') ?: "punto ($clickX, $clickY)"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("phrase", phrase); put("pkg", pkg); put("appLabel", appLabel)
        clickId?.let { put("clickId", it) }
        clickDesc?.let { put("clickDesc", it) }
        clickText?.let { put("clickText", it) }
        put("clickX", clickX); put("clickY", clickY)
    }

    companion object {
        private fun JSONObject.str(k: String): String? =
            if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

        fun fromJson(o: JSONObject) = Shortcut(
            o.getLong("id"), o.getString("phrase"), o.getString("pkg"), o.optString("appLabel"),
            o.str("clickId"), o.str("clickDesc"), o.str("clickText"),
            o.optInt("clickX", -1), o.optInt("clickY", -1)
        )
    }
}

object CommandStore {
    private const val PREFS = "okcopilot"
    private const val KEY = "shortcuts"
    private const val M365_COPILOT = "com.microsoft.office.officehubrow"
    private const val COPILOT = "com.microsoft.copilot"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Shortcut> {
        val s = prefs(ctx).getString(KEY, null)
        if (s == null) {
            val d = defaults(ctx)
            save(ctx, d)
            return d
        }
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { Shortcut.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun update(ctx: Context, sc: Shortcut) {
        val l = load(ctx)
        val i = l.indexOfFirst { it.id == sc.id }
        if (i >= 0) l[i] = sc else l.add(sc)
        save(ctx, l)
    }

    fun delete(ctx: Context, id: Long) {
        save(ctx, load(ctx).filter { it.id != id })
    }

    private fun defaults(ctx: Context): MutableList<Shortcut> {
        val pkg = if (AppLauncher.isInstalled(ctx, M365_COPILOT)) M365_COPILOT else COPILOT
        return mutableListOf(Shortcut(1L, "copilot", pkg, AppLauncher.label(ctx, pkg) ?: "Copilot"))
    }
}
