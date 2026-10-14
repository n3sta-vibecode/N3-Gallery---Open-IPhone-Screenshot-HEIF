#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
"${KOTLIN_HOME:+$KOTLIN_HOME/bin/}kotlinc" app/src/main/java/com/n3vibecode/key/TapEngine.kt tests/TapEngineTest.kt -include-runtime -d build/tests.jar
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar build/tests.jar
