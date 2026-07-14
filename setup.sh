#!/bin/bash
# iKofi dependency installer
# Installs JDK 21, Android SDK, NDK, and CMake for building.
# Tested on Debian, Fedora, and macOS (Homebrew).
#
# After running, make sure JAVA_HOME points to JDK 21.
# On macOS, add to your shell profile:
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
# Or just let `make` auto-detect it.

set -euo pipefail

echo "==> iKofi dependency setup"
echo ""

OS="$(uname -s)"
errors=0

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
                return 1
            fi
            ;;
        Darwin)
            if ! command -v brew &>/dev/null; then
                echo "Homebrew is required. Install from https://brew.sh"
                return 1
            fi
            # Install JDK 21 if missing.
            if [ ! -f /opt/homebrew/opt/openjdk@21/bin/java ]; then
                echo "Installing openjdk@21..."
                brew install openjdk@21
            else
                echo "  [✓] openjdk@21 already installed"
            fi
            brew install wget
            ;;
        *)
            echo "Unsupported OS: $OS"
            return 1
            ;;
    esac
}

# ── Android SDK ────────────────────────────────────────────────────────────

install_android_sdk() {
    if [ -z "${ANDROID_HOME:-}" ]; then
        ANDROID_HOME="$HOME/android-sdk"
        echo "ANDROID_HOME not set, defaulting to $ANDROID_HOME"
        echo "Add this to your shell profile:"
        echo "  export ANDROID_HOME=$ANDROID_HOME"
    fi

    mkdir -p "$ANDROID_HOME"

    # Install cmdline-tools if not present
    if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        echo "Downloading Android command-line tools..."
        cd "$ANDROID_HOME/cmdline-tools"

        case "$OS" in
            Darwin)
                URL="https://dl.google.com/android/repository/commandlinetools-mac-14742923_latest.zip"
                ;;
            *)
                URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
                ;;
        esac

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

# ── Verification ───────────────────────────────────────────────────────────

verify_jdk() {
    local java_home

    # Try known JDK 21 paths first.
    if [ -f /opt/homebrew/opt/openjdk@21/bin/java ]; then
        java_home=/opt/homebrew/opt/openjdk@21
    elif [ -f /usr/lib/jvm/java-21-openjdk/bin/java ]; then
        java_home=/usr/lib/jvm/java-21-openjdk
    elif [ -n "${JAVA_HOME:-}" ]; then
        java_home="$JAVA_HOME"
    else
        java_home=""
    fi

    if [ -n "$java_home" ] && [ -f "$java_home/bin/java" ]; then
        jdk_ver=$("$java_home/bin/java" -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\)\.[0-9]*\.[0-9]*.*/\1/')
        if [ "$jdk_ver" = "21" ]; then
            echo "  [✓] JDK 21  at $java_home"
            if [ -z "${JAVA_HOME:-}" ] || [ "$JAVA_HOME" != "$java_home" ]; then
                echo ""
                echo "  NOTE: JAVA_HOME is not set to JDK 21."
                echo "  Add this to your shell profile (~/.zshrc, ~/.bashrc, etc.):"
                if [ "$OS" = "Darwin" ]; then
                    echo "    export JAVA_HOME=/opt/homebrew/opt/openjdk@21"
                else
                    echo "    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk"
                fi
                echo "  Or simply use 'make' — it auto-detects JDK 21."
            fi
            return 0
        else
            echo "  [✗] Found JDK $jdk_ver at $java_home (need 21)"
            return 1
        fi
    else
        echo "  [✗] JDK 21 not found"
        echo "  Install it manually or re-run this script."
        return 1
    fi
}

verify_sdk() {
    local sdk_dir="${ANDROID_HOME:-$HOME/android-sdk}"

    if [ -f "$sdk_dir/platforms/android-36/android.jar" ]; then
        echo "  [✓] Android SDK 36"
    else
        echo "  [✗] Android SDK 36 missing — run setup again"
        return 1
    fi

    if [ -d "$sdk_dir/ndk/30.0.14904198" ]; then
        echo "  [✓] NDK 30.0.14904198"
    else
        echo "  [✗] NDK 30.0.14904198 missing — run setup again"
        return 1
    fi

    if [ -f "$sdk_dir/cmake/4.1.2/bin/cmake" ]; then
        echo "  [✓] CMake 4.1.2"
    else
        echo "  [✗] CMake 4.1.2 missing — run setup again"
        return 1
    fi
}

# ── Main ───────────────────────────────────────────────────────────────────

install_system_packages
install_android_sdk
setup_local_properties
run_prebuild

echo ""
echo "==> Verification"
echo ""
verify_jdk || errors=$((errors+1))
verify_sdk || errors=$((errors+1))

echo ""
if [ "$errors" -gt 0 ]; then
    echo "==> Setup finished with $errors error(s)."
    exit 1
fi

echo "==> Setup complete!"
echo ""
echo "    Build with:  make          (or ./gradlew assembleDebug)"
echo "    Install:     make install  (or adb install -r app/build/outputs/apk/debug/iKofi-*-debug.apk)"
echo "    SSH in:      make adb-forward && ssh -p 2223 localhost"
