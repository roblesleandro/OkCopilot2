package com.leandro.okcopilot

import android.content.Context
import android.content.Intent
import android.net.Uri

object CopilotLauncher {
    const val PKG = "com.microsoft.copilot"

    fun isInstalled(ctx: Context): Boolean =
        ctx.packageManager.getLaunchIntentForPackage(PKG) != null

    /** Intent para abrir la app de Copilot; si no está instalada, abre la web. */
    fun intent(ctx: Context): Intent {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://copilot.microsoft.com"))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return i
    }

    fun open(ctx: Context) {
        try {
            ctx.startActivity(intent(ctx))
        } catch (_: Exception) {
        }
    }
}
