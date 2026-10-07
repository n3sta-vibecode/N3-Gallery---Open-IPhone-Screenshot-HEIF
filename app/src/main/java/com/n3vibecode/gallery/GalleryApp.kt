package com.n3vibecode.gallery

import android.app.Application
import com.n3vibecode.gallery.util.Fmt
import java.io.File

/**
 * N3 Photos (N3 Vibecode)
 * App by N3 Vibecode
 *
 * Zentraler Speicherort für Hilfsverzeichnisse und den App-Zustand (kein Internet, keine Cloud).
 */
class GalleryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Fmt.appContext = this
        // Notizen/Tags/Favoriten im Hintergrund wiederherstellen, falls die App neu
        // installiert wurde (sie liegen sonst nur in den App-Einstellungen).
        Thread({
            runCatching { com.n3vibecode.gallery.data.MetaBackup.restoreIfEmpty() }
            // Danach die Sicherung anlegen, falls noch keine da ist: So ist sie schon
            // vorhanden, bevor jemand die App deinstalliert.
            runCatching { com.n3vibecode.gallery.data.MetaBackup.saveIfAny() }
        }, "n3-meta-restore").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    companion object {
        lateinit var instance: GalleryApp
            private set

        /** Ordner im App-Cache, in den geänderte Kopien geschrieben werden, wenn kein Schreibzugriff möglich ist. */
        fun exportDir(): File = File(instance.cacheDir, "n3export").apply { mkdirs() }

        /** Ordner für geteilte Dateien (FileProvider). */
        fun shareDir(): File = File(instance.cacheDir, "share").apply { mkdirs() }
    }
}
