package com.leandro.okcopilot

import android.content.Context
import android.content.Intent

data class AppInfo(val label: String, val pkg: String)

object AppLauncher {
    fun listApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(ctx: Context, pkg: String) =
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    fun label(ctx: Context, pkg: String): String? = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        null
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

    /** Busca la app cuyo nombre se parezca más a lo que se dijo. */
    fun findBest(apps: List<AppInfo>, spoken: String): AppInfo? {
        val s = TextMatch.normalize(spoken)
        if (s.length < 2) return null
        var best: AppInfo? = null
        var bestScore = 0.0
        for (a in apps) {
            val l = TextMatch.normalize(a.label)
            if (l.isEmpty()) continue
            val score = when {
                l == s -> 1.0
                s.length >= 3 && (l.startsWith(s) || s.startsWith(l)) -> 0.9
                else -> maxOf(TextMatch.ratio(l, s), TextMatch.ratio(l.replace(" ", ""), s.replace(" ", "")))
            }
            if (score > bestScore) {
                bestScore = score
                best = a
            }
        }
        return if (bestScore >= 0.72) best else null
    }
}
