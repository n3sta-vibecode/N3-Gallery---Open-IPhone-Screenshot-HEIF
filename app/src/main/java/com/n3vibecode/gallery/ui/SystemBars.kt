package com.n3vibecode.gallery.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Obere Systemleisten ausblenden – wie in einer normalen Foto-App.
 *
 * **Galerieseite** ([applyImmersive] mit `hideTop = true`): Statusleiste und Uhr/Akkku
 * verschwinden, die Bilder reichen bis unter die Kamera. Der untere Systembalken bleibt
 * sichtbar (dort sitzt die eigene Navigationsleiste), weil sonst die Navigationsgesten
 * unerreichbar wären.
 *
 * **Bildansicht** ([applyFullImmersive]): beide Leisten weg; eine Wischgeste vom Rand holt
 * sie vorübergehend zurück (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`).
 */
object SystemBars {

    /** Galerie: nur die obere Leiste aus, unten Platz für die eigene Leiste lassen. */
    fun hideTop(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        // Unten bleibt die Navigationsleiste – nur den Inhalt darüber legen
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    /** Bildansicht: alles aus, Tippen/Wischen holt die Leisten zurück. */
    fun hideAll(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Leisten wieder zeigen (z. B. wenn das Metadaten-Blatt offen ist). */
    fun showAll(activity: Activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    /** Höhe der Statusleiste – wird als Abstand oben gebraucht, wenn sie ausgeblendet ist. */
    fun topInset(view: View): Int {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
    }
}
