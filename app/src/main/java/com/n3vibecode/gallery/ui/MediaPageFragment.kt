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
        var fullShown = false

        // 0) Ganz zuerst die Mini-Vorschau (48 px, weich hochgerechnet): sofort Bildinhalt,
        //    während die scharfe Version noch dekodiert wird.
        if (ImageLoader.showMicro(requireContext(), media, image)) {
            progress.visibility = View.GONE
        }
        // 1) Dann die schon geladene Raster-Kachel (schärfer, aber ohne Wartezeit)
        ImageLoader.bestCached(media)?.let {
            image.setImageBitmap(it)
            progress.visibility = View.GONE
        }
        // 2) Gleich danach: 1024-px-Vorschau (scharf genug), bis das Vollbild fertig ist
        ImageLoader.loadPreview(requireContext(), media) { prev ->
            if (isAdded && !fullShown && prev != null) {
                image.setImageBitmap(prev)
                progress.visibility = View.GONE
                message.visibility = View.GONE
            }
        }
        // 3) Vollbild in Bildschirmgröße
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
        super.onDestroyView()
    }

    companion object {
        private var zoomHintShown = false
        const val ARG_INDEX = "index"

        fun create(index: Int): MediaPageFragment = MediaPageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_INDEX, index) }
        }
    }
}
