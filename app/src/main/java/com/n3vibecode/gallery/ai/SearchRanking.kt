package com.n3vibecode.gallery.ai

/**
 * Rangliste der Suchergebnisse – **ohne Android-Abhängigkeiten**, damit sie prüfbar ist.
 *
 * Vorher wurden Wort-Treffer und KI-Treffer einfach aneinandergehängt: erst alle
 * Dateinamen-Treffer, dann alle KI-Treffer. Ein Foto, das die KI als besten Treffer sieht,
 * landete dadurch hinter jedem zufälligen Namenstreffer – die Suche wirkte schwächer als
 * nötig. Hier wird stattdessen **rangiert**.
 */
object SearchRanking {

    /**
     * @param wordOnly  Treffer der Wortsuche in ihrer Reihenfolge (Dateiname, Ordner, Notiz …)
     * @param aiHits    Treffer der Bildsuche, bester zuerst
     * @param uriOf     wie die URI eines Treffers ermittelt wird (bleibt beim Aufrufer)
     */
    fun <T> rank(wordOnly: List<T>, aiHits: List<T>, uriOf: (T) -> String): List<T> {
        val out = ArrayList<T>(wordOnly.size + aiHits.size)
        val seen = HashSet<String>(wordOnly.size + aiHits.size)
        val wordUris = wordOnly.mapTo(HashSet(wordOnly.size)) { uriOf(it) }

        // 1) Von beiden Wegen bestätigt – die stärksten Treffer
        for (item in aiHits) {
            if (uriOf(item) in wordUris && seen.add(uriOf(item))) out.add(item)
        }
        // 2) Nur KI – in der Reihenfolge der Ähnlichkeit
        for (item in aiHits) {
            if (seen.add(uriOf(item))) out.add(item)
        }
        // 3) Nur Wort
        for (item in wordOnly) {
            if (seen.add(uriOf(item))) out.add(item)
        }
        return out
    }

    /**
     * Suchtext normalisieren: klein, ohne Umlaute, ohne Satzzeichen.
     * `"Grüne Wiese!"` → `"gruene wiese"` – damit „grun“, „grün“ und „GRÜNE“ dasselbe finden.
     */
    fun normalize(text: String): String = text.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace("é", "e").replace("è", "e").replace("à", "a").replace("ç", "c")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /** Passt der Suchtext? Alle Wörter müssen vorkommen – als Teilwort oder Wortanfang. */
    fun matches(query: String, candidate: String): Boolean {
        val terms = normalize(query).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return false
        val text = normalize(candidate)
        if (text.isEmpty()) return false
        val words = text.split(' ')
        return terms.all { t -> text.contains(t) || words.any { it.startsWith(t) } }
    }
}
