import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optionaler Release-Keystore (keystore.properties im Projekt-Root).
// Fehlt die Datei, wird der Release-Build mit dem Debug-Key signiert -> trotzdem installierbar.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.n3vibecode.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.n3vibecode.gallery"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "1.28"
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
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // PKCS12 für den CI-Schlüssel (aus openssl), JKS als Standard
                val type = keystoreProps.getProperty("storeType")
                    ?: if (storeFile?.name?.endsWith(".p12") == true || storeFile?.name?.endsWith(".pfx") == true) "PKCS12" else "JKS"
                storeType = type
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        // Testbuild: gleiche Funktionen wie Release, aber mit eigenem App-Namen und
        // eigener Kennung (.dev) -> lässt sich NEBEN der normalen App installieren,
        // ohne dass installierte App oder Notizen/Favoriten ersetzt werden.
        create("tryout") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-test"
            // Derselbe feste Schlüssel wie Release: sonst ließe sich auch die Test-App
            // nicht über eine frühere Version installieren.
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            // Manche Bibliotheken (z. B. der HEIF-Decoder) liefern nur debug/release –
            // für den Testbuild auf die release-Varianten zurückfallen.
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

    // Eingebauter HEIF/AVIF-Decoder (libheif + libde265 + libdav1d).
    // Ohne ihn kann Android nur "einfache" HEICs öffnen – gekachelte Apple-Container
    // (Screenshots, 48-MP-Fotos), 10-Bit- und HDR-HEIFs scheitern am System-Decoder.
    implementation("io.github.awxkee:avif-coder:2.2.1")
}
