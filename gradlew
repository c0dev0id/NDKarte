#!/bin/sh

#
# Gradle wrapper script for POSIX
#

set -e

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Determine Java command
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ]; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD="java"
    if ! command -v java >/dev/null 2>&1; then
        echo "ERROR: JAVA_HOME is not set and 'java' command not found in PATH." >&2
        exit 1
    fi
fi

# Download gradle-wrapper.jar if missing
if [ ! -f "$CLASSPATH" ]; then
    echo "Downloading Gradle wrapper..."
    mkdir -p "$APP_HOME/gradle/wrapper"
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$CLASSPATH" "$WRAPPER_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$CLASSPATH" "$WRAPPER_URL"
    else
        echo "ERROR: Neither curl nor wget found. Cannot download gradle-wrapper.jar" >&2
        exit 1
    fi
fi

exec "$JAVACMD" \
    -Dorg.gradle.appname="$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
