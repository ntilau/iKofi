# iKofi Makefile
# Wraps the Gradle build system and common Android development tasks.
# See CLAUDE.md for full project documentation.
#
# Quick start:
#   make           Build debug APK
#   make install   Build + install on device
#   make adb-forward  Forward port 2223→2222 for SSH testing

GRADLEW  := ./gradlew
APK_DIR  := app/build/outputs/apk/debug
APK_NAME := iKofi-*-debug.apk
APP_ID   := com.ikofi.sshd
APP_ID_DEBUG := $(APP_ID).debug

# ── JDK 21 detection ──────────────────────────────────────────────
# iKofi requires JDK 21 for the build toolchain.  Try to locate it
# automatically so callers don't need to set JAVA_HOME manually.

ifeq ($(shell uname -s),Darwin)
  # Homebrew installs JDK 21 in a versioned keg.
  JDK21_HOMEBREW := /opt/homebrew/opt/openjdk@21
  ifneq ($(wildcard $(JDK21_HOMEBREW)/bin/java),)
    JAVA_HOME_21 := $(JDK21_HOMEBREW)
  endif
else ifeq ($(shell uname -s),Linux)
  # update-java-alternatives or manual install.
  JDK21_UPDATE := /usr/lib/jvm/java-21-openjdk
  ifneq ($(wildcard $(JDK21_UPDATE)/bin/java),)
    JAVA_HOME_21 := $(JDK21_UPDATE)
  endif
endif

# Prefer a found JDK 21, else fall back to whatever JAVA_HOME says.
ifdef JAVA_HOME_21
  JAVA_HOME := $(JAVA_HOME_21)
else
  ifndef JAVA_HOME
    JAVA_HOME := /usr
  endif
endif

export JAVA_HOME

# ── Default goal ──────────────────────────────────────────────────
.DEFAULT_GOAL := debug

.PHONY: all debug release install test clean prebuild \
        setup adb-forward adb-forward-list uninstall \
        env check version help

# ── Prerequisite checks ───────────────────────────────────────────

check:
	@echo "=== iKofi environment check ==="
	@errors=0; \
	\
	if [ -z "$(ANDROID_HOME)" ]; then \
		echo "  [✗] ANDROID_HOME  not set"; \
		errors=$$((errors+1)); \
	else \
		echo "  [✓] ANDROID_HOME  $(ANDROID_HOME)"; \
	fi; \
	\
	jdk_line=$$("$(JAVA_HOME)/bin/java" -version 2>&1 | head -1); \
	jdk_ver=$$(echo "$$jdk_line" | sed 's/.*"\([0-9]*\)\.[0-9]*\.[0-9]*.*/\1/'); \
	if [ "$$jdk_ver" = "21" ]; then \
		echo "  [✓] JDK 21        $(JAVA_HOME)"; \
	elif echo "$$jdk_line" | grep -q "version"; then \
		echo "  [✗] JDK 21        found JDK $$jdk_ver at $(JAVA_HOME) (need 21)"; \
		errors=$$((errors+1)); \
	else \
		echo "  [✗] JDK 21        not found at $(JAVA_HOME)"; \
		errors=$$((errors+1)); \
	fi; \
	\
	if command -v adb >/dev/null 2>&1; then \
		echo "  [✓] adb           $$(adb --version 2>&1 | head -1)"; \
	else \
		echo "  [ ] adb           not in PATH (needed for install/test)"; \
	fi; \
	\
	if [ -f local.properties ]; then \
		echo "  [✓] local.properties"; \
	else \
		echo "  [ ] local.properties  missing — run \`make setup\`"; \
	fi; \
	\
	echo ""; \
	if [ "$$errors" -gt 0 ]; then \
		echo "  $$errors error(s) found.  Run \`make setup\` to install."; \
		exit 1; \
	else \
		echo "  All good!"; \
	fi

check-env:
ifndef ANDROID_HOME
	$(error ANDROID_HOME is not set. Run `make setup` or `export ANDROID_HOME=...`)
endif
	@jdk_line=$$("$(JAVA_HOME)/bin/java" -version 2>&1 | head -1); \
	jdk_ver=$$(echo "$$jdk_line" | sed 's/.*"\([0-9]*\)\.[0-9]*\.[0-9]*.*/\1/'); \
	if [ "$$jdk_ver" != "21" ]; then \
		echo "  ERROR: JAVA_HOME ($(JAVA_HOME)) points to JDK $$jdk_ver (need 21)"; \
		echo "  Install: brew install openjdk@21"; \
		echo "  Or let make auto-detect it."; \
		false; \
	fi

# ── Build targets ─────────────────────────────────────────────────

all: debug

debug: check-env
	$(GRADLEW) assembleDebug

release: check-env
	$(GRADLEW) assembleRelease

install: check-env
	$(GRADLEW) assembleDebug
	adb install -r $(APK_DIR)/$(APK_NAME)

# Show path to the latest debug APK (useful for scripts / CI).
apk-path:
	@ls -t $(APK_DIR)/$(APK_NAME) 2>/dev/null | head -1

# ── Testing ───────────────────────────────────────────────────────

test: check-env
	$(GRADLEW) connectedDebugAndroidTest

test-class: check-env
	$(GRADLEW) connectedDebugAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.class=$(CLASS)

test-method: check-env
	$(GRADLEW) connectedDebugAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.class=$(CLASS)#$(METHOD)

# ── Lint ──────────────────────────────────────────────────────────

lint: check-env
	$(GRADLEW) lintDebug

# ── Clean ─────────────────────────────────────────────────────────

clean:
	$(GRADLEW) clean

# ── Prebuild header generation ─────────────────────────────────────

PREBUILD_DROPBEAR := app/src/main/cpp/prebuild-dropbear.sh
PREBUILD_RSYNC    := app/src/main/cpp/prebuild-rsyn.sh

prebuild:
	@echo "==> Generating dropbear default_options_guard.h..."
	chmod +x $(PREBUILD_DROPBEAR)
	cd app && sh src/main/cpp/prebuild-dropbear.sh
	@echo "==> Generating rsync headers..."
	chmod +x $(PREBUILD_RSYNC)
	cd app && sh src/main/cpp/prebuild-rsyn.sh
	@echo "==> Prebuild complete."

# ── Setup (dependencies) ───────────────────────────────────────────

setup:
	./setup.sh

setup-first: setup debug
	@echo ""
	@echo "Build complete. Install with:"
	@echo "  adb install -r $(APK_DIR)/$(APK_NAME)"

# ── adb helpers ───────────────────────────────────────────────────

adb-forward:
	adb forward tcp:2223 tcp:2222

adb-forward-list:
	adb forward --list

uninstall:
	-adb uninstall $(APP_ID)
	adb uninstall $(APP_ID_DEBUG)

# ── Info ──────────────────────────────────────────────────────────

version:
	@echo "iKofi v$$(grep 'applicationVersionName' build.gradle | sed 's/.*= //; s/"//g')"
	@echo "App ID:        $(APP_ID)"
	@echo "App ID (debug): $(APP_ID_DEBUG)"
	@echo "APK:           $(APK_DIR)/$(APK_NAME)"

env:
	@echo "ANDROID_HOME=$(ANDROID_HOME)"
	@echo "JAVA_HOME=$(JAVA_HOME)"
	@echo "JDK version: $$("$(JAVA_HOME)/bin/java" -version 2>&1 | head -1)"
	@echo "SDK dir:     $$(grep 'sdk.dir' local.properties 2>/dev/null || echo 'not set')"
	@echo "NDK:         $$($(ANDROID_HOME)/ndk/30.0.14904198/ndk-build --version 2>/dev/null || echo ndk-30)"
	@$(GRADLEW) --version 2>/dev/null | head -5

# ── Help ──────────────────────────────────────────────────────────

help:
	@echo "iKofi — Android SSH/SFTP/rsync server"
	@echo ""
	@echo "Usage:  make <target>"
	@echo ""
	@echo "Build:"
	@echo "  debug (default)  Debug APK (arm64-v8a)"
	@echo "  release          Release APK (needs signing key)"
	@echo "  clean            Remove build output"
	@echo ""
	@echo "Install / Run:"
	@echo "  install          Build + install on connected device"
	@echo "  uninstall        Remove app from device"
	@echo "  adb-forward      Forward host:2223 -> device:2222"
	@echo ""
	@echo "Test:"
	@echo "  test             All instrumentation tests"
	@echo "  test-class       Single class:  CLASS=<FQN>"
	@echo "  test-method      Single method: CLASS=<FQN> METHOD=<name>"
	@echo ""
	@echo "Maintenance:"
	@echo "  setup            Install JDK, SDK, NDK, CMake"
	@echo "  setup-first      Full setup + first debug build"
	@echo "  prebuild         Regen C headers (dropbear, rsync)"
	@echo "  lint             Run Android lint (debug)"
	@echo ""
	@echo "Info:"
	@echo "  check            Verify all dependencies"
	@echo "  env              Show build environment"
	@echo "  version          Show app version"
	@echo "  apk-path         Show path to latest APK"
	@echo "  help             This message"
