package fr.manu.applis

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Mise à jour intégrée (comme Tâche) : compare la version installée avec la dernière publiée sur GitHub. */
object Updater {
    private const val BASE =
        "https://github.com/manuelbotelho37-commits/coffre/releases/download/applis-android/"

    fun installedVersion(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        var redirects = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.useCaches = false
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = c.getHeaderField("Location") ?: throw Exception("redirection")
                c.disconnect()
                u = URL(u, loc)
                redirects++
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw Exception("HTTP $code")
            }
            return c
        }
    }

    /** Numéro de la dernière version publiée, ou null sans réseau. Hors du fil principal. */
    fun latestVersion(): Long? = try {
        val c = open(BASE + "version.txt?t=" + System.currentTimeMillis())
        val txt = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
        c.disconnect()
        txt.toLongOrNull()
    } catch (e: Exception) {
        null
    }

    /** Télécharge la dernière APK de cette appli dans le cache. Hors du fil principal. */
    fun download(context: Context): File? = try {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val tmp = File(dir, "maj.part")
        val out = File(dir, "maj.apk")
        val c = open(BASE + BuildConfig.APK_NAME + ".apk?t=" + System.currentTimeMillis())
        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        c.disconnect()
        if (tmp.length() < 100_000) throw Exception("fichier incomplet")
        out.delete()
        if (!tmp.renameTo(out)) throw Exception("renommage")
        out
    } catch (e: Exception) {
        null
    }
}
