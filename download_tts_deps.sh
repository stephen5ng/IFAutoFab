#!/bin/bash
set -e

# Versions
SHERPA_VERSION="1.10.30"

# Base URLs
BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"

mkdir -p terminal/libs
mkdir -p terminal/models

echo "Downloading Sherpa-ONNX JARs..."

# Download Java API jar (Java 21 to match jvmToolchain)
if [ ! -f terminal/libs/sherpa-onnx-java.jar ]; then
    curl -L -o terminal/libs/sherpa-onnx-java.jar "${BASE_URL}/sherpa-onnx-v${SHERPA_VERSION}-java21.jar" || echo "Failed to download java jar"
fi

# Download macOS ARM64 JNI native lib (ships as a tarball, extract the JAR)
if [ ! -f terminal/libs/sherpa-onnx-macos-arm64.jar ]; then
    curl -L -o terminal/libs/osx-arm64-jni.tar.bz2 "${BASE_URL}/sherpa-onnx-v${SHERPA_VERSION}-osx-arm64-jni.tar.bz2" || echo "Failed to download native jni tarball"
    tar -xjf terminal/libs/osx-arm64-jni.tar.bz2 -C terminal/libs/
    # Move the extracted JAR (if present) or shared lib to expected location
    find terminal/libs/sherpa-onnx-v${SHERPA_VERSION}-osx-arm64-jni -name "*.jar" -exec cp {} terminal/libs/sherpa-onnx-macos-arm64.jar \; 2>/dev/null
    # If no JAR, package the dylib directory as-is for JNI loading
    if [ ! -f terminal/libs/sherpa-onnx-macos-arm64.jar ]; then
        cp -r terminal/libs/sherpa-onnx-v${SHERPA_VERSION}-osx-arm64-jni terminal/libs/sherpa-onnx-macos-arm64
    fi
    rm -rf terminal/libs/osx-arm64-jni.tar.bz2 terminal/libs/sherpa-onnx-v${SHERPA_VERSION}-osx-arm64-jni
fi


echo "Downloading Kokoro Model..."
if [ ! -d terminal/models/kokoro-en-v0_19 ]; then
    curl -L -o terminal/models/model.tar.bz2 "$MODEL_URL"
    tar -xjf terminal/models/model.tar.bz2 -C terminal/models/
    rm terminal/models/model.tar.bz2
fi

echo "Done."
