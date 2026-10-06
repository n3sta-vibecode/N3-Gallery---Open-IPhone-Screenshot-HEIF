package com.n3vibecode.gallery.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.image.Decoder
import com.n3vibecode.gallery.image.HeifInspector
import com.n3vibecode.gallery.image.ImageLoader
import com.n3vibecode.gallery.widget.ZoomImageView

/** Eine Seite im Vollbild-Viewer. */
class MediaPageFragment : Fragment() {

    private var item: MediaItem? = null

    /** Ist das die gerade sichtbare Seite? Nur die lädt das Vollbild. */
    @Volatile private var primary = false
    private var image: android.widget.ImageView? = null
    private var progress: ProgressBar? = null
    private var message: TextView? = null
    private var hint: TextView? = null
    private var maxPx = 0
    private var fullRequested = false

    /** true, sobald das Vollbild angezeigt wird – dann keine kleinere Vorschau mehr darüber. */
    @Volatile private var fullShown = false

    /**
     * Wird von der Großansicht aufgerufen, wenn diese Seite sichtbar wird bzw. nicht mehr
     * sichtbar ist. Nur die sichtbare Seite dekodiert das Vollbild – die Nachbarseiten
     * bleiben bei der schnellen 1024-px-Vorschau (Vorbereitung fürs Wischen).
     * Vorher dekodierten immer drei Seiten gleichzeitig, wodurch das angetippte Foto
     * sekundenlang auf sich warten ließ.
     */
    fun setPrimary(value: Boolean) {
        if (value == primary) return
        primary = value
        if (value) requestFull()
    }

    private fun requestFull() {
        val media = item ?: return
        val image = image ?: return
        loadFull(media, image)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val index = arguments?.getInt(ARG_INDEX, 0) ?: 0
        item = ViewState.viewList.getOrNull(index)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_media_page, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val image = view.findViewById<ZoomImageView>(R.id.image)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val message = view.findViewById<TextView>(R.id.tvMessage)
        val hint = view.findViewById<TextView>(R.id.tvHint)
        this.image = image
        this.progress = progress
        this.message = message
        this.hint = hint

        val media = item
        if (media == null) {
            progress.visibility = View.GONE
            message.visibility = View.VISIBLE
            message.text = getString(R.string.unsupported_preview)
            return
        }

        hint.text = Decoder.hintFor(media)
        hint.visibility = if (hint.text.isNullOrBlank()) View.GONE else View.VISIBLE

        // Einfacher Tipp = Leisten ein-/ausblenden · Doppeltipp/Zwei Finger = zoomen
        image.onSingleTap = { (activity as? DetailActivity)?.toggleBars() }
        image.onZoomChanged = { zoomed ->
            // Während des Zoomens darf der Pager nicht zwischen Fotos wischen
            (activity as? DetailActivity)?.setPagerInputEnabled(!zoomed)
        }
        // Wie in der Google-Fotos-App: rauszoomen (unter 1×) oder nach unten wischen
        // führt zurück zur Übersicht mit allen Fotos.
        image.onZoomOutToGrid = { (activity as? DetailActivity)?.finish() }
        image.onSwipeDownBack = { (activity as? DetailActivity)?.finish() }
        val zoomHint = view.findViewById<TextView>(R.id.tvZoomHint)
        if (!zoomHintShown) {
            zoomHintShown = true
            zoomHint.visibility = View.VISIBLE
            zoomHint.postDelayed({
                if (!isAdded) return@postDelayed
                zoomHint.animate().alpha(0f).setDuration(400)
                    .withEndAction { zoomHint.visibility = View.GONE }.start()
            }, 4500)
        }

        val maxPx = ImageLoader.detailPx(requireContext())
        this.maxPx = maxPx
        fullShown = false

        // Mini-Vorschau (48 px) wird hier absichtlich NICHT hochgezogen: auf
        // Bildschirmgröße wäre sie stark unscharf („lädt erst dann scharf“).
        // 1) Zuerst die schon geladene Raster-Kachel (scharf, ohne Wartezeit)
        var cachedPx = 0
        ImageLoader.bestCached(media)?.let {
            image.setImageBitmap(it)
            cachedPx = maxOf(it.width, it.height)
            progress.visibility = View.GONE
        }
        // 2) Zwischenbild (1024 px) nur, wenn noch nichts Brauchbares im Speicher liegt.
        //    Liegt schon eine Kachel ab 256 px vor, wäre die Vorschau ein **zweiter voller
        //    Dekodiervorgang für dasselbe Foto** – bei HEIC kostet genau das die Wartezeit
        //    beim Öffnen. Dann lieber direkt das scharfe Vollbild.
        if (cachedPx < 256) {
            ImageLoader.loadPreview(requireContext(), media) { prev ->
                if (isAdded && !fullShown && prev != null) {
                    image.setImageBitmap(prev)
                    progress.visibility = View.GONE
                    message.visibility = View.GONE
                }
            }
        }
        // 3) Vollbild in Bildschirmgröße – aber nur auf der gerade sichtbaren Seite
        if (!primary) primary = arguments?.getBoolean(ARG_PRIMARY, false) == true
        if (primary) loadFull(media, image)
    }

    /** Vollbild laden (eigene Funktion, damit sie auch später beim Wischen starten kann). */
    private fun loadFull(media: MediaItem, image: android.widget.ImageView) {
        if (fullRequested || !isAdded) return
        val progress = this.progress ?: return
        val message = this.message ?: return
        val hint = this.hint ?: return
        fullRequested = true
        ImageLoader.loadFullDetailed(requireContext(), media, maxPx) { bmp: Bitmap?, path ->
            if (!isAdded) return@loadFullDetailed
            progress.visibility = View.GONE
            if (bmp != null) {
                fullShown = true
                image.setImageBitmap(bmp)
                message.visibility = View.GONE
                Decoder.hintForPath(path)?.let {
                    hint.text = it
                    hint.visibility = View.VISIBLE
                }
                return@loadFullDetailed
            }
            // Zweiter Versuch: JPEG-Eintrag aus einem HEIF-Container holen (z. B. JPEG-kodiertes HEIF)
            if (media.isHeif || media.isAvif) {
                val uri = media.uriObj()
                val info = runCatching {
                    HeifInspector.inspect(
                        open = { runCatching { requireContext().contentResolver.openInputStream(uri) }.getOrNull() },
                        fileSize = media.size
                    )
                }.getOrNull()
                val extracted = info?.let {
                    Decoder.decodeHeifJpegItem(requireContext(), media, it, maxPx)
                }
                if (extracted != null) {
                    image.setImageBitmap(extracted)
                    message.visibility = View.GONE
                    hint.visibility = View.VISIBLE
                    hint.text = getString(R.string.jpeg_from_heif)
                    return@loadFullDetailed
                }
            }
            message.visibility = View.VISIBLE
            message.text = buildString {
                append(getString(R.string.unsupported_preview))
                append("\n\n")
                append(media.format)
                append(" · ")
                append(media.shortInfo())
                val detail = diagnose(media)
                if (detail.isNotEmpty()) {
                    append("\n\n")
                    append(detail)
                }
            }
        }
    }

    /** Kurze technische Einordnung, falls ein Format doch nicht angezeigt werden kann. */
    private fun diagnose(item: MediaItem): String {
        val uri = item.uriObj()
        val info = runCatching {
            com.n3vibecode.gallery.image.HeifInspector.inspect(
                open = { runCatching { requireContext().contentResolver.openInputStream(uri) }.getOrNull() },
                fileSize = item.size
            )
        }.getOrNull() ?: return ""
        val parts = mutableListOf<String>()
        info.primaryType?.let { parts += "Codierung: " + com.n3vibecode.gallery.image.HeifInspector.codedTypeName(it) }
        info.tiles?.let { parts += "Kacheln: ${it.cols} × ${it.rows}" }
        info.hevcProfile?.let { parts += "HEVC-Profil: " + (com.n3vibecode.gallery.image.HeifInspector.hevcProfileName(it) ?: "?") }
        info.bitDepth?.let { parts += "$it Bit" }
        parts += "Container: " + info.majorBrand
        return parts.joinToString(" · ")
    }

    override fun onDestroyView() {
        (activity as? DetailActivity)?.setPagerInputEnabled(true)
        // Wichtig: keine Verweise auf die zerstörte Ansicht behalten – sonst würde ein
        // späteres „diese Seite ist jetzt sichtbar“ auf alte Views zugreifen.
        image = null
        progress = null
        message = null
        hint = null
        super.onDestroyView()
    }

    companion object {
        private var zoomHintShown = false
        const val ARG_INDEX = "index"
        const val ARG_PRIMARY = "primary"

        fun create(index: Int, primary: Boolean): MediaPageFragment = MediaPageFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_INDEX, index)
                putBoolean(ARG_PRIMARY, primary)
            }
        }
    }
}
