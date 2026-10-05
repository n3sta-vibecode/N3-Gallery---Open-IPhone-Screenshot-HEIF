// N3 Vibecode Gallery - Root-Build
plugins {
    id("com.android.application") version "8.11.1" apply false
    // Kotlin 2.3 – nötig für die HEIF-Decoder-Bibliothek (avif-coder 2.2.1, Kotlin-2.3-Metadaten)
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
