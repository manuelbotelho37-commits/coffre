package fr.manu.applis

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Sauvegardes automatiques (comme Tâche), dans Téléchargements/<Appli> :
 * - « <Appli>-sauvegarde-derniere.json », réécrite après chaque changement ;
 * - « <Appli>-sauvegarde-auto-AAAA-MM-JJ.json », une par jour d'utilisation, 14 gardées.
 * Ces fichiers restent sur le téléphone même si l'appli est désinstallée.
 */
object AutoBackup {
    private const val KEEP = 14
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null

    private val app get() = BuildConfig.APK_NAME
    private val folder get() = Environment.DIRECTORY_DOWNLOADS + "/" + app + "/"
    private val prefix get() = "$app-sauvegarde-auto-"
    private val latest get() = "$app-sauvegarde-derniere.json"

    /** Contenu complet de la page (toutes ses données), envoyé par la page après chaque changement. */
    @Synchronized
    fun onChange(context: Context, storageJson: String) {
        val ctx = context.applicationContext
        val data = try { JSONObject(storageJson) } catch (e: Exception) { return }
        if (data.length() == 0) return // jamais écraser une sauvegarde avec du vide
        pending?.cancel(false)
        pending = executor.schedule(Runnable {
            try {
                val bytes = JSONObject()
                    .put("appli", app)
                    .put("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).format(Date()))
                    .put("donnees", data)
                    .toString(1).toByteArray(Charsets.UTF_8)
                File(ctx.filesDir, "derniere-sauvegarde.json").writeBytes(bytes)
                write(ctx, latest, bytes, false)
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date())
                write(ctx, "$prefix$day.json", bytes, true)
                ctx.getSharedPreferences("sauvegardes", Context.MODE_PRIVATE).edit()
                    .putLong("derniere", System.currentTimeMillis()).apply()
            } catch (e: Exception) { }
        }, 2, TimeUnit.SECONDS)
    }

    /** Lit un fichier de sauvegarde choisi par l'utilisateur ; renvoie les données, ou null. */
    fun parse(text: String): JSONObject? = try {
        val o = JSONObject(text)
        val d = o.optJSONObject("donnees")
        if (d != null && d.length() > 0) d else null
    } catch (e: Exception) {
        null
    }

    private fun write(context: Context, name: String, bytes: ByteArray, prune: Boolean) {
        val cr = context.contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val found = mutableListOf<Pair<Uri, String>>()
        cr.query(
            coll,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
            arrayOf(folder, if (prune) "$prefix%" else name),
            MediaStore.Downloads.DISPLAY_NAME + " DESC"
        )?.use { c ->
            while (c.moveToNext()) found.add(Pair(ContentUris.withAppendedId(coll, c.getLong(0)), c.getString(1)))
        }
        val existing = found.firstOrNull { it.second == name }?.first
        val uri = existing ?: cr.insert(coll, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
        }) ?: throw Exception("insert")
        cr.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw Exception("flux")
        if (!prune) return
        val keep = (found.map { it.second } + name).distinct().sortedDescending().take(KEEP).toSet()
        for ((u, n) in found) if (n !in keep) try { cr.delete(u, null, null) } catch (e: Exception) { }
    }
}
