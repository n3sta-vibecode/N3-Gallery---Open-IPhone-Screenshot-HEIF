package com.n3vibecode.gallery.ai

import com.n3vibecode.gallery.data.ExifRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Dinge, die man vorher nur „auf dem Gerät sieht“:
 *
 *  • Rangliste der Suche (KI-Treffer dürfen nicht hinter Wort-Treffern verschwinden)
 *  • Umlaut-/Teilwort-Suche
 *  • lesbare Hauptdarsteller-Angaben (Megapixel)
 *  • Koordinaten aus XMP und ISO 6709 (der Grund, warum „kein Ort“ dastand)
 */
class SearchRankingTest {

    private data class It(val uri: String)

    @Test
    fun kiTrefferStehtVorReinemWortTreffer() {
        val word = listOf(It("dateiname.jpg"), It("ordner.png"))
        val ai = listOf(It("ki_best.jpg"), It("ki_zweit.jpg"))
        val out = SearchRanking.rank(word, ai) { it.uri }
        // Die KI-Reihenfolge bleibt vorn, die Wort-Treffer hängen hinten dran
        assertEquals(
            listOf("ki_best.jpg", "ki_zweit.jpg", "dateiname.jpg", "ordner.png"),
            out.map { it.uri }
        )
    }

    @Test
    fun vonBeidenWegenGefundenStehtGanzOben() {
        val word = listOf(It("beides.jpg"), It("nur_wort.jpg"))
        val ai = listOf(It("nur_ki.jpg"), It("beides.jpg"))
        val out = SearchRanking.rank(word, ai) { it.uri }
        assertEquals("beides.jpg", out.first().uri)
        assertEquals(3, out.size)
        // keine Dubletten
        assertEquals(out.size, out.map { it.uri }.toSet().size)
    }

    @Test
    fun umlauteUndGrossschreibungEgal() {
        assertTrue(SearchRanking.matches("GRÜN", "gruen"))
        assertTrue(SearchRanking.matches("grun", "Grüne Wiese"))
        assertTrue(SearchRanking.matches("wiese", "Grüne Wiese"))
        assertTrue(SearchRanking.matches("gRüNe", "gruene wiese"))
    }

    @Test
    fun teilwortUndWortanfangTreffen() {
        // Teilwort mitten im Wort
        assertTrue(SearchRanking.matches("strand", "Strandbad Luzern"))
        // Wortanfang
        assertTrue(SearchRanking.matches("luz", "Luzern"))
        // mehrere Wörter in beliebiger Reihenfolge
        assertTrue(SearchRanking.matches("luzern strand", "Strandbad Luzern"))
        // kein Treffer
        assertFalse(SearchRanking.matches("berg", "Strandbad Luzern"))
        assertFalse(SearchRanking.matches("", "Luzern"))
    }

    @Test
    fun megapixelMitZweiStellen() {
        // 4032 × 3024 = 12.192.768 → 12,19 MP (Punkt im Code, Komma in der Anzeige)
        assertEquals("12,19 MP", ExifRepository.megapixelPrecise(4032, 3024))
        assertEquals("48,00 MP", ExifRepository.megapixelPrecise(8000, 6000))
        assertEquals("0,01 MP", ExifRepository.megapixelPrecise(100, 100))
        assertEquals("–", ExifRepository.megapixelPrecise(0, 0))
    }

    @Test
    fun koordinatenSindKopierbar() {
        assertEquals("47.050647, 8.306553", ExifRepository.coordinates(47.050647, 8.306553))
        // Süd/West bleiben negativ
        assertEquals("-33.868820, -151.209290", ExifRepository.coordinates(-33.86882, -151.20929))
    }

    @Test
    fun iso6709AusAppleHeif() {
        // So schreibt Apple den Ort in den HEIF-Container
        val p = ExifRepository.parseIso6709("...irgendwas...+47.0506+008.3066/...")
        assertEquals(47.0506, p!![0], 0.0001)
        assertEquals(8.3066, p[1], 0.0001)
        // Negative Werte (Süd/West)
        val q = ExifRepository.parseIso6709("x-33.8688-151.2093/")
        assertEquals(-33.8688, q!![0], 0.0001)
        assertEquals(-151.2093, q[1], 0.0001)
        // Unsinn wird abgelehnt statt eine falsche Position zu behaupten
        assertNull(ExifRepository.parseIso6709("keine koordinaten hier"))
        assertNull(ExifRepository.parseIso6709("+999.0+008.3"))
    }

    @Test
    fun xmpKoordinatenMitHimmelsrichtung() {
        val xmp = """<exif:GPSLatitude>47,050647N</exif:GPSLatitude>
                     <exif:GPSLongitude>8,306553E</exif:GPSLongitude>"""
        assertEquals(47.050647, ExifRepository.xmpCoordinate(xmp, "GPSLatitude")!!, 0.00001)
        assertEquals(8.306553, ExifRepository.xmpCoordinate(xmp, "GPSLongitude")!!, 0.00001)
        // S/W müssen negativ werden
        assertEquals(-33.8688, ExifRepository.xmpCoordinate("GPSLatitude=33,8688S", "GPSLatitude")!!, 0.0001)
        assertEquals(-8.3065, ExifRepository.xmpCoordinate("GPSLongitude 8,3065W", "GPSLongitude")!!, 0.0001)
        assertNull(ExifRepository.xmpCoordinate("nichts", "GPSLatitude"))
    }
}
