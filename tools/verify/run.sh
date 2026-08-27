#!/usr/bin/env bash
#
# Compiles and runs the audio-engine tests without Gradle, using the Kotlin
# compiler bundled with Android Studio.
#
# Why this exists: the real Gradle build needs the Android Gradle Plugin and
# AndroidX from Google's Maven repo (dl.google.com). On networks that block it,
# this script still verifies the entire DSP layer, the music model and the
# Android-native audio classes - which is where the risk actually lives. The
# Compose UI, DataStore and MediaSession code still needs a full Gradle build,
# which the GitHub Actions workflow does.
#
# Usage:
#   bash tools/verify/run.sh            # compile + run the test suite
#   bash tools/verify/run.sh --report   # print measured tuning error instead
#
set -euo pipefail
cd "$(dirname "$0")/../.."

# Two forms of the same paths: MSYS-style to launch a binary from bash,
# Windows-style for anything that ends up inside a -cp argument.
to_win() { echo "$1" | sed 's|^/\([a-zA-Z]\)/|\1:/|'; }

STUDIO_WIN="${ANDROID_STUDIO_HOME:-C:/Program Files/Android/Android Studio}"
STUDIO_SH="/$(echo "$STUDIO_WIN" | sed 's|:||')"
KLIB="$STUDIO_WIN/plugins/Kotlin/kotlinc/lib"
JAVA="$STUDIO_SH/jbr/bin/java.exe"
[ -x "$JAVA" ] || JAVA="java"

if [ ! -f "$KLIB/kotlin-compiler.jar" ]; then
  echo "Could not find Android Studio's Kotlin compiler at:"
  echo "  $KLIB/kotlin-compiler.jar"
  echo "Set ANDROID_STUDIO_HOME to your Android Studio install (Windows-style path)."
  exit 1
fi

L=tools/verify/libs
CP="$L/junit-4.13.2.jar;$L/hamcrest-core-1.3.jar;$L/kotlinx-serialization-core-jvm-1.6.3.jar;$L/kotlinx-serialization-json-jvm-1.6.3.jar"
S=app/src/main/java/com/riyaaz/tanpura
T=app/src/test/java/com/riyaaz/tanpura

rm -rf tools/verify/out tools/verify/out-android

# ------------------------------------------------------------------
# Pass 1: the pure-Kotlin engine, the music model and their tests.
# ------------------------------------------------------------------
echo "== compiling engine + tests =="
"$JAVA" -Xmx2g -cp "$KLIB/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -jvm-target 17 -Xplugin="$KLIB/kotlinx-serialization-compiler-plugin.jar" \
  -cp "$CP" -d tools/verify/out \
  "$S/audio/Dsp.kt" "$S/audio/StringVoice.kt" "$S/audio/Reverb.kt" "$S/audio/StrumSequencer.kt" \
  "$S/audio/SampleVoice.kt" "$S/audio/LoopSource.kt" "$S/audio/RefTone.kt" \
  "$S/audio/PitchDetector.kt" "$S/audio/PcmRing.kt" "$S/audio/TanpuraEngine.kt" \
  "$S/model/Pitch.kt" "$S/model/StringPattern.kt" "$S/model/TanpuraVoice.kt" \
  "$S/model/TanpuraSettings.kt" \
  "$T/StringVoiceTest.kt" "$T/EngineTest.kt" "$T/ModelTest.kt" \
  tools/verify/TuningReport.kt

RUN_CP="tools/verify/out;$KLIB/kotlin-stdlib.jar;$CP"

if [ "${1:-}" = "--report" ]; then
  echo "== tuning report =="
  "$JAVA" -cp "$RUN_CP" com.riyaaz.tanpura.verify.TuningReport
  exit 0
fi

# ------------------------------------------------------------------
# Pass 2: type-check the Android-native layer against android.jar. These files
# import android.* but no AndroidX, so they compile here without Google Maven.
# ------------------------------------------------------------------
HOME_WIN="$(to_win "$HOME")"
SDK_WIN="${ANDROID_SDK_WIN:-$HOME_WIN/AppData/Local/Android/Sdk}"
ANDROID_JAR="$SDK_WIN/platforms/android-35/android.jar"
ANDROID_JAR_SH="/$(echo "$ANDROID_JAR" | sed 's|:||')"

if [ -f "$ANDROID_JAR_SH" ]; then
  echo "== type-checking the android layer =="
  "$JAVA" -Xmx2g -cp "$KLIB/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -nowarn -jvm-target 17 \
    -cp "$ANDROID_JAR;tools/verify/out;$L/kotlinx-coroutines-core-jvm-1.9.0.jar;$CP" \
    -d tools/verify/out-android \
    "$S/audio/AudioOutput.kt" "$S/audio/AudioDecoder.kt" "$S/audio/MediaLoopSource.kt" \
    "$S/playback/PlaybackController.kt" \
    tools/verify/stubs/SettingsStoreStub.kt
else
  echo "!! android.jar not found at $ANDROID_JAR - skipping the android type-check"
fi

# ------------------------------------------------------------------
# Pass 3: run the tests.
# ------------------------------------------------------------------
echo "== running tests =="
"$JAVA" -cp "$RUN_CP" org.junit.runner.JUnitCore \
  com.riyaaz.tanpura.PitchTest \
  com.riyaaz.tanpura.PatternTest \
  com.riyaaz.tanpura.VoiceTest \
  com.riyaaz.tanpura.SettingsSerializationTest \
  com.riyaaz.tanpura.PitchDetectorTest \
  com.riyaaz.tanpura.PcmRingTest \
  com.riyaaz.tanpura.StrumSequencerTest \
  com.riyaaz.tanpura.StringVoiceTest \
  com.riyaaz.tanpura.EngineTest
