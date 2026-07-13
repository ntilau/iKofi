#!/bin/bash
# iKofi dependency installer
# Installs JDK 21, Android SDK, NDK, and CMake for building.
# Tested on Debian, Fedora, and macOS (Homebrew).

set -euo pipefail

echo "==> iKofi dependency setup"
echo ""

OS="$(uname -s)"

# ── Prerequisites ──────────────────────────────────────────────────────────

install_system_packages() {
    case "$OS" in
        Linux)
            if command -v apt-get &>/dev/null; then
                sudo apt-get update
                sudo apt-get install -y openjdk-21-jdk-headless wget unzip git gawk
            elif command -v dnf &>/dev/null; then
                sudo dnf install -y java-21-openjdk-devel wget unzip git gawk
            else
                echo "Unsupported Linux package manager. Install JDK 21, wget, unzip, git, gawk manually."
                exit 1
            fi
            ;;
        Darwin)
            if ! command -v brew &>/dev/null; then
                echo "Homebrew is required. Install from https://brew.sh"
                exit 1
            fi
            brew install openjdk@21 wget
            ;;
        *)
            echo "Unsupported OS: $OS"
            exit 1
            ;;
    esac
}

# ── Android SDK ────────────────────────────────────────────────────────────

install_android_sdk() {
    if [ -z "${ANDROID_HOME:-}" ]; then
        ANDROID_HOME="$HOME/android-sdk"
        echo "ANDROID_HOME not set, defaulting to $ANDROID_HOME"
    fi

    mkdir -p "$ANDROID_HOME"

    # Install cmdline-tools if not present
    if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        echo "Downloading Android command-line tools..."
        cd "$ANDROID_HOME/cmdline-tools"
        # Check latest version at https://developer.android.com/studio#command-line-tools-only
        URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
        wget -q "$URL" -O tools.zip
        unzip -q tools.zip
        rm tools.zip
        mv cmdline-tools latest
        cd ~
    fi

    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

    # Accept licenses
    yes | sdkmanager --licenses >/dev/null 2>&1 || true

    # Install required SDK components
    echo "Installing Android SDK components..."
    sdkmanager \
        "platforms;android-36" \
        "build-tools;36.0.0" \
        "ndk;30.0.14904198" \
        "cmake;4.1.2"
}

# ── local.properties ───────────────────────────────────────────────────────

setup_local_properties() {
    if [ ! -f local.properties ]; then
        echo "sdk.dir=$ANDROID_HOME" > local.properties
        echo "Created local.properties"
    fi
}

# ── Prebuild steps ─────────────────────────────────────────────────────────

run_prebuild() {
    echo "Running prebuild scripts..."
    chmod +x app/src/main/cpp/prebuild-dropbear.sh
    chmod +x app/src/main/cpp/prebuild-rsyn.sh
    ./app/src/main/cpp/prebuild-dropbear.sh
    ./app/src/main/cpp/prebuild-rsyn.sh
}

# ── Main ───────────────────────────────────────────────────────────────────

install_system_packages
install_android_sdk
setup_local_properties
run_prebuild

echo ""
echo "==> Setup complete!"
echo "    ANDROID_HOME=$ANDROID_HOME"
echo ""
echo "    Build with:  ./gradlew assembleDebug"
echo "    Install:     adb install -r app/build/outputs/apk/debug/iKofi-*-debug.apk"
