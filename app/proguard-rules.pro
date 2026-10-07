# =============================================================================
#  N3 Vibecode Gallery – ProGuard/R8-Regeln (Release)
#
#  GEHÄRTET. Die alte Fassung enthielt:
#
#      -keep class com.n3vibecode.gallery.** { *; }
#
#  Damit blieb der gesamte App-Code unverkleinert und unverschleiert –
#  isMinifyEnabled=true hätte nichts bewirkt, und jeder Dekompilierer liefert
#  Klartext-Klassennamen wie UriGuard, SafeFiles, MetaStore, MediaSaver. Genau das
#  erleichtert einem Angreifer das Auffinden von Schwachstellen.
#
#  Jetzt wird nur noch behalten, was technisch behalten werden MUSS.
# =============================================================================

-dontwarn org.jetbrains.annotations.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ------------------------------------------------- Android-Komponenten
# Werden über das Manifest per Klassenname instanziiert.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.appcompat.app.AppCompatActivity

# Custom Views werden in Layout-XML per vollqualifiziertem Namen referenziert
# (ZoomImageView, MosaicView, PhotoEditorView, SquareFrameLayout, ZoomGridRecyclerView).
-keep public class com.n3vibecode.gallery.widget.** {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
    *** get*();
}

-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
    protected void onCreate(android.os.Bundle);
    protected void onNewIntent(android.content.Intent);
}

# ---------------------------------------------------------------- Kotlin
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ------------------------------------------------- HEIF/AVIF-Decoder (JNI)
# io.github.awxkee:avif-coder ruft aus nativen Bibliotheken (libcoder.so, libheif.so)
# zurück in Java-Code. Diese Klassen dürfen weder entfernt noch umbenannt werden.
-keep class com.radzivon.bartoshyk.avif.coder.** { *; }
-keepclassmembers class com.radzivon.bartoshyk.avif.coder.** {
    native <methods>;
    <init>(...);
}
-dontwarn com.radzivon.bartoshyk.avif.coder.**

# ------------------------------------------------- SVG-Renderer (androidsvg)
# com.caverock:androidsvg nutzt Reflection für einige Android-Klassen.
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# ---------------------------------------------------------------- AndroidX
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.appcompat.app.AppLocalesMetadataHolderService { *; }
-keep class androidx.startup.** { *; }
-keep class androidx.profileinstaller.** { *; }
-dontwarn androidx.**

# ------------------------------------------------- Enum-Werte (MediaKind etc.)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------- Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# =============================================================================
#  Nach jeder Änderung: assembleRelease bauen und durchklicken – Grid, Detail,
#  HEIC/AVIF, SVG, Editor (PhotoEditorView), Notizen, Teilen, Löschen, Speichern,
#  Metadatenseite, „Ordner hinzufügen" (SAF). R8-Regelfehler zeigen sich als
#  ClassNotFoundException zur Laufzeit, nicht beim Bau.
# =============================================================================
