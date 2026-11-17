package com.n3vibecode.gallery.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.GeoStore
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.image.ImageLoader
import com.n3vibecode.gallery.map.GeoIndex
import com.n3vibecode.gallery.util.Fmt
import com.n3vibecode.gallery.widget.AspectImageView

/**
 * Vorschau nach **langem Drücken** auf eine Kachel – wie auf dem iPhone.
 *
 * Der wichtige Punkt: Das Foto wird **im richtigen Seitenverhältnis** gezeigt
 * (9:19,5 für iPhone-Screenshots, 3:2 für Kamera-Fotos, Panorama breit …) und dabei
 * **nicht beschnitten**. Im Raster selbst bleiben die Kacheln quadratisch – dort ist das
 * gewollt (gleichmäßiges Raster wie bei Apple Fotos); die Vorschau zeigt das ganze Bild.
 *
 * Zusätzlich: Ort des Fotos (offline bestimmt), Aufnahmedatum, Schnellaktionen.
 */
object PeekPreview {

    /** Zeigt die Vorschau. Gibt den Dialog zurück (zum Schließen von außen). */
    fun show(activity: Activity, item: MediaItem, position: Int = -1): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_peek, null)
        val image = view.findViewById<AspectImageView>(R.id.peekImage)
        val progress = view.findViewById<View>(R.id.peekProgress)
        val badge = view.findViewById<TextView>(R.id.peekBadge)
        val title = view.findViewById<TextView>(R.id.peekTitle)
        val sub = view.findViewById<TextView>(R.id.peekSub)
        val place = view.findViewById<TextView>(R.id.peekPlace)

        title.text = item.name
        sub.text = item.shortInfo() + " · " + Fmt.dateTime(item.time)

        val badgeText = when {
            item.isVideoFile && item.durationMs > 0 -> Fmt.duration(item.durationMs)
            item.isVideoFile -> "VIDEO"
            item.isRaw || item.isHeif || item.isAvif || item.isVector -> item.format
            else -> null
        }
        if (badgeText != null) {
            badge.text = badgeText
            badge.visibility = View.VISIBLE
        }

        // Vorschau laden: das Foto in Bildschirmbreite – der Decoder liefert es bereits
        // richtig gedreht (EXIF-Orientierung), die Ansicht übernimmt das Seitenverhältnis.
        val targetPx = (activity.resources.displayMetrics.widthPixels * 1.2f).toInt()
            .coerceIn(512, 2048)
        ImageLoader.loadPreview(activity, item, targetPx) { bmp ->
            progress.visibility = View.GONE
            if (bmp != null) {
                image.setImageBitmap(bmp)
            } else {
                // Kein Bild (z. B. exotisches RAW) – Hinweis statt leerer Fläche
                image.setImageResource(R.drawable.ic_image)
                val pad = (48 * activity.resources.displayMetrics.density).toInt()
                image.setPadding(pad, pad, pad, pad)
                image.minimumHeight = (120 * activity.resources.displayMetrics.density).toInt()
            }
        }
        // Sofort etwas zeigen, wenn schon eine Kachel im Speicher liegt
        ImageLoader.bestCached(item)?.let {
            image.setImageBitmap(it)
            progress.visibility = View.GONE
        }

        // Ort (offline): aus dem Zwischenspeicher oder direkt aus den Metadaten
        place.visibility = View.GONE
        // Merkt sich die gefundene Position – „Auf Karte zeigen“ springt dann genau dorthin.
        // (AtomicReference, weil sie im Hintergrund-Thread gesetzt und beim Antippen gelesen wird.)
        val found = java.util.concurrent.atomic.AtomicReference<Pair<Double, Double>?>(null)
        Thread({
            val pos = GeoStore.pointFor(item) ?: runCatching {
                GeoStore.readFromMetadata(activity.applicationContext, item)
            }.getOrNull()
            val loc = pos?.let {
                GeoIndex.load(activity.applicationContext)
                GeoIndex.nearest(it.first, it.second)
            }
            if (pos != null) found.set(pos)
            if (loc != null) {
                activity.runOnUiThread {
                    place.text = "📍 " + loc.label()
                    place.visibility = View.VISIBLE
                }
            }
        }, "n3-peek-place").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()

        val builder = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setBackground(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_peek))
        val dialog = builder.create()

        // Favorit
        val fav = view.findViewById<MaterialButton>(R.id.peekFav)
        fun syncFav() {
            val isFav = MetaStore.isFavorite(item.uri)
            fav.setText(if (isFav) R.string.fav_remove_short else R.string.favorite)
            fav.setIconResource(if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
        }
        syncFav()
        fav.setOnClickListener {
            val now = MetaStore.toggleFavorite(item.uri)
            syncFav()
            Toast.makeText(
                activity,
                if (now) R.string.fav_added else R.string.fav_removed,
                Toast.LENGTH_SHORT
            ).show()
        }

        view.findViewById<View>(R.id.peekShare).setOnClickListener {
            dialog.dismiss()
            share(activity, item)
        }
        view.findViewById<View>(R.id.peekEdit).setOnClickListener {
            dialog.dismiss()
            EditorActivity.start(activity, item.uri)
        }
        view.findViewById<View>(R.id.peekInfo).setOnClickListener {
            dialog.dismiss()
            ViewState.viewList = DataHub.all.ifEmpty { listOf(item) }
            val idx = ViewState.viewList.indexOfFirst { it.uri == item.uri }
                .takeIf { it >= 0 } ?: position
            activity.startActivity(
                InfoActivity.intentFor(activity, idx, item.uri)
            )
        }
        view.findViewById<View>(R.id.peekMap).setOnClickListener {
            dialog.dismiss()
            ViewState.viewList = DataHub.all.ifEmpty { listOf(item) }
            // Ist die Position bekannt, öffnet die Karte genau an dieser Stelle –
            // sonst zeigt sie alle Fotos (und liest die Position im Hintergrund nach).
            val at = found.get()
            val intent = if (at == null) Intent(activity, MapActivity::class.java)
            else MapActivity.intentFor(activity, at.first, at.second)
            runCatching { activity.startActivity(intent) }
        }
        view.findViewById<View>(R.id.peekDelete).setOnClickListener {
            dialog.dismiss()
            DeleteFlow.confirm(activity, item)
        }

        // Antippen der Vorschau öffnet das Foto groß
        image.maxHeightFraction = 0.58f
        image.setOnClickListener {
            dialog.dismiss()
            if (position >= 0) {
                activity.startActivity(
                    Intent(activity, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_POSITION, position)
                        .putExtra(DetailActivity.EXTRA_URI, item.uri)
                )
            }
        }
        image.setOnLongClickListener {
            dialog.dismiss()
            true
        }

        dialog.setOnShowListener {
            // Breite: fast der ganze Bildschirm (Rand bleibt für den runden Rahmen)
            dialog.window?.setLayout(
                activity.resources.displayMetrics.widthPixels -
                        (28 * activity.resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return dialog
    }

    private fun share(activity: Activity, item: MediaItem) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mime.ifBlank { "image/*" }
                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(item.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share)))
        } catch (t: Throwable) {
            Toast.makeText(
                activity,
                activity.getString(R.string.share_failed, t.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/** Löschen mit Rückfrage – an einer Stelle, damit überall gleich gefragt wird. */
object DeleteFlow {
    fun confirm(activity: Activity, item: MediaItem, onDeleted: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(activity.getString(R.string.delete_confirm_msg, item.name))
            .setPositiveButton(R.string.delete) { _, _ -> delete(activity, item, onDeleted) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(activity: Activity, item: MediaItem, onDeleted: (() -> Unit)?) {
        val uri = android.net.Uri.parse(item.uri)
        try {
            if (uri.scheme == "content") {
                activity.contentResolver.delete(uri, null, null)
            } else {
                java.io.File(uri.path ?: return).delete()
            }
            GeoStore.forget(item.uri)
            ImageLoader.clear()
            Toast.makeText(activity, R.string.deleted, Toast.LENGTH_SHORT).show()
            DataHub.requestRescan()
            onDeleted?.invoke()
        } catch (t: Throwable) {
            Toast.makeText(
                activity,
                activity.getString(R.string.delete_failed, t.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
