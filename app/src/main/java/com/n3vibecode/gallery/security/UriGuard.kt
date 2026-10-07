package com.n3vibecode.gallery.security

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.util.Locale

/**
 * Abschirmung aller Daten, die von AUSSEN in die App kommen.
 *
 * Hintergrund: [com.n3vibecode.gallery.ui.MainActivity] ist `exported="true"` und nimmt
 * `ACTION_VIEW` mit `mimeType="image/*"` an. Damit kann **jede** App auf dem Gerät – auch
 * eine völlig rechtelose – bestimmen, welche URI die Galerie öffnet. Zusätzlich legt
 * [com.n3vibecode.gallery.data.MetaBackup] beim Start eine `n3-sicherung.json` aus dem
 * geteilten Speicher als Notizen/Favoriten aus – auch diese URIs sind fremdbestimmt und
 * laufen durch diesen Guard.
 *
 * Abgewehrt werden (siehe `security/SICHERHEITS-ANALYSE.md`, Befunde V1–V3):
 *
 *  • `file://`-URIs                  → Pfad wird vom Angreifer gewählt; führte in
 *                                      `directDelete()`/`deleteDirect()` zu
 *                                      `File(uri.path).delete()` und in
 *                                      `MediaSaver.overwrite()` zu `File(uri.path)`-Schreiben.
 *  • `http://` / `https://`          → die App ist offline; Netz-URIs haben hier nichts
 *                                      zu suchen (SSRF-/Nachlade-Angriffsfläche).
 *  • unbekannte Schemata             → alles außer `content` wird abgewiesen.
 *  • Path Traversal                  → `Uri.getLastPathSegment()` **dekodiert**
 *                                      Prozent-Kodierung, `%2e%2e%2f` wird zu `../`.
 *                                      Der daraus gebaute Dateiname wurde ungeprüft in
 *                                      `File(shareDir(), name)` verwendet.
 *
 * Design-Entscheidung: **Allowlist statt Blocklist.** Es wird nicht nach „bösen"
 * Mustern gesucht, sondern ausschließlich ein enger, wohlverstandener Korridor erlaubt.
 */
object UriGuard {

    /** Maximale Länge einer URI, die wir überhaupt anfassen. */
    private const val MAX_URI_LENGTH = 4096

    /** Maximale Länge eines Dateinamens (DISPLAY_NAME), den wir übernehmen. */
    private const val MAX_NAME_LENGTH = 180

    /**
     * Autoritäten, die wir als vertrauenswürdig behandeln.
     *
     * `media` ist der Android-MediaStore. Alle anderen ContentProvider (auch die per SAF
     * vom Nutzer explizit freigegebenen `com.android.externalstorage.documents`) sind
     * *nicht* per se vertrauenswürdig – sie werden deshalb zwar geöffnet, aber ihr
     * DISPLAY_NAME wird zusätzlich durch [safeFileName] geschleust.
     */
    private val TRUSTED_AUTHORITIES = setOf(
        MediaStore.AUTHORITY,                       // "media"
        "com.android.externalstorage.documents",    // SAF / DocumentsUI
        "com.android.providers.downloads.documents"
    )

    /** Ergebnis der Prüfung – entweder eine bereinigte URI oder eine Begründung. */
    sealed class Verdict {
        data class Accept(val uri: Uri, val displayName: String, val trusted: Boolean) : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    // ------------------------------------------------------------------ Prüfung

    /**
     * Prüft eine URI, die per Intent von einer fremden App hereinkam.
     *
     * Liefert [Verdict.Accept] nur für `content://`-URIs ohne Traversal-Muster.
     */
    fun verifyIncoming(ctx: Context, raw: Uri?, declaredMime: String?): Verdict {
        if (raw == null) return Verdict.Reject("keine URI übergeben")

        val asString = raw.toString()
        if (asString.length > MAX_URI_LENGTH) {
            return Verdict.Reject("URI zu lang (${asString.length} Zeichen)")
        }

        val scheme = raw.scheme?.lowercase(Locale.ROOT)
        when (scheme) {
            "content" -> { /* einzig zulässiges Schema, wird unten weiter geprüft */ }
            "file" -> return Verdict.Reject(
                "file://-URIs werden nicht akzeptiert (Pfad wäre fremdbestimmt)"
            )
            "http", "https", "ftp" -> return Verdict.Reject(
                "Netz-URIs werden nicht akzeptiert (App arbeitet offline)"
            )
            null -> return Verdict.Reject("URI ohne Schema")
            else -> return Verdict.Reject("unzulässiges Schema: $scheme")
        }

        // Opaque URIs (content:foo:bar ohne "//") haben keinen Pfad, den wir
        // sinnvoll einordnen können -> ablehnen.
        if (raw.isOpaque) return Verdict.Reject("opaque URI wird nicht akzeptiert")

        val authority = raw.authority?.lowercase(Locale.ROOT).orEmpty()
        if (authority.isEmpty()) return Verdict.Reject("content-URI ohne Authority")

        // Traversal-Prüfung auf dem ROHEN und auf dem DEKODIERTEN Pfad. Beides ist
        // nötig: "%2e%2e%2f" ist roh harmlos, dekodiert aber zu "../".
        val encodedPath = raw.encodedPath.orEmpty()
        val decodedPath = raw.path.orEmpty()
        for (candidate in listOf(encodedPath, decodedPath)) {
            if (containsTraversal(candidate)) {
                return Verdict.Reject("Path Traversal in URI erkannt")
            }
        }
        // Zusätzlich wird jedes dekodierte Segment einzeln geprüft.
        //
        // Bewusst NICHT abgelehnt wird ein '/' innerhalb eines Segments: SAF-URIs
        // tragen ihre Dokument-ID als percent-enkodierten Pfad, z. B.
        //   content://com.android.externalstorage.documents/document/
        //       primary%3ADCIM%2FCamera%2FIMG_0001.HEIC
        // `Uri.getPathSegments()` dekodiert, das Segment enthält danach echte '/'.
        // Ein '/' im Segment kann trotzdem keinen Ausbruch bewirken, weil
        // [safeFileName] für jeden Dateinamen auf das letzte Segment reduziert.
        for (seg in raw.pathSegments.orEmpty()) {
            if (seg.indexOf('\u0000') >= 0) {
                return Verdict.Reject("Nullbyte in URI")
            }
            if (seg.split('/', '\\').any { it == ".." }) {
                return Verdict.Reject("unzulässiges Pfadsegment")
            }
        }

        val trusted = authority in TRUSTED_AUTHORITIES
        val name = queryDisplayName(ctx, raw)
            ?: safeFileName(raw.lastPathSegment ?: "Bild")

        if (declaredMime != null && !isPlausibleMediaMime(declaredMime)) {
            return Verdict.Reject("unplausibler MIME-Typ: $declaredMime")
        }

        return Verdict.Accept(raw, name, trusted)
    }

    /**
     * Erkennt Path Traversal in jeder relevanten Kodierungsstufe.
     *
     * Geprüft wird pro **Pfadkomponente**, nicht per Substring: Eine Datei darf
     * legitimately `..` im Namen tragen (z. B. `my..photo.jpg`), solange keine
     * Komponente für sich allein `..` ist.
     */
    fun containsTraversal(path: String): Boolean {
        if (path.indexOf('\u0000') >= 0) return true        // Nullbyte-Smuggling
        var current = path
        repeat(3) {
            if (hasTraversalComponents(current)) return true
            val decoded = Uri.decode(current)
            if (decoded == current) return false
            current = decoded
        }
        return hasTraversalComponents(current)
    }

    private fun hasTraversalComponents(path: String): Boolean =
        path.split('/', '\\').any { it == ".." }

    // ------------------------------------------------------------------ Dateinamen

    /**
     * Macht aus einem beliebigen (möglicherweise feindlichen) String einen
     * Dateinamen, der garantiert **kein** Verzeichnis verlässt.
     */
    fun safeFileName(raw: String?): String {
        if (raw.isNullOrBlank()) return "Bild"

        var s = raw
        // Prozent-Kodierung auflösen, damit "%2e%2e%2f" nicht erst später zu "../" wird
        repeat(2) { s = Uri.decode(s) }

        // Nur das letzte Segment behalten – schneidet jeden Pfad ab
        s = s.substringAfterLast('/').substringAfterLast('\\')

        // Steuerzeichen, Nullbytes und alles Nicht-Druckbare entfernen
        s = s.filter { it.isLetterOrDigit() || it in ALLOWED_NAME_CHARS }

        // führende Punkte entfernen -> verhindert "..", ".", versteckte Dateien
        s = s.trimStart('.')

        if (s.isBlank()) return "Bild"
        if (s.length > MAX_NAME_LENGTH) {
            val dot = s.lastIndexOf('.')
            s = if (dot > s.length - 12 && dot > 0) {
                s.substring(s.length - MAX_NAME_LENGTH)
            } else {
                s.substring(0, MAX_NAME_LENGTH)
            }
        }
        return s.ifBlank { "Bild" }
    }

    private val ALLOWED_NAME_CHARS = setOf('.', '-', '_', ' ', '(', ')', '+', '=', ',', '@')

    /**
     * Liefert den DISPLAY_NAME über den ContentResolver – der vertrauenswürdige Weg,
     * weil der Provider ihn vergibt und nicht der Pfad der URI. Auch dieser Wert wird
     * durch [safeFileName] geschleust.
     */
    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) safeFileName(c.getString(idx)) else null
                } else null
            }
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ MIME

    /** Nur Bild-/Video-/RAW-MIME-Typen sind plausibel. */
    fun isPlausibleMediaMime(mime: String): Boolean {
        val m = mime.lowercase(Locale.ROOT).trim()
        if (m.length > 128) return false
        if (m.indexOf('\u0000') >= 0) return false
        return when {
            m == "*/*" -> true
            m.startsWith("image/") -> true
            m.startsWith("video/") -> true
            m.startsWith("application/octet-stream") -> true
            m.startsWith("application/dng") || m.startsWith("application/x-adobe-dng") -> true
            else -> false
        }
    }

    /** Ist diese URI sicher vom Android-MediaStore vergeben? */
    fun isMediaStore(uri: Uri): Boolean =
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, true) &&
                uri.authority.equals(MediaStore.AUTHORITY, true)
}
