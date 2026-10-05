#!/bin/bash
set -e
T=/home/user/.cache
mkdir -p $T && cd $T
sudo fallocate -l 3G /swapfile 2>/dev/null && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null 2>&1 && sudo swapon /swapfile 2>/dev/null || true
echo "[1/4] JDK 17"
curl -sL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
mkdir -p jdk17 && tar xzf jdk.tar.gz -C jdk17 --strip-components=1 && rm -f jdk.tar.gz
$T/jdk17/bin/java -version 2>&1 | head -1
echo "[2/4] Android cmdline-tools"
curl -sL -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p android-sdk/cmdline-tools && unzip -q -o cmdline.zip -d android-sdk/cmdline-tools
rm -rf android-sdk/cmdline-tools/latest && mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest && rm -f cmdline.zip
echo "[3/4] Gradle 8.13"
curl -sL -o g.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip && unzip -q -o g.zip -d $T && rm -f g.zip
echo "[4/4] SDK-Pakete"
export ANDROID_HOME=$T/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses > /dev/null 2>&1 || true
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null 2>&1
ls $ANDROID_HOME/platforms $T | head -12
echo "=== BEREIT ==="
