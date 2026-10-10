package com.leandro.okcopilot

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Descarga y descomprime el modelo de voz de Vosk (~40 MB) la primera vez. */
object ModelManager {
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

    private fun baseDir(ctx: Context) = File(ctx.filesDir, "model")

    fun modelDir(ctx: Context) = File(baseDir(ctx), MODEL_NAME)

    fun isReady(ctx: Context) = File(modelDir(ctx), "conf/model.conf").exists()

    fun download(ctx: Context, progress: (String) -> Unit) {
        val base = baseDir(ctx)
        base.deleteRecursively()
        base.mkdirs()
        val tmp = File(ctx.cacheDir, "model.zip")

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.connect()
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 1_000_000) {
                        lastReport = done
                        val pct = if (total > 0) " ${done * 100 / total}%" else ""
                        progress("Descargando modelo…$pct (${done / 1_000_000} MB)")
                    }
                }
            }
        }

        progress("Descomprimiendo…")
        val basePath = base.canonicalPath
        ZipInputStream(tmp.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(base, e.name)
                if (!f.canonicalPath.startsWith(basePath)) error("Zip inválido")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
        tmp.delete()
        if (!isReady(ctx)) error("El modelo no quedó completo")
    }
}
