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
