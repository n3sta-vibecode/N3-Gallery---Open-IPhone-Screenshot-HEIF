package com.n3vibecode.gallery.data

/**
 * **Apple-Aufnahmedaten (MakerNote) lesen** – die Felder, die iPhone-Fotos von einer
 * gewöhnlichen Kamera unterscheiden und die in der Samsung-Galerie unter „Mehr“ stehen.
 *
 * Apple legt sie im MakerNote des EXIF-Blocks ab, teils als binäres „Apple Maker Note“
 * (Laufzeit, Beschleunigungssensor, HDR-Spielraum), teils als Text (Bildstil, Linsentyp).
 * Ein vollständiger Parser für das binäre Format ist nicht öffentlich dokumentiert; deshalb
 * wird hier **konservativ** gelesen:
 *
 *  • Klartext-Felder, die Apple als ASCII in den Block schreibt (Bildstil, Linsentyp,
 *    Signal-Rausch-Abstand, Qualität, Aufnahmeart) werden über ihre Kennungen gesucht,
 *  • binäre Felder werden nur übernommen, wenn sie sich sauber als Zahlen lesen lassen.
 *
 * Was nicht sauber gelesen werden kann, wird **nicht** angezeigt – lieber eine Lücke als
 * ein falscher Wert. Reine Textlogik, dadurch ohne Gerät prüfbar ([AppleMakerNoteTest]).
 */
object AppleMakerNote {

    /** Kennungen im MakerNote → deutsche Bezeichnung. */
    private val KEYS = listOf(
        "SemanticStyle" to "Apple: Bildstil",
        "LensType" to "Apple: Objektiv-Typ",
        "Quality" to "Apple: Qualität",
        "ImageCaptureType" to "Apple: Aufnahmeart",
        "SignalToNoiseRatio" to "Apple: Signal-Rausch-Abstand",
        "PhotoIdentifier" to "Apple: Foto-Kennung",
        "RunTime" to "Apple: Laufzeit (RunTime)",
        "AccelerationVector" to "Apple: Beschleunigungssensor",
        "HDRHeadroom" to "Apple: HDR-Spielraum"
    )

    /**
     * Durchsucht den gelesenen Container-Text nach Apple-Feldern.
     * Ergebnis: fertige Zeilen (Bezeichnung → Wert), höchstens [limit] Stück.
     */
    fun parse(text: String, limit: Int = 12): List<Pair<String, String>> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, String>>(limit)
        for ((key, label) in KEYS) {
            val value = valueFor(text, key) ?: continue
            out.add(label to value)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * Sucht `<key>` im Text und liest den folgenden Wert.
     *
     * Im MakerNote steht der Schlüssel als ASCII, danach folgen Struktur-Bytes (Länge, Typ)
     * und erst dann der Wert. Deshalb wird nach dem Schlüssel bis zu 24 Byte weitergesprungen
     * und der **erste lesbare Abschnitt** genommen – bei Text der Text, bei Zahlen die Zahlen.
     *
     * Bewusst streng: Was nicht eindeutig lesbar ist, wird **nicht** angezeigt (lieber eine
     * Lücke als ein falscher Wert). Ein Text endet am NUL-Byte, damit nicht der nächste
     * Schlüssel mit hineinrutscht.
     */
    fun valueFor(text: String, key: String): String? {
        val at = text.indexOf(key)
        if (at < 0) return null
        val window = text.substring(at + key.length, minOf(text.length, at + key.length + 64))

        // 1) Klartext: erstes lesbares Stück direkt nach dem Schlüssel (NUL-getrennt)
        val printable = StringBuilder()
        var started = false
        for (ch in window) {
            val ok = ch.code in 32..126
            if (ok) {
                printable.append(ch)
                started = true
            } else if (started) {
                break
            }
        }
        val candidate = printable.toString().trim()
        if (candidate.length >= 3 && candidate.any { it.isLetter() }) {
            // nicht über den nächsten Schlüssel hinauslesen
            return cutAtNextKey(candidate)
        }

        // 2) Zahlen (RunTime/AccelerationVector): kurze Zahlenfolge im Rohfenster
        val numbers = Regex("""-?\d+(?:[.,]\d+)?""").findAll(window).map { it.value }.take(4).toList()
        return when {
            numbers.size >= 2 -> numbers.joinToString(", ")
            numbers.size == 1 -> numbers.first()
            else -> null
        }
    }

    /** Schneidet bei einem anderen bekannten Schlüssel ab (verhindert „TeleQualityHighest“). */
    private fun cutAtNextKey(text: String): String {
        var end = text.length
        for ((key, _) in KEYS) {
            val at = text.indexOf(key)
            if (at > 0) end = minOf(end, at)
        }
        return text.substring(0, end).trim()
    }
}
