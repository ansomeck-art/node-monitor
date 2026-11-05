#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
DOTNET="${DOTNET:-$PROJECT_ROOT/.tools/dotnet/dotnet}"
ANDROID_SDK="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export DOTNET_CLI_HOME="$PROJECT_ROOT/.cache/dotnet-home" NUGET_PACKAGES="$PROJECT_ROOT/.cache/nuget" DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 DOTNET_CLI_TELEMETRY_OPTOUT=1
"$DOTNET" run --project "$PROJECT_ROOT/Windows/NodeMonitor.Tests/NodeMonitor.Tests.csproj"
TEST_DIR="$PROJECT_ROOT/.cache/android-unit"
ANDROID_JAR="$ANDROID_SDK/platforms/android-36/android.jar"
ANDROID_JAVA="$PROJECT_ROOT/Android/app/src/main/java/cn/nodemon/app"
ANDROID_TEST="$PROJECT_ROOT/Android/tests/cn/nodemon/app"
mkdir -p "$TEST_DIR"
"$JAVA_HOME/bin/javac" -classpath "$ANDROID_JAR" -d "$TEST_DIR" "$ANDROID_JAVA/HostStore.java" "$ANDROID_JAVA/TailnetBridge.java" "$ANDROID_JAVA/MonitorClient.java" "$ANDROID_JAVA/MetricFreshness.java" "$ANDROID_JAVA/PollCoordinator.java" "$ANDROID_JAVA/AlertTracker.java" "$ANDROID_JAVA/MonitorSettings.java" "$ANDROID_TEST/MonitorClientTest.java" "$ANDROID_TEST/MetricFreshnessTest.java" "$ANDROID_TEST/AlertTrackerTest.java" "$ANDROID_TEST/PollCoordinatorTest.java"
"$JAVA_HOME/bin/java" -classpath "$TEST_DIR:$ANDROID_JAR" cn.nodemon.app.MonitorClientTest
"$JAVA_HOME/bin/java" -classpath "$TEST_DIR:$ANDROID_JAR" cn.nodemon.app.MetricFreshnessTest
"$JAVA_HOME/bin/java" -classpath "$TEST_DIR:$ANDROID_JAR" cn.nodemon.app.AlertTrackerTest

"$JAVA_HOME/bin/java" -classpath "$TEST_DIR:$ANDROID_JAR" cn.nodemon.app.PollCoordinatorTest
