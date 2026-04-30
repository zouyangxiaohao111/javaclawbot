#!/bin/bash
set -e

# ===========================================================
# JavaClawBot macOS DMG Packaging Tool
# ===========================================================

echo ""
echo "========================================================="
echo "  JavaClawBot macOS DMG Packaging Tool"
echo "========================================================="
echo ""

# ===========================================================
# Configuration
# ===========================================================

APP_NAME="JavaClawBot"
APP_VERSION="2.1.0"
VENDOR="JavaClawBot"
DESCRIPTION="JavaClawBot - AI Assistant"
PACKAGE_ID="com.zjky.javaclawbot"

MAIN_JAR_NAME="javaclawbot.jar"
MAIN_CLASS="gui.ui.Launcher"

# Paths — adjust to your environment
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ICON_DIR="${PROJECT_DIR}/src/main/resources/icon"
ICON_PATH="${ICON_DIR}/app-icon.icns"

# Build output (relative to project dir, or set via env)
JAR_FILE="${JAR_FILE:-${PROJECT_DIR}/target/${MAIN_JAR_NAME}}"
OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_DIR}/target/dist}"
RUNTIME_DIR="${RUNTIME_DIR:-${PROJECT_DIR}/target/jre-mac}"
TEMP_INPUT_DIR="${TEMP_INPUT_DIR:-${PROJECT_DIR}/target/temp_package}"

# Java runtime options
JAVA_OPTS=(
    "-Dfile.encoding=UTF-8"
    "-Dprism.order=es2"
    "-Xmx512m"
)

# jlink modules for macOS
MODULES="java.se,java.net.http,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.management,jdk.management.agent,jdk.jdwp.agent,jdk.jfr,jdk.jshell,jdk.httpserver,jdk.accessibility,jdk.attach,jdk.compiler,jdk.jartool,jdk.jdi,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.security.auth,jdk.security.jgss,jdk.xml.dom"

# ===========================================================
# Validation
# ===========================================================

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null)}"

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "[ERROR] Java 17 not found. Set JAVA_HOME or install JDK 17."
    exit 1
fi

echo "[INFO] JAVA_HOME: $JAVA_HOME"
echo "[INFO] APP_NAME: $APP_NAME"
echo "[INFO] APP_VERSION: $APP_VERSION"
echo "[INFO] PROJECT_DIR: $PROJECT_DIR"
echo "[INFO] JAR_FILE: $JAR_FILE"
echo "[INFO] ICON_PATH: $ICON_PATH"
echo ""

for cmd in java jlink jpackage; do
    if [ ! -x "${JAVA_HOME}/bin/${cmd}" ]; then
        echo "[ERROR] ${cmd} not found at ${JAVA_HOME}/bin/${cmd}"
        exit 1
    fi
done

if [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] JAR file not found: $JAR_FILE"
    echo "[HINT] Run 'mvn package -DskipTests' first."
    exit 1
fi

if [ ! -f "$ICON_PATH" ]; then
    echo "[ERROR] ICNS icon not found: $ICON_PATH"
    echo "[HINT] Run icon generation script first."
    exit 1
fi

# ===========================================================
# Cleanup old output
# ===========================================================

echo "[STEP] Cleaning old output..."
rm -rf "$OUTPUT_DIR" "$RUNTIME_DIR" "$TEMP_INPUT_DIR"
mkdir -p "$OUTPUT_DIR" "$TEMP_INPUT_DIR"
echo "[OK] Clean."
echo ""

# ===========================================================
# Build custom runtime (jlink)
# ===========================================================

echo "[STEP] Building custom JRE with jlink..."
"${JAVA_HOME}/bin/jlink" \
    --add-modules "$MODULES" \
    --output "$RUNTIME_DIR" \
    --strip-debug \
    --compress 2 \
    --no-header-files \
    --no-man-pages
echo "[OK] Runtime created: $RUNTIME_DIR"
echo ""

# ===========================================================
# Copy JAR to temp input
# ===========================================================

echo "[STEP] Preparing input..."
cp "$JAR_FILE" "$TEMP_INPUT_DIR/"
# Copy lib dir if exists
if [ -d "${PROJECT_DIR}/target/lib" ]; then
    cp -R "${PROJECT_DIR}/target/lib" "$TEMP_INPUT_DIR/"
fi
echo "[OK] Input ready:"
ls -la "$TEMP_INPUT_DIR"
echo ""

# ===========================================================
# Build java options string
# ===========================================================

JAVA_OPT_ARGS=()
for opt in "${JAVA_OPTS[@]}"; do
    JAVA_OPT_ARGS+=(--java-options "$opt")
done

# ===========================================================
# Run jpackage → DMG
# ===========================================================

echo "[STEP] Running jpackage to create DMG..."
"${JAVA_HOME}/bin/jpackage" \
    --type dmg \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "$VENDOR" \
    --description "$DESCRIPTION" \
    --input "$TEMP_INPUT_DIR" \
    --main-jar "$MAIN_JAR_NAME" \
    --main-class "$MAIN_CLASS" \
    --runtime-image "$RUNTIME_DIR" \
    --dest "$OUTPUT_DIR" \
    --icon "$ICON_PATH" \
    --mac-package-identifier "$PACKAGE_ID" \
    --mac-package-name "$APP_NAME" \
    "${JAVA_OPT_ARGS[@]}" \
    --verbose

echo ""
echo "[OK] DMG created successfully!"
echo ""

# ===========================================================
# Cleanup temp
# ===========================================================

echo "[STEP] Cleaning temp files..."
rm -rf "$TEMP_INPUT_DIR"
echo "[OK] Done."
echo ""

echo "========================================================="
echo " Output:"
echo "========================================================="
ls -lh "$OUTPUT_DIR"
echo ""
echo "DMG file: $OUTPUT_DIR/${APP_NAME}-${APP_VERSION}.dmg"
echo ""
