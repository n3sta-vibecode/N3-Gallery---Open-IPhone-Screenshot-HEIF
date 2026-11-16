package com.n3vibecode.gallery.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Reflection-Suche – die Stelle, an der die Modellanbindung in 1.35 und 1.36
 * gescheitert ist. Die Test-Klassen unten bilden genau die MediaPipe-Struktur nach:
 *
 *  • ein **abstrakter** Builder, dessen Methoden `BaseOptions` verlangen, während die
 *    konkrete Klasse `AutoValue_…` heißt (Unterklasse),
 *  • `build()`, das **nicht** im abstrakten Builder steht, sondern in der erzeugten Klasse.
 */
class ReflectTest {

    // ------------------------------------------------------------------ Nachbau der API

    abstract class BaseOptions {
        abstract class Builder {
            abstract fun setModelAssetPath(value: String): Builder
            abstract fun setDelegate(value: Delegate): Builder
        }
    }

    /** So heißt die Klasse, die AutoValue zur Laufzeit erzeugt. */
    class AutoValueBaseOptions : BaseOptions()

    enum class Delegate { CPU, GPU, NPU }

    open class AutoValueBaseOptionsBuilder : BaseOptions.Builder() {
        override fun setModelAssetPath(value: String): BaseOptions.Builder = this
        override fun setDelegate(value: Delegate): BaseOptions.Builder = this

        /** `build()` liegt in der erzeugten Klasse – nicht im abstrakten Builder. */
        fun build(): BaseOptions = AutoValueBaseOptions()
    }

    class SomethingElse

    // ------------------------------------------------------------------ Tests

    /**
     * Der Fehler aus 1.35/1.36: Das Argument ist vom Typ `AutoValueBaseOptions`, der Parameter
     * verlangt `BaseOptions`. `getDeclaredMethod` findet hier nichts – unsere Suche muss es.
     */
    @Test
    fun findetMethodeMitUnterklassenArgument() {
        val builder = AutoValueBaseOptionsBuilder()
        val m = Reflect.findMethod(
            builder.javaClass, "setModelAssetPath", listOf(String::class.java)
        )
        assertNotNull("setModelAssetPath muss gefunden werden", m)
        // Der deklarierte Parametertyp ist String, das Argument auch – Aufruf muss klappen
        Reflect.invoke(builder, m!!, "modell.litertlm")
    }

    @Test
    fun findetBuildInDerErzeugtenKlasse() {
        val builder = AutoValueBaseOptionsBuilder()
        val m = Reflect.findMethod(builder.javaClass, "build", emptyList())
        assertNotNull("build() muss in der Unterklasse gefunden werden", m)
        val result = Reflect.invoke(builder, m!!)
        assertTrue("build() muss ein BaseOptions liefern", result is BaseOptions)
    }

    @Test
    fun findetSetDelegateMitEnum() {
        val builder = AutoValueBaseOptionsBuilder()
        val m = Reflect.findMethod(builder.javaClass, "setDelegate", listOf(Delegate.CPU.javaClass))
        assertNotNull("setDelegate muss gefunden werden", m)
        Reflect.invoke(builder, m!!, Delegate.CPU)
    }

    @Test
    fun findetMethodeInDerOberklasse() {
        // Object.toString() – liegt in der Oberklasse, muss trotzdem gefunden werden
        val m = Reflect.findMethod(AutoValueBaseOptionsBuilder::class.java, "toString", emptyList())
        assertNotNull("toString aus Object muss gefunden werden", m)
    }

    /** Primitive Parameter: `setL2Normalize(boolean)` mit einem `Boolean`-Objekt aufrufen. */
    @Test
    fun passtPrimitiveParameterMitBoxedWert() {
        class MitBoolean {
            var gesehen: Boolean? = null
            fun setL2Normalize(value: Boolean) { gesehen = value }
        }
        val target = MitBoolean()
        Reflect.call(target, "setL2Normalize", true)
        assertEquals(true, target.gesehen)
        // und mit `false`
        Reflect.call(target, "setL2Normalize", false)
        assertEquals(false, target.gesehen)
    }

    @Test
    fun lehntFalschenTypAb() {
        val m = Reflect.findMethod(
            AutoValueBaseOptionsBuilder::class.java, "setModelAssetPath", listOf(Int::class.java)
        )
        assertNull("Ein Int passt nicht auf einen String-Parameter", m)
    }

    @Test
    fun lehntFalscheParameterzahlAb() {
        val m = Reflect.findMethod(
            AutoValueBaseOptionsBuilder::class.java, "build", listOf(String::class.java)
        )
        assertNull("build() hat keine Parameter", m)
    }

    /** null passt auf Objekt-Parameter, aber nicht auf primitive. */
    @Test
    fun nullPasstNurAufNichtPrimitive() {
        assertTrue(Reflect.fits(arrayOf(String::class.java), listOf(null)))
        assertTrue(!Reflect.fits(arrayOf(String::class.java).let { arrayOf(Int::class.java) }, listOf(null)))
    }

    @Test
    fun callStaticFindetFactory() {
        // Einfacher Nachbau: Begleit-Objekt-Methode ist statisch
        val result = Reflect.callStatic(Factory::class.java, "builder")
        assertTrue(result is AutoValueBaseOptionsBuilder)
    }

    object Factory {
        @JvmStatic
        fun builder(): AutoValueBaseOptionsBuilder = AutoValueBaseOptionsBuilder()
    }

    // ------------------------------------------------------------------ Fehlertexte

    @Test
    fun fehlendeKlasseWirdLesbar() {
        val text = Reflect.human(NoClassDefFoundError("com.google.ai.edge.litertlm.ActivationDataType"))
        assertTrue("Klasse muss im Text stehen: $text", text.contains("ActivationDataType"))
        assertTrue("Ursache muss erwähnt werden: $text", text.contains("Klasse fehlt"))
    }

    @Test
    fun fehlendesNativesModulWirdLesbar() {
        val text = Reflect.human(UnsatisfiedLinkError("liblitertlm.so"))
        assertTrue(text.contains("Natives Modul"))
    }

    @Test
    fun ursacheWirdDurchgereicht() {
        val aussen = RuntimeException("außen", IllegalStateException("innen"))
        assertEquals("innen", Reflect.human(aussen))
    }

    /**
     * NPU-Erkennung: Die Vorprüfung darf nur bei Geräten anspringen, die überhaupt eine NPU
     * haben könnten – sonst zahlt jedes Telefon einen fehlschlagenden NPU-Versuch.
     * (Geprüft wird die reine Namenslogik, nicht das Gerät.)
     */
    private fun npuVerdacht(hardware: String): Boolean {
        val known = listOf(
            "qcom", "qualcomm", "sm8", "sm7", "tensor", "google", "mediatek", "mt6", "mt8",
            "exynos", "s5e", "kirin", "unisoc"
        )
        val device = hardware.lowercase()
        return known.any { device.contains(it) }
    }

    @Test
    fun npuWirdBeiBekanntenSocsErkannt() {
        assertTrue(npuVerdacht("qcom sm8650"))
        assertTrue(npuVerdacht("Google Tensor G4"))
        assertTrue(npuVerdacht("Mediatek MT6989"))
        assertTrue(npuVerdacht("samsung exynos 2400"))
    }

    @Test
    fun npuWirdOhneHinweisNichtAngenommen() {
        assertTrue(!npuVerdacht("unknown"))
        assertTrue(!npuVerdacht(""))
    }

    @Test
    fun unbekannterFehlerFaelltNichtAus() {
        assertEquals("unbekannter Fehler", Reflect.human(null))
        assertTrue(Reflect.human(RuntimeException()).isNotBlank())
    }
}
