#!/usr/bin/env bash
# Build with an existing toolchain, without Gradle or network access.
# The normal supported developer build is ./gradlew :app:assemblePreview.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Point to Java 17+}"
: "${KOTLIN_HOME:?Point to Kotlin compiler 2.1.20}"
: "${ANDROID_JAR:?Point to Android API 35 android.jar}"
: "${AAPT2:?Point to aapt2 executable}"
: "${R8_JAR:?Point to R8 jar}"
: "${APKSIGNER_JAR:?Point to apksigner jar}"
: "${KEYSTORE:?Point to your signing keystore (outside this repository)}"
: "${STORE_PASS:?Set signing password in the environment}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
mkdir -p build/standalone/dex
JAVA="$JAVA_HOME/bin/java"
"$KOTLIN_HOME/bin/kotlinc" app/src/main/java/com/n3vibecode/key/TapEngine.kt tests/TapEngineTest.kt \
  -include-runtime -d build/standalone/tests.jar
"$JAVA" -jar build/standalone/tests.jar
"$KOTLIN_HOME/bin/kotlinc" -jvm-target 1.8 -Xlambdas=class \
  -classpath "$ANDROID_JAR" app/src/main/java/com/n3vibecode/key/*.kt -d build/standalone/classes.jar
"$AAPT2" compile --dir app/src/main/res -o build/standalone/resources.zip
python3 - <<'PYMANIFEST'
from pathlib import Path
manifest = Path('app/src/main/AndroidManifest.xml').read_text()
Path('build/standalone/AndroidManifest.xml').write_text(manifest.replace('<manifest ', '<manifest package="com.n3vibecode.key" ', 1))
PYMANIFEST
"$AAPT2" link -o build/standalone/resources.apk -I "$ANDROID_JAR" \
  --manifest build/standalone/AndroidManifest.xml --min-sdk-version 28 --target-sdk-version 35 \
  build/standalone/resources.zip
"$JAVA" -Xmx1024m -cp "$R8_JAR" com.android.tools.r8.R8 --release --min-api 28 \
  --lib "$ANDROID_JAR" --pg-conf tools/standalone-r8.pro --output build/standalone/dex \
  build/standalone/classes.jar "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "$KOTLIN_HOME/lib/annotations-13.0.jar"
# Keep the resource table uncompressed and align stored ZIP entries to 4 bytes.
# No native libraries are included; DEX is compressed.
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
import struct
folder = Path('build/standalone')
with ZipFile(folder / 'resources.apk') as source, ZipFile(folder / 'unsigned.apk', 'w') as target:
    for entry in source.infolist():
        if entry.filename == 'resources.arsc':
            entry.compress_type = ZIP_STORED
        if entry.compress_type == ZIP_STORED:
            offset = target.fp.tell() + 30 + len(entry.filename.encode()) + len(entry.extra)
            pad = (-offset) % 4
            if pad:
                entry.extra += struct.pack('<HH', 0xffff, pad) + bytes(pad)
        target.writestr(entry, source.read(entry.filename))
    for dex in sorted((folder / 'dex').glob('classes*.dex')):
        target.write(dex, dex.name, compress_type=ZIP_DEFLATED)
    target.write('LICENSE-KOTLIN.txt', 'assets/LICENSE-KOTLIN.txt', compress_type=ZIP_DEFLATED)
    target.write('NOTICE', 'assets/NOTICE.txt', compress_type=ZIP_DEFLATED)
PY
"$JAVA" -jar "$APKSIGNER_JAR" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:STORE_PASS --key-pass env:STORE_PASS --min-sdk-version 28 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out build/standalone/N3-Key-1.0-by-n3-Vibecode.apk build/standalone/unsigned.apk
"$JAVA" -jar "$APKSIGNER_JAR" verify --verbose --print-certs build/standalone/N3-Key-1.0-by-n3-Vibecode.apk
sha256sum build/standalone/N3-Key-1.0-by-n3-Vibecode.apk
