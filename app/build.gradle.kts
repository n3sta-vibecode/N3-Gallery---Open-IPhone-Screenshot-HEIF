import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Release-Signatur — GEHÄRTET.
//
// Die alte Fassung hatte zwei kritische Eigenschaften:
//   1. Fiel die Signatur weg, wurde STILLSCHWEIGEND mit dem Debug-Key signiert. Der
//      AOSP-Debug-Key ist öffentlich – jeder hätte damit ein von Android akzeptiertes
//      „Update" signieren können.
//   2. Der Signaturschlüssel (ci/n3-ci.p12) UND sein Passwort (keystore.properties)
//      lagen im öffentlichen Repository und wurden von der CI sogar zusätzlich als
//      Release-Artefakt veröffentlicht. Damit kann JEDER eine APK signieren, die auf
//      Geräten mit installierter N3 Gallery als legitimes Update angenommen wird.
//
// Jetzt: Release/Tryout werden NUR mit echtem Keystore gebaut, sonst bricht der Build ab.
// Der Keystore kommt aus Umgebungsvariablen (CI-Secrets) oder keystore.properties
// (nicht versioniert). Ein einmal veröffentlichter Schlüssel gilt als kompromittiert
// und muss ersetzt werden — siehe SIGNIEREN.md und security/SICHERHEITS-ANALYSE.md.
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    // Umgebungsvariablen gewinnen – so nutzt die CI Secrets ohne Datei im Workspace.
    System.getenv("N3_STORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("N3_STORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("N3_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("N3_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
    System.getenv("N3_STORE_TYPE")?.let { setProperty("storeType", it) }
}

fun prop(name: String): String? = keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }

val storeFilePath = prop("storeFile")
// Base64-kodierter Keystore aus einem CI-Secret (empfohlener Weg).
val embeddedStore = System.getenv("N3_KEYSTORE_BASE64")
val resolvedStoreFile: java.io.File? = when {
    storeFilePath != null && rootProject.file(storeFilePath).exists() ->
        rootProject.file(storeFilePath)

    !embeddedStore.isNullOrBlank() -> {
        val out = File(layout.buildDirectory.get().asFile, "ci-keystore.bin")
        out.parentFile?.mkdirs()
        out.writeBytes(java.util.Base64.getDecoder().decode(embeddedStore.trim()))
        out
    }

    else -> null
}

val hasKeystore = resolvedStoreFile != null &&
        prop("storePassword") != null &&
        prop("keyAlias") != null &&
        prop("keyPassword") != null

android {
    namespace = "com.n3vibecode.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.n3vibecode.gallery"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "1.32-hardened"
        resourceConfigurations += listOf("de", "en")
        vectorDrawables.useSupportLibrary = true

        // HEIF-Dekoder (libheif/libde265/libdav1d/…) wird als natives Bibliothekspaket mitgeliefert.
        // Phones: arm64-v8a + armeabi-v7a · Emulator/Chromebook: x86_64
        ndk {
            // Handy-CPUs: arm64 (alle modernen Geräte) + armeabi-v7a (ältere 32-Bit-Geräte).
            // Für den Emulator einfach "x86_64" ergänzen.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = resolvedStoreFile
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
                // PKCS12 für openssl-erzeugte Schlüssel, sonst JKS
                storeType = prop("storeType")
                    ?: if (resolvedStoreFile?.name?.endsWith(".p12") == true ||
                        resolvedStoreFile?.name?.endsWith(".pfx") == true) "PKCS12" else "JKS"
                // V2+V3 erzwingen: V1 (JAR) allein wäre für Janus-artige Manipulationen
                // anfällig (CVE-2017-13156).
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // Gemeinsame Fehlermeldung, wenn keine Signatur konfiguriert ist.
    val missingKeyError = provider {
        GradleException(
            """
            |
            |Release-Signatur fehlt – Build abgebrochen.
            |
            |Es wird bewusst NICHT mehr auf den Debug-Key ausgewichen: Der AOSP-Debug-Key
            |ist öffentlich, eine damit signierte Release-APK könnte von jedem als Update
            |nachgebaut werden. Dasselbe gilt für den früher eingecheckten CI-Schlüssel.
            |
            |Abhilfe (eine der beiden Varianten):
            |  1) Lokal: keystore.properties im Projekt-Root anlegen
            |     (Vorlage: keystore.properties.template). Über .gitignore ausgeschlossen.
            |  2) CI: Secrets N3_KEYSTORE_BASE64, N3_STORE_PASSWORD, N3_KEY_ALIAS,
            |     N3_KEY_PASSWORD (und optional N3_STORE_TYPE) setzen.
            |
            |Neuen, NICHT veröffentlichten Keystore erzeugen und sicher ablegen:
            |  keytool -genkeypair -v -keystore n3-release.jks -alias n3gallery \
            |    -keyalg RSA -keysize 4096 -validity 10950
            |
            """.trimMargin()
        )
    }

    buildTypes {
        release {
            // GEHÄRTET: Minify + Resource-Shrinking waren aus. Ohne sie ist die App mit
            // jedem Dekompilierer im Klartext lesbar.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKeystore) signingConfigs.getByName("release")
                            else throw missingKeyError.get()
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isMinifyEnabled = false
        }
        // Testbuild: gleiche Funktionen wie Release, aber mit eigener Kennung (.dev) ->
        // lässt sich NEBEN der normalen App installieren.
        create("tryout") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-test"
            // Eigener Signaturschlüssel wie Release; kein Debug-Fallback.
            signingConfig = if (hasKeystore) signingConfigs.getByName("release")
                            else throw missingKeyError.get()
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // SVG-Renderer (Apache 2.0). Android kann SVG nicht von sich aus anzeigen –
    // damit werden SVG/SVGZ-Dateien in der gewünschten Größe gerendert (verlustfrei,
    // weil Vektorgrafik).
    implementation("com.caverock:androidsvg-aar:1.4")

    // Eingebauter HEIF/AVIF-Decoder (libheif + libde265 + libdav1d).
    // Ohne ihn kann Android nur "einfache" HEICs öffnen – gekachelte Apple-Container
    // (Screenshots, 48-MP-Fotos), 10-Bit- und HDR-HEIFs scheitern am System-Decoder.
    implementation("io.github.awxkee:avif-coder:2.2.1")
}
