package com.n3vibecode.gallery.util

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Weiß, ob gerade ein Bildschirm der App sichtbar ist.
 *
 * Wozu: Der Suchindex rechnet im Hintergrund alle Fotos durch. Das ist viel Rechenarbeit –
 * läuft sie weiter, während die App im Hintergrund ist, kostet sie nur Akku. Und läuft sie in
 * voller Geschwindigkeit, während jemand durch die Galerie scrollt, ruckelt die Oberfläche
 * (in 1.34 fühlte sich das wie „die App friert ein“ an). Hier hängt beides zusammen: sichtbar
 * = mit Pausen rechnen, unsichtbar = nur tröpfeln.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {

    @Volatile private var started = 0

    /** Ist mindestens ein Bildschirm der App gestartet (also sichtbar)? */
    fun isVisible(): Boolean = started > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
