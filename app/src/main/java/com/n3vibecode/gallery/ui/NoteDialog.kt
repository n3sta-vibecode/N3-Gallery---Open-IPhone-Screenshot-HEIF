package com.n3vibecode.gallery.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.MediaItem

/**
 * Dialog für Notiz + Tags. Standard ist das Schreiben in die Bilddatei (EXIF/XMP),
 * wie von Apple Fotos / Lightroom gewohnt.
 */
object NoteDialog {

    fun show(
        activity: Activity,
        item: MediaItem,
        note: String,
        tags: List<String>,
        onSave: (String, List<String>, Boolean) -> Unit,
        onSidecar: () -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_note, null)
        val etNote = view.findViewById<TextInputEditText>(R.id.etNote)
        val etTags = view.findViewById<TextInputEditText>(R.id.etTags)
        val sw = view.findViewById<MaterialSwitch>(R.id.swWriteFile)
        val warn = view.findViewById<TextView>(R.id.tvWarn)

        etNote.setText(note)
        etTags.setText(tags.joinToString(", "))

        val supported = com.n3vibecode.gallery.data.ExifRepository.isWriteSupported(item)
        if (!supported) {
            sw.isChecked = false
            sw.isEnabled = false
            warn.visibility = View.VISIBLE
            warn.text = "${item.format} kann Android nicht beschreiben. Die Notiz wird in der App gespeichert – " +
                    "zusätzlich lässt sich ein XMP-Sidecar (wie bei Lightroom für RAW üblich) exportieren."
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.note_edit)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val newNote = etNote.text?.toString().orEmpty()
                val newTags = etTags.text?.toString().orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                onSave(newNote, newTags, sw.isChecked)
            }
            .setNeutralButton("XMP-Sidecar …") { _, _ -> onSidecar() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
