#!/bin/bash
# N3 Vibecode Gallery – APK auf einem Linux-Rechner ohne Android Studio bauen.
# Installiert (falls nötig) JDK 17, Android SDK 36 + Build-Tools und Gradle 8.13 in ~/.n3build
# und baut anschließend die Release-APK.  Aufruf:  bash tools/build-apk.sh
set -e
T="${N3_TOOLCHAIN_DIR:-$HOME/.n3build}"
mkdir -p "$T" && cd "$T"

if [ ! -x "$T/jdk17/bin/java" ]; then
  echo "→ JDK 17 laden"
  curl -sL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk17 && tar xzf jdk.tar.gz -C jdk17 --strip-components=1 && rm -f jdk.tar.gz
fi

if [ ! -d "$T/android-sdk/platforms/android-36" ]; then
  echo "→ Android SDK + Build-Tools laden"
  curl -sL -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p android-sdk/cmdline-tools && unzip -q -o cmdline.zip -d android-sdk/cmdline-tools
  rm -rf android-sdk/cmdline-tools/latest && mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest && rm -f cmdline.zip
  export ANDROID_HOME="$T/android-sdk"
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses > /dev/null 2>&1 || true
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null
fi

if [ ! -d "$T/gradle-8.13" ]; then
  echo "→ Gradle 8.13 laden"
  curl -sL -o g.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip && unzip -q -o g.zip -d "$T" && rm -f g.zip
fi

export JAVA_HOME="$T/jdk17"
export ANDROID_HOME="$T/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")/.."
echo "→ Baue Release-APK"
"$T/gradle-8.13/bin/gradle" assembleRelease
echo
echo "✅ APK: app/build/outputs/apk/release/app-release.apk"
