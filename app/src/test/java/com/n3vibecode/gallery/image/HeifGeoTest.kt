package com.n3vibecode.gallery.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Beweist, dass die GPS-Daten und der Aufnahme-Sensor aus einem **HEIF-Container**
 * gelesen werden — ohne Gerät, ohne echte iPhone-Datei.
 *
 * Der Test baut im Speicher genau die Struktur, die Apple schreibt:
 * `ftyp` + `meta(iinf, iloc, iprp/ipco(irot))` + `mdat(Bild, Exif-Item)`.
 * Damit ist der Fall abgedeckt, der auf dem Gerät „kein Ort gespeichert“ ergeben hat.
 */
class HeifGeoTest {

    // ---------------------------------------------------------------- Testdatei bauen

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + payload.size
        out.write((size ushr 24) and 0xFF); out.write((size ushr 16) and 0xFF)
        out.write((size ushr 8) and 0xFF); out.write(size and 0xFF)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun u32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun u16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun rational(num: Int, den: Int): ByteArray = u32(num) + u32(den)

    /** Baut ein Apple-artiges HEIF mit EXIF (IFD0, Exif-IFD, GPS-IFD) und Drehung. */
    private fun buildHeif(
        make: String = "Apple",
        model: String = "iPhone 15 Pro",
        lens: String = "iPhone 15 Pro back camera 6.765mm f/1.78",
        latDms: Triple<Int, Int, Double> = Triple(47, 3, 2.3),
        lonDms: Triple<Int, Int, Double> = Triple(8, 18, 23.9),
        refLat: String = "N",
        refLon: String = "E",
        rotate: Int = 1,
        withGps: Boolean = true
    ): ByteArray {
        val latRaw = rational((latDms.first * 1000), 1000) +
            rational((latDms.second * 1000), 1000) +
            rational(((latDms.third * 1000)).toInt(), 1000)
        val lonRaw = rational((lonDms.first * 1000), 1000) +
            rational((lonDms.second * 1000), 1000) +
            rational(((lonDms.third * 1000)).toInt(), 1000)
        val makeRaw = make.toByteArray() + byteArrayOf(0)
        val modelRaw = model.toByteArray() + byteArrayOf(0)
        val lensRaw = lens.toByteArray() + byteArrayOf(0)
        val dateRaw = "2026:07:14 18:22:31".toByteArray() + byteArrayOf(0)

        val ifd0Size = 2 + 4 * 12 + 4
        val exifCount = 6
        val exifSize = 2 + exifCount * 12 + 4
        val gpsCount = if (withGps) 4 else 0
        val gpsSize = 2 + gpsCount * 12 + 4

        val ifd0Off = 8
        val exifOff = ifd0Off + ifd0Size
        val gpsOff = exifOff + exifSize
        val dataOff = gpsOff + gpsSize

        val latOff = dataOff
        val lonOff = latOff + latRaw.size
        val makeOff = lonOff + lonRaw.size
        val modelOff = makeOff + makeRaw.size
        val lensOff = modelOff + modelRaw.size
        val dateOff = lensOff + lensRaw.size
        val expRaw = rational(1, 120)
        val expOff = dateOff + dateRaw.size
        val fnumRaw = rational(178, 100)
        val fnumOff = expOff + expRaw.size

        fun entry(tag: Int, type: Int, count: Int, value: Int) =
            u16(tag) + u16(type) + u32(count) + u32(value)

        fun entryInline(tag: Int, type: Int, count: Int, a: Int, b: Int) =
            u16(tag) + u16(type) + u32(count) + u16(a) + u16(b)

        // IFD0: Make, Model, Exif-Zeiger, GPS-Zeiger
        val ifd0 = u16(4) +
            entry(0x010F, 2, makeRaw.size, makeOff) +
            entry(0x0110, 2, modelRaw.size, modelOff) +
            entry(0x8769, 4, 1, exifOff) +
            entry(0x8825, 4, 1, gpsOff) +
            u32(0)

        // Exif-IFD: Belichtung, Blende, ISO, Datum, KB-Brennweite, Objektiv
        val exifIfd = u16(exifCount) +
            entry(0x829A, 5, 1, expOff) +
            entry(0x829D, 5, 1, fnumOff) +
            entryInline(0x8827, 3, 1, 64, 0) +
            entry(0x9003, 2, dateRaw.size, dateOff) +
            entryInline(0xA405, 3, 1, 24, 0) +
            entry(0xA434, 2, lensRaw.size, lensOff) +
            u32(0)

        // GPS-IFD
        val gpsIfd = if (withGps) {
            u16(4) +
                entryInline(0x0001, 2, 2, (if (refLat == "N") 'N'.code else 'S'.code) shl 8, 0) +
                entry(0x0002, 5, 3, latOff) +
                entryInline(0x0003, 2, 2, (if (refLon == "E") 'E'.code else 'W'.code) shl 8, 0) +
                entry(0x0004, 5, 3, lonOff) +
                u32(0)
        } else {
            u16(0) + u32(0)
        }

        val tiff = byteArrayOf('M'.code.toByte(), 'M'.code.toByte()) + u16(42) + u32(ifd0Off) +
            ifd0 + exifIfd + gpsIfd +
            latRaw + lonRaw + makeRaw + modelRaw + lensRaw + dateRaw + expRaw + fnumRaw

        val exifPayload = u32(0) + tiff

        val ftyp = box("ftyp", "heic".toByteArray() + u32(0) + "mif1heic".toByteArray())
        val infe1 = box("infe", byteArrayOf(2, 0, 0, 0) + u16(1) + u16(0) + "hvc1".toByteArray() + "Bild".toByteArray() + byteArrayOf(0))
        val infe2 = box("infe", byteArrayOf(2, 0, 0, 0) + u16(2) + u16(0) + "Exif".toByteArray() + "EXIF".toByteArray() + byteArrayOf(0))
        val iinf = box("iinf", byteArrayOf(0, 0, 0, 0) + u16(2) + infe1 + infe2)

        fun iloc(exifAt: Int, exifLen: Int, imgAt: Int, imgLen: Int): ByteArray {
            var body = byteArrayOf(0, 0, 0, 0) + byteArrayOf(0x44, 0x00) + u16(2)
            body += u16(1) + u16(0) + u16(1) + u32(imgAt) + u32(imgLen)
            body += u16(2) + u16(0) + u16(1) + u32(exifAt) + u32(exifLen)
            return box("iloc", body)
        }

        val irot = box("irot", byteArrayOf(rotate.toByte()))
        val iprp = box("iprp", box("ipco", irot))

        val image = ByteArray(64)
        val probeMeta = box("meta", byteArrayOf(0, 0, 0, 0) + iinf + iloc(0, 0, 0, 0) + iprp)
        val imgOff = ftyp.size + probeMeta.size + 8
        val exifOff2 = imgOff + image.size
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + iinf + iloc(exifOff2, exifPayload.size, imgOff, image.size) + iprp)
        val mdat = box("mdat", image + exifPayload)
        return ftyp + meta + mdat
    }

    private fun read(bytes: ByteArray) = HeifGeo.read({ ByteArrayInputStream(bytes) }, bytes.size.toLong())

    // ---------------------------------------------------------------- Tests

    @Test
    fun gpsWirdAusDemContainerGelesen() {
        val geo = read(buildHeif())
        assertTrue("GPS muss gefunden werden", geo?.hasGps == true)
        assertEquals(47.0506388, geo!!.latitude!!, 0.0005)
        assertEquals(8.3066388, geo.longitude!!, 0.0005)
    }

    @Test
    fun kameraUndSensorWerdenGelesen() {
        val geo = read(buildHeif())!!
        assertEquals("Apple", geo.make)
        assertEquals("iPhone 15 Pro", geo.model)
        assertTrue(geo.lens!!.contains("back camera"))
        assertEquals(64, geo.iso)
        assertEquals(1.78, geo.fNumber!!, 0.001)
        assertEquals("1/120 s", geo.exposureTime)
        assertEquals(24, geo.focal35)
        assertEquals("2026:07:14 18:22:31", geo.taken)
    }

    @Test
    fun suedhalbkugelWirdNegativ() {
        val geo = read(
            buildHeif(
                latDms = Triple(33, 52, 7.8), lonDms = Triple(151, 12, 33.4),
                refLat = "S", refLon = "W"
            )
        )!!
        assertEquals(-33.86883, geo.latitude!!, 0.001)
        assertEquals(-151.2092, geo.longitude!!, 0.001)
    }

    @Test
    fun drehungAusIrot() {
        val geo = read(buildHeif(rotate = 3))!!
        assertEquals(270, geo.rotationDegrees)
        assertFalse(geo.mirrored)
    }

    @Test
    fun ohneGpsKeineErfundenePosition() {
        val geo = read(buildHeif(withGps = false))
        // Kamera ist da, aber keine Position – und es wird keine behauptet
        assertEquals("iPhone 15 Pro", geo?.model)
        assertNull(geo?.latitude)
        assertFalse(geo?.hasGps ?: false)
    }

    @Test
    fun kaputteDateienWerfenNicht() {
        assertNull(read(ByteArray(0)))
        assertNull(read("GIF89a".toByteArray()))
        assertNull(read(ByteArray(4096)))
        // abgeschnittene Datei: Anfang lesbar, Rest nicht
        val full = buildHeif()
        assertNull(read(full.copyOfRange(0, 40)))
    }

    @Test
    fun belichtungszeitSchreibweise() {
        assertEquals("1/120 s", HeifGeo.formatExposure(1.0 / 120))
        assertEquals("1/2000 s", HeifGeo.formatExposure(1.0 / 2000))
        assertEquals("2,5 s", HeifGeo.formatExposure(2.5))
        assertNull(HeifGeo.formatExposure(0.0))
        assertNull(HeifGeo.formatExposure(null))
    }

    @Test
    fun dmsRechnung() {
        assertEquals(47.5, HeifGeo.dms(listOf(47.0, 30.0, 0.0), "N")!!, 1e-9)
        assertEquals(-47.5, HeifGeo.dms(listOf(47.0, 30.0, 0.0), "S")!!, 1e-9)
        assertEquals(-8.25, HeifGeo.dms(listOf(8.0, 15.0, 0.0), "W")!!, 1e-9)
        assertNull(HeifGeo.dms(listOf(0.0, 0.0, 0.0), "N"))
        assertNull(HeifGeo.dms(emptyList<Double>(), "N"))
    }
}
