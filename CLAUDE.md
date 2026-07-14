# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**iKofi** — An Android app that bundles a full SSH server (Dropbear), SFTP server (from OpenSSH), rsync, and scp. It runs as a foreground Android service for persistent background operation. The app ID is `com.ikofi.sshd`, currently at version 0.1 (code 1).

- **Min SDK**: 30 (Android 11) | **Target/Compile SDK**: 36 (Android 16)
- **JDK**: 21 | **AGP**: 9.2.1 | **NDK**: 30.0.14904198 | **CMake**: 4.1.2
- **Gradle**: configuration cache + build caching enabled
- **Language**: Java (Android layer) + C (native services via JNI)
- **UI languages**: de, en, fr, ta, zh_CN (explicitly configured via `resourceConfigurations`)

## Build Commands

```bash
# Release build (requires ANDROID_HOME set)
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleRelease

# Debug build (arm64-v8a only, faster):
./gradlew assembleDebug

# Debug build (arm64-v8a only):
./gradlew assembleDebug

# Run Android instrumentation tests (on emulator/device):
./gradlew connectedDebugAndroidTest

# Run a single test class:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ikofi.sshd.UserPasswordTest

# Run an individual test method:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ikofi.sshd.UserPasswordTest#pw

# Clean:
./gradlew clean
```

Run `./setup.sh` to install dependencies (JDK 21, Android SDK, NDK, CMake). You also need `ANDROID_HOME` set and a `local.properties` file with `sdk.dir=$ANDROID_HOME`. The build has two prebuild steps that run before CMake to generate required headers.

## Architecture

### Three-layer design

```
┌──────────────────────────────────────────────────────────┐
│  Java/Android Layer                                      │
│  App → MainActivity → MainFragment → SshdService         │
│                      SettingsFragment → SettingsViewModel │
│  ServiceViewModel polls dropbear.err → updates UI (LiveData) │
└───────────────────────┬──────────────────────────────────┘
                        │ JNI
┌───────────────────────▼──────────────────────────────────┐
│  JNI Bridge (jni-dropbear.c)                              │
│  - forks dropbear_main() as child process                 │
│  - provides auth hooks (password file, pubkey, single-use)│
│  - maps executables: scp→libscp.so, rsync→librsync.so,    │
│    sftp-server→libsftp-server.so                          │
│  - waitpid() watchdog in Java for auto-restart            │
└──────┬──────────┬──────────┬──────────────────────────────┘
       │          │          │
┌──────▼──┐ ┌─────▼────┐ ┌──▼───────────┐
│ Dropbear│ │ OpenSSH  │ │ rsync        │
│ (sshd)  │ │ (sftp)   │ │ (rsync)      │
│ (scp)   │ │          │ │              │
├─────────┤ ├──────────┤ ├──────────────┤
│libtom-  │ │libcrypto │ │ zlib (int.)  │
│crypt    │ │(internal)│ │ popt         │
│libtom-  │ │          │ │              │
│math     │ │          │ │              │
└─────────┘ └──────────┘ └──────────────┘
```

### Key source files

| File | Purpose |
|------|---------|
| `app/src/main/java/.../sshd/` | |
| `SshdService.java` | Android foreground service; manages native process lifecycle (fork/kill/watchdog restart) with single-thread executor for waitpid |
| `MainActivity.java` | Entry point; handles Intent-based start/stop (`adb shell am start -a ...fg.START`) |
| `MainFragment.java` | Primary UI: start/stop button, log viewer, address list, TV remote mode with dedicated button layout |
| `ServiceViewModel.java` | Polls dropbear.err log file via background thread, exposes LiveData for UI updates |
| `SshdSettings.java` | Config directory management (host keys, authorized_keys, master_password), SHA-512 hashing, network interface enumeration |
| `settings/Prefs.java` | Central SharedPreferences accessor (port, auth flags, env vars, cmdline options, bindings parser) |
| `settings/SettingsFragment.java` | Settings UI: port validation, username/password with EncryptedSharedPreferences, boot/foreground toggles, theme switcher, auth method warnings |
| `settings/UserPassStorage.java` | Custom PreferenceDataStore wrapping EncryptedSharedPreferences for password persistence |
| `settings/SettingsViewModel.java` | Settings state; validates at least one auth method is enabled before allowing back navigation |
| `settings/ExtPreferenceCategory.java` | Custom preference category with divider drawable |
| `App.java` | Application subclass; initializes night mode from saved preference |
| `BootReceiver.java` | BOOT_COMPLETED / MY_PACKAGE_REPLACED receiver; starts service if run-on-boot is enabled |
| `SshdTileService.java` | Quick Settings tile for toggling the service |
| `StartMode.java` | Enum: ByUser (manual), ByIntent (external app/adb), OnBoot (device start) |
| `util/theme/NightMode.java` | Theme mode management (follow device / light / dark) |
| `app/src/main/cpp/` | |
| `jni-dropbear.c` / `.h` | JNI bridge: fork+exec dropbear, authentication callbacks (pubkey, password file, single-use), executable path remapping for scp/rsync/sftp-server |
| `prebuild-dropbear.sh` | Regenerates `default_options_guard.h` via `ifndef_wrapper.sh` if `default_options.h` changed |
| `prebuild-rsyn.sh` | Generates rsync headers: help text from markdown, function prototypes, daemon parameter table, default ignore/compress lists |
| `CMakeLists.txt` | Builds 4 binary targets from 722 source files across tommath, tomcrypt, dropbear, OpenSSH, rsync, and the JNI bridge |
| `dropbear/` | Vendored Dropbear SSH server (with libtomcrypt + libtommath) |
| `openssh/` | Vendored OpenSSH sftp-server |
| `rsync/` | Vendored rsync (with internal zlib + popt) |

### Authentication flow

1. Java calls `start_sshd()` JNI, which `fork()`s a child running `dropbear_main()`
2. Dropbear calls `ikofi_svr_authinitialise()` to configure auth types based on JNI flags
3. Supported auth methods (togglable in Settings):
   - **Public key** (`authorized_keys` file) — requires the file to exist and contain ≥10 chars
   - **Fixed user/password** (`master_password` file with `user:SHA-512-base64(password)`)
   - **Single-use passwords** (auto-generated 8-char passwords shown in the log)
4. For fixed user/password auth: the SHA-512 hash of the stored password is pre-loaded as the expected value. The incoming password is SHA-512'd server-side by the JNI bridge; Dropbear's standard `strcmp` compares the two hashes.

### Key architectural patterns

- **Native process lifecycle**: `fork()` in JNI → child runs dropbear → Java `waitpid()` watchdog restarts if process dies (unless it crashed twice within 10 seconds)
- **Executable mapping**: The JNI bridge intercepts `exec()` calls for `scp`, `rsync`, and `sftp-server` and redirects them to the bundled `.so` executables via `ikofi_exe_to_lib()`
- **Auth hooks**: Dropbear's auth system is extended via global callback functions defined in `jni-dropbear.c` (compile-time `IKOFI_EXTEND_AUTHENTICATION` define)
- **Settings persistence**: Uses AndroidX Preference with `EncryptedSharedPreferences` for the password field (via `UserPassStorage`)
- **Log monitoring**: `ServiceViewModel` polls `dropbear.err` every 2 seconds via a single-thread executor; no Android logging facility used natively
- **Build type hierarchy**: `abstractRelease` (minify+shrink) → `release` and `debug` — both build only `arm64-v8a`
- **Signing**: Release signing reads from an external properties file via `-DiKofi.properties` Gradle property (not committed); gracefully skips signing when absent
- **jniLibs must be extracted**: `useLegacyPackaging = true` in build.gradle so native libs are available on filesystem for `execv()` calls

## Testing

- All tests are **Android instrumentation tests** (run on emulator/device via `connectedCheck`)
- Tests are in `app/src/androidTest/java/com/ikofi/sshd/`
- Two test files:
  - `UserArgsTest.java` — tests command-line option parsing (`Prefs.splitOptions`, `collectBindings`)
  - `UserPasswordTest.java` — tests password file read/write/hash round-trip via `SshdSettings`
- No unit tests (`src/test/` is absent), no mocking framework used
- For manual testing: `adb forward tcp:2223 tcp:2222` then `ssh -p 2223 localhost` (see `TESTING-ON-WINDOWS.md`)

## Native Code Modification Conventions

- All required changes in vendored C code are flagged with `IKOFI_REQUIRED_CHANGE` comments
- Extension code is guarded by `IKOFI_EXTEND_AUTHENTICATION` compile-time definitions
- Upgrading vendored upstream packages (Dropbear, OpenSSH, rsync) is a complex manual process; see the git history of the original Sshd4a project for guidance.
